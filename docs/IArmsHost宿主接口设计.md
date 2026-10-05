# `IArmsHost` 宿主接口设计

> 关联文档：
>
> - `docs/总体设计文档.md` —— §2.1 的 `IArmsHost` 与 `ArmsCore` 原始设定
> - `docs/宿主接入与伤害管线设计.md` —— 玩家宿主的接入方式、位置回写相位、伤害管线的两端
> - `docs/ArmsCore双端权威与网络同步实现计划.md` —— 阶段 4.1（引入 `IArmsHost`）、阶段 4.2（`ArmsCore` 持有宿主引用）
>
> **本文自包含**：不要求读者先读上述任何一份文档，也不要求读者了解本文的任何旧版本。引用本仓库源码时按 `路径#符号` 给出坐标，引用兄弟仓库时按「仓库名 + 路径」给出坐标，正文在被引用处就地说明该符号的职责。

## 一、这个接口解决什么问题

`ArmsCore` 是逻辑机甲，不是实体。它的位姿权威是 Bullet 的运动学角色控制器（KCC），而"机娘在世界里占的位置"由承载它的实体体现。承载实体可能有好几种：玩家、Doll 实体、AI 敌人。

`IArmsHost` 回答一个问题：**`ArmsCore` 需要向"承载它的那个实体"提出哪些要求，才不必知道那是哪种实体？**

答案只有两件事：

1. **绑定关系** —— 这个宿主当前承载哪一个 `ArmsCore`。这是"是不是机娘"的唯一判据。
2. **位姿落地** —— `ArmsCore` 把 KCC 算出的位姿写进宿主实体。

其余的一切（相机、交互、背包、无敌帧、护甲、附魔、事件、输入采集、伤害落地）都由原版或别的机制承担，**不进这个接口**。§四 逐条说明为什么。

## 二、接口定义

```java
package io.github.sweetzonzi.arms_core.common;

/**
 * 机娘宿主：承载一份 ArmsCore 的实体。
 */
public interface IArmsHost {

    /** 宿主实体自身。接口由实体实现，因此实现处返回 {@code this}。 */
    LivingEntity getHostEntity();

    /** 当前承载的装配体；{@code null} 表示人类形态。 */
    @Nullable ArmsCore getControlledArmsCore();

    /** 建立 / 解除绑定。传 {@code null} 即解绑。 */
    void setControlledArmsCore(@Nullable ArmsCore core);

    /** 把 KCC 的位置写进宿主实体。由 ArmsCore 在服务端主线程调用。 */
    void applyPose(Vec3 capsuleCenter, float yRot, float yHeadRot);

    /** 把 KCC 的线速度写进宿主实体。由 ArmsCore 在服务端主线程调用。 */
    void applyVelocity(Vec3 velocity, float physicsStepSeconds);
}
```

五个方法，三类职责。`getHostEntity()` 与绑定两个方法解决第 1 件事，`applyPose` 与 `applyVelocity` 解决第 2 件。

### 2.1 参数的基准与单位

| 参数 | 基准 / 单位 | 依据 |
| --- | --- | --- |
| `capsuleCenter` | **世界坐标，胶囊中心**（不是脚底） | `common/control/attr/MechaBodyPreset.java#HALF_TOTAL` 定义了脚底与胶囊中心的换算；`common/ArmsCore.java#enterPhysicsSpace` 的入参也是胶囊中心 |
| `yRot` / `yHeadRot` | 度制，与 `net.minecraft.world.entity.Entity#setYRot`、`net.minecraft.world.entity.Entity#setYHeadRot` 同一约定 | 玩家宿主**不写**这两个参数，理由见 §5.2；保留在签名里供模型朝向与实体朝向分离的宿主使用 |
| `velocity` | **水平分量是每物理步位移，垂直分量是 m/s** | `docs/角色控制器-行走物理设计.md` 的单位约定；这一条不对称，实现方不得当作统一的速度矢量处理 |
| `physicsStepSeconds` | 单个物理步的时长 (s) | `common/ArmsCore.java#physicsStepSeconds`，取自 `Spark-Core` 的 `PhysicsLevel#tps`。服务端物理空间是 `baseStep = 5`（100 Hz，`0.01 s`），客户端是 `baseStep = 3`（60 Hz，约 `0.0167 s`），两端不同 |

