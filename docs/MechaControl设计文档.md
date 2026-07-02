# MechaControl — 运动控制编排器设计

> **状态**: 草案 · **创建**: 2026-07-03\
> **关联**: 总体设计文档.md · 角色控制器-行走物理设计.md · 分层控制器与状态机设计.md

---

## 目录

1. [MechaControl 的定位](#1-mechacontrol-的定位)
2. [MechaControlHolder — 持有者接口](#2-mechacontrolholder--持有者接口)
3. [MechaConditionSnapshot — 条件快照](#3-mechaconditionsnapshot--条件快照)
4. [MechaEvent — 离散事件](#4-mechaevent--离散事件)
5. [MechaControl — 编排器核心](#5-mechacontrol--编排器核心)
6. [数据流](#6-数据流)
7. [两条产品线](#7-两条产品线)
8. [与现有系统的对接](#8-与现有系统的对接)

---

## 1. MechaControl 的定位

`MechaControl` 是 ARMS-Core 角色运动系统的**唯一编排器**。它位于物理线程内，聚合以下职责：

| 职责 | 说明 |
|------|------|
| 输入消费 | 接收 Holder 写入的 `MechaConditionSnapshot` 和 `MechaEvent`，连同 KCC 内部采集的物理状态，汇入 `StateVariableContainer` |
| 状态机驱动 | 管理中央 `MultiAnimStateMachine`（全局动作）和本地 `AnimStateMachine`（零件自主动作），每物理帧推进 |
| 动画编排 | 从根 SubPart 提取 `animRootDelta` 写入 KCC，聚合动画完成状态 |
| 物理桥接 | 转发玩家输入给 `MechaController`（KCC），调用 `prePhysicsTick(dt)` |
| MoLang 集成 | 将 `StateVariableContainer` 注入 `MechaMolangContext`，供 JSON 动画控制器 `ctrl.*` 查询 |

**为什么不是子系统**：MechaControl 是直接操作物理刚体的纯逻辑组件，不依赖信号总线。它作为 `ArmsCore` 私有字段（机娘）或 `MechControllerSubsystem` 持有（可驾驶机甲），不暴露为 UGC 可配置的子系统。

---

## 2. MechaControlHolder — 持有者接口

```java
public interface MechaControlHolder {
    MechaControl getMechaControl();    // 委托 default 方法
    SubPart getRootSubPart();          // 根 SubPart → 追溯装配体整体
    MechChassisDefinition getChassis(); // 素体定义元数据

    // 默认方法 — 输入写入
    default void writeConditionSnapshot(MechaConditionSnapshot snapshot) { ... }
    default void postEvent(MechaEvent event) { ... }
    default void postEvents(Collection<MechaEvent> events) { ... }
}
```

### 两条产品线实现

| | 机娘/外骨骼 | 可驾驶机甲 |
|---|---|---|
| 实现类 | `ArmsCore` | `MechControllerSubsystem` |
| MechaControl 归属 | ArmsCore 私有字段 | 子系统持有 |
| `getRootSubPart()` | chassis.root → 躯干 SubPart | 子系统安装所在的 SubPart |
| 输入来源 | IArmsHost Capability | SeatSubsystem → 信号总线 |

**信息追溯链**：`rootSubPart → subPart.part → partNet → 全体 Part → 全体 SubPart`

---

## 3. MechaConditionSnapshot — 条件快照

每物理帧由 Holder 采集的条件快照。**仅包含从外部世界进入 MechaControl 的数据**。

```java
public class MechaConditionSnapshot {
    // 玩家输入
    float inputForward, inputStrafe;  // [-1, 1]
    boolean jumpPressed, sprintPressed;
    // 视角
    float viewYaw, viewPitch;
    // 宿主环境条件
    boolean inWater, inLava, isDead, isSleeping, isFallFlying, isInWall, isOnFire;
}
```

**边界原则**：KCC 推导状态（onGround、speed 等）**不在快照中**——这些由 MechaControl 内部从 KCC 直接采集。快照只负责"外部 → 内部"的单向数据流动。

### 与 YSM 的关系

YSM 的 `QueryBinding`（`q.is_on_ground`、`q.ground_speed` 等）直接查询实体——因为 YSM 模型一定属于实体。ARMS-Core **不一定**——载具机甲没有对应的 LivingEntity。所以环境条件通过 Snapshot 由 Holder 采集：机娘 Holder 从实体采集，载具 Holder 固定返回 false。

### 字段策略

字段全量保留，当前不支持的条件固定返回 false/0。

---

## 4. MechaEvent — 离散事件

与 Snapshot 的职责边界：

| | MechaConditionSnapshot | MechaEvent |
|---|---|---|
| 语义 | 持续状态，每帧采样 | 一次性触发，瞬发瞬逝 |
| 生命周期 | 每帧覆盖 | latch 到帧末，消费后清除 |
| 例子 | 按住前进、在水中、死亡中 | 按下开火、使用物品、切换状态 |

```java
public enum MechaEvent {
    // 当前实现
    ATTACK_PRIMARY, ATTACK_SECONDARY, USE_ITEM, INTERACT,
    TOGGLE_DRIVE,
    HURT, STUN,
    MOUNT, DISMOUNT,
    // 未来扩展: TOGGLE_SNEAK, TOGGLE_PRONE, DIVE, TACTICAL_SPRINT, SLIDE
}
```

### 与 Snapshot 的分工示例

- `TOGGLE_DRIVE` 必须是事件（不能是快照字段）——它在按下那帧触发一次。放快照里需要 Holder 手动记"上一帧"来比较，容易重复触发。
- `jumpPressed` 仍然是快照字段——它是持续状态（按住蓄力），不是离散触发（松开才触发跳跃）。

### 事件不经过信号总线

事件由 Holder → `MechaControl.postEvent()` → `pendingEvents` → 状态机推进前消费 → 清除。子系统若需响应（如武器开火触发弹药消耗），走独立路径。

---

## 5. MechaControl — 编排器核心

### 5.1 结构

```java
public class MechaControl {
    MechaControlHolder holder;
    MechaController kcc;                              // KCC 运动学控制器

    MechaConditionSnapshot conditionSnapshot;       // 本帧条件快照
    EnumSet<MechaEvent> pendingEvents;              // 本帧事件队列

    // TODO: 状态机与动画（待 Spark-Core 基础设施）
    StateVariableContainer variables;               // 统一状态变量
    Map<String, MultiAnimStateMachine> centralMachines; // 中央状态机
    List<IAnimatable<SubPart>> allAnimatables;       // 全体可动画 SubPart
    IAnimatable<SubPart> chassisAnimatable;          // 根骨骼提供者
    MechaMolangContext molangContext;                // MoLang 上下文
}
```

### 5.2 构造

```java
public MechaControl(MechaControlHolder holder, MechaController kcc)
```

构造时建立双向绑定：holder 持有 MechaControl，MechaControl 持有 holder。

### 5.3 物理帧主流程

```
public void onPhysicsStep(float dt):

  1. forwardInputToKCC()
     ├─ 视角偏航 → 将 inputForward/inputStrafe 转换为世界坐标系方向
     └─ → kcc.setMoveInput(worldDirX, worldDirZ)
     └─ → kcc.setJumpInput(held, released)

  2. 汇入快照字段到 StateVariableContainer
     variables.set(INPUT_FORWARD,    snapshot.inputForward)
     variables.set(IS_SPRINTING,     snapshot.sprintPressed)
     variables.set(IN_WATER,         snapshot.inWater)
     variables.set(IS_DEAD,          snapshot.isDead)
     ...

  3. 内部采集 KCC 状态
     variables.set(ON_GROUND,        kcc.onGround())
     variables.set(SPEED,            kcc.getHSpeed())
     ...

  4. 事件 → latched flags
     variables.set(EVENT_ATTACK_PRIMARY, pendingEvents.contains(ATTACK_PRIMARY))
     variables.set(EVENT_HURT,          pendingEvents.contains(HURT))
     ...

  5. molangContext.setVariables(variables)
     → ctrl.is_on_ground / ctrl.speed / ctrl.is_in_water 等可直接查询

  6. centralMachines.forEach { it.progress() }
     └─ PlayAnimAction → AnimGroup 分层广播 → 三级动画回退

  7. dispatchEventsToLocalMachines()
     → weaponPart.animController.stateMachines["fire"].triggerEvent("fire")

  8. extractAnimRootDelta()
     ├─ body_root 骨骼帧间世界坐标差 → kcc.setAnimRootDelta(dx, dy, dz)
     └─ body_root 骨骼 Y 轴旋转帧间差 → kcc.setAnimRootYawDelta(deltaYaw)
        （用于转身斩、回旋踢、idle 微晃等动画驱动面向变化）

  9. kcc.prePhysicsTick(dt)
     → 行走力模型 + 跳跃蓄力 + 碰撞 sweep + 多通道合成

  10. pendingEvents.clear()
```

### 5.4 移动输入方向转换

Minecraft yaw：0=南，90=西。视角-世界坐标转换：

```
worldDirX = forward × sin(yaw) + strafe × cos(yaw)
worldDirZ = forward × cos(yaw) - strafe × sin(yaw)
```

此转换在 `MechaControl.forwardInputToKCC()` 中完成，Holder 不需要关心坐标转换——它只需提供原始的 `[-1,1]` 前/右输入。

### 5.5 动画根运动（位移 + Y 轴旋转）

根运动（Root Motion）不仅包含位移，还包含面向变化。MechaControl 从 body_root 骨骼每帧提取两样东西：

```
提取源：body_root 骨骼的帧间世界位姿差

  位移 (dx, dy, dz, m/tick)
    → kcc.setAnimRootDelta(dx, dy, dz)
    → 在 updateWalk() 中与物理位移叠加写入 KCC XZ
    
  Y 轴旋转 (deltaYaw, rad/tick)
    → kcc.setAnimRootYawDelta(deltaYaw)
    → 在 prePhysicsTick() 第一步叠加到 KCC 当前 Y 旋转
    → KCC 的 angularFactor(0,1,0) 保证只接收 Y 旋转
```

**旋转的应用场景**：

| 场景 | animDelta | animYawDelta |
|------|:---:|:---:|
| 正常行走 | 0 | 0（面向由玩家视角决定） |
| 转身斩 | 小（可能前冲） | 大（~π rad/tick，快速转向） |
| 回旋踢 | 中等 | 大（~2π rad，完整一圈） |
| idle 微晃 | 0 | 小（±0.05 rad 摆动） |
| 受击后仰 | Y ≈ 0.3 | 0 或微小 |

**±π 环绕处理**：提取 yaw 帧间差时，若 delta > π 则减 2π，若 delta < -π 则加 2π，保证增量是"最短路径"而非绕远路。

**分离距离折减对旋转生效吗**？不。旋转不受分离距离限制——即使躯干被击退很远，面向仍跟随动画。这符合直觉：被炸飞时机体应该面朝受击方向（受击动画的 yaw delta），而不是死盯原来的方向。

---

## 6. 数据流

```mermaid
flowchart TB
    subgraph Holder["MechaControlHolder (ArmsCore / MechControllerSubsystem)"]
        direction LR
        Input[玩家按键 + 视角] --> S1[MechaConditionSnapshot]
        Entity[宿主实体状态<br/>isInWater/isDead/...] --> S1
        Discrete[离散操作] --> E1[MechaEvent]
    end

    S1 -->|writeConditionSnapshot| MC[MechaControl]
    E1 -->|postEvent| MC

    subgraph MC["MechaControl（物理线程）"]
        direction TB
        KCC[KCC 内部采集<br/>onGround/speed/...] --> VARS[StateVariableContainer]
        S1 --> VARS
        E1 -->|latched flags| VARS

        VARS -->|Java 直接读| CSM[中央状态机<br/>MultiAnimStateMachine]
        VARS -->|ctrl.* 绑定| ML[MoLang JSON 条件]
        E1 -->|triggerEvent| LSM[本地状态机<br/>AnimStateMachine]

        CSM -->|AnimGroup 分层| Render[Spk-Core AnimController 混合]
        LSM --> Render

        S1 -->|forward/strafe/jump| MECHC[MechaController(KCC)]
        KCC -->|setAnimRootDelta| MECHC
    end

    MECHC -->|prePhysicsTick| Phys[行走力/跳跃/碰撞sweep]
    Render -->|骨骼位姿 + 插值| GPU[渲染]
```

### 三条路径，一个数据源

```
StateVariableContainer
    ├──→ 硬编码 StateCondition  (Java 直接读，无开销)
    ├──→ MoLang ctrl.* 绑定     (JSON 动画控制器调用)
    └──→ 行走力模型等物理计算    (读 speed、onGround 等)
```

MoLang 不是状态机的必经路径——Java 硬编码条件直接读 `variables.get(...)`，只有 UGC JSON 动画控制器的 MoLang 条件才走 MoLang 评估。

---

## 7. 两条产品线

### 7.1 机娘/外骨骼（ArmsCore）

```java
class ArmsCore implements IPartAssembly, MechaControlHolder {
    MechaControl control;     // 私有字段，非子系统

    // MechaControlHolder 实现
    SubPart getRootSubPart() { /* chassis.root → 躯干 SubPart */ }
    MechChassisDefinition getChassis() { /* mech_chassis.json */ }
}

// 物理步回调
ArmsCore.onPhysicsStep(dt):
    MechaConditionSnapshot snap = collectFromHost();  // IArmsHost + 实体
    writeConditionSnapshot(snap);
    control.onPhysicsStep(dt);
```

- 输入路径：`IArmsHost.consumeMoveInput()` → `MechaControl.processInput()`（直接调用，不走信号总线）
- 伤害路径：Part → `ArmsCore.applyDamage()` → `IArmsHost.applyMechDamage()`
- 渲染：宿主实体 Renderer 遍历 Part → SubPart 模型

### 7.2 可驾驶机甲（MechControllerSubsystem）

```java
class MechControllerSubsystem extends BasicSubsystem implements MechaControlHolder {
    MechaControl control;

    // MechaControlHolder 实现
    SubPart getRootSubPart() { /* 本子系统安装所在的 SubPart */ }
    MechChassisDefinition getChassis() { /* 由 Part JSON 或机车定义指定 */ }
}

// 物理步回调
MechControllerSubsystem.onPhysicsStep(dt):
    MechaConditionSnapshot snap = collectFromSignalBus();  // SeatSubsystem → 信号总线
    writeConditionSnapshot(snap);
    control.onPhysicsStep(dt);
```

- 输入路径：`SeatSubsystem` → 信号总线 → `MechControllerSubsystem.updateMoveInputs()` → `MechaControl.processInput()`
- `handShake()` 自动发现下属轮子/机翼/推进器
- 渲染走 `MMPartEntity`

### 7.3 对比

| | 机娘/外骨骼 (ArmsCore) | 可驾驶机甲 (VehicleCore 扩展) |
|---|---|---|
| MechaControl 归属 | ArmsCore 私有字段 | MechControllerSubsystem 持有 |
| 输入来源 | IArmsHost Capability | SeatSubsystem → 信号总线 |
| UGC 可见性 | 不可见（MechaControl 非子系统） | 可见（Part JSON 子系统） |
| 可被载具搭载 | ✓ (外骨骼坐高达) | ✗ (SeatSubsystem 互斥) |

---

## 8. 与现有系统的对接

### 8.1 MechaController（KCC）

`MechaController` 已实现完整的行走/跳跃物理（`prePhysicsTick`）。MechaControl 在它之前：
1. `forwardInputToKCC()` 写入 `setMoveInput` / `setJumpInput`
2. 动画编排后写入 `setAnimRootDelta` / `setGravityScale` / `setSeparationDistance`
3. 最后调用 `kcc.prePhysicsTick(dt)` 完成物理积分

### 8.2 分层控制器与状态机

`分层控制器与状态机设计.md` 描述的状态机体系依赖以下 Spark-Core 基础设施（待实现）：

- `StateVariableContainer` — 统一状态变量容器
- `MultiAnimStateMachine` — 中央状态机（分组广播 + 三级回退）
- `AnimStateMachine` — 本地状态机（零件独立动画）
- `MechaMolangContext` — MoLang ctrl.* 绑定上下文

这些就绪后，MechaControl 中的 TODO 区域将逐项填补。

### 8.3 子系统合力叠加

当前阶段（WALK 状态）子系统不参与行走力计算。DRIVE 状态下轮子/推进器/机翼通过 `addSubsystemForce` 汇聚，施加到躯干刚体而非 KCC。实现时机待 WALK 行走闭环后再考虑。
