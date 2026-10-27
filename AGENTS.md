# ARMS-Core 开发速查

> 本仓库是 Minecraft NeoForge 1.21.1 模组，Machine-Max 的附属模组，核心提供“机娘”角色运动控制器（`MechaControl` / `MechaCharacter`）。
> 与 `docs/` 中的设计文档互补：代码先读 `ARMS.java` / `ArmsCore.java` / `MechaControl.java` / `MechaCharacter.java`。

## 环境约束

- **Java 21**：`build.gradle` 的 `java.toolchain.languageVersion` 与 `src/main/resources/arms_core.mixins.json` 均要求 `JAVA_21`。
- **Gradle 8.8**：使用仓库自带 `gradlew` / `gradlew.bat`；Windows 下用 `gradlew` 而非 `gradle`。
- **Minecraft / NeoForge**：`minecraft_version=1.21.1`，`neo_version=21.1.219`（见 `gradle.properties`）。
- **模组 ID**：`arms_core`；入口类 `io.github.sweetzonzi.arms_core.ARMS`。

## 常用开发命令

```powershell
# 客户端运行（物理线程与 KCC 调试主要看这个）
.\gradlew --offline runClient

# 服务端运行
.\gradlew --offline runServer

# 数据生成（输出到 src/generated/resources）
.\gradlew --offline runData

# GameTest 服务器（真服务端；无需客户端，跑完自动退出）
.\gradlew --offline runGameTestServer

# 重新生成 GameTest 场地结构模板（改了场地几何后才需要）
.\gradlew --offline generateGameTestStructure

# 单元测试（纯逻辑层；不需要启动 Minecraft）
.\gradlew --offline test

# 构建产物（jar 在 build/libs）
.\gradlew --offline build

# 清理构建目录
.\gradlew --offline clean
```

- **命令一律带 `--offline`。** 本仓库的依赖已经全部落在本地 Gradle 缓存里，联网解析会白白花掉十几分钟：同一份 `.\gradlew test` 联网 **14 分 32 秒**、带 `--offline` **12～16 秒**，而编译与用例本身是秒级（联网那一轮的输出里 23 个任务全部 `UP-TO-DATE`，时间全在依赖解析）。只有确实要拉新依赖时才去掉它：升级 `neo_version` / `minecraft_version`、新增 Maven 依赖、或改了 `gradle.properties` 里的版本号。带 `--offline` 时若真有依赖缺失，Gradle 会直接报「No cached version available for offline mode」，不会静默地少解析一部分。

- 单元测试位于 `src/test/java/` 下：`common/control/`（`MechaControlTest`、`MechaControlClientTest`、`MechaCharacterFacingTest`、`RigidBodyControllerPrototypeTest`、`RigidBodyControllerMeasurementTest` 与四个状态机图测试）、`common/control/presentation/`（`MechaPresentationGraphsTest` 四张姿态图的转移与映射一致性、`MechaPresentationPresetTest` 枚举往返 / 下行写入 / 映射表对内容包的核对、`MechaMolangContextTest` 查询面取值与未发布时的默认值、布尔上下文里的绑定查询、`MechaPackMolangTest` 内容包 2323 条 molang 的有限性与不抛）、`common/control/attr/MechaBodyPresetTest`、`common/FlightAbilityModifierTest`、`client/ClientMechaAnchorTest`、`network/ARMSNetworkCodecTest`。全部为纯逻辑、不触碰 jme3 native，因此 `test` 可独立运行；`test` 已配置为 `useJUnitPlatform()` 并纳入 `check`（`build.gradle` 的 `test` 任务配置）。搭真实 `PhysicsSpace` 并按 100 Hz 步进的四条：`RigidBodyControllerPrototypeTest` 用**裸刚体**度量刚体路线的机制（锁转站立稳定性、撞墙归零、自写越障阈值、引擎摩擦标定、分轴锁转及换形/改质量后的保持、地面姿态控制方案对照、落地收敛的最小旋转与 `(0,1,0)` 对照、单轴初态朝向无偏与斜置初态朝向偏差的量级），数值以 `System.out` 输出供标定，结论见 `docs/角色控制器-刚体原型与引擎约束.md`；`RigidBodyControllerMeasurementTest` 用**正式控制器**量落地形态的数值（越障速率残留、每步 yaw 写入的步内偏差、起步头十步的加速度、前馈开关两档的稳态速率、越障高度契约），以输出数值为主、不做基线断言（例外是两条判据契约：越障高度契约判「一次越障的抬升量不超过步高」这条不变量本身，楼梯与坡那条判「判据认不认得出立面」——楼梯一级一级上去、坡一次都不触发），结论见 `docs/角色控制器-刚体动力学方案.md` §7.4、§12.9、§12.10 与 `docs/角色控制器-刚体原型与引擎约束.md` §11.6；`TerrainSeamContactTest` 量**地形拼缝**上的接触（裸圆柱与正式控制器两路 × 七档地形拼法 × 速度阶梯 × 一个步长的 12 个相位，另有圆柱底缘边距五档与地形盒边距四档两条阶梯；按水平顿挫触发打出踢起来那一步的全部接触的法线 / 距离 / 冲量；第 ④ 组量**接触修正**：门（本体参与 / 对方是地形刚体）× 写法（改竖直 / 吸最近轴 / 改距离丢弃 / 取消这条点）× 回调（只 conceived / 只 processed / 两个 / 步进之后）× 含不含贴着 × 深度三档与接触点 yaw 通道，外加三条危险车道（悬出棱线、拼缝一堵墙、半砖台阶）与站在拼缝上的静置，每档都报判据命中数；其中 `SECTION_PRODUCTION` 一档直接调生产判据本体），结论见同文 §11.10、§11.11。`MechaCharacterWalkPhysicsTest` 与 `MechaCharacterStepTest` 保留着 KCC 承载时期的基线，已标 `@Disabled`，等按同文 §11 的 P2 行重建。
- **测试类不得放在 `mixin` 包下**：`io.github.sweetzonzi.arms_core.mixin.*` 已被 `arms_core.mixins.json` 声明为 Mixin 独占包，其中的类不能被直接引用，放进去会在测试启动时报 `IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly`。
- 启动时工作目录是 `run/`，由 NeoForge MDK 自动生成；首次运行会下载 MC 资产。

### 两条测试车道

