package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.collision.PhysicsRayTestResult;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.objects.PhysicsCharacter;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaJumpAttr;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 机娘运动学角色控制器（对应设计文档：行走物理模型）。
 * <p>
 * 继承 {@link PhysicsCharacter}（Bullet {@code btKinematicCharacterController}），
 * 通过手动积分驱动力模型 → {@code setLinearVelocity} 实现运动。
 * KCC 内部负责碰撞 sweep / 滑墙 / auto-step / 爬坡角限制 / 着地检测。
 * <p>
 * 支持多通道合成：玩家物理输入 + 动画根骨骼位移（animDelta）叠加写入 KCC，
 * 配合 gravityScale / inputScale 参数调制，由外部 MoLang 与 MechaControl 驱动。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>写入方：{@link #setMoveIntent}、{@link #setViewYaw}、{@link #setJumpInput}、
 *       {@link #setGravityScale}、{@link #setInputScale}、{@link #setAnimRootDelta}、
 *       {@link #setSeparationDistance} 写入 volatile 字段。生产路径上这些调用来自物理线程
 *       ——{@code MechaControl.frameLogic} 与 {@code onPhysicsStep} 同线程</li>
 *   <li>物理线程：{@link #prePhysicsTick} 读取 volatile 输入，计算力并施加</li>
 *   <li>主线程：只读出口——同步通道读 {@code getCurrentYaw()} / {@code getPhysicsLocation(null)} /
 *       {@code getLinearVelocity(null)}，不写任何字段</li>
 * </ul>
 * <p>
 * 朝向与移动方向的分工：{@link #setViewYaw} 绝对赋值控制器朝向 {@link #currentYaw}，
 * {@link #setMoveIntent} 接收本体坐标系的移动意图并按该朝向转成世界方向。两者合起来
 * 使 WASD 的「前后左右」始终是当前朝向下的一对轴，且朝向只被计入一次。
 * <p>
 * 可覆写定制点（protected）允许后续接入腿部助力子系统：
 * <ul>
 *   <li>{@link #computeDriveForce} — 当前速率下的总驱动力（含各源力-速曲线）</li>
 *   <li>{@link #computeJumpImpulse} — 当前蓄力比下的跳跃冲量（含多腿叠加）</li>
 *   <li>{@link #getJumpSpeedCap} — 起跳速度硬上限</li>
 *   <li>{@link #getEffectiveFriction} — 有效摩擦系数</li>
 *   <li>{@link #getControllerMass} — 控制器质量</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
public class MechaCharacter extends PhysicsCharacter {

    // ═══════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════

    /** 数值精度阈值 */
    private static final float EPSILON = MechaWalkingAttr.EPSILON;

    /** 地面射线超出胶囊底部的边际 (m) */
    private static final float GROUND_RAY_MARGIN = 0.15f;

    // ═══════════════════════════════════════════════
    // 实例字段
    // ═══════════════════════════════════════════════

    /** 物理空间引用（用于地面射线检测） */
    private final PhysicsSpace physicsSpace;

    /**
     * 胶囊半高 = 圆柱半高 + 半球半径 (m)，用于射线起点计算。
     * <p>
     * 随姿态改变（{@link #applyPostureShape}），因此不能是 {@code final}；
     * 由物理线程写入、物理线程读取，另加 {@code volatile} 是因为换形体也可能发生在
     * 主线程提交的任务里。
     */
    private volatile float capsuleHalfTotal;


    // ── 移动与跳跃输入（volatile，跨线程可见） ──

    /**
     * 本步移动方向的世界坐标 X 分量（已归一化）。
     * <p>
     * 由 {@link #setMoveIntent} 在写入时解出：它把体系移动意图按当时的 {@link #currentYaw}
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

    // ── 动画与参数调制（外部写入，物理线程读取）──

    /** 重力缩放系数，1.0 = 正常重力，0.0 = 浮空。由 MoLang ctrl.set_gravity_scale 改写 */
    @Setter
    private volatile float gravityScale = 1.0f;

    /** 玩家能动性缩放，同时作用于行走净力与跳跃冲量。1.0 = 正常，0.0 = 全锁。由 MoLang ctrl.set_input_scale 改写 */
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
     * 活跃时暂停跟随——才能生效；因此本字段被写入后不会改变 {@link #currentYaw}。
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
     * 蹲伏、卧倒、闪避、硬直都会按比例改变稳态速率（§3.6 的力平衡给出 {@code v ∝ 倍率}），
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
     * 上一步作为「位移叠加」写进 KCC 的水平位移 (m/tick)。
     * <p>
     * 动画根运动是叠加在物理位移之上的**位移**，不是速度；但它和物理位移共用 KCC 的同一个水平
     * 通道（§11），下一步读回来时会被当成速度。若不扣掉，叠加量会逐帧复利。读速度时减去本字段
     * 即恢复真实速度。
     * <p>
     * <b>闪避矢量不在本通道内。</b> 闪避是速度矢量赋值，直接写进 {@link #dodgeImpulseX} 参与速度
     * 合成，不写成位移，因此不参与这份记账。
     */
    private float overlayDispX;
    private float overlayDispZ;

    /** 是否正在蓄力跳跃（KCC 权威状态，vertical 子机镜像它） */
    @Getter
    private boolean chargingJump;
    /** 蓄力计时器 (s) */
    private float chargeTimer;

    /** 当前是否着地 */
    private boolean grounded;

    /** 地面法向量（世界坐标，Y 上），射线检测获取 */
    private final Vector3f groundNormal = new Vector3f(0, 1, 0);

    /**
     * 控制器当前 Y 轴朝向（弧度，Minecraft 约定：0 = 南 +Z，取值增大 = 向左转）。
     * <p>
     * 唯一写入方是 {@link #setViewYaw}：它按视野偏航绝对赋值并归一化到 [−π, π)，因此本字段
     * 有界，且每一步都等于当步写入的视野朝向。死亡（含 ragdoll）时编排层跳过写入，它保留
     * 最后一帧的值。
     * <p>
     * 物理线程写入、主线程读取（同步通道用它填 `DATA_YAW`），因此声明为 {@code volatile}；
     * 读到的值对应某一次完整物理步结束后的结果，不需要额外加锁。
     */
    @Getter
    private volatile float currentYaw;

    // ── 临时向量，减少物理线程分配 ──
    private final Vector3f tmp1 = new Vector3f();
    private final Vector3f tmp2 = new Vector3f();
    private final Vector3f tmp3 = new Vector3f();

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param shape        胶囊碰撞形状，尺寸应与 {@link MechaBodyPreset} 一致
     * @param physicsSpace 物理空间（用于地面射线检测）
     */
    public MechaCharacter(CapsuleCollisionShape shape, PhysicsSpace physicsSpace) {
        super(shape, MechaWalkingAttr.STEP_HEIGHT_BASE);
        this.physicsSpace = physicsSpace;
        // 半高取共享常量而非从 shape 推导：胶囊几何的唯一来源是 MechaBodyPreset，
        // 服务端出生点与客户端锚点也用同一组值，避免两处各算一遍
        this.capsuleHalfTotal = MechaBodyPreset.HALF_TOTAL;

        // KCC 配置
        setGravity(MechaWalkingAttr.GRAVITY);         // 重力加速度
        setMaxSlope(FastMath.QUARTER_PI);             // 初始 45°，有输入时由 μ 动态更新
        setLinearDamping(MechaWalkingAttr.C1);        // 粘滞阻尼完全由 KCC 内部处理
        setFallSpeed(55f);                 // 终端速度，Bullet 默认
        setJumpSpeed(0f);                  // 起跳时按蓄力结果动态设定
        setCollisionGroup(CollisionGroups.PAWN);
        setCollideWithGroups(CollisionGroups.TERRAIN);
    }

    // ═══════════════════════════════════════════════
    // 输入 API（主线程调用）
    // ═══════════════════════════════════════════════

    /**
     * 设置视野偏航 —— 控制器朝向的权威输入，绝对赋值语义。
     * <p>
     * 写入即生效：{@link #currentYaw} 立即等于本值（先归约到 [−180, 180)，见
     * {@link #normalizeViewYaw}），不累积、不插值。调用方（{@code MechaControl.applyFacing}）
     * 每个物理步在解算行走方向之前调用一次，因此同一物理步内的朝向、行走方向与闪避方向
     * 取的是同一个角。
     * <p>
     * 死亡（含 ragdoll）时调用方跳过写入，朝向停在最后一帧。
     *
     * @param degrees 视野偏航（度，Minecraft 约定：0 = 南 +Z，90 = 西 −X）
     */
    public void setViewYaw(float degrees) {
        this.currentYaw = (float) Math.toRadians(normalizeViewYaw(degrees));
    }

    /**
     * 把控制器直接搬到目标位置 —— 外部位移的摄入落点（warp）。
     * <p>
     * <b>重写基类的同名方法，而不是另起一个名字。</b>
     * {@code com.jme3.bullet.objects.PhysicsCharacter#warp} 的语义就是「直接改这个角色的位置」，而移动一个
     * 已入世的 KCC 需要的不只是换位置：速度的垂直分量与<b>本步遗留的施力状态</b>都属于「上一步的落点
     * 上下文」，换落点之后不再成立。两者是这次位移的配套语义，不是某个调用点的私事，因此挂在同一个入口上：
     * 调用方（{@code common/HostPositionIntake.java#runWarpTask}）用基类 API 就拿到完整语义。
     * <p>
     * <b>执行线程</b>：经 {@code SparkLevel#submitImmediateTask} 投递（{@code PPhase.ALL}），因此是物理线程
     * 或主线程；落在主线程时与物理步并发，写的是幽灵体变换，属既定的良性竞态
     * （`docs/宿主位置权威与位移摄入设计.md` §6.1 第 1 行）。
     * <p>
     * <b>不动碰撞形状。</b> {@code super.warp} 即 {@code btKinematicCharacterController#warp}，它只改幽灵体的
     * 世界变换；{@code btKinematicCharacterController#preStep} 在下一次步进时用幽灵体变换刷新
     * {@code m_currentPosition} / {@code m_targetPosition}，因此本步内的 warp 于下一步生效，且不需要重建
     * 控制器（也就不会碰到 {@link #applyPostureShape} 那条会让进程以 {@code 0xC0000409} 中止的换形状路径）。
     * <p>
     * <b>速度：清垂直分量、保留水平分量。</b> 保留水平动量使「跑动中传送」不丢动量；垂直分量清零是原版
     * 传送的语义对应物（原版对实体 {@code deltaMovement} 做 {@code multiply(1, 0, 1)}），但单位不能照抄
     * ——那个字段是每 tick 位移，与 KCC 的水平通道差 {@code 20 × physicsStepSeconds} 倍。因此这里读回
     * 矢量、只把 Y 置零再写回，水平两个分量原样保留。
     * <p>
     * 清零要分两次写：{@code btKinematicCharacterController#setLinearVelocity} 只在整向量为零时把
     * {@code m_verticalVelocity} 归零（该函数的 {@code else} 分支）；有一个水平分量时它改走
     * 「沿上方向的分量」那一支，而 {@code y = 0} 的向量与上方向正交，解出的分量是 0，那一支的赋值因此
     * 不会发生。先写一个零向量把 {@code m_verticalVelocity} 归零，再写水平分量，才符合「清垂直分量」
     * 这条语义。
     * <p>
     * <b>本步遗留的施力状态全部复位。</b> 跳跃蓄力、待发闪避冲量、动画位移叠加记账与当步输入意图都属于
     * 「上一步的落点上下文」，传送后不再成立（§6.1 第 3 行）。其中动画位移叠加记账尤其不能留：
     * {@link #updateWalk} 下一步按 {@code (读回速度 − overlayDisp) / dt} 反解速度，留着会把动画叠加量
     * 误读成真实动量。
     * <p>
     * <b>每次调用都会清掉正在进行的跳跃蓄力，因此这条路径只允许被真正的位移调用。</b>
     * 把宿主实体的每一次位置写入都当成位移来采纳，蓄力就活不过一个 tick；
     * {@code common/HostPositionIntake.java} 的四类写入分类正是为此存在的。
     * <p>
     * 入世那一次落点不走本方法：{@code ArmsCore#enterPhysicsSpace} 用
     * {@code PhysicsCharacter#setPhysicsLocation}。那时 KCC 尚未进物理空间，没有「上一步的落点上下文」
     * 需要复位。
     * <p>
     * <b>本方法会在子类构造完成之前被调用一次。</b>
     * {@code com.jme3.bullet.objects.PhysicsCharacter} 的构造器末尾就是 {@code warp(translateIdentity)}
     * （初始化落点），而 Java 的字段初始化器在父类构造器之后才跑，因此这一次调用里 {@link #tmp1} /
     * {@link #tmp2} / {@link #tmp3} 都还是 {@code null}。速度那一步因此必须用局部向量，不能碰这三个字段；
     * 其余各步只写基本类型字段与 KCC 自身，默认值下与「一次空操作」等价。
     *
     * @param location 目标胶囊中心（调用方持有，本方法不改写它）
     */
    @Override
    public void warp(Vector3f location) {
        // ① 落点：只改幽灵体世界变换
        super.warp(location);

        // ② 速度：清垂直分量、保留水平分量。先写零向量，让 m_verticalVelocity 真正归零。
        //    两个向量都现造：本方法可能在子类字段初始化之前被父类构造器调到（见上）
        Vector3f velocity = getLinearVelocity(new Vector3f());
        velocity.y = 0f;
        setLinearVelocity(new Vector3f(0f, 0f, 0f));
        setLinearVelocity(velocity);

        // ③ 步内遗留的施力状态与影子记账
        chargingJump = false;
        chargeTimer = 0f;
        dodgeImpulseX = 0f;
        dodgeImpulseZ = 0f;
        dodgeImpulsePending = false;
        overlayDispX = 0f;
        overlayDispZ = 0f;

        // ④ 当步输入意图：本步的世界方向是按传送前的落点解出的，直接作废；
        //    下一步 MechaControl 会按新落点重新写入
        inputDirX = 0f;
        inputDirZ = 0f;
        inputHasMove = false;
    }

    /**
     * 设置移动意图（本体坐标系，即玩家视角相对量）。
     * <p>
     * 行走方向的「移动意图 → 世界方向」变换在本方法内完成：意图按 {@link #currentYaw} 旋转后
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
     * 传入 (0, 0) 表示无移动意图，此时世界方向清零（触发 KCC 无输入制动）。
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
        float cos = (float) Math.cos(currentYaw);
        float sin = (float) Math.sin(currentYaw);
        this.inputDirX = nStrafe * cos - nForward * sin;
        this.inputDirZ = nForward * cos + nStrafe * sin;
        this.inputHasMove = true;
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
     * 单位：m/tick（直接与 KCC XZ 位移叠加，无需转换）。
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
     * 按姿态重建碰撞胶囊 —— <b>当前不可用，未接入调用路径</b>。
     * <p>
     * Libbulletjme 的 {@code PhysicsCharacter.setCollisionShape} 明确要求
     * 「the character should not be in any PhysicsSpace while changing shape; the character
     * gets rebuilt on the physics side」（`../Libbulletjme/src/main/java/com/jme3/bullet/objects/PhysicsCharacter.java:342-363`），
     * 方法体里带 {@code assert !isInWorld()}。在物理空间内直接换形状会让原生侧挂接一个未重建的
     * 碰撞对象：release JVM 不检查断言，原生内存随即被破坏，进程以 {@code 0xC0000409}
     * （stack buffer overrun）中止 —— 那是原生 abort，Java 侧捕获不到。
     * <p>
     * 先 {@code removeCollisionObject} → 换形状 → {@code addCollisionObject} 也不成立：实测
     * 幽灵体内部状态被重置（{@code onGround()} 失效、姿态回落到 {@code air}），且下一次换形状
     * 仍然中止。因此本方法保留为「已尝试且失败的路径」的记录，调用方不要接入。
     * <p>
     * 要让蹲伏 / 卧倒真正拥有低矮轮廓，需要换一条不触碰在世 KCC 形状的路径（重建 KCC 实例、
     * 或为姿态单独挂一个碰撞代理体），属于素体定义（阶段 4.6）的范围。
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
     * {@code k·min(P/v, F_max)·exp(−(v−v_rated)/λ) = (c₀ + sinθ)·m·g}（{@code k} 取中性值 1）。
     * 抓地力上限 {@code μ·N} 只钳制**控制力**，不直接决定顶速：只有当它低于阻力总和时，
     * 角色才连匀速都维持不住。这个方法把两者同时暴露出来，便于在没有可视化时定位
     * 「为什么顶速只有 xx」。
     *
     * @return {@code [抓地力上限 N, 稳态速率 m/s]}
     */
    public float[] debugGripTarget() {
        float slopeCos = Math.abs(groundNormal.y);
        float sinTheta = (float) Math.sqrt(Math.max(0f, 1f - slopeCos * slopeCos));
        float mass = getControllerMass();
        float normalForce = mass * MechaWalkingAttr.GRAVITY * slopeCos;
        float traction = Math.min(computeDriveForce(0f), getEffectiveFriction() * normalForce);
        float holdForce = (MechaWalkingAttr.C0 + sinTheta) * mass * MechaWalkingAttr.GRAVITY;
        return new float[]{traction, equilibriumSpeed(holdForce)};
    }

    /**
     * 解出力平衡给出的稳态速率 (m/s)，即 {@code computeDriveForce(v) == holdForce} 的根。
     * <p>
     * 力-速曲线对速率单调不增，因此直接二分。速率区间上界取 {@code 4 × v_rated}：
     * 超速衰减区里曲线按 {@code exp(−Δ/λ)} 退场，四个额定速度之外已低于任何可用的阻力。
     *
     * @param holdForce 需要被控制力抵消的阻力总和 (N)：内阻 + 坡度分量
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
     * 通用的力模型接管（地面摩擦按 μ·g 抹侧向、内阻按 c₀·g 扣沿向、空中没有摩擦因此原样保留）。
     * 方向已归一化，长度为零或冲量非正时忽略本次请求。
     * <p>
     * <b>为什么以冲量而不是速度衡量。</b> 与跳跃同构（{@link MechaJumpAttr}）：一次闪避是推进器在极短
     * 时间内给出的冲量，机体得到多少速度取决于它要推动多少质量。因此同一个 {@code I} 在越重的机体上
     * 得到的 Δv 越小——质量是「同样的推进器换多少速度」的比例系数，而不是一个与机体无关的常数。
     * <p>
     * <b>为什么走速度通道而不是位移叠加通道。</b> 水平通道的语义是「本步速度的最终表达式」，由
     * {@link #updateWalk} 每步 read-modify-write 维护：它先读回上一步的值，再重算并覆写。因此
     * 写进这个表达式的赋值会被下一步当作真实动量读回并继续参与积分，不会被丢弃；而位移叠加量
     * 则必须另立一份记账（{@link #overlayDispX}）才能在读速度时扣掉。闪避走速度通道因此更简单，
     * 也让它与抓地力、内阻、空中天花板共用同一份预算。
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
     * 空中与否共同决定的派生量：地面无输入时按 {@code μ·g} 制动、有输入时只扣内阻 {@code c₀·g}、
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
    // 物理步回调（物理线程调用）
    // ═══════════════════════════════════════════════

    /**
     * 每物理步调用一次，完成全部行走/跳跃物理计算。
     * <p>
     * 调用顺序：动画根旋转 → 重力调制 → 地面检测 → 跳跃更新 → 行走力计算与施加。
     *
     * @param dt 物理步长 (s)，通常 1/20
     */
    public void prePhysicsTick(float dt) {
        // 朝向不在这里推进：currentYaw 已由编排层（MechaControl.applyFacing → setViewYaw）按
        // 视野偏航绝对赋值，且早于本步的 setMoveIntent 解算

        // 1. 重力调制 — 每步按 gravityScale 动态缩放 KCC 重力加速度
        setGravity(MechaWalkingAttr.GRAVITY * gravityScale);

        // 2. 地面检测（KCC 着地 + 射线法线）
        updateGround();

        // 3. 跳跃蓄力与施放
        updateJump(dt);

        // 4. 行走力计算与施加（含 inputScale、animDelta、分离距离折减、闪避矢量赋值）
        updateWalk(dt);

        // 5. 无敌计时递减
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
    // 地面检测
    // ═══════════════════════════════════════════════

    /**
     * 更新着地状态与地面法线。
     * <p>
     * KCC 的 {@link #onGround()} 提供着地布尔值，但不暴露法线。
     * 因此额外使用射线检测获取法线，用于坡度力计算。
     */
    private void updateGround() {
        grounded = onGround(); // KCC 内建着地检测

        // 射线检测获取地面法线
        Vector3f center = getPhysicsLocation(tmp1);
        float rayFromY = center.y - capsuleHalfTotal;
        Vector3f rayFrom = tmp2.set(center.x, rayFromY, center.z);
        Vector3f rayTo = tmp3.set(center.x, rayFromY - GROUND_RAY_MARGIN, center.z);

        List<PhysicsRayTestResult> results = physicsSpace.rayTest(rayFrom, rayTo);
        groundNormal.set(0, 1, 0); // 默认水平面

        if (results != null) {
            for (PhysicsRayTestResult result : results) {
                PhysicsCollisionObject hit = result.getCollisionObject();
                if (hit == this) continue; // 排除自身幽灵体
                result.getHitNormalLocal(groundNormal);
                break; // 取最近命中
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 行走力模型
    // ═══════════════════════════════════════════════

    /**
     * 计算行走控制力并施加位移（物理线程），支持多通道合成。
     * <p>
     * <b>模型是控制力，不是速度。</b>输入只决定控制力的方向与大小，本步能改变多少水平速度
     * 由 {@code a = F/m} 决定，因此速度是一个有惯性的矢量：加速要时间、转向要先把侧向动量
     * 抵消掉、起跳时水平速度原样带走。每物理步的流程：
     * <ol>
     *   <li>取走本步的闪避冲量（若有），按 {@code Δv = I / m} 换算后把读回的水平速度<b>赋值</b>到
     *       闪避轴上：只保留沿轴的同向分量，再叠加 Δv</li>
     *   <li>读 KCC 水平速度矢量，按<b>输入方向</b>分解为「沿向分量」与「侧向分量」</li>
     *   <li>解出控制力：地面 {@code min(力-速曲线, μN)}，空中 {@code F_max × AIR_CONTROL}
     *       （§3.5、§3.7）</li>
     *   <li>沿向分量：控制力积分 {@code v += (F/m)·dt}，再扣内阻与坡度分量（§3.6，不反向）</li>
     *   <li>侧向分量：地面摩擦按 {@code μ·g·cosθ} 抵消（§3.5 的抓地力在侧向上的表现）；
     *       空中不抵消——这正是「空中难以变向」的力学来源</li>
     *   <li>空中另受「不可加速」约束（§3.7）：控制力可以转向、可以减速，但水平速率不会
     *       超过地面同倍率下能维持的顶速，超出时按比例缩回（方向仍然转过去了）</li>
     *   <li>合成回速度矢量 → {@code setLinearVelocity}（动画根运动作为位移叠加在 XZ 上）</li>
     * </ol>
     * <p>
     * <b>闪避在第 1 步把水平速度赋值到闪避轴上，它不是位移叠加。</b> 赋值结果是
     * {@code (max(v·u, 0) + Δv)·u}，其中 {@code u} 是闪避轴、{@code Δv = I / m} 由冲量与质量给出
     * （见 {@link #requestDodgeImpulse} 与 {@link #getControllerMass()}），此后与
     * 普通动量同权：地面摩擦按 μ 抹侧向、内阻按 c₀ 扣沿向、空中因为没有摩擦而原样保留
     * （空中闪避因此比地面更远）。赋值点必须在 {@code hSpeed} 计算之前，否则空中天花板会按不含
     * 本次闪避的旧速率把它缩回去。
     * <p>
     * 稳态速率不是被钳制出来的，而是力平衡的自然结果：沿向分量上
     * {@code k·min(P/v, F_max)·exp(−(v−v_rated)/λ) = c₀·m·g + m·g·sinθ}，
     * 解出 {@code v = k·P/(c₀·m·g)}（{@code k} 见 {@link #controlForceScale()}）。
     * 因此缩放控制力就缩放了顶速：蹲伏 0.3 → 1.8 m/s、站立 1.0 → 6 m/s（v_ref）。
     * <p>
     * 粘滞阻尼（c₁）由 KCC 内部 {@code setLinearDamping} 自动处理，不在此处手动乘衰减因子。
     * <p>
     * 动画根运动叠加：物理位移（m/tick）与 animDelta（m/tick）直接相加写入 XZ；
     * Y 分量 animDeltaY 需 /dt 转为 m/s 写入 KCC 垂直速度。
     * <p>
     * <b>TODO 已知缺陷：撞墙时速度不会归零。</b> KCC 的水平通道是「本步位移命令」，原生侧从不把
     * 实际走了多远写回 {@code m_walkDirection}——{@code btKinematicCharacterController.cpp#stepForwardAndStrafe}
     * 只改 {@code m_currentPosition} / {@code m_targetPosition}，{@code #getLinearVelocity} 是纯字段读出，
     * JNI 也只是转发。因此上面读回的 {@code vx} / {@code vz} 在撞墙时仍是一份没能执行的命令：角色顶在
     * 墙上时它按地面摩擦逐帧衰减到一个恒定的小推力，位置却一步都不动；障碍一消失（例如跳过去），
     * 这份被完整保留的命令会在<b>一个物理步内</b>把位置推满，表现为「从 0 直接加到满速」。
     * <p>
     * 已向上游报告并附实测数据：`https://github.com/stephengold/Libbulletjme/issues/58`。修复方向是
     * 用<b>位置差分</b>观测引擎实际达成的水平位移，把没能兑现的那部分从幽灵速度里扣掉；需要注意
     * 判据不能只看「位移变小」——自动上台阶由 {@code #stepUp} 先抬高胶囊再做水平 sweep，越障成功时
     * 水平位移是全量的，因此「位移短少」才是有区分度的信号（贴墙时短少为 100%）。
     */
    private void updateWalk(float dt) {
        // ── 快照 volatile 输入与调制量 ──
        boolean hasInput = inputHasMove;
        float dirX = inputDirX;
        float dirZ = inputDirZ;
        float adx = animDeltaX;
        float ady = animDeltaY;
        float adz = animDeltaZ;
        float mu = getEffectiveFriction();
        float mass = getControllerMass();
        float slopeCos = Math.abs(groundNormal.y); // cos(θ)
        float sinTheta = (float) Math.sqrt(Math.max(0f, 1f - slopeCos * slopeCos));

        // > 0 表示本步在空中，按此速率上限约束控制力（在空中且有输入的分支里赋值）
        float airCeiling = 0f;

        // 闪避冲量：在下面读速度之前取走。存的是 I·u 本身，因此这里同时解出轴 u（单位）与冲量 I（模长），
        // 再按 Δv = I / m 换算成速度增量——质量是这一步的除数，越重的机体拿到越小的 Δv。
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

        // ── 读 KCC 当前水平速度矢量：XZ 是位移 (m/tick)，÷dt → m/s；Y 已是 m/s ──
        // 位移叠加通道（动画根运动）要先扣掉：它是位移，不是速度
        Vector3f vel = getLinearVelocity(tmp1);
        float vx = (vel.x - overlayDispX) / dt;
        float vz = (vel.z - overlayDispZ) / dt;

        // ── 闪避赋值：v ← (max(v·u, 0) + Δv)·u，其中 Δv = I / m ──
        // 只保留沿闪避轴的同向分量：垂直于轴的动量在这里被整段抹掉、反向的分量截断为 0，
        // 因此闪避结束后速度只沿闪避方向，结果与进入速度的大小、方向都无关。
        // 赋值不读 μ、也不读输入：推进器不受接触面限制，也不受本步输入方向影响。
        if (dodgeThisStep) {
            float along = vx * dodgeAxisX + vz * dodgeAxisZ;
            if (along < 0f) along = 0f;
            vx = (along + dodgeDv) * dodgeAxisX;
            vz = (along + dodgeDv) * dodgeAxisZ;
        }

        float hSpeed = (float) Math.sqrt(vx * vx + vz * vz);
        float verticalVel = vel.y; // 无动画位移时透传，以免重置重力累积

        // Y 分量：动画位移需 /dt 转为 m/s
        float yVel = (Math.abs(ady) > EPSILON) ? (ady / dt) : verticalVel;

        if (hasInput) {
            // dirX / dirZ 已经是世界方向（setMoveIntent 内按本步 currentYaw 解出），
            // 这里再旋转一次就会把朝向计入两遍

            // ── 按输入方向分解当前速度：沿向 + 侧向 ──
            float vAlong = vx * dirX + vz * dirZ;
            float latX = vx - vAlong * dirX;
            float latZ = vz - vAlong * dirZ;

            // ── 控制力的大小（沿输入方向）──
            float forceScale = controlForceScale();
            float fControl;
            // > 0 表示本步在空中，按此速率上限约束控制力；0 表示不施加天花板
            // （地面，或无输入——无输入时控制力为 0，没有需要约束的加速）
            airCeiling = 0f;
            if (grounded) {
                setMaxSlope((float) Math.atan(mu)); // §8.1：有输入时爬坡角随 μ 更新
                // 力-速曲线（定制点，含多源叠加），再按抓地力钳制
                float fDrive = computeDriveForce(hSpeed) * forceScale;
                fControl = Math.min(fDrive, mu * mass * MechaWalkingAttr.GRAVITY * slopeCos);
            } else {
                // 空中：控制力取 F_max 的一个比例，不扣内阻/坡度——没有蹬地反力，
                // 内阻与坡度都是地面接触现象，扣掉它们会让空中控制力恒为零
                fControl = MechaWalkingAttr.F_MAX * MechaWalkingAttr.AIR_CONTROL * forceScale;
                // §3.7「不可加速」：地面能维持的顶速是本步空中控制力的速率天花板
                float groundHold = (MechaWalkingAttr.C0 + sinTheta) * mass * MechaWalkingAttr.GRAVITY;
                airCeiling = equilibriumSpeed(groundHold) * forceScale;
            }

            // ── ① 控制力积分：v += (F/m)·dt ──
            vAlong += (fControl / mass) * dt;

            if (grounded) {
                // ── ② 内阻与坡度：抵消沿向运动，不反向 ──
                // F_resist = c₀·m·g（§3.6）、F_slope = m·g·sinθ；合成减速度 (c₀ + sinθ)·g
                // 与质量无关，但均衡速率 v = k·P/(c₀·m·g) 随质量下降——「越重越慢」在此体现
                float holdDecel = (MechaWalkingAttr.C0 + sinTheta) * MechaWalkingAttr.GRAVITY;
                float alongAbs = Math.abs(vAlong);
                if (alongAbs > EPSILON) {
                    float reduced = alongAbs - Math.min(alongAbs, holdDecel * dt);
                    vAlong = vAlong < 0f ? -reduced : reduced;
                } else {
                    vAlong = 0f;
                }

                // ── ③ 侧向抓地：静摩擦把「不沿输入方向」的速度按 μ·g·cosθ 抵消 ──
                // 只作用于侧向分量，沿向的稳态速率因此仍由力平衡决定，不被摩擦污染；
                // 预算与无输入刹车同一个减速度（§3.9），不另设参数
                float latSpeed = (float) Math.sqrt(latX * latX + latZ * latZ);
                if (latSpeed > EPSILON) {
                    float keep = (latSpeed - Math.min(latSpeed, mu * MechaWalkingAttr.GRAVITY * slopeCos * dt))
                            / latSpeed;
                    latX *= keep;
                    latZ *= keep;
                } else {
                    latX = 0f;
                    latZ = 0f;
                }
            }
            // 空中不抵消侧向分量：水平动量原样保留，转向只能靠上面那点控制力慢慢掰

            vx = vAlong * dirX + latX;
            vz = vAlong * dirZ + latZ;
        } else if (grounded) {
            // ── 无输入制动（仅地面）──
            // 主动摩擦刹车：减速度 = μ × g × cos(θ)，与质量无关。
            // μ 高（粗糙地面）→ 急停，μ 低（冰面）→ 长距离滑行。
            // 粘滞阻尼 c₁ 由 KCC setLinearDamping 叠加提供空气阻力级衰减。
            // 空中不做任何水平制动：没有地面接触就没有摩擦，水平速度原样保留
            // （跳跃因此继承起跳时的水平速度，落点可预测）。
            float brakeDecel = mu * MechaWalkingAttr.GRAVITY * slopeCos;
            float newSpeed = hSpeed - brakeDecel * dt;
            if (newSpeed > EPSILON) {
                float keep = newSpeed / hSpeed; // hSpeed > EPSILON 保证非零
                vx *= keep;
                vz *= keep;
            } else {
                vx = 0f;
                vz = 0f;
            }
        }
        // 空中且无输入：不施加任何水平力，速度矢量原样带入下一物理步

        // ── 空中天花板（在三个分支之后统一执行）──
        // 必须排在闪避赋值之后：ceiling 取 max(airCeiling, hSpeed)，而 hSpeed 取的是赋值后的速率，
        // 因此赋值的结果会成为天花板本身、被原样保留。若把赋值放到这里之后，下一步 hSpeed 仍是不含
        // 本次闪避的旧值，赋值后的速率会被 ceiling/speedNow 静默缩掉（量级 Δv → 0.47 m/s）。
        // airCeiling 只压「加速」，不压转向：超限时按比例缩回，方向仍然转了（速率保持，矢量
        // 朝输入方向旋转）；已高于天花板的动量（同向闪避得到的 |v| + Δv、被击飞、从高处冲下）原样保留。
        if (airCeiling > 0f) {
            float ceiling = Math.max(airCeiling, hSpeed);
            float speedNow = (float) Math.sqrt(vx * vx + vz * vz);
            if (speedNow > ceiling && speedNow > EPSILON) {
                float keep = ceiling / speedNow;
                vx *= keep;
                vz *= keep;
            }
        }

        // 动画位移是叠加在水平位移上的**位移**（m/tick），不属于速度积分：物理位移 (m/s → m/tick)
        // 与它直接相加。记下本步的叠加量，下一步读速度时扣掉，否则它会被当成速度逐帧复利。
        // 闪避不在这里：它是速度矢量赋值，已在上面的 vx / vz 里。
        overlayDispX = adx;
        overlayDispZ = adz;
        setLinearVelocity(tmp2.set(
                vx * dt + overlayDispX, yVel, vz * dt + overlayDispZ));
    }

    /**
     * 本步控制力的总缩放系数。
     * <p>
     * 四个来源相乘，全部作用在<b>控制力</b>上（而不是净力），因此它们缩放的是稳态速率本身：
     * 均衡条件 {@code k·min(P/v, F_max) = c₀·m·g + m·g·sinθ} 给出 {@code v ∝ k}。
     * <ul>
     *   <li>{@link #inputScale} —— MoLang {@code ctrl.set_input_scale}，0 = 全锁</li>
     *   <li>跳跃蓄力折减 —— 蓄力比线性折减，见 {@link MechaJumpAttr#CHARGE_WALK_PENALTY}</li>
     *   <li>分离距离折减 —— {@link #separationDistance}，见 {@link MechaWalkingAttr#SEP_MAX}</li>
     *   <li>{@link #moveSpeedModifier} —— 逻辑层姿态/步态倍率</li>
     * </ul>
     */
    private float controlForceScale() {
        float scale = inputScale;
        if (chargingJump) {
            float ratio = Math.min(chargeTimer / MechaJumpAttr.T_CHARGE, 1.0f);
            scale *= 1.0f - ratio * MechaJumpAttr.CHARGE_WALK_PENALTY;
        }
        float sepFactor = 1.0f - separationDistance / MechaWalkingAttr.SEP_MAX;
        if (sepFactor < 0f) sepFactor = 0f;
        return scale * sepFactor * moveSpeedModifier;
    }

    // ═══════════════════════════════════════════════
    // 跳跃蓄力模型
    // ═══════════════════════════════════════════════

    /**
     * 更新跳跃蓄力状态，并在松开时通过 KCC 内建 {@link #jump()} 施放（物理线程）。
     * <p>
     * 跳跃冲量受 {@link #inputScale} 调制：inputScale = 0 时跳跃被完全禁止。
     */
    private void updateJump(float dt) {
        boolean held = jumpHeld;
        boolean released = jumpReleased;
        // 消费释放标记（单帧有效）
        if (released) {
            jumpReleased = false;
        }

        if (!chargingJump) {
            // 空闲状态：按下开始蓄力（须着地、且 inputScale > 0 允许跳跃）
            if (held && grounded && inputScale > EPSILON) {
                chargingJump = true;
                chargeTimer = 0f;
            }
        } else {
            if (released) {
                // 松开：按当前蓄力比施放冲量，受 inputScale 调制
                float ratio = Math.min(chargeTimer / MechaJumpAttr.T_CHARGE, 1.0f);
                float impulse = computeJumpImpulse(ratio) * inputScale;
                float vRaw = impulse / getControllerMass();
                float vTakeoff = Math.min(vRaw, getJumpSpeedCap());

                // 使用 KCC 内建跳跃，保证水平速度保留
                setJumpSpeed(vTakeoff);
                jump();

                chargingJump = false;
                chargeTimer = 0f;

            } else if (!held || !grounded) {
                // 中断：松键后重置、或离地
                chargingJump = false;
                chargeTimer = 0f;

            } else {
                // 持续蓄力
                chargeTimer += dt;
            }
        }
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
     *
     * @param currentSpeed 当前水平速率 (m/s)，从 KCC {@code getLinearVelocity} 读取
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
     * 计算跳跃冲量。
     * <p>
     * 默认实现：素体裸身线性蓄力曲线。
     * 子类覆写以实现多腿叠加：Σ I_per_leg(ratio)，取 v_extend 最大值作为硬上限。
     *
     * @param chargeRatio 蓄力比 [0, 1]
     * @return 跳跃冲量 (N·s)
     */
    protected float computeJumpImpulse(float chargeRatio) {
        return MechaJumpAttr.I_MIN + (MechaJumpAttr.I_MAX - MechaJumpAttr.I_MIN) * chargeRatio;
    }

    /**
     * 起跳速度硬上限。
     * <p>
     * 默认返回素体 v_extend。多腿时子类应覆写为 max(各腿 v_extend)。
     */
    protected float getJumpSpeedCap() {
        return MechaJumpAttr.V_EXTEND;
    }

    /**
     * 有效地面摩擦系数。
     * <p>
     * 默认返回裸足 μ_naked。子类覆写为 max(各腿 μ_foot)。
     */
    protected float getEffectiveFriction() {
        return MechaWalkingAttr.MU_NAKED;
    }

    /**
     * 控制器质量。
     * <p>
     * 只参与手动积分，不参与 KCC 刚体（KCC 是幽灵体无质量），是三条冲量/力换算的共同除数：
     * 行走 {@code a = F/m}、跳跃 {@code v_takeoff = I_jump/m}（见 {@code updateJump}）、
     * 闪避 {@code Δv = I_dodge/m}（见 {@link #requestDodgeImpulse}）。子类覆写返回机体总质量。
     */
    protected float getControllerMass() {
        return MechaWalkingAttr.MASS;
    }

    // ═══════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════

    /**
     * 当前水平速度 (m/s)，写入 {@code storeResult} 的 x / z；y 原样保留 KCC 的垂直速度。
     * <p>
     * KCC 的水平通道存的是**每物理步位移** (m/tick)，且其中混有动画根运动的位移叠加量
     * （见 {@link #overlayDispX}）。本方法把叠加量扣掉再除以 dt，因此给出的是真正参与控制力积分的速度。
     * <p>
     * 闪避矢量<b>不</b>扣除：闪避直接赋值的就是真实速度，因此逻辑层看到的 {@code SPEED} 会在闪避那一步
     * 变成 {@code max(a, 0) + Δv}，并在其后按地面摩擦衰减——这与「闪避改写了动量」这一事实一致。
     *
     * @param storeResult 存放结果的向量（不为 null）
     * @param dt          物理步长 (s)
     */
    public void getHorizontalVelocity(Vector3f storeResult, float dt) {
        getLinearVelocity(storeResult);
        storeResult.x = (storeResult.x - overlayDispX) / dt;
        storeResult.z = (storeResult.z - overlayDispZ) / dt;
    }

    /**
     * 当前水平速率 (m/s)，从 KCC 速度读取，调试用。
     * <p>
     * 必须由调用方给出物理步长：KCC 的 XZ 分量是**每物理步的位移** (m/tick)，除以 dt 才是
     * 速率（`docs/角色控制器-行走物理设计.md` §11）。
     *
     * @param dt 物理步长 (s)
     * @return 水平速率 (m/s)
     */
    public float getHSpeed(float dt) {
        getHorizontalVelocity(tmp3, dt);
        return (float) Math.sqrt(tmp3.x * tmp3.x + tmp3.z * tmp3.z);
    }
}
