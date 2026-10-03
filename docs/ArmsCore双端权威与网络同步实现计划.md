# ArmsCore 双端权威与网络同步实现计划

> **状态**：阶段 0、1、2 已落地并通过下述验证；阶段 3、4 待执行（见 §4 各阶段标题后的状态标记）
> **目标**：让 `ArmsCore` 由仅存在于客户端，变为双端存在、服务端权威，并建立两条独立同步通道——服务端 → 客户端的位姿与状态通道，客户端 → 服务端的输入通道。
> **范围**：`ArmsCore` 的权威归属、创建与移除协议、两条同步通道、tick 驱动、宿主输入契约。不含显示实体本身的实现，不含 SubPart 的物理与渲染。
> **本文自包含**：判定标准见 `AGENTS.md` 的「文档编辑规范」。具体到本文——全部符号、字段与判据在文内定义，结论写在正文，证据以「路径#符号」形式落在 §8 与各节表格，可在目标文件里直接检索该符号核验；形态快照另按「路径:行 @ 提交」标注，用 `git show <提交>:<路径>` 复现。允许把逐行论述留在外部（如 `docs/角色控制器-行走物理设计.md` 的 §10.3、§11），但每处外部引用都带节号或上述两种坐标之一，可顺引用直接核验。各节之间只有前向引用（如 §3.4、阶段 1.5），不存在指向本文以外或本文其他版本的隐含前提。
> **非前置参考**：`docs/总体设计文档.md`（`IArmsHost` 的原始设定、§2.4.5 的两条输入路径）、`docs/下一步开发TODO.md`（`LogicStateSnapshot` 的需求条目与跨线程决策）。本文不要求先读其中任何一份。

### 0.1 实现与验证状态

| 阶段 | 状态 | 验证方式 |
|------|------|----------|
| 阶段 0：前置修正 | 已落地 | `MechaBodyPresetTest`；`ArmsCore(Level, UUID)` 构造 |
| 阶段 1：服务端权威 + 下行通道 | 已落地 | `ARMSNetworkCodecTest` 的编解码往返；专用服务端实测（见下方「验证记录」） |
| 阶段 2：客户端输入上行 | 已落地 | 服务端实测中的输入写入链路；服务端合并与序号幂等由 `MechaInputHandler` 实现 |
| 阶段 3：同步通道完善 | 待执行 | — |
| 阶段 4：宿主与装配体接入 | 待执行 | — |

**不在阶段清单内但已补齐的一项**：状态机产出到物理的落地（速度倍率、闪避冲量、姿态轮廓）。
本计划的分阶段只覆盖「状态机怎么跑」与「状态怎么同步」，不含「产出怎么变成物理效果」；
其中速度倍率与闪避冲量已落地并验证，姿态轮廓受库约束阻塞。详见 §3.12.1 与 §3.12.2。

#### 验证记录

下表是在专用服务端上实测得到的数字。测量方式是临时的：当时用一个启动即自建 `ArmsCore`、
跑一段固定时间线（稳定站立 → 向前走 → 蹲伏走 → 投递 `DODGE`）并在结束时打印观测量的
自检夹具完成，该夹具已从仓库移除，因此这些数字**不可由当前代码一键复现**，只能作为实现
当时的验收证据；重新验证需要另建同类夹具或按「手动验证」逐项操作。

| 观测量 | 实测值 | 对应判据 |
|--------|--------|----------|
| 状态机在服务端运行 | `posture` 由 `air` 转 `stand`，`vertical` 由 `fall` 转 `ground` | 阶段 1 验收第 1 条的前半 |
| 下行带宽 | 80 tick 内 5–8 个增量包、13–17 个脏条目，单包最大 5 条（字段表上限 8） | 静止站立时**不**每 tick 成包，满足阶段 1 验收的带宽上界 |
| 成过包的字段 | `pos` / `vel` / `energy` / `posture` / `vertical` / `jumpCharging` | 位姿与逻辑五项都能过线；`gait` 缺项是因为该次运行全程 `gait = idle`，非未同步 |
| 上行 → 物理 → 逻辑 → 同步整链 | 写 `jumpPressed = true` 后观察到 `jumpCharging = true`、`vertical` 非 `ground` | 阶段 2 验收的上行链路 |
| 位姿变化进入脏批 | 5 m 显著位移在 1 个 tick 内进入同步容器 | 阶段 1 对位姿字段的采样机制 |
| 逻辑层 → 物理：速度倍率 | 站立 40 tick 位移 `2.33 m`，蹲伏同样 40 tick 位移 `0.70 m`，比值 **0.300** | 与 `Posture.CROUCH.speedModifier()` 一致（§3.12.1）。判据是比值；位移与速率的绝对值属于这次运行时的实现。当前实现下站立稳态速率 = `v_ref` = 6.0 m/s、蹲伏 = 1.8 m/s，回归测试 `MechaCharacterWalkPhysicsTest.java#moveSpeedModifierScalesTheEquilibriumSpeed` |
| 逻辑层 → 物理：闪避冲量 | 投递一次 `DODGE` 后水平位移 **1.24 m** | 该值取自「冲量走位移叠加通道、按窗口线性衰减」的旧实现，其设计式 `6 × 0.4 / 2 = 1.2 m` 已不适用（§3.12.1）。当前实现下冲量是速度阶跃，位移是派生量，回归测试 `MechaCharacterWalkPhysicsTest.java#dodgeImpulseStepChangesTheVelocityVector`、`#dodgeImpulseSurvivesTheAirCeiling` |
| 逻辑层 → 物理：姿态轮廓 | 无产出 | 蹲伏 / 卧倒的碰撞体积未实现，原因见 §3.12.2 |
| 注册表生命周期 | 注销后该维度实例数归 0 | 阶段 1 验收的重连 / 换维度无残留 |

包数与条目数写成区间而不是定值，因为多次运行的实测结果有差异，差异来自位姿在浮点精度边界上
抖动的次数不同（`SynchedEntityData.set` 的判等落在 `Objects.equals` 上，见 §3.3）。

**尚未验证的部分**（不得由上表推断）：真实玩家登录客户端后的手感与视觉表现、丢包注入下的
序号重发行为、换维度与重连的补发路径。这三项需要**一个带客户端的会话**，上表的测量方式不覆盖。

---

## 1. 背景与起点

### 1.1 `ArmsCore` 的定位

`ArmsCore` 是**装配体级**对象，与 Machine-Max 的 `VehicleCore` 同级：它由 `Part` 通过 `AbstractConnector` 组装而成，实现 `IPartAssembly`，并额外持有一个角色运动控制器 `MechaControl`（内含 KCC `MechaCharacter`）与逻辑状态机 `MechaLogicStateMachine`。

它与 `VehicleCore` 的关键差异在于**双向耦合**：

- KCC 把根 SubPart 的 `body_root` 骨骼通过 `New6Dof` 世界锚点约束挂在自身上，因此 **KCC 驱动根 SubPart**；
- `MechaControl.extractAnimRootDelta()` 从 `body_root` 骨骼的帧间位移与偏航增量中提取动画根位移，写回 KCC，因此 **SubPart 的姿态反过来影响 KCC 的运动**；
- 两者并不等同：`MechaCharacter.setSeparationDistance(...)` 维护的分离距离是独立的一等量，它折减行走力（`sepFactor = 1 - sepDist / SEP_MAX`），并在超过 `SEP_MAX` 时触发 `RAGDOLL`。
- **位置与朝向各自解耦**。约束是单端世界锚点（`New6Dof`，bodyA=null）：每步把锚点更新到控制器位置，躯干被"牵引"跟随，而牵引不是刚性的——躯干拖后即产生上面那个分离距离。旋转同理：WALK 全锁、DRIVE 为弹簧阻尼全锁（允许 ±2° 微倾）、RAGDOLL 时**平移与旋转约束全部解除**（`docs/角色控制器-行走物理设计.md`：§2.1、§2.3、§2.4）。因此**控制器侧朝向与躯干朝向是两个量**：前者经约束传导给后者，后者可以落后、可以倾斜，RAGDOLL 后完全独立。这与布娃娃中"实体朝向"与"躯干朝向"的关系同构。
- 位置分离有显式一等量（`separationDistance` / `SEP_MAX`），**朝向没有对应的角量**，见 §7 Q2。

因此 **KCC 位姿与根 SubPart 位姿承载不同的信息，各自需要一条同步通道**：KCC 不在任何 `Part` / `SubPart` 内，SubPart 通道表达不了它；根 SubPart 的位姿由 SubPart 刚体自身决定，KCC 通道也表达不了它。

**但这条耦合不是第一阶段的前置。** `MechaControl` 全文不调用 `getRootSubPart()` 与 `getAttr()`（仅在 `MechaControlHolder` 声明、`ArmsCore` 存根与 `ClientMechaTestRig`（计划写作时的客户端测试夹具，见 §1.2）中出现），`extractAnimRootDelta()` 当前是空实现。因此 `rootSubPart == null`、`Part` 装配缺席时，`ArmsCore` 与状态机可以完整运行，代价只落在表现层：动画根位移与动画驱动的转身不生效（§3.12）。控制器朝向不在这个代价里——它由视野偏航独立驱动（§1.2），与装配体是否接入无关。本计划据此把装配体装配推到阶段 4。

### 1.2 当前实现状态

本节的前三张表记录的是**计划写作时（提交 `93924d9`）**的形态：表中的 `:行` 都是该提交下的行号，可用 `git show 93924d9:<文件>` 核验，它们**不指向当前代码**（表头已标 `@ 93924d9`）。第四张表是 Spark-Core 的活引用，按「文件#符号」给出；本节末尾与 §1.3 的行内引用同样按当前代码给出坐标。

**`ArmsCore` 是骨架**（`src/main/java/io/github/sweetzonzi/arms_core/common/ArmsCore.java`）：

| 位置 @ `93924d9` | 内容 |
|------|------|
| `:20` | `class ArmsCore implements IPartAssembly, MechaControlHolder` |
| `:25` | `new MechaControl(this, null)` —— 构造时 KCC 传 `null` |
| `:29-32` | `getAssemblyId()` 返回 `null` |
| `:35-37` | `getLevel()` 返回 `null` |
| `:95-97` | `getRootSubPart()` 返回 `null` |
| `:99-102` | `getAttr()` 返回 `null` |

`MechaControl.onPhysicsStep()` 进入 `frameLogic → writeStateInputs` 后第一句即 `kcc.onGround()`（`MechaControl.java#writeStateInputs`），因此 **KCC 为 `null` 时 `ArmsCore` 无法被驱动**：任何驱动 `ArmsCore` 的实现都必须先有一个真实的 `MechaCharacter`。真 KCC 的构造因此是阶段 1 的交付物（阶段 1.4），而不是更晚阶段的前置。

**唯一的可用闭环在客户端**（`.../client/ARMSClient.java`）：

| 位置 @ `93924d9` | 内容 |
|------|------|
| `:44` | `private static volatile ClientMechaTestRig rig;` —— 一个 JVM 一个夹具 |
| `:47-49` | `initialized` 布尔量置位后不复位，切维度 / 重生 / 重连后不会重建 |
| `:113-126` | `PhysicsLevelTickEvent.Pre` 订阅者调用 `mechaControl.onPhysicsStep(1f / tps)` |
| `:122-124` | `tickCount % 600 == 0` 时把 KCC `warp` 到玩家坐标 |
| `:132-158` | 初始化读 `Minecraft.getInstance()`、`player.level()`、`SparkLevel.getPhysicsLevel(player.level())` |
| `:39-41` | 胶囊常量 `CAPSULE_RADIUS = 0.4f`、`CAPSULE_HEIGHT = 1.6f`（客户端私有） |

`ClientMechaTestRig`（`src/main/java/io/github/sweetzonzi/arms_core/client/ClientMechaTestRig.java:33-48 @ 93924d9`，该类不在当前代码中）是一个最小 `MechaControlHolder` 实现：构造 `MechaCharacter(shape, physicsSpace)` 与 `MechaControl(this, kcc)`，`getRootSubPart()` 返回 `null`，`getAttr()` 返回空存根。

**`MechaControl.onPhysicsStep(dt)` 的内部顺序**（`.../common/control/MechaControl.java#onPhysicsStep`）：

```
frameLogic(dt)            // 取事件批 → 汇入变量 → 推进状态机 → 转发输入
extractAnimRootDelta()    // 读 body_root 骨骼位姿 → kcc.setAnimRootDelta(...)
kcc.prePhysicsTick(dt)    // 行走力 / 跳跃 / 碰撞 sweep
```

**`MechaCharacter` 朝向相关的形态快照**（`.../common/control/MechaCharacter.java`，下表行号属于该提交的版本）：

| 位置 @ `93924d9` | 内容 |
|------|------|
| `:47` | `class MechaCharacter extends PhysicsCharacter`（Bullet `btKinematicCharacterController`，非刚体） |
| `:126-135` | 字段及其注释：「控制器当前 Y 轴朝向（弧度），由 `applyAnimRootYaw` 累积动画根骨骼 Y 旋转增量」；该字段为 `volatile`，并有 `getCurrentYaw()` 供主线程读取（阶段 0.1） |
| `:234-245` | `setAnimRootYawDelta` 的方法与其 javadoc：「在 `prePhysicsTick` 中直接叠加到 KCC 当前 Y 旋转」 |
| `:294` | 类注释：「KCC 本身没有旋转概念，我们在 `MechaCharacter` 内自行维护一个 `currentYaw` 字段」 |
| `:298-302` | `applyAnimRootYaw()` 中 `currentYaw += animRootYawDelta` |
| `:420-424` | `updateWalk()` 用 `currentYaw` 的 `sin`/`cos` 旋转输入方向 |

`currentYaw` 是 KCC 的**绝对** Y 朝向，在整个类中**不被写回刚体**（`PhysicsCharacter` 不是刚体，没有 `angularFactor`）。它的唯一写入方是 `MechaCharacter.setViewYaw(float)`：`MechaControl.applyFacing()` 每个物理步用快照的 `viewYaw` 调用它，写入时归约到 [−180, 180)，且该调用早于本步一切按朝向解算的量。死亡（含 ragdoll）时 `applyFacing()` 跳过写入，朝向停在最后一帧。

