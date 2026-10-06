# 角色控制器：KCC 制约调研与替换评估

**本文定位**：记录角色控制器所依赖引擎组件的**制约调研结果**，并据此引出「自实现刚体角色控制器」这一方向。实现途径、技术选型、原型验证与迁移分阶段**不在本文范围**，由后续实施文档承担。

**自包含**：本文不要求读者先读其它文档。结论写在文内；凡引用源码给「`路径#符号`」形式的活引用（可在目标文件里检索到该符号），凡引用本仓库设计文档给「文档名 §节号」。

---

## 1. 调研对象与两个前提事实

角色控制器的运动学部分由 `common/control/MechaCharacter.java` 实现，它继承 `com.jme3.bullet.objects.PhysicsCharacter`，后者封装 Bullet 的 `btKinematicCharacterController`（下称 **KCC**）。

两个决定「实际编译与运行的是哪份代码」的前提事实，先陈述：

1. **ARMS-Core 实际编译的 `com.jme3.bullet.*` 来自 Spark-Core 的源码副本。** `repositories.gradle` 只声明依赖 `io.github.solarmoonqaq:spark-core-…`；Spark-Core 在 `../Spark-Core/src/main/java/com/jme3/bullet/**` 维护了整套 jme3-bullet 源码，其 `build.gradle.kts` 对重复类做 `DuplicatesStrategy.EXCLUDE`。上游 `../Libbulletjme` 提供同源的 Java 与 C++ 源码，本文引用其 C++ 作为引擎行为的可读证据。
2. **Spark-Core 的生产路径不使用 KCC。** 它给自己的 `PhysicsHost` 实体建的是运动学刚体（`../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/body/CollisionFuncApplier.kt#addBodyForEntity`）：运动学模式、无接触响应、碰撞组 `PAWN`、碰撞掩码为空。

## 2. KCC 的本质

KCC 不是刚体，而是「**幽灵体 + 手写三段扫掠 + 纯速度推导着地**」：

- 位移推进分三段：`stepUp`（抬升越障）、`stepForwardAndStrafe`（水平扫掠与沿法线滑墙）、`stepDown`（下落贴地），同属 `../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#playerStep`。
- 类注释说明它是「ghost object + convex sweep test」，并明确「与动态刚体的交互需要使用者自行实现」（`../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.h#btKinematicCharacterController`）。
- 它没有质量，既不产生也不承受冲量。

后文所有制约都是这一本质的派生。

## 3. 制约清单

### 3.1 单位与语义（派生根源）

| # | 现象 | 根因 | 后果 | 现有处置 |
| --- | --- | --- | --- | --- |
| A1 | `getLinearVelocity()` 的水平分量是**位移**（m/物理步）、垂直分量是**速度**（m/s），同一向量两种量纲 | `m_walkDirection` 在 `stepForwardAndStrafe` 中直接当作位置增量使用；`m_verticalVelocity` 在 `playerStep` 中乘 `dt` 才成为偏移（`../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#getLinearVelocity` 把两者相加） | 每次读写都要分别换算，漏一处即以双端 `dt` 之比静默失真（服务端 100 Hz 与客户端约 60 Hz 相差约 1.67 倍） | 语义固化为 `docs/角色控制器-行走物理设计.md` §11；`common/control/MechaCharacter.java#getHSpeed` 强制调用方传入 `dt` |
| A2 | `setLinearVelocity` 把传入向量中沿「上」方向的分量拆出来写进垂直通道，其余作为水平位移 | 该方法内的启发式分支（注释自述为 “HACK: if we are moving in the direction of the up, treat it as a jump”），同属 `../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#setLinearVelocity`；它同时置 `m_wasJumping` | 水平与垂直无法分离设置，只能靠构造一个带 Y 的向量整体写入 | `common/control/MechaCharacter.java#warp` 连续写两次 `setLinearVelocity` 才能清掉垂直分量 |
| A3 | 两端物理步长不同 | 步频由物理空间决定（`../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/level/PhysicsLevel.kt#requestStep`），默认 100 Hz | 「每步位移 ↔ m/s」的换算因子不能写死 | 由 `ArmsCore#physicsStepSeconds` 逐层透传 |

### 3.2 不可观测与无写回

