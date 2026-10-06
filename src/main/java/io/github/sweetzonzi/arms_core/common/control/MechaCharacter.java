package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.CollisionSpace;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.collision.PhysicsRayTestResult;
import com.jme3.bullet.collision.PhysicsSweepTestResult;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.collision.shapes.ConvexShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaJumpAttr;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 机娘动力学角色控制器（对应设计文档：`docs/角色控制器-刚体动力学方案.md`）。
 * <p>
 * <b>承载物是一个动态刚体胶囊</b>：本类就是那个胶囊（继承 {@link PhysicsRigidBody}），
 * 由求解器负责碰撞响应、位置积分与速度积分。控制器只做四件事：
 * <ol>
 *   <li><b>位姿写入</b> —— 每物理步把视野偏航绝对写进刚体姿态，并在倾角超过阈值时按
 *       {@code tilt ← (1 − λ)·tilt} 把上轴拉回竖直（§5.3、§5.4）</li>
 *   <li><b>着地判据</b> —— 向下形状扫掠 + 法线筛，给出着地布尔与支撑法线（§7.7）</li>
 *   <li><b>行走力律</b> —— 地面按 {@code F_net = min(k·computeDriveForce(v), μ_eff·N)}、
 *       {@code N = m·g·cosθ}，再叠加摩擦锥前馈 {@code μ_eff·N·û}，以质心中心力施加（§7.4、§7.6）</li>
 *   <li><b>越障三段</b> —— 抬升 → 前移 → 落位，同一物理步内完成，水平速度原样保留（§6.2）</li>
 * </ol>
 * 不自己写滑墙、不自己写被动摩擦、不自己做穿透恢复：那三件事属于求解器。
 * <p>
 * <b>速度语义。</b> 三个轴统一为 m/s（§6），不再是「水平为每物理步位移、垂直为 m/s」的混合量纲，
 * 因此读取速度不需要除以 {@code dt}，也不存在「动画位移叠加记账」那条影子通道。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>写入方：{@link #setMoveIntent}、{@link #setViewYaw}、{@link #setJumpInput}、
 *       {@link #setGravityScale}、{@link #setInputScale}、{@link #setAnimRootDelta}、
 *       {@link #setSeparationDistance} 写入 volatile 字段。生产路径上这些调用来自物理线程
 *       ——{@code MechaControl.frameLogic} 与 {@code onPhysicsStep} 同线程</li>
 *   <li>物理线程：{@link #prePhysicsTick} 读取 volatile 输入，写位姿、施加力与冲量</li>
 *   <li>主线程：只读出口——同步通道读 {@link #getCurrentYaw()} / {@code getPhysicsLocation(null)} /
 *       {@code getLinearVelocity(null)}，不写任何字段</li>
 * </ul>
 * <p>
 * 朝向与移动方向的分工：{@link #setViewYaw} 写入朝向权威（并立即落到刚体姿态上），
 * {@link #setMoveIntent} 接收本体坐标系的移动意图并按该朝向转成世界方向。两者合起来
 * 使 WASD 的「前后左右」始终是当前朝向下的一对轴，且朝向只被计入一次。
 * <p>
 * 可覆写定制点（protected）允许后续接入腿部助力子系统：
 * <ul>
 *   <li>{@link #computeDriveForce} — 当前速率下的总驱动力（含各源力-速曲线）</li>
 *   <li>{@link #computeJumpImpulse} — 冲量相的跳跃冲量（含多腿叠加）</li>
 *   <li>{@link #getBoostForce} — 助推窗口内的持续向上助推力（含多腿叠加）</li>
 *   <li>{@link #getJumpSpeedCap} — 冲击相的速度增量上限</li>
 *   <li>{@link #getEffectiveFriction} — 组合摩擦系数 {@code μ_eff}</li>
 *   <li>{@link #getControllerMass} — 力学质量（与刚体质量的语义差别见 §12.3）</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
public class MechaCharacter extends PhysicsRigidBody {

    // ═══════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════

    /** 数值精度阈值 */
    private static final float EPSILON = MechaWalkingAttr.EPSILON;

    /**
     * 「是地面还是墙」的法线竖直分量分界线（≈70°），`docs/角色控制器-刚体动力学方案.md` §7.7。
     * <p>
     * 它是着地支撑与越障立面共用的同一个阈值。它有一个硬约束：<b>必须大于
     * {@code atan(μ_eff_max)}</b>，否则爬得上去的坡度会被判成墙（爬坡上限由摩擦锥自然涌现，
     * 不由本阈值决定）。当前组合 {@code μ_eff = 1.0} → 45°，0.35 留有余量。
     */
    private static final float GROUND_NORMAL_THRESHOLD = 0.35f;

    /** 向下扫掠窗口在胶囊中心<b>之上</b>的余量 (m)：允许在离地这么多时仍算着地。 */
    private static final float GROUND_WINDOW_ABOVE = 0.3f;

    /** 向下扫掠窗口在胶囊中心<b>之下</b>的余量 (m)：探到脚底以下这么多。 */
    private static final float GROUND_WINDOW_BELOW = 0.1f;

    /** 向下扫掠探针的总长度 (m)。引擎要求起终点至少相距 0.4，正好等于该下限。 */
    private static final float GROUND_PROBE_LENGTH = GROUND_WINDOW_ABOVE + GROUND_WINDOW_BELOW;

    /**
     * 着地状态的最小保持步数（{@code docs/角色控制器-刚体动力学方案.md` §7.7 要求的那一项）。
     * <p>
     * 合并地形的接缝处，一次向下扫掠可能整体漏判，若不加保持，{@code ON_GROUND} 会在一步内
     * 翻成假、地面驱动力断一拍、状态机的 posture 也跟着翻一次。取 2 步（服务端 20 ms）覆盖
     * 「漏判一步」这一形态。
     * <p>
     * <b>时长与计法（按 dt 还是按步）尚未定</b>，最终值在 P1 的真地形回归里定
     * （`docs/角色控制器-刚体动力学方案.md` §7.7）。这里按步计，2 步即 2 个物理步。
     */
    private static final int GROUND_MIN_HOLD_STEPS = 2;

    /**
     * 着地且垂直速度不超过本值时把垂直速度清零 (m/s)。
     * <p>
     * 重力每步给刚体注入 {@code g·dt = 0.098 m/s} 的下向速度，而求解器只约束位置、不回写速度，
     * 因此不清零会以每步约 5 mm 的速度均匀下沉（直到接触边距被吃满）。跳跃的起跳速度为正、
     * 自由下落的速度远大于本值（从 0.1 m 高度落下也有 1.4 m/s），因此本值不会误吃跳跃。
     */
    private static final float GROUNDED_VERTICAL_SNAP = 0.5f;

    /** 倾角小于本值即认为已竖直 (rad)，`docs/角色控制器-刚体原型与引擎约束.md` §10.2。 */
    private static final float UPRIGHT_LOCK_RAD = (float) Math.toRadians(0.5);

    /**
     * 姿态写入每步的收敛系数 λ（`docs/角色控制器-刚体动力学方案.md` §5.3）。
     * <p>
     * 倾角严格按 {@code tilt ← (1 − λ)·tilt} 衰减：0.20 时从 30° 收敛到 0.5° 约需 19 步。
     * 它同时是「冲击吸收可见度」的旋钮——λ 越大立正越快。
     */
    private static final float SETTLE_LAMBDA = 0.20f;

    /**
     * 朝向写入的让位阈值 (rad)：倾角超过本值就跳过本步的朝向写入（§5.3）。
     * <p>
     * 取 90°：朝向写入取的是局部 +Z 的<b>水平投影</b>，姿态躺平时该投影趋零、朝向误差的轴
     * 随之无定义。这种姿态只在「空中未收敛就再次落地」时出现，处置是让收敛写入先把它摆正。
     */
    private static final float FACING_WRITE_TILT_LIMIT = (float) (Math.PI / 2);

    /**
     * 越障探针的前向扫掠长度 (m)，`docs/角色控制器-刚体原型与引擎约束.md` §10.2。
     * <p>
     * 必须 ≥ 0.4（引擎对 {@code sweepTest} 起终点距离的下限），因此不能等于每步位移
     * ——100 Hz 下 6 m/s 只有 6 cm。它同时决定越障的起判距离：胶囊前缘离台阶超过
     * {@code 探针长度 − 半径} 时探不到。
     */
    private static final float STEP_PROBE_FORWARD = 1.2f;

    /**
     * 「抬起来走不走得过去」那一次扫掠的长度 (m)。<b>必须短于 {@link #STEP_PROBE_FORWARD}。</b>
     * <p>
     * 判决要回答的是「抬到台阶顶面之上以后这个位置还挡不挡」，而不是「再往前一米有没有别的东西」：
     * 台阶顶面只有 1 m 宽（MC 方块尺度）时，用 1.2 m 的探针会直接捅到台阶后方的下一格地形，
     * 把一次合法的越障判成失败。
     */
    private static final float STEP_CLEARANCE_PROBE = 0.5f;

    /**
     * 摩擦锥前馈的释放余量（无量纲），见 §7.4 的力律。
     * <p>
     * 施加的法向摩擦前馈取 {@code μ_eff·N·(1 + 本值)}，而不是恰好的 {@code μ_eff·N}。原因是
     * 引擎对受力接触体给的是**静摩擦锁定**：实测把施加力恰好取成 {@code μ_eff·N} 时胶囊纹丝
     * 不动（净力恒为 0，50 步位移 0.05 mm），因为求解器的静摩擦约束精确抵消了切线力。
     * 留 1% 的余量把工作点推离锁定面，净力才是期望净力。
     * <p>
     * 余量的量级由「不显著改变净力」与「确实脱离锁定」两条同时约束：1% 在当前参数下是 6.9 N，
     * 相对 {@code F_MAX = 700 N} 可忽略，而实测已足以起步（解析起步加速度 9.81 m/s² 复现到
     * 小数点后四位）。
     */
    private static final float FEEDFORWARD_RELEASE = 0.01f;

    /**
     * 抬起来做判决时脚底要高出台阶顶面多少 (m)。<b>必须大于胶囊半径。</b>
     * <p>
     * 胶囊底部是半径 0.4 m 的半球，脚底只高出顶面 {@code δ} 时半球在水平方向仍伸出
     * {@code √(2rδ)}：δ = 0.05 时伸出 0.20 m，判决会被「抬起来之后还是撞到台阶自己」
     * 误判成失败（原型实测，见 §11.4）。
     */
    private static final float STEP_LIFT_CLEARANCE = 0.5f;

    /** 越障时最小水平推进量 (m)：前缘已贴住台阶时仍要有一步向前，否则永远停在贴墙的平衡点上。 */
    private static final float STEP_MIN_ADVANCE = 1.0e-3f;

    /**
     * 素体裸足的越障高度 (m)，取值来自 {@link MechaWalkingAttr#STEP_HEIGHT_BASE}。
     * <p>
     * 公开是因为它是控制器与外部（腿部助力子系统、测试）之间的契约值：越障判决的落差上限就是它
     * （§6.2），腿部子系统提供 {@code step_height_bonus} 时会按它加成。
     * <p>
     * 语义与 KCC 时代的 {@code PhysicsCharacter#setStepHeight} 相同，但实现路径不同：那时是把这个值
     * 交给引擎的 {@code stepUp}，现在由 {@link #tryStepUp} 的三段判决自己比。
     */
    public static final float STEP_HEIGHT_BASE = MechaWalkingAttr.STEP_HEIGHT_BASE;

    /** 台阶落差的下限 (m)：低于它视为「几乎无落差」，不是台阶。 */
    private static final float STEP_RISE_MIN = 0.01f;

    /** 越障前向探针起点相对脚底的高度 (m)，见 {@link #probeForward}。 */
    private static final float STEP_PROBE_LIFT = 0.01f;

    /** 台阶顶面探针起点相对脚底的高度余量 (m)，见 {@link #probeStepTop}。 */
    private static final float STEP_TOP_PROBE_MARGIN = 0.3f;

    /** 复位「上一步落点上下文」时要作废的闪避轴向与输入（{@link #displaceTo} 用）。 */
    private static final Quaternion IDENTITY_ROTATION = new Quaternion();

    // ═══════════════════════════════════════════════
    // 实例字段
    // ═══════════════════════════════════════════════

    /**
     * 物理空间引用（用于扫掠与射线检测）。
     * <p>
     * 优先由 {@link PhysicsCollisionObject#getCollisionSpace()} 解析（刚体入物理空间后自动可得），
     * 构造参数只作为兜底——入世之前 {@code getCollisionSpace()} 返回 {@code null}。
     */
    private final PhysicsSpace fallbackSpace;

    /**
     * 胶囊半高 = 圆柱段半高 + 半球半径 (m)，用于把胶囊中心换算到脚底。
     * <p>
     * 随姿态改变（{@link #applyPostureShape}），因此不能是 {@code final}；
     * 由物理线程写入、物理线程读取，另加 {@code volatile} 是因为换形也可能发生在
     * 主线程提交的任务里。
     */
    private volatile float capsuleHalfTotal;

    // ── 移动与跳跃输入（volatile，跨线程可见） ──

    /**
     * 本步移动方向的世界坐标 X 分量（已归一化）。
     * <p>
     * 由 {@link #setMoveIntent} 在写入时解出：它把体系移动意图按当时的 {@code viewYaw}
     * 旋转成世界方向，{@link #updateWalk} 只消费结果、自身不做旋转。
     */
    @Getter(AccessLevel.PACKAGE)
    private volatile float inputDirX;
    /** 本步移动方向的世界坐标 Z 分量（已归一化），与 {@link #inputDirX} 同一次变换的产物 */
    @Getter(AccessLevel.PACKAGE)
    private volatile float inputDirZ;
    /** 是否有移动意图 */
    @Getter(AccessLevel.PACKAGE)
    private volatile boolean inputHasMove;
    /** 跳跃键是否按住 */
    @Getter(AccessLevel.PACKAGE)
    private volatile boolean jumpHeld;
    /** 跳跃键本帧松开（单帧标记，物理线程消费后清零） */
    private volatile boolean jumpReleased;

    /** 视野偏航（弧度，Minecraft 约定：0 = 南 +Z，取值增大 = 向左转） */
    private volatile float viewYaw;

    // ── 动画与参数调制（外部写入，物理线程读取）──

    /** 重力缩放系数，1.0 = 正常重力，0.0 = 浮空。由 MoLang ctrl.set_gravity_scale 改写 */
    @Setter
    private volatile float gravityScale = 1.0f;

    /** 玩家能动性缩放，只作用于行走净力（WASD 连续量）。1.0 = 正常，0.0 = 全锁。由 MoLang ctrl.set_input_scale 改写 */
    @Setter
    private volatile float inputScale = 1.0f;

    /** 动画根骨骼帧间位移 X 分量（世界坐标，m/tick），由 MechaControl 每物理步写入 */
    private volatile float animDeltaX;
    /** 动画根骨骼帧间位移 Y 分量（世界坐标，m/tick） */
    private volatile float animDeltaY;
    /** 动画根骨骼帧间位移 Z 分量（世界坐标，m/tick） */
    private volatile float animDeltaZ;

    /**
     * 动画根骨骼帧间 Y 轴旋转增量（rad/tick）—— 阶段 4 的接入点，当前不参与朝向合成。
     * <p>
     * 朝向的权威是 {@link #setViewYaw}（视野偏航绝对赋值）。动画驱动的转身（转身斩、回旋踢、
     * idle 微晃）需要先定义它与该权威的合成方式——叠加为随时间长回视野的偏移，或动画层
     * 活跃时暂停跟随——才能生效；因此本字段被写入后不会改变朝向。
     * <p>
     * 正值 = 逆时针旋转（面向左转，对应 Minecraft yaw 增大方向）。
     */
    @Setter
    private volatile float animRootYawDelta;

    /**
     * 控制器与躯干刚体的分离距离 (m)，用于行走力折减。
     * <p>
     * 由上层控制编排（MechaControl）每物理步更新。分离超过
     * {@link MechaWalkingAttr#SEP_MAX} 时行走力归零，并触发 RAGDOLL 状态切换。
     */
    @Setter
    private volatile float separationDistance;

    /**
     * 逻辑层给出的移动速度修正系数（0.0 ~ 1.8）。
     * <p>
     * 由 {@code MechaControl} 每物理步从状态变量 {@code MOVE_SPEED_MODIFIER} 读入，
     * 等于 {@code posture.speedModifier() × gait.baseSpeedModifier()}。它乘在**控制力**上：
     * 蹲伏、卧倒、闪避、硬直都会按比例改变稳态速率（§7.4 的力平衡给出 {@code v ∝ 倍率}），
     * 同时也在空中限制控制力不得超过地面同倍率下的顶速。
     * <p>
     * 默认 1.0 表示「逻辑层尚未写入时的中性值」，与完全不接逻辑层时的行为一致。
     * 写入侧只接受非负值，钳制规则见 {@link #setMoveSpeedModifier(float)}。
     */
    @Getter
    private volatile float moveSpeedModifier = 1.0f;

    // ── 闪避冲量 ──

    /**
     * 待施加的闪避冲量矢量 {@code I·u} —— 方向是闪避轴 {@code u}（水平单位矢量），模长是这次闪避的
     * 冲量 {@code I}，单位 <b>N·s</b>。
     * <p>
     * {@link #requestDodgeImpulse} 累加写入、{@link #updateWalk} 在下一步消费一次即清零，不随时间衰减。
     * 同一步内的多次请求按矢量累加，因此轴取累加后的方向、冲量取累加后的模长。消费时先按
     * {@code Δv = I / m} 换算成速度增量，再把本步水平速度<b>赋值</b>到该轴上（只保留沿轴的同向分量，
     * 再叠加 Δv），因此同一个操作在越重的机体上得到的速度增量越小，见 {@link #updateWalk}。
     * <p>
     * 只由物理线程读写：写入方是 {@code MechaControl.applyLogicOutputToKcc}，读方是
     * {@code updateWalk}，两者同线程，因此不需要 {@code volatile}。
     */
    private float dodgeImpulseX;
    private float dodgeImpulseZ;
    /** 是否存在待施加的闪避冲量；为 false 时上面两个分量视为 0 */
    private boolean dodgeImpulsePending;

    /** 闪避无敌剩余时长 (s)，物理线程递减 */
    private float dodgeInvulnerableTimer;

    /**
     * 当前是否处于闪避无敌窗口。
     * <p>
     * 伤害系统尚未接入（`docs/总体设计文档.md:27-59` 的伤害转发在阶段 4），因此本标志
     * 目前只被查询、没有消费方；它在伤害路径就位后直接可用。
     */
    @Getter
    private boolean invulnerable;

    // ── 物理线程独占状态 ──

    /**
     * 助推窗口剩余时长 (s)。大于 0 表示窗口活跃（由 {@link #isBoosting()} 镜像给逻辑层）。
     * 窗口按物理步衰减，到 0 自然结束。
     */
    private float jumpBoostRemainingS;

    /**
     * 本物理步注入 {@link #updateWalk} 的垂直助推速度增量 (m/s)。
     * <p>
     * {@link #updateJump} 在本步算出、{@link #updateWalk} 在同一步消费后叠加进垂直速度。
     * 两步同线程、同一步内先后执行，因此不需要 {@code volatile}。
     */
    private float boostDvY;

    /** 本物理步是否走到了一次越障（越障那一步不再施加地面行走力，见 {@link #updateWalk}）。 */
    private boolean steppedUpThisStep;

    /** 当前是否着地（含最小保持，见 {@link #GROUND_MIN_HOLD_STEPS}）。 */
    private boolean grounded;

    /** 着地判据的连续命中计数（正数 = 还在保持窗口内），用于 {@link #GROUND_MIN_HOLD_STEPS}。 */
    private int groundHoldSteps;

    /**
     * 支撑法线（世界坐标，Y 上）。
     * <p>
     * 取本步向下扫掠里通过筛的命中中<b>最竖直</b>的那一条；一条都没有时用 {@code (0, 1, 0)}
     * （平地），因为此时 {@link #grounded} 为假、力律不读它。
     */
    private final Vector3f groundNormal = new Vector3f(0f, 1f, 0f);

    /** 距离地面的间隙 (m)，由向下扫掠的命中比例给出；无命中时为 {@link #GROUND_WINDOW_ABOVE}。 */
    private float groundGap = GROUND_WINDOW_ABOVE;

    // ── 扫掠中间结果（复用，减少物理线程分配） ──

    private final List<PhysicsSweepTestResult> sweepResults = new ArrayList<>();
    private final List<PhysicsRayTestResult> rayResults = new ArrayList<>();

    /**
     * 本物理步实际以中心力施加的水平合力 (N)，诊断用。
     * <p>
     * 它回答的是「为什么速度是这个值」：地面力律算出来的净力、前馈加回去的那一份、以及空中
     * 控制力都汇总在这里。每物理步在 {@link #updateWalk} 里覆写。
     */
    @Getter
    private final Vector3f lastAppliedForce = new Vector3f();

    // ── 临时向量，减少物理线程分配 ──
    private final Vector3f tmp1 = new Vector3f();
    private final Vector3f tmp2 = new Vector3f();
    private final Vector3f tmp3 = new Vector3f();
    private final Vector3f tmp4 = new Vector3f();
    private final Matrix3f tmpMatrix = new Matrix3f();
    private final Quaternion tmpQuat1 = new Quaternion();
    private final Quaternion tmpQuat2 = new Quaternion();

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param shape        胶囊碰撞形状，尺寸应与 {@link MechaBodyPreset} 一致
     * @param physicsSpace 物理空间（用于着地扫掠与越障探针）；入世后会被
     *                     {@link PhysicsCollisionObject#getCollisionSpace()} 取代
     */
    public MechaCharacter(CapsuleCollisionShape shape, PhysicsSpace physicsSpace) {
        super(shape, MechaWalkingAttr.MASS);
        this.fallbackSpace = physicsSpace;
        // 半高取共享常量而非从 shape 推导：胶囊几何的唯一来源是 MechaBodyPreset，
        // 服务端出生点与客户端锚点也用同一组值，避免两处各算一遍
        this.capsuleHalfTotal = MechaBodyPreset.HALF_TOTAL;

        // 重力不写死在这里：刚体的重力是「物理空间重力 × 本体的 gravityScale」，
        // 而物理空间要等入世之后才拿得到（入世前 getCollisionSpace() 为 null）。
        // 未显式设置重力的刚体由引擎按所在空间的重力处理，因此这里留空，
        // 两步之后由 prePhysicsTick 每步按 gravityScale 修正。

        // 接触参数：角色侧摩擦要与地形侧相乘才是引擎使用的组合摩擦（§7.3），
        // 恢复系数取 0（KCC 的幽灵体没有弹性可言，落地不该反弹）
        setFriction(getEffectiveFriction() / MechaWalkingAttr.TERRAIN_FRICTION);
        setRestitution(0f);
        // 阻尼不映射到刚体：速度相关阻力的唯一来源尚未定（§12.5），当前取 0
        setLinearDamping(MechaWalkingAttr.C1);
        setAngularDamping(0f);
        // 姿态自由度：锁 pitch / roll、留 yaw（§4.1）。角因子的切换点只有这一处，
        // 飞行放行那一跳将来落在同一点
        setAngularFactor(new Vector3f(0f, 1f, 0f));
        // 碰撞过滤：只与地形碰撞（§2.2 选择 4）。与躯干的接触由约束层处理，不靠碰撞组
        setCollisionGroup(CollisionGroups.PAWN);
        setCollideWithGroups(CollisionGroups.TERRAIN);
    }

    // ═══════════════════════════════════════════════
    // 输入 API（主线程调用）
    // ═══════════════════════════════════════════════

    /**
     * 设置视野偏航 —— 控制器朝向的权威输入，绝对赋值语义。
     * <p>
     * 写入即生效：本值被记为 {@link #getCurrentYaw()} 的权威，并由下一步的
     * {@link #writeFacing} 落到刚体姿态上（§5.3 的朝向写入每物理步现算，不缓存旋转量）。
     * 调用方（{@code MechaControl.applyFacing}）每个物理步在解算行走方向之前调用一次，
     * 因此同一物理步内的朝向、行走方向与闪避方向取的是同一个角。
     * <p>
     * 死亡（含 ragdoll）时调用方跳过写入，朝向停在最后一帧。
     *
     * @param degrees 视野偏航（度，Minecraft 约定：0 = 南 +Z，90 = 西 −X）
     */
    public void setViewYaw(float degrees) {
        this.viewYaw = (float) Math.toRadians(normalizeViewYaw(degrees));
    }

    /**
     * 把偏航角（度）归约到 [−180, 180)。
     * <p>
     * 归约在度制上做，而不是先转弧度再取模：180 与 360 在 float 里可精确表示，因此 ±180
     * 这类边界值不会因为弧度换算的舍入被判到区间的另一侧（{@code 540°} 与 {@code 180°}
     * 都归到 {@code −180°}）。
     */
    private static float normalizeViewYaw(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }

    /**
     * 设置移动意图（本体坐标系，即玩家视角相对量）。
     * <p>
     * 行走方向的「移动意图 → 世界方向」变换在本方法内完成：意图按写入时的视野偏航旋转后
     * 写入 {@link #inputDirX} / {@link #inputDirZ}，{@link #updateWalk} 只消费结果、自身不做旋转。
     * 朝向因此只被计入一次，且计入的是本步 {@link #setViewYaw} 写下的那个角。（闪避方向由
     * {@code MechaControl.resolveDodgeDirection} 按同一个角另行解出，不叠加到行走方向上，
     * 因此不构成第二次旋转。）
     * <p>
     * 变换与原版 {@code Entity.getInputVector} 同式：
     * <pre>
     *   worldX = strafe·cos(yaw) − forward·sin(yaw)
     *   worldZ = forward·cos(yaw) + strafe·sin(yaw)
     * </pre>
     * 输入约定（与原版 {@code Input.leftImpulse} 一致）：forward 正 = 前进，strafe 正 = <b>左移</b>。
     * 校验：yaw=0（面向南 +Z）按左 → +X（东）；yaw=90（面向西 −X）按左 → +Z（南）。
     * 传入 (0, 0) 表示无移动意图，此时世界方向清零。
     *
     * @param forward 前后意图，[-1, 1]，正 = 前进
     * @param strafe  左右意图，[-1, 1]，正 = 左移
     */
    public void setMoveIntent(float forward, float strafe) {
        float len = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (len < 0.001f) {
            this.inputHasMove = false;
            this.inputDirX = 0;
            this.inputDirZ = 0;
            return;
        }
        float nForward = forward / len;
        float nStrafe = strafe / len;
        float yaw = this.viewYaw;
        float cos = (float) Math.cos(yaw);
        float sin = (float) Math.sin(yaw);
        this.inputDirX = nStrafe * cos - nForward * sin;
        this.inputDirZ = nForward * cos + nStrafe * sin;
        this.inputHasMove = true;
    }

    /**
     * 设置跳跃键状态（主线程调用）。
     * <p>
     * 调用方通过比较上帧/本帧按键状态来设置 released 标记。
     *
     * @param held     本帧是否按住
     * @param released 本帧是否刚松开
     */
    public void setJumpInput(boolean held, boolean released) {
        this.jumpHeld = held;
        if (released) {
            this.jumpReleased = true;
        }
    }

    /**
     * 设置动画根骨骼帧间位移。
     * <p>
     * 由上层控制编排（MechaControl）每物理步调用，写入当前帧与上帧根骨骼世界坐标差。
     * 单位：m/tick。
     * <p>
     * <b>当前未接入运动。</b> 刚体路线上动画位移走标准力 / 冲量通道（§6 的 B3 行），
     * 而「动画位移 → 力」的映射还没有设计；本字段保留为接入点，{@code updateWalk} 不读它。
     *
     * @param dx 世界坐标 X 位移 (m/tick)
     * @param dy 世界坐标 Y 位移 (m/tick)
     * @param dz 世界坐标 Z 位移 (m/tick)
     */
    public void setAnimRootDelta(float dx, float dy, float dz) {
        this.animDeltaX = dx;
        this.animDeltaY = dy;
        this.animDeltaZ = dz;
    }

    /**
     * 设置逻辑层给出的移动速度修正系数。
     * <p>
     * 由上层控制编排（MechaControl）每物理步从状态变量 {@code MOVE_SPEED_MODIFIER} 读入。
     * 它乘在行走净力上，因此蹲伏 / 卧倒 / 闪避 / 硬直都会真正改变实际速度，而不只是
     * 改变逻辑状态。
     * <p>
     * 负数会被钳到 0：本方法只接受「折减或不变」，放大速率由调用方在变量层决定。
     *
     * @param modifier 修正系数，0.0 = 完全无法自主移动，1.0 = 无修正
     */
    public void setMoveSpeedModifier(float modifier) {
        this.moveSpeedModifier = Math.max(0f, modifier);
    }

    /**
     * 按姿态重建碰撞胶囊 —— <b>当前不可用，未接入调用路径</b>（P5 的范围）。
     * <p>
     * 刚体路线解掉了 KCC 那条硬阻塞——{@code PhysicsRigidBody#setCollisionShape} 允许在世换形
     * （`docs/角色控制器-刚体原型与引擎约束.md` §6.2 实测：换形后锁转仍有效、姿态不受扰动），
     * 因此本方法不再受原生断言限制。
     * <p>
     * <b>但接管它需要两件本类还没有的东西</b>：① 变高之前先向上扫掠确认头顶空间（否则站起来
     * 会把人顶进天花板）；② 换形之后按新的半高把脚底重新对齐到换形前的脚底高度，否则胶囊变矮
     * 会把脚埋进地里。两件都是 P5 的内容（`docs/角色控制器-刚体动力学方案.md` §11 的 P5 行），
     * 因此这里保留为返回 {@code false} 的占位，调用方不要接入。
     *
     * @param posture 目标姿态
     * @return 始终 {@code false}
     */
    @Deprecated
    public boolean applyPostureShape(Posture posture) {
        return false;
    }

    /**
     * 诊断用：地面控制力的抓地力上限 (N) 与由力平衡解出的稳态速率 (m/s)。
     * <p>
     * 稳态速率不是被钳制出来的，而是沿向力平衡的解
     * {@code k·min(P/v, F_max)·exp(−(v−v_rated)/λ) = c₀·m·g}（{@code k} 取中性值 1）。
     * 抓地力上限 {@code μ_eff·N} 只钳制**净控制力**，不直接决定顶速：只有当它低于阻力总和时，
     * 角色才连匀速都维持不住。这个方法把两者同时暴露出来，便于在没有可视化时定位
     * 「为什么顶速只有 xx」。
     *
     * @return {@code [抓地力上限 N, 稳态速率 m/s]}
     */
    public float[] debugGripTarget() {
        float slopeCos = Math.abs(groundNormal.y);
        float mass = getMass();
        float normalForce = mass * MechaWalkingAttr.GRAVITY * slopeCos;
        float traction = Math.min(computeDriveForce(0f), getEffectiveFriction() * normalForce);
        float holdForce = MechaWalkingAttr.C0 * mass * MechaWalkingAttr.GRAVITY;
        return new float[]{traction, equilibriumSpeed(holdForce)};
    }

    /**
     * 解出力平衡给出的稳态速率 (m/s)，即 {@code computeDriveForce(v) == holdForce} 的根。
     * <p>
     * 力-速曲线对速率单调不增，因此直接二分。速率区间上界取 {@code 4 × v_rated}：
     * 超速衰减区里曲线按 {@code exp(−Δ/λ)} 退场，四个额定速度之外已低于任何可用的阻力。
     *
     * @param holdForce 需要被控制力抵消的阻力总和 (N)：内阻加可选的坡度分量
     * @return 稳态速率 (m/s)；阻力大到起步力都撑不住时返回 0
     */
    private float equilibriumSpeed(float holdForce) {
        if (holdForce <= EPSILON) return MechaWalkingAttr.V_RATED;
        float lo = 0f;
        float hi = MechaWalkingAttr.V_RATED * 4f;
        if (computeDriveForce(lo) <= holdForce) return 0f;
        for (int i = 0; i < 24; i++) {
            float mid = 0.5f * (lo + hi);
            if (computeDriveForce(mid) > holdForce) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5f * (lo + hi);
    }

    /**
     * 施加一次闪避 —— 把水平速度<b>赋值</b>到闪避轴上，并开启一次无敌窗口。闪避是纯速度操作，
     * 不是一段位移。
     * <p>
     * 由上层控制编排在闪避状态进入的那一物理步调用。<b>给的是冲量，不是速度</b>：矢量
     * {@code I·u} 累加进 {@link #dodgeImpulseX} / {@link #dodgeImpulseZ}，{@link #updateWalk} 在下一步
     * 按 {@code Δv = I / m} 换算（{@code m} 见 {@link #getControllerMass()}）后把速度赋值一次，此后由
     * 通用的力模型接管（地面摩擦由引擎接触摩擦给出、内阻按 c₀·g 扣沿向、空中没有摩擦因此原样保留）。
     * 方向已归一化，长度为零或冲量非正时忽略本次请求。
     * <p>
     * <b>为什么以冲量而不是速度衡量。</b> 与跳跃同构（{@link MechaJumpAttr}）：一次闪避是推进器在极短
     * 时间内给出的冲量，机体得到多少速度取决于它要推动多少质量。因此同一个 {@code I} 在越重的机体上
     * 得到的 Δv 越小——质量是「同样的推进器换多少速度」的比例系数，而不是一个与机体无关的常数。
     * <p>
     * <b>为什么走速度通道而不是位姿写入。</b> 闪避是动量的改写，不是落点的改写：写速度之后，接下来
     * 每一物理步都由求解器按真实接触继续积分，摩擦、坡度、撞墙都在里面；写成位姿则会绕过求解器，
     * 撞墙时把人直接推进墙里（§6.1 的规则 1 反过来用）。
     * <p>
     * <b>赋值规则：只保留沿闪避轴的同向分量。</b> 记闪避轴为 {@code u}、本步读回的水平速度为
     * {@code v = a·u + w}（{@code a = v·u} 带符号，{@code w ⟂ u}），则赋值结果是
     * {@code v' = (max(a, 0) + Δv)·u}：{@code w} 整段抹掉、{@code a} 只在为负时截断为 0。因此闪避
     * 结束后速度只沿闪避方向、速率是 {@code max(a, 0) + Δv}（下限 Δv）；沿原方向跑动时保留全部
     * 同向动量并叠加 Δv（冲刺中闪避得到 {@code |v| + Δv}），方向相反或垂直时直接得到 Δv 的
     * 定向位移。赋值不经过 μ：推进器不受接触面限制，同一次闪避在地面材质上的结果相同。
     * <p>
     * <b>方向与当前速度无关。</b> 调用方（{@code MechaControl.resolveDodgeDirection}）只按输入轴与
     * 本步朝向解出轴，不读速度，因此赋值的方向只由玩家输入决定。
     * <p>
     * <b>位移是派生量。</b> 单次闪避的位移不是设计常量，而是由赋值后的速率与当时的摩擦/输入/
     * 空中与否共同决定的派生量：地面无输入时按引擎摩擦制动、有输入时只扣内阻 {@code c₀·g}、
     * 空中没有摩擦因此更远。调参时应当调冲量 {@code I} 本身。
     * <p>
     * TODO 持续推力：当前只实现一次性赋值。要做出「短促爆发 vs 长时平稳加速」的第二种手感，需要
     * 在 {@link #updateWalk} 的同一个注入点再支持一条「窗口内恒定的加速度」（推力），而不是把它
     * 做成随时间衰减的位移。设计约束见 `docs/角色控制器-行走物理设计.md` §3.8。
     *
     * @param dirX          世界坐标 X 方向（水平，无需预先归一化）
     * @param dirZ          世界坐标 Z 方向（水平，无需预先归一化）
     * @param impulse       闪避冲量 {@code I}（N·s）；速度增量由 {@code I / m} 给出
     * @param invulnerableS 无敌时长 (s)
     */
    public void requestDodgeImpulse(float dirX, float dirZ, float impulse,
                                    float invulnerableS) {
        float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.001f || impulse <= 0f) return;
        float scale = impulse / len;
        this.dodgeImpulseX += dirX * scale;
        this.dodgeImpulseZ += dirZ * scale;
        this.dodgeImpulsePending = true;
        this.dodgeInvulnerableTimer = invulnerableS;
    }

    /**
     * 取走本步待施加的闪避冲量并清零（一次性）。
     * <p>
     * 只由 {@link #updateWalk} 调用一次，因此同一份冲量不会在两个物理步里重复施加。
     *
     * @return {@code true} 表示本步有闪避要施加；返回后 {@link #dodgeImpulseX} /
     * {@link #dodgeImpulseZ} 已清零，调用方应立即读走它们
     */
    private boolean consumeDodgeImpulse() {
        if (!dodgeImpulsePending) return false;
        dodgeImpulsePending = false;
        return true;
    }

    // ═══════════════════════════════════════════════
    // 外部位移与位姿写入
    // ═══════════════════════════════════════════════

    /**
     * 把一个落点与一份速度直接写进刚体 —— 全部位姿写入的唯一底层入口。
     * <p>
     * {@code docs/角色控制器-刚体动力学方案.md` §6.1 的四条规则在这里一次性落实：
     * <ol>
     *   <li><b>速度与位置同一步处理</b>：{@code verticalVelocity} 由调用方给出，写位置的同一步就写</li>
     *   <li><b>落点留净空</b>：本方法不做穿透恢复，落点由调用方给</li>
     *   <li><b>写完 activate()</b>：休眠的刚体不响应位姿写入</li>
     *   <li><b>只碰物理体</b>：本方法不写宿主实体，宿主位置永远只由每 tick 的回写驱动</li>
     * </ol>
     * <p>
     * 速度的两个水平分量保持不变，{@code y} 取 {@code verticalVelocity}。越障落位传 0（不清的话，
     * 下一步的积分会把刚对齐的脚底再拽下去），外部位移落点传 0（原版传送的语义对应物）。
     *
     * @param location         目标胶囊中心（调用方持有，本方法不改写它）
     * @param verticalVelocity 写入的垂直速度 (m/s)
     */
    private void displaceTo(Vector3f location, float verticalVelocity) {
        setPhysicsLocation(location);
        Vector3f velocity = getLinearVelocity(tmp1);
        setLinearVelocity(tmp2.set(velocity.x, verticalVelocity, velocity.z));
        activate();
    }

    /**
     * 外部位移的摄入落点（warp）—— 外部把宿主搬到另一个位置时，把控制器刚体也搬过去。
     * <p>
     * 刚体上收成两项（§6.3）：
     * <table>
     *   <tr><td>落点</td><td>{@code PhysicsRigidBody#setPhysicsLocation}，落点留净空（规则 2）</td></tr>
     *   <tr><td>速度</td><td>{@code setLinearVelocity}：清垂直分量、保留水平动量</td></tr>
     * </table>
     * 三轴同为一个量纲（m/s），因此不存在 KCC 那种「只有整向量为零才清掉垂直通道」的约束。
     * <p>
     * <b>KCC 时代承担过的另外两件事已经移走</b>：动画位移叠加记账随量纲统一消失（§6），而
     * 「终止跳跃助推窗口」与「作废当步输入意图」属于「这次位移的后果」，归
     * {@code common/HostPositionIntake.java#intake} 的采纳分支（§6.3）。
     * <p>
     * <b>每次调用都会终止正在进行的跳跃助推窗口，因此这条路径只允许被真正的位移调用。</b>
     * 把宿主实体的每一次位置写入都当成位移来采纳，助推窗口就活不过一个 tick；
     * {@code common/HostPositionIntake.java} 的四类写入分类正是为此存在的。
     * <p>
     * 入世那一次落点不走本方法：{@code ArmsCore#enterPhysicsSpace} 用
     * {@code PhysicsRigidBody#setPhysicsLocation} 之后再由本类构造器之外的调用方
     * {@code addCollisionObject}。
     *
     * @param location 目标胶囊中心（调用方持有，本方法不改写它）
     */
    public void warp(Vector3f location) {
        displaceTo(location, 0f);
    }

    // ═══════════════════════════════════════════════
    // 物理步回调（物理线程调用）
    // ═══════════════════════════════════════════════

    /**
     * 每物理步调用一次，完成全部行走/跳跃物理计算。
     * <p>
     * 调用顺序：重力调制与角因子 → 位姿写入（朝向 → 收敛）→ 着地判据 → 跳跃 → 行走力与越障 →
     * 无敌计时。位姿写入排在着地判据之前，是因为收敛要基于「本步已写入朝向之后」的姿态算倾斜
     * （§5.3），而着地判据与行走力都读本步姿态。
     *
     * @param dt 物理步长 (s)，通常 1/100
     */
    public void prePhysicsTick(float dt) {
        // 0. 重力：刚体的重力 = 物理空间重力 × gravityScale，空间重力在入世之后才读得到
        applyScaledGravity();

        // 1. 位姿写入（§5.3）：先朝向、后收敛，两者各一次、互不抵消
        //    viewYaw 已由编排层（MechaControl.applyFacing → setViewYaw）在本步之前绝对赋值
        writeFacing();
        settleUpright();

        // 2. 着地判据（§7.7）：向下形状扫掠 + 法线筛 + 最小保持
        updateGround();

        // 3. 跳跃：瞬时冲量（applyCentralImpulse）与助推窗口（逐步 applyCentralForce）
        updateJump(dt);

        // 4. 越障（§6.2）：抬升 → 前移 → 落位，同一物理步内
        steppedUpThisStep = tryStepUp();

        // 5. 行走力：地面按力律、空中按 F_max × AIR_CONTROL（§7.4）
        updateWalk(dt);

        // 6. 无敌计时递减
        updateInvulnerability(dt);
    }

    /** 递减闪避无敌计时。 */
    private void updateInvulnerability(float dt) {
        if (dodgeInvulnerableTimer > 0f) {
            dodgeInvulnerableTimer -= dt;
            invulnerable = true;
            if (dodgeInvulnerableTimer <= 0f) {
                dodgeInvulnerableTimer = 0f;
                invulnerable = false;
            }
        } else {
            invulnerable = false;
        }
    }

    // ═══════════════════════════════════════════════
    // 重力
    // ═══════════════════════════════════════════════

    /**
     * 按 {@link #gravityScale} 把物理空间的重力写进刚体。
     * <p>
     * 刚体的重力是<b>每体</b>参数：未显式设置时引擎按所在空间的重力处理，显式设置之后走本值。
     * 取空间重力而不是常量 {@link MechaWalkingAttr#GRAVITY}，是为了让物理空间自己配置的重力
     * （例如测试台架）生效。
     */
    private void applyScaledGravity() {
        CollisionSpace space = getCollisionSpace();
        if (space instanceof PhysicsSpace physicsSpace) {
            physicsSpace.getGravity(tmp4).multLocal(gravityScale);
            setGravity(tmp4);
            return;
        }
        // 未入世时兜底用常量重力，不读物理空间（读数在原生侧有断言，未入世的刚体上不可靠）
        tmp4.set(0f, -MechaWalkingAttr.GRAVITY * gravityScale, 0f);
        setGravity(tmp4);
    }

    // ═══════════════════════════════════════════════
    // 位姿写入：朝向与落地收敛（§5.3）
    // ═══════════════════════════════════════════════

    /**
     * 朝向写入：绕世界 Y 轴把胶囊局部 +Z 的<b>水平投影</b>转到视野偏航上。
     * <p>
     * 全部输入取自<b>本步开始时的当前姿态</b>，任何一步都不缓存、不复用上一拍的旋转量：
     * 写入是幂等的绝对对齐而不是增量累加，因此本步开始时刚体既吃了上一拍的写入、也吃了求解器
     * 在上一拍注入的角速度，两个误差都从这份当前姿态重新量出来（§5.3 的纪律）。
     * <p>
     * 绕世界 Y 的旋转不改变上轴与竖直方向的夹角，因此它与 {@link #settleUpright} 的收敛写入
     * 互不抵消；顺序上先写朝向、再按写入之后的姿态算倾斜，两个目标的输入姿态因此是同一份。
     * <p>
     * 倾角超过 {@link #FACING_WRITE_TILT_LIMIT} 时跳过：那时局部 +Z 的水平投影趋零、朝向误差
     * 的轴无定义，交给收敛写入先摆正（§5.3）。
     * <p>
     * <b>残留量是步内转动，不是累积误差。</b> 写入把朝向对到目标的下一刻，本步剩余的角速度仍会
     * 把它转走 {@code ω_y·Δt}，直到下一步写回，因此朝向在目标附近以固定小幅摆动，幅度正比于
     * 求解器注入的偏航角速度、上界就是 {@code ω_y·Δt}，不跨步叠加。
     */
    private void writeFacing() {
        float[] rotation = rotationColumns();
        // 局部 +Z 的世界方向
        float forwardX = rotation[6];
        float forwardY = rotation[7];
        float forwardZ = rotation[8];
        // 局部 +Y（上轴）的世界方向
        float upY = rotation[4];

        float tilt = (float) Math.acos(Math.max(-1f, Math.min(1f, upY)));
        if (tilt > FACING_WRITE_TILT_LIMIT) {
            return;
        }

        float targetX = (float) Math.sin(viewYaw);
        float targetZ = (float) Math.cos(viewYaw);

        // 把水平投影转到目标方向：绕世界 Y 轴的有符号夹角，
        // sinθ = (f × t)·ŷ、cosθ = f·t。退化姿态（投影为零）已由上面的倾角判据挡掉
        float cross = forwardZ * targetX - forwardX * targetZ;
        float dot = forwardX * targetX + forwardZ * targetZ;
        float angle = (float) Math.atan2(cross, dot);
        if (Math.abs(angle) < 1.0e-7f) {
            return;
        }

        float half = angle * 0.5f;
        float sinHalf = (float) Math.sin(half);
        tmpQuat1.set(0f, sinHalf, 0f, (float) Math.cos(half));
        getPhysicsRotation(tmpQuat2);
        setPhysicsRotation(tmpQuat1.mult(tmpQuat2));
        activate();
    }

    /**
     * 落地收敛写入：把上轴沿「过它与竖直方向的大圆」拉向竖直，每步转角 {@code λ·tilt}。
     * <p>
     * 旋转轴取 {@code u × (0, 1, 0)}（{@code u} = 刚体局部 +Y 的世界方向，模长恒为
     * {@code sin(tilt)}），因此倾角严格按 {@code tilt ← (1 − λ)·tilt} 衰减，不超调；轴上只用得到
     * {@code tilt} 一个角，{@code acos} / {@code asin} / {@code atan2} 都不出现（§5.3）。
     * <p>
     * 角速度的 pitch / roll 分量按同一系数衰减，<b>yaw 分量不动</b>：落地那一帧自带的角速度
     * 不清掉会与姿态写入打架，而 yaw 分量每步被 {@link #writeFacing} 重设，残留只表现为步内的
     * 那一小段转动（§5.3、§12.8）。
     * <p>
     * 倾角小于 {@link #UPRIGHT_LOCK_RAD} 时清掉全部角速度并把角因子写成 {@code (0, 1, 0)}：
     * 收敛结束，此后一直锁转。角度制与弧度制的换算只在本方法内出现一次。
     */
    private void settleUpright() {
        float[] rotation = rotationColumns();
        float upX = rotation[1];
        float upY = rotation[4];
        float upZ = rotation[7];

        float tilt = (float) Math.acos(Math.max(-1f, Math.min(1f, upY)));
        if (tilt < UPRIGHT_LOCK_RAD) {
            setAngularVelocity(tmp1.set(0f, 0f, 0f));
            setAngularFactor(new Vector3f(0f, 1f, 0f));
            return;
        }

        // 最小旋转：轴 = u × (0,1,0) = (upZ, 0, −upX)，模长恒为 sin(tilt)
        float axisX = upZ;
        float axisZ = -upX;
        float sinTilt = (float) Math.sqrt(axisX * axisX + axisZ * axisZ);
        if (sinTilt > 1.0e-6f) {
            float half = SETTLE_LAMBDA * tilt * 0.5f;
            float scale = (float) Math.sin(half) / sinTilt;
            tmpQuat1.set(axisX * scale, 0f, axisZ * scale, (float) Math.cos(half));
            getPhysicsRotation(tmpQuat2);
            setPhysicsRotation(tmpQuat1.mult(tmpQuat2));
            activate();
        }

        // 角速度：pitch / roll 按 (1 − λ) 衰减，yaw 原样保留
        Vector3f angularVelocity = getAngularVelocity(tmp2);
        setAngularVelocity(tmp3.set(
                angularVelocity.x * (1f - SETTLE_LAMBDA),
                angularVelocity.y,
                angularVelocity.z * (1f - SETTLE_LAMBDA)));
    }

    /** 刚体旋转矩阵的九个分量（列主序），按 {@code [m00, m10, m20, m01, m11, m21, m02, m12, m22]} 展开。 */
    private float[] rotationColumns() {
        getPhysicsRotationMatrix(tmpMatrix);
        return new float[]{
                tmpMatrix.get(0, 0), tmpMatrix.get(1, 0), tmpMatrix.get(2, 0),
                tmpMatrix.get(0, 1), tmpMatrix.get(1, 1), tmpMatrix.get(2, 1),
                tmpMatrix.get(0, 2), tmpMatrix.get(1, 2), tmpMatrix.get(2, 2)
        };
    }

    // ═══════════════════════════════════════════════
    // 着地判据（§7.7）
    // ═══════════════════════════════════════════════

    /**
     * 更新着地状态、支撑法线与离地间隙。
     * <p>
     * 判据的形态取自 KCC（每物理步一次向下形状扫掠、命中即着地），并按 Minie 的
     * {@code BetterCharacterControl#checkOnGround} 补两条：
     * <ol>
     *   <li>竖直速度为正时不判——这一步正在上升，脚下不该算着地（同时封掉
     *       「0.4 m 探针 = 二段跳窗口」）</li>
     *   <li>端点定窗口而不是扫满再比 fraction：起点取胶囊中心 + 0.3、终点取中心 − 0.1，
     *       长度 0.4 正好是引擎下限，而「有任何合法命中」就等于「贴地」</li>
     * </ol>
     * 结果筛：排除自己 → 只留对方组为 {@link CollisionGroups#TERRAIN} → 只留法线竖直分量
     * {@code ≥ }{@link #GROUND_NORMAL_THRESHOLD}。{@code sweepTest} 的结果是<b>无序</b>的
     * （与 {@code rayTest} 不同），因此必须「先筛再取」，不能取 {@code results.get(0)}。
     * <p>
     * 法线取通过筛的那些里最竖直的一条，它有两个消费者：越障判决与力律的 {@code cosθ}。
     * <p>
     * 着地布尔另加最小保持（{@link #GROUND_MIN_HOLD_STEPS}），防止接缝处的一次漏判把
     * {@code ON_GROUND} 翻成假。
     */
    private void updateGround() {
        PhysicsSpace space = resolveSpace();
        if (space == null) {
            grounded = false;
            groundNormal.set(0f, 1f, 0f);
            groundGap = GROUND_WINDOW_ABOVE;
            return;
        }

        // 上升门限取 GROUNDED_VERTICAL_SNAP 而不是严格大于 0：求解器在稳定接触上也会间歇给出
        // 0.0x m/s 的**正**垂直速度（接触迭代的残差），严格大于 0 会让着地在「有支撑」与
        // 「上升中」之间来回翻，地面摩擦随之周期性断开（实测破坏性：速度不再收敛）。
        // 取 0.5 m/s 的门限后，跳跃的起跳速度（8 m/s 量级）照旧封掉，而噪声被滤掉
        boolean rising = getLinearVelocity(tmp1).y > GROUNDED_VERTICAL_SNAP;
        boolean support = false;
        float bestUpY = GROUND_NORMAL_THRESHOLD;
        float bestGap = GROUND_WINDOW_ABOVE;

        if (!rising) {
            Vector3f center = getPhysicsLocation(tmp2);
            Vector3f start = new Vector3f(center.x, center.y + GROUND_WINDOW_ABOVE, center.z);
            Vector3f end = new Vector3f(center.x, center.y - GROUND_WINDOW_BELOW, center.z);

            sweepResults.clear();
            space.sweepTest((ConvexShape) getCollisionShape(),
                    new Transform(start, IDENTITY_ROTATION, Vector3f.UNIT_XYZ),
                    new Transform(end, IDENTITY_ROTATION, Vector3f.UNIT_XYZ),
                    sweepResults, 0f);

            for (PhysicsSweepTestResult result : sweepResults) {
                PhysicsCollisionObject hit = result.getCollisionObject();
                if (hit == this || hit == null) continue;
                if (hit.getCollisionGroup() != CollisionGroups.TERRAIN) continue;
                Vector3f normal = result.getHitNormalLocal(tmp3);
                if (normal.y < GROUND_NORMAL_THRESHOLD) continue;

                support = true;
                float gap = GROUND_WINDOW_ABOVE - result.getHitFraction() * GROUND_PROBE_LENGTH;
                if (normal.y > bestUpY) {
                    bestUpY = normal.y;
                    groundNormal.set(normal.x, normal.y, normal.z);
                    bestGap = gap;
                }
            }
        }

        if (support) {
            groundHoldSteps = GROUND_MIN_HOLD_STEPS;
            grounded = true;
            groundGap = bestGap;
        } else if (groundHoldSteps > 0) {
            // 保持窗口内：仍算着地，但法线停在最后一次有效值上（不更新 groundGap）
            groundHoldSteps--;
            grounded = true;
        } else {
            grounded = false;
        }
    }

    /**
     * 当前是否着地 —— 状态机 {@code ON_GROUND}、力模型与宿主写回共用的唯一来源（§7.7）。
     * <p>
     * 可覆写：它由 {@link #updateGround()} 每物理步写入，测试与将来的腿部子系统可以覆写它来
     * 构造「离地 / 着地」的场景而不必真的把刚体放进物理空间。
     */
    public boolean isOnGround() {
        return grounded;
    }

    /**
     * 当前是否着地，与 {@link #isOnGround()} 同值。
     * <p>
     * <b>只为兼容既有测试类的符号而保留</b>，新代码一律用 {@link #isOnGround()}——
     * {@code onGround()} 这个名字来自 KCC 的 {@code PhysicsCharacter#onGround}，而刚体路线
     * 已经没有那个父类了。测试基线重建（`docs/角色控制器-刚体动力学方案.md` §11 的 P2 行）
     * 之后本方法随之删除。
     *
     * @deprecated 改用 {@link #isOnGround()}
     */
    @Deprecated
    public boolean onGround() {
        return isOnGround();
    }

    /** 支撑法线与竖直方向的夹角的余弦（{@code cosθ}），力律的 {@code N = m·g·cosθ} 用它。 */
    private float supportSlopeCos() {
        return Math.abs(groundNormal.y);
    }

    /**
     * 解析物理空间：优先用刚体自己所属的空间（入世后才有），构造参数作为兜底。
     */
    private PhysicsSpace resolveSpace() {
        CollisionSpace space = getCollisionSpace();
        if (space instanceof PhysicsSpace physicsSpace) {
            return physicsSpace;
        }
        return fallbackSpace;
    }

    // ═══════════════════════════════════════════════
    // 行走力模型（§7.4）
    // ═══════════════════════════════════════════════

    /**
     * 计算行走控制力并施加到质心（物理线程），支持多通道合成。
     * <p>
     * <b>模型是力，不是速度。</b>输入只决定力的方向与大小，本步能改变多少水平速度由
     * {@code a = F/m} 给出，因此速度是一个有惯性的矢量：加速要时间、转向要先把侧向动量
     * 抵掉、起跳时水平速度原样带走。每物理步的流程：
     * <ol>
     *   <li>取走本步的闪避冲量（若有），按 {@code Δv = I / m} 换算后把读回的水平速度<b>赋值</b>到
     *       闪避轴上：只保留沿轴的同向分量，再叠加 Δv</li>
     *   <li>读刚体水平速度矢量。刚体三轴同为 m/s，没有量纲换算，也没有动画位移叠加记账</li>
     *   <li>解出沿输入方向的净控制力：地面 {@code min(力-速曲线, μ_eff·N)}，空中
     *       {@code F_max × AIR_CONTROL}（§7.4、§7.6）</li>
     *   <li>地面再叠加摩擦锥前馈 {@code μ_eff·N·û}，与净力合成后<b>一次</b>
     *       {@code applyCentralForce} 施加（§8.1 的单一施加点）</li>
     *   <li>空中另受「不可加速」约束：控制力可以转向、可以减速，但水平速率不会超过地面同倍率下
     *       能维持的顶速，超出时按比例缩回</li>
     * </ol>
     * <p>
     * <b>摩擦由引擎承担，前馈只把那一份加回去。</b>接触点上求解器给出的切向摩擦反着相对滑动
     * 方向，最多到 {@code μ_eff·N}；前馈按目标移动方向 {@code û} 把这一份额度补上，使实际净力
     * 等于 {@code F_net}。因此施加的峰值是 {@code k·F_MAX + μ_eff·N}，净力仍被截在
     * {@code μ_eff·N} 上（§7.4）。
     * <p>
     * <b>为什么必须有 {@code min(·, μ_eff·N)}。</b>引擎的摩擦只从施加的力里减掉 {@code μ_eff·N}，
     * 它不限制我们能造出多大的净力：去掉这一条，施加 {@code 3μ_eff·N} 就能得到 {@code 2μ_eff·N}
     * 的净力，冰面上照样起步。
     * <p>
     * <b>为什么 {@code N} 取静态估算。</b>{@code N = m·g·cosθ}，不读接触点的瞬时法向冲量：
     * 每步不需要订阅接触事件、不需要遍历全场 manifold。代价见 §7.6，证伪办法是
     * §12.9 的「起步头十步平均加速度」。
     * <p>
     * <b>坡度分量不由力模型扣。</b>刚体上重力是求解器真实施加的力，沿坡分量自己就存在；力模型
     * 再扣一次等于把同一次重力算两遍（§7.2）。内阻仍按 {@code c₀·m·g} 计，不随坡度缩放。
     * <p>
     * 稳态速率不是被钳制出来的，而是力平衡的自然结果：沿向分量上
     * {@code k·min(P/v, F_max)·exp(−(v−v_rated)/λ) = c₀·m·g}，解出 {@code v = k·P/(c₀·m·g)}
     * （{@code k} 见 {@link #controlForceScale()}）。因此缩放控制力就缩放了顶速：蹲伏 0.3 →
     * 1.8 m/s、站立 1.0 → 6.0 m/s。
     */
    private void updateWalk(float dt) {
        // ── 快照 volatile 输入与调制量 ──
        boolean hasInput = inputHasMove;
        float dirX = inputDirX;
        float dirZ = inputDirZ;
        float mass = getControllerMass();
        // 前馈与截断用的法向力按刚体质量算：它描述的是求解器实际承受的重力
        float bodyMass = getMass();
        float forceScale = controlForceScale();

        // 闪避冲量：在下面读速度之前取走。存的是 I·u 本身，因此这里同时解出轴 u（单位）与冲量 I
        // （模长），再按 Δv = I / m 换算成速度增量——质量是这一步的除数，越重的机体拿到越小的 Δv。
        // 模长为零表示本步的请求互相抵消、没有可投影的轴，按未发生处理（无敌窗口已在请求时开启）。
        boolean dodgeThisStep = consumeDodgeImpulse();
        float dodgeAxisX = 0f;
        float dodgeAxisZ = 0f;
        float dodgeDv = 0f;
        if (dodgeThisStep) {
            float ix = dodgeImpulseX;
            float iz = dodgeImpulseZ;
            dodgeImpulseX = 0f;
            dodgeImpulseZ = 0f;
            float mag = (float) Math.sqrt(ix * ix + iz * iz);
            if (mag > EPSILON) {
                dodgeAxisX = ix / mag;
                dodgeAxisZ = iz / mag;
                dodgeDv = mag / mass;
            } else {
                dodgeThisStep = false;
            }
        }

        // ── 读刚体当前水平速度：三个轴同为 m/s ──
        Vector3f velocity = getLinearVelocity(tmp1);
        float vx = velocity.x;
        float vz = velocity.z;

        // ── 闪避赋值：v ← (max(v·u, 0) + Δv)·u，其中 Δv = I / m ──
        // 只保留沿闪避轴的同向分量：垂直于轴的动量在这里被整段抹掉、反向的分量截断为 0，
        // 因此闪避结束后速度只沿闪避方向，结果与进入速度的大小、方向都无关。
        // 赋值不读 μ、也不读输入：推进器不受接触面限制，也不受本步输入方向影响。
        if (dodgeThisStep) {
            float along = vx * dodgeAxisX + vz * dodgeAxisZ;
            if (along < 0f) along = 0f;
            vx = (along + dodgeDv) * dodgeAxisX;
            vz = (along + dodgeDv) * dodgeAxisZ;
            setLinearVelocity(tmp2.set(vx, velocity.y, vz));
        }

        float hSpeed = (float) Math.sqrt(vx * vx + vz * vz);

        // > 0 表示本步在空中，按此速率上限约束控制力
        float airCeiling = 0f;
        // 本步要施加的合力（0 表示没有）
        float forceX = 0f;
        float forceZ = 0f;

        if (grounded && !steppedUpThisStep) {
            // 着地且本步没有越障：走地面力律。
            // 法向力与刹车预算按**刚体质量**算：它们描述求解器实际承受的重力与需要停下的动量
            float slopeCos = supportSlopeCos();
            float traction = getEffectiveFriction() * bodyMass * MechaWalkingAttr.GRAVITY * slopeCos;

            float controlX = 0f;
            float controlZ = 0f;
            if (hasInput) {
                // û 是世界系里的目标移动方向；dirX / dirZ 已由 setMoveIntent 按本步朝向解出。
                // 驱动力沿 û 施加，**不按运动方向投影**：投影会在两个地方出错——静止起步时
                // 投影值恒为 0（|v·û|/|v| 是 0/0），角色永远动不起来；侧向滑行时 v·û ≈ 0，
                // 驱动也会被抹掉。逆向移动时控制力变成制动这一点留给引擎的接触摩擦去表达
                float drive = computeDriveForce(hSpeed) * forceScale;
                if (drive > traction) drive = traction;
                controlX = drive * dirX;
                controlZ = drive * dirZ;
            } else if (hSpeed > EPSILON) {
                // 无输入：引擎的接触摩擦就是刹车，这里不再自己算减速度（§7.1 移出项二）。
                // 但「引擎摩擦反着滑动方向」是负反馈，不能前馈，因此只施加控制力、不加前馈。
                // 上限取「一步之内把动量停住」所需的力，避免大幅超调
                float brake = Math.min(traction, bodyMass * hSpeed / dt);
                controlX = -brake * (vx / hSpeed);
                controlZ = -brake * (vz / hSpeed);
            }

            // 摩擦锥前馈：把求解器在接触点上给出的那份反向摩擦加回去（方向取目标移动方向 û）。
            // 净力因此等于期望净力（引擎摩擦被抵消），而施加值不超过 μN·(1+释放余量)。
            // 是否叠加由 usesFrictionFeedforward() 决定，正在实测判决，见该方法的说明
            if (hasInput && usesFrictionFeedforward()) {
                controlX += traction * (1f + FEEDFORWARD_RELEASE) * dirX;
                controlZ += traction * (1f + FEEDFORWARD_RELEASE) * dirZ;
            }
            forceX = controlX;
            forceZ = controlZ;
        } else if (!grounded && hasInput) {
            // 空中：控制力取 F_max 的一个比例，不扣内阻/坡度——没有蹬地反力，
            // 内阻与坡度都是地面接触现象，扣掉它们会让空中控制力恒为零。
            // 也不加摩擦前馈：没有接触，就没有那份反向摩擦要抵消（§7.4 的「空中」行）
            float control = MechaWalkingAttr.F_MAX * MechaWalkingAttr.AIR_CONTROL * forceScale;
            forceX = control * dirX;
            forceZ = control * dirZ;
            // §3.7「不可加速」：地面能维持的顶速是本步空中控制力的速率天花板
            float groundHold = MechaWalkingAttr.C0 * bodyMass * MechaWalkingAttr.GRAVITY;
            airCeiling = equilibriumSpeed(groundHold) * forceScale;
        }
        // 空中且无输入：不施加任何水平力，速度矢量原样带入下一物理步

        // ── 一次施加：合力（质心）与垂向速度 ──
        lastAppliedForce.set(forceX, 0f, forceZ);
        if (forceX != 0f || forceZ != 0f) {
            applyCentralForce(tmp3.set(forceX, 0f, forceZ));
        }

        // ── 空中天花板（在闪避赋值与力计算之后统一执行）──
        // airCeiling 只压「加速」，不压转向：超限时按比例缩回，方向仍然转了（速率保持，矢量
        // 朝输入方向旋转）；已高于天花板的动量（同向闪避得到的 |v| + Δv、被击飞、从高处冲下）
        // 原样保留
        if (airCeiling > 0f) {
            float ceiling = Math.max(airCeiling, hSpeed);
            Vector3f current = getLinearVelocity(tmp2);
            float speedNow = (float) Math.sqrt(current.x * current.x + current.z * current.z);
            if (speedNow > ceiling && speedNow > EPSILON) {
                float keep = ceiling / speedNow;
                setLinearVelocity(tmp3.set(current.x * keep, current.y, current.z * keep));
            }
        }

        // ── 着地时兜住重力的下向速度 ──
        // 刚体上求解器只约束位置、不回写速度：不清零的话重力每步注入 g·dt，胶囊以每步约 5 mm
        // 的速度持续下沉，直到接触边距被吃满为止。
        // <b>只兜下沉、绝不制造上升。</b>写成 velocity.y + boostDvY 会把求解器在接触上给出的
        // 微小**正**垂直速度（求解器噪声，量级 0.05 m/s）一步步固化成向上的台阶，胶囊随后
        // 真的离地一拍、地面摩擦断一拍、再落回来——实测表现为速度无界的周期性抖动。
        // 因此只有 vy ≤ 本值时才动垂直分量，且此时写成 min(vy, 0) + 助推增量：噪声被抹平成 0，
        // 跳跃的起跳速度（> 本值）与助推增量都不受影响
        if (grounded) {
            float vy = getLinearVelocity(tmp1).y;
            if (vy <= GROUNDED_VERTICAL_SNAP) {
                float settledY = (vy > 0f ? 0f : vy) + boostDvY;
                setLinearVelocity(tmp2.set(vx, settledY, vz));
            } else if (boostDvY != 0f) {
                setLinearVelocity(tmp2.set(vx, vy + boostDvY, vz));
            }
        }
    }

    /**
     * 地面驱动力是否叠加摩擦锥前馈 {@code μ_eff·N·û}。
     * <p>
     * <b>这是一处正在实测判决的开关</b>（`docs/角色控制器-刚体动力学方案.md` §7.4）。引擎对
     * 受力接触体给出的响应不是「从施加的力里减掉 {@code μ_eff·N}」，而是**静摩擦锁定**：
     * 实测施加刚好等于 {@code μ_eff·N} 的力时胶囊纹丝不动（50 步平均加速度 0.0007 m/s²），
     * 而把摩擦设成 0 的同一组立刻回到 4.9 m/s²。前馈因此在低加速区正好把施加力推到锁定点上，
     * 表现为「起步完全不动」。
     * <p>
     * 判决方法是量同一场景在两档下的起步加速度与稳态速率：引擎自己那份摩擦已经承担了前馈想
     * 承担的那件事，前馈若成为第二个 μN 就会被锁定吃掉。
     *
     * @return {@code true} 表示叠加前馈
     */
    protected boolean usesFrictionFeedforward() {
        return true;
    }

    /**
     * 本步控制力的总缩放系数。
     * <p>
     * 三个来源相乘，全部作用在<b>控制力</b>上（而不是净力），因此它们缩放的是稳态速率本身：
     * 均衡条件 {@code k·min(P/v, F_max) = c₀·m·g} 给出 {@code v ∝ k}。
     * <ul>
     *   <li>{@link #inputScale} —— MoLang {@code ctrl.set_input_scale}，0 = 全锁</li>
     *   <li>分离距离折减 —— {@link #separationDistance}，见 {@link MechaWalkingAttr#SEP_MAX}</li>
     *   <li>{@link #moveSpeedModifier} —— 逻辑层姿态/步态倍率</li>
     * </ul>
     */
    private float controlForceScale() {
        float scale = inputScale;
        float sepFactor = 1.0f - separationDistance / MechaWalkingAttr.SEP_MAX;
        if (sepFactor < 0f) sepFactor = 0f;
        return scale * sepFactor * moveSpeedModifier;
    }

    // ═══════════════════════════════════════════════
    // 越障（§6.2）
    // ═══════════════════════════════════════════════

    /**
     * 越障：抬升 → 前移到台阶前缘上方 → 落到顶面，三步在<b>同一个物理步内</b>走完（§6.2）。
     * <p>
     * 为什么必须是位置写入而不是力：胶囊底部是半径 0.4 m 的半球，台阶棱角一旦高于球心，
     * 接触法线的竖直分量就朝下，接触力只能把人顶住。走台阶的判据与常量沿用
     * `docs/角色控制器-刚体原型与引擎约束.md` §10（探针长度、抬升量、净空探针、最小前移）。
     * <p>
     * <b>水平速度原样保留。</b>落位只把胶囊的前缘写到台阶立面上，这一步的积分仍按写入前的
     * 水平速度走，因此多出的 {@code v·Δt} 恰好把胶囊中心送到顶面支撑区里——6 m/s 时是 6 cm，
     * 而贴墙后中心到立面的水平余量是半径 0.4 m，两者相差一个量级。清零水平速度会让人物卡在
     * 台阶前缘并丢掉动量，那正是刚体路线要修掉的那类表现。因此越障<b>不改速度通道</b>，也就
     * 不需要在越障期间把刚体切成运动学。
     * <p>
     * <b>垂直速度必须清零。</b>不清的话，下一步的积分会把判决刚对齐的脚底再拽下去
     * （`docs/角色控制器-刚体原型与引擎约束.md` §4 实测 0.5067 m 的落差）。
     *
     * @return 本步是否完成了一次越障
     */
    private boolean tryStepUp() {
        if (!grounded) return false;

        // 方向：优先用本步输入意图，无输入时用当前水平速度的方向
        Vector3f velocity = getLinearVelocity(tmp1);
        float dirX = inputHasMove ? inputDirX : velocity.x;
        float dirZ = inputHasMove ? inputDirZ : velocity.z;
        float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0e-3f) return false;
        dirX /= len;
        dirZ /= len;
        // 速度过小不值得抬一次：越障是「走向台阶」这件事，不是静止时的坠落
        float projected = velocity.x * dirX + velocity.z * dirZ;
        if (projected < 0.05f) return false;

        PhysicsSpace space = resolveSpace();
        if (space == null) return false;

        Vector3f center = getPhysicsLocation(tmp2);
        float feet = center.y - capsuleHalfTotal;

        Float toFace = probeForward(space, center, feet, dirX, dirZ);
        if (toFace == null) return false;

        Float stepTop = probeStepTop(space, center, feet, dirX, dirZ, toFace);
        if (stepTop == null) return false;

        float rise = stepTop - feet;
        if (rise <= STEP_RISE_MIN || rise > STEP_HEIGHT_BASE) return false;

        if (!hasClearance(space, stepTop, dirX, dirZ, toFace)) return false;

        stepForwardTo(stepTop, dirX, dirZ, toFace);
        return true;
    }

    /**
     * ① 脚底略上方的水平扫掠：前方 {@link #STEP_PROBE_FORWARD} 米内有没有挡路的台阶立面。
     * <p>
     * <b>必须用脚底高度（抬 1 cm）的胶囊 sweep，不能用中心高度的射线。</b>半砖顶面只有 0.5 m、
     * 而胶囊中心在 1.2 m，从中心打出的射线整条从台阶上方穿过去，命中数恒为 0——表现是
     * 「越障永不触发」且没有任何报错（`docs/角色控制器-刚体原型与引擎约束.md` §11.1）。
     * 抬 1 cm 是为了跳过「脚下的地板」：胶囊站在地上时脚底与地面接触，从脚底精确起扫会把地板
     * 当成 0 距离的命中。
     *
     * @return 胶囊中心到台阶立面的水平距离 (m)；前方没有立面或障碍在射程边缘时返回 {@code null}
     */
    private Float probeForward(PhysicsSpace space, Vector3f center, float feet, float dirX, float dirZ) {
        Vector3f start = new Vector3f(
                center.x, feet + STEP_PROBE_LIFT + capsuleHalfTotal, center.z);
        Vector3f end = new Vector3f(
                start.x + dirX * STEP_PROBE_FORWARD, start.y, start.z + dirZ * STEP_PROBE_FORWARD);

        sweepResults.clear();
        space.sweepTest((ConvexShape) getCollisionShape(),
                new Transform(start, IDENTITY_ROTATION, Vector3f.UNIT_XYZ),
                new Transform(end, IDENTITY_ROTATION, Vector3f.UNIT_XYZ),
                sweepResults, 0f);

        // 先筛再取：sweepTest 的结果是无序的，取 get(0) 会得到与地形分块顺序有关的行为
        // （§11.5）。这里只留「法线不是可站立面」的那些，它们是可能的台阶立面
        float bestFraction = Float.POSITIVE_INFINITY;
        for (PhysicsSweepTestResult result : sweepResults) {
            if (result == null || result.getCollisionObject() == this) continue;
            Vector3f normal = result.getHitNormalLocal(tmp3);
            if (Math.abs(normal.y) >= GROUND_NORMAL_THRESHOLD) continue;
            if (result.getHitFraction() < bestFraction) {
                bestFraction = result.getHitFraction();
            }
        }
        if (bestFraction == Float.POSITIVE_INFINITY) return null;
        // 命中比例 1 是「障碍恰好在探针终点」，交给下一步再判；命中比例 0 是<b>合法且常见</b>的
        // 信号（出发即接触），必须限幅而不能判失败（§11.2）
        if (bestFraction >= 1f) return null;

        float gap = Math.max(bestFraction, 0f) * STEP_PROBE_FORWARD;
        return gap + MechaBodyPreset.CAPSULE_RADIUS;
    }

    /**
     * ② 从「脚底 + 步高 + 余量」向下打一条射线，取最近的向上表面作为台阶顶面。
     * <p>
     * <b>起点必须高过台阶顶面。</b>从胶囊中心高度往下打，在台阶还差半步才走到时会先打到脚下的
     * 地板，解出的落差恒为 0，表现与「没探到台阶」完全相同（§11.3）。
     *
     * @param toFace 胶囊中心到台阶立面的水平距离 (m)
     * @return 台阶顶面的世界高度 (m)；没有向上的表面时返回 {@code null}
     */
    private Float probeStepTop(PhysicsSpace space, Vector3f center, float feet,
                               float dirX, float dirZ, float toFace) {
        float probeX = center.x + dirX * toFace;
        float probeZ = center.z + dirZ * toFace;
        Vector3f from = new Vector3f(probeX, feet + STEP_HEIGHT_BASE + STEP_TOP_PROBE_MARGIN, probeZ);
        Vector3f to = new Vector3f(probeX, feet - GROUND_WINDOW_BELOW, probeZ);

        rayResults.clear();
        space.rayTest(from, to, rayResults);
        Float stepTop = null;
        float bestFraction = Float.POSITIVE_INFINITY;
        for (PhysicsRayTestResult hit : rayResults) {
            if (hit == null || hit.getCollisionObject() == this) continue;
            if (hit.getCollisionObject().getCollisionGroup() != CollisionGroups.TERRAIN) continue;
            Vector3f normal = hit.getHitNormalLocal(tmp3);
            if (normal.y < GROUND_NORMAL_THRESHOLD) continue;
            // rayTest 的结果是有序的，但仍按命中比例取最小，避免依赖返回顺序
            if (hit.getHitFraction() < bestFraction) {
                bestFraction = hit.getHitFraction();
                stepTop = from.y + (to.y - from.y) * hit.getHitFraction();
            }
        }
        return stepTop;
    }

    /**
     * ③ 把胶囊抬到台阶顶面之上再水平扫掠一次：仍被挡说明障碍高于步高（或头顶有东西），放弃。
     * <p>
     * 探针长度取 {@link #STEP_CLEARANCE_PROBE}（必须短于前向探针），抬升量取
     * {@link #STEP_LIFT_CLEARANCE}（必须大于胶囊半径，见 §11.4）。
     *
     * @return {@code true} 表示抬起来之后前方畅通
     */
    private boolean hasClearance(PhysicsSpace space, float stepTop, float dirX, float dirZ, float toFace) {
        // 抬起来时纵向不动：判决回答的是「把当前这个水平位置抬到台阶顶面之上还挡不挡」
        Vector3f center = getPhysicsLocation(tmp2);
        Vector3f lifted = new Vector3f(
                center.x, stepTop + STEP_LIFT_CLEARANCE + capsuleHalfTotal, center.z);
        Vector3f end = new Vector3f(
                lifted.x + dirX * STEP_CLEARANCE_PROBE, lifted.y, lifted.z + dirZ * STEP_CLEARANCE_PROBE);

        sweepResults.clear();
        space.sweepTest((ConvexShape) getCollisionShape(),
                new Transform(lifted, IDENTITY_ROTATION, Vector3f.UNIT_XYZ),
                new Transform(end, IDENTITY_ROTATION, Vector3f.UNIT_XYZ),
                sweepResults, 0f);
        for (PhysicsSweepTestResult result : sweepResults) {
            if (result != null && result.getCollisionObject() != this) {
                return false;
            }
        }
        return true;
    }

    /**
     * ④ 落位：脚底对齐台阶顶面，水平只推进到「前缘贴到台阶立面」的那一段。
     * <p>
     * 纵向取原来的水平位置（判决已经确认这个位置抬起来之后畅通），横向取
     * {@code toFace + 半径 + 最小推进量}，即胶囊前缘正好贴住立面再向前一步。
     * 这样落位之后胶囊中心仍在立面之外，顶面留出的支撑区长
     * {@code 2 × 半径 = 0.8 m}，足够承接下一步的积分。
     */
    private void stepForwardTo(float stepTop, float dirX, float dirZ, float toFace) {
        Vector3f center = getPhysicsLocation(tmp2);
        float advance = toFace + MechaBodyPreset.CAPSULE_RADIUS + STEP_MIN_ADVANCE;
        Vector3f target = new Vector3f(
                center.x + dirX * advance, stepTop + capsuleHalfTotal, center.z + dirZ * advance);
        // 规则 1：写位置的同一物理步处理速度。水平分量原样保留（§6.2），垂直清零
        displaceTo(target, 0f);
    }

    // ═══════════════════════════════════════════════
    // 跳跃：瞬时冲量 + 持续助推窗口
    // ═══════════════════════════════════════════════

    /**
     * 更新跳跃的瞬时冲量与助推窗口（物理线程）。
     * <p>
     * 三个阶段：
     * <ol>
     *   <li><b>冲量相</b> —— 着地、不在窗口中且按住时，立即按 {@link #computeJumpImpulse()} 施加
     *       一次 {@code applyCentralImpulse} 并离地，无前置延迟</li>
     *   <li><b>助推窗口</b> —— 保持按住期间，按 {@link #getBoostForce()} 持续施加向上助推，
     *       每物理步注入 {@link #boostDvY} 并衰减 {@link #jumpBoostRemainingS}</li>
     *   <li><b>终止</b> —— 松开、过上止点（{@code v.y ≤ 0}）或达到
     *       {@link MechaJumpAttr#T_BOOST_MAX} 时窗口关闭，之后仅剩重力</li>
     * </ol>
     * <p>
     * <b>冲量不由 {@link #inputScale} 调制。</b>跳跃许可由逻辑层的 {@code CAN_JUMP} 表达，
     * 它只作用于 WASD 连续量，因此 {@code set_input_scale(0)} 不改变跳跃。
     * <p>
     * <b>「先判终止、后施力、再衰减」的顺序</b>保证松开的那一物理步不注入助推；冲量相那一
     * 物理步同时进入窗口并立即参与本步施力与衰减，因此有效助推时长恰为 {@code T_BOOST_MAX}。
     * <p>
     * <b>助推不需要再抬速度上限。</b>KCC 的 {@code m_jumpSpeed} 兼作上升速度上限、会把助推增量
     * 静默钳回起跳速度，因此旧实现要逐步把它抬高；刚体没有这个上限，助推力直接进求解器。
     */
    private void updateJump(float dt) {
        boolean held = jumpHeld;
        boolean released = jumpReleased;
        // 消费释放标记（单帧有效）
        if (released) {
            jumpReleased = false;
        }

        // 冲量相：着地、不在窗口中且按住时立即起跳。起跳速度按冲量换算，并受 v_extend 上限约束
        if (jumpBoostRemainingS <= 0f && grounded && held) {
            float mass = getMass();
            float dv = computeJumpImpulse() / mass;
            float vy = getLinearVelocity(tmp1).y;
            float targetVy = Math.min(vy + dv, getJumpSpeedCap());
            if (targetVy > vy) {
                applyCentralImpulse(tmp2.set(0f, (targetVy - vy) * mass, 0f));
            }
            jumpBoostRemainingS = MechaJumpAttr.T_BOOST_MAX;
        }

        if (jumpBoostRemainingS > 0f) {
            // 助推窗口：松开 / 上止点立即终止；否则本步注入助推并衰减剩余时长
            float vy = getLinearVelocity(tmp1).y;
            if (released || !held || vy <= 0f) {
                jumpBoostRemainingS = 0f;
                boostDvY = 0f;
            } else {
                // 助推是持续的向上**力**，按每步持续施加；这里额外换算一份速度增量，
                // 供 updateWalk 在同一物理步内做「垂直速度最终表达式」的收尾
                applyCentralForce(tmp2.set(0f, getBoostForce(), 0f));
                boostDvY = (getBoostForce() / getMass()) * dt;
                jumpBoostRemainingS -= dt;
            }
        } else {
            boostDvY = 0f;
        }
    }

    /**
     * 当前是否处于助推窗口内（{@code jumpBoostRemainingS > 0}）。
     * <p>
     * 由 {@code MechaControl} 镜像到 {@code KCC_JUMP_BOOSTING}，驱动 air 图的
     * {@code jump_boost} 状态迁移与 {@code MechaControl#forwardInputToKCC} 的 held 旁路。
     */
    public boolean isBoosting() {
        return jumpBoostRemainingS > 0f;
    }

    /**
     * 立即终止助推窗口。
     * <p>
     * 供受击 / 硬直 / 入水等中断源调用。本方法只把窗口剩余时长置 0、不施加任何力，下一物理步
     * {@link #updateJump} 会把 {@link #boostDvY} 一并归零。
     */
    public void cancelBoost() {
        jumpBoostRemainingS = 0f;
    }

    // ═══════════════════════════════════════════════
    // 可覆写定制点（protected）
    // ═══════════════════════════════════════════════

    /**
     * 计算当前水平速率下的总驱动力。
     * <p>
     * 默认实现：本体单源功率-力-速曲线。
     * 子类覆写以实现多源叠加（本体 + 各腿部助力子系统），
     * 每个源自据其 P / v_rated / F_max 独立计算 F_i 后求和。
     * <p>
     * 返回值是<b>净力曲线</b>：它随后被截到摩擦锥 {@code μ_eff·N} 上（§7.4）。
     *
     * @param currentSpeed 当前水平速率 (m/s)，从刚体 {@code getLinearVelocity} 读取
     * @return 总驱动力 (N)
     */
    protected float computeDriveForce(float currentSpeed) {
        // 第一步：功率限力
        float fPower;
        if (currentSpeed < EPSILON) {
            fPower = MechaWalkingAttr.F_MAX; // 除零兜底
        } else {
            fPower = MechaWalkingAttr.P_BASE / currentSpeed;
        }

        // 第二步：力上限钳制
        float fRaw = Math.min(fPower, MechaWalkingAttr.F_MAX);

        // 第三步：超速衰减
        if (currentSpeed <= MechaWalkingAttr.V_RATED) {
            return fRaw;
        }
        return fRaw * (float) Math.exp(-(currentSpeed - MechaWalkingAttr.V_RATED) / MechaWalkingAttr.LAMBDA);
    }

    /**
     * 冲量相的跳跃冲量。
     * <p>
     * 默认实现：素体裸身值 {@link MechaJumpAttr#I_BASE}。
     * 子类覆写以实现多腿叠加：Σ I_per_leg。
     *
     * @return 跳跃冲量 (N·s)
     */
    protected float computeJumpImpulse() {
        return MechaJumpAttr.I_BASE;
    }

    /**
     * 助推窗口内的持续向上助推力。
     * <p>
     * 默认实现：素体裸身值 {@link MechaJumpAttr#F_BOOST}。子类覆写以实现多腿叠加
     * （各腿推力求和）。
     *
     * @return 助推力 (N)
     */
    protected float getBoostForce() {
        return MechaJumpAttr.F_BOOST;
    }

    /**
     * 冲量相的速度增量上限。
     * <p>
     * 只钳制冲量相带来的上升速度，不参与助推窗口。默认返回素体 v_extend，
     * 多腿时子类应覆写为 max(各腿 v_extend)。
     */
    protected float getJumpSpeedCap() {
        return MechaJumpAttr.V_EXTEND;
    }

    /**
     * 组合摩擦系数 {@code μ_eff}（角色侧 × 地形侧）。
     * <p>
     * 默认实现：裸足 {@link MechaWalkingAttr#MU_NAKED} 乘以地形默认值
     * {@link MechaWalkingAttr#TERRAIN_FRICTION}。子类覆写为 {@code max(各腿 μ_foot) × 地形侧}。
     * <p>
     * <b>它是配置约定，不是每拍问引擎要的读数</b>（§7.6 的代价 2）：逐方块材质接入之后
     * （§7.5）它随接触对变，但力律的形式不变。
     * <p>
     * <b>覆写必须在构造期就返回正确值。</b> 本方法有两个调用点：构造器用它反解角色侧的
     * {@code setFriction}（除以地形侧），{@link #updateWalk} 用它算摩擦锥。构造器那一次发生在
     * 子类字段初始化之前，因此覆写体只能用常量或静态量，不能读子类自己的实例字段——那是
     * Java 的「构造器里调用可覆写方法」这一固有约束，不是本类额外加的限制。参数化取值
     * （各腿摩擦）落地时应当改成构造参数，而不是靠覆写。
     *
     * @return 组合摩擦系数
     */
    protected float getEffectiveFriction() {
        return MechaWalkingAttr.MU_NAKED * MechaWalkingAttr.TERRAIN_FRICTION;
    }

    /**
     * 力学质量 —— 就是刚体自己的质量。
     * <p>
     * 刚体路线上质量是唯一的：控制器本身就是那个受力的刚体，{@code Δv = I / m} 里的 {@code m}
     * 与加速度 {@code a = F/m} 里的 {@code m} 是同一个量，不存在旧实现那种「手动积分的质量」
     * 与「碰撞对象的质量」两套值。损伤、换形改变质量就直接改刚体质量。
     * <b>它是可覆写的钩子，但覆写体不能读子类实例字段</b>（构造器会调用它，见
     * {@link #getEffectiveFriction()} 的同一条说明）。默认值就是刚体质量，因此它与碰撞质量
     * 当前是同一个值。
     * <p>
     * 语义上仍有一处未定：{@code Δv = I / m} 该取控制器质量还是（双体接入后的）刚体系统总质量
     * （`docs/角色控制器-刚体动力学方案.md` §12.3）。等躯干刚体接上、约束建立之后再定，
     * 落点就在本方法。
     *
     * @return 控制器质量 (kg)，等于 {@code PhysicsRigidBody#getMass()}
     */
    public float getControllerMass() {
        return getMass();
    }

    // ═══════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════

    /**
     * 当前朝向（弧度，Minecraft 约定：0 = 南 +Z，取值增大 = 向左转）。
     * <p>
     * 返回的是**朝向权威值**，也就是最后一次 {@link #setViewYaw} 写下的那个角。它与刚体姿态上的
     * 朝向是同一个量：{@link #writeFacing} 每物理步把姿态精确对到本值上（§5.3 的朝向写入每步
     * 现算、不缓存），因此读权威值等于读「本步写入之后应该看到」的朝向，且不依赖本步物理是否
     * 已经步进。
     * <p>
     * 不直接从姿态里反解，是因为姿态在两种情形下不等于权威值：刚写入还没步进时（测试与调试里
     * 的常见形态），以及倾角超过 {@link #FACING_WRITE_TILT_LIMIT} 时朝向写入主动让位的那一段。
     * 反解还要处理水平投影趋零的退化姿态。朝向的消费者（同步通道的 {@code DATA_YAW}、
     * {@code MechaControl} 的闪避方向解算）要的都是权威值。
     */
    public float getCurrentYaw() {
        return viewYaw;
    }

    /**
     * 当前水平速度 (m/s)，写入 {@code storeResult} 的 x / z；y 原样保留。
     * <p>
     * 刚体的三个轴统一是 m/s，因此本方法只做一次读取：没有量纲换算，也没有「动画位移叠加
     * 记账」那种影子通道要扣（§6 的量纲统一）。
     * <p>
     * 闪避改写的动量在这一份读数里照实出现——这与「闪避改写了动量」这一事实一致。
     *
     * @param storeResult 存放结果的向量（不为 null）
     */
    public void getHorizontalVelocity(Vector3f storeResult) {
        getLinearVelocity(storeResult);
    }

    /**
     * 当前水平速率 (m/s)，从刚体速度读取。
     *
     * @return 水平速率 (m/s)
     */
    public float getHSpeed() {
        getLinearVelocity(tmp1);
        return (float) Math.sqrt(tmp1.x * tmp1.x + tmp1.z * tmp1.z);
    }

    /**
     * 当前水平速率 (m/s)，与 {@link #getHSpeed()} 同值。
     * <p>
     * <b>只为兼容既有测试类的符号而保留</b>，新代码一律用 {@link #getHSpeed()}——参数
     * {@code dt} 来自 KCC 时代「水平分量是每物理步位移、要除以步长才是速率」那条换算，
     * 刚体上三轴同为 m/s，已经不需要它。测试基线重建（`docs/角色控制器-刚体动力学方案.md`
     * §11 的 P2 行）之后本方法随之删除。
     *
     * @param dt 已忽略
     * @deprecated 改用 {@link #getHSpeed()}
     */
    @Deprecated
    public float getHSpeed(float dt) {
        return getHSpeed();
    }

    /**
     * 离地间隙 (m)：向下扫掠命中的那一份距离，无命中时为窗口上沿。
     * <p>
     * fraction 只用于测距（表现层与落地收敛的触发），<b>不作接受判据</b>（§7.7）。
     */
    public float getGroundGap() {
        return groundGap;
    }

}
