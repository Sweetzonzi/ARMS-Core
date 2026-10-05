package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.state.MechaLogicStateMachine;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import lombok.Getter;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import lombok.Setter;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;

/**
 * 机甲运动控制编排器核心。
 * <p>
 * MechaControl 是 ARMS-Core 角色运动系统的唯一编排器，聚合以下职责：
 * <ol>
 *   <li><b>输入消费</b> — 接收 Holder 写入的 {@link MechaConditionSnapshot} 和 {@link MechaEvent}，
 *       连同 KCC 内部采集的物理状态，汇入统一的 {@code StateVariableContainer}</li>
 *   <li><b>状态机驱动</b> — 管理逻辑层状态机树（posture + gait/vertical 子机），每物理帧推进状态转移</li>
 *   <li><b>物理桥接</b> — 将玩家输入转发给 {@link MechaCharacter}（KCC 封装），
 *       在 {@link #onPhysicsStep} 中调用 {@code kcc.prePhysicsTick(dt)} 完成行走/跳跃物理积分</li>
 *   <li><b>旁路观测</b> — 默认 {@link #bypassObservation} 开启：状态机照常运行并产出变量，
 *       但暂不应用 CAN_MOVE / CAN_JUMP 门控 KCC，保证旧 KCC 行为无回归；置 false 后启用反向控制</li>
 * </ol>
 * <p>
 * 线程模型：
 * <ul>
 *   <li>主线程（或 Holder 采集线程）：{@link #applyConditionSnapshot} 发布不可变快照（volatile 引用）、
 *       {@link #postEvent} 追加离散事件（原子 copy-on-write）</li>
 *   <li>物理线程：{@link #onPhysicsStep} 帧首原子取走整批事件（{@code pendingEvents}），
 *       之后的所有状态机读写均在物理线程内完成，无需再加锁</li>
 * </ul>
 * <p>
 * 不依赖 Machine-Max 信号总线（ISignalBus）。输入由 Holder 直接传入。
 * 子系统合力叠加（推进器等）通过 {@code addSubsystemForce} 汇聚后施加到 KCC。
 *
 * @author Sweetzonzi
 */
@Getter
public class MechaControl {

    // ==========================================
    // 常量
    // ==========================================

    /**
     * 初始能量值。
     * <p>
     * sprint 等能量门控条件（{@code ENERGY > 0}）在运行时依赖此值；
     * 当前阶段仅初始化满值，消耗/恢复系统后续实现。
     */
    public static final float INITIAL_ENERGY = 100f;

    /**
     * 闪避冲量 {@code I_dodge} (N·s) —— 一次闪避由水平推进器提供的冲量。
     * <p>
     * <b>闪避以冲量衡量，不以速度衡量。</b> 与跳跃同构（{@link MechaJumpAttr}）：两者都是瞬时过程，
     * 给定的都是冲量，落地时由控制器换算成速度增量
     * {@code Δv = I / m}（{@code m} 见 {@code MechaCharacter.java#getControllerMass}）。因此同一个冲量在
     * 越重的机体上得到的 Δv 越小——质量是「用同样的推进器换多少速度」的比例系数，而不是一个与机体
     * 无关的常数。
     * <p>
     * 素体（70 kg）下 {@code Δv = 840 / 70 = 12 m/s}，即两倍地面顶速 {@code v_ref}。它不是「位移通道的
     * 初速度」，也不是一段位移：赋值把水平速度改写成 {@code (max(v·u, 0) + Δv)·u}（{@code u} 是闪避轴），
     * 此后由通用力模型接管，因此单次闪避的<b>位移是派生量</b>——地面无输入时由制动
     * {@code μ·g = 9.81 m/s²} 削、地面按住输入时只被内阻 {@code c₀·g ≈ 1.9 m/s²} 削、空中没有摩擦
     * 因此原样保留。调参调的是本值，不是距离。模型见 `docs/角色控制器-行走物理设计.md` §3.8。
     * <p>
     * 取值集中在这里，待素体定义（阶段 4.6）落地后移交到素体参数表。
     */
    public static final float DODGE_IMPULSE = 840f;

    /** 闪避无敌时长 (s) */
    public static final float DODGE_INVULNERABLE_SECONDS = 0.4f;

    // ==========================================
    // 持有关系
    // ==========================================

    /** 持有者引用，构造时绑定 */
    @Getter
    private final MechaControlHolder holder;

    /** KCC 运动学胶囊控制器（行走物理 + 跳跃 + 碰撞 sweep） */
    @Getter
    private final MechaCharacter kcc;