**输入方向只旋转一次。** `MechaControl.applyMoveIntent` 把玩家视角相对的意图（正 = 前进 / 左移）原样交给 KCC，`MechaCharacter.setMoveIntent` 用 `currentYaw` 把它转成世界方向，`updateWalk` 只消费这个结果、自身不做旋转：

```java
// MechaCharacter.setMoveIntent —— 行走方向的「意图 → 世界」
worldX = strafe·cos(currentYaw) − forward·sin(currentYaw);   // 意图已归一化
worldZ = forward·cos(currentYaw) + strafe·sin(currentYaw);
```

闪避方向（`MechaControl.resolveDodgeDirection`）按同一个 `currentYaw` 独立解出同一公式，其结果只用于闪避冲量、不叠加到行走方向上，因此不构成第二次旋转。两条路径取的都是本步 `applyFacing()` 写下的角，于是 WASD 的「前后左右」始终是当前朝向下的一对轴。

**物理世界是双端的，且两端速率不同**（`../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/level/`）：

| 位置 | 内容 |
|------|------|
| `PhysicsLevelApplier.kt#load` | 服务端 `ServerPhysicsLevel(level, 5, ...)` |
| `ClientPhysicsLevelApplier.kt#load` | 客户端 `ClientPhysicsLevel(level, 3, ...)` |
| `PhysicsLevel.kt#tps` | `val tps = baseStep * 20` → 服务端 100 Hz，客户端 60 Hz |
| `PhysicsLevel.kt#stepPhysics` | `stepPhysics()` 按实测帧时间在 `minStep..maxStep` 间动态调整每 tick 步数 |
| `ServerPhysicsLevel.kt#requestEntities` | 服务端 `requestEntities()` 返回 `mcLevel.allEntities` |
| `ClientPhysicsLevel.kt#requestEntities` | 客户端只返回摄像机 `renderDistance` 内的实体 |
| `PhysicsLevelApplier.kt#mcLevelTask` | 服务端在 `LevelTickEvent.Pre`（`HIGH`）调 `physicsLevel.requestStep()`，物理步在独立线程执行 |

### 1.3 结论：必须单端权威

上述速率差异、步数自适应、刚体集合差异，加上 Bullet 的浮点非确定性，使得"两端各自跑一份 KCC 并期望一致"不成立。而逻辑状态机的输入全部派生自 KCC：

```java
// MechaControl.java#writeStateInputs
variables.set(StateVariableKeys.ON_GROUND, kcc.onGround());
variables.set(StateVariableKeys.SPEED, horizontalSpeed);      // |v_xz| / dt → m/s（§3.10）
variables.set(StateVariableKeys.VERTICAL_SPEED, stateVelocity.y);  // 已是 m/s，不得再除以 dt（§3.10）
variables.set(KCC_JUMP_CHARGING, kcc.isChargingJump());
```

`gait` 的 `idle ↔ drift` 使用阈值化的 `SPEED`，`stand` 的 `ground ↔ jump_charge` 镜像 `KCC_JUMP_CHARGING`（`.../state/preset/VerticalSubGraphs.java#buildStandVert`）。两份 KCC 一旦分毫不同，就会在着地判定边界产生不同的 `ON_GROUND`，进而分出不同的 `posture`，再分出不同的 `CAN_MOVE`。**状态机放大差异，不收敛差异。**

因此本计划确立：**服务端是唯一权威端；客户端不运行 KCC，只消费同步结果。** 状态机不需要同步，它只存在于权威端；跟随端若需要状态用于渲染与动画门控，从同步变量重建。

由此派生一条硬约束，贯穿全部阶段：**阶段 1 起 KCC 只在服务端构造，客户端不持有 `MechaCharacter` / `MechaControl`。** `ClientMechaTestRig` 在阶段 1 退役（阶段 1.8），客户端只保留按 `coreId` 索引的可视锚点。该形态下每台权威端各自驱动自己的 `ArmsCore`，不存在两个驱动源。单一客户端 KCC 的做法列在附录 A.1。

---

## 2. 设计决策

### 2.1 决策清单

| 编号 | 决策 | 理由 |
|------|------|------|
| D1 | 服务端为唯一权威端，客户端不运行 `MechaCharacter` 与 `MechaLogicStateMachine` | §1.3 |
| D2 | `ArmsCore` 的网络身份使用 `UUID` | 它是装配体级对象，与 `VehicleCore` 同级；宿主形态各异，UUID 免去整数 id 在双端之间的交接 |
| D3 | 服务端 → 客户端的位姿与状态增量载体为原版 `SynchedEntityData`，由 `ArmsCore` 自身实现 `SyncedDataHolder` 并持有该实例 | 增量同步与变更门控是现成的（§3.3）；`ArmsCore` 已经依赖 `net.minecraft.world.level.Level`，不引入新的分层代价 |
| D4 | 增量字段固定为 8 项，见 §2.2；**只追加** | 覆盖 KCC 位姿与逻辑层状态；宿主绑定不进增量 |
| D5 | 对象的存在性与宿主绑定走创建 / 移除包，不进每 tick 增量包 | `SynchedEntityData` 的 accessor 需要固定序列化器，装不下多态宿主引用（§3.4） |
| D6 | 创建包携带**全量初值**（`getNonDefaultValues()`） | `packDirty()` 只在变化时发包，后加入 / 重连的客户端否则永远看不到当前状态（§3.4） |
| D7 | 输入走独立的上行包 `MechaInputPayload`，客户端每 tick 至多一包；离散边沿带单调序号 | 单帧边沿（跳跃松开 `MechaEvent.JUMP_RELEASE`、`DODGE` 等）跨网络不可靠，序号使其幂等且不丢（§3.11） |
| D8 | 服务端每 tick 读取 KCC 是**唯一**的物理状态出口，由 Level 级注册表 + `LevelTickEvent.Post` 扇出 | 与宿主形态解耦；宿主驱动会因宿主不同而时机不同、且漏调即停摆 |
| D9 | 物理步驱动由 Level 级注册表 + `PhysicsLevelTickEvent.Pre` 扇出，每步恰好一次 | 与 `VehicleCore` 的 `ObjectManager.onPrePhysicsTick` 同相位（§3.6、§3.9） |
| D10 | `ArmsCore` 实现 `MechaControlHolder`，作为统一控制输入点；宿主侧引入 `IArmsHost` 提供上下文 | 阶段 4；`ArmsCore` 已实现该接口（`ArmsCore.java#ArmsCore`） |
| D11 | 物理线程计算并发布不可变快照，主线程写入 `syncedData` 并发包，频率为 20 Hz 主线程 tick | `SynchedEntityData` 非线程安全（§3.7） |
| D12 | 本期不实现位姿外推，客户端在收到的相邻两个采样之间线性插值 | 插值已给出连续运动，外推只缩短恒定延迟，而客户端没有本地模拟与之对照 |
| D13 | 枚举的线上身份使用其 `molangName()` 字符串 | `molangName()` 已是状态图的稳定节点标识；使用 `ordinal` 会在枚举中间插入常量时整体平移（§3.2） |
| D14 | `pos` / `vel` 使用 JOML `Vector3f`（`EntityDataSerializers.VECTOR3`） | 该序列化器即为 JOML 类型；与 Machine-Max `DestroyableObject` 的 `DATA_POS_ID` 一致 |
| D15 | 位姿、速度与 `currentYaw` 由主线程直接读 KCC，只有逻辑状态经不可变 `LogicStateSnapshot` 跨线程 | `SynchedEntityData` 的 accessor 各自独立，不需要统一载体；`DestroyableRigidObject.postTick()` 已确立主线程直读物理体的先例（§3.5） |
| D16 | `DATA_YAW` 承载**控制器侧**的朝向，躯干朝向仍由 SubPart 通道承载 | 两者解耦且躯干存在受约束的滞后（§1.1）：控制器侧朝向是上游权威量，躯干是下游结果 |
| D17 | `DATA_YAW` 发送 `MechaCharacter.currentYaw` 本身，即 KCC 的绝对 Y 朝向 | 该字段的语义是"控制器当前 Y 轴朝向"（`MechaCharacter.java#currentYaw`、`#setViewYaw`）。它由视野偏航绝对驱动，但**不等于**线上载荷里的 `viewYaw`：写入经过 [−180, 180) 规约，且死亡（含 ragdoll）时被冻结（`MechaControl.java#applyFacing`） |
| D18 | 客户端不为 `ArmsCore` 重建任何物理体，只按 `DATA_POS` / `DATA_YAW` 摆放一个非实体可视锚点 | 与 `MMPartEntity` 同形（§3.13）；避免"客户端不跑物理"与"客户端要渲染"的冲突，也避免与宿主实体的位置形成双份权威 |
| D19 | `ArmsCore` 持有 `Level`，并由 `MechaCoreRegistry` 按维度注册；生命周期跟随宿主 / 装配体，不注册为世界实体 | `docs/总体设计文档.md` §2.1 明确 `ArmsCore` 不注册到 `ObjectManager` |
| D20 | 阶段 1–3 允许 `rootSubPart == null`、`Part` 装配缺席、胶囊尺寸取阶段 0 的临时参数 | §1.1 的耦合不构成运行前置；表现层缺失在 §3.12 显式登记 |

### 2.2 增量字段表

| accessor 常量 | 类型 | 序列化器 | 内容与语义 |
|---------------|------|----------|------------|
| `DATA_POS` | `org.joml.Vector3f` | `VECTOR3` | KCC 物理位置（世界坐标，胶囊中心）。它是角色控制器自身的位置，不等于根 SubPart 的位置，也不等于宿主实体的位置（§3.14） |
| `DATA_VEL` | `org.joml.Vector3f` | `VECTOR3` | KCC 线速度。**水平与垂直分量单位不同**（§3.10）：水平为每物理步位移，垂直为 m/s。它用于未来本地插值 / 本地物理查询，当前阶段只做透传 |
| `DATA_YAW` | `net.minecraft.core.Rotations` | `ROTATIONS` | `MechaCharacter.currentYaw`，即 KCC 的**绝对** Y 朝向，单位**度**，仅偏航有效。它与躯干朝向解耦（§1.1、D16、D17）：躯干朝向由 SubPart 通道承载，是受约束牵引的下游量，可以落后、可以倾斜，RAGDOLL 后完全独立 |
| `DATA_POSTURE` | `String` | `STRING` | `Posture.molangName()` |
| `DATA_GAIT` | `String` | `STRING` | `Gait.molangName()` |
| `DATA_VERTICAL` | `String` | `STRING` | `Vertical.molangName()` |
| `DATA_ENERGY` | `Float` | `FLOAT` | 逻辑层能量值 |
| `DATA_JUMP_CHARGING` | `Boolean` | `BOOLEAN` | `kcc.isChargingJump()` 的镜像 |

字段表的变更纪律：**只追加**。删除、重排或在中间插入会使该位置之后的 id 全部平移，导致双端线上格式错配（§3.2）。废弃字段保留并实现为 no-op。

### 2.3 两条通道的分工

| | 上行（客户端 → 服务端） | 下行（服务端 → 客户端） |
|---|---|---|
| 载荷 | `MechaInputPayload`（§3.11） | `ArmsCoreCreatePayload` / `ArmsCoreRemovePayload` / `MechaCoreSyncPayload`（§3.4） |
| 频率 | 每客户端 tick 至多一包（20 Hz），无变化不发 | 每服务端 tick 一次 `packDirty()`；无脏数据不发 |
| 内容 | 连续量（移动 / 视角）+ 按键集合 + 事件序号 | 8 项增量；创建包另带全量初值与宿主绑定 |
| 权威 | 客户端只表达意图，服务端合并环境状态后写入 | 服务端唯一写入方，客户端只读 |

---

## 3. 依赖机制说明

本节把本计划用到的外部机制就地说明，使读者无需查阅 Spark-Core、Machine-Max 或 Minecraft 的源码文档。Minecraft 源码可通过解压 `build/moddev/artifacts/neoforge-21.1.219-sources.jar`（人类可读）或 `neoforge-21.1.219-merged.jar` 内附的 `*.java` 获得。

### 3.1 `SynchedEntityData` 可用于非实体对象

原版的脏数据同步机制不要求持有者是 `Entity`：

| 符号 | 签名 / 事实 |
|------|-------------|
| `net.minecraft.network.syncher.SyncedDataHolder` | 接口，仅两个方法：`onSyncedDataUpdated(EntityDataAccessor<?>)` 与 `onSyncedDataUpdated(List<DataValue<?>>)` |
| `SynchedEntityData.Builder` | 构造参数类型为 `SyncedDataHolder` |
| `SynchedEntityData.defineId` | `static <T> EntityDataAccessor<T> defineId(Class<? extends SyncedDataHolder>, EntityDataSerializer<T>)` |
| `EntityDataSerializers.ROTATIONS` | 值类型 `net.minecraft.core.Rotations`，其构造器对三个分量取 `% 360` 并对 NaN/Inf 归零，因此单位是度且自动环绕 |

Machine-Max 的 `DestroyableObject`（一个非实体、非刚体的抽象类）即建立在此之上：`DestroyableObject.java#DestroyableObject` 声明 `implements SyncedDataHolder`，`#syncedData` 持有 `protected final SynchedEntityData syncedData`，`#DestroyableObject`（构造器）内用 `new SynchedEntityData.Builder(this)` 定义初值。

### 3.2 id 分配语义

`defineId(cls, serializer)` 通过 `net.minecraft.util.ClassTreeIdRegistry` 分配 id，其语义为：

```java
public int define(Class<?> cls) {
    int i = getLastIdFor(cls);          // 缓存命中则直接返回
    int j = (i == -1) ? 0 : i + 1;      // 否则 = 父类链上最后一个 id + 1
    cache.put(cls, j);
    return j;
}
public int getLastIdFor(Class<?> cls) {
    // 先查 cls 自身缓存；未命中则沿 getSuperclass() 逐级上溯，到 Object 为止
    // getSuperclass() 不返回接口
}
```

由此得到三条可操作的约束：

