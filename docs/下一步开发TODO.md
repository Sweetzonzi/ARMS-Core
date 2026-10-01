# ARMS-Core 下一步开发 TODO

> **状态**：执行清单
> **创建**：2026-07-16
> **最近更新**：2026-08-14（MechaControl 客户端闭环旁路观测 + 时长条件 + ENERGY 初始化，53 项单元测试通过）
> **目标**：先完成 `MechaControl` 客户端测试闭环和逻辑状态机验证，再接入动画、MoLang 与正式 `ArmsCore` 生命周期。
> **关联**：[分层控制器与状态机设计](./分层控制器与状态机设计.md) · [MechaControl 设计文档](./MechaControl设计文档.md) · [角色控制器-行走物理设计](./角色控制器-行走物理设计.md)

---

## 1. 当前里程碑

将 `ARMSClient` 中直接持有的测试用 `MechaCharacter` 提升为测试用 `MechaControl`，让以下链路在真实客户端物理步中闭环：

```text
客户端输入/环境采集
    -> MechaConditionSnapshot + MechaEvent
    -> MechaControl
    -> StateVariableContainer
    -> MechaLogicStateMachine
    -> MechaCharacter (KCC)
```

第一阶段采用**旁路观测模式**：状态机同步运行并输出调试状态，但暂不使用 `CAN_MOVE`、`CAN_JUMP`、`MOVE_SPEED_MODIFIER` 改写 KCC 行为。先确保原 KCC 行走和跳跃无回归，再逐项启用状态机产出。

`ARMSClient` 中的实例只是单玩家测试夹具，不代表正式架构采用全局单例。正式运行时应由每个 `ArmsCore` 或 `MechControllerSubsystem` 分别持有自己的 `MechaControl`。

---

## 2. P0：Spark-Core 子图事件处理

> **归属仓库**：`../Spark-Core`
> **当前状态**：Spark-Core 已提供 `broadcastEvent()`，按父级优先、深度优先顺序向处理后的活跃子树广播；ARMS-Core 已接入并完成测试。

- [x] 在 Spark-Core 为 `StateGraphController` 增加活跃状态树事件 API：`broadcastEvent(eventType)`。
- [x] 广播在控制器内部遍历私有 `activeChildren`，下游不能直接修改活跃子机集合。
- [x] 明确父级事件与子级事件的处理顺序。
  - posture 事件：`prone`、`mount`、`dismount`、`knockdown`。（`sneak` 已改为连续状态，见 §3.2）
  - gait 事件：`dodge`、`stun`、`hard_land`。
  - vertical 事件：`jump_start`、`jump_release`、`glide_activate`、`hover`、`fly`。
- [x] 当父级事件导致 posture 变化并替换活跃子图时，不再向旧子图投递同一事件；新子图接收当前事件。
- [x] 为以下场景添加 Spark-Core 测试：根节点事件、活跃子节点事件、非活跃子节点、未知事件、父级切换后子图重置、事件未匹配转移。
- [ ] 将当前每次触发都输出的通用 `INFO` 日志调整为可配置的调试日志，避免物理帧状态推进刷屏。

---

## 3. P0：逻辑状态机转移条件细化

### 3.1 posture 顶层状态

- [x] 将死亡设为最高优先级条件：所有非 `ragdoll` posture 在 `IS_DEAD = true` 时直接转入 `ragdoll`。
- [x] 确认 `ragdoll` 的生命周期：作为终态，通过 `reset()` 或 stop/start 回到初始状态，不隐式回到 `stand`。
- [x] 补齐 `crouch`、`prone` 的环境转移：离地进入 `air`，入水进入 `water`，不能在悬空或水中继续保持地面姿态。
- [x] 细化 `air` 落水/着地优先级。角色落入浅水且同时 `ON_GROUND = true`、`IN_WATER = true` 时，一次转入 `water`。
- [x] 蹲伏改为**连续状态**：`IS_SNEAKING` 条件直接映射 `stand ↔ crouch`（蹲下即 crouch、站直即 stand，与 onGround/inWater 同属环境条件），不再使用 `sneak` 事件；环境条件（air/water）优先级高于蹲伏。
- [ ] 明确 `riding` 下死亡、载具失效、强制下车的转移；骑乘时是否忽略普通 `ON_GROUND`/`IN_WATER` 条件也需固定。
- [ ] 为 `isSleeping`、`isFallFlying` 等已采集环境状态确定归属：加入现有 posture/vertical，或标注为当前里程碑明确不支持，避免快照字段长期悬空。