**`velocity` 与 `physicsStepSeconds` 必须一起用。** `net.minecraft.world.entity.Entity#setDeltaMovement` 的三个分量语义统一是**每 tick 位移**，而 `velocity` 的水平分量是每物理步位移、垂直分量是 m/s。直接透传会同时错两次，且两个错的倍数不同：

```text
水平（每物理步位移 → 每 tick 位移）：× 20 × physicsStepSeconds
垂直（m/s → 每 tick 位移）        ：÷ 20
```

`20` 是 Minecraft 的 TPS 常数（`net.minecraft.server.MinecraftServer` 的 tick 频率），`physicsStepSeconds` 是每 tick 内的物理步数（`20 × physicsStepSeconds`）的倒数。这个换算只在宿主侧做一次——`common/ArmsCore.java#DATA_VEL` 走的是 KCC 原生单位，两者不要互相套用。

`net.minecraft.world.entity.Entity#position` 的语义是**包围盒底面中心（脚底）**，而胶囊中心比胶囊底面高 `HALF_TOTAL`。写位置时必须明确是哪一个：把胶囊中心直接写进 `net.minecraft.world.entity.Entity#setPos` 会把宿主整体抬高 `HALF_TOTAL`（当前素体取值下为 `1.2 m`），这是本项目已经登记过一次的"差一个身高的经典错误"（`docs/ArmsCore双端权威与网络同步实现计划.md` §3.14）。

**`capsuleCenter` 这一行的时效。** `docs/宿主位置权威与位移摄入设计.md` §九 第 4 步会删掉服务端每 tick 的位置回写，届时 `applyPose` 失去调用方（当前唯一调用点是 `common/ArmsCoreServerEvents.java#applyPoseToHost`），位置基准随之下沉到「客户端按 `DATA_POS` 摆放实体」那一条路径（换算见 `client/ClientHostPoseEvents.java#applySyncedPose`）。接口是否保留一个位置落地入口供非玩家宿主使用，在该设计的实现阶段一并定；本行在此之前仍然成立。

基准由原版的两处实现共同确定：`setPos` 把分量存进 `position` 后以它为原点重建包围盒，重建由 `net.minecraft.world.entity.EntityDimensions#makeBoundingBox` 完成，形状是 `AABB(x − 宽/2, y, z − 宽/2, x + 宽/2, y + 高, z + 宽/2)`；交叉验证是 `net.minecraft.world.entity.player.Player#DEFAULT_EYE_HEIGHT` 为 `1.62`，只有在"`position.y` 是脚底"的语义下才等于"脚上 1.62 m"。

### 2.2 调用约束

- **只有 `ArmsCore` 调用 `applyPose` / `applyVelocity`。** 宿主实现不主动拉取位姿——位姿的权威在 KCC，宿主没有独立的位姿来源。
- **只在服务端主线程调用。** 调用点在 `common/ArmsCoreServerEvents.java#syncToClients` 所在的 `LevelTickEvent.Post`，与同步写包同相位，使同步出去的 `DATA_POS` 与宿主实体位置取自同一次采样。两个守卫缺一不可：`ArmsCore` 自身是服务端实例（`common/ArmsCore.java#authoritative`），且 §6.1 的宿主引用非 `null`——客户端实例两者都不满足。
- **物理线程不调用。** KCC 在主线程被读取属良性竞态（`docs/ArmsCore双端权威与网络同步实现计划.md` §3.5 已登记同类做法），但写实体是主线程专属操作。

## 三、命名约束：不得与 `Entity` 的既有成员冲突

这是本接口设计中最容易踩的一处，因为"设位置"的自然命名在原版里**已经被占用，而且其中两个是 `final`**。依据取自 `net.minecraft.world.entity.Entity`：

