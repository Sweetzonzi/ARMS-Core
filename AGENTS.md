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
.\gradlew runClient

# 服务端运行
.\gradlew runServer

# 数据生成（输出到 src/generated/resources）
.\gradlew runData

# GameTest 服务器
.\gradlew runGameTestServer

# 单元测试（纯逻辑层；不需要启动 Minecraft）
.\gradlew test

# 构建产物（jar 在 build/libs）
.\gradlew build

# 清理构建目录
.\gradlew clean
```

- 单元测试位于 `src/test/java/` 下：`common/control/`（`MechaControlTest`、`MechaCharacterWalkPhysicsTest`、`MechaCharacterStepTest`、`MechaCharacterFacingTest` 与四个状态机图测试）、`common/control/attr/MechaBodyPresetTest`、`common/FlightAbilityModifierTest`、`client/ClientMechaAnchorTest`、`network/ARMSNetworkCodecTest`。全部为纯逻辑、不触碰 jme3 native，因此 `test` 可独立运行；`test` 已配置为 `useJUnitPlatform()` 并纳入 `check`（`build.gradle` 的 `test` 任务配置）。`MechaCharacterWalkPhysicsTest` 与 `MechaCharacterStepTest` 会搭真实 `PhysicsSpace` 并按 100 Hz 步进 KCC，断言的是力学结果（稳态速率、转向速率、跳跃继承、越障阈值）。
- **测试类不得放在 `mixin` 包下**：`io.github.sweetzonzi.arms_core.mixin.*` 已被 `arms_core.mixins.json` 声明为 Mixin 独占包，其中的类不能被直接引用，放进去会在测试启动时报 `IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly`。
- 启动时工作目录是 `run/`，由 NeoForge MDK 自动生成；首次运行会下载 MC 资产。

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
- 新增资源后若 IDEA 没识别，运行 `gradlew neoForgeIdeSync` 或 `gradlew generateModMetadata`。

## 代码架构要点

| 文件 | 职责 |
|------|------|
| `ARMS.java` | 模组入口，注册配置、服务端事件监听。 |
| `Config.java` | 示例配置（当前为占位），实际逻辑待扩展。 |
| `ArmsCore.java` | 机娘逻辑机甲单元，实现 `IPartAssembly` + `MechaControlHolder` + `SyncedDataHolder`；持有 `Level` / `UUID` / `MechaControl` / `SynchedEntityData`。服务端构造 KCC，客户端经 `newClientInstance` 构造且不持有 KCC。 |
| `MechaControl.java` | 角色运动控制器编排器：输入消费 → 朝向写入 → 状态机 → 动画 → KCC 物理积分；嵌套 `record LogicStateSnapshot` 作为物理线程 → 主线程的出口。 |
| `MechaCharacter.java` | 基于 Bullet `PhysicsCharacter` 的运动学胶囊控制器（KCC）：朝向（`setViewYaw`）、体系移动意图到世界方向的唯一变换（`setMoveIntent`）、控制力积分（地面全额 + 侧向抓地 / 空中缩放）、跳跃蓄力、动画根位移合成。 |
| `MechaLogicStateMachine.java` | 状态机顶层封装，组合 `PostureLogicGraphs` / `GaitSubGraphs` / `VerticalSubGraphs`。 |
| `common/IArmsHost.java` | 宿主接口：绑定关系的读写（`getControlledArmsCore` / `setControlledArmsCore`）、`getHostEntity`、位置与速度的落地入口（`applyPose` / `applyVelocity`）。玩家经 Mixin 实现它，Doll / AI 敌人可直接实现。 |
| `mixin/PlayerHostMixin.java` | `@Mixin(Player.class) implements IArmsHost`：注入绑定字段 `armsCore$controlledCore` 并实现五个方法。绑定关系的唯一入口，负责三条换绑路径与输入重置；绑定期间通过 `neoforge:creative_flight` 属性授予飞行许可。 |
| `common/PlayerHostEvents.java` | 双端共用的 `PlayerTickEvent.Post` 订阅者：置 `noPhysics` 与重置坠距。 |
| `client/ClientHostPoseEvents.java` | 客户端专属（`Dist.CLIENT`）：按 `DATA_POS` 摆放本地玩家实体并清速度。`LocalPlayer` 只在客户端发行版存在，所以必须单独成类。 |
| `common/MechaCoreRegistry.java` | 装配体注册表（服务端按 `ServerLevel`、客户端单表）；服务端注册 / 注销时广播创建 / 移除包，并承载登录 / 换维度补发。注销时一并解绑宿主。 |
| `common/MechaInputHandler.java` | 上行输入的服务端处理链：控制权校验 → 合并环境状态 → 写快照 → 按事件序号幂等投递事件。 |
| `common/ArmsCoreServerEvents.java` | 两个相位的接线：`PhysicsLevelTickEvent.Pre` 扇出 `prePhysicsTick`，`LevelTickEvent.Post` 写 `syncedData` 并发包；以及补发、断线重置、维度卸载。 |
| `common/command/ArmsCoreDebugCommand.java` | `/arms` 调试命令（spawn / list / remove / move / jump / stop / event / control）。 |
| `common/control/attr/MechaBodyPreset.java` | 胶囊几何的唯一来源（半径 / 圆柱段高 / `HALF_TOTAL` / 由脚底算胶囊中心），服务端出生点与客户端锚点共用。 |
| `common/control/attr/MechaModelPreset.java` | 机体占位模型的唯一来源（模型 `ModelIndex` / 贴图 / 缩放 / 底面偏移），与 `MechaBodyPreset` 同为阶段 4.6 前素体取值的临时权威；外观 1:1 渲染，只与胶囊底面对齐。 |
| `network/ARMSNetwork.java` | 载荷注册与协议版本串；四个载荷为下行 `ArmsCoreCreatePayload` / `ArmsCoreRemovePayload` / `MechaCoreSyncPayload` 与上行 `MechaInputPayload`。 |
| `client/ARMSClient.java` | 客户端输入采集与上行发送（`ClientTickEvent.Pre`）。客户端不构造 KCC、不跑状态机。 |
| `client/ClientMechaAnchor.java` | 客户端可视锚点的采样与插值（含传送 / 断流跳变判据）。 |
| `client/MechaAnimatable.java` | 客户端动画体（`IAnimatable<ArmsCore>`）：持有模型 / 贴图 / MoLang 变量，把锚点插值组装成模型矩阵（`getModelSpaceMatrix` 供挂在宿主实体上的绘制，`getWorldPositionMatrix` 满足接口契约）。装配体之外的对象，`ArmsCore` 不持有它，改为逐 Part 渲染时整体删除。 |
| `client/MechaPlayerRenderer.java` | 客户机体的持有、驱动与绘制：按装配体 UUID 缓存 `MechaAnimatable`、每客户端 tick 推进动画、在 `RenderPlayerEvent.Pre` 取消玩家模型并就地画出机体。有机体时玩家模型连同其 RenderLayer（护甲 / 手持物 / 披风 / 鞘翅）与阴影全部让位，名称牌不受影响。 |

- 线程模型：主线程写 `volatile` 输入（`setMoveIntent` / `setViewYaw` 等），物理线程（`PhysicsLevelTickEvent.Pre`）在 `prePhysicsTick` 中读取；不要跨线程直接读写物理状态。逻辑层五项经 `MechaControl.LogicStateSnapshot` 不可变发布到主线程，`SynchedEntityData` 只在主线程写。
- 朝向与移动映射：`MechaControl.applyFacing` 每物理步把快照的 `viewYaw` 绝对写进 KCC（`MechaCharacter.setViewYaw`，度制归约到 [−180, 180)，死亡 / ragdoll 时跳过），`applyMoveIntent` 只透传体系移动意图，`MechaCharacter.setMoveIntent` 按本步朝向解出世界方向——朝向只被计入一次，闪避方向（`resolveDodgeDirection`）用同一个角独立解出。动画根 Y 增量（`animRootYawDelta`）是阶段 4 接入点，当前不参与合成。
- 逻辑层产出 → 物理的落地集中在 `MechaControl.applyLogicOutputToKcc`：`MOVE_SPEED_MODIFIER` 写进 KCC，作为**控制力的缩放系数**（稳态速率随之等比缩放：站立 6.0 m/s、蹲伏 1.8 m/s；不是另设一道速度上限），进入 dodge 时施加按窗口积分的冲量。姿态轮廓（蹲伏 / 卧倒的胶囊尺寸）**未接入**：Libbulletjme 禁止在世的 KCC 换碰撞形状，违反会以 `0xC0000409` 中止进程，见 `docs/ArmsCore双端权威与网络同步实现计划.md` §3.12.1、§3.12.2。
- 行走力学模型（`MechaCharacter.updateWalk`）：输入施加的是**控制力**，速度按矢量积分——地面控制力全额、受抓地力 `μN` 钳制并经 `μ·g·cosθ` 抹掉侧向速度；空中控制力为 `F_max × AIR_CONTROL`（0.30）、无侧向抓地、无摩擦刹车，因此空中难变向而跳跃继承水平速度。顶速是力平衡 `v = k·v_ref` 的解，不靠速度钳制。参数与公式见 `docs/角色控制器-行走物理设计.md` §3.5–§3.9。
- 客户端与服务端的权威分工：服务端是唯一权威端，客户端不运行 `MechaCharacter` 与 `MechaLogicStateMachine`，只按同步来的位姿摆放非实体可视锚点（`docs/ArmsCore双端权威与网络同步实现计划.md` D1、D18）。
- 字段表纪律：`ArmsCore` 的 `EntityDataAccessor` 只允许在末尾追加（`docs/ArmsCore双端权威与网络同步实现计划.md` §2.2、§3.2）；改动字段表或载荷字段后必须同时提升 `ARMSNetwork.PROTOCOL_VERSION`。
- 当前状态机与动画模块为部分实现（大量 `TODO`），新增状态机节点/子图需同时更新 `MechaLogicStateMachine` 的 `children` 映射。

## 代码风格与依赖

- 使用 **Lombok**：`@Getter` / `@Setter` 注解已启用，主源码集与测试源码集各声明一次（`repositories.gradle:127-133`）。字段上的 `@NotNull` / `@Nullable`（JetBrains）会被 Lombok 拷到生成的 getter 上，因此接口的返回值可空性靠字段注解维持。
- 使用 **Mixin**（`arms_core.mixins.json`），新增 Mixin 需同步写入该文件的 `mixins` 或 `client` 数组。**该文件的 `package` 是一个 Mixin 独占包**：`io.github.sweetzonzi.arms_core.mixin.*` 下的类不能在别处被直接引用（`@Mixin` 目标之外的类放进这个包会报 `cannot be referenced directly`）。
- **Mixin 注入的成员在普通 Java 编译期不存在于目标类上。** 调用方必须写成 `((IArmsHost) player).applyPose(...)` 或 `player instanceof IArmsHost host` 后经 `host` 调用；`player.applyPose(...)` 这类直接调用**编译不过**。`common/ArmsCore.java#bindHostOf` 是这一形态的便捷封装。
- 编码统一为 UTF-8（`build.gradle` 的 `options.encoding` 与 `ProcessResources.filteringCharset`）。
- 包结构：`io.github.sweetzonzi.arms_core.*`，与 `mod_group_id` 一致。

