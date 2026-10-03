# GameTest 车道指南

> 本文回答两件事：`gradlew runGameTestServer` 到底跑起来的是什么，以及这条车道在坐标系上受什么限制。
> 结论与坐标以 1.21.1 / NeoForge 21.1.219 的 `neoforge-21.1.219-sources.jar` 与 `build\moddev\artifacts\neoforge-21.1.219-merged.jar` 为准（两份 jar 都在 `build/moddev/artifacts`，可用 `gradlew prepareGameTestServerRun` 重新生成）。

## 1. 一句话

`runGameTestServer` 起的是一个**真的 `MinecraftServer` 子类**（`net.minecraft.gametest.framework.GameTestServer`），跑一个**固定种子的超平坦世界**，把每个 `@GameTest` 方法当一条用例、以**结构方块**为单位摆在一张网格上执行，全跑完自动退出。它没有客户端、没有渲染、没有真实玩家连接。

## 2. 启动与结束

| 环节 | 行为 | 坐标 |
|------|------|------|
| 谁创建它 | NeoForge 给服务端启动加了 GameTest 分支：`neoforge.gameTestServer` 为真时注册用例并直接起 `GameTestServer`，普通世界加载那条路不执行 | `net.minecraft.server.Main#main` |
| 何时注册用例 | 同一个分支里调 `GameTestHooks.registerGametests()`；扫描 `@GameTestHolder` 类，再按 `neoforge.enabledGameTestNamespaces` 过滤 | `net.neoforged.neoforge.gametest.GameTestHooks#registerGametests` |
| 本仓库的开关怎么给 | `gameTestServer` run 里 `systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id` | `build.gradle` |
| 世界形态 | 世界预设 `WorldPresets.FLAT`、`WorldOptions(0L, false, false)` | `net.minecraft.gametest.framework.GameTestServer#WORLD_OPTIONS` |
| 游戏规则 | 关掉怪物生成、天气循环、随机刻（0）、火焰蔓延 | `net.minecraft.gametest.framework.GameTestServer#TEST_GAME_RULES` |
| 退出 | 用例全跑完就 `halt`，随后 `System.exit(失败的必要用例数)`；崩溃走 `System.exit(1)` | `net.minecraft.gametest.framework.GameTestServer#onServerExit` |
| 判定成败 | 日志出现 `All N required tests passed`，且进程退出码为 0 | `net.minecraft.gametest.framework.GameTestServer#tickServer`、`net.minecraft.gametest.framework.GameTestServer#onServerExit` |

- 游戏模式 CREATIVE、难度 NORMAL、`isPublished()` 为假。`onServerCrash` 会把崩溃报告写成 `crash-<时间>-server.txt`（工作目录是 `run/`）。
- **退出码语义要留意**：它是「失败的必要用例条数」，不是 0/1。日志里 `All 1 required tests passed` 与退出码 0 是同一件事的两种说法。

## 3. 一条用例的完整生命周期

```
GameTestServer.initServer()            加载超平坦主世界，按 batch 把用例切块（每块 ≤ 50 条）
  └─ GameTestServer.tickServer()       服务端第一个 tick 调 startTests()   ← 原点是随机的，见 §4
       └─ GameTestRunner.start()
            └─ runBatch(i)             对每条用例：
                 createStructuresForBatch → StructureGridSpawner.spawnStructure
                   ① 记下网格格位 setNorthWestCorner
                   ② prepareTestStructure：清空并强制加载该格位所在区块，
                      放结构方块 + 命令方块 + 石按钮，四周与顶面用屏障包起来
                   ③ GameTestTicker.SINGLETON.add(info)   每 tick 由 MinecraftServer 推进
                        └─ GameTestInfo.tick()
                             等到该区块实体可 tick（最多 20 tick）→ 结构方块 load 场地模板
                             → 按钮被按下 → 命令方块执行 test runclosest → 方法体被调用
                             → succeed() 或抛异常 → 汇报 → 下一个 batch
```

执行驱动的两个细节值得记住：

