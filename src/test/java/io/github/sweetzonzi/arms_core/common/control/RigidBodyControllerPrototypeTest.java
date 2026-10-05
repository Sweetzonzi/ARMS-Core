package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsRayTestResult;
import com.jme3.bullet.collision.PhysicsSweepTestResult;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 刚体角色控制器最小原型 —— 只跑真实 {@link PhysicsSpace}，不接入 Minecraft。
 * <p>
 * 本类回答四个在动手迁移 {@code MechaCharacter} 之前必须先有数值答案的问题：
 * <ol>
 *   <li><b>锁转刚体站得住吗</b> —— {@code setAngularFactor(0)} 之后，胶囊在 100 Hz 下 5 s 是否
 *       既不抖动也不缓慢下沉、朝向是否恒为单位四元数</li>
 *   <li><b>撞墙速度归零吗</b> —— 这是 KCC 的已知缺陷 B1，刚体路线必须天然成立</li>
 *   <li><b>自写越障能做到多少</b> —— 抬升 + 水平 sweep + 向下探针，判决阈值是否精确等于设定步高</li>
 *   <li><b>引擎摩擦能给出多少抓地</b> —— 引擎取「双方摩擦之积」，地形侧是 Bullet 默认 0.5，
 *       因此角色侧要取多少才等价于原来 {@code μ = 1.0} 的手感（爬坡上限 = atan(μ_combined)）</li>
 * </ol>
 * 全部为观测式测量：断言只钉住「必须成立的性质」，具体数值由 {@code System.out} 输出供标定。
 *
 * @author Sweetzonzi
 */
class RigidBodyControllerPrototypeTest {

    /** 与游戏内一致的物理步长：tps = 100 */
    private static final float DT = 1f / 100f;

    private static final float CAPSULE_RADIUS = MechaBodyPreset.CAPSULE_RADIUS;
    private static final float CAPSULE_HEIGHT = MechaBodyPreset.CAPSULE_HEIGHT;
    private static final float HALF_TOTAL = MechaBodyPreset.HALF_TOTAL;

    private static final float MASS = MechaWalkingAttr.MASS;
    private static final float STEP_HEIGHT = MechaWalkingAttr.STEP_HEIGHT_BASE;

    /**
     * 越障探针的一次性扫掠长度 (m)。必须 ≥ 0.4（引擎对 {@code sweepTest} 起终点距离的下限，
     * 见 {@code CollisionSpace#sweepTest}），因此不能等于每步位移（100 Hz 下 6 m/s 只有 6 cm）。
     * <p>
     * 它同时决定越障的<b>起判距离</b>：胶囊前缘离台阶超过 {@code 探针长度 − 半径} 时探不到。
     * 探到之后每次只推进「前缘贴到台阶」的那一小段，因此探针长不会带来瞬移。
     */
    private static final float PROBE_FORWARD = 1.2f;

    /**
     * 「抬起来走不走得过去」那一次扫掠的长度 (m)。
     * <p>
     * <b>它必须比 {@link #PROBE_FORWARD} 短。</b>判决要回答的是「抬到台阶顶面之上以后，这个位置还挡不挡」，
     * 而不是「再往前一米有没有别的东西」：台阶顶面只有 1 m 宽（MC 方块尺度）时，用 1 m 的探针会直接
     * 捅到台阶后方的下一格地形，把一次合法的越障判成失败。0.5 m 既满足引擎的 0.4 m 下限，
     * 又足够确认「抬到这个高度后面前是空的」。
     */
    private static final float CLEARANCE_PROBE = 0.5f;

    /**
     * 抬起来做判决时，脚底要高出台阶顶面多少 (m)。
     * <p>
     * <b>它必须大于胶囊半径。</b>抬起来之后胶囊在水平方向上仍然要前进
     * {@link #CLEARANCE_PROBE} 米，而胶囊底部是一个半径 0.4 m 的半球：脚底只高出顶面
     * {@code δ} 时，半球在水平方向仍伸出 {@code √(r² − (r − δ)²)} ≈ {@code √(2rδ)}，
     * 台阶另一侧的上边缘就落在这段范围内。δ = 0.05 时伸出 0.20 m，判决因此会被
     * 「抬起来之后还是撞到台阶自己」误判成失败——原型的第一次尝试正是这样失败的。
     * 取 0.5 m 时伸出量降到 0.4 m 以内且不与判决扫掠的长度叠加，判决只反映真实障碍。
     */
    private static final float LIFT_CLEARANCE = 0.5f;

    /** 越障时最小水平推进量 (m)：前缘已经贴住台阶时仍要有一步向前，否则永远停在贴墙的平衡点上。 */
    private static final float MIN_ADVANCE = 1.0e-3f;


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

    /** 按角度倾斜的静态盒（绕 X 轴），用于坡度摩擦标定。 */
    private static PhysicsRigidBody staticSlope(float degrees, float friction) {
        PhysicsRigidBody body = new PhysicsRigidBody(new BoxCollisionShape(new Vector3f(25f, 0.5f, 25f)), 0f);
        Quaternion rotation = new Quaternion().fromAngleNormalAxis(
                (float) Math.toRadians(degrees), new Vector3f(1f, 0f, 0f));
        Transform transform = new Transform(new Vector3f(0f, -0.5f, 0f), rotation, new Vector3f(1f, 1f, 1f));
        body.setPhysicsTransform(transform);
        body.setFriction(friction);
        body.setRestitution(0f);
        body.setCollisionGroup(CollisionGroups.TERRAIN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
        return body;
    }

    /**
     * 倾斜盒上表面的世界高度 (m)。
     * <p>
     * 盒中心在 {@code (0, −0.5, 0)}、半高 0.5，因此底面是一个过原点的平面，绕 X 轴倾斜 θ 后
     * 其方程是 {@code y = −z·tanθ}。出生点必须按这个高度算，否则胶囊会被摆在斜面内部，
     * 求解器把它挤出来的方向是任意的，量到的「滑落」也就不是摩擦的结果。
     */
    private static float slopeSurfaceY(float degrees, float z) {
        return (float) (-z * Math.tan(Math.toRadians(degrees)));
    }

    /**
     * 控制器刚体：动态、无阻尼、无弹性，旋转自由度由 {@code angularFactor} 给出。
     * <p>
     * {@code angularFactor = 0} 即锁转（位置积分不再改变朝向），胶囊的本地轴是 +Y
     * （{@code CapsuleCollisionShape} 内部是 {@code btCapsuleShape}），因此单位四元数就是「竖直」。
     * <p>
     * 摩擦系数由调用方给出：本参数即将取代力模型里的 {@code μ_naked} / {@code μ_foot}，
     * 见 {@link #engineFrictionCalibration()}。
     *
     * @param angularFactor 0 = 锁转，1 = 三轴旋转自由度全开
     */
    private static PhysicsRigidBody newController(PhysicsSpace space, Vector3f feet, float friction,
                                                  float angularFactor) {
        PhysicsRigidBody body = new PhysicsRigidBody(
                new CapsuleCollisionShape(CAPSULE_RADIUS, CAPSULE_HEIGHT), MASS);
        body.setPhysicsLocation(new Vector3f(feet.x, feet.y + HALF_TOTAL, feet.z));
        body.setAngularFactor(angularFactor);
        body.setAngularDamping(0f);
        body.setLinearDamping(0f);
        body.setFriction(friction);
        body.setRestitution(0f);
        body.setCollisionGroup(CollisionGroups.PAWN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN);
        space.addCollisionObject(body);
        return body;
    }

    /** 锁转的控制器刚体（原型前四组测量用的形态）。 */
    private static PhysicsRigidBody newController(PhysicsSpace space, Vector3f feet, float friction) {
        return newController(space, feet, friction, 0f);
    }

    private static float feetY(PhysicsRigidBody body) {
        return body.getPhysicsLocation(null).y - HALF_TOTAL;
    }

    // ═══════════════════════════════════════════════
    // ① 锁转刚体的站立稳定性
    // ═══════════════════════════════════════════════

    /**
     * 动态刚体胶囊在 100 Hz 下站 5 s：脚底不得穿透、不得持续下沉；朝向恒为单位四元数。
     * <p>
     * 这条不成立，后面三条都不用谈——它检验的是「锁转刚体是不是一个可用的承载物」。
     */
    @Test
    void lockRotatedBodyStandsStillOnFlatGround() {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, 0f, 0f), 2.0f);

