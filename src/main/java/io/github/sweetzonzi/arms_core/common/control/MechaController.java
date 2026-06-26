package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.collision.PhysicsRayTestResult;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.objects.PhysicsCharacter;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ArmsCore;

import java.util.List;

/**
 * 机娘运动学角色控制器（对应设计文档 §1-4 行走物理模型）。
 * <p>
 * 继承 {@link PhysicsCharacter}（Bullet {@code btKinematicCharacterController}），
 * 通过手动积分驱动力模型 → {@code setWalkDirection} 实现运动。
 * KCC 内部负责碰撞 sweep / 滑墙 / auto-step / 爬坡角限制 / 着地检测。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>主线程：{@link #setMoveInput}、{@link #setJumpInput} 写入 volatile 字段</li>
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
public class MechaController extends PhysicsCharacter {

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

    /** 胶囊半高 = 圆柱半高 + 半球半径 (m)，用于射线起点计算 */
    private final float capsuleHalfTotal;

    // ── 主线程写入、物理线程读取的 volatile 输入 ──

    /** 世界坐标 X 方向分量（已归一化） */
    private volatile float inputDirX;
    /** 世界坐标 Z 方向分量（已归一化） */
    private volatile float inputDirZ;
    /** 是否有移动意图 */
    private volatile boolean inputHasMove;
    /** 跳跃键是否按住 */
    private volatile boolean jumpHeld;
    /** 跳跃键本帧松开（单帧标记，物理线程消费后清零） */
    private volatile boolean jumpReleased;

    // ── 物理线程独占状态 ──

    /** 是否正在蓄力跳跃 */
    private boolean charging;
    /** 蓄力计时器 (s) */
    private float chargeTimer;

    /** 当前是否着地 */
    private boolean grounded;

    /** 地面法向量（世界坐标，Y 上），射线检测获取 */
    private final Vector3f groundNormal = new Vector3f(0, 1, 0);

    // ── 临时向量，减少物理线程分配 ──
    private final Vector3f tmp1 = new Vector3f();
    private final Vector3f tmp2 = new Vector3f();
    private final Vector3f tmp3 = new Vector3f();

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param shape        胶囊碰撞形状
     * @param physicsSpace 物理空间（用于地面射线检测）
     */
    public MechaController(CapsuleCollisionShape shape, PhysicsSpace physicsSpace) {
        super(shape, MechaWalkingAttr.STEP_HEIGHT_BASE);
        this.physicsSpace = physicsSpace;
        this.capsuleHalfTotal = shape.getHeight() / 2f + shape.getRadius();

        // KCC 配置（§1.2、§5.1）
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

    // ═══════════════════════════════════════════════
    // 物理步回调（物理线程调用）
    // ═══════════════════════════════════════════════

    /**
     * 每物理步调用一次，完成全部行走/跳跃物理计算。
     * <p>
     * 调用顺序：地面检测 → 跳跃更新 → 行走力计算与施加。
     *
     * @param dt 物理步长 (s)，通常 1/20
     */
    public void prePhysicsTick(float dt) {
        // 1. 地面检测（KCC 着地 + 射线法线）
        updateGround();

        // 2. 跳跃蓄力与施放（§4）
        updateJump(dt);

        // 3. 行走力计算与施加（§3）
        updateWalk(dt);
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
    // 行走力模型（§3 完整模型）
    // ═══════════════════════════════════════════════

    /**
     * 计算行走净力并施加位移（物理线程）。
     * <p>
     * 流程：读取 KCC 当前速度 → 驱动力 → 抓地力钳制 → 空中衰减 → 内阻/坡度扣除
     * → 净力 → 加速度积分 → setWalkDirection。
     * <p>
     * 粘滞阻尼（c₁）由 KCC 内部 {@code setLinearDamping} 自动处理，
     * 我们不手动乘衰减因子——有输入时 netForce 与阻尼抗衡达稳态，
     * 无输入时沿用当前 KCC 速度作为 walk direction，KCC 阻尼自然衰减至零。
     */
    private void updateWalk(float dt) {
        // 快照 volatile 输入
        boolean hasInput = inputHasMove;
        float dirX = inputDirX;
        float dirZ = inputDirZ;

        // 从 KCC 读取当前水平速率（已含上帧阻尼效果）
        Vector3f vel = getLinearVelocity(tmp1);
        float hSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        if (hasInput) {
            // ── 更新爬坡角 (§3.5 末段、§8.1) ──
            float mu = getEffectiveFriction();
            setMaxSlope((float) Math.atan(mu));

            // ── §3.3-3.4: 驱动力（定制点，含多源叠加与力-速曲线） ──
            float fDrive = computeDriveForce(hSpeed);

            // ── §3.5: 地面抓地力钳制 ──
            float slopeCos = Math.abs(groundNormal.y); // cos(θ)
            float normalForce = getControllerMass() * MechaWalkingAttr.GRAVITY * slopeCos;
            float fEffective;
            if (grounded) {
                fEffective = Math.min(fDrive, mu * normalForce);
            } else {
                // §3.7 空中衰减
                fEffective = fDrive * MechaWalkingAttr.AIR_CONTROL;
            }

            // ── §3.6: 内阻 ──
            float fResist = MechaWalkingAttr.C0 * getControllerMass() * MechaWalkingAttr.GRAVITY;

            // ── §3.6: 坡度重力分量 = m·g·sin(θ) ──
            float sinTheta = (float) Math.sqrt(Math.max(0, 1 - slopeCos * slopeCos));
            float fSlope = getControllerMass() * MechaWalkingAttr.GRAVITY * sinTheta;

            float fNet = fEffective - fResist - fSlope;
            if (fNet < 0) fNet = 0;

            // ── §4.4: 蓄力期间行走速度折减 ──
            if (charging) {
                float ratio = Math.min(chargeTimer / MechaJumpAttr.T_CHARGE, 1.0f);
                fNet *= (1.0f - ratio * MechaJumpAttr.CHARGE_WALK_PENALTY);
            }

            // ── §3.8: 手动积分 → setWalkDirection ──
            // 粘滞阻尼由 KCC setLinearDamping 内部处理，不在此处手动乘衰减因子
            float accel = fNet / getControllerMass();
            float newSpeed = hSpeed + accel * dt;
            float delta = newSpeed * dt;
            ArmsCore.LOGGER.debug("hSpeed = {}, accel = {}, fNet = {}, delta = {} ", hSpeed, accel, fNet, delta);
            setWalkDirection(tmp2.set(dirX * delta, 0, dirZ * delta));

        } else {
            // ── §3.9: 无输入制动 ──
            // 主动摩擦刹车：减速度 = μ × g × cos(θ)，与质量无关。
            // μ 高（粗糙地面）→ 急停，μ 低（冰面）→ 长距离滑行。
            // 粘滞阻尼 c₁ 由 KCC setLinearDamping 叠加提供空气阻力级衰减。
            float slopeCos = Math.abs(groundNormal.y);
            float brakeDecel = getEffectiveFriction() * MechaWalkingAttr.GRAVITY * slopeCos;
            float newSpeed = hSpeed - brakeDecel * dt;
            if (newSpeed < 0) newSpeed = 0;

            if (newSpeed > EPSILON) {
                // 刹车后的速度沿当前实际方向
                float invSpeed = 1f / hSpeed; // hSpeed > EPSILON 保证非零
                float delta = newSpeed * dt;
                setWalkDirection(tmp2.set(vel.x * invSpeed * delta, 0, vel.z * invSpeed * delta));
            } else {
                setWalkDirection(tmp2.set(0, 0, 0));
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 跳跃蓄力模型（§4）
    // ═══════════════════════════════════════════════

    /**
     * 更新跳跃蓄力状态，并在松开时通过 KCC 内建 {@link #jump()} 施放（物理线程）。
     */
    private void updateJump(float dt) {
        boolean held = jumpHeld;
        boolean released = jumpReleased;
        // 消费释放标记（单帧有效）
        if (released) {
            jumpReleased = false;
        }

        if (!charging) {
            // 空闲状态：按下开始蓄力（须着地）
            if (held && grounded) {
                charging = true;
                chargeTimer = 0f;
            }
        } else {
            if (released) {
                // 松开：按当前 ratio 施放冲量（§4.3 + §4.5）
                float ratio = Math.min(chargeTimer / MechaJumpAttr.T_CHARGE, 1.0f);
                float impulse = computeJumpImpulse(ratio);
                float vRaw = impulse / getControllerMass();
                float vTakeoff = Math.min(vRaw, getJumpSpeedCap());

                // 使用 KCC 内建跳跃，保证水平速度保留
                setJumpSpeed(vTakeoff);
                jump();

                charging = false;
                chargeTimer = 0f;

            } else if (!held || !grounded) {
                // §4.4 中断：松键后重置、或离地
                charging = false;
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
     * 计算当前水平速率下的总驱动力（§3.3-3.4）。
     * <p>
     * 默认实现：本体单源功率-力-速曲线。
     * 子类覆写以实现多源叠加（本体 + 各腿部助力子系统），
     * 每个源自据其 P / v_rated / F_max 独立计算 F_i 后求和。
     *
     * @param currentSpeed 当前水平速率 (m/s)，从 KCC {@code getLinearVelocity} 读取
     * @return 总驱动力 (N)
     */
    protected float computeDriveForce(float currentSpeed) {
        // §3.3 第一步：功率限力
        float fPower;
        if (currentSpeed < EPSILON) {
            fPower = MechaWalkingAttr.F_MAX; // 除零兜底
        } else {
            fPower = MechaWalkingAttr.P_BASE / currentSpeed;
        }

        // §3.3 第二步：力上限钳制
        float fRaw = Math.min(fPower, MechaWalkingAttr.F_MAX);

        // §3.3 第三步：超速衰减
        if (currentSpeed <= MechaWalkingAttr.V_RATED) {
            return fRaw;
        }
        return fRaw * (float) Math.exp(-(currentSpeed - MechaWalkingAttr.V_RATED) / MechaWalkingAttr.LAMBDA);
    }

    /**
     * 计算跳跃冲量（§4.2-4.5）。
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
     * 起跳速度硬上限（§4.5）。
     * <p>
     * 默认返回素体 v_extend。多腿时子类应覆写为 max(各腿 v_extend)。
     */
    protected float getJumpSpeedCap() {
        return MechaJumpAttr.V_EXTEND;
    }

    /**
     * 有效地面摩擦系数（§3.5）。
     * <p>
     * 默认返回裸足 μ_naked。子类覆写为 max(各腿 μ_foot)。
     */
    protected float getEffectiveFriction() {
        return MechaWalkingAttr.MU_NAKED;
    }

    /**
     * 控制器质量（§1.5）。
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

    /** 是否正在蓄力跳跃，调试用 */
    public boolean isChargingJump() {
        return charging;
    }
}