    /** 逻辑层共享变量容器；父状态机和全部活跃子机共用。 */
    private final StateVariableContainer variables;

    /** 逻辑层共享 GameplayTag 容器。 */
    private final GameplayTagContainer tags;

    /** posture + gait/vertical 子机组成的逻辑状态机树。 */
    @Getter
    private final MechaLogicStateMachine logicStateMachine;

    /** 采集 KCC 速度时复用，避免物理帧内分配临时向量。 */
    private final Vector3f stateVelocity = new Vector3f();

    // ==========================================
    // 输入缓冲（跨线程发布）
    // ==========================================

    /**
     * 本帧条件快照（主线程/采集线程发布，物理线程帧首读取）。
     * <p>
     * record 的 final 字段 + volatile 引用保证安全发布，不会撕裂。
     * <p>
     * -- GETTER --
     * 当前快照（不可变 record，可安全跨线程读取）。
     */
    private volatile MechaConditionSnapshot conditionSnapshot;

    /**
     * 事件投递缓冲（主线程追加，物理线程帧首原子取走整批）。
     * <p>
     * postEvent 采用 copy-on-write 的 {@code getAndUpdate}，与帧首
     * {@code getAndSet(空集)} 交换配合：任意交错下事件既不丢失也不重复，
     * 只会归属到本帧或下一帧。
     */
    private final AtomicReference<Set<MechaEvent>> pendingEventBuffer =
            new AtomicReference<>(EnumSet.noneOf(MechaEvent.class));

    /**
     * 本帧事件批（物理线程帧内快照，仅物理线程访问）。
     * <p>
     * -- GETTER --
     * 本帧待处理事件（帧内快照，只读；跨帧请勿持有）。
     */
    private Set<MechaEvent> pendingEvents = EnumSet.noneOf(MechaEvent.class);

    /** 上一帧是否处于 dodge，用于检出「刚进入 dodge」的那一帧（物理线程独占）。 */
    private boolean dodgingLastFrame;

    // ==========================================
    // 模式开关与调试
    // ==========================================

    /**
     * 旁路观测模式：状态机照常运行并产出变量，但不应用 CAN_MOVE / CAN_JUMP 门控。
     * <p>
     * 默认开启——先保证原 KCC 行走/跳跃无回归，观测稳定后再置 false 逐项启用反向控制。
     * -- GETTER --
     * 当前是否处于旁路观测模式。
     * -- SETTER --
     *  切换旁路观测模式。
     *  <p>
     */
    @Setter
    private volatile boolean bypassObservation = true;

    /** 调试日志开关（仅状态变化时打印一行，不每帧打印） */
    @Setter
    private volatile boolean debugLog = false;

    /** 状态变化日志缓存（物理线程独占） */
    private Posture lastLoggedPosture;
    private Gait lastLoggedGait;
    private Vertical lastLoggedVertical;

    // ==========================================
    // 状态机与动画（待 Spark-Core 基础设施）
    // ==========================================

    // TODO: MultiAnimStateMachine — 中央状态机（locomotion / hold / swing 等），见分层控制器与状态机设计 §6
    // TODO: AnimStateMachine — 本地状态机（武器开火等），挂在各 SubPart 的 AnimController 上
    // TODO: MechaMolangContext — MoLang ctrl.* 绑定上下文，见分层控制器与状态机设计 §9

    /** 中央状态机映射：控制器名 → MultiAnimStateMachine */
    // TODO: Map<String, MultiAnimStateMachine> centralMachines;

    /** 全体可动画 SubPart 列表（从根 SubPart 追溯 partNet 获取），按渲染优先级排列 */
    // TODO: List<IAnimatable<SubPart>> allAnimatables;

    /** 包含 body_root 骨骼的躯干 SubPart（根骨骼提供者，用于提取 animRootDelta） */
    // TODO: IAnimatable<SubPart> chassisAnimatable;

    // ==========================================
    // MoLang（待实现）
    // ==========================================

    /** MoLang 上下文，通过接口组合获得 ctrl.* 绑定 */
    // TODO: MechaMolangContext molangContext;

    // ==========================================
    // 构造
    // ==========================================

