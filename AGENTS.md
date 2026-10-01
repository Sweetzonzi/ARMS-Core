# ARMS-Core 开发速查

> 本仓库是 Minecraft NeoForge 1.21.1 模组，Machine-Max 的附属模组，核心提供“机娘”角色运动控制器（`MechaControl` / `MechaCharacter`）。
> 与 `docs/` 中的设计文档互补：代码先读 `ARMS.java` / `ArmsCore.java` / `MechaControl.java` / `MechaCharacter.java`。

## 环境约束

- **Java 21**：`build.gradle:15` 与 `src/main/resources/arms_core.mixins.json` 均要求 `JAVA_21`。
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

# 构建产物（jar 在 build/libs）
.\gradlew build

# 清理构建目录
.\gradlew clean
```

- 没有现成单元测试目录，`src/test` 不存在；`test` 任务当前无意义。
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

- `src/main/templates/META-INF/neoforge.mods.toml` 中的 `${...}` 占位符由 `generateModMetadata` 任务在构建时替换（属性列表在 `build.gradle:103-116`）。
- `src/main/resources` 与 `build/generated/sources/modMetadata` 共同打包；数据生成输出目录 `src/generated/resources` 也被包含进资源集（`build.gradle:93`）。
- 新增资源后若 IDEA 没识别，运行 `gradlew neoForgeIdeSync` 或 `gradlew generateModMetadata`。

## 代码架构要点

| 文件 | 职责 |
|------|------|
| `ARMS.java` | 模组入口，注册配置、服务端事件监听。 |
| `Config.java` | 示例配置（当前为占位），实际逻辑待扩展。 |
| `ArmsCore.java` | 机娘逻辑机甲单元，实现 `IPartAssembly` + `MechaControlHolder`；目前为骨架。 |
| `MechaControl.java` | 角色运动控制器编排器：输入消费 → 状态机 → 动画 → KCC 物理积分。 |
| `MechaCharacter.java` | 基于 Bullet `PhysicsCharacter` 的运动学胶囊控制器（KCC），包含行走力、跳跃蓄力、动画根位移合成。 |
| `MechaLogicStateMachine.java` | 状态机顶层封装，组合 `PostureLogicGraphs` / `GaitSubGraphs` / `VerticalSubGraphs`。 |
| `ARMSClient.java` | 客户端入口：监听 `ClientTickEvent` 读取 WASD/跳跃，并在物理步回调中驱动 `MechaCharacter`。 |

- 线程模型：主线程写 `volatile` 输入（`setMoveInput` 等），物理线程（`PhysicsLevelTickEvent.Pre`）在 `prePhysicsTick` 中读取；不要跨线程直接读写物理状态。
- 当前状态机与动画模块为部分实现（大量 `TODO`），新增状态机节点/子图需同时更新 `MechaLogicStateMachine` 的 `children` 映射。

## 代码风格与依赖

- 使用 **Lombok**：`@Getter` 等注解已启用（`repositories.gradle:114-115`）。
- 使用 **Mixin**（`arms_core.mixins.json`），但当前 `mixins` / `client` 数组为空；新增 Mixin 需同步写入该文件。
- 编码统一为 UTF-8（`build.gradle:18`）。
- 包结构：`io.github.sweetzonzi.arms_core.*`，与 `mod_group_id` 一致。

## 文档编辑规范

### 自包含性检查（编辑任何文档后、报告完成前必做）

**适用范围**：`docs/`、`AGENTS.md`、代码注释与 Javadoc。

**要求只有两条。**

1. **对外部依赖：可以读别的文档，但必须交叉引用。** 本文档的结论、判据、字段含义写在自己文内；引用别的文档或源码时给出可定位的坐标（`docs/X.md` 的节号、`../Spark-Core/.../PhysicsLevel.kt:62`）。允许把支撑论述留在外部——读者顺着引用去读即可，但不给引用就引用，等于要求读者自己猜，属于违规。
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

**完成条件（汇报格式）**

1. 命中清单：`文件:行` + 原句
2. 逐条处理：改写后的句子 / 已移入附录 / 判 C 类并说明基线或引用出处
3. 交叉引用复核：本次涉及的 §号与阶段号是否仍指向存在的目标
4. C 类判定有争议时，一律改写为自足表述

**与上游的关系**：本节的第 2 条与 `../Machine-Max/AGENTS.md`「文档编辑规范」的判据一致（那份文档不允许以自身旧版本为前提）；第 1 条在本项目放宽——允许读者顺着交叉引用去读别的文档与源码，只要引用可定位。

**一句话版**：凡出现「不再/仍然/改为/之前/取消/（原…）」等对照词，判断该对照的基线是否在同一份文档里给出；没有就给基线、改成绝对陈述、或移到文末附录。凡引用别的文档或源码，必须给出节号或「文件:行」。

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

- 设计文档的结论与判据以表格和「文件:行」证据为主，改动代码后若与文档冲突，先按上节复核文档的自包含性，再决定改代码还是改文档。