| # | 现象 | 根因 | 后果 |
| --- | --- | --- | --- |
| B1 | **撞墙时水平速度不归零** | 原生侧从不把「实际走了多远」写回 `m_walkDirection`：`stepForwardAndStrafe` 只改 `m_currentPosition` / `m_targetPosition`，而 `getLinearVelocity` 是纯字段读出 | 顶墙期间读到的速度是一份没有执行的命令：位置不动、速度按地面摩擦缓慢衰减；障碍一消失就在一个物理步内把位置推满，表现为「从 0 直接加到满速」 |
| B2 | 由 B1 派生：撞墙与越障难以区分 | `stepUp` 先抬高胶囊再做水平扫掠，**越障成功时水平位移是全量** | 「位移短少」才是有区分度的信号，判据实现记录在 `common/control/MechaCharacter.java#updateWalk` 的 TODO 中，尚未落地 |
| B3 | 动画根位移与物理位移共用同一水平通道 | 水平通道的语义就是「本步位移」，动画位移也是位移，两者共用同一字段 | 必须自记账 `overlayDispX` / `overlayDispZ`（KCC 承载时期 `common/control/MechaCharacter.java` 的私有字段），读速度时扣掉，否则动画位移会被当速度逐帧复利 |

### 3.3 垂直通道的隐藏规则

| # | 现象 | 根因 |
| --- | --- | --- |
| C1 | **向上速度被静默钳制** | `playerStep` 扣掉重力后把 `m_verticalVelocity > m_jumpSpeed` 的部分截回 `m_jumpSpeed`（`../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#playerStep`）。`m_jumpSpeed` 是有状态字段，因此任何非 `jump()` 的向上来源都会被钳；助推窗口的触发条件是 `助推力 / 质量 > g`，即质量小于约 51 kg |
| C2 | 下落速度被终端速度截断 | 同一处对 `m_verticalVelocity < -m_fallSpeed` 的截断 |
| C3 | **`onGround()` 不是接触清单查询** | 判据是「垂直速度与垂直偏移都小于单精度阈值」，而这两个量由**上一步的向下形状扫掠**清零（`../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#stepDown` 命中时 `m_verticalVelocity = 0; m_verticalOffset = 0`，且该扫掠在垂直速度为正时直接返回；`#onGround` 只读这两个量）。Java 侧再包一层「当前未在跳跃中」的与运算（`../Libbulletjme/src/main/native/glue/com_jme3_bullet_objects_infos_CharacterController.cpp#isOnGround`） |
| C4 | 跳跃家族半废弃 | 最大跳跃高度只有 setter、其唯一消费点是被注释掉的代码块（`../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#setMaxJumpHeight`）；`canJump()` 在 C++ 只查着地、在 Java 侧才叠加「未在跳跃中」，两层语义不一致 |
| C5 | 线阻尼同时作用于水平与垂直 | 同一处对 `m_walkDirection` 与 `m_verticalVelocity` 各乘一次阻尼因子。本项目把阻尼设为 0（`common/control/attr/MechaWalkingAttr.java#C1`），改由手动力模型承担 |

### 3.4 能力缺失与硬阻塞

| # | 能力 | 状态 |
| --- | --- | --- |
| D1 | **按姿态更换碰撞形状（蹲伏 / 卧倒胶囊）** | **硬阻塞**。`setCollisionShape` 带「不得在物理空间内」的断言（`../Spark-Core/src/main/java/com/jme3/bullet/objects/PhysicsCharacter.java#setCollisionShape`）；发行版 JVM 不检查断言，违反后原生内存被破坏，进程以 `0xC0000409` 中止。先移出物理空间、换形、再移入也不成立：幽灵体内部状态被重置。细节见 `docs/ArmsCore双端权威与网络同步实现计划.md` §3.12.2 |
| D2 | **地面法线** | KCC 不输出，只能外挂射线自取（`common/control/MechaCharacter.java#updateGround`） |
| D3 | **质量与外力** | 幽灵体无质量，外部冲量（击退、爆炸）不产生效果；子系统合力叠加入口 `common/control/MechaControl.java#addSubsystemForce` 因此没有可施加的对象 |
| D4 | **与动态刚体的交互** | 需使用者自行实现（见 §2 的类注释） |
| D5 | **越障实际高度略小于设定值** | 水平扫掠前把形状 margin 临时加大 0.02 m，等于把胶囊底面再压低 2 cm（`common/control/attr/MechaWalkingAttr.java#STEP_HEIGHT_BASE`） |

