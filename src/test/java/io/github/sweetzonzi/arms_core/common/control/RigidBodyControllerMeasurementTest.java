package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 刚体控制器在裸 {@link PhysicsSpace} 上的测量 —— 只量数，不定基线。
 * <p>
 * 它承载 `docs/角色控制器-刚体动力学方案.md` §12.9 登记的三项「写法已定、数值未测」：
 * <ol>
 *   <li><b>越障那一步的水平速率残留</b> —— 判决 §6.2 的「水平速度原样保留」是否在贴墙那一拍
 *       被接触吃掉。若被吃掉，就要加一条兜底</li>
 *   <li><b>每步 yaw 写入下的步内朝向偏差峰峰值与 {@code ω_y}</b> —— 判决 §5.3 的摆动上界
 *       {@code ω_y·Δt} 是否可观。可观则在朝向写入处顺手把 {@code ω_y} 归零</li>
 *   <li><b>起步头十步的平均加速度</b> —— 判决 §7.4「对账表是恒等式」是否成立：{@code N} 取静态
 *       估算 {@code m·g·cosθ} 与引擎实际能传的摩擦上限不一致时会低于解析值 {@code μ_eff·g}</li>
 * </ol>
 * 另加一组地面力律的对照测量：引擎对**受力接触体**给出的是静摩擦锁定而不是「从受力里减掉
 * {@code μN}」，因此 {@code μ_eff·N·û} 那条前馈是否该叠加（{@code usesFrictionFeedforward()}）
 * 必须用数据定，这里把两档一起打出来。
 * <p>
 * <b>本类只输出数值，不做基线断言。</b>断言只有「场景确实成立」这一类前提（越障真的发生、
 * 起步确实在加速），因为场景不成立时量到的数没有意义。基线重建是下一步的事
 * （`docs/角色控制器-刚体动力学方案.md` §11 的 P2 行），要看的就是这里打出来的数。
 * <p>
 * 与 {@code RigidBodyControllerPrototypeTest} 同类：真 {@code PhysicsSpace}、100 Hz、
 * 不接 Minecraft。差别是本类驱动的是**正式控制器**（{@link MechaCharacter}）而不是原型里的
 * 裸刚体，因此量到的数就是落地形态的数。
 *
 * @author Sweetzonzi
 */
class RigidBodyControllerMeasurementTest {

    /** 与游戏内一致的物理步长：tps = 100 */
    private static final float DT = 1f / 100f;

    /** 素体裸足的角色侧摩擦系数 */
    private static final float CHARACTER_FRICTION = MechaWalkingAttr.MU_NAKED;

    /** 组合摩擦 {@code μ_eff} = 角色侧 × 地形侧 */
    private static final float MU_EFF = CHARACTER_FRICTION * MechaWalkingAttr.TERRAIN_FRICTION;

    /** 平地解析起步加速度 (m/s²)：{@code μ_eff·g}，质量在锥截里约掉 */
    private static final float ANALYTIC_START_ACCEL = MU_EFF * MechaWalkingAttr.GRAVITY;

    /** 起步平均加速度的统计步数 */
    private static final int START_ACCEL_STEPS = 10;

    /** 稳态速率的统计时长（步）：末段 100 步内的最大值 */
    private static final int STEADY_WINDOW = 100;

    // ═══════════════════════════════════════════════
    // 场景搭建
    // ═══════════════════════════════════════════════

    private static PhysicsSpace newSpace() {
        return new PhysicsSpace(new Vector3f(-50f, -50f, -50f), new Vector3f(50f, 50f, 50f));
    }