1. **追加安全**：在类的静态初始化里多写一个 `defineId` 调用，只是 `lastId + 1`，已有 id 不变。
2. **中间插入 / 删除 / 重排不安全**：id 由 `defineId` 的调用顺序决定，即静态字段的文本顺序。
3. **`Builder` 用运行时类取容量，`build()` 要求 id 区间连续**：`new Builder(holder)` 以 `ID_REGISTRY.getCount(holder.getClass())` 为数组长度（`holder.getClass()` 是运行时类，会沿父类链取起点），而 `build()` 会遍历 `[0, count)` 并在任一槽位为空时抛 `IllegalStateException`。

实践要求：**全部 accessor 声明为 `static final`，且其静态初始化先于 `new SynchedEntityData.Builder(this)` 完成。** 把 `Builder` 调用放在构造器内即可满足（类初始化先于任何实例构造）。`ArmsCore` 的 `getSuperclass()` 是 `Object`（`IPartAssembly` 与 `MechaControlHolder` 是接口），因此 id 从 0 开始。

**客户端必须在收到增量包之前完成一次 `build()`。** 客户端的 `ArmsCore` 由创建包触发构造（§3.4），构造器内同样 `build()`，因此到达顺序（创建包先于增量包）保证了这一点。若客户端实现为"先建壳、后填数据"，则必须确保 `syncedData` 已构建，否则 `assignValues` 会在空槽位上失败。

### 3.3 增量与变更门控

| 符号 | 语义 |
|------|------|
| `SynchedEntityData.set(accessor, value)` | 用 `ObjectUtils.notEqual(新值, 旧值)` 判等（最终落到 `Objects.equals`），值变才置脏。**非线程安全** |
| `SynchedEntityData.packDirty()` | 若 `isDirty == false` 返回 `null`；否则清除脏标记并返回本批变化的 `List<DataValue<?>>` |
| `SynchedEntityData.getNonDefaultValues()` | 返回与默认值不同的全部条目，不清除脏标记。创建包用它携带全量初值（D6） |
| `DataItem.setValue(v)` | 直接存引用，不做防御性拷贝 |

三条由此导出的结论：

- 低频率字段（`DATA_POSTURE` / `DATA_GAIT` / `DATA_VERTICAL` / `DATA_ENERGY` / `DATA_JUMP_CHARGING`）在状态稳定时不产生任何流量，无需自行实现比较与脏跟踪。
- **高频字段每 tick 都重新采样**：`DATA_POS` / `DATA_VEL` 每 tick 从 KCC 读入新的 JOML 向量、`DATA_YAW` 每 tick 构造新的 `Rotations`。写入的值与槽中旧值的比较经 `ObjectUtils.notEqual` 落到 `Objects.equals`（`Rotations` 为逐分量比较，见 `net.minecraft.core.Rotations#equals`），因此**只有数值确实相同才会被抑制**；位姿只要有浮点级抖动就会进入本批脏数据，静止的客户端也可能每 tick 收到这三个字段。阶段 1 的验收判据据此写成带宽上界（§4 阶段 1），并要求逻辑字段在无变化时不出现在批里。若需要静止时完全静默，做法是在主线程侧加"值变化阈值门控"（位移 > ε 或偏航 > ε 才 `set`），该项列为 §7 Q3。
- 因为 `setValue` 不做拷贝，**写入的向量对象不得被后续复用**。`DestroyableRigidObject` 的 `SparkMathKt.toVector3f()` 每次返回新对象，正是这个原因；ArmsCore 侧同样不得把 `kcc.getPhysicsLocation(tmp)` 的复用缓冲直接交给 `set`。

`DataValue` 的线上格式自带 `255` 终结符，可直接复用 Machine-Max 的编解码写法（`network/payload/SubPartSyncPayload.java#STREAM_CODEC`）：

```java
// encode
buffer.writeInt(id);
for (DataValue<?> v : values) v.write(buffer);
buffer.writeByte(255);
// decode
int id = buffer.readInt();
List<DataValue<?>> values = new ArrayList<>();
int i;
while ((i = buffer.readUnsignedByte()) != 255) values.add(DataValue.read(buffer, i));
```

### 3.4 创建、增量与移除协议

这是本计划与 Machine-Max 装配体生命周期对齐的部分，形态直接取自 `VehicleCore`。

**四个载荷，三个方向。** 下表四个类型名称均为本计划与本仓库新增，载荷 id 统一放在 `arms_core:` 命名空间下。

| 载荷（载荷 id） | 方向 | 何时发 | 内容 |
|------|------|--------|------|
| `ArmsCoreCreatePayload`（`arms_core:core_create`） | 服务端 → 客户端 | 对象在服务端注册后；玩家登录 / 换维度后补发 | `UUID coreId`、`ResourceKey<Level> dimension`、`int hostEntityId`（无实体宿主为 `-1`）、`List<DataValue<?>> initial`（`getNonDefaultValues()`） |
| `ArmsCoreRemovePayload`（`arms_core:core_remove`） | 服务端 → 客户端 | 对象注销 / 维度卸载 | `UUID coreId`、`ResourceKey<Level> dimension` |
| `MechaCoreSyncPayload`（`arms_core:mecha_core_sync`） | 服务端 → 客户端 | 每 tick，若有脏数据 | `UUID coreId`、`List<DataValue<?>> dirty`（`packDirty()`） |
| `MechaInputPayload`（`arms_core:mecha_input`） | 客户端 → 服务端 | 每客户端 tick，满足 §3.11 的发送条件时 | 见 §3.11 |

**Machine-Max 的对应实现**（照此形态照抄即可）：

| 机制 | 位置 |
|------|------|
| 服务端注册 + 维度广播创建包 | `ObjectManager.addVehicle` → `ObjectManager.java#addVehicle`，载荷 `VehicleCreatePayload(dimension, VehicleData)` |
| 客户端构造 | `VehicleCreatePayload.handle` → `VehicleCreatePayload.java#handle`：`new VehicleCore(context.player().level(), payload.vehicle, true)` + `ObjectManager.addVehicle(...)` |
| 移除 | `ObjectManager.removeVehicle` → `ObjectManager.java#removeVehicle`，载荷 `VehicleRemovePayload` |
| 登录补发 | `ObjectManager.transmitVehicleData(PlayerEvent.PlayerLoggedInEvent)` → `ObjectManager.java#transmitVehicleData`，逐个 `LevelVehicleDataPayload` |
| 换维度请求 | `ObjectManager.loadVehicleData(PhysicsLevelInitEvent)` → `ObjectManager.java#loadVehicleData`，客户端发 `ClientRequestVehicleDataPayload`；服务端处理见 `ClientRequestVehicleDataPayload.java` |

**连接与重连的时序。** 客户端在以下三个时刻都可能是"有维度、无对象"：

```
玩家登录：服务端 PlayerLoggedInEvent → 遍历该维度注册表 → 逐个发 CreatePayload
玩家换维度：客户端本地清空注册表 → 发请求包 → 服务端按新维度补发全部 CreatePayload
玩家重生 / 重连：同"换维度"路径（客户端注册表随 ClientLevel 重建而清空）
```

**幂等要求**：客户端收到已存在的 `coreId` 时，应复用已有实例并只 `assignValues(initial)`，而不是覆盖重建（否则正在插值的锚点会跳变）。服务端收到重复请求同理，不重复注册。

**全量初值不可省（D6）。** `packDirty()` 只在"变化"时发包，而"客户端刚创建"与"服务端上次变更"之间可能相隔任意久。创建包携带 `getNonDefaultValues()` 是唯一能让客户端拿到当前状态的路径。`getNonDefaultValues()` 返回 `null`（即全部字段都等于默认值）时，客户端保持默认值即可。

**顺序前提**：客户端必须在该 `UUID` 的 `ArmsCore` 已存在的前提下收到增量包（D5）。实现上，服务端在同一 tick 内先发创建包再发增量包，且两者都走同一连接（TCP 语义有序），因此顺序天然成立；客户端对未知 `coreId` 的增量包应**丢弃并计数告警**，不得抛异常。

**广播范围**：`PacketDistributor.sendToPlayersInDimension((ServerLevel) level, payload)`。它不需要维护每个 `ArmsCore` 的追踪玩家集合，代价是维度内全广播；实例数量上升后可切换到 `sendToPlayersTrackingEntity` / `sendToPlayer`（R5）。

**客户端应用**：先按 `UUID` 解析出 `ArmsCore`，再对其 `syncedData` 调用 `SynchedEntityData.assignValues(List<DataValue<?>>)`。该调用会按 accessor 触发 `onSyncedDataUpdated`。`SubPartSyncPayload.java#handler` 是同一形态的参考实现。

### 3.5 跨线程读取的两条路径

`SynchedEntityData` 只能由单一线程写入，而它的数据源分布在两处，两者的跨线程代价不同，需要分开处理。

**位姿、速度、`currentYaw`：主线程直接读 KCC。** 这些量由 Bullet 维护，不需要任何中间载体。`DestroyableRigidObject.postTick()`（`DestroyableRigidObject.java#postTick`）在 Machine-Max 中就是这样做的——它在主线程直接读 Bullet 刚体：

```java
// DestroyableRigidObject.java#postTick（主线程）
transform = PhysicsBodyExtensionKt.stateOf(body).getTransform();
setPosition(transform.getTranslation());
setLinearVelocity(body.getLinearVelocity(null));
```

KCC 的幽灵体世界变换由 `playerStep` 在末尾一次性写入（`btKinematicCharacterController.cpp#playerStep` 末尾），因此并发读取拿到的是某一次完整步进的结果，与 `DestroyableRigidObject` 已接受的竞态同类。KCC 侧对应调用是 `getPhysicsLocation(null)`（`PhysicsCollisionObject.java#getPhysicsLocation`，传 `null` 返回新向量）与 `getLinearVelocity(tmp)`。

`MechaCharacter.currentYaw` 由物理线程（`MechaControl.applyFacing` → `setViewYaw`）写入、主线程读取，因此已声明为 `volatile` 并提供 `getCurrentYaw()`（阶段 0.1）。读到的值是某一次完整物理步结束后的结果，无需加锁；写入侧的规约见 `MechaCharacter.java#normalizeViewYaw`。

**逻辑状态：需要不可变快照。** `posture` / `gait` / `vertical` / `energy` / `jumpCharging` 的来源是 `MechaControl` 的 `StateVariableContainer`——物理线程写入的可变容器。主线程不得读取它，因此需要一个物理线程 → 主线程的不可变载体 `LogicStateSnapshot`：

```java
// 物理线程每物理步发布（volatile 引用，不修改已发布对象）
private volatile LogicStateSnapshot logicState;

// 主线程 tick 读取
ArmsCore.this.logicState  →  syncedData.set(DATA_POSTURE, ...) 等
```

**它是什么、谁消费。** 它是这五项的**一次性不可变副本**，存在的唯一理由是那五项住在物理线程独占的容器里（读法本身要求物理线程内访问，如 `MechaControl.java#logStateChanges`），而主线程要拿它们去填 `DATA_POSTURE` / `DATA_GAIT` / `DATA_VERTICAL` / `DATA_ENERGY` / `DATA_JUMP_CHARGING`。**当前唯一消费者是 `ArmsCore` 主线程 tick**（阶段 1.15）；客户端将来读的是 `synchedData` 的这五个字段（渲染与动画门控、MoLang `ctrl.*`），不直接读本快照。

**为什么用 record 而不是让 `MechaControl` 暴露五个 `volatile` 字段**：后者少一个类型、零分配，但五项不是同一时刻的值，且要在 `MechaControl` 上新增五个公开读点；直接暴露 `variables` 容器则违反 `docs/下一步开发TODO.md` §4「不要把可变 `StateVariableContainer` 暴露给主线程或调试 UI」。因此采用与 `MechaConditionSnapshot` 相同的模式，但它**嵌套在 `MechaControl` 内**而不单独占一个公共协议类型——只服务 `MechaControl` 的查询 API。它的归属是 `common/control/`（与 `MechaConditionSnapshot` 同包），不是 `common/net/`：它与网络无关，只是恰好被发包路径消费。

`MechaConditionSnapshot`（`.../common/control/MechaConditionSnapshot.java`）是同一模式的既有实例：不可变 `record` + `volatile` 引用，方向为主线程 → 物理线程；`LogicStateSnapshot` 的方向相反（物理线程 → 主线程），语义相同。

该模式在仓库内已有成文决策（`docs/下一步开发TODO.md` §5.2）：优先使用不可变对象获得清晰的跨线程语义，在性能数据证明存在问题之前不引入对象池。按 100 物理步/秒、每个 `ArmsCore` 一个小对象估算（约 100 次分配/秒），分配压力可以忽略。

两个线程各自要遵守的纪律：`SynchedEntityData.set` 只在主线程调用；`StateVariableContainer` 只在物理线程读写。

### 3.6 装配体的自驱动

`VehicleCore` 把自身装配体的物理步驱动集中在单个方法内：

```java
// VehicleCore.java#prePhysicsTick
public void prePhysicsTick() {
    subSystemController.prePhysicsTick();
    if (inLoadedChunk && !isRemoved) {
        for (Part part : partMap.values()) {
            part.onPrePhysicsTick();
        }
    }
}
```

**构造前置的成立依据**：`ArmsCore` 构造时直接读物理空间
（`SparkLevel.getPhysicsLevel(level).getWorld()`，`SparkLevel.java#getPhysicsLevel`）。`PhysicsLevel.world`
是 `lateinit var`，在默认（多线程）路径下由协程在物理线程上赋值
（`PhysicsLevel.kt#world` 的声明、`#start` 的赋值处），而 Spark-Core 在 `LevelEvent.Load` 中 `setPhysicsLevel(...)`
后立即 `start()`（`PhysicsLevelApplier.kt#load`）。因此这条读取成立的前提是**构造发生在
Level 加载完成之后**——宿主登录、装配体创建包、调试命令都满足该顺序。需要显式确认初始化
完成时，订阅 Spark-Core 的 `PhysicsLevelInitEvent`（`PhysicsLevelApplier.kt#load` 里 `start {}` 之后投递）作为构造时机。