### 3.5 生命周期与集成

- **E1 传送入口承担复合语义**：`common/control/MechaCharacter.java#warp` 同时负责「改幽灵体落点、清垂直速度、复位本步施力状态」；且基类构造器末尾自身会调用一次 `warp`，此时子类字段尚未初始化。
- **E2 乘客态需暂停自身积分**：角色成为乘客时，`updateWalk` / `updateJump` 与重力都必须停用，否则控制器会持续把角色吸附到座位（规则见 `docs/宿主位置权威与位移摄入设计.md` §七）。
- **E3 步频可变**：负载高时物理空间会下调每 tick 的步数，所有按时长或按步计的量必须按 `dt` 累加（`docs/ArmsCore双端权威与网络同步实现计划.md` §R7）。
- **E4 双端权威分工不变**：KCC 只存在于服务端，客户端零仿真、只消费同步结果。任何替换方案都必须维持这条约束。

## 4. 两个改变判断的发现

1. **引擎是可控的，但控制点是「Spark-Core 的副本」。** 该副本保留了 `setCollisionShape` 的断言，也没有调整垂直钳制，只是把单位语义写进了 Javadoc（`../Spark-Core/src/main/java/com/jme3/bullet/objects/infos/CharacterController.java#getLinearVelocity`）。也就是说「直接修改引擎」在工程上可行，代价是后续每个调用方仍需自行承担这些语义。
2. **运动学刚体的基础设施已经存在。** Spark-Core 已用它承载自己的 `PhysicsHost` 实体，并且**在运行时替换碰撞形状**（经物理线程任务提交）。这与 D1 的硬阻塞形成对照：换形状在刚体路线上本就是可行操作。含义是：替换不必从零设计，可以接上既有设施。

## 5. 既有可参考实现：Minie 的 BetterCharacterControl

评估刚体路线时，一份现成实现能直接回答「这条路是否走得通」。该类位于 Minie 库：`MinieLibrary/src/main/java/com/jme3/bullet/control/BetterCharacterControl.java`，仓库 https://github.com/stephengold/Minie 。类头版权归 jMonkeyEngine（2009-2018），作者署名 `normenhansen`，BSD 3-Clause 许可；Minie 由 libbulletjme 的作者 stephengold 维护，与 §2 引用的引擎同源。**它以 URL 定位，属外部先例，不在本仓库「`路径#符号`」活引用之列。**

### 5.1 它的运作范式

- **真刚体**：内部持有 `PhysicsRigidBody`，碰撞形状是「偏移胶囊」的组合形状（`CompoundCollisionShape` 内含偏移的 `CapsuleCollisionShape`），有质量，受重力与求解器约束。构造时 `setAngularFactor(0f)`——**旋转全锁**，姿态只在视图 / 重力变化时由 `updateLocalCoordinateSystem` 写入。
- **速度权威**：`physicsTick`（步进后）把刚体线速度读进自己的字段，`dynamicPreTick`（下一步的步进前）把它按水平方向衰减（`dampingFactor` 默认 0.9，即每步把水平分量乘 0.1）、补足到请求速度、非主动跳跃时夹 `maxUpwardVelocity`，然后**整体写回** `rigidBody.setLinearVelocity`。它不施加力，也不保留水平惯性，坡度与材质差异因此无从出现。
- **着地判定**：`checkOnGround` 用 `CollisionSpace#sweepTest` 扫一个半径等于胶囊半径的球，起点是胶囊中心、终点是下半球心（减 margin）；结果里**只排除自己**，有任何命中即着地——**不取命中法线**，因此贴着竖直面时与站在地上不可区分。
- **跳跃**：`applyCentralImpulse` 施加一次冲量，矢量是 `质量 × 5 m/s`（固定起跳速度，与质量无关）。
- **在世换形**：`setHeightPercent` 改变碰撞形状（`setCollisionShape`），用于蹲伏 / 卧倒这类轮廓切换；**变高之前先 `checkCanUnDuck` 向上扫球确认头顶空间**。
- **显式上限**：`maxUpwardVelocity` 是可配置字段，语义即「非跳跃时的最大上升速度」；它的 Javadoc 自述「设为零可避免过台阶时的弹跳」。
- **没有越障**：没有 `stepUp` 段落，也没有其它把角色抬上台阶的机制。台阶靠自然接触上不去——胶囊底部是球面，台阶棱角一旦高于球心，接触法线的竖直分量就朝下（半径 0.4 m 的胶囊对 0.5 m 台阶即如此）。
- **运动学模式**：`setKinematic` 直接把刚体切成运动学，供需要外部驱动位置的场合使用（乘客态的现成范式）。