### 3.2 gait 子状态

- [x] 修正 `idle <-> drift` 振荡。
  - `idle -> drift`：`!HAS_INPUT && SPEED > stopThreshold`。
  - `drift -> idle`：`!HAS_INPUT && SPEED <= stopThreshold`。
  - 阈值应集中定义并带滞回区间，避免速度在临界点附近抖动。
- [x] `stun` 使用持续时间退出（`STUN_DURATION = 0.8s`，`StateTimeCondition` 到时按输入/速度分支），不再使用 `!HAS_INPUT`；并补齐从所有 gait 进入 stun 的事件转移。
- [x] `hard_land` 退出改用恢复计时器（`HARD_LAND_DURATION = 1.0s`，可被 dodge 取消）。
- [ ] `hard_land` 进入条件（落地冲量/落地前垂直速度）待实现，当前无入口。
- [x] `dodge` 已定义持续时间（`DODGE_DURATION = 0.4s`）与时长退出，不再根据当前输入立即退出。
- [ ] `dodge` 能量消耗与冷却待实现。
- [x] 补齐从其他 gait 进入 `stun` 的事件转移（idle/creep/jog/sprint/drift/dodge/hard_land → stun）。
- [x] `ENERGY` 已初始化为满值（`MechaControl.INITIAL_ENERGY = 100f`，sprint 立即可用）。
- [ ] `ENERGY` 消耗/恢复系统待实现。
- [ ] 决定离散事件的唯一表达方式：使用 `triggerEvent` 驱动逻辑转移，`EVENT_*` 变量仅在表现层确实需要读取时保留，避免同一事件必须同时写 flag 和触发事件。

### 3.3 vertical 子状态

- [x] 修正 `jump_charge` 在 `ON_GROUND = true` 时自动返回 `ground` 的条件；改为镜像 KCC 蓄力状态。
- [x] 明确跳跃蓄力的唯一状态源。
  - 建议当前阶段由 KCC 维护蓄力计时和冲量计算。
  - 逻辑状态机镜像 KCC 的 `isChargingJump()`，负责表现语义和输入门控。
  - 后续若改为逻辑状态机主导，需移除 KCC 内部重复状态，不能长期保留两套权威状态。
- [x] 明确跳跃按下、持续、松开三个信号的语义：按下/持续是 `keyFlags` 的 `BIT_JUMP`（连续量，可丢可合并），松开是 `MechaEvent.JUMP_RELEASE`（单帧边沿，经事件闩锁在物理线程被消费一次）。
- [ ] 细化 `fall`、`glide`、`hover`、`fly` 的进入条件和互斥优先级；事件只负责切换意图，环境条件仍需阻止非法状态。

### 3.4 状态产出一致性

- [x] gait 与 vertical 分别写入 `GAIT_CAN_*` / `VERTICAL_CAN_*`，不再覆盖对方来源。
- [x] 将输入许可改为 `MechaLogicStateMachine.progress()` 后的集中派生：
  - `CAN_MOVE = postureAllowsMove && gaitAllowsMove && verticalAllowsMove`。
  - `CAN_JUMP = postureAllowsJump && gaitAllowsJump && verticalAllowsJump`。
- [x] 为 `POSTURE`、`GAIT`、`VERTICAL` 和 one-hot 布尔值增加单元测试断言，保证任一时刻每组恰好一个值为 true。

---

## 4. P0：MechaControl 逻辑层接线

- [x] 让 `MechaControl` 实际持有并初始化共享的 `StateVariableContainer`、`GameplayTagContainer`、`MechaLogicStateMachine`。
- [x] 在物理线程内按固定顺序执行（`MechaControl.frameLogic`）：
  1. 消费最新快照和已 latch 的事件（帧首原子取走事件批）。
  2. 采集步进前 KCC 状态，包括 `ON_GROUND`、`SPEED`、`VERTICAL_SPEED`、蓄力状态。
  3. 写入外部输入、环境变量、KCC 变量和事件变量。
  4. 将事件路由到 posture、当前 gait 或当前 vertical 控制器。
  5. 推进逻辑状态机的自动转移。
  6. 发布只读调试状态 — 待实现（当前直接暴露变量容器）。
  7. 应用状态机产出再调用 `kcc.prePhysicsTick(dt)` — 当前处于旁路观测模式，门控未启用（`setBypassObservation(false)` 可开启）。
