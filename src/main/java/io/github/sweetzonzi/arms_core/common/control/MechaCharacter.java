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
 *   <li>主线程：{@link #setMoveInput}、{@link #setJumpInput}、{@link #setGravityScale}、
 *       {@link #setInputScale}、{@link #setAnimRootDelta}、{@link #setSeparationDistance} 写入 volatile 字段</li>
 *   <li>物理线程：{@link #prePhysicsTick} 读取 volatile 输入，计算力并施加</li>
 * </ul>
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


    // ── 主线程写入、物理线程读取的 volatile 输入 ──

    /** 世界坐标 X 方向分量（已归一化） */
    @Getter(AccessLevel.PACKAGE)
    private volatile float inputDirX;
    /** 世界坐标 Z 方向分量（已归一化） */
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
     * 动画根骨骼帧间 Y 轴旋转增量（rad/tick）。
     * <p>
     * 由 MechaControl 每物理步从 body_root 骨骼的帧间旋转差提取并写入，用于转身斩、
     * 回旋踢、动画 idle 微晃等动画驱动的面向变化。
     * 正值 = 逆时针旋转（面向左转，对应 Minecraft yaw 增大方向）。在 prePhysicsTick 中
     * 叠加到 KCC 的 Y 轴旋转。
     * <p>
     * KCC 的 angularFactor 为 (0,1,0) —— 只接收 Y 轴旋转，X/Z 被冻结。
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
     * 等于 {@code posture.speedModifier() × gait.baseSpeedModifier()}。它乘在行走净力上：
     * 蹲伏、卧倒、闪避、硬直都会通过它改变实际速度。
     * <p>
     * 默认 1.0 表示「逻辑层尚未写入时的中性值」，与完全不接逻辑层时的行为一致。
     * 写入侧只接受非负值，钳制规则见 {@link #setMoveSpeedModifier(float)}。
     */
    @Getter
    private volatile float moveSpeedModifier = 1.0f;

    // ── 闪避冲量 ──

    /** 闪避冲量方向 X（世界坐标，已归一化的水平方向） */
    private volatile float dodgeDirX;
    /** 闪避冲量方向 Z */
    private volatile float dodgeDirZ;
    /** 闪避冲量初速度 (m/s) */
    private volatile float dodgeSpeed;
    /** 闪避冲量剩余时长 (s)；> 0 期间每步都叠加冲量位移 */
    private volatile float dodgeRemaining;
    /** 闪避冲量总时长 (s)，用于速度随时间线性衰减 */
    private volatile float dodgeTotal;

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
     * 控制器当前 Y 轴朝向（弧度），由 {@link #applyAnimRootYaw} 累积动画根骨骼 Y 旋转增量。
     * <p>
     * 物理线程写入、主线程读取（同步通道要用它填 `DATA_YAW`），因此声明为 {@code volatile}；
     * 读到的值对应某一次完整物理步结束后的结果，不需要额外加锁。
     * <p>
     * 该字段单向累积、不归一化：动画根骨骼持续正转时会超出 ±π 范围，任其无限增长。使用者
     * 若需要有界朝向，自行归一化（同步通道侧由 `Rotations` 的 `% 360` 覆盖线上格式）；
     * 归一化约定的讨论见 `docs/ArmsCore双端权威与网络同步实现计划.md` §7 Q2。
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
     * 设置移动输入方向（世界坐标系水平分量）。
     * <p>
     * 调用方负责将玩家 WASD 输入从视角/身体相对坐标系转换到世界坐标系。
     * 传入 (0, 0) 表示无移动意图。
     *
     * @param worldDirX 世界坐标 X 分量
     * @param worldDirZ 世界坐标 Z 分量
     */
    public void setMoveInput(float worldDirX, float worldDirZ) {
        float len = (float) Math.sqrt(worldDirX * worldDirX + worldDirZ * worldDirZ);
        if (len < 0.001f) {
            this.inputHasMove = false;
            this.inputDirX = 0;
            this.inputDirZ = 0;
        } else {
            this.inputDirX = worldDirX / len;
            this.inputDirZ = worldDirZ / len;
            this.inputHasMove = true;
        }
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
     * 诊断用：当前抓地力上限 (N) 与由它反推出的速率上限 (m/s)。
     * <p>
     * 力-速曲线给出的均衡速率只有在摩擦力撑得住那个驱动力时才成立；抓地力不足时真实顶速
     * 远低于曲线值。这个方法把那个上限暴露出来，便于在没有可视化的情况下定位
     * 「为什么顶速只有 xx」。
     *
     * @return {@code [抓地力上限 N, 抓地力速率上限 m/s]}
     */
    public float[] debugGripTarget() {
        float slopeCos = Math.abs(groundNormal.y);
        float mu = getEffectiveFriction();
        float normalForce = getControllerMass() * MechaWalkingAttr.GRAVITY * slopeCos;
        float fEffective = Math.min(computeDriveForce(0f), mu * normalForce);
        return new float[]{fEffective, MechaWalkingAttr.P_BASE / Math.max(fEffective, EPSILON)};
    }

    /**
     * 施加一次闪避冲量。
     * <p>
     * 由上层控制编排在闪避状态进入的那一物理步调用。冲量在 {@link #dodgeDuration} 内持续叠加
     * 到 KCC 的每步位移上，速度随时间线性衰减到 0；方向已归一化，长度为零时忽略本次请求。
     * <p>
     * <b>为什么不是一次性注入速度</b>：KCC 每步都会被 {@link #updateWalk} 重写速度矢量，
     * 一次性注入最多活一个物理步（实测 6 m/s 只走出 0.06 m），随后就被行走/制动逻辑覆盖。
     * 要让「闪避 = 一段位移」成立，冲量必须在整个窗口内逐步积分。
     *
     * @param dirX          世界坐标 X 方向（水平，已归一化）
     * @param dirZ          世界坐标 Z 方向（水平，已归一化）
     * @param speed         冲量初速度 (m/s)
     * @param invulnerableS 无敌时长 (s)
     * @param dodgeDuration 冲量作用时长 (s)
     */
    public void requestDodgeImpulse(float dirX, float dirZ, float speed,
                                    float invulnerableS, float dodgeDuration) {
        float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.001f || speed <= 0f || dodgeDuration <= 0f) return;
        this.dodgeDirX = dirX / len;
        this.dodgeDirZ = dirZ / len;
        this.dodgeSpeed = speed;
        this.dodgeTotal = dodgeDuration;
        this.dodgeRemaining = dodgeDuration;
        this.dodgeInvulnerableTimer = invulnerableS;
    }

    /**
     * 当前闪避冲量在本步的水平速率 (m/s)，并推进衰减。
     * <p>
     * 线性衰减：起点 {@code dodgeSpeed}，终点 0，因此整个窗口的累计位移是
     * {@code dodgeSpeed × dodgeDuration / 2}。
     *
     * @param dt 物理步长 (s)
     * @return 本步要叠加到水平位移上的速率 (m/s)；无冲量时为 0
     */
    private float consumeDodgeSpeed(float dt) {
        if (dodgeRemaining <= 0f || dodgeTotal <= 0f) return 0f;
        float ratio = dodgeRemaining / dodgeTotal;
        float current = dodgeSpeed * ratio;
        dodgeRemaining -= dt;
        if (dodgeRemaining <= 0f) {
            dodgeRemaining = 0f;
            dodgeSpeed = 0f;
        }
        return current;
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
        // 0. 动画根骨骼 Y 轴旋转 — 转身斩/回旋踢等动画驱动的面向变化
        applyAnimRootYaw();

        // 1. 重力调制 — 每步按 gravityScale 动态缩放 KCC 重力加速度
        setGravity(MechaWalkingAttr.GRAVITY * gravityScale);

        // 2. 地面检测（KCC 着地 + 射线法线）
        updateGround();

        // 3. 跳跃蓄力与施放
        updateJump(dt);

        // 4. 行走力计算与施加（含 inputScale、animDelta、分离距离折减、闪避冲量）
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
    // 动画根旋转
    // ═══════════════════════════════════════════════

    /**
     * 将动画根骨骼的 Y 轴旋转增量累积到控制器当前 Y 轴朝向。
     * <p>
     * KCC 本身没有旋转概念，我们在 MechaCharacter 内自行维护一个 {@code currentYaw} 字段。
     * 每次动画帧叠加根骨骼的 Y 旋转增量到此字段，
     * {@link #updateWalk} 中用其旋转行走方向输入。
     */
    private void applyAnimRootYaw() {
        float deltaYaw = animRootYawDelta;
        if (Math.abs(deltaYaw) < EPSILON) return;
        currentYaw += deltaYaw;
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
     * 计算行走净力并施加位移（物理线程），支持多通道合成。
     * <p>
     * 流程：读取 KCC 当前速度 → 驱动力 → 抓地力钳制 → 空中衰减 → 内阻/坡度扣除
     * → 净力 → inputScale 调制 → 逻辑层速度倍率调制 → 分离距离折减 → 加速度积分
     * → 闪避冲量叠加 → setLinearVelocity（含 animDelta 叠加）。
     * <p>
     * 粘滞阻尼（c₁）由 KCC 内部 {@code setLinearDamping} 自动处理，
     * 我们不手动乘衰减因子——有输入时 netForce 与阻尼抗衡达稳态，
     * 无输入时沿用当前 KCC 速度作为 walk direction，KCC 阻尼自然衰减至零。
     * <p>
     * 动画根运动叠加：物理位移（m/tick）与 animDelta（m/tick）直接相加写入 XZ；
     * Y 分量 animDeltaY 需 /dt 转为 m/s 写入 KCC 垂直速度。
     */
    private void updateWalk(float dt) {
        // 快照 volatile 输入
        boolean hasInput = inputHasMove;
        float dirX = inputDirX;
        float dirZ = inputDirZ;

        // 快照动画位移与调制参数
        float adx = animDeltaX;
        float ady = animDeltaY;
        float adz = animDeltaZ;
        float inScale = inputScale;
        float sepDist = separationDistance;
        float speedMod = moveSpeedModifier;

        // 闪避冲量：本步速率由 consumeDodgeSpeed 推进衰减，整个窗口逐步积分
        float dodgeSpeed = consumeDodgeSpeed(dt);
        float dodgeX = dodgeDirX;
        float dodgeZ = dodgeDirZ;

        // 从 KCC 读取当前速度：XZ ÷ dt → m/s，Y 已是 m/s
        Vector3f vel = getLinearVelocity(tmp1);
        float hSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z) / dt;
        float verticalVel = vel.y; // KCC Y 分量即 m/s 速度，无动画位移时透传以免重置重力累积

        if (hasInput) {
            // ── 更新爬坡角 ──
            float mu = getEffectiveFriction();
            setMaxSlope((float) Math.atan(mu));

            // ── 驱动力（定制点，含多源叠加与力-速曲线）──
            float fDrive = computeDriveForce(hSpeed);

            // ── 地面抓地力钳制 ──
            float slopeCos = Math.abs(groundNormal.y); // cos(θ)
            float normalForce = getControllerMass() * MechaWalkingAttr.GRAVITY * slopeCos;
            float fEffective;
            if (grounded) {
                fEffective = Math.min(fDrive, mu * normalForce);
            } else {
                // 空中衰减
                fEffective = fDrive * MechaWalkingAttr.AIR_CONTROL;
            }

            // ── 内阻 ──
            float fResist = MechaWalkingAttr.C0 * getControllerMass() * MechaWalkingAttr.GRAVITY;

            // ── 坡度重力分量 = m·g·sin(θ) ──
            float sinTheta = (float) Math.sqrt(Math.max(0, 1 - slopeCos * slopeCos));
            float fSlope = getControllerMass() * MechaWalkingAttr.GRAVITY * sinTheta;

            float fNet = fEffective - fResist - fSlope;
            if (fNet < 0) fNet = 0;

            // ── inputScale 调制净力（MoLang ctrl.set_input_scale）──
            fNet *= inScale;

            // ── 蓄力期间行走速度折减 ──
            if (chargingJump) {
                float ratio = Math.min(chargeTimer / MechaJumpAttr.T_CHARGE, 1.0f);
                fNet *= (1.0f - ratio * MechaJumpAttr.CHARGE_WALK_PENALTY);
            }

            // ── 分离距离保护：行走力随分离距离折减 ──
            float sepFactor = 1.0f - sepDist / MechaWalkingAttr.SEP_MAX;
            if (sepFactor < 0) sepFactor = 0;
            fNet *= sepFactor;

            // ── 逻辑层速度倍率：限制稳态速率 ──
            // 只折减力是不够的：力-速曲线的均衡点几乎正好落在 v_rated 上，
            // 折减力只是让加速变慢，最终仍会走到同一个顶速（蹲伏因此看起来没效果）。
            // 真正的「蹲着走得慢」是一个速度上限，所以这里解出倍率为 1.0 时的均衡速率，
            // 再乘以本帧倍率作为上限。用 max 组合：倍率 > 1（冲刺）时不会把冲刺压回常速。
            //
            // 上限必须把闪避冲量算进去：冲量是设计上「不受姿态倍率折减」的位移，
            // 若只钳到 hSpeed × speedMod，蹲伏（倍率 0.3）下冲量会被立刻削掉，
            // 实际只走出 0.06 m 而不是设计值的 2.4 m。
            float baseTarget = currentTargetSpeed(fResist + fSlope, fEffective);
            float cappedSpeed = Math.max(baseTarget * speedMod, hSpeed * speedMod) + dodgeSpeed;
            fNet *= speedMod;

            // ── 手动积分 → setLinearVelocity（含动画根运动叠加）──
            // 粘滞阻尼由 KCC setLinearDamping 内部处理，不在此处手动乘衰减因子
            float accel = fNet / getControllerMass();
            float newSpeed = hSpeed + accel * dt;
            if (newSpeed > cappedSpeed) newSpeed = cappedSpeed;
            float dispXZ = newSpeed * dt; // m/s × s → m，即 KCC XZ 位移量 (m/tick)

            // ── 用 currentYaw 旋转输入方向 ──
            float cos = (float) Math.cos(currentYaw);
            float sin = (float) Math.sin(currentYaw);
            float rotatedX = dirX * cos - dirZ * sin;
            float rotatedZ = dirX * sin + dirZ * cos;

            // ── 闪避冲量：一次性叠加到水平位移（m/tick），不受行走力折减影响 ──
            float dodgeDisp = dodgeSpeed * dt;
            float extraX = dodgeX * dodgeDisp;
            float extraZ = dodgeZ * dodgeDisp;

            // Y 分量：动画位移需 /dt 转为 m/s；无动画位移时透传 KCC 垂直速度以免重置重力累积
            float yVel = (Math.abs(ady) > EPSILON) ? (ady / dt) : verticalVel;

            setLinearVelocity(tmp2.set(
                    rotatedX * dispXZ + adx + extraX, yVel, rotatedZ * dispXZ + adz + extraZ));

        } else {
            // ── 无输入制动 ──
            // 主动摩擦刹车：减速度 = μ × g × cos(θ)，与质量无关。
            // μ 高（粗糙地面）→ 急停，μ 低（冰面）→ 长距离滑行。
            // 粘滞阻尼 c₁ 由 KCC setLinearDamping 叠加提供空气阻力级衰减。
            // 动画位移依然叠加（如受击后仰动画），无动画时透传 KCC 垂直速度。
            // 闪避冲量同样叠加：闪避不需要按住方向键，这正是它的用途。
            float slopeCos = Math.abs(groundNormal.y);
            float brakeDecel = getEffectiveFriction() * MechaWalkingAttr.GRAVITY * slopeCos;
            float newSpeed = hSpeed - brakeDecel * dt;
            if (newSpeed < 0) newSpeed = 0;

            float yVel = (Math.abs(ady) > EPSILON) ? (ady / dt) : verticalVel;

            float dodgeDisp = dodgeSpeed * dt;
            float extraX = dodgeX * dodgeDisp;
            float extraZ = dodgeZ * dodgeDisp;

            if (newSpeed > EPSILON) {
                // 刹车后的速度沿当前实际方向
                float invSpeed = 1f / hSpeed; // hSpeed > EPSILON 保证非零
                float dispXZ = newSpeed * dt;
                setLinearVelocity(tmp2.set(
                        vel.x * invSpeed * dispXZ + adx + extraX, yVel, vel.z * invSpeed * dispXZ + adz + extraZ));
            } else {
                setLinearVelocity(tmp2.set(adx + extraX, yVel, adz + extraZ));
            }
        }
    }

    /**
     * 解出当前阻力下、移动速度倍率为 1.0 时的均衡速率 (m/s)。
     * <p>
     * 均衡条件是驱动力等于阻力之和：{@code min(P/v, F_max) · exp(−(v−v_rated)/λ) = fResistTotal}。
     * {@code v ≤ v_rated} 区间内指数项为 1，因此先试 {@code v = P / fResistTotal}：
     * <ul>
     *   <li>该值落在 {@code [P/F_max, v_rated]} 内时，它就是均衡速率（低速区力-速曲线为
     *       {@code F = P/v}，恰好与阻力相交于 {@code v = P / 阻力}）</li>
     *   <li>否则说明均衡点在超速衰减区，用对数反解</li>
     * </ul>
     * 结果再按抓地力上限钳制：摩擦力不足以支撑那么大驱动力时，真实顶速更低。
     *
     * @param fResistTotal 需要被驱动力抵消的阻力总和 (N)：内阻 + 坡度分量
     * @param fEffective   当前抓地力上限 (N)
     * @return 均衡速率 (m/s)
     */
    private float currentTargetSpeed(float fResistTotal, float fEffective) {
        if (fResistTotal <= EPSILON) {
            // 无阻力时力-速曲线本身就限制了速率
            return MechaWalkingAttr.V_RATED;
        }
        float vLow = MechaWalkingAttr.P_BASE / fResistTotal;
        float vFMax = MechaWalkingAttr.P_BASE / MechaWalkingAttr.F_MAX;
        float target;
        if (vLow <= MechaWalkingAttr.V_RATED && vLow >= vFMax) {
            target = vLow;
        } else {
            // 超速衰减区：P/v · exp(−(v−v_rated)/λ) = fResistTotal
            float allowed = fResistTotal / MechaWalkingAttr.F_MAX;
            target = allowed <= 0f
                    ? 0f
                    : MechaWalkingAttr.V_RATED - MechaWalkingAttr.LAMBDA * (float) Math.log(allowed);
        }
        // 抓地力上限：阻力很小时 v_low 会很大，但摩擦力撑不住那个驱动力
        if (fEffective > 0f) {
            float gripTarget = MechaWalkingAttr.P_BASE / fEffective;
            if (gripTarget < target) return gripTarget;
        }
        return target;
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
     * 仅用于手动积分 a = F/m，不参与 KCC 刚体（KCC 是幽灵体无质量）。
     * 子类覆写返回机体总质量。
     */
    protected float getControllerMass() {
        return MechaWalkingAttr.MASS;
    }

    // ═══════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════

    /** 当前水平速率 (m/s)，从 KCC 速度读取，调试用 */
    public float getHSpeed() {
        Vector3f vel = getLinearVelocity(tmp3);
        return (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
    }
}