`ArmsCore.prePhysicsTick()` 因此为：

```java
public void prePhysicsTick() {
    // ① Part 层动画混合必须在 extractAnimRootDelta 之前完成（partMap 为空时自然跳过，D20）
    for (Part part : partMap.values()) part.onPrePhysicsTick();

    // ② 状态机推进 → extractAnimRootDelta → KCC 积分（顺序由 MechaControl 内部保证）
    mechaControl.onPhysicsStep(1f / physicsLevel.getTps());

    // ③ 发布逻辑状态到主线程（位姿由主线程直接读 KCC，见 §3.5）
    this.logicState = mechaControl.snapshotLogicState();
}
```

① 早于 ② 是硬约束：`extractAnimRootDelta()` 读取 `body_root` 骨骼位姿，而该位姿由 `Part.onPrePhysicsTick()` 产出。该约束在阶段 1–3 不生效（`partMap` 为空），阶段 4 接入装配后立即生效。

**dt 的正确取法**：`1f / physicsLevel.getTps()`。`PhysicsLevel.stepPhysics()` 对每一步都传同一个 `fixedStep = 1f / tps` 给 `world.update(...)`（`PhysicsLevel.kt#stepPhysics`），因此**单步 dt 是恒定值，不随负载变化**；负载自适应调整的是每 tick 的步数 `dynamicRepeat`（`PhysicsLevel.kt#stepPhysics` 的负载自适应段）。`getTps()` 返回名义值 `baseStep * 20`，服务端为 100。

### 3.7 反馈回路与线程纪律

服务端从物理体读取状态并写入 `syncedData` 时，写入动作本身可能经由 setter 反向推回物理体，形成回路。Machine-Max 用 `updateLock` 阻断：

```java
// DestroyableRigidObject.java
:29  protected boolean updateLock = true;      // 禁止同步应用位姿数据到刚体
:34  if (level.isClientSide()) updateLock = false;
:47  updateLock = true;                        // 锁定…
:50-51  setLinearVelocity(body.getLinearVelocity(null)); setAngularVelocity(...);
:53  updateLock = false;                       // 解锁
:107/118/129/140  setter 内部依据 updateLock 决定是否 submitImmediateTask 到物理线程
```

`ArmsCore` 采用同一纪律：主线程从 KCC 读取 → 写入 `syncedData` 期间不得把数据推回 KCC；`onSyncedDataUpdated` 在服务端一律不做位姿应用（Machine-Max 的第一句就是 `if (!level.isClientSide()) return;`）。

其余线程纪律沿用本仓库与 Machine-Max 的既有约定：

- 不在主线程直接操作物理体，一律经 `SparkLevel.getPhysicsLevel(level).submitImmediateTask(PPhase..., ...)`。
- `SynchedEntityData` 的 `set` 只在主线程调用。
- 主线程读取物理体状态存在良性竞态，`DestroyableRigidObject.postTick()`（`DestroyableRigidObject.java#postTick`）即建立在此前提上。

### 3.8 数学库

物理侧使用 JME（`com.jme3.math.*`），渲染与同步数据使用 JOML（`org.joml.*`），跨界通过 `PhysicsHelperKt.toBVector3f(Vector3f)`（JOML → JME）与 `SparkMathKt.*` 转换。`EntityDataSerializers.VECTOR3` 的值类型是 JOML `Vector3f`。

`MechaCharacter` 内部经 `getLinearVelocity(tmp)`（继承自 `PhysicsCharacter`）取得 JME `Vector3f`（`MechaCharacter.java#updateWalk` 等处）。

### 3.9 事件与优先级

| 事件 | 线程 | Machine-Max 采用的优先级 | 触发点 |
|------|------|--------------------------|--------|
| `LevelTickEvent.Pre` | 主线程 | `EventPriority.HIGHEST` | `ObjectManager.java#onPreTick` |
| `LevelTickEvent.Post` | 主线程 | `EventPriority.NORMAL` | `ObjectManager.java#onPostTick` |
| `PhysicsLevelTickEvent.Pre` | 物理线程 | 默认 | `ObjectManager.java#onPrePhysicsTick` |
| `PhysicsLevelTickEvent.Post` | 物理线程 | 默认 | `ObjectManager.java#onPostPhysicsTick` |

本计划在两个相位各接一次：

- **`PhysicsLevelTickEvent.Pre`**：按 Level 扇出 `ArmsCore.prePhysicsTick()`（D9）。
- **`LevelTickEvent.Post`**：读快照 → 写 `syncedData` → 发包（D8）。与 `DestroyableObject.postTick()` → `syncToClient()` 的相位一致，优先级沿用 `EventPriority.NORMAL`。

> 注意 Spark-Core 自身在 `LevelTickEvent.Pre`（`HIGH`）调 `physicsLevel.requestStep()`（`PhysicsLevelApplier.kt#mcLevelTask`）。本计划不在该相位做同步，避免与物理请求步进竞争。

### 3.10 KCC 线速度的单位约定

`PhysicsCharacter.getLinearVelocity()` 与 `setLinearVelocity()` 的**水平分量与垂直分量单位不同**。这是 Bullet `btKinematicCharacterController` 的设计，库的 javadoc 明确写着（`../Libbulletjme/src/main/java/com/jme3/bullet/objects/PhysicsCharacter.java`）：

> Note that the horizontal units differ from PhysicsRigidBody!
> horizontal components in physics-space units **per time step**, vertical component in physics-space units **per second**

即：**水平 = 每物理步位移，垂直 = m/s。**

原生实现给出了原因。`getLinearVelocity()` 把两者拼接返回：

```cpp
// ../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#getLinearVelocity
btVector3 btKinematicCharacterController::getLinearVelocity() const
{
    return m_walkDirection + (m_verticalVelocity * m_up);
}
```

- `m_walkDirection` 是**每步位移**：`playerStep` 直接把它当位移传给 `stepForwardAndStrafe`（`btKinematicCharacterController.cpp#playerStep`），而 Java 侧 `getWalkDirection()` 的 javadoc 称其为 "the character's **walk offset**"（"an **offset** vector"）。
- `m_verticalVelocity` 是**真正的速度**：按 `m_verticalVelocity -= m_gravity * dt` 积分（`btKinematicCharacterController.cpp#playerStep`），随后 `m_verticalOffset = m_verticalVelocity * dt` 转成位移（同处），并被 `m_fallSpeed`（`btKinematicCharacterController.cpp#btKinematicCharacterController` 构造器注释写明 "Terminal velocity of a sky diver in m/s"）与 `m_jumpSpeed` 钳制。

该结论与调用方转换规则在 `docs/角色控制器-行走物理设计.md`：§10.3、§11 有逐行的源码证据。本节给出同步通道需要遵守的结论（下表），需要展开推导时按上述节号查阅：

| 量 | 换算 |
|----|------|
| 水平速率 `SPEED` | `｜v_xz｜ / dt`：位移/步 ÷ dt → m/s |
| 垂直速率 `VERTICAL_SPEED` | `v_y`：已是 m/s，不得再除以 `dt` |
| 写回水平分量 | `速度(m/s) × dt` → 位移/步 |
| 写回垂直分量 | 动画位移 `m/tick ÷ dt`，或无动画时透传 `v_y` |

`MechaControl` 与 `MechaCharacter` 的现有实现与上表一致，无需改动。把垂直分量也除以 `dt`，或去掉水平分量的换算，都会同时破坏竖直运动与 `SPEED` / `VERTICAL_SPEED` 的量纲一致性。

**对同步通道的影响**：`DATA_VEL` 是逐分量语义不同的向量。客户端若将来用它做本地外推或速度相关的表现，只有垂直分量可直接用于 m/s 语境，水平分量须先 `÷ dt`；当前阶段（D18）只透传不消费。其余分量（`DATA_POS` / `DATA_YAW`）不受本约定影响。

### 3.11 上行输入通道

`MechaControl` 的输入入口只有两个：`MechaControlHolder.writeConditionSnapshot(MechaConditionSnapshot)` 与 `postEvent(MechaEvent)`。阶段 1 起调用方在服务端，因此上游必须由网络补齐。

**`MechaInputPayload` 字段。**

| 字段 | 类型 | 语义 |
|------|------|------|
| `coreId` | `UUID` | 目标 `ArmsCore` |
| `forward` / `strafe` | `float` | 与 `MechaConditionSnapshot` 同约定（正 = 前进 / 左移） |
| `viewYaw` / `viewPitch` | `float` | 玩家视角（度）。`MechaControl.applyFacing` 用 `viewYaw` 绝对赋值控制器朝向（`MechaCharacter.setViewYaw`），行走方向再由该朝向解出 |
| `keyFlags` | `int` 位集 | `jumpHeld`、`sprintHeld`、`walkKeyHeld`、`sneaking` —— 连续量，可丢可合并 |
| `eventSeq` | `int` | 单调递增的事件序号。每次"产生一个事件"自增一次（不是每 tick 自增），服务端据此判断新旧 |
| `eventBits` | `int` 位集 | 本包携带的事件类型集合，位序 = `MechaEvent.ordinal()`（当前 13 项，`int` 足够；超过 32 项时改 `long` 或 `int[]`）。跳跃松开是其中的 `MechaEvent.JUMP_RELEASE`，与 `DODGE` / `TOGGLE_*` 同批投递；快照只承载连续量 |

**离散边沿必须带序号（D7）。** 跳跃松开与 `DODGE` / `TOGGLE_*` 一样是 `MechaEvent` 的一项，都是单帧标记，`MechaControl` 在下一个物理步的第一时间就会 `getAndSet(空集)` 吃掉（`MechaControl.java#frameLogic`（帧首 `getAndSet(空集)` 取走整批））。跨网络若只发一帧，丢包即永久丢失（`docs/下一步开发TODO.md` §3.3「明确跳跃按下、持续、松开三个信号的语义，保证松开边沿在物理线程消费前不会丢失」正是指这条）。实现方式二选一：

1. **事件序号 + 重发（推荐）**：客户端维护一个自增计数 `eventSeq` 与一组"未确认事件位" `eventBits`；只要 `eventBits` 非空，就**强制发包**，把同一 `eventSeq` 与同一 `eventBits` 连续带在**接下来 N 个上行包**里（建议 N = 3），之后才清空。服务端只在 `seq` 比它记录的更大时投递一次事件，因此重复包不会重复触发（幂等），而丢 1–2 个包也不会丢事件。事件停发后若再丢包才会丢失，此时表现为"这一次动作没生效"，与本地帧丢失的观感同级。注意重发窗口的单位是"包"而不是"tick"——否则"仅值变化时发包"会让窗口内根本没有包可带（见下）。
2. **序号 + 显式确认**：服务端在收到并消费后回执 `lastConsumedSeq`，客户端据此缩短重发窗口。可省少量带宽，但引入一个反向包与状态机，本阶段不值得。

**推荐方案 1**：跳跃松开（`MechaEvent.JUMP_RELEASE`）、`TOGGLE_*`、`DODGE`、`STUN`、`MOUNT` / `DISMOUNT` 都是"发生过"语义，重复表达无害；方案 2 的回执通道等到上行带宽真正成为问题时再引入。

**连续量可以丢。** `forward` / `strafe` / `viewYaw` / `keyFlags` 属于"最新值覆盖"语义，与现有 `conditionSnapshot` 的覆盖式写入完全一致，丢包只造成短暂迟滞。因此客户端**仅在值变化时发包**（参考 `MovementInputPayload` 的发送条件，`RawInputHandler.java#handleMoveInputs`），另有两条强制发包条件：`eventBits` 非空（上述重发窗口），以及距上次发包超过 1 s 的心跳。心跳的目的不是补齐延迟，而是让服务端能区分"玩家没动"与"这个客户端的包断了"，从而在必要时按 §3.11 的控制权转移路径重置快照。

**服务端处理链。**

```
payload 到达（主线程，MainThreadPayloadHandler）
  → MechaCoreRegistry.get(player.level(), coreId)
  → 权限校验：该玩家是否是此 ArmsCore 的控制者（阶段 2.5）
  → 合并：上行（输入 / 视角 / 按键） + 服务端本地查询（环境类，D10 / 阶段 4）
  → armsCore.writeConditionSnapshot(merged)     // volatile 发布，物理线程下步读取
  → 按 eventSeq 差投递 MechaEvent               // postEvent，幂等
```

**频率对齐。** 上行取自 20 Hz 客户端 tick，物理步为 100 Hz，两者不是固定 1:5 交替（物理线程独立推进，见 §3.9）。两点必须写成显式约定：

- 连续量复用同一快照是正确的：物理线程只读 `volatile` 引用，快照被下一次上行覆盖不会破坏正在读取的对象。
- **离散事件的"至多消费一次"由 `MechaControl` 的写入侧保证，不由频率保证**：事件经 `postEvent` 进入 `AtomicReference<Set<MechaEvent>>`，物理线程帧首 `getAndSet(空集)` 原子取走整批（`MechaControl.java#pendingEventBuffer`、`#frameLogic`），因此投递一次就只消费一次，与后面跟着几个物理步无关。需要上游避免的只是"把同一个事件投递多次"——这正是 `eventSeq` 幂等的职责（上述方案 1），而不是发包频率的职责。反过来，如果客户端按物理步（100 Hz）发包并每次都生成新 `seq`，同一按键边沿就会被判定为新事件并投递多次。

**控制权转移与断线。** 服务端必须在下列时机重置该 `ArmsCore` 的输入状态，否则状态机会卡在最后一帧（例如 `jumpHeld = true` 永久蓄力）：

- 玩家断开连接 / 换维度 / 退出控制（`ServerPlayer` 卸载）；
- `coreId` 对应的控制者变更。

处理方式：服务端主动 `writeConditionSnapshot(MechaConditionSnapshot.EMPTY)`，并清空待消费事件。

### 3.12 阶段 1–3 的表现层缺失（显式登记）

D20 允许 `rootSubPart == null`，代价必须登记清楚，避免验收表出现永远绿不了或看不出问题的条目：

