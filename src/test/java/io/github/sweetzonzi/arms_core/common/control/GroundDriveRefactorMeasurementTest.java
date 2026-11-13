package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import cn.solarmoon.spark_core.physics.body.ManifoldPoint;
import cn.solarmoon.spark_core.physics.body.PhysicsBodyEvent;
import cn.solarmoon.spark_core.util.InlineEventConsumer;
import io.github.sweetzonzi.arms_core.common.control.attr.LegDriveAttr;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.PhysicsTickListener;
import com.jme3.bullet.StepFlag;
import com.jme3.bullet.collision.ManifoldPoints;
import com.jme3.bullet.collision.PersistentManifolds;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Matrix3f;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import io.github.sweetzonzi.arms_core.util.control.ContactBudget;
import io.github.sweetzonzi.arms_core.util.control.GearResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地面驱动的可行性与标定测量。
 *
 * <p>本类跑真实 {@link PhysicsSpace}，由 {@code measurementTest} 运行，不进常规 {@code test}。
 *
 * <p>本类覆盖摩擦写入、正式控制器的驱动／停车／保持、支撑冲量相位、载荷缓存、侧向制动与低速辅助。
 * 多接触与动态甲板反作用等场景仍未覆盖。
 *
 * <p>写法与生产路径同构：写入发生在 {@code PhysicsSpace#onContactProcessed}（持久点，每步）与
 * {@code PhysicsSpace#onContactConceived}（新点，加入 manifold 之前），两者都在求解器之前，
 * 因此写进去的就是本步求解所用的值。求解后的读数在 {@code PhysicsTickListener#physicsTick}
 * 里枚举 manifold 取，{@code Collide.Processed} 不是求解完成事件。
 *
 * <p><b>本类把三轴转动全锁。</b>圆柱与盒是单点接触，切向冲量作用在偏心点上；留 yaw 自由时
 * 摩擦约束解的是接触点的相对速度，它会先让机体绕 Y 转起来（实测 100 步后 yaw −175.6°），
 * 切向冲量只到 5.7581 N·s，没吃到 6.867 N·s 的额度，机体因此以 0.39 m/s 蠕行——那是单点接触
 * 加自由 yaw 的偏转，不是摩擦系数的问题。生产控制器每步绝对写入 yaw
 * （{@code common/control/MechaCharacter.java#writeFacing}），本用例用全锁复现「步内没有转动
 * 自由度」这一条件，让读数只反映系数写入本身。
 *
 * @author Sweetzonzi
 */
@Tag("measurement")
class GroundDriveRefactorMeasurementTest {

    /** 与生产一致的物理步长：100 Hz。 */
    private static final float DT = 1f / 100f;

    /** 腿力基线：600 N 低于平地 μ_pair_base·mg = 686.7 N。 */
    private static final float DRIVE_FORCE = 600f;

    /** 地形侧取 Bullet 默认 0.5，与 {@code MechaWalkingAttr#TERRAIN_FRICTION} 一致。 */
    private static final float GROUND_FRICTION = 0.5f;

    /** 角色侧 2.0：组合 {@code 0.5 × 2.0 = 1.0}，即 μ_pair_base = 1.0。 */
    private static final float BODY_FRICTION = 2.0f;

    /** 静置若干步，让接触点集合稳定下来再施力。 */
    private static final int SETTLE_STEPS = 20;

    /** 施力 1 s。 */
    private static final int DRIVE_STEPS = 100;

    /**
     * 600 N 腿力起步时，按接触点写入的组合摩擦决定能不能动、能拿到多少加速度。
     *
     * <p>四个档位各跑一次 {@link #DRIVE_STEPS} 步：不写（保持组合值 1.0）、写 0、写 0.2、写回 1.0。
     * 解析预期：写 0 时净力 600 N，1 s 后 8.57 m/s；写 0.2 时切向上限 137.3 N，净力 462.7 N，
     * 1 s 后 6.61 m/s；组合值 1.0 时切向预算 686.7 N 高于 600 N，机体应基本不动。
     */
    @Test
    void measureForceDriveAndFrictionHold() {
        Trial none = run(Float.NaN);
        Trial zero = run(0f);
        Trial low = run(0.2f);
        Trial base = run(1.0f);

        report("不写", none);
        report("写 0", zero);
        report("写 0.2", low);
        report("写 1.0", base);

        // 不写时沿用材质组合值 0.5 × 2.0 = 1.0
        assertEquals(1.0f, none.readBack(), 1e-6f, "未覆盖时点内的组合摩擦应是双方之积");
        // 组合值 1.0 时切向预算 686.7 N 高于 600 N：求解器给出的切向冲量正好等于腿力冲量 6 N·s
        assertEquals(6.0f, none.meanLateralImpulse(), 1e-3f,
                "保持时切向冲量应正好抵掉 600 N × 0.01 s，实际 " + none.meanLateralImpulse());
        assertTrue(Math.abs(none.finalSpeed()) < 0.01f,
                "组合摩擦 1.0 时 600 N 腿力不应把机体推起来，实际 vx = " + none.finalSpeed());

        // 写 0 之后没有切向阻力，净力 600 N，1 s 的解析值为 8.571 m/s
        assertTrue(zero.processed() > 0, "持久点路径必须被调用，否则写不进每步的求解");
        assertEquals(8.5714f, zero.finalSpeed(), 0.01f,
                "写 0 之后净力 600 N，实际 vx = " + zero.finalSpeed());
        assertEquals(0.0f, zero.meanLateralImpulse(), 1e-3f,
                "组合摩擦为 0 时不应产生切向冲量，实际平均 " + zero.meanLateralImpulse());

        // 写 0.2 时上限 137.3 N，净力 462.7 N，1 s 的解析值为 6.61 m/s
        assertEquals(6.6094f, low.finalSpeed(), 0.02f,
                "写 0.2 时 1 s 后的速度应落在解析值 6.61 m/s 附近，实际 vx = " + low.finalSpeed());
        // 每步切向冲量被 0.2 × J_n = 1.3734 N·s 卡住，这就是「写入值决定本步上限」的直接读数
        assertEquals(1.3734f, low.meanLateralImpulse(), 1e-3f,
                "写 0.2 时切向冲量应等于 μ·J_n，实际 " + low.meanLateralImpulse());
        assertTrue(low.finalSpeed() < zero.finalSpeed(),
                "写 0.2 的切向上限高于 0，末速必须低于写 0 的那一档");

        // 写回基准组合值应与不写等价
        assertTrue(Math.abs(base.finalSpeed()) < 0.01f,
                "写回 1.0 应恢复静摩擦，实际 vx = " + base.finalSpeed());
        assertEquals(none.meanLateralImpulse(), base.meanLateralImpulse(), 1e-3f,
                "写回 1.0 与不写的切向冲量应相同");
    }

    /**
     * 驱动模型接入正式控制器后的整条链：曲线出力 → 一次裁剪 → 接触写入 → 制动 → 保持。
     *
     * <p>走路的是真的 {@link MechaCharacter}（质量、摩擦、着地判据、越障都与生产一致），
     * 驱动输入直接写入（{@code setDriveInput}），绕开 Part 属性来源那一层。
     * 步进带 conceived／processed 标志，并由 {@link DispatchingSpace} 按 {@code PhysicsWorld}
     * 的形态派发刚体事件 —— 裸 {@link PhysicsSpace} 自己不会派发，生产里这件事由
     * {@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/level/PhysicsWorld.kt#onContactProcessed}
     * 完成。
     */
    @Test
    void measureDriveModelThroughController() {
        DispatchingSpace space = new DispatchingSpace();
        PhysicsRigidBody ground = new PhysicsRigidBody(
                new BoxCollisionShape(new Vector3f(50f, 0.5f, 50f)), 0f);
        ground.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
        ground.setFriction(GROUND_FRICTION);
        ground.setRestitution(0f);
        ground.setCollisionGroup(CollisionGroups.TERRAIN);
        ground.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
        space.addCollisionObject(ground);

        MechaCharacter body = new MechaCharacter(MechaBodyPreset.newBodyShape(), space);
        body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
        space.addCollisionObject(body);
        // 慢走档：F = 600 N、V = 6 m/s。600 N 低于 μ_pair_base·mg = 686.7 N，
        // 旧力律靠 1% 前馈才能起步；新路径靠主动阶段把接触摩擦写成 0。
        body.setDriveInput(List.of(new LegDriveAttr(600f, 600f, 6f, 6f, 6f)),
                new double[]{1.0, 0.0, 0.0});

        int stepFlags = StepFlag.contactConceived | StepFlag.contactProcessed;
        body.setMoveIntent(1f, 0f);
        for (int i = 0; i < 100; i++) {
            body.prePhysicsTick(DT);
            space.update(DT, 0, stepFlags);
        }
        float afterDrive = horizontalSpeed(body);
        float driveFriction = space.lastSupportFriction;

        body.setMoveIntent(0f, 0f);
        float brakeFriction = Float.NaN;
        for (int i = 0; i < 100; i++) {
            body.prePhysicsTick(DT);
            space.update(DT, 0, stepFlags);
            // 取制动窗口前段的读数：窗口末端速度已进门内，那时写的是保持值
            if (i == 5) {
                brakeFriction = space.lastSupportFriction;
            }
        }
        float afterBrake = horizontalSpeed(body);

        for (int i = 0; i < 100; i++) {
            body.prePhysicsTick(DT);
            space.update(DT, 0, stepFlags);
        }
        float afterHold = horizontalSpeed(body);
        float holdFriction = space.lastSupportFriction;

        System.out.println("[驱动模型] 起步 1 s 后 |v| = " + fmt(afterDrive) + " m/s，支撑点组合摩擦 "
                + fmt(driveFriction));
        System.out.println("[驱动模型] 松键 1 s 后 |v| = " + fmt(afterBrake) + " m/s，支撑点组合摩擦 "
                + fmt(brakeFriction));
        System.out.println("[驱动模型] 再静置 1 s 后 |v| = " + fmt(afterHold) + " m/s，支撑点组合摩擦 "
                + fmt(holdFriction));

        // 主动阶段写 0：600 N 低于静摩擦上限也能起步
        assertEquals(0.0f, driveFriction, 1e-6f, "DRIVE 的支撑点组合摩擦应为 0");
        assertTrue(afterDrive > 1.0f, "1 s 后应明显起步，实际 |v| = " + afterDrive);
        // 松键进入制动，也是主动阶段
        assertEquals(0.0f, brakeFriction, 1e-6f, "BRAKE 的支撑点组合摩擦应为 0");
        assertTrue(afterBrake < 0.2f, "制动 1 s 后应基本停住，实际 |v| = " + afterBrake);
        // 停稳后保持门打开，把基准组合值写回接触点
        assertEquals(1.0f, holdFriction, 1e-6f, "保持时应写回基准组合值");
        assertTrue(afterHold < 0.2f, "保持期间不应重新滑动，实际 |v| = " + afterHold);
    }

    /**
     * 载荷缓存接进正式控制器后的整条链：Post 的支撑冲量 → 一步缓存 → 软压缩 → 本步预算。
     *
     * <p>测试没有 Level 与注册表，因此收集器在测试里就地实现一份（
     * {@link #supportNormalImpulse}），扫描口径与生产
     * {@code common/ArmsCoreServerEvents.java#collectSupportImpulses} 相同：只数本体参与、
     * {@code |n.y| ≥ MechaCharacter.SUPPORT_NORMAL_Y} 的点，求和后交给
     * {@code MechaCharacter#recordSupportImpulse}。
     *
     * <p>判据是「计划的等效载荷等于下一步该得到的那个值」：每一步都用上一步的求和复算
     * {@code softCompress(J/dt, m·g, κ, a)}，与控制器实际用的 {@code getLastEffectiveLoad()} 对齐。
     * 这条判据同时证明缓存被消费了、压缩被应用了，且没有样本时确实回落到 {@code m·g}。
     */
    @Test
    void measureSupportLoadCacheThroughController() {
        DispatchingSpace space = new DispatchingSpace();
        PhysicsRigidBody ground = new PhysicsRigidBody(
                new BoxCollisionShape(new Vector3f(50f, 0.5f, 50f)), 0f);
        ground.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
        ground.setFriction(GROUND_FRICTION);
        ground.setRestitution(0f);
        ground.setCollisionGroup(CollisionGroups.TERRAIN);
        ground.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
        space.addCollisionObject(ground);

        MechaCharacter body = new MechaCharacter(MechaBodyPreset.newBodyShape(), space);
        body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL + 0.02f, 0f));
        space.addCollisionObject(body);
        // 生产当前的两条腿（ArmsCore#getDrivingAttrs 的硬编码值），常速档权重 1
        List<LegDriveAttr> legs = List.of(
                new LegDriveAttr(350f, 350f, 1.6f, 4.0f, 6.0f),
                new LegDriveAttr(350f, 350f, 1.6f, 4.0f, 6.0f));
        body.setDriveInput(legs, new double[]{1.0, 0.0, 0.0});

        double gravityLoad = body.getMass() * MechaWalkingAttr.GRAVITY;
        int stepFlags = StepFlag.contactConceived | StepFlag.contactProcessed;
        double previousImpulse = Double.NaN;
        double peakLoad = 0.0;

        for (int step = 0; step < 60; step++) {
            body.prePhysicsTick(DT);
            space.update(DT, 0, stepFlags);
            double impulse = supportNormalImpulse(space, body);
            body.recordSupportImpulse(impulse, DT);

            if (body.isOnGround()) {
                double expectedEffective = previousImpulse > 0.0
                        ? ContactBudget.softCompress(previousImpulse / DT, gravityLoad,
                                MechaWalkingAttr.LOAD_KNEE, MechaWalkingAttr.LOAD_LOG_SCALE)
                        : gravityLoad;
                assertEquals(expectedEffective, body.getLastEffectiveLoad(), 1e-3,
                        "第 " + step + " 步的等效载荷应由上一步的冲量算出");
                peakLoad = Math.max(peakLoad, body.getLastEffectiveLoad());
                if (step < 12) {
                    System.out.println("[载荷缓存] 步 " + step
                            + "：本步 J = " + fmt((float) impulse) + " N·s"
                            + "，上一步 J = " + fmt((float) previousImpulse) + " N·s"
                            + "，N_eff = " + fmt((float) body.getLastEffectiveLoad()) + " N"
                            + "（静态 " + fmt((float) gravityLoad) + " N）");
                }
            }
            previousImpulse = impulse;
        }

        System.out.println("[载荷缓存] 60 步内的峰值等效载荷 " + fmt((float) peakLoad) + " N；末步 N_eff = "
                + fmt((float) body.getLastEffectiveLoad()) + " N（静态 " + fmt((float) gravityLoad) + " N）");
        assertEquals(gravityLoad, body.getLastEffectiveLoad(), 0.02 * gravityLoad,
                "静置后缓存值与静态载荷一致，实际 " + body.getLastEffectiveLoad());
        assertTrue(peakLoad >= gravityLoad, "峰值不应低于静态载荷");
    }

    /** 与生产收集器同一口径的支撑法向冲量之和（N·s）。 */
    private static double supportNormalImpulse(PhysicsSpace space, PhysicsRigidBody body) {
        long bodyId = body.nativeId();
        Vector3f normal = new Vector3f();
        double sum = 0.0;
        for (long manifoldId : space.listManifoldIds()) {
            if (PersistentManifolds.getBodyAId(manifoldId) != bodyId
                    && PersistentManifolds.getBodyBId(manifoldId) != bodyId) {
                continue;
            }
            for (long pointId : PersistentManifolds.listPointIds(manifoldId)) {
                ManifoldPoints.getNormalWorldOnB(pointId, normal);
                if (Math.abs(normal.y) < MechaCharacter.SUPPORT_NORMAL_Y) {
                    continue;
                }
                sum += Math.abs(ManifoldPoints.getAppliedImpulse(pointId));
            }
        }
        return sum;
    }

    /** 正式控制器的直角转向：两档附着、旋转输入基及两种侧速符号。 */
    @Test
    void measureSideBrakingThroughController() {
        float fullGripSide = sideBrakeTrial(0f, 3f, 0.5f);
        float lowGripSide = sideBrakeTrial(0f, 3f, 0.05f);
        float rotatedSide = sideBrakeTrial(90f, 3f, 0.5f);
        float oppositeSide = sideBrakeTrial(0f, -3f, 0.5f);
        assertTrue(Math.abs(fullGripSide) < 0.05f, "正常附着下转向 1 s 应消除旧方向侧速");
        assertTrue(lowGripSide > 1f, "低附着应保留明显侧滑，不能无视预算瞬间转向");
        assertEquals(fullGripSide, rotatedSide, 0.02f, "转动输入坐标系不应改变侧向制动");
        assertEquals(fullGripSide, -oppositeSide, 0.02f, "侧向制动应关于零速对称");
    }

    private static float sideBrakeTrial(float yawDegrees, float initialSideSpeed, float terrainFriction) {
        DispatchingSpace space = new DispatchingSpace();
        try {
            PhysicsRigidBody ground = new PhysicsRigidBody(
                    new BoxCollisionShape(new Vector3f(50f, 0.5f, 50f)), 0f);
            ground.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
            ground.setFriction(terrainFriction);
            ground.setCollisionGroup(CollisionGroups.TERRAIN);
            ground.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
            space.addCollisionObject(ground);
            MechaCharacter body = new MechaCharacter(MechaBodyPreset.newBodyShape(), space) {
                @Override
                protected float getEffectiveFriction() {
                    // 软件附着预算通过现有配置钩子给入，与本夹具地面的组合值一致。
                    return BODY_FRICTION * terrainFriction;
                }
            };
            body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
            body.setDriveInput(List.of(new LegDriveAttr(600f, 600f, 6f, 6f, 6f)),
                    new double[]{1.0, 0.0, 0.0});
            body.setViewYaw(yawDegrees);
            space.addCollisionObject(body);
            int flags = StepFlag.contactConceived | StepFlag.contactProcessed;
            for (int i = 0; i < SETTLE_STEPS; i++) {
                body.prePhysicsTick(DT);
                space.update(DT, 0, flags);
                body.recordSupportImpulse(supportNormalImpulse(space, body), DT);
            }

            double yaw = Math.toRadians(yawDegrees);
            Vector3f alongAxis = new Vector3f((float) -Math.sin(yaw), 0f, (float) Math.cos(yaw));
            Vector3f sideAxis = new Vector3f(-alongAxis.z, 0f, alongAxis.x);
            body.setLinearVelocity(sideAxis.mult(initialSideSpeed));
            body.setMoveIntent(1f, 0f);
            float side = initialSideSpeed;
            for (int i = 0; i < DRIVE_STEPS; i++) {
                body.prePhysicsTick(DT);
                space.update(DT, 0, flags);
                body.recordSupportImpulse(supportNormalImpulse(space, body), DT);
                side = body.getLinearVelocity(null).dot(sideAxis);
                assertTrue(side * Math.signum(initialSideSpeed) >= -0.002f,
                        "侧向制动不应穿过零后反向加速，实际 " + side);
            }
            float along = body.getLinearVelocity(null).dot(alongAxis);
            assertTrue(along > 0.1f, "收侧速的同时应沿新输入方向起步");
            assertEquals(0f, space.lastSupportFriction, 1e-6f,
                    "有输入时侧向制动应由显式腿力完成，原生滑动摩擦保持为零");
            System.out.println("[侧向制动] yaw=" + yawDegrees + "°，组合 μ=" + 2f * terrainFriction
                    + "，初始侧速=" + initialSideSpeed + " m/s，1 s 后侧速=" + fmt(side)
                    + " m/s，沿向=" + fmt(along) + " m/s");
            return side;
        } finally {
            space.destroy();
        }
    }

    /** 内置三档参数的长平地标定：末秒位移速率、求解器速度与持续支撑一起核对。 */
    @Test
    void measureBuiltinGroundSpeeds() {
        // 参数对照 common/ArmsCore.java#DRIVE_ATTRS；这里显式固定标定条件，保留可重复的读数。
        List<LegDriveAttr> legs = List.of(
                new LegDriveAttr(350f, 350f, 1.6f, 4.317f, 5.612f, 1f),
                new LegDriveAttr(350f, 350f, 1.6f, 4.317f, 5.612f, 1f));
        for (int gear = 0; gear < GearResponse.GEARS.size(); gear++) {
            DispatchingSpace space = new DispatchingSpace();
            try {
                PhysicsRigidBody ground = new PhysicsRigidBody(
                        new BoxCollisionShape(new Vector3f(250f, 0.5f, 250f)), 0f);
                ground.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
                ground.setFriction(GROUND_FRICTION);
                ground.setCollisionGroup(CollisionGroups.TERRAIN);
                ground.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
                space.addCollisionObject(ground);
                MechaCharacter body = new MechaCharacter(MechaBodyPreset.newBodyShape(), space);
                body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
                space.addCollisionObject(body);
                int flags = StepFlag.contactConceived | StepFlag.contactProcessed;
                body.setDriveInput(legs, GearResponse.zeroWeights());
                for (int i = 0; i < SETTLE_STEPS; i++) {
                    body.prePhysicsTick(DT);
                    space.update(DT, 0, flags);
                    body.recordSupportImpulse(supportNormalImpulse(space, body), DT);
                }
                body.setMoveIntent(1f, 0f);
                double[] weights = GearResponse.zeroWeights();
                double alpha = GearResponse.alpha(DT, MechaWalkingAttr.RESPONSE_TAU);
                float lastSecondStart = Float.NaN;
                float previousSpeed = 0f;
                float acceleration = Float.NaN;
                for (int i = 0; i < 1200; i++) {
                    if (i == 1100) {
                        lastSecondStart = body.getPhysicsLocation(null).z;
                    }
                    weights = GearResponse.step(weights, alpha, gear);
                    body.setDriveInput(legs, weights);
                    body.prePhysicsTick(DT);
                    space.update(DT, 0, flags);
                    body.recordSupportImpulse(supportNormalImpulse(space, body), DT);
                    assertTrue(body.isOnGround(), "长平地标定的全部采样步必须着地");
                    float speed = horizontalSpeed(body);
                    acceleration = (speed - previousSpeed) / DT;
                    previousSpeed = speed;
                }
                float displacementSpeed = (body.getPhysicsLocation(null).z - lastSecondStart) / (100f * DT);
                double target = legs.getFirst().noLoadSpeed(GearResponse.GEARS.get(gear));
                assertEquals(target, previousSpeed, 0.02, "末步速率应接近力速曲线零点");
                assertEquals(target, displacementSpeed, 0.02, "末秒实际位移应匹配目标速率");
                assertEquals(0f, acceleration, 0.02f, "末段应已收敛，不能以加速中的读数作为顶速");
                System.out.println("[内置地面速度] 档位=" + GearResponse.GEARS.get(gear)
                        + "，目标=" + fmt((float) target) + " m/s，12 s 末步=" + fmt(previousSpeed)
                        + " m/s，末秒位移速率=" + fmt(displacementSpeed) + " m/s，末步加速度="
                        + fmt(acceleration) + " m/s²，始终着地");
            } finally {
                space.destroy();
            }
        }
    }

    /** 低速辅助在正式控制器上的停车距离、转向残留及材质差异。 */
    @Test
    void measureBrakeAssistThroughController() {
        BrakeTrial baseStop = brakeAssistTrial(0f, 0.5f, false);
        BrakeTrial assistedStop = brakeAssistTrial(1f, 0.5f, false);
        BrakeTrial baseTurn = brakeAssistTrial(0f, 0.5f, true);
        BrakeTrial assistedTurn = brakeAssistTrial(1f, 0.5f, true);
        BrakeTrial lowGripTurn = brakeAssistTrial(1f, 0.05f, true);
        assertTrue(assistedStop.distance() < baseStop.distance() * 0.8f,
                "从慢跑速度松键，辅助应明显缩短滑行距离");
        assertTrue(assistedTurn.speedAtPointTwo() < baseTurn.speedAtPointTwo() * 0.8f,
                "直角转向 0.2 s 的旧方向残速应明显降低");
        assertTrue(lowGripTurn.speedAtPointTwo() > assistedTurn.speedAtPointTwo(),
                "辅助仍应保留低附着地面的侧滑差异");
        assertEquals(0f, assistedStop.finalSpeed(), 0.01f, "辅助停车后应由保持门稳定站住");
        assertTrue(assistedTurn.alongAtPointTwo() > 0.1f, "收侧速时应沿新输入方向起步");
    }

    private record BrakeTrial(float speedAtPointTwo, float alongAtPointTwo,
                              float finalSpeed, float distance) {
    }

    private static BrakeTrial brakeAssistTrial(float assist, float terrainFriction, boolean turn) {
        DispatchingSpace space = new DispatchingSpace();
        try {
            PhysicsRigidBody ground = new PhysicsRigidBody(
                    new BoxCollisionShape(new Vector3f(50f, 0.5f, 50f)), 0f);
            ground.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
            ground.setFriction(terrainFriction);
            ground.setCollisionGroup(CollisionGroups.TERRAIN);
            ground.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
            space.addCollisionObject(ground);
            MechaCharacter body = new MechaCharacter(MechaBodyPreset.newBodyShape(), space) {
                @Override
                protected float getEffectiveFriction() {
                    return BODY_FRICTION * terrainFriction;
                }
            };
            body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
            body.setDriveInput(List.of(new LegDriveAttr(700f, 700f, 1.6f, 4f, 6f, assist)),
                    new double[]{0.0, 1.0, 0.0});
            space.addCollisionObject(body);
            int flags = StepFlag.contactConceived | StepFlag.contactProcessed;
            for (int i = 0; i < SETTLE_STEPS; i++) {
                body.prePhysicsTick(DT);
                space.update(DT, 0, flags);
                body.recordSupportImpulse(supportNormalImpulse(space, body), DT);
            }
            // 无输入沿世界 Z 停车；有输入沿世界 Z 推进并消除世界 X 的初始侧速。
            Vector3f brakeAxis = turn ? new Vector3f(1f, 0f, 0f) : new Vector3f(0f, 0f, 1f);
            Vector3f start = body.getPhysicsLocation(null);
            body.setLinearVelocity(brakeAxis.mult(4f));
            body.setMoveIntent(turn ? 1f : 0f, 0f);
            float speed = 4f;
            float checkpointSpeed = Float.NaN;
            float checkpointAlong = Float.NaN;
            for (int i = 0; i < DRIVE_STEPS; i++) {
                body.prePhysicsTick(DT);
                space.update(DT, 0, flags);
                body.recordSupportImpulse(supportNormalImpulse(space, body), DT);
                Vector3f velocity = body.getLinearVelocity(null);
                speed = velocity.dot(brakeAxis);
                assertTrue(speed >= -0.002f, "辅助制动不应自行反向加速，实际 " + speed);
                if (i == 19) {
                    checkpointSpeed = speed;
                    checkpointAlong = turn ? velocity.z : 0f;
                }
            }
            float distance = body.getPhysicsLocation(null).subtract(start).dot(brakeAxis);
            assertEquals(turn ? 0f : BODY_FRICTION * terrainFriction,
                    space.lastSupportFriction, 1e-6f);
            System.out.println("[制动辅助] assist=" + assist + "，组合 μ=" + BODY_FRICTION * terrainFriction
                    + "，转向=" + turn + "，初速=4 m/s，0.2 s 残速=" + fmt(checkpointSpeed)
                    + " m/s，沿向=" + fmt(checkpointAlong) + " m/s，1 s 残速=" + fmt(speed)
                    + " m/s，旧方向行程=" + fmt(distance) + " m");
            return new BrakeTrial(checkpointSpeed, checkpointAlong, speed, distance);
        } finally {
            space.destroy();
        }
    }

    /**
     * 支撑法向冲量在一步之内何时可见。
     *
     * <p>{@code Collide.Pre} 与 {@code Collide.Processed} 都在窄相里、求解器之前：那一刻点上读到的
     * {@code appliedImpulse} 是<b>上一步</b>求解写下的值（warm start 的起点），本步的值要等求解完成
     * 才存在。本用例让圆柱从高出地面 2 cm 处落下并静置，逐步打印两侧的和：窄相里读到的（{@code pre}）
     * 与求解后枚举 manifold 得到的（{@code post}），并与静置值 {@code m·g·dt = 6.8670 N·s} 对照。
     *
     * <p>读数回答三件事：{@code pre} 是否等于上一步的 {@code post}（滞后一步）、新接触点的第一步
     * 是否有值（没有）、以及落地那一步的 {@code post} 是否是静置值的数倍（是，落地冲击）。
     */
    @Test
    void measureSupportImpulsePhases() {
        ImpulsePhaseSpace space = new ImpulsePhaseSpace();
        PhysicsRigidBody ground = new PhysicsRigidBody(
                new BoxCollisionShape(new Vector3f(50f, 0.5f, 50f)), 0f);
        ground.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
        ground.setFriction(GROUND_FRICTION);
        ground.setRestitution(0f);
        ground.setCollisionGroup(CollisionGroups.TERRAIN);
        ground.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
        space.addCollisionObject(ground);

        PhysicsRigidBody body = new PhysicsRigidBody(MechaBodyPreset.newBodyShape(), MechaWalkingAttr.MASS);
        // 落 2 cm：以 100 Hz 计约 6 步，落地那一步是「新点」而不是持久点
        body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL + 0.02f, 0f));
        body.setAngularFactor(new Vector3f(0f, 0f, 0f));
        body.setAngularDamping(0f);
        body.setLinearDamping(0f);
        body.setFriction(BODY_FRICTION);
        body.setRestitution(0f);
        body.setCollisionGroup(CollisionGroups.PAWN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN);
        space.addCollisionObject(body);
        space.subject = body;

        System.out.println("[冲量相位] 静置值 m·g·dt = "
                + fmt(MechaWalkingAttr.MASS * MechaWalkingAttr.GRAVITY * DT) + " N·s");

        int stepFlags = StepFlag.contactConceived | StepFlag.contactProcessed;
        float previousPost = Float.NaN;
        for (int step = 0; step < 14; step++) {
            space.beginStep();
            space.update(DT, 0, stepFlags);
            space.endStep();
            System.out.println("[冲量相位] 步 " + step
                    + " : pre = " + fmt(space.preSum) + "（" + space.prePoints + " 点，"
                    + space.conceivedThisStep + " 个新点）"
                    + "，post = " + fmt(space.postSum) + "（" + space.postPoints + " 点）"
                    + "，上一 post = " + fmt(previousPost));
            if (step > 0) {
                // 窄相里读到的和就是上一步求解留下的那份：滞后一步
                assertEquals(previousPost, space.preSum, 2e-3f,
                        "第 " + step + " 步窄相读到的和应等于上一步求解的和");
            }
            previousPost = space.postSum;
        }

        System.out.println("[冲量相位] 首个新点在第 " + space.firstConceivedStep + " 步，该点窄相读数 "
                + fmt(space.newPointImpulse) + " N·s");
        assertEquals(0f, space.newPointImpulse, 1e-6f,
                "新接触点在窄相里读到的是 0，实际 " + space.newPointImpulse);
        // 静置段：求解后的和就是静置反力，且窄相读到的与它重合
        assertEquals(MechaWalkingAttr.MASS * MechaWalkingAttr.GRAVITY * DT, space.postSum, 0.05f,
                "静置后求解出的法向冲量应等于 m·g·dt，实际 " + space.postSum);
        assertEquals(space.postSum, space.preSum, 2e-3f,
                "静置时窄相与求解后应重合，实际 pre = " + space.preSum + "，post = " + space.postSum);
    }

    private static float horizontalSpeed(PhysicsRigidBody body) {
        Vector3f velocity = body.getLinearVelocity(null);
        return (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    /**
     * 按 {@code PhysicsWorld} 的形态派发刚体接触事件的裸空间：裸 {@link PhysicsSpace} 只回调
     * {@code onContactProcessed}／{@code onContactConceived}，不会把事件送到刚体的事件表。
     */
    private static final class DispatchingSpace extends PhysicsSpace {

        /** 最近一次写进支撑点的组合摩擦，供读数用。 */
        float lastSupportFriction = Float.NaN;

        private DispatchingSpace() {
            super(new Vector3f(-50f, -50f, -50f), new Vector3f(50f, 50f, 50f));
        }

        @Override
        public void onContactProcessed(PhysicsCollisionObject pcoA, PhysicsCollisionObject pcoB,
                                       long manifoldPointId) {
            record(pcoA, pcoB, manifoldPointId);
            fire(pcoA, new PhysicsBodyEvent.Collide.Processed(pcoA, pcoB,
                    new ManifoldPoint(manifoldPointId, 0), new ManifoldPoint(manifoldPointId, 1)));
            fire(pcoB, new PhysicsBodyEvent.Collide.Processed(pcoB, pcoA,
                    new ManifoldPoint(manifoldPointId, 1), new ManifoldPoint(manifoldPointId, 0)));
        }

        @Override
        public boolean onContactConceived(long manifoldPointId, long manifoldId,
                                          PhysicsCollisionObject pcoA, PhysicsCollisionObject pcoB) {
            record(pcoA, pcoB, manifoldPointId);
            PhysicsBodyEvent.Collide.Pre first = new PhysicsBodyEvent.Collide.Pre(pcoA, pcoB,
                    new ManifoldPoint(manifoldPointId, 0), new ManifoldPoint(manifoldPointId, 1), false);
            PhysicsBodyEvent.Collide.Pre second = new PhysicsBodyEvent.Collide.Pre(pcoB, pcoA,
                    new ManifoldPoint(manifoldPointId, 1), new ManifoldPoint(manifoldPointId, 0), false);
            fire(pcoA, first);
            fire(pcoB, second);
            return !first.getCanceled() && !second.getCanceled();
        }

        /** 只记本体参与的支撑点（法线朝上）。 */
        private void record(PhysicsCollisionObject pcoA, PhysicsCollisionObject pcoB, long pointId) {
            if (!(pcoA instanceof MechaCharacter) && !(pcoB instanceof MechaCharacter)) {
                return;
            }
            Vector3f normal = new Vector3f();
            ManifoldPoints.getNormalWorldOnB(pointId, normal);
            if (Math.abs(normal.y) < 0.5f) {
                return;
            }
            lastSupportFriction = ManifoldPoints.getCombinedFriction(pointId);
        }

        @SuppressWarnings("unchecked")
        private static void fire(PhysicsCollisionObject target, PhysicsBodyEvent.Collide event) {
            Class<? extends PhysicsBodyEvent> eventClass = event instanceof PhysicsBodyEvent.Collide.Pre
                    ? PhysicsBodyEvent.Collide.Pre.class
                    : PhysicsBodyEvent.Collide.Processed.class;
            KClass<? extends PhysicsBodyEvent> key = JvmClassMappingKt.getKotlinClass(eventClass);
            List<InlineEventConsumer<? extends PhysicsBodyEvent>> consumers =
                    target.getEventHandlers().get(key);
            if (consumers == null) {
                return;
            }
            for (InlineEventConsumer<? extends PhysicsBodyEvent> consumer : consumers) {
                ((InlineEventConsumer<PhysicsBodyEvent.Collide>) consumer).invoke(event);
            }
        }
    }

    /**
     * 记录法向冲量在两个相位上的空间：窄相回调（求解前）与 {@link PhysicsTickListener#physicsTick}（求解后）。
     *
     * <p>只数本体参与、法线朝上的点（{@code |n.y| ≥ 0.5}），与生产判据同一条门。
     */
    private static final class ImpulsePhaseSpace extends PhysicsSpace {

        /** 只统计本体参与的接触对；窄相回调是全空间的。 */
        private PhysicsCollisionObject subject;

        private final Vector3f tmpNormal = new Vector3f();

        /** 本步在窄相里读到的法向冲量之和（N·s），即上一步求解的结果。 */
        private float preSum;
        private int prePoints;
        /** 本步求解之后枚举 manifold 得到的法向冲量之和（N·s）。 */
        private float postSum;
        private int postPoints;
        /** 本步新接触点数。 */
        private int conceivedThisStep;
        /** 第一次出现新接触点的步序号；{@code -1} 表示还没有。 */
        private int firstConceivedStep = -1;
        /** 第一个新接触点在窄相里读到的冲量（N·s）。 */
        private float newPointImpulse = Float.NaN;
        private int stepIndex;

        private ImpulsePhaseSpace() {
            super(new Vector3f(-50f, -50f, -50f), new Vector3f(50f, 50f, 50f));
            addTickListener(new PhysicsTickListener() {
                @Override
                public void prePhysicsTick(PhysicsSpace space, float timeStep) {
                }

                @Override
                public void physicsTick(PhysicsSpace space, float timeStep) {
                    collectSolved();
                }
            });
        }

        private void beginStep() {
            preSum = 0f;
            prePoints = 0;
            conceivedThisStep = 0;
            postSum = 0f;
            postPoints = 0;
        }

        private void endStep() {
            stepIndex++;
        }

        @Override
        public void onContactProcessed(PhysicsCollisionObject pcoA, PhysicsCollisionObject pcoB,
                                       long manifoldPointId) {
            if (subject == null || (pcoA != subject && pcoB != subject)) {
                return;
            }
            ManifoldPoints.getNormalWorldOnB(manifoldPointId, tmpNormal);
            if (Math.abs(tmpNormal.y) < 0.5f) {
                return;
            }
            preSum += Math.abs(ManifoldPoints.getAppliedImpulse(manifoldPointId));
            prePoints++;
        }

        @Override
        public boolean onContactConceived(long pointId, long manifoldId,
                                          PhysicsCollisionObject pcoA, PhysicsCollisionObject pcoB) {
            if (subject != null && (pcoA == subject || pcoB == subject)) {
                conceivedThisStep++;
                if (firstConceivedStep < 0) {
                    firstConceivedStep = stepIndex;
                    newPointImpulse = Math.abs(ManifoldPoints.getAppliedImpulse(pointId));
                }
            }
            return true;
        }

        /** 求解之后的读数：枚举本体的 manifold，按同一条法线门求和。 */
        private void collectSolved() {
            if (subject == null) {
                return;
            }
            long subjectId = subject.nativeId();
            for (long manifoldId : listManifoldIds()) {
                if (PersistentManifolds.getBodyAId(manifoldId) != subjectId
                        && PersistentManifolds.getBodyBId(manifoldId) != subjectId) {
                    continue;
                }
                for (long pointId : PersistentManifolds.listPointIds(manifoldId)) {
                    ManifoldPoints.getNormalWorldOnB(pointId, tmpNormal);
                    if (Math.abs(tmpNormal.y) < 0.5f) {
                        continue;
                    }
                    postSum += Math.abs(ManifoldPoints.getAppliedImpulse(pointId));
                    postPoints++;
                }
            }
        }
    }

    private static void report(String label, Trial trial) {
        System.out.println("[摩擦写入] " + label
                + " : vx = " + fmt(trial.finalSpeed()) + " m/s"
                + "，yaw = " + fmt(trial.yawDegrees()) + "°"
                + "，conceived = " + trial.conceived()
                + "，processed = " + trial.processed()
                + "，平均 J_n = " + fmt(trial.meanNormalImpulse()) + " N·s"
                + "，平均 |J_t| = " + fmt(trial.meanLateralImpulse()) + " N·s"
                + "，点内读到的组合值 = " + fmt(trial.readBack()));
    }

    // ═══════════════════════════════════════════════
    // 场景搭建
    // ═══════════════════════════════════════════════

    /**
     * 跑一次：平地 + 控制器圆柱，施力 {@code DRIVE_FORCE} 向 +X 共 {@link #DRIVE_STEPS} 步。
     *
     * @param write 写进每个接触点的组合摩擦；{@link Float#NaN} 表示不写，保持材质组合值
     */
    private static Trial run(float write) {
        FrictionOverrideSpace space = new FrictionOverrideSpace(write);
        PhysicsRigidBody ground = new PhysicsRigidBody(
                new BoxCollisionShape(new Vector3f(50f, 0.5f, 50f)), 0f);
        ground.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
        ground.setFriction(GROUND_FRICTION);
        ground.setRestitution(0f);
        ground.setCollisionGroup(CollisionGroups.TERRAIN);
        ground.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
        space.addCollisionObject(ground);

        PhysicsRigidBody body = new PhysicsRigidBody(MechaBodyPreset.newBodyShape(), MechaWalkingAttr.MASS);
        body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
        body.setAngularFactor(new Vector3f(0f, 0f, 0f));
        body.setAngularDamping(0f);
        body.setLinearDamping(0f);
        body.setFriction(BODY_FRICTION);
        body.setRestitution(0f);
        body.setCollisionGroup(CollisionGroups.PAWN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN);
        space.addCollisionObject(body);
        space.subject = body;

        int stepFlags = StepFlag.contactConceived | StepFlag.contactProcessed;
        for (int i = 0; i < SETTLE_STEPS; i++) {
            space.update(DT, 0, stepFlags);
        }
        space.resetImpelSeSums();

        Vector3f force = new Vector3f(DRIVE_FORCE, 0f, 0f);
        for (int i = 0; i < DRIVE_STEPS; i++) {
            body.applyCentralForce(force);
            space.update(DT, 0, stepFlags);
        }

        Matrix3f rotation = body.getPhysicsRotationMatrix(new Matrix3f());
        float yaw = (float) Math.toDegrees(Math.atan2(rotation.get(0, 2), rotation.get(2, 2)));
        return new Trial(body.getLinearVelocity(null).x, yaw, space.conceived, space.processed,
                space.readBack, space.meanNormalImpulse(), space.meanLateralImpulse());
    }

    private static String fmt(float value) {
        if (Float.isNaN(value)) {
            return "NaN";
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    /**
     * 一次测量的读数。
     *
     * @param finalSpeed        100 步之后的 +X 速度（m/s）
     * @param yawDegrees        100 步之后的 yaw 角（度）
     * @param conceived         新点回调次数
     * @param processed         持久点回调次数
     * @param readBack          未覆盖时在回调里读到的组合摩擦
     * @param meanNormalImpulse 施力期间每步的求解后法向冲量均值（N·s）
     * @param meanLateralImpulse 施力期间每步的求解后切向冲量模长均值（N·s）
     */
    private record Trial(float finalSpeed, float yawDegrees, int conceived, int processed,
                         float readBack, float meanNormalImpulse, float meanLateralImpulse) {
    }

    /**
     * 在求解器之前按点写组合摩擦、在求解之后读冲量的空间。
     *
     * <p>{@code onContactConceived} 是新点（{@code Collide.Pre}），{@code onContactProcessed} 是每步的
     * 持久点（{@code Collide.Processed}），两者都早于求解；回调只在步进带上
     * {@link StepFlag#contactConceived}／{@link StepFlag#contactProcessed} 时才会被原生侧调用。
     * 求解后的读数在 {@link PhysicsTickListener#physicsTick} 里按 manifold 枚举，步长沿用本步。
     */
    private static final class FrictionOverrideSpace extends PhysicsSpace {

        private final float write;
        /** 只统计本体参与的接触对；回调是全空间的。 */
        private PhysicsCollisionObject subject;
        private int conceived;
        private int processed;
        private float readBack = Float.NaN;
        private double normalImpulseSum;
        private double lateralImpulseSum;
        private int impulseSteps;

        private FrictionOverrideSpace(float write) {
            super(new Vector3f(-50f, -50f, -50f), new Vector3f(50f, 50f, 50f));
            this.write = write;
            addTickListener(new PhysicsTickListener() {
                @Override
                public void prePhysicsTick(PhysicsSpace space, float timeStep) {
                }

                @Override
                public void physicsTick(PhysicsSpace space, float timeStep) {
                    collectSolvedImpulses();
                }
            });
        }

        @Override
        public boolean onContactConceived(long pointId, long manifoldId,
                                          PhysicsCollisionObject pcoA, PhysicsCollisionObject pcoB) {
            if (involvesSubject(pcoA, pcoB)) {
                conceived++;
                apply(pointId);
            }
            return true;
        }

        @Override
        public void onContactProcessed(PhysicsCollisionObject pcoA, PhysicsCollisionObject pcoB,
                                       long manifoldPointId) {
            if (involvesSubject(pcoA, pcoB)) {
                processed++;
                apply(manifoldPointId);
            }
        }

        /** 求解后的法向与切向冲量：只数本体参与、法线朝上的点。 */
        private void collectSolvedImpulses() {
            if (subject == null) {
                return;
            }
            long subjectId = subject.nativeId();
            double normal = 0.0;
            double lateral = 0.0;
            for (long manifoldId : listManifoldIds()) {
                if (PersistentManifolds.getBodyAId(manifoldId) != subjectId
                        && PersistentManifolds.getBodyBId(manifoldId) != subjectId) {
                    continue;
                }
                for (long pointId : PersistentManifolds.listPointIds(manifoldId)) {
                    normal += Math.abs(ManifoldPoints.getAppliedImpulse(pointId));
                    lateral += Math.hypot(ManifoldPoints.getAppliedImpulseLateral1(pointId),
                            ManifoldPoints.getAppliedImpulseLateral2(pointId));
                }
            }
            normalImpulseSum += normal;
            lateralImpulseSum += lateral;
            impulseSteps++;
        }

        private float meanNormalImpulse() {
            return impulseSteps == 0 ? Float.NaN : (float) (normalImpulseSum / impulseSteps);
        }

        private float meanLateralImpulse() {
            return impulseSteps == 0 ? Float.NaN : (float) (lateralImpulseSum / impulseSteps);
        }

        private void resetImpelSeSums() {
            normalImpulseSum = 0.0;
            lateralImpulseSum = 0.0;
            impulseSteps = 0;
        }

        private boolean involvesSubject(PhysicsCollisionObject pcoA, PhysicsCollisionObject pcoB) {
            return subject != null && (pcoA == subject || pcoB == subject);
        }

        private void apply(long pointId) {
            if (Float.isNaN(write)) {
                if (Float.isNaN(readBack)) {
                    readBack = ManifoldPoints.getCombinedFriction(pointId);
                }
                return;
            }
            ManifoldPoints.setCombinedFriction(pointId, write);
        }
    }
}