- [x] 修正视角到世界方向的 yaw 符号，使其与当前 `ARMSClient` 已验证的方向一致。
- [x] 将跳跃松开边沿真正传给 KCC（`MechaEvent.JUMP_RELEASE` → KCC 的松开闩锁），并保证蓄力期间释放边沿不被 `CAN_JUMP=false` 吞掉。
- [x] 朝向由视野偏航绝对驱动：`MechaCharacter.setViewYaw` 把写入值归约到 [−180, 180) 后赋值给 `currentYaw`，`MechaControl.applyFacing` 每物理步在解算移动方向之前调用一次（死亡 / ragdoll 时跳过，朝向冻结）。
- [x] WASD 是体系意图：唯一一次「意图 → 世界」变换在 `MechaCharacter.setMoveIntent` 内按本步朝向完成，`MechaControl.applyMoveIntent` 只做许可门控与透传；闪避方向 `resolveDodgeDirection` 用同一个角独立解出。
- [ ] 区分以下 KCC 控制量，不能全部复用 `setInputScale()`：
  - 是否允许移动。
  - 是否允许开始/释放跳跃。
  - gait 的移动速度或驱动力倍率。
  - 动画脚本对整体能动性的临时缩放。
- [ ] 增加不可变的逻辑状态查询结果，例如 `LogicStateSnapshot(posture, gait, vertical)`；不要把可变 `StateVariableContainer` 暴露给主线程或调试 UI。
- [x] 第一阶段只记录状态变化（`MechaControl.logStateChanges`），不每帧打印；确认状态稳定后再开启反向控制。

---

## 5. P0：MechaConditionSnapshot 线程模型

### 5.1 record 方案

- [x] 将 `MechaConditionSnapshot` 改为不可变 `record`，构造后通过 `volatile` 字段发布给物理线程（record final 字段 + volatile 引用保证安全发布）。
- [x] 将连续状态与离散边沿分开：WASD、视角、按键是否按住属于 snapshot；跳跃松开（`MechaEvent.JUMP_RELEASE`）、切换姿态、闪避等单独 latch（`MechaEvent` + 原子事件缓冲），不依赖“最新快照”恰好被物理线程看到。
- [x] `applyConditionSnapshot` 不再保留调用方之后还能修改的对象引用（record 不可变）。
- [x] `pendingEvents` 改为线程安全且具有明确消费语义的结构：`AtomicReference<Set<MechaEvent>>`，postEvent copy-on-write 追加，物理线程帧首 `getAndSet(空集)` 原子取走整批——任意交错下事件不丢失、不重复，只归属本帧或下一帧。

### 5.2 GC 压力判断

Java `record` 仍是普通堆对象，本身不会自动减少分配。若每客户端 tick 为每个控制器创建一个 record，理论分配率约为：

```text
分配对象数/秒 = 活跃控制器数量 * 输入采样频率
```

单个本地玩家按 20 至 60 Hz 创建快照，压力通常可以忽略；大量服务端机体同时采样时才可能形成可见的年轻代 GC 压力。通过 `volatile`/`AtomicReference` 发布的对象会逃逸，不能假设 HotSpot 一定通过逃逸分析消除分配。

当前决策：**先使用不可变 record 获得清晰、正确的跨线程语义，不提前实现对象池或复杂无锁结构。** 在多机体压力场景用 JFR/分配剖析确认 `MechaConditionSnapshot` 是否成为热点，只有数据证明存在问题时再改为无分配方案。

候选无分配方案：

- [ ] 在确有压力时，改为“锁内复制到物理线程私有快照”。写线程更新 staging 对象，物理线程在短临界区复制全部 primitive 字段，之后只读取私有对象。
- [ ] 或实现带 sequence/version 的 SPSC 双缓冲，确保读线程不会读取正在被写线程修改的槽位。
- [ ] 不使用可变对象池冒充不可变 record；复用已经发布的对象会破坏可见性和一致性，风险高于节省的分配。

---

## 6. P1：ARMSClient 测试闭环