| 原版成员 | 形态 | 对接口命名的影响 |
| --- | --- | --- |
| `net.minecraft.world.entity.Entity#setPos` | **`public final void setPos(Vec3)`**，另有非 final 的 `setPos(double, double, double)` | `setPos` 被占用；单参数 `Vec3` 那个重载是 `final`，Mixin 无法覆盖 |
| `net.minecraft.world.entity.Entity#getPosition` | **`public final Vec3 getPosition(float)`** | 方法名已被占用且为 `final` |
| `net.minecraft.world.entity.Entity#position` | `public Vec3 position()`，非 final | 只读 getter 已存在，无需另设 |
| `net.minecraft.world.entity.Entity#setDeltaMovement` | `public void setDeltaMovement(Vec3)`，另有 `(double, double, double)` 重载 | 速度写入已被占用 |

两条硬约束由此得出：

1. **Mixin 不得实现 `final` 方法。** 若接口声明 `setPosition(Vec3)` 并在 Mixin 里提供方法体，而目标类已有一个 `final` 的同签名方法，Mixin 无法覆盖它，结果不是"没生效"而是**类加载或运行期失败**。因此接口绝不声明与 `setPos(Vec3)`、`getPosition(float)` 同签名的成员。
2. **同名的不同签名仍然不可取。** 声明 `getPosition()`（无参）在 Java 里合法——原版是 `getPosition(float)`——但两个方法同处一个类时，阅读者必须数参数才能判断调的是哪一个，而其中一个的返回值还依赖 `partialTick`。这类命名歧义一律避免。

**本接口采用 `apply*` 前缀。** 理由有两条：

- 语义准确：`applyPose` 读作"把外部权威算出的位姿施加到本宿主"，而 `setPosition` 读作"设置本宿主的位置"——后者暗示宿主自己有位置状态可以独立设置，与"位姿权威在 KCC"这一事实相反。
- 与本仓库既有词汇一致：位姿或运动产出的落地一律用 `apply`（`common/control/MechaControl.java#applyFacing`、`#applyMoveIntent`、`#applyLogicOutputToKcc`）。

参数名 `capsuleCenter` 同样是有意的：它让每个调用点都自带"这不是脚底坐标"的提示。

## 四、明确不进接口的成员

`docs/总体设计文档.md` §2.1 给出的 `IArmsHost` 是伪代码，其中若干方法在落地时会与既有机制冲突或重复。逐条记录不纳入的理由，以免后来者重新引入。

| 伪代码成员 | 处理 | 理由 |
| --- | --- | --- |
| `Vec3 getPosition()` / `void setPosition(Vec3)` | 不纳入 | 见 §三：`setPos(Vec3)` 是 `final`、`getPosition(float)` 是 `final` 且名字被占用；只读位置由 `net.minecraft.world.entity.Entity#position` 提供。位姿写入改由 `applyPose` 承担 |
| `Vec3 getVelocity()` / `void setVelocity(Vec3)` | 不纳入 | `net.minecraft.world.entity.Entity#getDeltaMovement` 与 `net.minecraft.world.entity.Entity#setDeltaMovement` 已提供同一语义；写入改由 `applyVelocity` 承担 |
| `void applyDamage(float amount, DamageSource source)` | 不纳入 | 伤害落地走 `BallisticsFramework` 的 `api/BFDamageApi.java#deliverTo`：它把承载者压为协议上下文栈顶后调用**原版 `hurt`**，因此无敌帧、原版护甲、附魔、荆棘反伤与伤害事件全部保留。另开一个"直接施加伤害"的入口必然要自己走一遍生命值写入，绕过上述语义，而这正是 `docs/宿主接入与伤害管线设计.md` §4.4 反对的做法 |
| `AttributeModifierManager getAttributeModifierManager()` | 不纳入 | `AttributeModifierManager` 在 NeoForge 中不存在。原版属性修饰是 `net.minecraft.world.entity.LivingEntity#getAttribute` 返回的 `net.minecraft.world.entity.ai.attributes.AttributeInstance` 上的 `addTransientModifier` / `addPermanentModifier`，或 `net.minecraft.world.entity.ai.attributes.AttributeMap`。需要时直接取原版 API，不必经宿主接口包一层 |
| `MoveInputSignal consumeMoveInput()` / `RegularInputSignal consumeRegularInput()` | 不纳入 | 这两个类型属兄弟仓库 `Machine-Max` 的信号总线体系（`common/mech/signal/MoveInputSignal.java`、`RegularInputSignal.java`），服务"可驾驶机甲"产品线。玩家机娘的输入路径只有一条：客户端载荷上行 → 服务端合并环境 → 写入条件快照（`common/MechaInputHandler.java#apply`、`#merge`）。再开一个宿主级输入拉取入口会造成同一份输入有两个消费者 |
| `CompoundTag save()` / `void load(CompoundTag)` | 不纳入 | 绑定关系的持久化形态未定（`docs/宿主接入与伤害管线设计.md` §八 第 10 项）。在形态定下之前不设签名 |

