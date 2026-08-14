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

## 调试与运行

- VS Code 已配置启动项 `.vscode/launch.json`（Client / Data / GameTestServer / Server），依赖 `build/moddev/` 下的参数文件；若文件不存在，先运行一次 `gradlew prepareClientRun` 等任务生成。
- 物理/动画日志：`MechaLogicStateMachine.DEBUG_LOG = false`，临时设为 `true` 可打印状态转移。
- `MechaCharacter` 中行走力、跳跃、摩擦等参数集中在 `MechaWalkingAttr` / `MechaJumpAttr`（`common/control/attr`）。

## 文档参考

- `docs/总体设计文档.md`：架构、IArmsHost、ArmsCore、MechaControl 双层刚体、输入路径。
- `docs/角色控制器-行走物理设计.md`：KCC 力学模型、抓地力、跳跃蓄力、多通道合成。
- `docs/分层控制器与状态机设计.md`：状态机、MoLang 集成、动画驱动。
- `docs/MechaControl设计文档.md`：早期 MechaControl 接口设计。