    /**
     * @param holder 持有者（ArmsCore 或 MechControllerSubsystem），不可为 null
     * @param kcc    已初始化的运动学角色控制器
     */
    public MechaControl(MechaControlHolder holder, MechaCharacter kcc) {
        this.holder = holder;
        this.kcc = kcc;
        this.conditionSnapshot = MechaConditionSnapshot.EMPTY;
        this.variables = new StateVariableContainer();
        this.tags = new GameplayTagContainer();
        this.logicStateMachine = new MechaLogicStateMachine(variables, tags);
        this.logicStateMachine.reset();
        // 能量初始化为满值，sprint 等能量门控立即可用（消耗/恢复后续实现）
        this.variables.set(ENERGY, INITIAL_ENERGY);
    }

    // ==========================================
    // 输入 API（Holder 通过接口 default 方法调用）
    // ==========================================

    /**
     * 写入本帧条件快照（每帧覆盖）。
     * <p>
     * 由 {@link MechaControlHolder#writeConditionSnapshot(MechaConditionSnapshot)} 委托调用。
     * 快照必须为不可变对象（本实现使用 record）；调用方不得在发布后继续修改。
     *
     * @param snapshot 本帧由 Holder 采集的条件快照
     */
    public void applyConditionSnapshot(MechaConditionSnapshot snapshot) {
        this.conditionSnapshot = snapshot;
    }

    /**
     * 投递离散事件（latch 到下一物理帧消费）。
     * <p>
     * 由 {@link MechaControlHolder#postEvent(MechaEvent)} 委托调用。
     * 线程安全：copy-on-write 追加，重复投递同一事件仅保留一个（Set 去重）。
     *
     * @param event 事件类型
     */
    public void postEvent(MechaEvent event) {
        pendingEventBuffer.getAndUpdate(current -> {
            Set<MechaEvent> next = EnumSet.noneOf(MechaEvent.class);
            next.addAll(current);
            next.add(event);
            return next;
        });
    }

    // ==========================================
    // 子系统合力叠加
    // ==========================================

    // TODO: 当轮子(MotorSubsystem)/推进器(ThrusterSubsystem)/机翼(WingSubsystem)等
    // 子系统在 DRIVE 状态下施加驱动力时，通过此方法汇聚后叠加到 KCC。
    // 设计文档 §7 — DRIVE 状态下轮子/推进器施力于躯干刚体，不经控制器中转。
    // 当前阶段（仅 WALK）暂不需要。

    /**
     * 叠加子系统驱动力（WALK 下忽略，DRIVE 下汇聚后作用于躯干刚体）。
     * <p>
     * 单位：N（世界坐标系）。每物理帧由子系统调用。
     *
     * @param force 子系统合力向量（世界坐标，N）
     */
    public void addSubsystemForce(float forceX, float forceY, float forceZ) {
        // TODO: DRIVE 状态下施加到躯干刚体（WALK 下忽略）
        // 见行走物理设计 §7 — WALK 下轮子不施加驱动力
    }

    // ==========================================
    // 物理步主流程
    // ==========================================

    /**
     * 每物理帧主流程，由 Holder 在物理步中调用。
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>帧首原子取走主线程投递的事件批</li>
     *   <li>汇入快照和 KCC 状态到 StateVariableContainer，并把视野偏航写进 KCC 作为朝向权威
     *       （{@link #applyFacing}，早于一切按朝向解算的量）</li>
     *   <li>广播离散事件并推进逻辑状态机</li>
     *   <li>逻辑状态机在推进完成后合并最终输入许可</li>
     *   <li>按旁路观测/反向控制模式转发玩家输入到 KCC</li>
     *   <li>提取动画根骨骼位移 → kcc.setAnimRootDelta（当前 TODO 空实现）</li>
     *   <li>KCC 物理积分 ({@code kcc.prePhysicsTick(dt)})</li>
     * </ol>
     *
     * @param dt 物理步长 (s)
     */
    public void onPhysicsStep(float dt) {
        frameLogic(dt);

        // —— 5. 注入 MoLang ——
        // TODO: molangContext.setVariables(variables);

        // —— 6. 驱动表现层中央状态机 ——
        // TODO: centralMachines.forEach((name, machine) -> machine.progress(dt));

        // —— 7. 分发事件到本地动画状态机 ——
        // TODO: dispatchEventsToLocalMachines();

        // —— 8. 提取动画根骨骼位移 ——
        extractAnimRootDelta();

        // —— 9. KCC 物理积分（行走力 / 跳跃 / 碰撞 sweep） ——
        kcc.prePhysicsTick(dt);
    }