- [x] 用测试 `MechaControl` 替换当前直接持有的测试 KCC，KCC 仍由 `MechaControl` 内部持有（`ClientMechaTestRig`）。
- [x] 创建最小测试 `MechaControlHolder`（`ClientMechaTestRig`），不要使用尚未完成且会传入 null KCC 的正式 `ArmsCore` 骨架。
- [x] 每个客户端 tick 只采集输入和环境快照，不从主线程读取或修改物理状态。
- [x] 每个物理步只调用 `MechaControl.onPhysicsStep(dt)`，避免客户端同时绕过编排器直接调用 KCC。
- [x] 增加状态变化日志（`MechaControl.logStateChanges`），包含 `posture/gait/vertical`、关键输入、`ON_GROUND`、水平/垂直速度。
- [ ] 处理玩家退出、切换世界/维度、死亡重生和客户端重连：从旧 PhysicsSpace 移除 KCC，清空状态机和事件，允许重新初始化。
- [x] 保留现有定期 warp 仅作为测试保护，并记录触发原因；正式链路应由宿主/KCC 同步协议替代。

旁路观测阶段验收标准：

- [ ] 原有 WASD、转向、制动和跳跃蓄力行为无回归。
- [ ] 静止多帧保持稳定 `idle`，不与 `drift` 振荡。
- [ ] 行走、慢走、冲刺、离地、落地、入水、蹲伏、卧倒的状态序列符合预期。
- [ ] 事件只由目标层消费一次，未知或当前状态不支持的事件可诊断但不破坏状态。
- [ ] 重连或切维度后不存在旧碰撞体和旧控制器继续步进。

---

## 7. P1：测试与验证

- [x] 为纯逻辑图增加普通单元测试，优先于启动完整 Minecraft 客户端（当前 53 项，`test` 全绿）。
- [ ] 覆盖初始状态、所有合法转移、非法事件、转移优先级、连续多帧稳定性和 one-hot 一致性。
- [x] 对死亡用例做参数化测试：从每个非 ragdoll posture 设置 `IS_DEAD`，下一次推进必须进入 `ragdoll`。
- [x] 对 `idle/drift` 使用阈值边界和滞回测试。
- [x] 对输入发布增加并发测试（`MechaControlTest.concurrentEventPostsAreNotLost`），验证离散边沿不丢失、不重复消费；snapshot 字段撕裂由不可变 record 结构性杜绝。
- [ ] 增加客户端人工验证清单；只有需要真实 PhysicsSpace、碰撞和地形的部分才使用 `runClient`/GameTest。
- [x] 每个阶段至少执行 `compileJava`；逻辑测试已纳入默认 `check`（当前 `test` / `check` 均通过）。

---

## 8. P2：状态机反向控制 KCC

- [ ] 在旁路验证稳定后启用 `CAN_MOVE`、`CAN_JUMP`，验证 stun、hard_land、jump_charge、ragdoll 等门控。
- [ ] 启用 gait 移动倍率，但不要连带缩放跳跃冲量。
- [ ] 明确状态读取发生在本物理步还是下一物理步，避免依赖偶然调用顺序形成一帧延迟。
- [ ] 为 posture 切换需要的 KCC 参数建立集中应用点，例如胶囊高度、步高、重力、碰撞开关。
- [ ] 对每个反向控制逐项启用和回归，不一次性打开所有状态产出。

---

## 9. 后续阶段，当前不阻塞

- [ ] 正式接入 `ArmsCore`、根 `SubPart`、素体属性和多实例生命周期。
- [ ] 接入 `MultiAnimStateMachine`、本地 `AnimStateMachine` 和动画事件分发。
- [ ] 接入 `MechaMolangContext` 与 `ctrl.*` 查询。
- [ ] 提取 `body_root` 动画根位移和 Y 轴旋转，并与 KCC 位移合成。Y 轴旋转要先定义与视野朝向权威（`MechaCharacter.setViewYaw`）的合成方式，见 `docs/ArmsCore双端权威与网络同步实现计划.md` §3.12。
- [ ] 实现 DRIVE 模式及轮子、推进器、机翼等子系统力汇聚。
- [ ] 完善死亡 ragdoll 的物理体切换、复活重建和宿主同步。

这些任务应在逻辑状态机和 KCC 测试闭环稳定后推进，避免动画、宿主装配和基础运动三个问题域同时调试。