- **方法体不是被框架直接调用的**，而是由场地里那个命令方块的 `test runclosest` 触发的。这也是为什么场地模板必须**事先存在于资源里**：`GameTestRunner#createStructuresForBatch` 读模板的时点早于同批次的 `@BeforeBatch` 函数，缺模板会抛 `IllegalStateException: Missing test structure`（同一异常在 `StructureUtils#prepareTestStructure` 与 `GameTestInfo#prepareTestStructure` 两处都会出现）。本项目模板由 `gradlew generateGameTestStructure` 生成（`src/test/java/io/github/sweetzonzi/arms_core/tools/GenerateGameTestStructure.java`），落在 `src/main/resources/data/arms_core/structure/empty_platform.nbt`——目录名是**单数** `structure`（`net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager#STRUCTURE_RESOURCE_DIRECTORY_NAME`）。
- **驱动在服务端 tick 里**：`MinecraftServer.tickServer` 在 `GameTestHooks.isGametestEnabled()` 为真时调 `GameTestTicker.SINGLETON.tick()`。因此 GameTest 方法体跑在**服务端主线程**，与物理线程不是同一条；断言「物理步进几帧后的结果」需要跨越线程与 tick，不能在同一调用栈里读。

## 4. 场地坐标系：随机原点

`GameTestServer.startTests` 取原点的唯一方式：

```java
BlockPos blockpos = new BlockPos(
    p_177625_.random.nextIntBetweenInclusive(-14999992, 14999992), -59,
    p_177625_.random.nextIntBetweenInclusive(-14999992, 14999992));
```

`p_177625_.random` 是 `Level#random`（`net.minecraft.world.level.Level` 的字段 `public final RandomSource random = RandomSource.create()`，无参创建即取 JVM 随机数做种），**不受世界种子的 0L 影响**。NeoForge 给服务端启动只加了 `--spawnPos`（`net.minecraft.server.Main#main`，默认 `(0, 60, 0)`），它写的是 `setDefaultSpawnPos`，**不参与测试网格的定位**；没有系统属性或命令行参数能覆盖上面这个随机原点。

**实测**：`run/logs` 里保留下来的历次 GameTest 启动共 32 个互不相同的原点，覆盖 X ∈ [−14 957 071, 13 836 904]、Z ∈ [−13 741 658, 13 715 547]，Y 恒为 −59（超平坦预设的地面）。每一次都长这样：

```
[04 10月 2026 05:49:09.813] [Server thread/INFO] [net.minecraft.gametest.framework.GameTestServer/]: 1 tests are now running at position 9201004, -59, -6143586!
```

由此得到的布局与坐标语义：

| 项 | 值 | 出处 |
|----|----|------|
| 原点 Y | −59 恒定 | `GameTestServer#startTests` 的字面量 |
| 每行格数 | 8 | `GameTestRunner#DEFAULT_TESTS_PER_ROW`，经 `GameTestServer#startTests` 的 `new StructureGridSpawner(blockpos, 8, false)` 传入 |
| 同排横向间隔 | 场地宽度 + 5 | `StructureGridSpawner#SPACE_BETWEEN_COLUMNS` |
| 排间距 | 场地进深 + 6 | `StructureGridSpawner#SPACE_BETWEEN_ROWS` |
| 场地本体（本项目） | 5×3×5 石砖地板 | `src/test/java/io/github/sweetzonzi/arms_core/tools/GenerateGameTestStructure.java` |
| 场地落点 | 原点即第 1 个格位的西北角，结构方块在它下方 1 格 | `StructureGridSpawner#spawnStructure` 与 `StructureUtils#createStructureBlock` |
| 相对坐标换算 | `结构方块坐标 + 相对坐标`，再按 `rotationSteps` 旋转 | `GameTestHelper#absolutePos`、`GameTestHelper#absoluteVec` |
| 每个 batch 一格新网格 | 是，但**原点相同** | `GameTestServer#startTests` 只取一次原点，`GameTestRunner#runBatch` 每次 `onBatchStart` 只重置游标 |

`rotationSteps` 在 GameTestServer 路径上恒为 0（`GameTestBatchFactory#toGameTestInfo` 传 `p_320796_ = 0`），所以场地朝向不随运行变化。