### 5.2 它能证明什么

把 §3 的制约逐条对照，这份实现解掉了其中多数：速度是单一量纲（m/s）且水平与垂直同源（A1、A2）；对外报告的速度即求解器给出的真实速度，被墙挡住会被求解器归零（B1、B2）；**着地由一条向下扫掠判定**（C3）；除设计参数外没有上升速度上限（C1、C2）；碰撞形状可在运行时更换（D1）；外力与冲量可直接施加（D3）；乘客态由 `setKinematic` 切换（E2）。

**两条不成立**：D2「地面法线可直接取得」——扫掠结果里没有法线过滤，也没有坡度处理（局部坐标系的上方向取自**重力**，不是地面法线）；D5 越障——它没有 `stepUp`，而 `maxUpwardVelocity` 的 Javadoc 说明「过台阶会弹」是这条路线上的已知现象。

**它是速度权威，不是力权威。** 每步把线速度整体写回，等于放弃惯性，也放弃「地面能传多少你才能拿多少」这条因果（引擎摩擦只被当作需要覆盖掉的旧值），因此冰面、坡度、材质差异与「跳跃继承水平速度」这些语义都做不出来。本项目因此只取它的三样形态——球扫掠着地、变高前先扫掠、`setKinematic`——驱动力仍走力与摩擦锥（`docs/角色控制器-刚体动力学方案.md` §7.4）。

也就是说，§7 验收基线里由 A / B / C / D / E 派生的条目在这份实现上**多数**有先例，但「越障」与「地面法线 / 坡度」两条没有。这仍足以证实「以刚体承载角色控制器」不是理论构想，而补齐这两条正是本项目的增量。

也就是说，§7 验收基线里由 A / B / C / D / E 派生的条目，在这份实现上均有先例。这证实「以刚体承载角色控制器」不是理论构想。

### 5.3 移植障碍

它不能作为依赖直接引入，障碍有三类：

- **基类缺失**：它 `extends AbstractPhysicsControl`，而本仓库实际编译的 `com.jme3.bullet` 是 Spark-Core 的源码副本（§1 前提事实一），其中没有 `com/jme3/bullet/control` 包。
- **外部工具依赖**：依赖 `jme3utilities`（`Validate`、`MyQuaternion`、`MyVector3f`）。
- **场景图耦合**：导入 `Spatial`、`com.jme3.export.*`（`JmeExporter`、`InputCapsule` 等）、`TempVars`、`Cloner`，面向 jME 的场景图与序列化，与本项目的实体模型无关。

它所需的底层 API 在副本中齐备：`CollisionSpace#sweepTest`、`PhysicsSweepTestResult`、`PhysicsTickListener`，以及**不带「不得在物理空间内」断言的** `PhysicsRigidBody#setCollisionShape`。

### 5.4 定位

因此这份实现的价值是**范式参考与移植来源**（许可允许），不是可直接依赖的组件。它证明制约清单可在刚体路线上解掉；「本项目如何实现」——自写扫掠 / 滑墙 / 越障 / 贴地，以及与既有功率-力-速曲线、跳跃助推窗口的相容——仍归后续实施文档（见 §8）。

## 6. 结论与方向

上述制约可按来源分为三层：

- **语义层（A）**：量纲混装、隐藏的状态耦合。它们不是缺陷而是设计惯性，但要求每个调用方逐处小心，成本随调用点数量线性增长。
- **观测层（B）**：引擎不回报真实结果，导致「命令」与「事实」分家。这一层无法通过调用方小心来消除，只能外挂补偿（记账、位置差分）。
- **能力层（C / D / E）**：垂直通道的隐藏上限、访问不到的接触信息、在世不可换形、无质量不受力。这一层是**阻断性的**——姿态轮廓、外部冲量、子系统合力叠加这些已规划的能力在 KCC 上无法实现。