| 缺失 | 直接后果 | 恢复阶段 |
|------|----------|----------|
| 无 `body_root` 骨骼 | `extractAnimRootDelta()` 无可读对象，动画根位移恒为 0 | 阶段 4 |
| 动画驱动转身未接入 | `animRootYawDelta` 无人写入，`MechaCharacter` 也不消费它。动画根骨骼的 Y 增量要先定义与视野权威的合成方式（叠加为随时间长回视野的偏移，或动画层活跃时暂停跟随）才能生效，见 `MechaCharacter.java#animRootYawDelta` | 阶段 4 |
| 躯干朝向与控制器朝向解耦 | `DATA_YAW` 只承载控制器朝向；躯干朝向由 SubPart 通道承载、且受约束存在滞后（§1.1、D16），装配体接入前不存在，因此「躯干朝哪」无法验收 | 阶段 4 |
| 无 `Part` 装配 | `ArmsCore.prePhysicsTick()` 第 ① 步空转；`getRootSubPart()` / `getAttr()` 返回 `null` | 阶段 4 |
| 胶囊尺寸取阶段 0 的临时参数 | 与最终 `mech_chassis.json` 的 `controller` 段可能不一致，届时需回归一次手感 | 阶段 4 |
| 姿态轮廓不随 posture 变化 | 蹲伏 / 卧倒的碰撞体积仍是站立胶囊；`MechaBodyPreset` 的姿态几何已就位但未接入，原因与两条已失败的路径见 §3.12.2 | 阶段 4.6 |

§1.1 的"SubPart 姿态反向影响 KCC"这条耦合在上述缺失下**一行都没生效**。这一点不得被"阶段 1 验收通过"掩盖。

#### 3.12.1 逻辑层产出到物理的落地

本计划的阶段清单只覆盖「状态机怎么跑」与「状态怎么同步」，**不包含**「状态机的产出怎么变成物理效果」。
这一层没有单独列项，实现它需要下面三项落地：速度倍率与闪避冲量已落地，姿态轮廓受库约束阻塞。
`MechaStateVariableKeys.java` 类注释里那句「MOVE_SPEED_MODIFIER，KCC 直接读取」是事实：该变量由
`MechaStateActions.java#gaitWithModifier` 写入，由 `MechaControl.java#applyLogicOutputToKcc` 每物理步
推给 KCC，`MechaCharacter` 据此缩放控制力。三项的落地位置与判据如下。

| 产出 | 落地位置 | 语义 | 验证方式 |
|------|----------|------|----------|
| 速度倍率 `MOVE_SPEED_MODIFIER`（= posture.speedModifier × gait.baseSpeedModifier） | `MechaControl.java#applyLogicOutputToKcc` 读变量 → `MechaCharacter.java#setMoveSpeedModifier` 写入 → 在 `MechaCharacter.java#updateWalk` 里经 `MechaCharacter.java#controlForceScale` **缩放控制力**（地面与空中同一套系数） | 蹲伏 0.3、卧倒 0.1、硬直 0；稳态速率随倍率等比缩放（§3.12.1 下段）。**dodge 不写这一项**：闪避是一次速度阶跃而不是控制力，闪避期间保留进入前的倍率并仍可正常移动（`MechaStateActions.java#gaitPreservingModifier`） | 蹲伏倍率 0.3 下稳态速率 = 0.3 × 站立稳态速率 = 1.8 m/s，见 `MechaCharacterWalkPhysicsTest.java#moveSpeedModifierScalesTheEquilibriumSpeed`；dodge 不写倍率见 `GaitSubGraphsTest.java#dodgePreservesTheSpeedModifierAndKeepsMoving` |
| 闪避冲量 | `MechaControl.java#applyLogicOutputToKcc` 在进入 dodge 的那一物理步调用 → `MechaCharacter.java#requestDodgeImpulse` 累加速度增量 Δv（方向与单位向量由 `MechaControl.java#resolveDodgeDirection` 给出）→ `MechaCharacter.java#updateWalk` 在下一步把它并入速度矢量并清空待发标记 | 一次 **速度阶跃 Δv = 6 m/s**（`MechaControl.java#DODGE_IMPULSE_SPEED`）；同时开启 0.4 s 无敌窗口（`MechaCharacter.java#invulnerable`）。**位移是派生量**：地面无输入约 1.6 m、地面按住输入约 2.2 m、空中约 2.4 m | `MechaCharacterWalkPhysicsTest.java#dodgeImpulseStepChangesTheVelocityVector`（Δv 与后续的 μ·g 衰减）、`#dodgeImpulseAddsToExistingMomentum`（叠加而非替换）、`#dodgeImpulseSurvivesTheAirCeiling`（注入点在 `hSpeed` 之前）、`#dodgeGoesFartherInTheAirThanOnTheGround`（无摩擦） |
| 姿态轮廓（蹲伏 / 卧倒的胶囊尺寸） | **未落地**；原因与两条走不通的路径见 §3.12.2 | — | 无 |

**倍率必须乘在驱动力上，不能乘在净力上。** 均衡条件是「控制力 = 阻力之和」：乘在**净力**上不改变
`F_net = 0` 的位置，均衡点仍在同一个顶速，只是加速变慢（倍率 0.3 的蹲伏会得到与站立几乎相同的稳态
速率）。乘在**驱动力**上则让 `k × P/v = c₀ × m × g`，解出 `v = k × v_ref`——倍率直接缩放顶速，
`MechaCharacter.java#controlForceScale` 就是这个系数的唯一入口。

**顶速由力平衡给出，不另设速度上限。** 沿向分量上控制力与阻力相等即到顶：
`v = k × P_base / (c₀ × m × g)`，平地裸机 = `v_ref` = 6.0 m/s（`MechaWalkingAttr.java#V_REF`）。
抓地力 `μ × N` 钳制的是控制力本身（起步与转向的能力），不是顶速：匀速时控制力只需抵消约 19% 的
F_max，远在抓地力上限之下。`MechaCharacter.java#equilibriumSpeed` 解同一个方程，供诊断
（`MechaCharacter.java#debugGripTarget`）与空中天花板复用。完整模型见
`docs/角色控制器-行走物理设计.md` §3.5–§3.9。

**闪避冲量是一次速度阶跃，由通用力模型接管。** Δv 并进 `MechaCharacter.java#updateWalk` 读出的
水平速度，随同一次 `setLinearVelocity` 落地，因此不需要位移记账，也不会逐帧复利——它本来就是速度。
代价是它此后与普通动量同权：地面摩擦按 μ·g 把它磨掉、空中没有摩擦因此原样保留（空中闪避因此
比地面更远）、侧向抓地把不属于本步输入方向的部分抹掉。**单次闪避的位移因此是派生量而不是设计
常量**，调参调的是 Δv 本身。

注入点在 `hSpeed` 计算**之前**是硬约束：空中天花板取 `max(airCeiling, hSpeed)`，顺序写反会让
冲量被 `ceiling/speedNow` 静默缩掉（量级 6 → 0.47 m/s，不报任何错）。`GaitSubGraphs` 的 dodge
进入动作也刻意不写 `MOVE_SPEED_MODIFIER`——倍率缩放的是 WASD 控制力，与速度阶跃互不干涉，
闪避因此不剥夺自主移动能力。模型全文见 `docs/角色控制器-行走物理设计.md` §3.8。

#### 3.12.2 姿态轮廓（蹲伏 / 卧倒的胶囊尺寸）为什么按姿态切换不了

`PhysicsCharacter.setCollisionShape` 的库文档明确写着：

> Apply the specified CollisionShape to this character. Note that the character
> **should not be in any PhysicsSpace while changing shape**; the character gets
> rebuilt on the physics side.
>
> —— `../Libbulletjme/src/main/java/com/jme3/bullet/objects/PhysicsCharacter.java#setCollisionShape`

方法体内还有 `assert !isInWorld()`。**在世的 KCC 上换形状会让进程以 `0xC0000409`
（Windows STATUS_STACK_BUFFER_OVERRUN）中止** —— release JVM 不检查断言，原生侧挂接了一个
未重建的碰撞对象，内存随即被破坏；那是原生 abort，Java 侧 `try/catch` 捕获不到。

两条路径都不成立，结论记录在此以免重复尝试：

1. **直接换**：`setCollisionShape` 在空间内调用 → 首次换形状（蹲伏）侥幸通过，第二次（卧倒）立即中止。
2. **先移出再换**：`removeCollisionObject` → `setCollisionShape` → `addCollisionObject` → 恢复速度
   → 不中止，但幽灵体内部状态被重置（`onGround()` 失效、姿态回落到 `air`、位移读出 0），
   且仍会在后续换形状时中止。

因此 `MechaCharacter.applyPostureShape`（`MechaCharacter.java#applyPostureShape`）是 `@Deprecated` 且恒返回
`false` 的记录，**调用方不得接入**；`MechaControl.applyLogicOutputToKcc` 的第 ② 步已注释掉。
`MechaBodyPreset` 里的姿态几何（`CROUCH_HEIGHT` / `PRONE_HEIGHT` / `MIN_CAPSULE_HEIGHT` /
`capsuleHeightFor` / `halfTotalFor` / `newCapsuleShape(Posture)`）已就位，供将来走通时直接使用；
`MIN_CAPSULE_HEIGHT = 0.1f` 是防止退化形状（零长度圆柱）触发同一类原生中止的兜底。

要让蹲伏 / 卧倒真正拥有低矮轮廓，需要换一条**不触碰在世 KCC 形状**的路径，候选：

- 重建 KCC 实例（销毁旧的、按新形状构造新的，代价是速度与内部状态全部重置）；
- 姿态切换时把碰撞交给一个独立的代理体，KCC 只负责运动学。

这一项属于素体定义（阶段 4.6）的范围，且需要先定下「姿态切换时速度如何保留」的策略。

### 3.13 位姿回写的参考实现：`SubPart` → `MMPartEntity`

Machine-Max 对同类问题（SubPart 的位姿如何到达客户端）给出的方案是**两段式**的，本计划的取舍据此确定：

| 环节 | 位置 | 事实 |
|------|------|------|
| 服务端读物理体并写入实体 | `SubPart.postTick()` → `SubPart.java#postTick` | 在主线程读 `PhysicsBodyExtensionKt.stateOf(body).getCachedBoundingBox()` 与 `getTransform().getTranslation()`，直接写 `entity.boundingBox.set(box)`、`entity.bodyCenter.set(center)`，**不经 `syncedData`** |
| 客户端跟随 | `MMPartEntity.baseTick()` → `MMPartEntity.java#baseTick` | 每 tick `this.setPos(SparkMathKt.toVec3(subPart.getPosition()))`，并从四元数提取 yaw/pitch 调 `setRot`，速度乘 `0.05f` 后写 `setDeltaMovement` |
| 让原版别插手 | `MMPartEntity.java#move`、`#setPos`、`#shouldCreateDefaultPhysicsBody` | `move()` 空实现（"交由物理引擎处理"）、`setPos` 只透传、`shouldCreateDefaultPhysicsBody()` 返回 `false`（否则 Spark-Core 会给实体再挂一个运动学盒体） |
| 服务端直接写实体位置 | `SubPart.setPosition()` → `SubPart.java#setPosition` | `entity.setPos(...)` 受 `updateLock` 门控（§3.7） |

**关键差异**：`MMPartEntity` 是**真实体**，所以位姿传输完全由原版实体追踪（位移/传送包）承担，`DestroyableObject` 的 `DATA_POS_ID` 那条 `SynchedEntityData` 通道并不是它的位置传输路径。也就是说 SubPart 的方案是"物理体 → 代理实体 → 原版追踪"，而本计划是"物理体 → `synchedData` → 自建包"。

**本计划的取舍（D18）**：阶段 1–3 采用后者，即客户端只按 `DATA_POS` / `DATA_YAW` 摆放一个**非实体可视锚点**（并在相邻采样间线性插值），不为 `ArmsCore` 重建任何物理体。这样做的原因：

1. 客户端此时没有宿主实体、没有 `Part` 装配，建物理体没有消费者；
2. 避免"客户端要渲染 SubPart"（`总体设计文档.md` §2.3）与"客户端不跑物理"（D1）之间的冲突——渲染所需的一切位姿都来自同步数据；
3. 避免与宿主实体的位置形成双份权威：若将来宿主实体承担位置传输（阶段 4），`DATA_POS` 应降级为对账 / 兜底，而不是并存为第二个真值。

**阶段 4 的迁移路径**：当宿主实体（或专用代理实体，见 §7 Q5）接管位置时，`ArmsCore` 主线程 tick 的职责从"写 `syncedData` 并发包"变为"按 `updateLock` 纪律把 KCC 位姿写进宿主实体"，同时保留 `DATA_POS` 作为低频对账通道（或直接删除，取决于届时是否需要非实体宿主）。这一步应在计划中单列，不应视为自然演进。

### 3.14 坐标系与高度基准

三个"位置"必须区分，混用会产生固定偏移或抖动：

| 量 | 含义 | 来源 |
|----|------|------|
| KCC 物理位置 | **胶囊几何中心**。Bullet 幽灵体的世界变换就是它 | `MechaCharacter.getPhysicsLocation(null)` |
| 胶囊底面（脚底） | `pos.y - halfTotal`，`halfTotal = shape.getHeight()/2 + shape.getRadius()` | `common/control/attr/MechaBodyPreset.java#capsuleCenterFromFeet` 实现了该算式 |
| 实体 `setPos` | 原版语义是**包围盒底面中心（脚底）**。`net.minecraft.world.entity.Entity#setPos` 把三个分量存进 `net.minecraft.world.entity.Entity#position`，再以它为原点重建包围盒，重建由 `net.minecraft.world.entity.EntityDimensions#makeBoundingBox` 完成，形状是 `AABB(x − 宽/2, y, z − 宽/2, x + 宽/2, y + 高, z + 宽/2)` | `net.minecraft.world.entity.Entity#setPos` |

因此：

