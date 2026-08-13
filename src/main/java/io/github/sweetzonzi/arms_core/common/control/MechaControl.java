package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.state.MechaLogicStateMachine;

import java.util.EnumSet;
import java.util.Set;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;

/**
 * 机甲运动控制编排器核心。
 * <p>
 * MechaControl 是 ARMS-Core 角色运动系统的唯一编排器，聚合以下职责：
 * <ol>
 *   <li><b>输入消费</b> — 接收 Holder 写入的 {@link MechaConditionSnapshot} 和 {@link MechaEvent}，
 *       连同 KCC 内部采集的物理状态，汇入统一的 {@code StateVariableContainer}</li>
 *   <li><b>状态机驱动</b> — 管理中央 {@code MultiAnimStateMachine}（全局动作）和本地
 *       {@code AnimStateMachine}（零件自主动作），每物理帧推进状态转移</li>
 *   <li><b>动画编排</b> — 从根 SubPart 的 body_root 骨骼提取帧间位移写入 KCC
 *       （animRootDelta），并聚合动画完成状态供状态机条件查询</li>
 *   <li><b>物理桥接</b> — 将玩家输入转发给 {@link MechaCharacter}（KCC 封装），
 *       在 {@link #onPhysicsStep} 中调用 {@code kcc.prePhysicsTick(dt)} 完成行走/跳跃物理积分</li>
 *   <li><b>MoLang 集成</b> — 将 StateVariableContainer 注入 {@code MechaMolangContext}，
 *       使 JSON 动画控制器可通过 {@code ctrl.*} 绑定读取状态</li>
 * </ol>
 * <p>
 * 线程模型：所有公共方法均在物理线程内执行（由 Holder 的物理步回调驱动）。
 * pendingEvents 和 conditionSnapshot 仅被物理线程单线程访问，无需同步。
 * <p>
 * 不依赖 Machine-Max 信号总线（ISignalBus）。输入由 Holder 直接传入。
 * 子系统合力叠加（推进器等）通过 {@code addSubsystemForce} 汇聚后施加到 KCC。
 *
 * @author Sweetzonzi
 */
public class MechaControl {

    // ==========================================
    // 持有关系
    // ==========================================

    /** 持有者引用，构造时绑定 */
    private final MechaControlHolder holder;

    /** KCC 运动学胶囊控制器（行走物理 + 跳跃 + 碰撞 sweep） */
    private final MechaCharacter kcc;

    /** 逻辑层共享变量容器；父状态机和全部活跃子机共用。 */
    private final StateVariableContainer variables;

    /** 逻辑层共享 GameplayTag 容器。 */
    private final GameplayTagContainer tags;

    /** posture + gait/vertical 子机组成的逻辑状态机树。 */
    private final MechaLogicStateMachine logicStateMachine;

    /** 采集 KCC 速度时复用，避免物理帧内分配临时向量。 */
    private final Vector3f stateVelocity = new Vector3f();

    // ==========================================
    // 输入缓冲
    // ==========================================

    /** 本帧条件快照（每帧由 Holder 覆盖写入） */
    private MechaConditionSnapshot conditionSnapshot;