**一条判断原则**：接口只承载"换一种宿主形态就需要另一份实现"的成员。凡是原版已经提供、或由别的通道唯一承担的，纳入接口只会多一层转发并让"谁负责"变模糊。

## 五、玩家宿主的实现形态

玩家宿主经 Mixin 实现：`@Mixin(Player.class)` 的声明上 `implements IArmsHost`，各方法体放在该 Mixin 类内。同一个 Mixin 还承担 `BallisticsFramework` 的 `api/BFHitResolver.java#resolveHit` 实现，理由与接线方式见 `docs/宿主接入与伤害管线设计.md` §3.1、§4.7。

### 5.1 字段与方法命名

| 成员 | 建议名 | 说明 |
| --- | --- | --- |
| 绑定字段 | `armsCore$controlledCore` | 类型 `@Nullable ArmsCore`，`volatile`（见 §5.3）。`armsCore$` 前缀避免与其它模组的注入成员撞名 |
| 接口方法 | `getHostEntity` / `getControlledArmsCore` / `setControlledArmsCore` / `applyPose` / `applyVelocity` | 直接实现接口方法，无需改名——这五个名字都不与原版 `Player`、`LivingEntity`、`Entity` 的成员冲突 |

**调用点必须写成经接口的形态。** Mixin 的注入成员在编译期不存在于目标类上，因此
`player.setControlledArmsCore(...)` 这类直接调用**编译不过**，必须写
`((IArmsHost) player).setControlledArmsCore(...)` 或 `player instanceof IArmsHost host` 后经
`host` 调用；`common/ArmsCore.java#bindHostOf` 是这一形态的便捷封装。
这是 Mixin 的固有代价而不是配置问题，与 §三 的命名约束无关。

**不要给接口方法加 `@Unique`。** `@Unique` 让 Mixin 把该成员当作"本模组的私有成员"处理并在重命名时改写它，而这里的方法必须是目标类上真实可调用的接口实现——调用方会写 `((IArmsHost) player).applyPose(...)`，方法被重命名或移除会让该调用点失去目标。字段用 `@Unique` 或 `*$` 前缀是为避免与其它模组的注入成员撞名，方法因来自本模组的独立接口而没有这个风险。

### 5.2 方法体

