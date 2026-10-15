package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.presentation.MechaAnimPresenter;
import io.github.sweetzonzi.arms_core.common.control.state.MechaLogicStateMachine;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import io.github.sweetzonzi.arms_core.common.control.state.graph.MechaStateActions;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

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
 *       连同控制器内部采集的物理状态，汇入统一的 {@code StateVariableContainer}</li>
 *   <li><b>状态机驱动</b> — 管理逻辑层状态机树（posture + gait/vertical 子机），每物理帧推进状态转移</li>
 *   <li><b>物理桥接</b> — 将玩家输入转发给 {@link MechaCharacter}（动力学刚体胶囊），
 *       在 {@link #onPhysicsStep} 中调用 {@code body.prePhysicsTick(dt)} 完成位姿写入、着地判据、
 *       行走力与越障</li>
 *   <li><b>旁路观测</b> — 默认 {@link #bypassObservation} 开启：状态机照常运行并产出变量，
 *       但暂不应用 CAN_MOVE / CAN_JUMP 门控控制器，保证不接逻辑层时的行为无回归；置 false 后启用反向控制</li>
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
 * 子系统合力叠加（推进器等）通过 {@code addSubsystemForce} 汇聚后施加到控制器刚体。
 *
 * <h3>双端权威不变量</h3>
 * 本类在两端都被构造，但职责不同。以下三条是后续改动的边界，不要越过：
 * <ol>
 *   <li><b>客户端实例没有身体、没有权威。</b> 客户端的 {@link #body} 恒为 {@code null}，
 *       {@link #logicStateMachine} 也不构造；客户端不推进逻辑状态机、不施力、不写刚体、
 *       不产生任何会被回传服务端的量。</li>
 *   <li><b>客户端的逻辑四项只能来自下行通道。</b> {@link #variables} 里的 POSTURE / GAIT /
 *       VERTICAL / ENERGY 及其 one-hot 派生由 {@link #applySyncedLogicState(LogicStateSnapshot)}
 *       发布、{@link #applySyncedLogicState()} 在物理步帧首写入，不得由客户端本地的任何状态机推导。
 *       客户端因此也不调用 {@link #logicStateMachine} 的 reset——构造期那次写入会把样式表默认值
 *       变成一次本地产出。</li>
 *   <li><b>表现层是单向消费者。</b> {@link #presentation} 只读 {@link #variables}、只驱动动画，
 *       不写任何会被回传服务端的量。</li>
 * </ol>
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

    /**
     * 控制器刚体（动力学胶囊：地面力律 + 跳跃 + 着地判据 + 越障）。
     * <p>
     * 客户端实例为 {@code null}：客户端没有输入权威与地形权威，本地建的刚体会在台阶与边缘处得出
     * 与服务端不同的着地结论，同一个 GAIT 因此在两端落到不同动画上，比一个网络往返的延迟更难排查。
     * 为空的成本是零——表现层首版不求控制器产出的任何状态量。判空统一走 {@link #hasBody()}。
     */
    @Getter
    private final @Nullable MechaCharacter body;

    /** 逻辑层共享变量容器；父状态机和全部活跃子机共用。 */
    private final StateVariableContainer variables;

    /** 逻辑层共享 GameplayTag 容器。 */
    private final GameplayTagContainer tags;

    /**
     * posture + gait/vertical 子机组成的逻辑状态机树。
     * <p>
     * 客户端实例为 {@code null}：客户端不推进逻辑树（四项来自下行通道），而构造它就要在构造期
     * {@code reset()} 一次，那会把 POSTURE 一类产出写成主线程的本地写入。见类注释的不变量 2。
     */
    @Getter
    private final @Nullable MechaLogicStateMachine logicStateMachine;

    /**
     * 本帧使用的下行逻辑状态 —— 主线程发布、物理线程帧首读取。
     * <p>
     * 与 {@link #conditionSnapshot} 同构（volatile 引用 + 不可变 record），方向相反：那个是主线程
     * 采集的输入，本字段是下行通道送来的逻辑层产出。权威侧从不发布到它，因此它在服务端恒为
     * {@code null}、消费端是 no-op；客户端没有逻辑树，容器里的四项只可能来自这里。
     * <p>
     * 同一 record 在这条通道上两个方向的语义相反：服务端方向是<b>产出</b>
     * （{@link #snapshotLogicState()} 从容器读出，供主线程写同步数据），客户端方向是<b>输入</b>
     * （{@code common/ArmsCoreServerEvents.java#onPostTick} 从同步数据写入容器）。
     */
    private volatile @Nullable LogicStateSnapshot syncedLogicState;

    /**
     * 表现层编排器：常驻并行动画层与四台姿态状态机。
     * <p>
     * 两端都建、都由 {@link #onPhysicsStep(float)} 推进；权威侧没有注入动画目标（首版唯一的
     * 目标是客户端对象），因此在那边是空转。它的建立与物理步推进都不依赖 {@link #body}。
     */
    @Getter
    private final MechaAnimPresenter presentation;

    /** 采集控制器速度时复用，避免物理帧内分配临时向量。 */
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
     * 空事件批的共享哨兵（不可变，永不写入）。
     * <p>
     * 物理线程每个物理子步都会把缓冲换回"空"。用这个共享实例代替
     * {@code EnumSet.noneOf(MechaEvent.class)}，无事件的那些步因此不产生分配——
     * 服务端每 tick 跑 5 个物理子步（{@code tps = baseStep × 20 = 100}），其中多数步没有事件。
     * <p>
     * 共享的前提是两端都只读：投递端 {@link #postEvent} 走 copy-on-write（新建集合后
     * {@code addAll}），读取端只做 {@code contains} 与遍历。任何一端改成原地修改都会破坏
     * 这条共享，{@code MechaControlTest#postEventDoesNotMutateTheBatchBeingConsumed} 把这一点钉住。
     */
    private static final Set<MechaEvent> NO_EVENTS = Set.of();

    /**
     * 事件投递缓冲（主线程追加，物理线程帧首原子取走整批）。
     * <p>
     * postEvent 采用 copy-on-write 的 {@code getAndUpdate}，与帧首
     * {@code getAndSet(NO_EVENTS)} 交换配合：任意交错下事件既不丢失也不重复，
     * 只会归属到本帧或下一帧。
     */
    private final AtomicReference<Set<MechaEvent>> pendingEventBuffer =
            new AtomicReference<>(NO_EVENTS);

    /**
     * 本帧事件批（物理线程帧内快照，仅物理线程访问）。
     * <p>
     * -- GETTER --
     * 本帧待处理事件（帧内快照，只读；跨帧请勿持有）。
     */
    private Set<MechaEvent> pendingEvents = NO_EVENTS;

    /** 上一帧是否处于 dodge，用于检出「刚进入 dodge」的那一帧（物理线程独占）。 */
    private boolean dodgingLastFrame;

    // ==========================================
    // 模式开关与调试
    // ==========================================

    /**
     * 旁路观测模式：状态机照常运行并产出变量，但不应用 CAN_MOVE / CAN_JUMP 门控。
     * <p>
     * 默认开启——先保证逻辑层不介入时的行走/跳跃无回归，观测稳定后再置 false 逐项启用反向控制。
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
     * @param body   已入物理空间的动力学角色控制器；客户端传 {@code null}（见类注释的不变量 1）
     */
    public MechaControl(MechaControlHolder holder, @Nullable MechaCharacter body) {
        this.holder = holder;
        this.body = body;
        this.conditionSnapshot = MechaConditionSnapshot.EMPTY;
        this.variables = new StateVariableContainer();
        this.tags = new GameplayTagContainer();
        // 表现层与逻辑层共用同一对容器，因此先建它、再建逻辑树；两者都只被物理线程读写
        this.presentation = new MechaAnimPresenter(variables, tags);
        if (body != null) {
            this.logicStateMachine = new MechaLogicStateMachine(variables, tags);
            this.logicStateMachine.reset();
            // 能量初始化为满值，sprint 等能量门控立即可用（消耗/恢复后续实现）
            this.variables.set(ENERGY, INITIAL_ENERGY);
        } else {
            // 客户端：不构造、不启动逻辑树。四项与 one-hot 由下行通道写入（不变量 2）
            this.logicStateMachine = null;
        }
    }

    /**
     * 本实例是否持有角色控制器 —— 权威分支的开关。
     * <p>
     * 权威侧：构造了控制器刚体与逻辑状态机，物理步跑完整的帧逻辑并驱动刚体。
     * 客户端：两者都没有，物理步只跑下行状态写入与表现层推进。
     *
     * @return {@code body != null}
     */
    public boolean hasBody() {
        return body != null;
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
     * 发布一份下行逻辑状态（主线程 → 物理线程帧首）。
     * <p>
     * 调用点是客户端主线程每 tick 的投影（{@code common/ArmsCoreServerEvents.java#onPostTick}）：
     * 它从同步数据读出 POSTURE / GAIT / VERTICAL / ENERGY 三个字符串与一个浮点、翻成枚举，
     * 组装成本 record 后交给本方法。权威侧不调用它。
     * <p>
     * record 的 final 字段 + volatile 引用保证安全发布，不会撕裂。四项与已发布的完全相同时本方法
     * 不替换引用（record 的 equals 按值比较），因此调用方每 tick 无脑调用即可，不会持续产生新对象。
     *
     * @param snapshot 本帧下行通道的逻辑状态
     */
    public void applySyncedLogicState(LogicStateSnapshot snapshot) {
        LogicStateSnapshot current = this.syncedLogicState;
        if (current != null && current.equals(snapshot)) return;
        this.syncedLogicState = snapshot;
    }

    /**
     * 把下行逻辑状态写进变量容器（物理步帧首）。
     * <p>
     * one-hot 派生复用状态机动作的同一份实现（{@link MechaStateActions} 的容器级入口），
     * 不另写一遍：姿态机的条件读的正是一批 one-hot 布尔。
     * <p>
     * gait 用「保留倍率」那一支：客户端不消费 MOVE_SPEED_MODIFIER，写它只会让客户端出现一个
     * 没有消费者的数值。
     * <p>
     * 本方法在权威侧是 no-op：那边从不发布到 {@link #syncedLogicState}，它们的四项由逻辑树
     * 自己写入，若在这里再覆盖一次就会用上一 tick 的陈旧值盖掉本步产出。
     */
    private void applySyncedLogicState() {
        LogicStateSnapshot state = syncedLogicState;
        if (state == null) return;
        MechaStateActions.writePostureFlags(variables, state.posture());
        MechaStateActions.writeGaitPreservingFlags(variables, state.gait());
        MechaStateActions.writeVerticalFlags(variables, state.vertical());
        variables.set(ENERGY, state.energy());
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
    // 子系统在 DRIVE 状态下施加驱动力时，通过此方法汇聚后叠加到控制器刚体。
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
     * 每物理帧主流程，由 Holder 在物理步中调用。两端都跑，按 {@link #hasBody()} 分段。
     * <p>
     * 执行顺序：
     * <ol>
     *   <li><b>下行状态写入</b>（{@link #applySyncedLogicState()}）——把客户端主线程投影过来的
     *       四项与 one-hot 写进变量容器。<b>必须在 {@link #hasBody()} 之外</b>：客户端容器里的
     *       四项只由这里写入，漏掉它，四台姿态机会停在键的默认值上（IS_STANDING 与 IS_IDLE 的
     *       默认都是 true），全部停在待机而不报任何错。</li>
     *   <li><b>帧逻辑</b>（仅权威侧，{@link #frameLogic(float)}）——帧首取走事件批、汇入快照与
     *       控制器状态、写朝向、广播事件、推进逻辑树、产出回写、转发输入。</li>
     *   <li><b>表现层推进</b>（{@link #presentation}）——状态机推进 + 动画时间推进。位置沿用
     *       「帧逻辑之后、提取动画根位移之前」这段空档，不改变权威侧既有的顺序。</li>
     *   <li><b>动画根位移与控制器物理步</b>（仅权威侧）——{@code extractAnimRootDelta()} 读的是
     *       上一段推进后的骨骼姿态；最后是 {@code body.prePhysicsTick(dt)}：位姿写入 / 着地 /
     *       行走力 / 越障。</li>
     * </ol>
     *
     * @param dt 物理步长 (s)
     */
    public void onPhysicsStep(float dt) {
        // —— 1. 下行状态写入（两端） ——
        applySyncedLogicState();

        // —— 2. 帧逻辑（仅权威侧） ——
        if (hasBody()) {
            frameLogic(dt);

            // —— 2b. 注入 MoLang ——
            // TODO: molangContext.setVariables(variables);

            // —— 2c. 分发事件到本地动画状态机 ——
            // TODO: dispatchEventsToLocalMachines();
        }

        // —— 3. 表现层推进（两端；权威侧没有注入动画目标，因此是空转） ——
        presentation.progress(dt);

        // —— 4. 动画根位移与控制器物理步（仅权威侧） ——
        if (hasBody()) {
            extractAnimRootDelta();
            body.prePhysicsTick(dt);
        }
    }

    /**
     * 帧逻辑推进（不含控制器的物理步）。
     * <p>
     * 包内可见以便单元测试在不触碰 jme3 native（sweepTest 等）的前提下验证接线：
     * 事件 latch → 变量汇入 → 朝向写入 → 状态机推进 → 输入转发 → 状态变化日志。
     *
     * @param dt 物理步长 (s)
     */
    void frameLogic(float dt) {
        // —— 帧首：原子取走主线程投递的事件批 ——
        pendingEvents = pendingEventBuffer.getAndSet(NO_EVENTS);

        // —— 1. 汇入快照与步进前的控制器状态 ——
        float safeDt = Math.max(dt, 1.0e-6f);
        writeStateInputs(safeDt);

        // —— 1b. 朝向：把视野偏航绝对赋值到控制器 ——
        // 必须早于 applyLogicOutputToKcc（闪避方向）与 forwardInputToKCC（行走方向）：
        // 这两处都按本步朝向解算，晚一步就会用上一物理步的角
        applyFacing();

        // —— 2. 事件广播到当前活跃状态树 ——
        broadcastPendingEvents();

        // —— 3. 推进整棵状态树，并由逻辑机合并最终输入许可 ——
        logicStateMachine.progress(safeDt);

        // —— 3b. 逻辑层产出回写到控制器（速度倍率、闪避）——
        // 必须在 progress 之后：本步产出的 gait / posture 才是本帧要生效的值
        applyLogicOutputToKcc(safeDt);

        // —— 4. 按旁路/反向模式转发输入到控制器 ——
        forwardInputToKCC();

        // —— 状态变化日志 ——
        logStateChanges();
    }

    /**
     * 把逻辑层本帧的产出落到控制器上。
     * <p>
     * 在同一个物理步内，本方法之前状态机产出的 {@code POSTURE} / {@code GAIT} /
     * {@code MOVE_SPEED_MODIFIER} 还只是变量容器里的值，控制器不看它们；本方法就是把它们变成
     * 物理效果的链路，共两项：
     * <ol>
     *   <li><b>速度倍率</b> —— {@code MOVE_SPEED_MODIFIER}（= posture.speedModifier ×
     *       gait.baseSpeedModifier）写进控制器，作为控制力的缩放系数（稳态速率随之等比缩放，
     *       不是另设一道速度上限；见 `docs/角色控制器-刚体动力学方案.md` §7.4 的力平衡）</li>
     *   <li><b>闪避</b> —— 进入 dodge 状态的那一帧解出闪避轴，把水平速度赋值到该轴上（只保留沿轴的
     *       同向分量，再叠加一次 Δv）并开启无敌窗口</li>
     * </ol>
     * 全部在物理线程执行，符合「只允许物理线程触碰 Bullet 对象」的约定。
     * <p>
     * 闪避只做一次赋值，不受 {@code MOVE_SPEED_MODIFIER} 影响：倍率缩放的是标准
     * WASD 控制力（{@link MechaCharacter#controlForceScale()}），而闪避是速度矢量赋值，两者量纲不同、
     * 互不干涉。dodge 状态因此不剥夺自主移动能力。
     */
    private void applyLogicOutputToKcc(float dt) {
        // —— ① 速度倍率 ——
        Float speedMod = variables.get(MOVE_SPEED_MODIFIER);
        body.setMoveSpeedModifier(speedMod == null ? 1.0f : speedMod);

        // —— ② 胶囊尺寸 ——
        // 未接入，属 P5 的范围（`docs/角色控制器-刚体动力学方案.md` §11 的 P5 行）。
        // 刚体路线已经解掉 KCC 那条硬阻塞——PhysicsRigidBody#setCollisionShape 允许在世换形
        // （`docs/角色控制器-刚体原型与引擎约束.md` §6.2 实测）——但接管它还需要两件事：
        // 变高前先向上扫掠确认头顶空间、换形后按新的半高把脚底重新对齐到换形前的脚底高度。
        // Posture posture = variables.get(POSTURE);
        // if (posture != null) body.applyPostureShape(posture);

        // —— ③ 闪避 ——
        // 只在「刚进入 dodge」的那一物理步投递一次：dodgingLastFrame 记录上一步是否在闪避，
        // 因此同一次闪避不会逐步重复投递，而 dodge 退出后再次进入又会投递一次。
        Gait gait = variables.get(GAIT);
        boolean dodgingNow = gait == Gait.DODGE;
        if (dodgingNow && !dodgingLastFrame) {
            float[] dir = resolveDodgeDirection();
            body.requestDodgeImpulse(dir[0], dir[1], DODGE_IMPULSE,
                    DODGE_INVULNERABLE_SECONDS);
        }
        dodgingLastFrame = dodgingNow;
    }

    /**
     * 解析闪避轴（世界坐标水平单位向量）—— 闪避赋值的目标方向。
     * <p>
     * 有移动输入时沿输入方向，无输入时沿控制器当前朝向（{@link MechaCharacter#getCurrentYaw()}）。
     * 两种情形的朝向都取控制器本步的朝向（{@link #applyFacing} 先于本方法执行），
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
        float yaw = body.getCurrentYaw();
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

    /** 将连续输入、环境状态、事件 latch 和控制器状态写入共享变量容器。 */
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

        variables.set(StateVariableKeys.ON_GROUND, body.isOnGround());
        // 水平速度直接从刚体读：三轴统一为 m/s，没有量纲换算，也没有动画根运动那条位移叠加
        // 通道要扣（刚体上动画位移走标准力 / 冲量通道，不占用物理速度字段）
        // 闪避改写的动量照实出现在这一份读数里——它赋值的就是真实速度
        body.getHorizontalVelocity(stateVelocity);
        float horizontalSpeed = (float) Math.sqrt(
                stateVelocity.x * stateVelocity.x + stateVelocity.z * stateVelocity.z);
        variables.set(StateVariableKeys.SPEED, horizontalSpeed);
        variables.set(StateVariableKeys.VERTICAL_SPEED, stateVelocity.y);
        variables.set(KCC_JUMP_BOOSTING, body.isBoosting());

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
     * 把本帧视野偏航写进控制器（{@link MechaCharacter#setViewYaw} 记录权威值，并由
     * {@code MechaCharacter#prePhysicsTick} 的朝向写入落到刚体姿态上）。
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
        body.setViewYaw(snap.viewYaw());
    }

    /**
     * 将快照中的移动/跳跃输入转发到控制器。
     * <p>
     * 移动意图（前后 / 左右）原样交给 {@link MechaCharacter#setMoveIntent}，由控制器按本步朝向
     * 转成世界方向，本层不做三角变换。跳跃的"按住"取自快照（连续量），"松开"取自本帧事件批
     * （{@link MechaEvent#JUMP_RELEASE} 的单帧边沿），因此同一次松键只被 latch 一次，
     * 不随快照被反复读取。
     * 旁路观测模式（默认）：原样透传，等价于逻辑层完全不存在时的控制器行为；
     * 反向控制模式：按逻辑层合并许可（CAN_MOVE / CAN_JUMP）门控。
     */
    private void forwardInputToKCC() {
        MechaConditionSnapshot snap = this.conditionSnapshot;
        float fwd = snap.inputForward();
        float str = snap.inputStrafe();
        boolean hasInput = (fwd * fwd + str * str) > 0.001f;
        boolean jumpRelease = pendingEvents.contains(MechaEvent.JUMP_RELEASE);

        if (bypassObservation) {
            // 旁路观测：原样透传（无输入则置零，地面的无输入制动由引擎接触摩擦给出）
            applyMoveIntent(fwd, str, hasInput);
            body.setJumpInput(snap.jumpPressed(), jumpRelease);
        } else {
            // 反向控制：CAN_MOVE / CAN_JUMP 门控
            applyMoveIntent(fwd, str, hasInput && variables.get(CAN_MOVE));

            // CAN_JUMP 只限制开始跳跃；已经开始助推后仍须透传 held/released 才能正常终止窗口。
            // 离地后 posture 切 air、CAN_JUMP 即为假，靠「正在助推」这条旁路让窗口内的 held 继续透传
            boolean boosting = body.isBoosting();
            boolean jumpHeld = snap.jumpPressed() && (variables.get(CAN_JUMP) || boosting);
            boolean jumpReleased = jumpRelease && boosting;
            body.setJumpInput(jumpHeld, jumpReleased);
        }
    }

    /**
     * 把移动意图写入控制器。
     * <p>
     * 参数是玩家视角相对的原始量（正 = 前进 / 左移），本方法不做坐标系变换：行走方向的
     * 「意图 → 世界」变换发生在 {@link MechaCharacter#setMoveIntent} 内，用的是本步
     * {@link #applyFacing} 写下的视野偏航。禁止移动时置零。
     *
     * @param fwd     前后意图，[-1, 1]，正 = 前进
     * @param str     左右意图，[-1, 1]，正 = 左移
     * @param allowed 本步是否允许移动
     */
    private void applyMoveIntent(float fwd, float str, boolean allowed) {
        if (!allowed) {
            body.setMoveIntent(0f, 0f);
            return;
        }
        body.setMoveIntent(fwd, str);
    }

    // ==========================================
    // 动画根骨骼位移提取（私有）
    // ==========================================

    /**
     * 从躯干 SubPart 的 body_root 骨骼提取帧间位移与旋转，写入控制器供多通道合成。
     * <p>
     * 设计依据：行走物理设计 §1.2（多通道合成）和 §10.3（叠加公式）。
     * body_root 骨骼通过 New6Dof 世界锚点约束与控制器刚体链接。
     * <p>
     * 提取内容：
     * <ul>
     *   <li><b>位移</b> (dx, dy, dz, m/tick) — 世界坐标帧间差，作为力源数据交给
     *       {@code MechaCharacter#setAnimRootDelta}（刚体上动画位移走标准力 / 冲量通道，
     *       不占用物理位移字段，见 `docs/角色控制器-刚体动力学方案.md` §6 的 B3 行）</li>
     *   <li><b>Y 轴旋转</b> (deltaYaw, rad/tick) — 面向帧间差，用于转身斩/回旋踢/
     *       idle 微晃等动画驱动面向变化。控制器的角因子是 {@code (0,1,0)}，偏航以外
     *       两轴不接受角冲量（`docs/角色控制器-刚体动力学方案.md` §4.1、§8.3）</li>
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
        //    body.setAnimRootDelta(dx, dy, dz);
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
        //    body.setAnimRootYawDelta(deltaYaw);
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
                body.isOnGround(), variables.get(StateVariableKeys.SPEED),
                variables.get(StateVariableKeys.VERTICAL_SPEED));
    }
    // ==========================================
    // 查询 API
    // ==========================================

    /**
     * 当前合并后的水平移动许可。
     * <p>
     * 仅权威侧有逻辑树；客户端返回 {@code false}。这条许可只服务权威侧的门控，客户端不消费它。
     */
    public boolean canMove() {
        return logicStateMachine != null && logicStateMachine.canMove();
    }

    /** 当前合并后的开始跳跃许可。同 {@link #canMove()}，客户端没有逻辑树时返回 {@code false}。 */
    public boolean canJump() {
        return logicStateMachine != null && logicStateMachine.canJump();
    }

    /** 本帧是否有待处理事件（物理线程帧内视图）。 */
    public boolean hasPendingEvent(MechaEvent event) {
        return pendingEvents.contains(event);
    }

    /**
     * 逻辑层本帧产出的移动速度修正系数。
     * <p>
     * 等于 {@code posture.speedModifier() × gait.baseSpeedModifier()}，由 gait 子机在进入
     * 各状态时写入变量容器，并在同一步被推到控制器上（{@link #applyLogicOutputToKcc}）。
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

    /**
     * 逻辑层本帧的姿态。同 {@link #getMoveSpeedModifier()} 的读取注意。
     * <p>
     * 权威侧是逻辑树的本步产出；客户端是下行通道写入的值，两者默认值都是键自带的默认值
     * （POSTURE 默认 STAND、GAIT 默认 IDLE）。
     */
    public Posture getCurrentPosture() {
        return variables.get(POSTURE);
    }

    /** 逻辑层本帧的水平移动模式。语义与空值行为同 {@link #getCurrentPosture()}。 */
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
     * <b>同一 record 在下行通道上方向相反。</b> 权威侧方向是产出：容器由逻辑树写、快照由
     * {@link #snapshotLogicState()} 读出，供主线程写同步数据。客户端方向是输入：主线程从同步数据
     * 读出字段、组装成同一个 record 后经 {@link #applySyncedLogicState(LogicStateSnapshot)}
     * 发布，物理线程在帧首把它写回容器。客户端因此不读本快照，也不从容器反推状态。
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

    /**
     * 与 {@link #snapshotLogicState()} 相同，但四项与 {@code previous} 全同时**原样返回
     * {@code previous}**、不新建对象。
     * <p>
     * 给物理线程用来发布逻辑状态：逻辑层每个物理子步推进一次，而主线程每 tick 才取一次读，
     * 多数子步的四项与上一步相同。读取端（{@code common/ArmsCoreServerEvents.java#writeSyncedData}）
     * 只取组件值、不比较引用，因此"值相同就沿用旧对象"对它不可见。
     * <p>
     * 四个变量各读一次，因此未变的那些步既不分配、也不比 {@link #snapshotLogicState()} 多读。
     *
     * @param previous 上一次发布的快照
     * @return 四项有任意一项变化时是新建的快照，否则就是 {@code previous} 本身
     */
    public LogicStateSnapshot snapshotLogicStateIfChanged(LogicStateSnapshot previous) {
        Posture posture = variables.get(POSTURE);
        Gait gait = variables.get(GAIT);
        Vertical vertical = variables.get(VERTICAL);
        float energy = variables.get(ENERGY);
        if (posture == previous.posture()
                && gait == previous.gait()
                && vertical == previous.vertical()
                && Float.compare(energy, previous.energy()) == 0) {
            return previous;
        }
        return new LogicStateSnapshot(posture, gait, vertical, energy);
    }
}