- 服务端出生点由"目标脚底位置 + `halfTotal`"算出 KCC 位置，与 `common/control/attr/MechaBodyPreset.java#capsuleCenterFromFeet` 的写法一致；
- 阶段 4 把位姿写进宿主实体时，实体位置字段的语义是包围盒底面，因此写入值是 `KCC 位置 − halfTotal`。交叉验证：`net.minecraft.world.entity.player.Player#DEFAULT_EYE_HEIGHT` 为 `1.62`，只有在"`position.y` 是脚底"的语义下才等于"脚上 1.62 m"。展开见 `docs/IArmsHost宿主接口设计.md` §2.1 与 `docs/宿主接入与伤害管线设计.md` §5.1。

---

## 4. 实施阶段

阶段 0–1 只依赖本仓库；阶段 2 起引入网络；阶段 3 引入上行输入；阶段 4 接入宿主与装配体。四段各自可独立验收。

### 阶段 0：前置修正（已完成）

**先定一组临时胶囊参数并写在共享位置。** 当前仓库唯一的胶囊尺寸来源是 `common/control/attr/MechaBodyPreset.java#CAPSULE_RADIUS`、`#CAPSULE_HEIGHT`（半径 `0.4f`、圆柱段高 `1.6f`，全高 = 圆柱段 + 2×半径 = `2.4f`）；`docs/角色控制器-行走物理设计.md` 的 §5.1「素体级参数表」不含胶囊尺寸与质量，也没有 `mech_chassis.json` 落地，因此这组值就是本阶段的**临时权威取值**，阶段 4.6 再由素体定义替换。服务端出生点与客户端锚点共用同一组值与 `halfTotal = 全高/2`（§3.14），转换算式在 `#capsuleCenterFromFeet`。

| # | 任务 | 位置 | 完成判据 |
|---|------|------|----------|
| 0.1 | `MechaCharacter.currentYaw` 加 `volatile` 并提供 getter | `MechaCharacter.java#currentYaw` | 该字段由物理线程写入、将被主线程读取（§3.5）；当前是普通字段且无 getter |
| 0.2 | 胶囊常量提到共享位置 | `common/control/attr/MechaBodyPreset.java#CAPSULE_RADIUS`、`#CAPSULE_HEIGHT` | 上表那组值集中一处，服务端出生点与客户端锚点共用同一组值与 `halfTotal` 算式（D20、§3.14） |
| 0.3 | 为 `ArmsCore` 增加最小构造参数、查询 API 与 KCC | `ArmsCore.java` 整体 | 构造注入 `Level` 与 `UUID`；`getLevel()` / `getAssemblyId()` 返回注入值；构造时以 `MechaBodyPreset` 的胶囊几何 + `SparkLevel.getPhysicsLevel(level).getWorld()` 创建 `MechaCharacter` 并交给 `MechaControl`；暴露 `getKcc()`；`getRootSubPart()` / `getAttr()` 返回 `null`（D20） |

客户端不持有 KCC，因此本阶段不包含客户端的控制器生命周期管理：KCC 的初始化、出生点与兜底逻辑都在服务端（阶段 1.5），客户端只维护按 `coreId` 索引的锚点，其生命周期跟随创建 / 移除包（阶段 1.12–1.14）。

### 阶段 1：服务端权威模拟 + 下行通道（已落地）

本阶段交付"服务端唯一 KCC → 同步 → 客户端可视锚点"的最小闭环，输入来源为服务端调试命令。**客户端不发任何输入包。**

**新增类型的包归属**（与 Machine-Max 的既有分层一致，避免把状态类型塞进网络包）：

| 类型 | 包 | 理由 |
|------|----|------|
| `LogicStateSnapshot` | `common/control/`（作为 `MechaControl` 的嵌套 record） | 纯状态副本，与 `MechaConditionSnapshot` 同类；网络只是恰好消费它的路径之一 |
| `MechaCoreRegistry` | `common/` | 装配体注册表，同时服务物理步扇出与同步写包；对应 Machine-Max 的 `common/mech/ObjectManager.java` |
| 四个载荷与包处理器 | `network/payload/` 与 `network/` | 真正的网络类型；对应 Machine-Max 的 `network/payload/*` 与 `MMPayloadRegistry.java` |

| # | 交付物 | 说明 |
|---|--------|------|
| 1.1 | `LogicStateSnapshot`（不可变 record，**`MechaControl` 的嵌套类型**） | 五项状态（`posture` / `gait` / `vertical` / `energy` / `jumpCharging`）的跨线程出口，唯一消费者是阶段 1.15 的同步写包（§3.5）。嵌套在 `MechaControl` 内，与 `MechaConditionSnapshot` 同属"某一方的按帧状态副本"这一模式，不单独占一个公共协议类型；对应 `docs/下一步开发TODO.md` §4 的条目。它**不属于网络包**，因此不进 `common/net/` |
| 1.2 | `MechaCharacter` 的只读出口 | `currentYaw` getter；`getPhysicsLocation` / `getLinearVelocity` 的使用约定（§3.5，注意 `getLinearVelocity(Vector3f)` 需传复用缓冲） |
| 1.3 | `ArmsCore` 实现 `SyncedDataHolder` | 声明 §2.2 的 8 个 `private static final EntityDataAccessor`；构造器内 `new SynchedEntityData.Builder(this)` 并 `build()`（服务端与客户端共用同一构造路径，§3.2）；提供 `getSyncedData()` 供包处理器调用；实现两个 `onSyncedDataUpdated` 重载 |
| 1.4 | `ArmsCore` 的 KCC 接线 | 阶段 0.3 已让 `ArmsCore(Level, UUID)` 构造 `MechaCharacter`（胶囊几何取自 `MechaBodyPreset`，物理空间取自 `SparkLevel.getPhysicsLevel(level).getWorld()`），无需再补构造；本项剩余的是把 `prePhysicsTick()` 接上 `mechaControl.onPhysicsStep(dt)` 与逻辑状态发布（1.7）。装配体成员（`partMap` / `getRootSubPart` / `getAttr`）按 D20 留空 |
| 1.5 | 服务端出生与兜底 | 调试命令在服务端创建 `ArmsCore`、注册，并在 `submitImmediateTask` 内 `setPhysicsLocation` + `space.addCollisionObject`（出生点算式与 `common/control/attr/MechaBodyPreset.java#capsuleCenterFromFeet` 同形）；兜底由服务端按"KCC 与宿主 / 目标点距离超过阈值"触发，阈值取值集中定义 |
| 1.6 | 唯一的物理步驱动 | `PhysicsLevelTickEvent.Pre` 订阅者按 Level 注册表扇出 `ArmsCore.prePhysicsTick()`（§3.6、D9） |
| 1.7 | 物理线程发布逻辑状态 | 在阶段 1.6 的订阅者所触发的 `ArmsCore.prePhysicsTick()` 中，按 §3.6 伪代码的第 ③ 步构造 `LogicStateSnapshot` 并写入 `volatile` 字段（§3.5）；同时给 `MechaControl` 增加 `snapshotLogicState()`（只读变量容器，返回该不可变 record）。注意 §3.6 伪代码与本节任务号是两套编号：前者是 `prePhysicsTick()` 内部的 ①②③ 执行顺序，后者是阶段 1 的交付物清单 |
| 1.8 | 客户端夹具退役 | 删除 `ARMSClient` 的 KCC / `MechaControl` 构造与物理步订阅；`ClientMechaTestRig` 整个删除。客户端不存在任何 `MechaCharacter` 实例 |
| 1.9 | 载荷注册骨架 | 新建 `RegisterPayloadHandlersEvent` 订阅者（`ARMS.java#ARMS` 构造器当前只注册了一个配置）。阶段 1 只注册 `playToClient` 三项 + `MainThreadPayloadHandler`；`playToServer` 方向留到阶段 2 一并注册（2.1） |
| 1.10 | `MechaCoreSyncPayload`（record，`network/payload/`） | `(UUID coreId, List<SynchedEntityData.DataValue<?>> dirty)`，载荷 id 见 §3.4；编解码按 §3.3 的 `255` 终结符写法 |
| 1.11 | `ArmsCoreCreatePayload` / `ArmsCoreRemovePayload` | 字段见 §3.4；创建包携带 `getNonDefaultValues()`（D6）与 `hostEntityId`（阶段 1–3 恒为 `-1`） |
| 1.12 | `MechaCoreRegistry`（`common/`） | `Map<Level, Map<UUID, ArmsCore>>`；`add` / `remove` / `get`；服务端注册时广播创建包，注销时广播移除包；维度卸载时清理。它是装配体注册表（同时被物理步扇出与同步写包读取），不是网络类型，因此与 Machine-Max 的 `ObjectManager`（`common/mech/ObjectManager.java`）同级放在 `common/` 下，不进 `network/` |
| 1.13 | 客户端处理器 | 创建包：按 `coreId` 幂等构造 `ArmsCore`（客户端构造器不建 KCC）→ 注册 → `assignValues(initial)`；移除包：注销并清空锚点；增量包：`UUID` 不存在时丢弃并计数告警，不抛异常（§3.4） |
| 1.14 | 登录 / 换维度 / 重连补发 | `PlayerEvent.PlayerLoggedInEvent` 遍历该维度注册表逐个补发创建包；客户端进入 `ClientLevel` 时清空本地注册表并按 §3.4 的时序重新获取 |
| 1.15 | 主线程同步与发包 | `LevelTickEvent.Post` 订阅者遍历注册表：读 `logicState` 与 KCC 位姿 → `set` 8 项 → `packDirty()` → 非空则广播 `MechaCoreSyncPayload`（D8、§3.9） |
| 1.16 | 客户端可视锚点 | 按 `DATA_POS` 摆放一个非实体锚点（调试用方块 / 线段 / 粒子），在相邻两采样间线性插值（D12、D18）；朝向取 `DATA_YAW`，在相邻两采样间按最短角路径插值。`DestroyableObject.clientSyncPose()` + `getWorldPositionMatrix(partialTick)`（`DestroyableObject.java#clientSyncPose`、`#getWorldPositionMatrix`）是同一形态的参考实现 |
| 1.17 | 服务端调试输入源 | 命令 / 调试键在服务端直接 `writeConditionSnapshot` + `postEvent`，用于驱动状态机与跳跃，验证下行通道（§4 阶段 2 之前的唯一输入路径） |

**验收**：

- 服务端日志的 `posture` / `gait` / `vertical` 序列与客户端从 `syncedData` 读到的值一致，仅存在恒定延迟；
- 客户端**不存在任何 `MechaCharacter` 实例**（`grep` 级检查即可）；
- 三个位姿字段（`DATA_POS` / `DATA_VEL` / `DATA_YAW`）每 tick 重新采样并写回，静止时也可能持续成包；这是采样机制的固有结果（§3.3），因此本项验收的是**带宽上界**（例如单机 < 100 B/tick），而不是"静止零包"。同时断言逻辑字段（posture/gait/vertical/energy/jump_charging）在无变化时**不产生**条目；
- 断开并重连、切换维度、死亡重生后，`MechaCoreRegistry` 与客户端锚点侧均无残留实例，且重连后 5 s 内状态与位姿收敛到服务端当前值（验证 D6 的全量初值）；
- `DATA_YAW` 的**值**验收「跟随视野偏航」（`/arms move` 给 yaw、或客户端转视角后观察朝向线段与新朝向一致）；**躯干朝向**不作验收（§3.12）。

**验收结果**：本阶段已在专用服务端上实测，数字见 §0.1 的「验证记录」。其中「三个位姿字段每 tick 重新采样」一条实测为 80 tick 内 5–8 个增量包，远低于带宽上界；「逻辑字段无变化时不产生条目」一条实测为静止段只出现 `vel`（幽灵体在浮点精度边界上的抖动），未出现 `posture` / `gait` / `vertical` / `energy` / `jump_charging`。

该次测量使用的自检夹具已从仓库移除，因此上述数字不可一键复现；重新测量需按下面的手动验证操作，或另建同类夹具。

**手动验证**：启动 `runClient` 进入世界，在聊天栏执行 `/arms spawn`（需要权限等级 2，单人游戏默认满足），屏幕上应出现一个浅蓝盒（胶囊外接盒）与一条从盒心伸出的橙色线段（朝向）。随后 `/arms move 1 0` 应看到服务端日志出现状态变化，`/arms list` 应打印位置与逻辑五项，`/arms remove` 应使盒子消失。客户端自己按住 W 时，同一个盒子应跟着移动——这条路径验证的是上行通道而不是调试命令。

### 阶段 2：客户端输入上行（已落地）

| # | 任务 | 说明 |
|---|------|------|
| 2.1 | `MechaInputPayload`（record，`network/payload/`） | 字段见 §3.11；`playToServer` 方向，`MainThreadPayloadHandler`（写入的是 `volatile` 快照，与 `MechaConditionSnapshot` 现有纪律一致） |
| 2.2 | 客户端采集与发送 | `ClientTickEvent.Pre` 采集 WASD / 跳跃 / 冲刺 / 蹲伏 / 视角，打包上行；仅值变化、`eventBits` 非空、或距上次发包 > 1 s 时发送（§3.11） |
| 2.3 | 事件序号与重发窗口 | 客户端维护 `eventSeq` 与未确认 `eventBits`，连续 N 个包（建议 3）携带同一对；服务端记录 `lastEventSeq`，仅在新序号时投递 `MechaEvent`，重复到达不重复投递（D7、§3.11） |
| 2.4 | 服务端合并与写入 | 按 §3.11 的处理链合并"上行输入 + 本地环境"后 `writeConditionSnapshot`；环境类字段此时仍由服务端临时从控制者实体查询（阶段 4 移交给 `IArmsHost`） |
| 2.5 | 控制权校验（最小版） | 阶段 2 先以"`ArmsCore` 记录的创建者 / 调试命令指定的控制者"为准；阶段 4 换为宿主实体链（`getControllingPassenger()`） |
| 2.6 | 控制权转移与断线重置 | 断开 / 换维度 / 失去控制时服务端 `writeConditionSnapshot(EMPTY)` 并清空事件（§3.11） |
| 2.7 | 服务端调试输入源收窄 | 正常输入路径接收 `MechaInputPayload`；阶段 1.17 的服务端直写只在单人调试构建中保留，用于不启动客户端时驱动状态机 |

**验收**：