| 车道 | 命令 | 能覆盖什么 |
|------|------|-----------|
| JUnit | `.\gradlew --offline test` | 纯逻辑与力学：不需要活着的世界，可搭裸 `PhysicsSpace` 步进刚体控制器。十余秒到几分钟（搭空间并跑长跑的用例是分钟级） |
| GameTest | `.\gradlew --offline runGameTestServer` | 真 `ServerLevel` + 真物理线程 + 真服务端 tick 循环。当前两条探针：「物理空间就绪且 `ArmsCore` 构造得出来」，以及「位置写入分类与锚点判据」（镜像不摄入、只有栈空且目标 ≠ 锚点才采纳）；**它量不了位移与速度**——测试网格落在随机原点（±1.5e7）上，那里 float32 的量化步长是 1 格，原因与实测见 `docs/GameTest车道指南.md`。跑完自动退出，退出码是失败的必要用例条数 |

- GameTest 的用例类放 `src/main/java/**/gametest/`，用 `@GameTestHolder(ARMS.MOD_ID)` + `@PrefixGameTestTemplate(false)` + `@GameTest(template = "empty_platform", batch = ...)`；当前唯一一处是 `common/gametest/ArmsCoreGameTest.java`。**批次名必须自成一档**：框架要求每个批次至多一个 `@BeforeBatch`，而 `../BallisticsFramework` 的用例占用 `defaultBatch`，撞上会让服务端在注册阶段直接失败。
- **结构模板必须事先存在于资源里，不能用 `@BeforeBatch` 现搭**：框架在 `net/minecraft/gametest/framework/GameTestRunner.java` 的 `createStructuresForBatch` 里读模板，该时点早于同批次的批次函数（同文件下方几行），缺模板会抛 `IllegalStateException: Missing test structure`。模板由 `gradlew --offline generateGameTestStructure` 生成（源在 `src/test/java/**/tools/GenerateGameTestStructure.java`，走游戏自己的 NBT 序列化），产物是 `src/main/resources/data/arms_core/structure/empty_platform.nbt`。
- `.vscode/launch.json` 已有 `GameTestServer` 启动项，依赖 `build/moddev/gameTestServerRunProgramArgs.txt`（由 `gradlew --offline prepareGameTestServerRun` 生成）。

### 开发期 mod 目录（两个，都在本仓库根）

| 目录 | 放什么 | 挂载点 | 哪些 run 加载 |
|------|--------|--------|---------------|
| `mods/` | 双端都要：Curios、SuperbWarfare | 私有配置 `devMods` → main 的 `runtimeClasspath` | `runClient`、`runServer`、`runGameTestServer`、`runData` |
| `mods-client/` | 仅客户端：DistantHorizons、Iris、Sodium | 私有配置 `devClientMods` → `runClient` 任务的 `classpathProvider` | 只有 `runClient` |

- **jar 不入库**：两份目录都只提交各自的 `README.md`，`.gitignore` 忽略其中的 `*.jar`；clone 之后按 README 里的清单自行下载放置（版本以 README 为准）。目录为空或整个不存在时，对应的 run 只是少加载几个 mod。Machine-Max 用的是同一套目录布局与同两条挂载规则。
- **不要写进 `runtimeOnly`**：文件依赖没有坐标，会作为 root component 进入 `runtimeElements`，本项目被下游消费（或发布到 `repo/`）时会被一并带出去。`runtimeClasspath` 只用于解析，不属于任何 variant。
- **判据是 jar 在不在该 run 的 JVM 类路径上**。run 的类路径就是 main 的 `runtimeClasspath`：moddev 的 `RunGameTask.exec()` 执行 `classpath(getClasspathProvider())`，`classpathProvider` 由 `setupRunInGradle` 用 `sourceSet.runtimeClasspath` 填充。FML 的开发期 mod 发现读的也是这条类路径——`UserdevLocator` 用 `DevEnvUtils` 在系统类加载器上枚举 `META-INF/neoforge.mods.toml`，即 `java.class.path`。
- **不能挂 `<run>AdditionalRuntimeClasspath`**：那条配置只被 `WriteLegacyClasspath` 写进 `build/moddev/<run>LegacyClasspath.txt`，由 `BootstrapLauncher` 读取后建 MC-BOOTSTRAP 模块层，并用它取代 `java.class.path`；写在那里的 jar 不属于任何 run 的类路径，不会被当作 mod 扫描。本项目用的 moddev 2.0.141 把这条配置描述为「给 manifest 里没有 `FMLModType` 的普通库用的」（`VersionCapabilities.legacyClasspath()`）。
- **仅客户端的那批不能进 `mods/`**：`runServer` / `runGameTestServer` / `runData` 与 `runClient` 共用 main 的 `runtimeClasspath`。DistantHorizons 会在 `ServerAboutToStart` 里把服务器强转 `DedicatedServer`，而 `GameTestServer` 不是该类型，进程会在启动阶段以 `ClassCastException` 退出。
- **GeckoLib 与 Spark 不放 `mods/`**：它们的 jar 与其它来源的同名模块同时在类路径上时，ModLauncher 在模块解析阶段直接中止：
  `java.lang.module.ResolutionException: Modules geckolib and geckolib.neoforge export package … to module mixinextras.neoforge`。
  GeckoLib 由 Spark-Core 与 Machine-Max 各自的 Maven 依赖 `software.bernie.geckolib:geckolib-neoforge-<mc>:<geckolib_version>` 提供，不需要 jar；Spark 来自 Spark-Core 构建脚本里的 `implementation(files(fileTree("mods")))`，它作为 root component 进入 Spark-Core 的 `runtimeElements`，随复合构建出现在本项目的 `runtimeClasspath` 上（Mod List 里的 `spark 1.10.124` 就是它），本地再放一份同名模块就会撞包。
- **从 IDE 发起的 run 只带 `mods/`**：IDE 的类路径来自 Gradle 的 main `runtimeClasspath`（四个 run 共用一份），`runClient` 任务上的 `classpathProvider` 只有 `gradlew --offline runClient` 会走。`gradlew --offline createClientLaunchScript` 生成的启动脚本同理。
- **核对办法**：`run/logs/latest.log` 里 `ModDiscoverer` 打出的 Mod List 是最终生效的 mod 集合；`build/moddev/*LegacyClasspath.txt` 只是 MC-BOOTSTRAP 模块层的清单，其中出现某个 jar 不代表它会被当成 mod 载入。

## 复合构建依赖（极易踩坑）

`settings.gradle` 通过**复合构建（composite build）**引入三个同级源码目录：