因此本文引出的方向是：**以刚体承载角色控制器（自实现刚体角色控制器）**，从而在语义、观测、能力三层同时取得一致性与可扩展性。这不是对 KCC 的修补，而是把「运动学胶囊 + 手写扫掠」的职责收归本项目。

## 7. 替换方案须满足的条目（验收基线）

下表把第 3 节的每一条制约转成「替换方案必须解决」的条目，作为后续实施文档的验收基线。此处只列**须满足什么**，不含**如何实现**。

| 来源 | 替换方案须满足 |
| --- | --- |
| A1 / A2 / A3 | 速度的读写是单一量纲（建议统一为 m/s），水平与垂直可分别设置；换算不依赖调用方手算 |
| B1 / B2 | 对外报告的速度反映真实位移：被阻挡即归零；「被挡」与「越障」由引擎行为本身区分，无需外挂差分判据 |
| B3 | 动画驱动的位移与物理位移走不同通道，不存在「位移被当速度读回」的记账负担 |
| C1 / C2 | 垂直速度没有除设计参数以外的隐藏上限 |
| C3 | 着地由接触判定而非速度推导；地面法线可直接取得，无需外挂射线 |
| C4 | 跳跃相关接口语义自洽，不存在死代码与跨层不一致 |
| C5 | 阻尼（若需要）与水/垂直通道解耦，或明确不参与垂直 |
| D1 | 碰撞形状可在运行时按姿态更换，不触碰原生内存安全边界 |
| D2 | 接触信息（法线、接触面）可直接查询 |
| D3 | 可对控制器施加外部冲量（击退、爆炸、子系统合力） |
| D4 | 与动态刚体的交互有明确定义的语义 |
| D5 | 越障高度等于设定值，不含隐式折扣 |
| E1 | 传送是位置操作，不承担「复位施力状态」这类复合语义 |
| E2 | 乘客态可暂停自身积分 |
| E3 | 所有按时长/按步计的量按 `dt` 累加，兼容步频调整 |
| E4 | 保持「服务端权威、客户端零仿真」的分工不变 |

## 8. 不在本文范围

- 刚体控制器的**实现途径**：自写扫掠 / 滑墙 / 越障 / 贴地，或复用引擎既有能力。
- **运动学刚体与动力学刚体的取舍**，以及它与现有功率-力-速曲线、闪避速度赋值、跳跃助推窗口的相容性分析。
- **原型与实验**：最小可验证形体、与既有力学不变量测试的对照。
- **迁移路径与分阶段**：宿主回写、同步通道、测试与文档的同步改动。
- 上述内容归后续的实施文档。

本文 §6 引出的方向由两份后续文档承接：`docs/角色控制器-刚体动力学方案.md` 写控制器怎么设计，`docs/角色控制器-刚体原型与引擎约束.md` 写引擎允许什么、要求什么。本文 §7 验收基线的逐条对账在后者的「验收基线的对账」一节。

## 9. 引用坐标与调研快照

- 实时源码：`common/control/MechaCharacter.java`、`common/control/MechaControl.java`、`common/control/attr/MechaWalkingAttr.java`；`../Spark-Core/src/main/java/com/jme3/bullet/**`、`../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/**`；`../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.{cpp,h}`、`../Libbulletjme/src/main/native/glue/com_jme3_bullet_objects_infos_CharacterController.cpp`。
- 本仓库设计文档：`docs/角色控制器-行走物理设计.md` §11；`docs/ArmsCore双端权威与网络同步实现计划.md` §3.12.1、§3.12.2、§R7；`docs/宿主位置权威与位移摄入设计.md` §六、§七；`docs/GameTest车道指南.md` §5.2；`docs/角色控制器-刚体动力学方案.md`；`docs/角色控制器-刚体原型与引擎约束.md`。
- 外部先例（非本仓库，按 URL 定位）：Minie 的 `BetterCharacterControl.java`（https://github.com/stephengold/Minie ）；BSD 3-Clause 许可。
- 调研快照：本文结论基于上述源码与文档的当前形态；行号不入文——本仓库源码用「`路径#符号`」、设计文档用「§节号」、外部先例用 URL 定位。