    /** 本帧离散事件队列（latch 到帧末，消费后清除） */
    private final Set<MechaEvent> pendingEvents = EnumSet.noneOf(MechaEvent.class);

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
        this.conditionSnapshot = MechaConditionSnapshot.createEmpty();
        this.variables = new StateVariableContainer();
        this.tags = new GameplayTagContainer();
        this.logicStateMachine = new MechaLogicStateMachine(variables, tags);
        this.logicStateMachine.reset();
    }

    // ==========================================
    // 输入 API（Holder 通过接口 default 方法调用）
    // ==========================================

    /**
     * 写入本帧条件快照（每帧覆盖）。
     * <p>
     * 由 {@link MechaControlHolder#writeConditionSnapshot(MechaConditionSnapshot)} 委托调用。
     *
     * @param snapshot 本帧由 Holder 采集的条件快照
     */
    public void applyConditionSnapshot(MechaConditionSnapshot snapshot) {
        this.conditionSnapshot = snapshot;
    }

    /**
     * 投递离散事件（latch 到帧末）。
     * <p>
     * 由 {@link MechaControlHolder#postEvent(MechaEvent)} 委托调用。
     * 重复投递同一事件仅保留一个（EnumSet 去重）。
     *
     * @param event 事件类型
     */
    public void postEvent(MechaEvent event) {
        pendingEvents.add(event);
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
     *   <li>汇入快照和 KCC 状态到 StateVariableContainer</li>
     *   <li>广播离散事件并推进逻辑状态机</li>
     *   <li>逻辑状态机在推进完成后合并最终输入许可</li>
     *   <li>按最终许可转发玩家输入到 KCC</li>
     *   <li>提取动画根骨骼位移 → kcc.setAnimRootDelta</li>
     *   <li>KCC 物理积分 ({@code kcc.prePhysicsTick(dt)})</li>
     *   <li>清除已消费事件</li>
     * </ol>
     *
     * @param dt 物理步长 (s)
     */
    public void onPhysicsStep(float dt) {
        // —— 1. 汇入快照与步进前 KCC 状态 ——
        float safeDt = Math.max(dt, 1.0e-6f);
        writeStateInputs(safeDt);

        // —— 2. 事件广播到当前活跃状态树 ——
        broadcastPendingEvents();

        // —— 3. 推进整棵状态树，并由逻辑机合并最终输入许可 ——
        logicStateMachine.progress(safeDt);

        // —— 4. 按最终许可转发输入到 KCC ——
        forwardInputToKCC();

        // —— 5. 注入 MoLang ——
        // TODO: molangContext.setVariables(variables);

        // —— 6. 驱动表现层中央状态机 ——
        // TODO: centralMachines.forEach((name, machine) -> machine.progress(dt));
        // 内部：PlayAnimAction → for target in animTargets:
        //   anim = findAnimation(target, stateName)  ← 三级回退（零件 → 素体 → 内置）
        //   instance.group = controller.animGroup
        //   target.animController.playAnimation(instance)

        // —— 7. 分发事件到本地动画状态机 ——
        // TODO: dispatchEventsToLocalMachines();

        // —— 8. 提取动画根骨骼位移 ——
        extractAnimRootDelta();

        // —— 9. KCC 物理积分（行走力 / 跳跃 / 碰撞 sweep） ——
        kcc.prePhysicsTick(dt);

        // —— 10. 清除已消费事件 ——
        pendingEvents.clear();
    }

    /** 将连续输入、环境状态、事件 latch 和 KCC 状态写入共享变量容器。 */
    private void writeStateInputs(float dt) {
        MechaConditionSnapshot snap = conditionSnapshot;
        boolean hasInput = snap.inputForward * snap.inputForward
                + snap.inputStrafe * snap.inputStrafe > 0.001f;

        variables.set(StateVariableKeys.INPUT_FORWARD, snap.inputForward);
        variables.set(StateVariableKeys.INPUT_STRAFE, snap.inputStrafe);
        variables.set(StateVariableKeys.IS_SPRINTING, snap.sprintPressed);
        variables.set(StateVariableKeys.IS_DEAD, snap.isDead);
        variables.set(HAS_INPUT, hasInput);
        variables.set(WALK_KEY_DOWN, snap.walkKeyPressed);
        variables.set(IN_WATER, snap.inWater);

        variables.set(StateVariableKeys.ON_GROUND, kcc.onGround());
        float safeDt = Math.max(dt, 1.0e-6f);
        kcc.getLinearVelocity(stateVelocity);
        float horizontalSpeed = (float) Math.sqrt(
                stateVelocity.x * stateVelocity.x + stateVelocity.z * stateVelocity.z) / safeDt;
        variables.set(StateVariableKeys.SPEED, horizontalSpeed);
        variables.set(StateVariableKeys.VERTICAL_SPEED, stateVelocity.y);
        variables.set(KCC_JUMP_CHARGING, kcc.isChargingJump());

        variables.set(EVENT_DODGE, pendingEvents.contains(MechaEvent.DODGE));
        variables.set(EVENT_TOGGLE_SNEAK, pendingEvents.contains(MechaEvent.TOGGLE_SNEAK));
        variables.set(EVENT_TOGGLE_PRONE, pendingEvents.contains(MechaEvent.TOGGLE_PRONE));
        variables.set(EVENT_TOGGLE_DRIVE, pendingEvents.contains(MechaEvent.TOGGLE_DRIVE));
        variables.set(EVENT_TOGGLE_FLY, pendingEvents.contains(MechaEvent.TOGGLE_FLY));
    }

    /**
     * 广播逻辑层事件。死亡时忽略普通输入事件，让 ragdoll 自动转移拥有绝对优先级。
     */
    private void broadcastPendingEvents() {
        if (conditionSnapshot.isDead) return;

        for (MechaEvent event : pendingEvents) {
            String eventType = switch (event) {
                case TOGGLE_SNEAK -> "sneak";
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
    // 输入转发（私有）
    // ==========================================

    /**
     * 将快照中的移动/跳跃输入转发到 KCC。
     * <p>
     * 移动方向需要从玩家输入方向 + 视角偏航转换为世界坐标系方向。
     * 若无移动意图则设置零向量（触发 §3.9 无输入制动）。
     */
    private void forwardInputToKCC() {
        MechaConditionSnapshot snap = this.conditionSnapshot;

        // 移动输入：将 [-1,1] 输入方向从视角相对坐标系转换为世界坐标系
        float fwd = snap.inputForward;
        float str = snap.inputStrafe;
        boolean hasInput = (fwd * fwd + str * str) > 0.001f;

        if (hasInput && variables.get(CAN_MOVE)) {
            // 视角偏航转弧度（Minecraft yaw: 0=南, 90=西）
            float yawRad = (float) Math.toRadians(snap.viewYaw);
            float sinYaw = (float) Math.sin(yawRad);
            float cosYaw = (float) Math.cos(yawRad);

            // 与客户端已验证方向一致：前进 = (-sinYaw, 0, cosYaw)
            float worldDirX = str * cosYaw - fwd * sinYaw;
            float worldDirZ = fwd * cosYaw + str * sinYaw;

            kcc.setMoveInput(worldDirX, worldDirZ);
        } else {
            kcc.setMoveInput(0, 0);
        }

        // CAN_JUMP 只限制开始跳跃；已经开始蓄力后仍须透传 held/released 才能正常释放。
        boolean charging = kcc.isChargingJump();
        boolean jumpHeld = snap.jumpPressed && (variables.get(CAN_JUMP) || charging);
        boolean jumpReleased = snap.jumpReleased && charging;
        kcc.setJumpInput(jumpHeld, jumpReleased);
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
    // 查询 API
    // ==========================================

    /** 获取当前快照（状态机内部可能需要直接查询未被汇入变量的字段） */
    public MechaConditionSnapshot getCurrentSnapshot() {
        return conditionSnapshot;
    }

    /** 获取 KCC 引用（调试/子系统合力叠加等场景） */
    public MechaCharacter getKCC() {
        return kcc;
    }

    /** 获取持有者引用 */
    public MechaControlHolder getHolder() {
        return holder;
    }

    /** 获取逻辑状态机，供物理线程调试和只读状态查询。 */
    public MechaLogicStateMachine getLogicStateMachine() {
        return logicStateMachine;
    }

    /** 当前合并后的水平移动许可。 */
    public boolean canMove() {
        return logicStateMachine.canMove();
    }

    /** 当前合并后的开始跳跃许可。 */
    public boolean canJump() {
        return logicStateMachine.canJump();
    }

    /** 检查是否有待处理的事件 */
    public boolean hasPendingEvent(MechaEvent event) {
        return pendingEvents.contains(event);
    }

    /** 获取当前帧所有待处理事件（只读） */
    public Set<MechaEvent> getPendingEvents() {
        return pendingEvents;
    }
}