- `getHostEntity()` 返回 `this`。
- `applyPose(capsuleCenter, yRot, yHeadRot)` 写位置。参数是**胶囊中心**，而实体位置字段的语义是包围盒底面，因此实现必须先换算：`setPos(capsuleCenter.x, capsuleCenter.y − MechaBodyPreset.HALF_TOTAL, capsuleCenter.z)`（§2.1）。玩家实体的包围盒底面与胶囊底面因此对齐，**不**直接把 `capsuleCenter` 写进去。
- **`yRot` / `yHeadRot` 不写。** 朝向的权威在客户端：视野偏航由客户端上行，服务端 `MechaControl#applyFacing` 把它绝对赋值给 KCC 的 `currentYaw`，`common/ArmsCore.java#DATA_YAW` 只是这个值的下行回显（消费者是渲染路径）。若把它写回宿主实体的 `yRot`，而客户端下一次上行读的又正是这个字段（`client/ARMSClient.java#collectAndSend`），两点之间就构成「本机视角 → 上行 → 服务端 → 下行 → 本机视角」的滞后反馈环，表现为**视角持续抖动**。位置没有这个问题：客户端上行的位置被服务端采纳后立即被本轮 KCC 的产物覆盖，是单向下行。
- `applyVelocity(velocity, physicsStepSeconds)` 写 `deltaMovement`，**必须先做 §2.1 的两条换算**（水平 `× 20 × physicsStepSeconds`、垂直 `÷ 20`）：入参是 KCC 的原生单位，而 `deltaMovement` 三个分量统一是每 tick 位移，直接透传会同时错两次。`physicsStepSeconds` 由调用方传入而不是在宿主里写死，因为服务端 100 Hz 与客户端 60 Hz 的因子不同。`noPhysics` 为真时该值不产生实际位移，作用只是让外部查询（动画、其它模组、调试）看到 KCC 的真实速度。
- **位置回写是过渡态。** `docs/宿主位置权威与位移摄入设计.md` §九 第 4 步会删掉服务端每 tick 的位置回写，只留速度那一笔：位置由客户端上报与 §5 的位移摄入通道维护。理由是每 tick 的位置回写会覆盖同一 tick 的外部位移，使控制器不知道 `/tp` 一类的写入发生过。
- 位置回写后调用 `net.minecraft.world.entity.Entity#resetFallDistance`——实体被外部搬动时原版会按位置差累计坠落距离，不重置会让玩家持续受到坠落伤害。位置回写移除之后，这一职责要重新指派：当前它是服务端唯一的坠距重置点（`common/PlayerHostEvents.java#onPlayerTickPost` 走的是 `Player#tick` 末尾的玩家 tick 事件，服务端玩家不在该路径上），可选落点是对账用的 `net.minecraft.server.network.ServerGamePacketListenerImpl#handleMovePlayer` 采纳分支。
- 绑定字段的每一次变化都要重置输入状态，否则状态机会卡在上一帧（例如 `jumpPressed` 永久为真，`docs/ArmsCore双端权威与网络同步实现计划.md` R11）。`common/MechaInputHandler.java#resetInput` 已提供该动作，绑定实现调用它即可。
- `setControlledArmsCore` 是绑定关系的**唯一入口**，两个方向的一致性由它负责，三条换绑路径都要覆盖：写入新值前解除本宿主此前承载的装配体；新装配体此前承载于别的宿主时，先解除那一侧的绑定；同一装配体重复绑定直接返回，连输入都不重置——重置会让正在进行的跳跃助推窗口凭空终止。单宿主唯一性与「同一装配体换宿主」都收敛在这一处，调用方只需回答「这个宿主现在承载谁」。
- 绑定期间授予飞行许可，解除时收回，并调用 `net.minecraft.server.level.ServerPlayer#onUpdateAbilities` 同步给客户端：`docs/宿主接入与伤害管线设计.md` §5.2 末段的「飞行过久」检测在 `noPhysics` 为真时不再有脚下碰撞支撑，悬停与滑翔会被踢。**用 `NeoForgeMod#CREATIVE_FLIGHT` 属性上的修饰符，不要直写 `net.minecraft.world.entity.player.Abilities#mayfly`**——该字段已被 NeoForge 标记为 `@Deprecated` 并明确劝阻直写，许可的正确判据是 `IPlayerExtension#mayFly`（游戏模式或属性值大于 0 二者之一）。修饰符 id 固定（`arms_core:mecha_flight`），因此重复绑定不叠加、收回只撤掉自己那一个，不会覆盖别的模组或游戏模式给出的许可。

**只有服务端路径会走这些方法体。** 客户端玩家同样实现本接口且绑定字段填的是客户端实例，但客户端不持有 KCC（§5.4），因此 `applyPose` / `applyVelocity` 在客户端不会被调用。客户端侧"玩家实体跟着机体走"由另一条路径承担：客户端按同步来的 `DATA_POS` 写自己的实体位置，细节见 `docs/宿主接入与伤害管线设计.md` §5.3。

### 5.3 线程

**绑定字段只在主线程读写。** 写入点是绑定建立与解除（`PlayerEvent` 一侧）；读取点是伤害解析、位姿回写、输入校验与命令，全部在主线程相位，物理线程不读它。声明为 `volatile` 是为了让渲染路径能读到一个完整引用（与 `common/ArmsCore.java#logicState` 同一模式：不可变引用经 `volatile` 安全发布），不是为跨线程写。