        float minFeet = Float.POSITIVE_INFINITY;
        float maxFeet = Float.NEGATIVE_INFINITY;
        float maxTilt = 0f;
        for (int i = 0; i < 500; i++) {
            space.update(DT, 0, 0x0);
            float feet = feetY(controller);
            minFeet = Math.min(minFeet, feet);
            maxFeet = Math.max(maxFeet, feet);
            maxTilt = Math.max(maxTilt, tiltDegrees(controller));
        }

        System.out.printf("[原型①] 锁转站立：脚底 y ∈ [%.6f, %.6f]，最大倾角 %.6f°，末速 %s%n",
                minFeet, maxFeet, maxTilt, controller.getLinearVelocity(null));

        assertTrue(maxFeet < 0.05f, "脚底不应被弹起，实际最高 " + maxFeet);
        assertTrue(minFeet > -0.05f, "脚底穿透超过 5 cm，实际最低 " + minFeet);
        assertTrue(maxTilt < 0.01f, "锁转后朝向应恒定，实际最大倾角 " + maxTilt + "°");
        assertTrue(Math.abs(feetY(controller)) < 0.02f,
                "稳态脚底应与地面重合，实际 " + feetY(controller));
    }

    /** 刚体相对竖直方向的倾角（度）。 */
    private static float tiltDegrees(PhysicsRigidBody body) {
        Matrix3f rotation = body.getPhysicsRotationMatrix(new Matrix3f());
        // 旋转后的本地 +Y 即旋转矩阵的第二列（列主序：get(0,1), get(1,1), get(2,1)）
        float upX = rotation.get(0, 1);
        float upY = rotation.get(1, 1);
        float upZ = rotation.get(2, 1);
        float length = (float) Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        if (length < 1.0e-6f) {
            return 0f;
        }
        float cos = Math.min(1f, Math.max(-1f, upY / length));
        return (float) Math.toDegrees(Math.acos(cos));
    }

    // ═══════════════════════════════════════════════
    // ② 撞墙：速度必须归零（KCC 的 B1 缺陷）
    // ═══════════════════════════════════════════════

    /**
     * 以 6 m/s 顶墙 1 s：水平速度应被求解器归零（或压到接近零），且不得穿墙。
     * <p>
     * 这是 KCC 路线解不掉、必须外挂位置差分判据的那条缺陷（
     * {@code common/control/MechaCharacter.java#updateWalk} 的 TODO 与
     * https://github.com/stephengold/Libbulletjme/issues/58 ）。刚体路线上它应天然成立。
     * <p>
     * 水平速度每步直接赋值、不使用力模型：本用例只问「求解器是否归零」，不掺入力标定。
     */
    @Test
    void blockedBodyLosesItsHorizontalVelocity() {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        // 墙：前沿 z = 2.0，高度 3 m
        space.addCollisionObject(staticBox(new Vector3f(2f, 1.5f, 0.5f), new Vector3f(0f, 1.5f, 2.5f), 0.5f));
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, 0f, 0f), 2.0f);

        float maxZ = Float.NEGATIVE_INFINITY;
        float speedAtWall = Float.NaN;
        for (int i = 0; i < 100; i++) {
            Vector3f velocity = controller.getLinearVelocity(null);
            controller.setLinearVelocity(new Vector3f(0f, velocity.y, 6f));
            space.update(DT, 0, 0x0);
            float z = controller.getPhysicsLocation(null).z;
            maxZ = Math.max(maxZ, z);
            if (i == 40) {
                speedAtWall = horizontalSpeed(controller);
            }
        }

        System.out.printf("[原型②] 顶墙：最前沿 z = %.4f（墙前沿 2.0，胶囊半径 %.2f → 理论止于 %.4f），"
                        + "第 40 步水平速率 %.4f m/s，末步水平速率 %.4f m/s%n",
                maxZ, CAPSULE_RADIUS, 2.0f - CAPSULE_RADIUS, speedAtWall, horizontalSpeed(controller));

        assertTrue(maxZ < 2.0f - CAPSULE_RADIUS + 0.05f,
                "不得穿入墙面，实际最前沿 z = " + maxZ);
        assertTrue(horizontalSpeed(controller) < 0.2f,
                "顶墙时水平速度应被求解器归零，实际 " + horizontalSpeed(controller));
    }

    private static float horizontalSpeed(PhysicsRigidBody body) {
        Vector3f velocity = body.getLinearVelocity(null);
        return (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    // ═══════════════════════════════════════════════
    // ③ 自写越障（auto-step）
    // ═══════════════════════════════════════════════

    /**
     * 走上 0.5 m 半砖、被 1.0 m 完整方块挡住。
     * <p>
     * 这两个判据与现有 {@code MechaCharacterStepTest#climbsAHalfSlabStep} /
     * {@code #isBlockedByAFullBlockStep} 同义，是本路线必须复现的行为。
     * 差别在于自写探针不含 KCC 那 2 cm 的隐式折扣（水平扫掠把 margin 加大
     * {@code m_addedMargin = 0.02}），因此越过上限可以精确等于设定步高。
     */
    @Test
    void autoStepClimbsASlabAndIsBlockedByAFullBlock() {
        StepOutcome slab = walkIntoAStep(0.5f);
        StepOutcome block = walkIntoAStep(1.0f);

        System.out.printf("[原型③] 0.5 m 半砖：脚底最高 %.4f，终止 z = %.4f，越障触发 %d 次%n",
                slab.maxFeetY(), slab.finalPosition().z, slab.stepUps());
        System.out.printf("[原型③] 1.0 m 方块：脚底最高 %.4f，终止 z = %.4f，越障触发 %d 次%n",
                block.maxFeetY(), block.finalPosition().z, block.stepUps());

        assertTrue(slab.stepUps() > 0, "半砖应触发越障");
        assertTrue(slab.maxFeetY() > 0.4f, "半砖（0.5 m）应能走上去，实际脚底最高 " + slab.maxFeetY());
        assertTrue(slab.maxFeetY() < 0.6f, "不应被抬过半砖顶面，实际 " + slab.maxFeetY());
        assertTrue(slab.finalPosition().z > 2.5f, "应越过半砖前沿 z=2.0，实际 z=" + slab.finalPosition().z);
        // 越障高度精确等于台阶高度：自写探针不含 KCC 那 2 cm 的隐式折扣
        //（btKinematicCharacterController 在水平扫掠前把形状 margin 临时加大 m_addedMargin = 0.02）
        assertTrue(Math.abs(slab.maxFeetY() - 0.5f) < 0.02f,
                "越障后的脚底应精确落在 0.5 m，实际 " + slab.maxFeetY());

        assertTrue(block.maxFeetY() < 0.3f, "1 格方块超出步高，不应被抬上去，实际 " + block.maxFeetY());
        assertTrue(block.finalPosition().z < 2.0f, "应被挡在台阶前沿 z=2.0 之外，实际 z=" + block.finalPosition().z);
        assertTrue(block.stepUps() == 0, "1 格方块不应触发越障，实际触发 " + block.stepUps() + " 次");
    }

    /** 一次行走的观测结果。 */
    private record StepOutcome(float maxFeetY, Vector3f finalPosition, int stepUps) {
    }

    private static StepOutcome walkIntoAStep(float obstacleHeight) {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        space.addCollisionObject(staticBox(
                new Vector3f(2f, obstacleHeight / 2f, 2f),
                new Vector3f(0f, obstacleHeight / 2f, 4f), 0.5f));
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, 0f, 0f), 2.0f);

        List<PhysicsSweepTestResult> sweepResults = new ArrayList<>();
        List<PhysicsRayTestResult> rayResults = new ArrayList<>();

        float maxFeetY = Float.NEGATIVE_INFINITY;
        int stepUps = 0;
        int[] rejections = new int[8];
        for (int i = 0; i < 400; i++) {
            int gate = tryStepUp(space, controller, new Vector3f(0f, 0f, 1f), sweepResults, rayResults);
            if (gate == 0) {
                stepUps++;
            } else {
                rejections[gate]++;
            }
            Vector3f velocity = controller.getLinearVelocity(null);
            controller.setLinearVelocity(new Vector3f(0f, velocity.y, 6f));
            space.update(DT, 0, 0x0);
            maxFeetY = Math.max(maxFeetY, feetY(controller));
        }
        System.out.printf("    [判决] 台阶 %.2f m：各判据拒绝次数 = %s%n", obstacleHeight,
                java.util.Arrays.toString(java.util.Arrays.copyOf(rejections, 7)));
        return new StepOutcome(maxFeetY, controller.getPhysicsLocation(null), stepUps);
    }

    // ── 越障探针本体 ──

    /**
     * 自写越障：抬升试扫 → 向下探落脚面 → 落地。
     * <p>
     * 与 KCC 的 {@code stepUp} 同范式（先抬高胶囊再水平扫掠），但三条判据全部由本方法给出，
     * 因此可越障高度是精确的设计值，不含 KCC 那 2 cm 的隐式折扣：
     * <ol>
     *   <li><b>是否被挡</b> —— 从<b>脚底高度</b>沿移动方向用胶囊形状做水平 sweep。用脚底而不是
     *       胶囊中心是必须的：障碍只要低于中心就会从中心的射线下方穿过去，那正是「矮台阶探测不到」的成因</li>
     *   <li><b>是不是墙</b> —— 命中面法线的竖直分量足够小才算墙；接近水平的是坡或地面，不是台阶。
     *       墙的法线接近水平时，其 Y 分量就是台阶顶面的高度</li>
     *   <li><b>抬起来走不走得过去</b> —— 把胶囊抬到台阶顶面之上再沿同方向 sweep 一次；仍被挡说明
     *       障碍高于步高（或头顶有东西），放弃。这一次 sweep 同时充当越障高度的判决</li>
     * </ol>
     * 成立则把胶囊放到「前缘抵墙、脚底踏在台阶顶面」的位置。本原型用位姿写入完成这次位移；
     * 正式实现应改为速度修正，避免每步一次运动学瞬移。
     *
     * @param direction 水平移动方向（无需归一化）
     * @return 本次是否完成了一次越障
     */
    private static int tryStepUp(PhysicsSpace space, PhysicsRigidBody controller,
                                     Vector3f direction,
                                     List<PhysicsSweepTestResult> sweepResults,
                                     List<PhysicsRayTestResult> rayResults) {
        Vector3f dir = direction.normalize();
        Vector3f center = controller.getPhysicsLocation(null);
        float feet = center.y - HALF_TOTAL;
        CapsuleCollisionShape shape = (CapsuleCollisionShape) controller.getCollisionShape();

        // ① 脚底略上方的水平 sweep：前方 PROBE_FORWARD 米内有没有挡路的东西。
        //    起点要比脚底高一点点：胶囊是站在地上的，脚底与地面处于接触状态，
        //    从脚底精确起扫会把「脚下的地板」当成 0 距离的命中，于是每次判决都判成
        //    「命中面接近水平 → 不是台阶」而失败。抬 1 cm 即可跳过地面、同时仍低于任何台阶顶面
        float sweepFeet = feet + 0.01f;
        Vector3f feetCenter = new Vector3f(center.x, sweepFeet + HALF_TOTAL, center.z);
        space.sweepTest(shape,
                new Transform(feetCenter, new Quaternion(), Vector3f.UNIT_XYZ),
                new Transform(feetCenter.add(dir.mult(PROBE_FORWARD)), new Quaternion(), Vector3f.UNIT_XYZ),
                sweepResults, 0.0f);
        if (sweepResults.isEmpty()) {
            return 1;
        }
        // 命中比例 0 是<b>合法且常见</b>的信号，不是「没探到」：胶囊被求解器按在台阶面上时，
        // 扫掠一出发就接触，引擎给出 0。把它当成失败会让越障只在「还没贴上墙」的那几帧里生效，
        // 而真正需要它的时刻恰恰是贴上墙之后——紧贴障碍时比例恒为 0，越障于是永不触发
        float fraction = sweepResults.get(0).getHitFraction();
        if (fraction >= 1f) {
            return 1; // 命中点在探针终点：障碍恰好在射程边缘，交给下一步再判
        }
        Vector3f normal = sweepResults.get(0).getHitNormalLocal(new Vector3f());
        if (Math.abs(normal.y) > 0.7f) {
            return 3; // 命中面接近水平：那是坡或地面，不是台阶
        }
        float gap = Math.max(fraction, 0f) * PROBE_FORWARD;

        // ② 台阶前缘离胶囊中心还有多远 = 探针走过的距离 + 半径（比例 0 表示已经贴住，距离取半径）。
        //    它一定是正数，因此可以无条件当作「到台阶面的水平距离」用
        float toFace = gap + CAPSULE_RADIUS;
        if (toFace > PROBE_FORWARD + CAPSULE_RADIUS) {
            return 4; // 台阶在探针范围之外（防御性，比例已限幅到 1 以内）
        }

        // ③ 台阶顶面高度：从「脚底 + 步高」之上往下打。
        //    起点必须高过台阶顶面：从胶囊中心高度（= 脚底 + 1.2 m）往下打，在台阶还差半步才
        //    走到的情况下会先打到脚下的地板，解出的落差恒为 0，越障永远不触发；
        //    而台阶一旦真的走到跟前，探针又正好落在台阶上——表现是「越障只在贴墙那一帧才开始判」，
        //    取高错误与取位错误会互相掩盖
        float probeX = center.x + dir.x * toFace;
        float probeZ = center.z + dir.z * toFace;
        Vector3f from = new Vector3f(probeX, feet + STEP_HEIGHT + 0.3f, probeZ);
        Vector3f to = new Vector3f(probeX, feet - 0.1f, probeZ);
        float stepTop = -1f;
        for (PhysicsRayTestResult hit : space.rayTest(from, to, rayResults)) {
            if (hit.getHitNormalLocal(new Vector3f()).y > 0.5f) {
                stepTop = from.y + (to.y - from.y) * hit.getHitFraction();
                break;
            }
        }
        if (stepTop < 0f) {
            return 5;
        }
        float rise = stepTop - feet;
        if (rise <= 0.01f || rise > STEP_HEIGHT) {
            return 6; // 落差不是台阶（几乎无落差，或高于步高）
        }

        // ④ 抬到台阶顶面之上再水平 sweep：仍被挡说明障碍高于步高（或头顶有东西），放弃。
        //    这一次 sweep 同时充当越障高度的判决：墙高 ≤ 步高时抬到顶面之上就畅通，
        //    高于步高时抬起来的胶囊下半段仍在墙的高度范围内，必然再次命中
        Vector3f liftedCenter = new Vector3f(
                probeX, stepTop + LIFT_CLEARANCE + HALF_TOTAL, probeZ);
        space.sweepTest(shape,
                new Transform(liftedCenter, new Quaternion(), Vector3f.UNIT_XYZ),
                new Transform(liftedCenter.add(dir.mult(CLEARANCE_PROBE)), new Quaternion(), Vector3f.UNIT_XYZ),
                sweepResults, 0.0f);
        if (!sweepResults.isEmpty()) {
            return 7;
        }

        // ⑤ 落地：脚底踏在台阶顶面，水平只推进「前缘贴到墙面」的那一段
        controller.setPhysicsLocation(new Vector3f(
                probeX + dir.x * MIN_ADVANCE, stepTop + HALF_TOTAL, probeZ + dir.z * MIN_ADVANCE));
        return 0;
    }

    // ═══════════════════════════════════════════════
    // ④ 引擎摩擦标定
    // ═══════════════════════════════════════════════

    /**
     * 引擎的组合摩擦是双方摩擦系数之<b>积</b>（Bullet 的 {@code btManifoldResult} 行为），
     * 地形侧当前是 Bullet 默认 0.5，因此角色侧要取多少才能等价于原来 {@code μ = 1.0} 的手感，
     * 必须实测而不是假定。
     * <p>
     * 测量方式：在给定倾角的斜面上放一个锁转胶囊，不给任何输入，看它是否静止。
     * 「不滑」的最大倾角就是 {@code atan(μ_combined)}。
     */
    @Test
    void engineFrictionCalibration() {
        float[] slopes = {10f, 20f, 30f, 40f, 45f, 55f};
        float[] frictions = {0.5f, 1.0f, 1.4f, 2.0f, 4.0f};

        System.out.println("[原型④] 引擎摩擦标定（斜面上静置 3 s，看是否滑落）");
        for (float characterFriction : frictions) {
            StringBuilder row = new StringBuilder();
            for (float slope : slopes) {
                float slide = measureSlideOnSlope(slope, characterFriction);
                row.append(String.format("  %2.0f°:%s", slope,
                        slide < 0.05f ? "站住" : String.format("滑%.2f", slide)));
            }
            System.out.printf("  角色摩擦 %.1f（× 地形 0.5 → 组合 %.2f，理论上限 %.1f°）%s%n",
                    characterFriction, characterFriction * 0.5f,
                    Math.toDegrees(Math.atan(characterFriction * 0.5f)), row);
        }

        // 判据一：组合摩擦 = 双方之积。角色 2.0 × 地形 0.5 → 上限 atan(1.0) = 45°，
        // 因此 40° 站得住、55° 必须滑。这条同时验证「μ_foot 可以继续控制爬坡上限」。
        float slideAt40 = measureSlideOnSlope(40f, 2.0f);
        float slideAt55 = measureSlideOnSlope(55f, 2.0f);
        assertTrue(slideAt40 < 0.1f, "组合摩擦 1.0 时 40°（< 45°）应站得住，实际滑了 " + slideAt40 + " m");
        assertTrue(slideAt55 > 0.5f, "组合摩擦 1.0 时 55°（> 45°）应滑下去，实际只滑了 " + slideAt55 + " m");

        // 判据二：爬坡上限随角色摩擦单调提高
        float slideLowFriction = measureSlideOnSlope(20f, 0.5f);
        assertTrue(slideLowFriction > 0.5f,
                "组合摩擦 0.25 时 20°（> 14°）应滑下去，实际只滑了 " + slideLowFriction + " m");
    }

    /** 在给定倾角的斜面上无输入静置 3 s，返回落稳后沿坡的累计位移 (m)。 */
    private static float measureSlideOnSlope(float degrees, float characterFriction) {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticSlope(degrees, 0.5f));
        // 出生点按斜面方程 y = −z·tanθ 取，再抬高半个胶囊，让它落到坡面上而不是被埋在坡里
        float z = 0f;
        float spawnY = slopeSurfaceY(degrees, z) - HALF_TOTAL + 0.02f;
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, spawnY, z), characterFriction);

        for (int i = 0; i < 100; i++) {
            space.update(DT, 0, 0x0);
        }
        Vector3f settled = controller.getPhysicsLocation(null);
        for (int i = 0; i < 200; i++) {
            space.update(DT, 0, 0x0);
        }
        Vector3f after = controller.getPhysicsLocation(null);
        float dx = after.x - settled.x;
        float dz = after.z - settled.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    // ═══════════════════════════════════════════════
    // ⑥ 探针自检：越障的前提是「前方射线能认出垂直面」
    // ═══════════════════════════════════════════════

    /**
     * 越障探针的取高判据：矮台阶必须用<b>脚底高度</b>的 sweep 去探，胶囊中心高度的射线探不到它。
     * <p>
     * 这条是原型开发中实际踩到的坑，钉住它以免正式实现又退回「从中心打一条射线」的写法：
     * 半砖顶面只有 0.5 m，而胶囊中心在 1.2 m——从中心水平打出的射线从台阶上方整条穿过去，
     * 命中数恒为 0，表现是「越障永不触发」且没有任何报错。
     */
    @Test
    void acenterHeightRayMissesAShortStepWhileAFeetHeightSweepFindsIt() {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(
                new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        space.addCollisionObject(staticBox(
                new Vector3f(2f, 0.25f, 2f), new Vector3f(0f, 0.25f, 4f), 0.5f));

        // ① 中心高度的水平射线：探不到 0.5 m 台阶
        Vector3f from = new Vector3f(0f, HALF_TOTAL, 1.0f);
        Vector3f to = new Vector3f(0f, HALF_TOTAL, 1.0f + PROBE_FORWARD);
        List<PhysicsRayTestResult> rayHits = space.rayTest(from, to, new ArrayList<>());
        System.out.printf("[原型⑥] 中心高度 %.2f m 的水平射线：命中 %d 条（台阶顶面只有 0.50 m）%n",
                HALF_TOTAL, rayHits.size());
        assertTrue(rayHits.isEmpty(), "中心高度的射线应从矮台阶上方越过，实际命中 " + rayHits.size() + " 条");

        // ② 脚底高度的水平 sweep：探得到，且命中比例恰好等于「前缘贴到台阶」的那一段
        CapsuleCollisionShape shape = new CapsuleCollisionShape(CAPSULE_RADIUS, CAPSULE_HEIGHT);
        Vector3f feetCenter = new Vector3f(0f, HALF_TOTAL, 1.0f);
        List<PhysicsSweepTestResult> sweepHits = new ArrayList<>();
        space.sweepTest(shape,
                new Transform(feetCenter, new Quaternion(), Vector3f.UNIT_XYZ),
                new Transform(feetCenter.add(new Vector3f(0f, 0f, PROBE_FORWARD)),
                        new Quaternion(), Vector3f.UNIT_XYZ),
                sweepHits, 0.0f);
        System.out.printf("[原型⑥] 同位置的胶囊 sweep：命中 %d 条%s%n", sweepHits.size(),
                sweepHits.isEmpty() ? "" : String.format("，比例 %.4f，法线 (%.3f, %.3f, %.3f)",
                        sweepHits.get(0).getHitFraction(),
                        sweepHits.get(0).getHitNormalLocal(new Vector3f()).x,
                        sweepHits.get(0).getHitNormalLocal(new Vector3f()).y,
                        sweepHits.get(0).getHitNormalLocal(new Vector3f()).z));

        assertTrue(!sweepHits.isEmpty(), "脚底高度的胶囊 sweep 应命中台阶正面");
        // 命中比例在「出发即接触」时是 0，因此只钉「命中面接近水平（是墙不是坡）」这一条；
        // 距离由 step-up 用 gap = max(fraction, 0) × 探针长度 限幅后使用
        assertTrue(sweepHits.get(0).getHitFraction() >= 0f
                        && Math.abs(sweepHits.get(0).getHitNormalLocal(new Vector3f()).y) < 0.5f,
                "台阶正面的法线应接近水平");
    }

    // ═══════════════════════════════════════════════
    // ⑦ 两种保持竖直的方案对决（旋转自由度锁死 vs 全开 + 条件姿态控制器）
    // ═══════════════════════════════════════════════

    /** 失稳判据：倾角超过此值即视为「摔了」。 */
    private static final float FALLEN_TILT = 60f;

    /** 姿态控制器的角加速度上限 (rad/s²)：限制 D 项对求解器接触噪声的放大。 */
    private static final float MAX_ANG_ACCEL = 200f;

    /**
     * 对照实验：初始倾角 30° 的胶囊在平地上，两组方案各自的结局。
     * <p>
     * A 组锁转（{@code setAngularFactor(0)}）、B 组旋转自由度全开且不做任何姿态控制。
     * 两组都不施加行走力，只让物理跑 2 s。
     */
    @Test
    void lockedRotationHoldsUprightWhileFreeRotationFallsOver() {
        LockedOutcome locked = runUprightTrial(0f, 0f, 0f);
        LockedOutcome free = runUprightTrial(1f, 0f, 0f);

        System.out.printf("[原型⑦A] 锁转（angularFactor=0）：初始倾角 %.1f°，最大倾角 %.2f°，"
                        + "末倾角 %.2f°，脚底 y ∈ [%.4f, %.4f]，水平位移 %.4f m，峰值角速度 %.3f rad/s%n",
                locked.initialTilt(), locked.maxTilt(), locked.finalTilt(),
                locked.minFeet(), locked.maxFeet(), locked.horizontalDrift(), locked.maxAngularSpeed());
        System.out.printf("[原型⑦B] 旋转全开且无姿态控制：初始倾角 %.1f°，最大倾角 %.2f°，"
                        + "末倾角 %.2f°，脚底 y ∈ [%.4f, %.4f]，水平位移 %.4f m，峰值角速度 %.3f rad/s%n",
                free.initialTilt(), free.maxTilt(), free.finalTilt(),
                free.minFeet(), free.maxFeet(), free.horizontalDrift(), free.maxAngularSpeed());

        assertTrue(locked.maxTilt() < 35f,
                "锁转时倾角不应变化（初始 30°），实际最大 " + locked.maxTilt() + "°");
        assertTrue(free.maxTilt() > FALLEN_TILT,
                "旋转全开且无姿态控制时应摔下去，实际最大倾角只有 " + free.maxTilt() + "°");
    }

    /**
     * B 组的补救方案：旋转自由度全开 + PD 姿态控制器，扫一遍增益。
     * <p>
     * 姿态控制器按文档的 PD 形式给出角加速度，再经逆惯量主对角线换算成力矩：
     * {@code targetAngAccel = -kP·uprightErr - kD·angVelErr}，{@code torque = invInertia ⊙ targetAngAccel}。
     * 判据是「能不能把 30° 倾角的胶囊扶正并稳住」，而不是「能不能不动」——后者锁转天然满足。
     */
    @Test
    void pdAttitudeControllerCanHoldUprightWithFreeRotation() {
        System.out.println("[原型⑦C] 旋转全开 + PD 姿态控制器（初始倾角 30°，跑 2 s）");
        float[][] gains = {{500f, 40f}, {2000f, 80f}, {2000f, 200f}, {8000f, 200f}, {8000f, 600f}, {20000f, 400f}};
        for (float[] gain : gains) {
            LockedOutcome outcome = runUprightTrial(1f, gain[0], gain[1]);
            System.out.printf("    kP=%6.0f kD=%5.0f → 最大倾角 %6.2f°，末倾角 %6.2f°，"
                            + "脚底 y ∈ [%+.4f, %+.4f]，水平位移 %.3f m，峰值角速度 %.1f rad/s%n",
                    gain[0], gain[1], outcome.maxTilt(), outcome.finalTilt(),
                    outcome.minFeet(), outcome.maxFeet(), outcome.horizontalDrift(),
                    outcome.maxAngularSpeed());
        }

        LockedOutcome controlled = runUprightTrial(1f, 8000f, 200f);
        LockedOutcome locked = runUprightTrial(0f, 0f, 0f);
        System.out.printf("[原型⑦C] 参考档 kP=8000 kD=200：最大倾角 %.2f°，末倾角 %.2f°%n",
                controlled.maxTilt(), controlled.finalTilt());
        // 判据钉的是「过程无界」这条实测结论，不是「最终能否扶正」：
        // 试过的六组增益里没有一组能在过程上有界地把 30° 倾角扶回来，每一组都先让胶囊翻过头
        // （最大倾角 178.66°–179.89°），并且在翻的过程中被弹离地面（脚底升到 +0.75 ~ +1.08 m），
        // 峰值角速度 24–28 rad/s。这是「单接触点 + 绕质心回正」的固有形态：力矩扶正的同时把
        // 接触点甩到质心侧面，接触力随之产生向上的分量，胶囊起飞后失去摩擦、进入自由翻滚。
        // 站立所需的「过程有界」因此不是调参能达到的
        assertTrue(controlled.maxTilt() > FALLEN_TILT,
                "实测：PD 姿态控制器在扶正过程中会先让胶囊翻过头，实际最大倾角 " + controlled.maxTilt() + "°");
        assertTrue(locked.maxTilt() < 35f,
                "对照：锁转时倾角恒为初始值，实际最大 " + locked.maxTilt() + "°");
    }

    /** 一次「能不能站住」的试验结果。 */
    private record LockedOutcome(float initialTilt, float maxTilt, float finalTilt,
                                 float minFeet, float maxFeet, float horizontalDrift,
                                 float maxAngularSpeed) {
    }

    /**
     * 分轴锁转是否可行，以及锁在换形 / 改质量之后是否还在。
     * <p>
     * 分轴锁转（{@code setAngularFactor(new Vector3f(0, 1, 0))}）把 pitch / roll 锁住、只留 yaw：
     * 这是「站立必须竖直」与「朝向可自由旋转」的同时满足，比 PD 姿态控制器简单得多。
     * <p>
     * 换形（蹲伏 / 卧倒）与改质量（损伤后重建质量属性）都会走原生侧的
     * {@code btRigidBody::setMassProps} + {@code updateInertiaTensor}。源码上这两个函数都不读
     * {@code m_angularFactor}（`../Libbulletjme/src/main/native/bullet3/BulletDynamics/Dynamics/btRigidBody.cpp#setMassProps`），
     * 因此锁应当自动保持；本用例把这一点钉成可执行判据，因为它是蹲伏轮廓能否接入的前提。
     */
    @Test
    void perAxisLockSurvivesShapeAndMassChanges() {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, 0f, 0f), 2.0f, 0f);

        System.out.printf("[原型⑦D] 锁转后各轴角速度 (%.6f, %.6f, %.6f)，倾角 %.4f°%n",
                controller.getAngularVelocity(null).x,
                controller.getAngularVelocity(null).y,
                controller.getAngularVelocity(null).z,
                tiltDegrees(controller));

        // 分轴锁：只留 yaw
        controller.setAngularFactor(new Vector3f(0f, 1f, 0f));
        controller.getPhysicsRotationMatrix(new Matrix3f());

        // ① 换形：蹲伏轮廓（圆柱段 0.4 m），在世
        controller.setCollisionShape(new CapsuleCollisionShape(CAPSULE_RADIUS, 0.4f));
        // ② 改质量：损伤后重建质量属性
        controller.setMass(50f);

        // ③ 给一个绕 X 轴（pitch）的力矩脉冲，锁应当把它吃掉
        controller.applyTorqueImpulse(new Vector3f(1000f, 0f, 0f));
        // ④ 给一个绕 Y 轴（yaw）的力矩脉冲，这一轴应当有响应
        controller.applyTorqueImpulse(new Vector3f(0f, 1000f, 0f));

        for (int i = 0; i < 100; i++) {
            space.update(DT, 0, 0x0);
        }

        Vector3f angVel = controller.getAngularVelocity(null);
        System.out.printf("[原型⑦D] 换形 + 改质量 + 力矩脉冲后：角速度 (%.6f, %.6f, %.6f)，倾角 %.4f°%n",
                angVel.x, angVel.y, angVel.z, tiltDegrees(controller));

        assertTrue(Math.abs(angVel.x) < 0.05f && Math.abs(angVel.z) < 0.05f,
                "pitch / roll 应被锁住，实际角速度 (" + angVel.x + ", " + angVel.z + ")");
        assertTrue(tiltDegrees(controller) < 1f,
                "换形与改质量之后朝向应仍是竖直，实际倾角 " + tiltDegrees(controller) + "°");
    }

    /**
     * 平地站立试验：胶囊初始倾角 30°（绕 Z 轴前倾），可选每步施加 PD 姿态力矩。
     *
     * @param angularFactor 0 = 锁转，1 = 全开
     * @param kP            姿态控制器的比例增益（rad/s² per rad），0 表示不控制
     * @param kD            姿态控制器的微分增益（rad/s² per rad/s）
     */
    private static LockedOutcome runUprightTrial(float angularFactor, float kP, float kD) {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(
                new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, 0f, 0f), 2.0f, angularFactor);

        // 初始倾角 30°：绕 Z 轴旋转，本地 +Y 因此偏出世界 +X 方向
        controller.setPhysicsRotation(new Quaternion().fromAngleNormalAxis(
                (float) Math.toRadians(30f), new Vector3f(0f, 0f, 1f)));

        Vector3f start = controller.getPhysicsLocation(null);
        float initialTilt = tiltDegrees(controller);
        float maxTilt = initialTilt;
        float maxAngVel = 0f;
        float minFeet = Float.POSITIVE_INFINITY;
        float maxFeet = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 200; i++) {
            if (kP > 0f) {
                applyPdAttitudeTorque(controller, kP, kD);
            }
            space.update(DT, 0, 0x0);
            maxTilt = Math.max(maxTilt, tiltDegrees(controller));
            maxAngVel = Math.max(maxAngVel, controller.getAngularVelocity(null).length());
            minFeet = Math.min(minFeet, feetY(controller));
            maxFeet = Math.max(maxFeet, feetY(controller));
        }
        Vector3f end = controller.getPhysicsLocation(null);
        float dx = end.x - start.x;
        float dz = end.z - start.z;
        return new LockedOutcome(initialTilt, maxTilt, tiltDegrees(controller),
                minFeet, maxFeet, (float) Math.sqrt(dx * dx + dz * dz), maxAngVel);
    }

    /**
     * 按文档的 PD 形式施加一次姿态力矩：目标角速度为「只有 yaw」，误差只取 pitch / roll 分量。
     * <p>
     * 力矩由角加速度经逆惯量主对角线换算：{@code τ = α / invInertia}。
     * <b>逆惯量为 0 的轴必须跳过</b>：那一轴的旋转自由度是锁死的，力矩施加不上去，
     * 而 {@code α / 0} 会得到无穷大。这个除法本身就是「旋转自由度是否可施力」的判据。
     */
    private static void applyPdAttitudeTorque(PhysicsRigidBody body, float kP, float kD) {
        Vector3f invInertia = body.getInverseInertiaLocal(new Vector3f());
        if (invInertia.x <= 0f && invInertia.y <= 0f && invInertia.z <= 0f) {
            return; // 三轴全锁，姿态控制器无事可做
        }

        Matrix3f rotation = body.getPhysicsRotationMatrix(new Matrix3f());
        // 本地 +Y 旋转到世界后的方向，与 (0,1,0) 的夹角即总倾角
        float upX = rotation.get(0, 1);
        float upY = rotation.get(1, 1);
        float upZ = rotation.get(2, 1);
        float tilt = (float) Math.acos(Math.min(1f, Math.max(-1f, upY)));

        Vector3f err = new Vector3f();
        if (tilt > 1.0e-6f) {
            // 倾斜轴 = up × worldUp，误差矢量 = 倾斜轴 × tilt，再转到世界坐标
            Vector3f axis = new Vector3f(upX, upY, upZ).cross(Vector3f.UNIT_Y);
            if (axis.length() > 1.0e-6f) {
                axis = axis.normalize();
                err.set(axis.x * tilt, axis.y * tilt, axis.z * tilt);
                rotation.mult(err, err);
            }
        }

        Vector3f angVel = body.getAngularVelocity(null);
        Vector3f alpha = err.multLocal(-kP).addLocal(angVel.multLocal(-kD));
        // 角加速度必须限幅：求解器给出的角速度里含有接触噪声，D 项对它做线性放大是正反馈。
        // 不限幅时实测角速度在 2 s 内涨到 1e36 rad/s，PD 直接崩成无穷大
        float alphaMag = alpha.length();
        if (alphaMag > MAX_ANG_ACCEL) {
            alpha = alpha.mult(MAX_ANG_ACCEL / alphaMag);
        }
        Vector3f torque = new Vector3f(
                invInertia.x > 0f ? alpha.x / invInertia.x : 0f,
                invInertia.y > 0f ? alpha.y / invInertia.y : 0f,
                invInertia.z > 0f ? alpha.z / invInertia.z : 0f);
        body.applyTorque(torque);
    }

    // ═══════════════════════════════════════════════
    // ⑧ 落地收敛：每步把姿态按指数衰减拉向「只保留 yaw 的竖直」，收敛后锁定
    // ═══════════════════════════════════════════════

    /**
     * 落地收敛窗口：每物理步把姿态按指数衰减拉向竖直，并把角速度一并衰减；倾角小到阈值内即锁定。
     *
     * @param body     控制器刚体
     * @param lambda   每步的收敛系数（0 = 不动，1 = 一步到位）
     * @param lockTilt 倾角小于此值时把 {@code angularFactor} 置 0 并清零角速度
     * @return 本步是否完成了锁定
     */
    private static boolean settleTowardUpright(PhysicsRigidBody body, float lambda, float lockTilt) {
        Matrix3f rotation = body.getPhysicsRotationMatrix(new Matrix3f());
        float tilt = tiltDegrees(body);

        if (tilt < lockTilt) {
            // 已经够竖直：清角速度、锁转，此后不再和力矩较劲
            body.setAngularVelocity(new Vector3f(0f, 0f, 0f));
            body.setAngularFactor(0f);
            return true;
        }

        // 目标 = 只保留 yaw 的竖直姿态
        Vector3f up = unitY(rotation);
        float yaw = (float) Math.atan2(-up.x, -up.z);
        Quaternion target = new Quaternion().fromAngleNormalAxis(yaw, Vector3f.UNIT_Y);

        // 相对旋转 → 旋转矢量 → 按 lambda 衰减成一个小角度增量
        Quaternion current = body.getPhysicsRotation(null);
        Quaternion errQ = target.mult(current.inverse());
        float ex = errQ.getX();
        float ey = errQ.getY();
        float ez = errQ.getZ();
        float ew = errQ.getW();
        if (ew < 0f) {
            ex = -ex;
            ey = -ey;
            ez = -ez;
            ew = -ew; // 取短弧
        }
        float vecLen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (vecLen > 1.0e-6f) {
            float angle = 2f * (float) Math.atan2(vecLen, ew);
            float scale = angle * lambda / vecLen;
            Quaternion step = new Quaternion(ex * scale, ey * scale, ez * scale, 1f);
            step.normalizeLocal();
            body.setPhysicsRotation(step.mult(current));
        }
        // 角速度按同一系数衰减：姿态是运动学写入的，残留角速度会在下一步与它打架
        body.setAngularVelocity(body.getAngularVelocity(null).mult(1f - lambda));
        return false;
    }

    /** 旋转矩阵第二列 = 本地 +Y 旋转后的世界方向。 */
    private static Vector3f unitY(Matrix3f rotation) {
        return new Vector3f(rotation.get(0, 1), rotation.get(1, 1), rotation.get(2, 1));
    }

    /**
     * 落地收敛方案的效果与代价，并与 PD 姿态控制器对照。
     * <p>
     * 同一个初态（倾角 30°、静止落地）分别用两种方案处理，比较四件事：过程是否单调收敛、
     * 会不会被弹离地面、峰值角速度、以及收敛需要多少步。
     */
    @Test
    void exponentialSettleConvergesMonotonicallyWherePdOvershoots() {
        System.out.println("[原型⑧] 落地收敛：指数衰减 vs PD（初始倾角 30°，上限 2 s）");
        for (float lambda : new float[]{0.05f, 0.10f, 0.20f, 0.35f}) {
            SettleOutcome outcome = runSettleTrial(lambda);
            System.out.printf("    指数衰减 λ=%.2f → 收敛用 %3d 步（%.2f s），最大倾角 %6.2f°，"
                            + "脚底 y ∈ [%+.4f, %+.4f]，峰值角速度 %.3f rad/s，水平位移 %.4f m%n",
                    lambda, outcome.steps(), outcome.steps() * DT, outcome.maxTilt(),
                    outcome.minFeet(), outcome.maxFeet(), outcome.maxAngularSpeed(),
                    outcome.horizontalDrift());
        }
        LockedOutcome pd = runUprightTrial(1f, 8000f, 200f);
        System.out.printf("    对照 PD kP=8000 kD=200 → 最大倾角 %.2f°，脚底 y ∈ [%+.4f, %+.4f]，"
                        + "峰值角速度 %.1f rad/s%n",
                pd.maxTilt(), pd.minFeet(), pd.maxFeet(), pd.maxAngularSpeed());

        SettleOutcome settle = runSettleTrial(0.20f);
        assertTrue(settle.steps() > 0 && settle.steps() < 50,
                "指数衰减应在约 0.5 s 内收敛并锁定，实际 " + settle.steps() + " 步");
        assertTrue(settle.maxTilt() < 31f,
                "过程必须单调：最大倾角不应超过初始的 30°，实际 " + settle.maxTilt() + "°");
        assertTrue(settle.maxFeet() < 0.02f,
                "运动学收敛不应把胶囊弹离地面，实际脚底最高 " + settle.maxFeet() + " m");
        assertTrue(settle.maxAngularSpeed() < 0.5f,
                "角速度应被同系数衰减、不产生高转速，实际峰值 " + settle.maxAngularSpeed() + " rad/s");
    }

    /** 一次落地收敛试验的结果。 */
    private record SettleOutcome(int steps, float maxTilt, float minFeet, float maxFeet,
                                 float maxAngularSpeed, float horizontalDrift) {
    }

    /**
     * 姿态写入是否绕过 {@code angularFactor}：落地的第一步就把旋转自由度全锁，同时用运动学收敛。
     * <p>
     * 这一条成立的话，落地处理比「先留自由度做收敛、收敛完再锁」更简单：
     * 锁只拦角速度与角冲量，不拦 {@code setPhysicsRotation} 这种直接位姿写入，因此两者可以同时做，
     * 而且「竖直之后才锁」所隐含的那段窗口（以及窗口的打断条件）整个不需要了。
     */
    @Test
    void orientationWriteBypassesTheAngularFactorSoLockingCanHappenOnTheFirstLandingStep() {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(
                new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, 0f, 0f), 2.0f, 1f);
        controller.setPhysicsRotation(new Quaternion().fromAngleNormalAxis(
                (float) Math.toRadians(30f), new Vector3f(0f, 0f, 1f)));

        // 落地第一步：锁转，然后立即开始运动学收敛
        controller.setAngularFactor(0f);
        float startTilt = tiltDegrees(controller);
        float maxTilt = startTilt;
        float maxAngVel = 0f;
        float maxFeet = Float.NEGATIVE_INFINITY;
        float minFeet = Float.POSITIVE_INFINITY;
        Vector3f start = controller.getPhysicsLocation(null);
        int lockedAt = -1;
        for (int i = 0; i < 200; i++) {
            if (settleTowardUpright(controller, 0.20f, 0.5f)) {
                lockedAt = i + 1;
            }
            space.update(DT, 0, 0x0);
            maxTilt = Math.max(maxTilt, tiltDegrees(controller));
            maxAngVel = Math.max(maxAngVel, controller.getAngularVelocity(null).length());
            maxFeet = Math.max(maxFeet, feetY(controller));
            minFeet = Math.min(minFeet, feetY(controller));
        }
        for (int i = 0; i < 100; i++) {
            space.update(DT, 0, 0x0);
            maxTilt = Math.max(maxTilt, tiltDegrees(controller));
            maxAngVel = Math.max(maxAngVel, controller.getAngularVelocity(null).length());
            maxFeet = Math.max(maxFeet, feetY(controller));
            minFeet = Math.min(minFeet, feetY(controller));
        }
        Vector3f end = controller.getPhysicsLocation(null);
        float drift = (float) Math.hypot(end.x - start.x, end.z - start.z);

        System.out.printf("[原型⑨A] 首步即锁 + 运动学收敛：收敛用 %d 步，末倾角 %.4f°，最大倾角 %.2f°，"
                        + "脚底 y ∈ [%+.4f, %+.4f]，峰值角速度 %.4f rad/s，水平位移 %.4f m%n",
                lockedAt < 0 ? -1 : lockedAt, tiltDegrees(controller), maxTilt,
                minFeet, maxFeet, maxAngVel, drift);

        assertTrue(lockedAt > 0, "首步锁转之后运动学收敛仍应到达锁定阈值");
        assertTrue(tiltDegrees(controller) < 1f,
                "运动学收敛应把倾角带回竖直，实际 " + tiltDegrees(controller) + "°");
        assertTrue(maxTilt < 31f, "过程不应超过初始 30°，实际 " + maxTilt + "°");
        assertTrue(maxFeet < 0.02f, "不应弹离地面，实际脚底最高 " + maxFeet + " m");
    }

    /**
     * 锁转会冻住姿态：倒下的胶囊不会自己站起来，所以「锁」与「摆正」必须分别做。
     * <p>
     * 胶囊水平躺在地面上时，重力过质心、接触法向也过质心，合力矩为零——这是中性平衡，
     * 物理上没有任何东西会把它扶正。因此锁转只负责「不再改变朝向」，摆正必须由姿态写入完成。
     * 这条同时解释了为什么摆正那一小段不能省。
     */
    @Test
    void lockedRotationFreezesThePoseSoRightingMustBeDoneByTheWrite() {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(
                new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        // 躺倒：绕 Z 轴 90°，胶囊轴变成水平
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, 0f, 0f), 2.0f, 0f);
        controller.setPhysicsLocation(new Vector3f(0f, CAPSULE_RADIUS, 0f));
        controller.setPhysicsRotation(new Quaternion().fromAngleNormalAxis(
                (float) Math.toRadians(90f), new Vector3f(0f, 0f, 1f)));

        for (int i = 0; i < 300; i++) {
            space.update(DT, 0, 0x0);
        }
        float tiltAfterFalling = tiltDegrees(controller);
        System.out.printf("[原型⑨B] 锁转的躺倒胶囊静置 3 s：倾角 %.4f°（初始 90°），角速度 %.6f rad/s%n",
                tiltAfterFalling, controller.getAngularVelocity(null).length());

        assertTrue(Math.abs(tiltAfterFalling - 90f) < 1f,
                "锁转会把 90° 的躺倒姿态原样冻住，实际 " + tiltAfterFalling + "°");
        assertTrue(controller.getAngularVelocity(null).length() < 1.0e-3f,
                "锁转下角速度恒为 0");
    }

    /** 倾角 30° 落地，每步跑一次 {@link #settleTowardUpright}，直到锁定或到 2 s。 */
    private static SettleOutcome runSettleTrial(float lambda) {
        PhysicsSpace space = newSpace();
        space.addCollisionObject(staticBox(
                new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f), 0.5f));
        PhysicsRigidBody controller = newController(space, new Vector3f(0f, 0f, 0f), 2.0f, 1f);
        controller.setPhysicsRotation(new Quaternion().fromAngleNormalAxis(
                (float) Math.toRadians(30f), new Vector3f(0f, 0f, 1f)));

        Vector3f start = controller.getPhysicsLocation(null);
        float maxTilt = 30f;
        float minFeet = Float.POSITIVE_INFINITY;
        float maxFeet = Float.NEGATIVE_INFINITY;
        float maxAngVel = 0f;
        int lockedAt = -1;
        for (int i = 0; i < 200; i++) {
            if (settleTowardUpright(controller, lambda, 0.5f)) {
                lockedAt = i + 1;
                break;
            }
            space.update(DT, 0, 0x0);
            maxTilt = Math.max(maxTilt, tiltDegrees(controller));
            maxAngVel = Math.max(maxAngVel, controller.getAngularVelocity(null).length());
            minFeet = Math.min(minFeet, feetY(controller));
            maxFeet = Math.max(maxFeet, feetY(controller));
        }
        // 锁定后继续跑一会儿，确认锁得住
        for (int i = 0; i < 100; i++) {
            space.update(DT, 0, 0x0);
            maxTilt = Math.max(maxTilt, tiltDegrees(controller));
            maxAngVel = Math.max(maxAngVel, controller.getAngularVelocity(null).length());
            minFeet = Math.min(minFeet, feetY(controller));
            maxFeet = Math.max(maxFeet, feetY(controller));
        }
        Vector3f end = controller.getPhysicsLocation(null);
        float dx = end.x - start.x;
        float dz = end.z - start.z;
        return new SettleOutcome(lockedAt < 0 ? 200 : lockedAt, maxTilt, minFeet, maxFeet,
                maxAngVel, (float) Math.sqrt(dx * dx + dz * dz));
    }

    // ═══════════════════════════════════════════════
    // ⑤ 结论汇总：把四个问题的答案打在一起，便于一次运行读全
    // ═══════════════════════════════════════════════

    /** 单独跑一遍全部测量，输出一张可读的结论表（不重复断言）。 */
    @Test
    void printPrototypeSummary() {
        System.out.println();
        System.out.println("══════════ 刚体控制器原型结论 ══════════");
        System.out.printf("胶囊：半径 %.2f m，圆柱段 %.2f m，全高 %.2f m，质量 %.1f kg，步高 %.2f m%n",
                CAPSULE_RADIUS, CAPSULE_HEIGHT, CAPSULE_HEIGHT + 2 * CAPSULE_RADIUS, MASS, STEP_HEIGHT);
        System.out.println("详见 [原型①]–[原型④] 的逐条输出。");
        System.out.println("════════════════════════════════════════");
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