## 文档编辑规范

### 自包含性检查（编辑任何文档后、报告完成前必做）

**适用范围**：`docs/`、`AGENTS.md`、代码注释与 Javadoc。

**要求只有两条。**

1. **对外部依赖：可以读别的文档，但必须交叉引用。** 本文档的结论、判据、字段含义写在自己文内；引用别的文档或源码时给出可定位的坐标，分两种：**活引用**（指向当前代码）写「路径#符号」，符号取可唯一检索的声明名——`docs/X.md` 的节号、`../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/level/PhysicsLevel.kt#stepPhysics`、`common/control/MechaControl.java#frameLogic`；**历史快照**（指向某一版形态）写「路径:行 @ 提交」——`src/main/java/io/github/sweetzonzi/arms_core/common/ArmsCore.java:20 @ 93924d9`，可用 `git show 93924d9:<同一路径>` 复现。活引用不写行号：行号漂移是静默的，既不会报错也不会提示，转换后只会指向无关代码。符号必须**在源码里可检索**：Lombok 生成的 getter / setter 不出现在源码文本里，这类成员锚到它所在的**字段**——写字段名（`MechaCharacter.java#currentYaw`），不写 Lombok 生成的那个方法名。允许把支撑论述留在外部——读者顺着引用去读即可，但不给引用就引用，等于要求读者自己猜，属于违规。
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