**用例能看到的是相对量。** `helper.absolutePos(...)` 返回的是「随机原点 + 相对量」，因此断言里用绝对坐标比较只在同一进程内自洽，跨运行不可复现。

## 5. 这条车道的三条硬限制

### 5.1 随机原点 → 不可复现

每次启动换一个位置（取值范围 ±14 999 992 格，约合 ±117 187 个区块）。随之变化的是：该处由世界种子推导出的生物群系与「地表」构成、区块内的装饰，以及场地相对区块边界的对齐。**任何断言依赖绝对坐标、固定区块或固定生物群系的用例都会随机漂移。**

### 5.2 单精度物理 → 亚米级量不出来

物理链路把世界坐标转成 float32：`PhysicsHelper.kt#toBVector3f` 把 `Vec3` 的 double 分量压成 jme3 的 `Vector3f`；物理侧存的就是它（`PhysicsCollisionObject.java#getPhysicsLocation` 返回 `Vector3f`，`PhysicsCharacter.java#setPhysicsLocation` 收 `Vector3f`，这两个类来自 Spark-Core 源码里内嵌的 jme3 分支）。Arm 侧的写入点是 `MechaCharacter#warp`（重写 `PhysicsCharacter#warp`，仍收 `Vector3f`）。Gradle 缓存里解析到的 native 制品只有单精度那一支（`com.github.stephengold:Libbulletjme-Windows64:22.0.3` 的 `SpMtRelease` 变体）；Libbulletjme 的双精度变体以 `Dp` 标记，与 `Sp`/`SpMt` 相对（判据见 `Libbulletjme/src/test/java/Utils.java#loadNativeLibrary`），本仓库没有引入它，它提供的 `getPhysicsLocationDp` / `setPhysicsLocationDp` 也不在链路上。运行期可用 `NativeLibrary#isDoublePrecision` 复核实际加载到的是哪一支。

float32 在整数坐标上的可表示间隔（ULP）：

| |坐标| 区间 | ULP |
|-----------|------|-----|
| < 8 388 608（2²³） | | ≤ 0.5 格 |
| 8 388 608 ~ 16 777 216（2²⁴） | | 1 格 |
| ≥ 16 777 216（2²⁴） | | ≥ 2 格 |

随机原点的取值范围是 ±14 999 992，即**每一次运行的落点都落在「1 格一跳」的区间**里，其中约 11% 的运行还会越过 2²⁴ 进到「2 格一跳」。后果：

- 传入的亚米级位移在写入物理空间的瞬间就被量化掉，`getPhysicsLocation` 读回的值只能落在 1 格（或 2 格）的栅格上。
- 依赖位移、速度、地面射线、台阶、碰撞接触点、越障阈值的断言，在这条车道上**量不出真实数值**（量到的是量化误差，不是物理结果）。
- Y 轴不受影响：这些量在 −60 附近，ULP 是 2⁻¹⁷ 量级。

### 5.3 一个世界、一张网格，不是「每条用例一个测试世界」

用例之间共享同一个超平坦世界。框架给每个格位做的事是：清空该格位周围（含下方 3 格、上方 20 格、四面各 3 格）、放模板、用屏障围起侧面与顶面（除非 `skyAccess = true`）、并在用例结束时丢弃该场地内的非玩家实体（`GameTestInfo#succeed`）。

因此**跨用例的污染是可能的**：别的格位上正在跑的用例、强制加载的区块、被写坏的世界状态都可能被观察到。`helper.getLevel()` 拿到的是那个全局 `ServerLevel`，`helper.getLevel().getGameTime()`、时间、天气都是同一个。写用例时不要假设「世界是干净的」。

## 6. 想控制落点时可选的路

| 方案 | 落点 | 代价 |
|------|------|------|
| 现状：`gradlew runGameTestServer` | 随机 ±1.5e7 | 自动退出、退出码即结论；但 §5 的两条限制无法规避 |
| `runServer`（`neoforge.enableGameTest=true`）后用 `/test` 命令执行 | `TestCommand.createTestPositionAround` 取**命令来源的位置**（+3 格 Z、地表上方 1 格），控制台来源即世界出生点 | 同一套代码、同样的物理限制；但没有「跑完自动退出」，需要在控制台敲命令并解析日志，不能当 CI 门禁 |
| `runClient`（已开 `neoforge.enabledGameTestNamespaces`）后用 `/test run` | 与 `/test` 同一套规则：取命令来源的位置；由玩家执行时即该玩家所在处 | 要人盯着，且需要进世界 |
| 自己写 Mixin/Java agent 替换 `GameTestServer#startTests` 的原点 | 任意 | 改的是 MC 内部行为，NeoForge 版本一变就碎；不值得为了跑测试引入 |