`ArmsCore` 侧的宿主引用（§6.1）共用同一条纪律：只在主线程读写。宿主实体的全部已知用途——位姿回写、伤害解析的包围盒、环境字段来源——都在主线程相位，物理线程不需要它。

### 5.4 两端都有这个字段，但只有服务端会写位姿

`net.minecraft.world.entity.player.Player` 在客户端同样存在，因此客户端玩家也实现 `IArmsHost`，其绑定字段填的是客户端实例。区别在于：**客户端不持有 KCC**（`common/ArmsCore.java#newClientInstance` 构造的实例不创建 KCC），因此 `applyPose` / `applyVelocity` 在客户端永远不会被调用；宿主实现的这两个方法体在客户端不会执行。

客户端字段的填法：创建包 `network/payload/ArmsCoreCreatePayload.java` 携带 `hostEntityId`，客户端在**它与本地玩家实体 id 相等时**才把客户端 `ArmsCore` 实例填进该字段。这个比对不可省——创建包按维度广播，同一个 `hostEntityId` 会被维度里每个客户端收到，漏掉比对会让每个玩家都把自己绑定到同一个装配体。

## 六、与绑定关系存储的关系

这条关系有两个方向，各有自己的存储：

- 宿主 → 装配体：宿主实体上的绑定字段，经 `getControlledArmsCore()` 读取。
- 装配体 → 宿主：`ArmsCore` 持有的宿主引用，见 §6.1。

这与 `docs/ArmsCore双端权威与网络同步实现计划.md` 阶段 4.2 所述的"`ArmsCore` 持有宿主引用"一致。两处存储能并存而不打架，靠的不是"只存一份"，而是**装配体不跨宿主实体存活**：宿主实体对象被替换或退场的每一个时刻，装配体都在同一处逻辑里被处置（`docs/宿主接入与伤害管线设计.md` §3.1.3 的规则表），因此不存在"装配体活着、而它持有的宿主实体已经作废"的常态。

### 6.1 `ArmsCore` 持有的宿主引用

`ArmsCore` 需要这条方向的原因是位姿回写：它由 `common/ArmsCoreServerEvents.java#syncToClients` 逐个 `ArmsCore` 发起，那里拿不到宿主实体，而回写要经 `IArmsHost#applyPose` 与 `IArmsHost#applyVelocity` 落到实体上。

| 项 | 取值 |
| --- | --- |
| 类型 | `IArmsHost`，可空（`null` 表示未绑定） |
| 读取入口 | `common/ArmsCore.java#getHost` |
| 写入入口 | `common/ArmsCore.java#setHost`，只由 `IArmsHost#setControlledArmsCore` 的实现调用；直接调它会让宿主一侧仍指着旧装配体 |
| 写入点 | 绑定建立时运行时注入；另有同维度重生时的一次重设（§6.2 的表）。注入不能在构造器里做：`common/ArmsCore.java` 的构造签名是 `ArmsCore(Level, UUID)`，而创建流程（`common/command/ArmsCoreDebugCommand.java#spawn`）先构造装配体、再投递入世任务 |
| 线程 | 只在主线程读写，与绑定字段同一纪律（§5.3） |

**可空是常态，`null` 不是错误状态。** 装配体在"已注册、尚未绑定宿主"这段区间里合法存在，客户端实例则永远没有宿主。三条使用路径的空值行为各自定义在 `docs/宿主接入与伤害管线设计.md` §3.1.2 的规则表里（位姿回写跳过两步但照常写包、伤害解析返回 `null`、环境字段与控制权校验的调用方本身持有判据所以拿不到 `null`），实现时应逐条对齐该表。反向兜底——引用为 `null` 时去查"哪个玩家在控制这个装配体"——是禁止的：那个方向的信息正是 §6.3 要删除的临时表。

**为什么是引用而不是 `UUID`。** 一个自然的替代是存 `UUID`、用时经 `net.minecraft.server.players.PlayerList#getPlayer` 解析，好处是重生会 `new ServerPlayer` 而新实体登记在同一个 `UUID` 下，索引不需要维护。但那条优势只有在"装配体跨断线存活"的前提下才值钱——而在本设计里断线就注销装配体（§6.2），跨维度也是重建，因此装配体的存活区间与宿主实体对象的存活区间几乎重合：

