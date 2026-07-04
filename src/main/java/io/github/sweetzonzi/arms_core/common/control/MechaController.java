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

    // ── 动画与参数调制（外部写入，物理线程读取）──

    /** 重力缩放系数，1.0 = 正常重力，0.0 = 浮空。由 MoLang ctrl.set_gravity_scale 改写 */
    private volatile float gravityScale = 1.0f;

    /** 玩家能动性缩放，同时作用于行走净力与跳跃冲量。1.0 = 正常，0.0 = 全锁。由 MoLang ctrl.set_input_scale 改写 */
    private volatile float inputScale = 1.0f;

    /** 动画根骨骼帧间位移 X 分量（世界坐标，m/tick），由 MechaControl 每物理步写入 */
    private volatile float animDeltaX;
    /** 动画根骨骼帧间位移 Y 分量（世界坐标，m/tick） */
    private volatile float animDeltaY;
    /** 动画根骨骼帧间位移 Z 分量（世界坐标，m/tick） */
    private volatile float animDeltaZ;

    /**
     * 动画根骨骼帧间 Y 轴旋转增量（rad/tick）。
     * 由 MechaControl 每物理步写入，用于转身斩/回旋踢等动画驱动的面向变化。
     * 正值 = 逆时针旋转（面向左转，对应 Minecraft yaw 增大方向）。
     * 在 prePhysicsTick 中叠加到 KCC 的 Y 轴旋转。
     */
    private volatile float animRootYawDelta;

    /** 控制器与躯干刚体的分离距离 (m)，用于行走力折减，超过 SEP_MAX 则触发 RAGDOLL */
    private volatile float separationDistance;

    // ── 物理线程独占状态 ──

    /** 是否正在蓄力跳跃 */
    private boolean charging;
    /** 蓄力计时器 (s) */
    private float chargeTimer;

    /** 当前是否着地 */
    private boolean grounded;

    /** 地面法向量（世界坐标，Y 上），射线检测获取 */
    private final Vector3f groundNormal = new Vector3f(0, 1, 0);

    /** 控制器当前 Y 轴朝向（弧度），由 {@link #applyAnimRootYaw} 累积动画根骨骼 Y 旋转增量 */
    private float currentYaw;

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
     * 设置重力缩放系数。
     * <p>
     * 由 MoLang {@code ctrl.set_gravity_scale(s)} 在动画关键帧脚本中调用。
     * 0.0 = 浮空（KCC 重力关闭），1.0 = 正常重力。
     */
    public void setGravityScale(float s) {
        this.gravityScale = s;
    }

    /**
     * 设置玩家能动性缩放。
     * <p>
     * 由 MoLang {@code ctrl.set_input_scale(s)} 在动画关键帧脚本中调用。
     * 同时调制行走净力与跳跃冲量。0.0 = 全锁（移动+跳跃），1.0 = 正常。
     */
    public void setInputScale(float s) {
        this.inputScale = s;
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
     * 设置动画根骨骼帧间 Y 轴旋转增量。
     * <p>
     * 由 MechaControl 从 body_root 骨骼的帧间旋转差提取。
     * 用于转身斩、回旋踢、动画 idle 微晃等动画驱动面向变化。
     * <p>
     * KCC 的 angularFactor 为 (0,1,0) —— 只接收 Y 轴旋转，X/Z 被冻结。
     * 因此只需传入 deltaYaw，在 prePhysicsTick 中直接叠加到 KCC 当前 Y 旋转。
     *
     * @param deltaYaw Y 轴旋转增量 (rad/tick)，正值 = 逆时针（yaw 增大方向）
     */
    public void setAnimRootYawDelta(float deltaYaw) {
        this.animRootYawDelta = deltaYaw;
    }

    /**
     * 设置控制器与躯干刚体的当前分离距离。
     * <p>
     * 由上层控制编排（MechaControl）每物理步更新，用于行走力折减。
     * 分离超过 {@link MechaWalkingAttr#SEP_MAX} 时行走力归零，并触发 RAGDOLL 状态切换。
     */
    public void setSeparationDistance(float sep) {
        this.separationDistance = sep;
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

        // 4. 行走力计算与施加（含 inputScale、animDelta、分离距离折减）
        updateWalk(dt);
    }

    // ═══════════════════════════════════════════════
    // 动画根旋转
    // ═══════════════════════════════════════════════

    /**
     * 将动画根骨骼的 Y 轴旋转增量累积到控制器当前 Y 轴朝向。
     * <p>
     * KCC 本身没有旋转概念，我们在 MechaController 内自行维护一个 {@code currentYaw} 字段。
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
     * → 净力 → inputScale 调制 → 分离距离折减 → 加速度积分 → setLinearVelocity（含 animDelta 叠加）。
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
            if (charging) {
                float ratio = Math.min(chargeTimer / MechaJumpAttr.T_CHARGE, 1.0f);
                fNet *= (1.0f - ratio * MechaJumpAttr.CHARGE_WALK_PENALTY);
            }

            // ── 分离距离保护：行走力随分离距离折减 ──
            float sepFactor = 1.0f - sepDist / MechaWalkingAttr.SEP_MAX;
            if (sepFactor < 0) sepFactor = 0;
            fNet *= sepFactor;

            // ── 手动积分 → setLinearVelocity（含动画根运动叠加）──
            // 粘滞阻尼由 KCC setLinearDamping 内部处理，不在此处手动乘衰减因子
            float accel = fNet / getControllerMass();
            float newSpeed = hSpeed + accel * dt;
            float dispXZ = newSpeed * dt; // m/s × s → m，即 KCC XZ 位移量 (m/tick)

            // ── 用 currentYaw 旋转输入方向 ──
            float cos = (float) Math.cos(currentYaw);
            float sin = (float) Math.sin(currentYaw);
            float rotatedX = dirX * cos - dirZ * sin;
            float rotatedZ = dirX * sin + dirZ * cos;

            // Y 分量：动画位移需 /dt 转为 m/s；无动画位移时透传 KCC 垂直速度以免重置重力累积
            float yVel = (Math.abs(ady) > EPSILON) ? (ady / dt) : verticalVel;

            ARMS.LOGGER.debug("hSpeed = {}, accel = {}, fNet = {}, dispXZ = {} ", hSpeed, accel, fNet, dispXZ);
            setLinearVelocity(tmp2.set(rotatedX * dispXZ + adx, yVel, rotatedZ * dispXZ + adz));

        } else {
            // ── 无输入制动 ──
            // 主动摩擦刹车：减速度 = μ × g × cos(θ)，与质量无关。
            // μ 高（粗糙地面）→ 急停，μ 低（冰面）→ 长距离滑行。
            // 粘滞阻尼 c₁ 由 KCC setLinearDamping 叠加提供空气阻力级衰减。
            // 动画位移依然叠加（如受击后仰动画），无动画时透传 KCC 垂直速度。
            float slopeCos = Math.abs(groundNormal.y);
            float brakeDecel = getEffectiveFriction() * MechaWalkingAttr.GRAVITY * slopeCos;
            float newSpeed = hSpeed - brakeDecel * dt;
            if (newSpeed < 0) newSpeed = 0;

            float yVel = (Math.abs(ady) > EPSILON) ? (ady / dt) : verticalVel;

            if (newSpeed > EPSILON) {
                // 刹车后的速度沿当前实际方向
                float invSpeed = 1f / hSpeed; // hSpeed > EPSILON 保证非零
                float dispXZ = newSpeed * dt;
                setLinearVelocity(tmp2.set(vel.x * invSpeed * dispXZ + adx, yVel, vel.z * invSpeed * dispXZ + adz));
            } else {
                setLinearVelocity(tmp2.set(adx, yVel, adz));
            }
        }
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

        if (!charging) {
            // 空闲状态：按下开始蓄力（须着地、且 inputScale > 0 允许跳跃）
            if (held && grounded && inputScale > EPSILON) {
                charging = true;
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

                charging = false;
                chargeTimer = 0f;

            } else if (!held || !grounded) {
                // 中断：松键后重置、或离地
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

    /** 是否正在蓄力跳跃，调试用 */
    public boolean isChargingJump() {
        return charging;
    }
}