**结论**：想在原点附近（ULP ≤ 0.5 格）跑物理量，只能走 `/test` 命令那条路（世界出生点可定），并自己承担「无自动退出码」的编排成本。为此改 GameTestServer 的源码不值得。

## 7. 本仓库现在的用法与边界

- 用例一：`src/main/java/io/github/sweetzonzi/arms_core/common/gametest/ArmsCoreGameTest.java#physicsSpaceIsReadyAndCoreConstructs`。它只断言四件事：服务端构造成功、拿到权威实例与 KCC、装配体持有位移摄入状态、物理步长是有限正数。**这四条都与坐标无关**，因此随机原点不影响它。
- 用例二：同类的 `#intakeClassifiesMirrorsAndAdoptsOnlyRealDisplacement`。它绑一个 `GameTestHelper#makeMockPlayer` 到装配体，然后在作用域内外直接调 `Entity#setPos`，断言位置写入的分类（三类已知镜像不摄入、其中第 2/4 类推进锚点、第 1 类不推进、栈空且目标 ≠ 锚点才采纳一次、重复写入不重复采纳）。**这些断言也是纯逻辑**：作用域深度、锚点的三个 double、已采纳次数，用的都是整数级坐标（`0.5`、`40.0`），与 float32 的量化步长无关；量不了的只是「KCC 有没有落到目标」。
- 批次名必须自成一档（`ArmsCoreGameTest#BATCH = "armsCore"`）：每个批次至多一个 `@BeforeBatch`，兄弟仓库 BallisticsFramework 的用例占用 `defaultBatch`，撞上会让服务端在注册阶段直接失败。
- 该车道**不适合**承载的东西：位移量本身（`/tp`、末影珍珠、紫颂果落在哪）、KCC 落地与越障、速度积分、闪避冲量、跳跃继承——全部是 §5.2 那一类。这些走 `gradlew test` 的 JUnit 车道（`MechaCharacterWalkPhysicsTest`、`MechaCharacterStepTest` 在裸 `PhysicsSpace` 里按 100 Hz 步进，坐标在原点附近）。
- 适合继续加的：注册表与字段表一致性、载荷编解码、状态机图的纯逻辑、位移摄入的**判据**（分类与锚点），以及「物理空间就绪」这类构造性断言；真实 `ServerLevel` + 真物理线程 + 真 tick 循环这一层，用它验证构造、接线与判据，而不是验证数值。

## 8. 常用命令与核对点

```powershell
# 跑完整 GameTest 车道（工作目录 run/，跑完自动退出）
.\gradlew runGameTestServer

# 只生成/更新到场地的启动参数（IDE 启动项依赖它）
.\gradlew prepareGameTestServerRun

# 重新生成场地模板（改了场地几何才需要）
.\gradlew generateGameTestStructure
```

- 运行后看 `run/logs/latest.log`：`N tests are now running at position ...` 给出本次随机原点；`All N required tests passed` 与进程退出码一起构成结论。
- GameTest 用例类必须放 `src/main/java/**/gametest/`（主源码集，因为 GameTestServer 扫描的是模组类路径）；纯逻辑测试放 `src/test/java/`。
- `@GameTest` 注解在 1.21.1 的字段是：`timeoutTicks`（默认 100）、`batch`（默认 `defaultBatch`）、`skyAccess`（默认 false）、`rotationSteps`、`required`（默认 true）、`manualOnly`、`templateNamespace`、`template`、`setupTicks`、`attempts`、`requiredSuccesses`（`net.minecraft.gametest.framework.GameTest`）。NeoForge 追加的只有类/方法级的 `@GameTestHolder` 与 `@PrefixGameTestTemplate`。