| 事件 | 宿主实体对象 | 装配体 | 引用要不要动 |
| --- | --- | --- | --- |
| 登录 | 新建 | 无 | — |
| 断线 | 退场 | 注销 | — |
| 换维度 | 不变 | 注销后在新维度新建 | — |
| 跨维度重生 | 新建 | 注销后新建 | — |
| 同维度重生 | 新建 | 保留 | **要重设一次** |

真正需要重设的只有最后一行，而那一行本来就要在 `net.neoforged.neoforge.event.entity.player.PlayerEvent#Clone` 里做"残骸 / 重建 / 保留"的判定，顺手就把引用改指新实体。维护点既然只有这一处，引用比 `UUID` 少一次查表、少一处"解析失败"分支，也更贴 `IArmsHost` 的语义（`applyPose` 直接经接口调用，`ArmsCore` 里不出现宿主类型分支）。

**一处要留意的是静默失效**：同维度重生那一格若漏了重设，引用会指向已被移出世界的旧实体，`applyPose` 照写不报错。在 `PlayerEvent#Clone` 的处理里设完之后补一条断言或日志即可，不需要结构上的防御。

### 6.2 不能用"宿主被销毁就自毁"替代

另一种思路是让 `ArmsCore` 定期自查、发现宿主没了就自杀。问题在于 `net.minecraft.world.entity.Entity#isRemoved` 回答的是"这个实体当前有没有被移出世界"，而不是"这个宿主还有没有未来"——它至少有五种为真的触发点，其中三种并不代表宿主消失：

| 触发 | `isRemoved()` | 宿主是否真的没了 |
| --- | --- | --- |
| 断线（`net.minecraft.server.players.PlayerList#remove`） | 真，不撤销 | 是 |
| 重生（`net.minecraft.server.players.PlayerList#respawn` 移出旧对象） | 真，不撤销 | 是（换了对象） |
| 换维度（`net.minecraft.server.level.ServerPlayer#changeDimension` 移出后立即 `revive`） | 真 → 假，同一对象 | **否** |
| 末地通关（`net.minecraft.world.level.block.EndPortalBlock#entityInside` → `net.minecraft.server.level.ServerPlayer#showEndCredits`，该路径不 `revive`，玩家停在制作人员名单界面直到点重生） | 真，持续很久 | **否** |
| 死亡本身（`net.minecraft.server.level.ServerPlayer#die` 不移除实体） | 假，只有 `isDeadOrDying` 为真 | 否 |

`net.minecraft.world.entity.Entity#isRemoved` 与 `net.minecraft.world.entity.Entity#setRemoved` 都是 `final`，无法在实体层把判据解释得更聪明；而且自查必须落在主线程相位——`common/MechaCoreRegistry.java#removeServer` 会广播移除包，物理线程发包是硬违规——所以 `common/ArmsCore.java#prePhysicsTick` 这个唯一的每步心跳不能用来做这件事。

**结论是用事件而不是轮询**：`net.neoforged.neoforge.event.entity.player.PlayerEvent#Clone`（重生）、`net.neoforged.neoforge.event.entity.player.PlayerEvent#PlayerChangedDimensionEvent`（跨维度搬迁）、`net.neoforged.neoforge.event.entity.player.PlayerEvent#PlayerLoggedOutEvent`（断线）。事件给的是**原因**，而 `isRemoved()` 只给**现象**。完整规则表见 `docs/宿主接入与伤害管线设计.md` §3.1.3。

### 6.3 控制权表已由绑定字段取代

控制权校验曾经由 `common/MechaInputHandler.java` 里一张「装配体 UUID → 控制者玩家 UUID」的内存表承担，方向与 §六 的宿主 → 装配体相反。该表已删除，三处用途全部由绑定字段承担：