    /**
     * 帧逻辑推进（不含 KCC 物理积分）。
     * <p>
     * 包内可见以便单元测试在不触碰 jme3 native（rayTest 等）的前提下验证接线：
     * 事件 latch → 变量汇入 → 朝向写入 → 状态机推进 → 输入转发 → 状态变化日志。
     *
     * @param dt 物理步长 (s)
     */
    void frameLogic(float dt) {
        // —— 帧首：原子取走主线程投递的事件批 ——
        pendingEvents = pendingEventBuffer.getAndSet(EnumSet.noneOf(MechaEvent.class));

        // —— 1. 汇入快照与步进前 KCC 状态 ——
        float safeDt = Math.max(dt, 1.0e-6f);
        writeStateInputs(safeDt);

        // —— 1b. 朝向：把视野偏航绝对赋值到 KCC ——
        // 必须早于 applyLogicOutputToKcc（闪避方向）与 forwardInputToKCC（行走方向）：
        // 这两处都按 KCC 本步朝向解算，晚一步就会用上一物理步的角
        applyFacing();

        // —— 2. 事件广播到当前活跃状态树 ——
        broadcastPendingEvents();

        // —— 3. 推进整棵状态树，并由逻辑机合并最终输入许可 ——
        logicStateMachine.progress(safeDt);

        // —— 3b. 逻辑层产出回写到 KCC（速度倍率、胶囊尺寸、闪避）——
        // 必须在 progress 之后：本步产出的 gait / posture 才是本帧要生效的值
        applyLogicOutputToKcc(safeDt);

        // —— 4. 按旁路/反向模式转发输入到 KCC ——
        forwardInputToKCC();

        // —— 状态变化日志 ——
        logStateChanges();
    }

    /**
     * 把逻辑层本帧的产出落到 KCC 上。
     * <p>
     * 在同一个物理步内，本方法之前状态机产出的 {@code POSTURE} / {@code GAIT} /
     * {@code MOVE_SPEED_MODIFIER} 还只是变量容器里的值，KCC 不看它们；本方法就是把它们变成
     * 物理效果的链路，共三项：
     * <ol>
     *   <li><b>速度倍率</b> —— {@code MOVE_SPEED_MODIFIER}（= posture.speedModifier ×
     *       gait.baseSpeedModifier）写进 KCC，作为控制力的缩放系数（稳态速率随之等比缩放，
     *       不是另设一道速度上限；见 `docs/角色控制器-行走物理设计.md` §3.6、§3.8.1）</li>
     *   <li><b>胶囊尺寸</b> —— 按 posture 换碰撞形状；蹲伏 / 卧倒压低轮廓</li>
     *   <li><b>闪避</b> —— 进入 dodge 状态的那一帧解出闪避轴，把水平速度赋值到该轴上（只保留沿轴的
     *       同向分量，再叠加一次 Δv）并开启无敌窗口</li>
     * </ol>
     * 全部在物理线程执行，符合「只允许物理线程触碰 Bullet 对象」的约定。
     * <p>
     * 闪避只做一次赋值，不进位移叠加通道，也不受 {@code MOVE_SPEED_MODIFIER} 影响：倍率缩放的是标准
     * WASD 控制力（{@link MechaCharacter#controlForceScale()}），而闪避是速度矢量赋值，两者量纲不同、
     * 互不干涉。dodge 状态因此不剥夺自主移动能力。
     */
    private void applyLogicOutputToKcc(float dt) {
        // —— ① 速度倍率 ——
        Float speedMod = variables.get(MOVE_SPEED_MODIFIER);
        kcc.setMoveSpeedModifier(speedMod == null ? 1.0f : speedMod);

        // —— ② 胶囊尺寸 ——
        // 未接入。Libbulletjme 的 PhysicsCharacter.setCollisionShape 要求
        // 「the character should not be in any PhysicsSpace while changing shape; the character
        // gets rebuilt on the physics side」（../Libbulletjme/.../objects/PhysicsCharacter.java:342-363，
        // 方法体含 assert !isInWorld()）。在物理空间内直接换形状会让原生侧挂接一个未重建的
        // 碰撞对象：release JVM 不检查断言，原生内存随即损坏，进程以 0xC0000409 中止。
        // 先 removeCollisionObject → setCollisionShape → addCollisionObject 也不行：实测
        // 幽灵体状态被重置（着地检测失效、姿态回落到 air），且仍会在下一次换形状时中止。
        // 因此轮廓姿态（crouch / prone）的碰撞体积目前**没有**实现，见计划文档 §3.12 的登记。
        // Posture posture = variables.get(POSTURE);
        // if (posture != null) kcc.applyPostureShape(posture);

        // —— ③ 闪避 ——
        // 只在「刚进入 dodge」的那一物理步投递一次：dodgingLastFrame 记录上一步是否在闪避，
        // 因此同一次闪避不会逐步重复投递，而 dodge 退出后再次进入又会投递一次。
        Gait gait = variables.get(GAIT);
        boolean dodgingNow = gait == Gait.DODGE;
        if (dodgingNow && !dodgingLastFrame) {
            float[] dir = resolveDodgeDirection();
            kcc.requestDodgeImpulse(dir[0], dir[1], DODGE_IMPULSE,
                    DODGE_INVULNERABLE_SECONDS);
        }
        dodgingLastFrame = dodgingNow;
    }