- VS Code 已配置启动项 `.vscode/launch.json`（Client / Data / GameTestServer / Server），依赖 `build/moddev/` 下的参数文件；若文件不存在，先运行一次 `gradlew prepareClientRun` 等任务生成。
- 物理/动画日志：`MechaLogicStateMachine.DEBUG_LOG = false`，临时设为 `true` 可打印状态转移。
- `MechaCharacter` 中行走力、跳跃、摩擦等参数集中在 `MechaWalkingAttr` / `MechaJumpAttr`（`common/control/attr`）。

## 文档参考

| 文档 | 内容 |
|------|------|
| `docs/总体设计文档.md` | 架构、`IArmsHost`、`ArmsCore`、`MechaControl` 双层刚体、两条输入路径。 |
| `docs/角色控制器-行走物理设计.md` | KCC 力学模型、抓地力、跳跃蓄力、多通道合成、线速度单位约定。 |
| `docs/分层控制器与状态机设计.md` | 状态机、MoLang 集成、动画驱动。 |
| `docs/MechaControl设计文档.md` | 早期 `MechaControl` 接口设计。 |
| `docs/下一步开发TODO.md` | 当前里程碑、逐项待办、跨线程快照决策。 |
| `docs/ArmsCore双端权威与网络同步实现计划.md` | `ArmsCore` 的服务端权威归属、创建 / 移除协议、上下行同步通道、实施阶段与验收判据。 |
| `docs/宿主接入与伤害管线设计.md` | 玩家宿主形态、绑定字段与 Mixin 接线、位置权威与 tick 相位、伤害管线的解析端与投递端、装配接入前的临时区域、已知风险与实施顺序。 |
| `docs/IArmsHost宿主接口设计.md` | 宿主接口的方法集与命名约束、参数的基准与单位、玩家宿主的实现形态、绑定关系的存储与派生索引。 |

- 设计文档的结论与判据以表格和「路径#符号」证据为主，改动代码后若与文档冲突，先按上节复核文档的自包含性，再决定改代码还是改文档。