| 依赖 | 源码目录 | 缺失时的回退 |
|------|----------|--------------|
| Spark-Core | `../Spark-Core` | Maven `io.github.solarmoonqaq:spark-core-neoforge` |
| BallisticsFramework | `../BallisticsFramework` | Maven `io.github.sweetzonzi:ballistics_framework-1.21.1-neoforge` |
| Machine-Max | `../Machine-Max` | Maven `io.github.sweetzonzi.machine_max:MachineMax-1.21.1` |

- 若三个目录之一存在，Gradle 会优先用源码；否则自动回退到发布 jar（`repositories.gradle` 中声明版本）。
- 修改 `settings.gradle` 或 `gradle.properties` 中 `spark_core_dir` / `ballistics_framework_dir` / `machine_max_dir` 可改变源码路径。
- 同步 IDE 或 `gradlew` 报错时，先检查这三个目录是否对应真实分支，否则确认 Maven 版本号与源码 API 兼容。

## 构建与资源生成

- `src/main/templates/META-INF/neoforge.mods.toml` 中的 `${...}` 占位符由 `generateModMetadata` 任务在构建时替换（属性列表是 `generateModMetadata` 任务里的 `replaceProperties` 映射）。
- `src/main/resources` 与 `build/generated/sources/modMetadata` 共同打包；数据生成输出目录 `src/generated/resources` 也被包含进资源集（`build.gradle` 的 `sourceSets.main.resources`）。
- 新增资源后若 IDEA 没识别，运行 `gradlew --offline neoForgeIdeSync` 或 `gradlew --offline generateModMetadata`。

## 代码架构要点

| 文件 | 职责 |
|------|------|
| `ARMS.java` | 模组入口，注册配置、服务端事件监听。 |
| `Config.java` | 示例配置（当前为占位），实际逻辑待扩展。 |
| `ArmsCore.java` | 机娘逻辑机甲单元，实现 `IPartAssembly` + `MechaControlHolder` + `SyncedDataHolder`；持有 `Level` / `UUID` / `MechaControl` / `SynchedEntityData`。两端都构造 `MechaControl`：服务端有控制器刚体与逻辑状态树，客户端没有这两样、只有表现层，判据是 `MechaControl.hasBody()`；控制器刚体本身仍只在服务端构造。 |
| `MechaControl.java` | 角色运动控制器编排器，两端都由 `ArmsCore` 构造：输入消费 → 朝向写入 → 状态机 → 表现层推进 → 控制器物理步；嵌套 `record LogicStateSnapshot` 同时是「物理线程 → 主线程的产出出口」与「主线程 → 物理线程的下行输入」。客户端形态没有身体、也没有逻辑状态树，物理步只跑下行状态写入与表现层推进；三条双端不变量写在类注释里。 |
| `MechaCharacter.java` | 机娘动力学角色控制器：**继承 `com.jme3.bullet.objects.PhysicsRigidBody`**（动态刚体圆柱，不再是 KCC）。四件事：每物理步的位姿写入（朝向绝对对齐 + 倾倒按 `(1 − λ)` 收敛）、着地判据（向下形状扫掠 + 法线筛 + 最小保持）、地面行走力律（`F_drive = min(k·computeDriveForce, μ_eff·N)` 加 1% 释放余量的摩擦前馈，走 `applyCentralForce`）、越障两段（探落脚面 → 落位，同一物理步完成、水平速度原样保留）。另有 `setViewYaw`、`setMoveIntent`（体系意图 → 世界方向的唯一变换）、跳跃冲量与助推窗口、闪避速度赋值；构造末尾经 `TerrainSeamContactCorrection#attachTo` 挂上地形拼缝的窄相接触修正。设计与实测见 `docs/角色控制器-刚体动力学方案.md` §7.4。 |
| `common/control/TerrainSeamContactCorrection.java` | 地形拼缝的窄相接触修正：只处理 owner 为 `PhysicsChunkSection` 的对方，把 `0.2 < \|n.y\| < 0.999` 的接触法线摆正为竖直（挂在本体刚体的 `Collide.Pre` 事件上，窄相里、求解器之前）；Create 装置被这道门排除。判据、深度与接触点的对照读数与三条危险车道见 `docs/角色控制器-刚体原型与引擎约束.md` §11.11。 |
| `MechaLogicStateMachine.java` | 状态机顶层封装，组合 `PostureLogicGraphs` / `GaitSubGraphs` / `VerticalSubGraphs`。 |
| `common/IArmsHost.java` | 宿主接口：绑定关系的读写（`getControlledArmsCore` / `setControlledArmsCore`）、`getHostEntity`、位置与速度的落地入口（`applyPose` / `applyVelocity`）。玩家经 Mixin 实现它，Doll / AI 敌人可直接实现。 |
| `mixin/PlayerHostMixin.java` | `@Mixin(Player.class) implements IArmsHost`：注入绑定字段 `armsCore$controlledCore` 并实现五个方法。绑定关系的唯一入口，负责三条换绑路径与输入重置；绑定期间通过 `neoforge:creative_flight` 属性授予飞行许可。 |
| `common/PlayerHostEvents.java` | 双端共用的 `PlayerTickEvent.Post` 订阅者：置 `noPhysics` 与重置坠距。 |
| `client/ClientHostPoseEvents.java` | 客户端专属（`Dist.CLIENT`）：按 `DATA_POS` 摆放本地玩家实体并清速度。`LocalPlayer` 只在客户端发行版存在，所以必须单独成类。 |
| `common/HostPositionIntake.java` | 外部位移的摄入：作用域栈（区分「实体自身运动 / 客户端上报采纳 / 本模组运行时回写」与真正的第三方写入）+ 锚点（`advanceAnchor` / `isAtAnchor`，吞掉「重断言当前位置」那一族写入）+ pin（warp 落地前 `DATA_POS` 暂取的目标）。判定点是 `mixin/EntityPositionWriteMixin.java` 对 `Entity#setPos` 的注入（栈空才判摄入、栈非空只按类别推进锚点；作用域退出只弹栈），采纳后把落点交给 `MechaCharacter#warp`。 | 
| `common/MechaCoreRegistry.java` | 装配体注册表（服务端按 `ServerLevel`、客户端单表）；服务端注册 / 注销时广播创建 / 移除包，并承载登录 / 换维度补发。注销时一并解绑宿主。 |
| `common/MechaInputHandler.java` | 上行输入的服务端处理链：控制权校验 → 合并环境状态 → 写快照 → 按事件序号幂等投递事件。 |
| `common/ArmsCoreServerEvents.java` | 两个相位的接线，两端都注册：`PhysicsLevelTickEvent.Pre` 按 Level 扇出 `prePhysicsTick`（服务端与客户端各有自己的物理级别），`LevelTickEvent.Post` 在服务端写 `syncedData` 并发包、在客户端把同步来的四项投影进变量容器（下行通道）；以及补发、断线重置、维度卸载。类名保留了它最初只接服务端时的形态，类注释说明双端职责。 |
| `common/command/ArmsCoreDebugCommand.java` | `/arms` 调试命令（spawn / list / remove / move / jump / stop / event / control）。 |
| `common/control/attr/MechaBodyPreset.java` | 碰撞体几何的唯一来源（圆柱半径 / 全高 / `HALF_TOTAL` / 由脚底算控制器碰撞体中心 / 各姿态高度），服务端出生点与客户端锚点共用。 |
| `common/control/attr/MechaModelPreset.java` | 机体占位模型的唯一来源（模型 `ModelIndex` / 贴图 / 缩放 / 底面偏移），与 `MechaBodyPreset` 同为阶段 4.6 前素体取值的临时权威；外观 1:1 渲染，只与碰撞体底面对齐。 |
| `network/ARMSNetwork.java` | 载荷注册与协议版本串；四个载荷为下行 `ArmsCoreCreatePayload` / `ArmsCoreRemovePayload` / `MechaCoreSyncPayload` 与上行 `MechaInputPayload`。 |
| `client/ARMSClient.java` | 客户端输入采集与上行发送（`ClientTickEvent.Pre`）。客户端不构造 KCC、不跑状态机。 |
| `client/ClientMechaAnchor.java` | 客户端可视锚点的采样与插值（含传送 / 断流跳变判据）。 |
| `client/MechaAnimatable.java` | 客户端动画体（`IAnimatable<ArmsCore>`）：持有模型 / 贴图 / MoLang 变量（`molangContext` 是本仓库的 `MechaMolangContext`），把锚点插值组装成模型矩阵（`getModelSpaceMatrix` 供挂在宿主实体上的绘制，`getWorldPositionMatrix` 满足接口契约）。主线程每 tick 走 `clientTick`（采样锚点 + 把本 tick 的同步速度与朝向发布给 MoLang 上下文 + `AnimController.tick()`），物理线程由表现层编排器走 `AnimController.physTick()`（动画时间与骨骼混合）。装配体之外的对象，`ArmsCore` 不持有它；阶段 4.6 改为逐 Part 渲染后整体删除。 |
| `client/MechaPlayerRenderer.java` | 客户机体的持有、驱动与绘制：按装配体 UUID 缓存 `MechaAnimatable`、每客户端 tick 推进动画、把该动画体注入装配体的表现层编排器（`common/control/presentation/MechaAnimPresenter.java#attach`，幂等）、在 `RenderPlayerEvent.Pre` 取消玩家模型并就地画出机体，并在同一相位把宿主的头朝向发布给 MoLang 上下文（那是唯一拿得到宿主实体的相位）。有机体时玩家模型连同其 RenderLayer（护甲 / 手持物 / 披风 / 鞘翅）与阴影全部让位，名称牌不受影响。 |
| `common/control/presentation/` | 表现层编排（方案与落地差异见 `docs/表现层动画接入实现计划.md`）：`MechaAnimPresenter` 持有注入的 `List<IAnimatable<?>>`、四台 `MultiAnimStateMachine` 与常驻并行层（建立时把共用的状态变量容器注入 MoLang 上下文），按物理子步推进；`MechaPresentationGraphs` 是四张姿态图；`MechaAnimPreset` 是动画名映射与过渡时长；`MechaPlayAnimAction` 是 Java 可达的播放动作（引擎的 `PlayAnimAction` 因 Kotlin value class 参数在 Java 侧构造不出来）；`MechaAnimGroups` 是层号别名；`MechaMolangContext` 是内容包 `q.*` 的求值上下文（运动 / 朝向 / 头部 / 每 tick 位移 / 寿命 / 潜水与蹲伏两项，值的来源与线程约定见 `docs/MoLang查询面接入实现计划.md`）。`MechaAnimatable` 与 `ClientMechaAnchor` 仍留在 `client` 包，presenter 只认 `IAnimatable`。 |