- 客户端按住 W / 松开跳跃等操作的端到端表现与服务端日志一致，边沿事件在丢包注入下不丢失（可用序号人为跳号验证）；
- 客户端全程零 `MechaCharacter`、零 `MechaControl`；
- 无输入时服务端状态机不产生自发转移；断开连接后服务端不会再收到该 `coreId` 的输入，且状态回到 `EMPTY` 快照对应的静止形态；
- 同一次按键边沿（如跳跃松开）在整条链路上只被消费一次：服务端日志中 `EVENT_*` 与蓄力释放各出现一次，重发窗口内的重复包不产生第二次触发（§3.11）。

### 阶段 3：同步通道的完善（待执行）

| # | 任务 | 说明 |
|---|------|------|
| 3.1 | 静止零包的阈值门控（可选） | 若阶段 1 的带宽上界不可接受，按 §7 Q3 在主线程加位移 / 偏航阈值门控，把"静止零包"变为可达性质 |
| 3.2 | 广播范围收窄 | 实例数量上升后由维度广播切换到 `sendToPlayersTrackingEntity`（R5） |
| 3.3 | 插值质量 | 补齐 D12 的插值边界（首采样、跳跃式传送、长时间无包），参考 `DestroyableObject.lastSync`（`DestroyableObject.java#lastSync`）与 `DestroyableObject.java#getWorldPositionMatrix` 的既有处理 |

**验收**：单机带宽可观测并稳定；传送 / 重连后锚点不出现跨越地图的插值拖尾。

### 阶段 4：宿主与装配体接入（待执行）

| # | 任务 | 说明 |
|---|------|------|
| 4.1 | 引入 `IArmsHost` | 宿主上下文接口：`getLevel()`、`getHostEntity()`、伤害转发。设计文档 `docs/总体设计文档.md` §2.1 已给出原始设定；本仓库尚无任何实现 |
| 4.2 | `ArmsCore` 持有宿主引用 | 创建包开始携带真实 `hostEntityId`；客户端据此解析渲染 / 交互目标 |
| 4.3 | 拆分 `MechaConditionSnapshot` 的职责 | 现 15 个字段按来源分为三类：输入（`inputForward` / `inputStrafe` / `jumpPressed` / `sprintPressed` / `walkKeyPressed`）、视角（`viewYaw` / `viewPitch`）、环境（`sneaking` / `inWater` / `inLava` / `isDead` / `isSleeping` / `isFallFlying` / `isInWall` / `isOnFire`）。**环境类由 `ArmsCore` 每物理帧从 `IArmsHost.getHostEntity()` 查询**，宿主只负责提供输入与视角 |
| 4.4 | 宿主输入实现 | 按宿主形态各自实现输入来源：玩家宿主读取上行包，Doll 宿主读取服务端 AI 决策，SubPart 宿主读取信号总线。跨端传递由宿主负责，跨线程传递由 `ArmsCore` 现有的 `volatile` 快照 + `AtomicReference<Set<MechaEvent>>` 事件闩锁负责（`MechaControl.java#pendingEventBuffer`、`#postEvent`） |
| 4.5 | `rootSubPart` 与 `Part` 装配接入 | 补 `getRootSubPart()`；接 `ArmsCore.prePhysicsTick()` 第 ① 步（§3.6）；同时启用 `extractAnimRootDelta()` 的真实实现，`DATA_YAW` 从本阶段起才有验收意义（§3.12） |
| 4.6 | `mech_chassis.json` / `MechAttr` | 落地素体定义与 `getAttr()`，替换阶段 0 定义的临时胶囊参数（该组值同时需要补进 `docs/角色控制器-行走物理设计.md` 的 §5.1 参数表）；回归一次手感 |
| 4.7 | 位姿权威迁移（可选） | 按 §3.13 的迁移路径，把位置传输交给宿主 / 代理实体，`DATA_POS` 降级为对账通道 |

**验收**：同一份 `ArmsCore` 代码在两种以上宿主形态下工作，宿主实现中不出现对环境字段的赋值；`DATA_YAW` 随动画转身正确变化。

---

## 5. 风险与约束

| # | 风险 | 应对 |
|---|------|------|
| R1 | accessor 的静态初始化顺序错误导致 `build()` 抛 `IllegalStateException` | 全部 accessor 为 `static final` 且不加任何惰性初始化；`Builder` 置于构造器内（§3.2） |
| R2 | 字段表中间插入导致 id 平移、双端格式错配 | 字段只追加；废弃字段保留并实现为 no-op（§2.2） |
| R3 | 服务端读取物理体与物理线程写入形成反馈回路 | 引入与 `updateLock` 等价的锁，主线程写 `syncedData` 期间不推回 KCC；`onSyncedDataUpdated` 在服务端不应用位姿（§3.7） |
| R4 | 主线程从物理体读到的位姿处于物理步中途 | 与 `DestroyableRigidObject.postTick()` 同等接受该竞态；同步结果是"最近一次物理步的近似采样"，由阶段 3.3 的插值吸收 |
| R5 | 维度广播在实例数量上升后带宽增长 | 承载实体存在时使用 `sendToPlayersTrackingEntity`；阶段 3.2 |
| R6 | 物理线程每步分配 `LogicStateSnapshot` 的 GC 压力 | 按 `docs/下一步开发TODO.md` §5.2 的既有决策先使用不可变对象；若 JFR 证明是热点，再切换到"主线程置位同步请求、物理线程仅在请求时分配"的门控 |
| R7 | 服务端物理步与其他装配体共享 45 ms 预算，负载过高时 `dynamicRepeat` 会下调，仿真时间相对墙钟变慢（每 tick 实际推进秒数减少） | 单步 dt 恒为 `1f / tps`（§3.6），状态机的时长条件（`STUN_DURATION` / `DODGE_DURATION` / `HARD_LAND_DURATION` / `T_CHARGE`）按 dt 累加即可，无需为降频做补偿；受影响的是手感与仿真速率本身，属于玩法调参 |
| R8 | `DATA_YAW` 与躯干朝向被当成同一个量 | 二者解耦（§1.1、D16、D17）：渲染机体的仍是 SubPart 姿态，而"角色朝哪"是 KCC 侧的绝对 Y 朝向。躯干是受约束牵引的下游量，RAGDOLL 时二者完全独立 |
| R9 | 客户端在创建包到达前收到增量包 | 服务端同 tick 内先创建后增量；客户端对未知 `coreId` 丢弃并计数，不抛异常（§3.4） |
| R10 | 上行包丢失导致单帧边沿永久丢失（跳跃松开 `MechaEvent.JUMP_RELEASE`、`DODGE`） | 事件带单调序号 + 按差投递，同一序号幂等（D7、§3.11） |
| R11 | 玩家断线 / 换维度后服务端快照停在最后一帧，状态机卡住 | 控制权转移与断线时重置为 `EMPTY` 并清空事件（§3.11、阶段 2.6） |
| R12 | 客户端为零物理查询重建代理体，与宿主实体位置形成双份权威 | D18：客户端不建物理体；阶段 4 若需迁移按 §3.13 单列，不并存 |

---

## 6. 非目标

以下内容不在本期范围内：

- **位姿外推**。客户端在相邻采样之间线性插值（D12）。`DestroyableObject` 的 `DestroyableObject.java#lastSync` 字段记录了同步时刻，而其 `DestroyableObject.java#getWorldPositionMatrix` 只做 `lerp`；本计划不补齐外推。
- **客户端预测与回滚**。在 KCC 力学模型与权威归属稳定后另行评估。
- **显示实体本身**。宿主实体、渲染器、骑乘挂点、乘客装配属于另一条工作线；本计划只要求通道对宿主形态不敏感。该线的已知约束：Spark-Core 的 `CollisionFuncApplier` 为每个加入世界的实体自动创建一个名为 `body` 的运动学盒体，实体侧可用 `PhysicsHost.shouldCreateDefaultPhysicsBody()` 返回 `false` 关闭（`MMPartEntity.java#shouldCreateDefaultPhysicsBody` 即此用法）；由 KCC 驱动位姿的显示实体需要关闭它，否则物理空间中会多出一个不参与模拟的盒子。
- **客户端为 `ArmsCore` 重建物理体**。D18 明确排除。
- **SubPart 的物理与渲染**。SubPart 的姿态由 Machine-Max 现有的 `DestroyableObject` / `SubPartSyncPayload` 通道承载，本计划不触碰；阶段 4 的 `rootSubPart` 只接入 KCC 耦合与动画根位移，不含 SubPart 自身的同步。
- **`MechControllerSubsystem`**。它是"可驾驶机甲"产品线的宿主实现，与本计划的通道设计正交。

---

## 7. 待决问题

| # | 问题 | 影响 |
|---|------|------|
| Q1 | 是否需要一个"角分离量" | 位置有 `separationDistance` + `SEP_MAX`，并据此触发 `RAGDOLL`（`docs/角色控制器-行走物理设计.md`：§1.5、§5.1）；朝向上不存在对应的阈值或量，因此无法检测躯干在朝向上被扯离过多 |
| Q2 | `MechaCharacter.currentYaw` 的规约（已决） | `setViewYaw` 在度制上把写入值归约到 [−180, 180)（`MechaCharacter.java#normalizeViewYaw`），因此作为朝向使用的值有界；`Rotations` 的 `% 360` 只覆盖线上格式 |
| Q3 | `DATA_POS` / `DATA_VEL` / `DATA_YAW` 是否加"值变化阈值门控"以实现静止零包 | 决定阶段 1 的验收是"带宽上界"还是"静止零包"（§3.3、阶段 3.1）。加门控会引入少量位姿量化误差 |
| Q4 | 上行包在控制权转移的瞬间是否需要显式的 `stopInput` 包 | 当前设计由服务端在断线 / 失控时主动重置（R11），但"仍在连接、只是不再控制"的路径依赖服务端能及时察觉控制权变更 |
| Q5 | 宿主实体（阶段 4）用玩家实体本身、Doll 实体，还是为装配体单开一个代理实体承担位置传输 | 决定 §3.13 的迁移路径与 `DATA_POS` 的最终定位；也决定 `PhysicsHost.shouldCreateDefaultPhysicsBody()` 的返回方 |
| Q6 | `ArmsCore` 的持久化边界 | `docs/总体设计文档.md` §2.1 说"由宿主 NBT + Capability 序列化"，但阶段 1–3 的 `ArmsCore` 只有 KCC 与状态机，没有可持久化内容。是否需要在本计划内定义最小存档形态（至少保证重启后 `coreId` 与位置可恢复） |

---

## 8. 证据索引

### 本仓库

本节记录各结论对应的坐标，一律是「路径#符号」——在目标文件里检索该符号即可定位。行号只在形态快照里出现，且必定带 `@ 提交`（见 §1.2）；活引用不写行号。

| 结论 | 位置 |
|------|------|
| `ArmsCore` 身份与同步容器（`SyncedDataHolder`） | `common/ArmsCore.java#ArmsCore`（类声明）、`#DATA_POS`、`#DATA_JUMP_CHARGING`、`#newClientInstance`、`#prePhysicsTick`、`#enterPhysicsSpace` |
| 逻辑状态跨线程出口 | `common/control/MechaControl.java#LogicStateSnapshot`、`#snapshotLogicState` |
| 逻辑层产出到物理的落地（速度倍率 / 闪避冲量） | `common/control/MechaControl.java#applyLogicOutputToKcc`、`#getMoveSpeedModifier`、`#resolveDodgeDirection`；`common/control/MechaCharacter.java#setMoveSpeedModifier`、`#controlForceScale`、`#requestDodgeImpulse`、`#consumeDodgeImpulse`、`#updateWalk`（沿向/侧向分解与三个机制）、`#overlayDispX`、`#getHorizontalVelocity`、`#equilibriumSpeed`；`common/control/state/graph/MechaStateActions.java#gaitPreservingModifier` |
| 姿态几何（已就位但未接入）与退化形状兜底 | `common/control/attr/MechaBodyPreset.java#CROUCH_HEIGHT`、`#PRONE_HEIGHT`、`#MIN_CAPSULE_HEIGHT`、`#capsuleHeightFor`、`#halfTotalFor`、`#newCapsuleShape` |
| 「在世 KCC 不可换形状」的库约束 | `../Libbulletjme/src/main/java/com/jme3/bullet/objects/PhysicsCharacter.java#setCollisionShape` |
| 调试命令入口 | `common/command/ArmsCoreDebugCommand.java` |
| 装配体注册表 | `common/MechaCoreRegistry.java#addServer`、`#onLevelUnload`、`#sendAllTo` |
| 上行输入服务端处理链 | `common/MechaInputHandler.java#isController`、`#resetInput`、`#apply` |
| 物理步扇出与主线程同步写包 | `common/ArmsCoreServerEvents.java#onPrePhysicsTick`、`#syncToClients`、`#onPlayerJoinLevel` |
| 载荷注册与协议版本 | `network/ARMSNetwork.java#PROTOCOL_VERSION`、`#register`（三处 `playToClient`、一处 `playToServer`） |
| 客户端输入采集与重发窗口 | `client/ARMSClient.java#RESEND_WINDOW`、`#onClientTick`、`#queueEvent` |
| 客户端可视锚点与其渲染 | `client/ClientMechaAnchor.java#accept`、`#lerpPosition`；`client/MechaAnimatable.java#clientTick`、`#getModelSpaceMatrix`、`#getWorldPositionMatrix`；`client/MechaPlayerRenderer.java#onClientTick`（驱动动画体）、`#onRenderPlayerPre`（取消玩家模型并就地绘制机体） |
| 调试命令 | `common/command/ArmsCoreDebugCommand.java` |
| `onPhysicsStep` 顺序 | `common/control/MechaControl.java#onPhysicsStep` |
| 事件帧首取走（单帧边沿语义） | `common/control/MechaControl.java#frameLogic`（帧首 `getAndSet(空集)` 取走整批） |
| 状态机输入来源 | `common/control/MechaControl.java#writeStateInputs` |
| `ENERGY` 定义与初值（快照字段来源） | `common/control/MechaControl.java#INITIAL_ENERGY`、`#MechaControl`（构造器初值）；`#logStateChanges`（posture/gait/vertical 的读取形态） |
| 事件闩锁 | `common/control/MechaControl.java#pendingEventBuffer`、`#postEvent` |
| `getRootSubPart()` / `getAttr()` 无调用方 | 全仓仅出现在 `MechaControlHolder.java#getRootSubPart`、`#getAttr`、`ArmsCore.java`（返回 `null` 的存根） |
| `currentYaw` 的绝对朝向语义与唯一写入方 | `common/control/MechaCharacter.java#currentYaw`、`#setViewYaw`、`#normalizeViewYaw`、`#animRootYawDelta`（阶段 4 接入点，当前不参与合成）；`common/control/MechaControl.java#applyFacing` |
| 行走方向「意图 → 世界」的唯一变换点与闪避方向同源 | `common/control/MechaCharacter.java#setMoveIntent`、`#updateWalk`；`common/control/MechaControl.java#applyMoveIntent`、`#resolveDodgeDirection` |
| 分离距离是一等量 | `common/control/MechaCharacter.java#separationDistance`、`#updateWalk`（`sepFactor` 折减） |
| 快照模式与跨线程决策 | `common/control/MechaConditionSnapshot.java#MechaConditionSnapshot`；`docs/下一步开发TODO.md` §4、§5.2 |
| 单帧边沿不得丢失（TODO 条目） | `docs/下一步开发TODO.md` §3.3 |
| `vertical` 子机条件 | `common/control/state/preset/VerticalSubGraphs.java#buildStandVert` |
| 位置与朝向的解耦、各状态的约束设置 | `docs/角色控制器-行走物理设计.md`：§1.2、§2.1、§2.3、§2.4 |
| KCC 线速度的单位语义（含逐行源码证据与调用方转换规则） | `docs/角色控制器-行走物理设计.md`：§10.3、§11 |
| 控制器跨线程字段清单 | `docs/角色控制器-行走物理设计.md`：§10.1 |
| 胶囊尺寸的唯一来源 | `common/control/attr/MechaBodyPreset.java#CAPSULE_RADIUS`、`#CAPSULE_HEIGHT` |
| `ArmsCore` 不注册为世界实体、由宿主序列化 | `docs/总体设计文档.md` §2.1 |
| 宿主输入路径（玩家按键 → 网络包 → `IArmsHost`） | `docs/总体设计文档.md` §2.4.5、§3.2 |
| 客户端渲染遍历 `SubPart.body` | `docs/总体设计文档.md` §2.3 |
| 载荷注册的现状 | `ARMS.java#ARMS`（构造器，只注册配置）；四个载荷在 `network/ARMSNetwork.java#register` 注册 |