    /**
     * 解析闪避轴（世界坐标水平单位向量）—— 闪避赋值的目标方向。
     * <p>
     * 有移动输入时沿输入方向，无输入时沿控制器当前朝向（{@link MechaCharacter#getCurrentYaw()}）。
     * 两种情形的朝向都取 KCC 本步的朝向（{@link #applyFacing} 先于本方法执行），
     * 与 {@link MechaCharacter#setMoveIntent} 解行走方向用的是同一个角。
     * 后者保证按住闪避键不放方向键时也能倒地翻滚，而不是原地不动。
     * <p>
     * 本方法不读速度：闪避是把速度矢量赋值到这个轴上，赋值结果的方向因此只由玩家输入决定。
     *
     * @return {@code [dirX, dirZ]}，已归一化
     */
    private float[] resolveDodgeDirection() {
        MechaConditionSnapshot snap = conditionSnapshot;
        float fwd = snap.inputForward();
        float str = snap.inputStrafe();
        float yaw = kcc.getCurrentYaw();
        float sinYaw = (float) Math.sin(yaw);
        float cosYaw = (float) Math.cos(yaw);
        if (fwd * fwd + str * str > 0.001f) {
            float dirX = str * cosYaw - fwd * sinYaw;
            float dirZ = fwd * cosYaw + str * sinYaw;
            float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len > 0.001f) {
                return new float[]{dirX / len, dirZ / len};
            }
        }
        return new float[]{-sinYaw, cosYaw};
    }

    /** 将连续输入、环境状态、事件 latch 和 KCC 状态写入共享变量容器。 */
    private void writeStateInputs(float dt) {
        MechaConditionSnapshot snap = conditionSnapshot;
        boolean hasInput = snap.inputForward() * snap.inputForward()
                + snap.inputStrafe() * snap.inputStrafe() > 0.001f;

        variables.set(StateVariableKeys.INPUT_FORWARD, snap.inputForward());
        variables.set(StateVariableKeys.INPUT_STRAFE, snap.inputStrafe());
        variables.set(StateVariableKeys.IS_SPRINTING, snap.sprintPressed());
        variables.set(StateVariableKeys.IS_DEAD, snap.isDead());
        variables.set(HAS_INPUT, hasInput);
        variables.set(WALK_KEY_DOWN, snap.walkKeyPressed());
        variables.set(IN_WATER, snap.inWater());
        variables.set(IS_SNEAKING, snap.sneaking());

        variables.set(StateVariableKeys.ON_GROUND, kcc.onGround());
        float safeDt = Math.max(dt, 1.0e-6f);
        // 走 getHorizontalVelocity 而不是裸读 KCC：它会扣掉动画根运动那条位移叠加通道，
        // 否则动画驱动的位移会被当成速度，让 SPEED 虚高并把 gait 误推进 drift。
        // 闪避不扣——它赋值的就是真实速度，见 MechaCharacter.java#getHorizontalVelocity
        kcc.getHorizontalVelocity(stateVelocity, safeDt);
        float horizontalSpeed = (float) Math.sqrt(
                stateVelocity.x * stateVelocity.x + stateVelocity.z * stateVelocity.z);
        variables.set(StateVariableKeys.SPEED, horizontalSpeed);
        variables.set(StateVariableKeys.VERTICAL_SPEED, stateVelocity.y);
        variables.set(KCC_JUMP_BOOSTING, kcc.isBoosting());

        variables.set(EVENT_DODGE, pendingEvents.contains(MechaEvent.DODGE));
        variables.set(EVENT_TOGGLE_PRONE, pendingEvents.contains(MechaEvent.TOGGLE_PRONE));
        variables.set(EVENT_TOGGLE_DRIVE, pendingEvents.contains(MechaEvent.TOGGLE_DRIVE));
        variables.set(EVENT_TOGGLE_FLY, pendingEvents.contains(MechaEvent.TOGGLE_FLY));
    }

    /**
     * 广播逻辑层事件。死亡时忽略普通输入事件，让 ragdoll 自动转移拥有绝对优先级。
     */
    private void broadcastPendingEvents() {
        if (conditionSnapshot.isDead()) return;

        for (MechaEvent event : pendingEvents) {
            String eventType = switch (event) {
                case TOGGLE_PRONE -> "prone";
                case TOGGLE_FLY -> "fly";
                case DODGE -> "dodge";
                case STUN -> "stun";
                case MOUNT -> "mount";
                case DISMOUNT -> "dismount";
                default -> null;
            };
            if (eventType != null) {
                logicStateMachine.broadcastEvent(eventType);
            }
        }
    }

    // ==========================================
    // 朝向与输入转发（私有）
    // ==========================================

    /**
     * 把本帧视野偏航写进 KCC，作为控制器朝向的绝对权威
     * （{@link MechaCharacter#setViewYaw} 不累积、不插值，写入即生效）。
     * <p>
     * 调用点在 {@link #frameLogic(float)} 的第 1b 步：早于闪避方向与行走方向的解算，因此
     * 本步内所有按朝向解算的量取的是同一个角。
     * <p>
     * 死亡时不写入。{@code RAGDOLL} 在当前状态机里的唯一入边条件就是 {@code IS_DEAD}
     * （{@code common/control/state/preset/PostureLogicGraphs.java#buildGraph} 里转入 ragdoll 的
     * 六条转移全部以 {@code COND_IS_DEAD} 为条件，且该节点无出口），因此「死亡 / ragdoll 冻结」
     * 由这一个判据覆盖，控制器停在最后一帧朝向。
     * <p>
     * 移动许可（{@code CAN_MOVE}）不影响朝向：被晕住、助推中、卧倒时仍可转身看向别处。
     */
    private void applyFacing() {
        MechaConditionSnapshot snap = conditionSnapshot;
        if (snap.isDead()) return;
        kcc.setViewYaw(snap.viewYaw());
    }

    /**
     * 将快照中的移动/跳跃输入转发到 KCC。
     * <p>
     * 移动意图（前后 / 左右）原样交给 {@link MechaCharacter#setMoveIntent}，由 KCC 按本步朝向
     * 转成世界方向，本层不做三角变换。跳跃的"按住"取自快照（连续量），"松开"取自本帧事件批
     * （{@link MechaEvent#JUMP_RELEASE} 的单帧边沿），因此同一次松键只被 latch 一次，
     * 不随快照被反复读取。
     * 旁路观测模式（默认）：原样透传，等价于逻辑层完全不存在时的 KCC 行为；
     * 反向控制模式：按逻辑层合并许可（CAN_MOVE / CAN_JUMP）门控。
     */
    private void forwardInputToKCC() {
        MechaConditionSnapshot snap = this.conditionSnapshot;
        float fwd = snap.inputForward();
        float str = snap.inputStrafe();
        boolean hasInput = (fwd * fwd + str * str) > 0.001f;
        boolean jumpRelease = pendingEvents.contains(MechaEvent.JUMP_RELEASE);

        if (bypassObservation) {
            // 旁路观测：原样透传（无输入则置零，触发 KCC §3.9 无输入制动）
            applyMoveIntent(fwd, str, hasInput);
            kcc.setJumpInput(snap.jumpPressed(), jumpRelease);
        } else {
            // 反向控制：CAN_MOVE / CAN_JUMP 门控
            applyMoveIntent(fwd, str, hasInput && variables.get(CAN_MOVE));

            // CAN_JUMP 只限制开始跳跃；已经开始助推后仍须透传 held/released 才能正常终止窗口。
            // 离地后 posture 切 air、CAN_JUMP 即为假，靠「正在助推」这条旁路让窗口内的 held 继续透传
            boolean boosting = kcc.isBoosting();
            boolean jumpHeld = snap.jumpPressed() && (variables.get(CAN_JUMP) || boosting);
            boolean jumpReleased = jumpRelease && boosting;
            kcc.setJumpInput(jumpHeld, jumpReleased);
        }
    }

    /**
     * 把移动意图写入 KCC。
     * <p>
     * 参数是玩家视角相对的原始量（正 = 前进 / 左移），本方法不做坐标系变换：行走方向的
     * 「意图 → 世界」变换发生在 {@link MechaCharacter#setMoveIntent} 内，用的是本步
     * {@link #applyFacing} 写下的 {@code currentYaw}。禁止移动时置零（触发 KCC 无输入制动）。
     *
     * @param fwd     前后意图，[-1, 1]，正 = 前进
     * @param str     左右意图，[-1, 1]，正 = 左移
     * @param allowed 本步是否允许移动
     */
    private void applyMoveIntent(float fwd, float str, boolean allowed) {
        if (!allowed) {
            kcc.setMoveIntent(0f, 0f);
            return;
        }
        kcc.setMoveIntent(fwd, str);
    }

    // ==========================================
    // 动画根骨骼位移提取（私有）
    // ==========================================

    /**
     * 从躯干 SubPart 的 body_root 骨骼提取帧间位移与旋转，写入 KCC 供多通道合成。
     * <p>
     * 设计依据：行走物理设计 §1.2（多通道合成）和 §10.3（叠加公式）。
     * body_root 骨骼通过 New6Dof 世界锚点约束与 KCC 链接。
     * <p>
     * 提取内容：
     * <ul>
     *   <li><b>位移</b> (dx, dy, dz, m/tick) — 世界坐标帧间差，直接与 KCC 物理位移叠加</li>
     *   <li><b>Y 轴旋转</b> (deltaYaw, rad/tick) — 面向帧间差，用于转身斩/回旋踢/
     *       idle 微晃等动画驱动面向变化。KCC 的 angularFactor(0,1,0) 保证只接收 Y 旋转</li>
     * </ul>
     */
    private void extractAnimRootDelta() {
        // TODO:
        // 1. 从 chassisAnimatable 获取 body_root 骨骼 Pose
        //    Pose pose = chassisAnimatable.modelController.model.pose;
        //    BonePose rootPose = pose.getBonePose("body_root");
        //
        // 2. 帧间位移（世界坐标，m/tick）
        //    float dx = rootPose.worldPos.x - rootPose.prevWorldPos.x;
        //    float dy = rootPose.worldPos.y - rootPose.prevWorldPos.y;
        //    float dz = rootPose.worldPos.z - rootPose.prevWorldPos.z;
        //    kcc.setAnimRootDelta(dx, dy, dz);
        //
        // 3. 帧间 Y 轴旋转（rad/tick）— 从世界旋转四元数提取 yaw 的帧间差
        //    float[] angles = new float[3];
        //    float[] prevAngles = new float[3];
        //    rootPose.worldRot.toAngles(angles);        // [yaw, pitch, roll]
        //    rootPose.prevWorldRot.toAngles(prevAngles);
        //    float deltaYaw = angles[0] - prevAngles[0];
        //    // 处理 ±π 环绕：若 delta > π 则 -2π，若 delta < -π 则 +2π
        //    if (deltaYaw > Math.PI) deltaYaw -= 2 * Math.PI;
        //    else if (deltaYaw < -Math.PI) deltaYaw += 2 * Math.PI;
        //    kcc.setAnimRootYawDelta(deltaYaw);
        //
        // 4. 更新上一帧位姿
        //    rootPose.prevWorldPos.set(rootPose.worldPos);
        //    rootPose.prevWorldRot.set(rootPose.worldRot);
    }

    // ==========================================
    // 事件分发到本地状态机（私有）
    // ==========================================

    /**
     * 将 pendingEvents 中的事件分发给对应 SubPart 的本地 AnimStateMachine。
     * <p>
     * 事件映射：
     * <ul>
     *   <li>ATTACK_PRIMARY → 主手武器 SubPart 的 "fire" 状态机 triggerEvent("fire")</li>
     *   <li>ATTACK_SECONDARY → 副手武器同上</li>
     *   <li>USE_ITEM → 主手 SubPart 的 "use" 状态机 triggerEvent("use")</li>
     *   <li>HURT → 全体 SubPart triggerEvent("hurt")</li>
     *   <li>MOUNT/DISMOUNT → passenger 中央状态机处理</li>
     * </ul>
     */
    private void dispatchEventsToLocalMachines() {
        // TODO:
        // for (MechaEvent event : pendingEvents) {
        //     switch (event) {
        //         case ATTACK_PRIMARY:
        //             // weaponPart.animController.stateMachines["fire"].triggerEvent("fire")
        //             break;
        //         case HURT:
        //             // for allAnimatables:
        //             //     target.animController.stateMachines["hit"].triggerEvent("hurt")
        //             break;
        //         // ...
        //     }
        // }
    }

    // ==========================================
    // 调试日志
    // ==========================================

    private void logStateChanges() {
        if (!debugLog) return;
        Posture posture = variables.get(POSTURE);
        Gait gait = variables.get(GAIT);
        Vertical vertical = variables.get(VERTICAL);
        if (posture == lastLoggedPosture && gait == lastLoggedGait && vertical == lastLoggedVertical) {
            return;
        }
        lastLoggedPosture = posture;
        lastLoggedGait = gait;
        lastLoggedVertical = vertical;
        MechaConditionSnapshot snap = conditionSnapshot;
        ARMS.LOGGER.info("[MechaCtrl] posture={} gait={} vertical={} | input=({}, {}) sprint={} jump={} "
                        + "| ground={} hSpeed={} vSpeed={}",
                posture.molangName(), gait.molangName(), vertical.molangName(),
                snap.inputForward(), snap.inputStrafe(), snap.sprintPressed(), snap.jumpPressed(),
                kcc.onGround(), variables.get(StateVariableKeys.SPEED),
                variables.get(StateVariableKeys.VERTICAL_SPEED));
    }
    // ==========================================
    // 查询 API
    // ==========================================

    /** 当前合并后的水平移动许可。 */
    public boolean canMove() {
        return logicStateMachine.canMove();
    }

    /** 当前合并后的开始跳跃许可。 */
    public boolean canJump() {
        return logicStateMachine.canJump();
    }

    /** 本帧是否有待处理事件（物理线程帧内视图）。 */
    public boolean hasPendingEvent(MechaEvent event) {
        return pendingEvents.contains(event);
    }

    /**
     * 逻辑层本帧产出的移动速度修正系数。
     * <p>
     * 等于 {@code posture.speedModifier() × gait.baseSpeedModifier()}，由 gait 子机在进入
     * 各状态时写入变量容器，并在同一步被推到 KCC 上（{@link #applyLogicOutputToKcc}）。
     * <p>
     * 读取注意：变量容器由物理线程写、本方法可能被主线程调用（调试与自检），因此读到的是
     * 最近一次物理步的结果。这是只读诊断用途，不要在物理决策中依赖它。
     *
     * @return 修正系数；变量容器尚无该键时返回 1.0（中性值）
     */
    public float getMoveSpeedModifier() {
        Float value = variables.get(MOVE_SPEED_MODIFIER);
        return value == null ? 1.0f : value;
    }

    /** 逻辑层本帧产出的姿态。同 {@link #getMoveSpeedModifier()} 的读取注意。 */
    public Posture getCurrentPosture() {
        return variables.get(POSTURE);
    }

    /** 逻辑层本帧产出的水平移动模式。同 {@link #getMoveSpeedModifier()} 的读取注意。 */
    public Gait getCurrentGait() {
        return variables.get(GAIT);
    }

    /**
     * 逻辑层状态的不可变快照 —— 物理线程 → 主线程的跨线程出口。
     * <p>
     * 这四项住在 {@link StateVariableContainer}（物理线程独占），主线程不得读写该容器
     * （`docs/下一步开发TODO.md:113`）。本 record 与 {@link MechaConditionSnapshot} 是同一模式
     * （不可变 record + volatile 引用），方向相反：本类是物理线程发布、主线程读取。
     * <p>
     * 唯一的消费者是同步写包路径——主线程据它填 `DATA_POSTURE` / `DATA_GAIT` /
     * `DATA_VERTICAL` / `DATA_ENERGY`
     * （`docs/ArmsCore双端权威与网络同步实现计划.md` §2.2、§3.5）。
     * 客户端读的是同步后的 {@code SynchedEntityData} 字段，不读本快照。
     * <p>
     * 它不属于网络协议类型，因此与 {@code MechaConditionSnapshot} 同放在
     * {@code common/control/} 下，作为 {@link MechaControl} 的嵌套类型。
     *
     * @param posture  当前姿态
     * @param gait     当前水平移动模式
     * @param vertical 当前垂直模式
     * @param energy   当前能量值
     */
    public record LogicStateSnapshot(
            Posture posture,
            Gait gait,
            Vertical vertical,
            float energy
    ) {
    }

    /**
     * 在物理线程读取状态变量容器，构造一份不可变的 {@link LogicStateSnapshot}。
     * <p>
     * 只允许在物理线程（即 {@code onPhysicsStep} / {@link #frameLogic(float)} 的调用线程）内调用：
     * 读取的 {@code variables} 是物理线程独占的可变容器。
     *
     * @return 本时刻的逻辑层状态副本
     */
    public LogicStateSnapshot snapshotLogicState() {
        return new LogicStateSnapshot(
                variables.get(POSTURE),
                variables.get(GAIT),
                variables.get(VERTICAL),
                variables.get(ENERGY));
    }
}