- 线程模型：主线程写 `volatile` 输入（`setMoveIntent` / `setViewYaw` 等），物理线程（`PhysicsLevelTickEvent.Pre`）在 `prePhysicsTick` 中读取；不要跨线程直接读写物理状态。逻辑层五项经 `MechaControl.LogicStateSnapshot` 不可变发布到主线程，`SynchedEntityData` 只在主线程写。
- 朝向与移动映射：`MechaControl.applyFacing` 每物理步把快照的 `viewYaw` 绝对写进控制器（`MechaCharacter.setViewYaw`，度制归约到 [−180, 180)，死亡 / ragdoll 时跳过），控制器在 `prePhysicsTick` 里把权威值落到刚体姿态上（`writeFacing`，绕世界 Y 轴、每步现算）；`applyMoveIntent` 只透传体系移动意图，`MechaCharacter.setMoveIntent` 按本步朝向解出世界方向——朝向只被计入一次，闪避轴（`resolveDodgeDirection`）用同一个角独立解出。动画根 Y 增量（`animRootYawDelta`）是阶段 4 接入点，当前不参与合成。
- 逻辑层产出 → 物理的落地集中在 `MechaControl.applyLogicOutputToKcc`：`MOVE_SPEED_MODIFIER` 写进控制器（`MechaCharacter.setMoveSpeedModifier`），作为**驱动力 `F_drive` 的缩放系数**（在锥截阈值以下的区间里稳态速率随之等比缩放；实测倍率 1.0 与 0.3 的稳态速率之比不是 0.3，原因见 `docs/角色控制器-刚体动力学方案.md` §12.10），进入 dodge 时把水平速度**赋值**到闪避轴上（`v' = (max(v·u, 0) + Δv)·u`：垂直于轴的动量整段抹掉、反向分量截断为 0、同向分量保留并叠加 Δv）。闪避与跳跃同构地**以冲量衡量**：`Δv = I_dodge / m`（`I_dodge` 见 `common/control/MechaControl.java#DODGE_IMPULSE`，`m` 见 `common/control/MechaCharacter.java#getControllerMass`，默认就是刚体质量），因此同一个冲量在越重的机体上效果越小；闪避轴 `u` 由输入轴与本步朝向解出，不读当前速度，见 `common/control/MechaCharacter.java#requestDodgeImpulse`。不剥夺自主移动：dodge 的进入动作不写 `MOVE_SPEED_MODIFIER`，见 `common/control/state/graph/MechaStateActions.java#gaitPreservingModifier`。姿态轮廓（蹲伏 / 卧倒的碰撞体尺寸）**未接入**（P5 的范围）：刚体路线上在世换形已经可行（`docs/角色控制器-刚体原型与引擎约束.md` §6.2 实测），但接管它还差「变高前先向上扫掠确认头顶空间」与「按新半高把脚底重新对齐」两件，因此 `MechaCharacter.applyPostureShape` 仍是返回 `false` 的占位。
- 行走力学模型（`MechaCharacter.updateWalk`，刚体形态）：输入施加的是**力**，速度由求解器按真实接触积分。地面：`F_drive = min(k·computeDriveForce(v), μ_eff·N)` 沿目标移动方向施加，再加 `μ_eff·N·(1 + 1% 余量)` 的摩擦前馈把引擎的静摩擦抵消掉，`N = m·g·cosθ` 取静态估算；**引擎对受力接触体给的是静摩擦锁定而不是「从受力里减掉 μN」**，因此前馈与那 1% 余量都是必需的，机制与实测见 `docs/角色控制器-刚体动力学方案.md` §7.4。空中控制力为 `F_max × AIR_CONTROL`（0.30）、不加前馈，因此空中难变向而跳跃继承水平速度。着地判据（向下扫掠 + 法线筛 + 最小保持）见同文 §7.7，位姿写入规则与越障两段见同文 §6.1、§6.2。**顶速三项尚未与旧标定对齐**（实测站立 25.0 m/s、蹲伏 10.7 m/s，且倍率不等比），原因与可动的旋钮见同文 §12.10。
- **撞墙时速度会归零**（KCC 时代的那条缺陷已随承载物消失）：刚体的速度就是求解器给出的真实速度，被挡住即归零，实测见 `docs/角色控制器-刚体原型与引擎约束.md` §3。KCC 那条「水平通道是位移命令、原生侧从不写回」的成因与上游报告（[Libbulletjme#58](https://github.com/stephengold/Libbulletjme/issues/58)）记在 `docs/角色控制器-KCC制约调研与替换评估.md` §3.2。
- **已知缺陷：外部位移会被两处回写抹掉。** 宿主实体的位置由 KCC 每 tick 产出并在服务端 level 相位回写（`common/ArmsCoreServerEvents.java#applyPoseToHost`），而 `/tp` 一类的本模组之外的写入只改了宿主实体。客户端那一侧：`PlayerTickEvent.Post` 上的 `client/ClientHostPoseEvents.java#applySyncedPose` 用旧 `DATA_POS` 把自己放回旧位置，而它只调 `Entity#setPos`、不写 `xo/yo/zo`（原版处理 `ClientboundPlayerPositionPacket` 时把这一族字段写成了目标），于是同一 tick 渲染出的相机按 `xo` 与当前位置插值——外观上是「闪到目标一帧」，位置其实当 tick 就被抹掉。服务端那一侧：同一 tick 的 level 相位用旧 KCC 位置覆盖实体，tick 尾相位（收到的包在 `net.minecraft.server.MinecraftServer#waitUntilNextTick` 里排空）再由客户端上报的旧位置覆盖一次。**摄入已落地**：`common/HostPositionIntake.java` 把外部位移转成 KCC 的 warp 并在落地前钉住 `DATA_POS`；判定点是 `mixin/EntityPositionWriteMixin.java` 对 `Entity#setPos` 的注入，按「作用域栈 + 锚点」分类（三类已知镜像由该 Mixin 与 `mixin/ServerGamePacketListenerMixin.java`、两端运行时回写各压一个作用域；只有栈空且目标不等于锚点才采纳）。**仍未落地的是服务端那次每 tick 位置回写**（`docs/宿主位置权威与位移摄入设计.md` §九 第 4 步要删掉它、宿主位置改由客户端上报维护），以及乘客态与客户端那两半；已完成与未完成的逐条清单见该文 §十一。
- 客户端与服务端的权威分工：服务端是唯一权威端，客户端不运行 `MechaCharacter` 与 `MechaLogicStateMachine`，只按同步来的位姿摆放非实体可视锚点（`docs/ArmsCore双端权威与网络同步实现计划.md` D1、D18）。客户端的 `MechaControl` 是空壳形态（无身体、无逻辑树），它多出来的那项职责是推进表现层动画——动画编排住在它里面，所以它必须有实例（`docs/表现层动画接入实现计划.md` D1、§4.1）。
- 字段表纪律：`ArmsCore` 的 `EntityDataAccessor` 只允许在末尾追加（`docs/ArmsCore双端权威与网络同步实现计划.md` §2.2、§3.2）；改动字段表或载荷字段后必须同时提升 `ARMSNetwork.PROTOCOL_VERSION`。
- 逻辑层状态图与表现层的四台姿态机都已接线，逻辑层仍有 `TODO`（武器本地状态机、动画根位移提取、`ctrl.*` 命名空间的查询面——后者见 `docs/分层控制器与状态机设计.md` §9）。内容包的 `q.*` 查询面已接入（见 `docs/MoLang查询面接入实现计划.md`）。新增逻辑层状态机节点/子图需同时更新 `MechaLogicStateMachine` 的 `children` 映射；新增表现层姿态节点要同时更新 `MechaAnimPreset` 的映射表，否则该节点没有动画（`MechaPresentationGraphsTest` 的映射一致性用例会挡住）。动画播放默认走 `MechaPlayAnimAction`，不要直接构造引擎的 `PlayAnimAction`。

## 代码风格与依赖

- 使用 **Lombok**：`@Getter` / `@Setter` 注解已启用，主源码集与测试源码集各声明一次（`repositories.gradle:127-133`）。字段上的 `@NotNull` / `@Nullable`（JetBrains）会被 Lombok 拷到生成的 getter 上，因此接口的返回值可空性靠字段注解维持。
- 使用 **Mixin**（`arms_core.mixins.json`），新增 Mixin 需同步写入该文件的 `mixins` 或 `client` 数组。**该文件的 `package` 是一个 Mixin 独占包**：`io.github.sweetzonzi.arms_core.mixin.*` 下的类不能在别处被直接引用（`@Mixin` 目标之外的类放进这个包会报 `cannot be referenced directly`）。
- **Mixin 注入的成员在普通 Java 编译期不存在于目标类上。** 调用方必须写成 `((IArmsHost) player).applyPose(...)` 或 `player instanceof IArmsHost host` 后经 `host` 调用；`player.applyPose(...)` 这类直接调用**编译不过**。`common/ArmsCore.java#bindHostOf` 是这一形态的便捷封装。
- **网络包可能被分两遍处理：收包线程那一遍把真正的处理排进服务端任务队列后退出，服务端线程在 tick 尾相位再跑一遍（这一遍才执行方法体）。** 所以 `@WrapMethod` 的包装体两遍都会进入——状态只在执行方法体的那一遍持有，`common/HostPositionIntake.java` 的作用域栈是服务端线程独占的普通字段。
- 编码统一为 UTF-8（`build.gradle` 的 `options.encoding` 与 `ProcessResources.filteringCharset`）。
- 包结构：`io.github.sweetzonzi.arms_core.*`，与 `mod_group_id` 一致。

## Git 与工作区纪律

以下几条针对「在**未提交**的工作上做局部撤销」这一类操作。文件级回滚命令不区分「你刚加的东西」与「这个文件里其它尚未提交的工作」，一次误用可以静默丢掉一整轮已验证的改动。

- **拿不准能不能安全回滚，就先提交。** 未提交的工作在 `git checkout` / `git restore` / `git stash drop` 面前没有保护层；把当前这一轮做完的部分先 `git add` 成一个临时提交是唯一稳妥的「存档点」。
- `git checkout -- <文件>` / `git restore <文件>` **只能用于「这个文件的全部未提交改动都不要了」**。执行前先 `git diff --stat <文件>` 确认这个文件里到底有哪些改动，并逐条确认都可以丢。
- **禁止用文件级回滚撤销「只占该文件一部分」的改动。** 典型场景：某个文件里既有本轮已验证、尚未提交的重构，又有为调查临时加进去的代码——此时要撤的只是后者，`git checkout` 会把前者一起抹掉。这种情况逐处 `edit` 删掉那部分代码。
- 撤销之后必须复跑 `.\gradlew --offline test`，并用 `git status --short` 核对文件清单。文件级回滚是静默的：它不会报错，也不会提示丢了什么。
- 临时的诊断代码与探针测试要么放在本轮结束前删除，要么在存在同文件的未提交改动时先提交正式改动再加进去，避免两者混在同一个文件里。

## 文档编辑规范

### 自包含性检查（编辑任何文档后、报告完成前必做）

**适用范围**：`docs/`、`AGENTS.md`、代码注释与 Javadoc。

**要求只有两条。**

1. **对外部依赖：可以读别的文档，但必须交叉引用。** 本文档的结论、判据、字段含义写在自己文内；引用别的文档或源码时给出可定位的坐标，分两种：**活引用**（指向当前代码）写「路径#符号」，符号取可唯一检索的声明名——`docs/X.md` 的节号、`../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/level/PhysicsLevel.kt#stepPhysics`、`common/control/MechaControl.java#frameLogic`；**历史快照**（指向某一版形态）写「路径:行 @ 提交」——`src/main/java/io/github/sweetzonzi/arms_core/common/ArmsCore.java:20 @ 93924d9`，可用 `git show 93924d9:<同一路径>` 复现。活引用不写行号：行号漂移是静默的，既不会报错也不会提示，转换后只会指向无关代码。符号必须**在源码里可检索**：Lombok 生成的 getter / setter 不出现在源码文本里，这类成员锚到它所在的**字段**——写字段名（`MechaCharacter.java#viewYaw`），不写 Lombok 生成的那个方法名。允许把支撑论述留在外部——读者顺着引用去读即可，但不给引用就引用，等于要求读者自己猜，属于违规。
2. **对自身历史：绝对不允许假设读者了解先前版本。** 差分残留是最常见的失效模式：句子的成立以读者知道修改前的内容为前提。典型：`文件名不再使用 X`、`掩体仍然生效`、`A 取消，改为 B`、`（原 foo()）`、`比之前更简单`、`见上文`。

**唯一判据**：假设读者只拿到当前文件的最终版本，从未看过历史版本、git diff、PR 描述或本次对话，该句是否仍能被无歧义地理解与验证？不能 → 违规，必须修复。第 1 条违反给引用即可，第 2 条违反只能改写或搬家。

**第一步：机械扫描（不要凭记忆）** 对本次编辑的文件检索触发词，形成候选清单再逐条判断：

```powershell
Select-String -Path <文件> -Pattern '不再|不再需要|不再依赖|仍然|依旧|仍旧|照旧|还是|取消|移除|删除|改成|改为|换成|替换为|新增|补充|之前|原先|原来|本来|以前|原有|旧版|过去|同上|如前所述|见上文|（原|曾用名|变更前|变更后|已并入|作废|早期'
```

结构性触发（无触发词也要查）：

- 无基线的比较级（更快、更简单、更稳）——比什么？
- 悬空代词回指（它／该方案／上述做法）指向已删除的内容
- 章节交叉引用（§9.2、见 2.6）指向已删除或已重编号的章节
- 表格中的「变更前/变更后」列、「建议改为…」（隐含最初的做法）
- 代码注释与 Javadoc 中的 `现在不再…`／`改为…`

**第二步：判定分级**

| 级别 | 特征 | 处理 |
|------|------|------|
| A 必须改 | 以旧状态为基线描述新状态；或引用外部而完全不给坐标 | 改成对当前事实的绝对陈述；或就地补上引用坐标 |
| B 需重写或搬家 | 版本对比对「从旧版升级」的读者有价值 | 移入文末「附录：被取代的做法 / 修订记录」，正文只留当前事实 |
| C 合法保留 | 基线在同一文档内已给出（同句或前文）；或「之前/之后」指**系统运行时**时序而非文档版本；或该节主题就是版本差异（修订记录、迁移指南、对比章节）；或引用坐标已在同一段给出 | 保留，并在汇报中注明基线或引用在哪 |

词表命中 ≠ 违规：`在 tick 之前`、`取消订阅`、`命中后不再判定`、`移除该实体` 都是合法用法。
词表未命中 ≠ 安全：先看结构，再看用词。

**第三步：修复写法（按优先级）**

1. **绝对化** —— 删掉对比，只陈述当前事实及其成立理由
   `文件名不再使用 X` → `文件名使用随机 UUID（<uuid>.json，与 VehicleData.uuid 无关）`
2. **就地补基线** —— 把被对比项写进同一句，让对比自足
   `掩体仍然生效` → `管理员关闭地形破坏后，掩体削弱仍按穿透折减生效`
3. **补引用** —— 属于 A 类第 1 条时，把 `见某文档` 补成 `见 docs/X.md §N`（节号必须真实存在）
4. **搬家** —— 对比信息只在「版本演进」语境下有意义时，移入文末附录

**检查范围**：扫描全文触发词（成本低），不止本次改动的段落——自包含声明、简介、章节编号经常在离改动很远处失效。

**禁止**：

- 只删触发词不管语义（`不再使用 X` → `使用 X`，而现状其实是 Y）
- 把 A 类"保留信息"改写成 B 类（把 diff 抄进正文）
- 顺手扩写无关章节——除修复点外不动内容
- 汇报「已检查无问题」却不给证据

**机械校验**：`pwsh -NoProfile -File tools/check_doc_refs.ps1` 逐条核对 `AGENTS.md` 与 `docs/` 里的坐标——`路径#符号` 能否在目标文件（含三个同级源码仓库）里检索到、`路径:行 @ 提交` 能否被 `git show` 复现、Minecraft 一类外部类名能否在 `build/moddev/artifacts` 的 sources jar 里找到；同时列出仍写裸行号、没有 `@ 提交` 基线的活引用。有无法定位的引用时退出码为 1，可在提交前跑一次。

**完成条件（汇报格式）**

1. 命中清单：`路径#符号`（或 `路径:行 @ 提交`）+ 原句
2. 逐条处理：改写后的句子 / 已移入附录 / 判 C 类并说明基线或引用出处
3. 交叉引用复核：本次涉及的 §号与阶段号是否仍指向存在的目标
4. C 类判定有争议时，一律改写为自足表述

**与上游的关系**：本节的第 2 条与 `../Machine-Max/AGENTS.md`「文档编辑规范」的判据一致（那份文档不允许以自身旧版本为前提）；第 1 条在本项目放宽——允许读者顺着交叉引用去读别的文档与源码，只要引用可定位。

**一句话版**：凡出现「不再/仍然/改为/之前/取消/（原…）」等对照词，判断该对照的基线是否在同一份文档里给出；没有就给基线、改成绝对陈述、或移到文末附录。凡引用别的文档或源码，必须给出节号、「路径#符号」（活引用）或「路径:行 @ 提交」（历史快照）。

## 调试与运行

- VS Code 已配置启动项 `.vscode/launch.json`（Client / Data / GameTestServer / Server），依赖 `build/moddev/` 下的参数文件；若文件不存在，先运行一次 `gradlew --offline prepareClientRun` 等任务生成。
- 物理/动画日志：`MechaLogicStateMachine.DEBUG_LOG = false`，临时设为 `true` 可打印状态转移。
- `MechaCharacter` 中行走力、跳跃、摩擦等参数集中在 `MechaWalkingAttr` / `MechaJumpAttr`（`common/control/attr`）。

## 文档参考

| 文档 | 内容 |
|------|------|
| `docs/总体设计文档.md` | 架构、`IArmsHost`、`ArmsCore`、`MechaControl` 双层刚体、两条输入路径。 |
| `docs/角色控制器-行走物理设计.md` | KCC 承载下的现行力模型：控制力、抓地力、跳跃模型（瞬时冲量 + 助推窗口）、多通道合成、线速度单位约定；摩擦项归属、越障判决与着地判据的迁移结论指向 `docs/角色控制器-刚体动力学方案.md` §6.2、§7.1、§7.4、§7.6。 |
| `docs/角色控制器-KCC制约调研与替换评估.md` | KCC（btKinematicCharacterController）制约调研：单位语义、不可观测与无写回、垂直通道隐藏上限、在世不可换形的硬阻塞、无质量不受力；`onGround()` 的真实机制（上一步向下形状扫掠是否命中，不是接触清单查询）；记录 Minie 的 BetterCharacterControl 作为既有先例——它的「每步写线速度」的速度权威、没有越障、不取命中法线三条不照搬，只取球扫掠着地 / 变高前先扫掠 / `setKinematic` 三样形态；据此引出自实现刚体角色控制器的方向与验收基线（实现途径归后续文档）。 |
| `docs/角色控制器-刚体原型与引擎约束.md` | 动力学刚体路线在真实 `PhysicsSpace` 上的**实测结果与实现约束**：锁转站立零漂移、撞墙速度归零、越障阈值精确等于台阶高度、引擎组合摩擦为双方之积（五档 × 六坡角的标定表，地形侧取 Bullet 默认 0.5——数据包里 `BlockPhysicsData` 的 0.7 只被按接触点写入的路径用到）、`setAngularFactor` 只拦角冲量而不改逆惯量、分轴锁在换形与改质量后仍有效、地面 PD 姿态控制器的六组增益数据与单接触点机制、落地收敛的首步即锁对照（`(0,1,0)` 锁 pitch/roll 留 yaw）、探针取高判据。**十一条**实施坑（前五条是越障判决本身：探针取高、命中比例 0 是合法信号、顶面探针的取高、抬升量的下限（只属于胶囊承载时期）、`sweepTest` 结果无序；第六条是落脚面探针的取位与取高——用碰撞体形状在落位位置下扫，含掠射接触的读数表与推进量下限 0.1 m 的两侧夹法；后三条是底面为平面的形体在斜置初态下的穿透恢复、锁转下倾角是姿态写入的专属量、有限宽碰撞体绕角点接触的实测（含判据收窄后该边界为何消失），以及地形拼缝上的窄相行为——圆柱核心的底缘压过邻块盒核心的棱时给出一条歪掉的接触法线，把前进动量拆成向上一份、向后一份，两条碰撞边距只以和的形式进入，其处置是在窄相里把这条法线摆正、门只认 owner 为地形 section 的对方，含深度与接触点两问的对照读数与三条危险车道）与 KCC 验收基线的对账表也在本文；§8.3 与 §8.4 给出收敛律的纯运动学验证（倾角严格按 `(1−λ)` 衰减、单轴初态朝向漂移 0.00000°）与斜置初态的朝向偏差量级表。可执行判据 `src/test/java/io/github/sweetzonzi/arms_core/common/control/RigidBodyControllerPrototypeTest.java`。 |
| `docs/角色控制器-刚体动力学方案.md` | 刚体控制器的**设计决策与理由与实测数值**：现行 KCC 的三个阻断性问题、四项已定型选择、姿态自由度划分（`(0, 1, 0)` 锁 pitch/roll 留 yaw，yaw 由每物理步的位姿写入驱动并作为躯干的目标朝向，理由见 §4.1、§5.2）、落地处理（锁与摆正两个动作、收敛律 = 把上轴转正的最小旋转、为什么不需要平滑过渡的权重）、**地面行走力律**（`F_drive` 截到 `μN` + 摩擦锥前馈带 1% 释放余量；含引擎「静摩擦锁定」的实测、三种候选模型的取舍、以及与旧标定的对账表——起步加速度已复现、顶速未对齐）、**接触量取法**（`N = m·g·cosθ`、`μ_eff` 取配置常量，都不读接触点）、**着地判据**（向下扫掠 + 端点定窗口 + 法线竖直分量 ≥ 0.35 + 上升门限取量级 + 最小保持）、**位姿写入的四条规则与越障两段**（探落脚面 → 落位，同一物理步内）、`warp` 收缩为「位置 + 速度」、力与力矩如何作用于碰撞体（世界系归约）、力源到作动器的数据映射（五类力源、两个去向、作动器交出的四样数据、分配目标与各界的构造、活行掩码由 `angularFactor` 导出——飞行 6 行 / 地面与收敛窗口 4 行）、气动复用与每物理步现算（逐计算点速度含 `ω × r`、气动点随舵面转带来两条接线前提、仿射模型 = 截距 + 割线列，实测区间内偏差峰值 7.5% 列模长）、气动的去向是**目标上的矢量减法**（含折进界 / 折进目标 / 加在输出三路对比）。含实施阶段 P1–P6 的落地状态、待定项（§12.1、§12.8 已定，§12.9 三处待测已量出数值，§12.10 是顶速标定）与术语表；控制分配器本体见 `../Machine-Max/docs/plan/进行中/控制分配-作动器wrench分配设计.md`。 |
| `../Machine-Max/docs/plan/进行中/控制分配-作动器wrench分配设计.md` | Machine-Max 的通用控制分配设计：按游戏可用性、实际施力可信与有界成本验收，不要求全局最优。行缩放 `S` 与作动器代价 `W` 分离；`util/WrenchAllocator` 已实现批量阻尼伪逆与批量钉界基线，退化出口和残差见 §5，验证结果见 §11.5，响应后实际施力与动画同源见 §7、§9。§11 分纯数学、裸 Bullet、性能及游戏接入阶段；虚拟作动器与平衡辅助为可选能力（§2.4、§5.3）。 |
| `docs/跳跃-瞬时冲量持续助推设计.md` | 跳跃模型改造设计：瞬时冲量 + 持续助推窗口、三条窗口终止条件、参数 `F_BOOST` / `T_BOOST_MAX`、代码与文档改动清单、验收判据。 |
| `docs/分层控制器与状态机设计.md` | 状态机、MoLang 集成、动画驱动。 |
| `docs/表现层动画接入实现计划.md` | 表现层动画的**接入方案与落地形态**：`MechaControl` 双端化与三条不变量、客户端下行状态通道（枚举 ↔ MoLang 名）、presenter 的三个就绪判据与资源重载后的重建、四台姿态机的边表与逐条设计意图（兜底边、DRIFT 期无出边、受击不设节点）、常驻并行层的层号取值、实施阶段 1~5 的验收与落地坐标，以及一节「落地差异」逐条记下实现与方案的出入。 |
| `docs/MoLang查询面接入实现计划.md` | MoLang 查询面的**接入方案与落地形态**：引擎现有 `q.*` 绑定与「查不到取 0」的降级行为、内容包实际用到的命名空间清单与全包实测（2323 条表达式里的 NaN 与异常）、`MechaMolangContext` 的十个查询及其来源与单位口径（运动量取自同步 `DATA_VEL`、每 tick 位移、头部朝向由渲染路径发布、逻辑状态两项取自变量容器）、全包有限性用例 `MechaPackMolangTest`、以及两个非目标（`ysm.*` / `ctrl.*`）。上游的引擎侧修正见 `../Spark-Core/docs/molang_compiled_path_defects_plan.md`。 |
| `docs/MechaControl设计文档.md` | 早期 `MechaControl` 接口设计。 |
| `docs/下一步开发TODO.md` | 当前里程碑、逐项待办、跨线程快照决策。 |
| `docs/ArmsCore双端权威与网络同步实现计划.md` | `ArmsCore` 的服务端权威归属、创建 / 移除协议、上下行同步通道、实施阶段与验收判据。 |
| `docs/宿主接入与伤害管线设计.md` | 玩家宿主形态、绑定字段与 Mixin 接线、位置权威与 tick 相位、伤害管线的解析端与投递端、装配接入前的临时区域、已知风险与实施顺序。 |
| `docs/宿主位置权威与位移摄入设计.md` | 原版玩家位移路径地图（唯一权威通道、各入口调用链、不进通道的例外）、宿主实体位置的四类写入者与判据（作用域栈 + 锚点）、KCC warp 与位姿钉住、服务端不回写宿主位置、乘客态的位置权威在载具、验收矩阵与残留风险。检测层与动作层已落地；**实现状态、与设计不同的三处（不做维度守卫、判据与投递同一调用栈、第 4 类作用域覆盖两端）、以及仍未落地的四项**见该文 §十一。 |
| `docs/IArmsHost宿主接口设计.md` | 宿主接口的方法集与命名约束、参数的基准与单位、玩家宿主的实现形态、绑定关系的存储与派生索引。 |
| `docs/GameTest车道指南.md` | `runGameTestServer` 起的是什么、一条用例从结构方块到 `succeed()` 的完整链路、场地坐标系（随机原点、网格几何、相对坐标换算）、三条硬限制（随机原点、单精度物理的 ULP 界、单世界共享网格）、控制落点的可选路径。 |

- 设计文档的结论与判据以表格和「路径#符号」证据为主，改动代码后若与文档冲突，先按上节复核文档的自包含性，再决定改代码还是改文档。