    /**
     * 一块静态盒。地形侧摩擦必须显式设置：Bullet 的 {@code btCollisionObject} 默认摩擦是 0.5，
     * 而引擎的组合摩擦是双方之积，不写清楚就不知道角色侧该取多少。
     */
    private static PhysicsRigidBody staticBox(Vector3f halfExtents, Vector3f location, float friction) {
        PhysicsRigidBody body = new PhysicsRigidBody(new BoxCollisionShape(halfExtents), 0f);
        body.setPhysicsLocation(location);
        body.setFriction(friction);
        body.setRestitution(0f);
        body.setCollisionGroup(CollisionGroups.TERRAIN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
        return body;
    }

    /**
     * 正式控制器，出生在脚底 y = 0、朝向 yaw = 0（面向 +Z），并加入物理空间。
     *
     * @param feedforward 是否叠加摩擦锥前馈（{@code usesFrictionFeedforward()} 的覆写值）
     */
    private static MechaCharacter newController(PhysicsSpace space, boolean feedforward) {
        MechaCharacter body = new MechaCharacter(MechaBodyPreset.newCapsuleShape(), space) {
            @Override
            protected boolean usesFrictionFeedforward() {
                return feedforward;
            }
        };
        body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
        space.addCollisionObject(body);
        return body;
    }

    /** 平地场景 + 控制器。 */
    private static MechaCharacter flatRig(PhysicsSpace space, boolean feedforward) {
        space.addCollisionObject(staticBox(new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        return newController(space, feedforward);
    }

    /** 脚底高度 (m)。 */
    private static float feetY(MechaCharacter body) {
        return body.getPhysicsLocation(null).y - MechaBodyPreset.HALF_TOTAL;
    }

    /** 刚体局部 +Z 的水平投影方向（度）。 */
    private static float yawDegrees(MechaCharacter body) {
        com.jme3.math.Matrix3f rotation = body.getPhysicsRotationMatrix(new com.jme3.math.Matrix3f());
        return (float) Math.toDegrees(Math.atan2(rotation.get(0, 2), rotation.get(2, 2)));
    }

    /** 两个角度之间的最短差值（度），落在 (−180, 180]。 */
    private static float angleDelta(float from, float to) {
        float delta = (to - from) % 360f;
        if (delta > 180f) {
            delta -= 360f;
        } else if (delta <= -180f) {
            delta += 360f;
        }
        return delta;
    }

    // ═══════════════════════════════════════════════
    // ① 越障那一步的水平速率残留（§12.9 第一项）
    // ═══════════════════════════════════════════════

    /**
     * 0.5 m 半砖台阶：量「贴墙前一拍」与「越过后一拍」的水平速率比。
     * <p>
     * 判据的含义：§6.2 的落位只把胶囊前缘写到台阶立面上、不改速度通道，因此越障前后速率应当
     * 基本连续（差异只来自这一步的接触与摩擦）。若贴墙那一拍速率已经被吃掉，比值会明显小于 1，
     * 那就需要在落位时加一条「沿推进方向的车体速度不小于写入前值」的兜底。
     * <p>
     * 台阶几何与 {@code MechaCharacterStepTest} 同形：地面顶面 y = 0，台阶前沿 z = 2.0、顶面
     * y = 0.5。胶囊半径 0.4，因此贴墙时胶囊中心 z ≈ 1.6。
     */
    @Test
    void measureHorizontalSpeedAcrossAStepUp() {
        final float stepHeight = 0.5f;
        final float stepFrontZ = 2f;
        final int maxSteps = 600;

        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        space.addCollisionObject(staticBox(
                new Vector3f(2f, stepHeight / 2f, 2f),
                new Vector3f(0f, stepHeight / 2f, stepFrontZ + 2f), 0.5f));
        MechaCharacter body = newController(space, true);

        float speedAtWall = Float.NaN;
        int wallStep = -1;
        float speedBeforeStep = Float.NaN;
        float speedAfterStep = Float.NaN;
        float maxFeetY = Float.NEGATIVE_INFINITY;
        int stepUpStep = -1;
        float minSpeedAtWall = Float.POSITIVE_INFINITY;

        for (int i = 0; i < maxSteps; i++) {
            body.setMoveIntent(1f, 0f);
            float speed = body.getHSpeed();
            float z = body.getPhysicsLocation(null).z;

            // 贴墙前一拍：胶囊前缘离台阶立面不足 2 cm（此时还没有越障，脚底仍在地面）
            if (Float.isNaN(speedAtWall) && z + MechaBodyPreset.CAPSULE_RADIUS > stepFrontZ - 0.02f) {
                speedAtWall = speed;
                speedBeforeStep = speed;
                wallStep = i;
            }
            // 顶墙期间的最低速率：它回答「贴墙那一拍速度有没有被吃掉」
            if (z + MechaBodyPreset.CAPSULE_RADIUS > stepFrontZ - 0.5f && stepUpStep < 0) {
                minSpeedAtWall = Math.min(minSpeedAtWall, speed);
            }

            body.prePhysicsTick(DT);
            space.update(DT, 0, 0x0);

            float feet = feetY(body);
            maxFeetY = Math.max(maxFeetY, feet);
            if (stepUpStep < 0 && feet > stepHeight * 0.6f) {
                // 越障发生在这一步：本步结束时的速率就是「越过后一拍」
                stepUpStep = i + 1;
                speedAfterStep = body.getHSpeed();
            }
        }

        float ratio = (Float.isNaN(speedBeforeStep) || speedBeforeStep < 1.0e-3f)
                ? Float.NaN : speedAfterStep / speedBeforeStep;
        System.out.printf("[测量①] 越障速率：贴墙前一拍 %.4f m/s（第 %d 步），越过后一拍 %.4f m/s（第 %d 步），"
                        + "比值 %.4f；顶墙期间最低 %.4f m/s；脚底最高 %.4f m，终止 z = %.4f%n",
                speedBeforeStep, wallStep, speedAfterStep, stepUpStep, ratio, minSpeedAtWall,
                maxFeetY, body.getPhysicsLocation(null).z);

        assertTrue(stepUpStep > 0,
                "前提：0.5 m 半砖应当触发越障（脚底升到台阶顶面），实际脚底最高 " + maxFeetY + " m");
        assertTrue(ratio > 0.5f,
                "前提：越障前后应当仍在移动（比值 > 0.5）；若接近 0 说明落位把速度吃掉了，"
                        + "实际比值 " + ratio);
    }

    // ═══════════════════════════════════════════════
    // ② 每步 yaw 写入下的步内朝向偏差与 ω_y（§12.9 第二项）
    // ═══════════════════════════════════════════════

    /**
     * 平地直线行走：每物理步写一次 yaw，量步内朝向偏差的峰峰值与 {@code ω_y}。
     * <p>
     * 采样点有三个，对应一步之内的三个时刻：
     * <ol>
     *   <li><b>写入之前</b> —— 上一步求解器转走之后、本步写入之前，相对目标的最大偏离</li>
     *   <li><b>写入之后</b> —— 朝向写入把偏差对到零（理论上恒为 0）</li>
     *   <li><b>步进之后</b> —— 求解器又转走的那一段，即 §5.3 说的「步内转动」</li>
     * </ol>
     * 峰峰值取 ① 相对目标的最大绝对值：它就是「朝向在目标附近以固定小幅摆动」的幅度，
     * 上界应当是 {@code ω_y·Δt}。同时记录最大倾角，确认摆动没有变成姿态上的歪。
     */
    @Test
    void measureYawJitterPerPhysicsStep() {
        final int steps = 400;
        final float targetYaw = 30f;

        PhysicsSpace space = newSpace();
        MechaCharacter body = flatRig(space, false);

        float maxDeviationBeforeWrite = 0f;
        float maxDeviationAfterWrite = 0f;
        float maxDeviationAfterSolver = 0f;
        float maxAngularVelocityY = 0f;
        float maxAngularVelocity = 0f;
        float maxTilt = 0f;

        // 先跑一步把初始朝向对到目标上：否则第 0 步的「写入前」读到的是出生朝向 0°，会把
        // 一次性的初始对齐误记成摆动
        body.setViewYaw(targetYaw);
        body.prePhysicsTick(DT);
        space.update(DT, 0, 0x0);

        for (int i = 0; i < steps; i++) {
            body.setViewYaw(targetYaw);
            body.setMoveIntent(1f, 0f);

            // ① 本步写入之前：上一步求解器转走之后相对目标的偏离
            maxDeviationBeforeWrite = Math.max(maxDeviationBeforeWrite,
                    Math.abs(angleDelta(targetYaw, yawDegrees(body))));

            body.prePhysicsTick(DT);

            // ② 写入之后：朝向写入把偏差对到零
            maxDeviationAfterWrite = Math.max(maxDeviationAfterWrite,
                    Math.abs(angleDelta(targetYaw, yawDegrees(body))));

            Vector3f angularVelocity = body.getAngularVelocity(null);
            maxAngularVelocityY = Math.max(maxAngularVelocityY, Math.abs(angularVelocity.y));
            maxAngularVelocity = Math.max(maxAngularVelocity, angularVelocity.length());

            space.update(DT, 0, 0x0);

            // ③ 步进之后：求解器在这一步里转走的那一段
            maxDeviationAfterSolver = Math.max(maxDeviationAfterSolver,
                    Math.abs(angleDelta(targetYaw, yawDegrees(body))));

            com.jme3.math.Matrix3f rotation = body.getPhysicsRotationMatrix(new com.jme3.math.Matrix3f());
            float upY = Math.max(-1f, Math.min(1f, rotation.get(1, 1)));
            maxTilt = Math.max(maxTilt, (float) Math.toDegrees(Math.acos(upY)));
        }

        System.out.printf("[测量②] 每步 yaw 写入：%d 步内相对目标的步内偏差峰峰值 %.6f°"
                        + "（写入前 %.6f°，写入后 %.6f°，步进后 %.6f°）；峰值 |ω_y| %.6f rad/s，"
                        + "峰值 |ω| %.6f rad/s；最大倾角 %.5f°；末速率 %.4f m/s（ω_y·Δt 上界 %.6f°）%n",
                steps, maxDeviationBeforeWrite, maxDeviationBeforeWrite, maxDeviationAfterWrite,
                maxDeviationAfterSolver, maxAngularVelocityY, maxAngularVelocity, maxTilt,
                body.getHSpeed(), Math.toDegrees(maxAngularVelocityY * DT));

        assertTrue(maxTilt < 1f, "平地直线行走不应出现倾角，实际最大倾角 " + maxTilt + "°");
        assertTrue(maxDeviationAfterWrite < 0.5f,
                "朝向写入之后应当已经对到目标上（残差来自四元数舍入），实际最大 " + maxDeviationAfterWrite + "°");
    }

    // ═══════════════════════════════════════════════
    // ③ 起步头十步的平均加速度（§12.9 第三项）
    // ═══════════════════════════════════════════════

    /**
     * 平地、{@code μ_eff} 按配置取值、无输入起步：量前 {@link #START_ACCEL_STEPS} 步的平均加速度
     * 与解析值 {@code μ_eff·g} 的偏差，两档前馈各量一次。
     * <p>
     * 这条同时是前馈去留的判决点：引擎对受力接触体给的是静摩擦锁定（实测施加 μN 时胶囊纹丝
     * 不动），因此若引擎自己那份摩擦已经承担了前馈想承担的事，叠加前馈就会把施加力推到锁定点
     * 上、起步完全不动。
     */
    @Test
    void measureStartAccelerationOverTheFirstTenSteps() {
        System.out.printf("[测量③] 起步加速度（μ_eff = %.2f，解析值 %.4f m/s²）：%n",
                MU_EFF, ANALYTIC_START_ACCEL);
        for (boolean feedforward : new boolean[]{false, true}) {
            PhysicsSpace space = newSpace();
            MechaCharacter body = flatRig(space, feedforward);

            // 落稳：出生时脚底正好贴地，先让求解器建立接触
            for (int i = 0; i < 20; i++) {
                body.prePhysicsTick(DT);
                space.update(DT, 0, 0x0);
            }

            float speedAtTen = 0f;
            float minFeet = Float.POSITIVE_INFINITY;
            float maxLateralSpeed = 0f;
            for (int i = 1; i <= START_ACCEL_STEPS; i++) {
                body.setMoveIntent(1f, 0f);
                body.prePhysicsTick(DT);
                space.update(DT, 0, 0x0);

                Vector3f velocity = body.getLinearVelocity(null);
                speedAtTen = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
                maxLateralSpeed = Math.max(maxLateralSpeed, Math.abs(velocity.x));
                minFeet = Math.min(minFeet, feetY(body));
            }
            float averageAccel = speedAtTen / (START_ACCEL_STEPS * DT);
            System.out.printf("         前馈 %s：十步末速率 %.5f m/s → 平均加速度 %.5f m/s²，"
                            + "与解析值之比 %.4f；最大侧向速率 %.6f m/s；脚底最低 %.5f m%n",
                    feedforward ? "开" : "关", speedAtTen, averageAccel,
                    averageAccel / ANALYTIC_START_ACCEL, maxLateralSpeed, minFeet);
        }
    }

    // ═══════════════════════════════════════════════
    // ④ 稳态速率对账（§7.4 表格里的两行）
    // ═══════════════════════════════════════════════

    /**
     * 平地持续前进：量站立与蹲伏两档的稳态速率，两档前馈各量一次。
     * <p>
     * 解析值分别是 {@code P_base / (c₀·m·g) = 6.0 m/s} 与它的 0.3 倍——{@code MOVE_SPEED_MODIFIER}
     * 缩放的是控制力，力平衡给出 {@code v ∝ k}。这一条不属于 §12.9 登记的三项，但它是 §7.4
     * 对账表里唯一能在裸物理空间上量到的两行，顺手一起打出来。
     */
    @Test
    void measureSteadyStateSpeed() {
        final int steps = 1200;
        for (boolean feedforward : new boolean[]{false, true}) {
            for (float modifier : new float[]{1.0f, 0.3f}) {
                PhysicsSpace space = newSpace();
                MechaCharacter body = flatRig(space, feedforward);
                body.setMoveSpeedModifier(modifier);

                float speed = 0f;
                for (int i = 0; i < steps; i++) {
                    body.setMoveIntent(1f, 0f);
                    body.prePhysicsTick(DT);
                    space.update(DT, 0, 0x0);
                    if (i >= steps - STEADY_WINDOW) {
                        speed = Math.max(speed, body.getHSpeed());
                    }
                }
                System.out.printf("[测量④] 前馈 %s、倍率 %.1f：稳态速率 %.4f m/s（解析值 %.4f m/s）%n",
                        feedforward ? "开" : "关", modifier, speed, MechaWalkingAttr.V_REF * modifier);
            }
        }
    }
}