| 用途 | 入口 | 现在的判据 |
| --- | --- | --- |
| 控制权校验 | `common/MechaInputHandler.java#isController` | 发起者实体的绑定字段是不是这个装配体 |
| 取控制者实体查环境字段 | `common/MechaInputHandler.java#merge` | 按发起者 UUID 经 `net.minecraft.server.players.PlayerList#getPlayer` 解析服务端玩家实体；环境值仍由服务端查询，不采信客户端上行 |
| 断线 / 换维度释放控制权 | `common/ArmsCoreServerEvents.java#releaseCoresControlledBy` | 直接读该实体自己的绑定字段，不再遍历注册表 |

删表的同时移除了两个只服务于它的成员：按装配体查控制者的访问器，以及按装配体写入、移除控制者的设置器——控制权不再是独立的一份状态，而是绑定关系的派生读法。

## 七、实施顺序

1. 新建 `common/IArmsHost.java`（§二 的方法集）。
2. 新建 `mixin/PlayerHostMixin.java`：`@Mixin(Player.class) implements IArmsHost`，注入 `armsCore$controlledCore` 字段与五个方法体（§5.2）。同步把该类写入 `src/main/resources/arms_core.mixins.json` 的 `mixins` 数组；该文件的 `package` 已声明为 `io.github.sweetzonzi.arms_core.mixin`，数组本身为空。
3. `common/MechaInputHandler.java` 的控制权表并入绑定字段（§6.3 的表）。
4. `ArmsCore` 持有 §6.1 的宿主引用：绑定建立时运行时注入，另在同维度重生的处置点上重设（§6.1 的表）。
5. 创建包开始携带真实 `hostEntityId`；客户端**仅在 `hostEntityId` 等于本地玩家 id 时**填自己的字段（§5.4、`docs/宿主接入与伤害管线设计.md` §3.1）。`network/payload/ArmsCoreCreatePayload.java` 当前由调用点传入 `ArmsCoreCreatePayload.NO_HOST_ENTITY`（`common/command/ArmsCoreDebugCommand.java` 是唯一创建路径），这一步同时把该常量从默认值降为"无实体宿主"的显式选项。
6. 位置与朝向回写接入 `common/ArmsCoreServerEvents.java#syncToClients`（与该函数的同步写包同相位），位置按 §5.2 换算到包围盒底面；`noPhysics` 与坠距重置另见 `docs/宿主接入与伤害管线设计.md` §5.2 规定的相位。

第 1 至 5 步不依赖零件装配，也不依赖 `BallisticsFramework` 的投递入口，可独立编译验收。

## 八、待决

| # | 项 | 说明 |
| --- | --- | --- |
| 1 | 持久化 | 本次落地不做正反序列化：断线只注销装配体并解绑，重启与重连后宿主回到人类形态。将来接入持久化路径时，形态与 `docs/总体设计文档.md` §2.1 的"宿主 NBT + Capability 序列化"对齐 |
| 2 | 装配体生命周期 | **规则表见 `docs/宿主接入与伤害管线设计.md` §3.1.3。** 三档：死亡且掉落物品 → 残骸；死亡但未掉落物品且同维度重生 → 保留机体；其余（跨维度重生、跨维度搬迁、断线、登录）→ 注销，按需在新维度新建。判定集中在 `net.neoforged.neoforge.event.entity.player.PlayerEvent#Clone` 一处；为什么不能用轮询见 §6.2 |
| 3 | 非玩家宿主的落地 | Doll 实体在其类定义中直接实现本接口（不经 Mixin）；AI 敌人同理。它们的 `applyPose` 实现与玩家不同（无客户端预测、无 `noPhysics` 需求），但接口契约相同 |
| 4 | `IArmsHost` 是否并入 `BFHitResolver` | 可以合并成"实现一个接口同时获得两种身份"，但 `resolveHit` 是协议的伤害解析契约、只对与伤害相关的宿主有意义，与"承载机娘素体"是两件事。当前分开实现，代价只是 Mixin 声明上多写一个接口名 |
| 5 | 非玩家宿主的生命周期 | §6.2 的规则表是按玩家实体的事件写的。Doll / AI 敌人没有重生与换维度，退场即销毁，因此它们的分支只有"创建 / 销毁"两条，`ArmsCore` 的宿主引用在同一处注入与清空即可 |