### `../Spark-Core`

| 结论 | 位置 |
|------|------|
| 双端物理世界与速率 | `physics/level/PhysicsLevelApplier.kt#load`（服务端构造调用）、`ClientPhysicsLevelApplier.kt#load`（客户端构造调用）、`PhysicsLevel.kt#tps` |
| 步数负载自适应 | `physics/level/PhysicsLevel.kt#stepPhysics` |
| 服务端物理步请求点（`LevelTickEvent.Pre`，`HIGH`） | `physics/level/PhysicsLevelApplier.kt#mcLevelTask` |
| 物理世界初始化时机与完成事件（构造前置依据） | `physics/level/PhysicsLevelApplier.kt#load`（`start {}` 与完成事件）、`PhysicsLevel.kt#world`、`#start`；`api/SparkLevel.java#getPhysicsLevel` |
| 物理线程发布点 | `physics/level/PhysicsLevel.kt#prePhysicsTick`、`#physicsTick` |
| 两端实体集合差异 | `physics/level/ServerPhysicsLevel.kt#requestEntities`、`ClientPhysicsLevel.kt#requestEntities` |
| 每个实体自动获得运动学盒体 | `mixin/extension/EntityMixin.java`、`EntityPatch.java`（接口声明）、`physics/body/CollisionFuncApplier.kt#addBodyForEntity`、`physics/PhysicsHost.kt#shouldCreateDefaultPhysicsBody` |

### `../Machine-Max`

| 结论 | 位置 |
|------|------|
| `SynchedEntityData` 用于非实体类 | `common/mech/DestroyableObject.java#DestroyableObject`（类声明）、`#DATA_POS_ID`（accessor 表）、`#syncedData`、`#DestroyableObject`（构造器） |
| 增量发包 | `common/mech/DestroyableObject.java#syncToClient` |
| 客户端应用钩子 | `common/mech/DestroyableObject.java#onSyncedDataUpdated` |
| 插值与其边界 | `common/mech/DestroyableObject.java#clientSyncPose`、`#getWorldPositionMatrix` |
| 反馈回路锁 | `common/mech/DestroyableRigidObject.java#updateLock`、`#DestroyableRigidObject`（构造器）、`#postTick`、`#setPosition`、`#setRotation`、`#setLinearVelocity`、`#setAngularVelocity` |
| 客户端运动学代理体 | `common/mech/DestroyableRigidObject.java#DestroyableRigidObject`（构造器里 `body.setKinematic(true)`）、`#prePhysicsTick` |
| 增量包编解码 | `network/payload/SubPartSyncPayload.java#STREAM_CODEC`、`#handler` |
| 创建 / 移除 / 登录补发 / 换维度请求 | `common/mech/ObjectManager.java#addVehicle`、`#removeVehicle`、`#transmitVehicleData`、`#loadVehicleData`；`network/payload/assembly/VehicleCreatePayload.java#handle` |
| 上行输入的发送条件 | `client/input/RawInputHandler.java#handleMoveInputs`；`network/payload/MovementInputPayload.java#STREAM_CODEC`、`#serverHandler` |
| 两套注册表 | `common/mech/ObjectManager.java#levelVehicles`、`#levelDestroyableObjects` |
| tick 与物理步扇出 | `common/mech/ObjectManager.java#onPreTick`、`#onPostTick`、`#onPrePhysicsTick`、`#onPostPhysicsTick` |
| 整数 id 的创建包交接 | `common/mech/vehicle/Part.java#Part`（构造器里按客户端接收 id） |
| 装配体自驱动 `Part` 动画 | `common/mech/vehicle/VehicleCore.java#prePhysicsTick` |
| 位姿回写的两段式实现（物理体 → 实体 → 原版追踪） | `common/mech/vehicle/SubPart.java#preTick`、`#postTick`、`#setPosition`；`common/entity/MMPartEntity.java#MMPartEntity`（构造器）、`#baseTick`、`#shouldCreateDefaultPhysicsBody`、`#move`、`#setPos` |

### Minecraft 1.21.1

源码位置：`build/moddev/artifacts/neoforge-21.1.219-sources.jar`（人类可读）或解压 `neoforge-21.1.219-merged.jar` 内的 `*.class`。

| 结论 | 符号 |
|------|------|
| 持有者不要求是实体 | `net.minecraft.network.syncher.SyncedDataHolder` |
| 构造器接受接口 | `SynchedEntityData.Builder(SyncedDataHolder)` |
| accessor 分配 | `SynchedEntityData.defineId(Class<? extends SyncedDataHolder>, EntityDataSerializer)` |
| id 只追加、沿父类链取起点 | `net.minecraft.util.ClassTreeIdRegistry.define` / `getLastIdFor` |
| 变更门控按值判等（`Objects.equals`） | `SynchedEntityData.set` → `ObjectUtils.notEqual` |
| 变更门控 | `SynchedEntityData.packDirty()` |
| 全量非默认值（创建包用） | `SynchedEntityData.getNonDefaultValues()` |
| 客户端应用 | `SynchedEntityData.assignValues(List<DataValue<?>>)` |
| `setValue` 不做防御性拷贝 | `SynchedEntityData.DataItem.setValue` |
| 自动环绕的度制旋转、逐分量 `equals` | `EntityDataSerializers.ROTATIONS`、`net.minecraft.core.Rotations#Rotations`（`% 360` 环绕）、`net.minecraft.core.Rotations#equals` |
| 骑乘输入上行（阶段 4 的玩家宿主） | `ServerboundPlayerInputPacket`、`ServerboundMoveVehiclePacket`、`PlayerRideableJumping.handleStartJump` |
| 骑乘与乘客 API | `Entity.getControllingPassenger()`、`Entity.isControlledByLocalInstance()`、`LivingEntity.travelRidden` |

### `../Libbulletjme`

源码位置：`src/main/java/`（Java 绑定）与 `src/main/native/`（Bullet 原生实现与 JNI 胶水）。

| 结论 | 位置 |
|------|------|
| KCC 水平/垂直分量单位不同（javadoc 明示） | `src/main/java/com/jme3/bullet/objects/PhysicsCharacter.java#getLinearVelocity` |
| `getWalkDirection()` 的描述是 "walk offset" | `src/main/java/com/jme3/bullet/objects/PhysicsCharacter.java#getWalkDirection` |
| `warp` 接口 | `src/main/java/com/jme3/bullet/objects/PhysicsCharacter.java#warp` |
| `getPhysicsLocation(storeResult)`（传 `null` 返回新向量） | `src/main/java/com/jme3/bullet/collision/PhysicsCollisionObject.java#getPhysicsLocation` |
| 返回值 = `m_walkDirection` + `m_verticalVelocity * m_up` | `src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#getLinearVelocity` |
| `setLinearVelocity` 把沿 `up` 的分量拆进 `m_verticalVelocity` | `src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#setLinearVelocity` |
| 垂直分量按速度积分（`-= m_gravity * dt`），再转位移（`m_verticalOffset = m_verticalVelocity * dt`） | `src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#playerStep`（两处） |
| `m_fallSpeed` 单位为 m/s（注释 "Terminal velocity of a sky diver in m/s"） | `src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#btKinematicCharacterController`（构造器里的 `m_fallSpeed` 注释） |
| `m_walkDirection` 作为位移传给 `stepForwardAndStrafe` | `src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#playerStep` |
| 幽灵体世界变换在 `playerStep` 末尾写入一次 | `src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#playerStep`（末尾） |

---

## 附录A：被本计划取代的做法

本附录只记录**已不再采用**的设计，供了解演进的人查阅。正文全部按当前设计书写，不依赖本附录。

### A.1 客户端夹具承载 KCC

`ARMSClient` + `ClientMechaTestRig` 的形态：客户端自己构造 `MechaCharacter` 与 `MechaControl`，在 `PhysicsLevelTickEvent.Pre` 中调用 `onPhysicsStep`，并以一个按 JVM 单例持有、按 `% 600` 周期 `warp` 到玩家位置的夹具作为唯一闭环。

取代原因：客户端与服务端物理速率不同（60 Hz vs 100 Hz）、步数自适应、Bullet 浮点非确定性，两份 KCC 无法保持一致，而逻辑状态机会放大差异（§1.3）。现行设计是服务端唯一权威（D1），客户端夹具退役（阶段 1.8）。

### A.2 阶段 1 只建通道、不接模拟

早期阶段划分把"建立并验证同步通道"与"服务端权威接线"分成两阶段，阶段 1 计划在服务端创建一个只写合成快照、不构造 KCC 的 `ArmsCore`。

取代原因：`MechaControl.onPhysicsStep()` 进入 `writeStateInputs` 后立即调用 `kcc.onGround()`（`MechaControl.java#writeStateInputs`），`kcc == null` 时抛 `NullPointerException`（§1.2）。现行设计把真实 KCC 的构造放在阶段 1.4，阶段 1 交付完整闭环。

### A.3 "静止时不产生任何包"作为验收判据

取代原因：`DATA_POS` / `DATA_VEL` / `DATA_YAW` 每 tick 重新采样，`set` 的判等为值比较，位姿只要抖动就会成包（§3.3）。现行判据是带宽上界加"逻辑字段无变化时不出现在批里"（阶段 1 验收）。

### A.4 胶囊尺寸与根 SubPart 作为阶段 2 前置

早期计划把"补齐 `getRootSubPart()`""构造真实 `MechaCharacter`"列为阶段 2 的任务，隐含要求装配体装配先就位。

取代原因：`MechaControl` 全文不调用 `getRootSubPart()` 与 `getAttr()`，`extractAnimRootDelta()` 是空实现（§1.1），因此这两项不构成运行前置。现行设计允许 `rootSubPart == null`、胶囊尺寸硬编码（D20），代价登记在 §3.12，装配体接入推迟到阶段 4。

### A.5 客户端为零物理查询重建代理体

取代原因：客户端在阶段 1–3 没有宿主实体与 `Part` 装配，代理体没有消费者；且与宿主实体的位置会形成双份权威。现行设计是客户端只摆放非实体可视锚点（D18、§3.13），位姿权威是否迁移到实体留作 §7 Q5。

### A.6 上行包里独立的 `jumpReleased` 字段

`MechaInputPayload` 曾带第 9 个字段 `boolean jumpReleased`（`src/main/java/io/github/sweetzonzi/arms_core/network/payload/MechaInputPayload.java:51 @ 8ea7199`），服务端 `MechaInputHandler` 合并上行时把它连同 `keyFlags` 一起填进 `MechaConditionSnapshot`，`MechaControl` 再从快照读该字段转发给 KCC。

取代原因有两条：

- **边沿不能搭覆盖式快照。** 快照的语义是"此刻的状态、每帧覆盖"，物理步按 tick 的步数（100 Hz）重复读取同一个引用，于是同一次松键会被重新 latch 多次。跳跃释放必须恰好被消费一次，这与 `DODGE` / `TOGGLE_*` 走事件闩锁的理由相同（D7、§3.11）。
- **该字段在采集侧曾被算出来却没有填入载荷**：客户端在 `ARMSClient.collectAndSend` 里比较上帧与本帧的跳跃键得到松开边沿，但进包的是另一个只为事件准备的状态位（`src/main/java/io/github/sweetzonzi/arms_core/client/ARMSClient.java:156 @ 8ea7199`）。服务端因此恒收到 `false`，蓄力能起来、松键只会被 `MechaCharacter` 当作中断，跳跃永不施放。

现行设计是 `MechaEvent.JUMP_RELEASE`：按住是 `keyFlags` 的连续量，松开是与 `DODGE` 同批投递的事件位，载荷因此回到 8 个字段（§3.11）。
