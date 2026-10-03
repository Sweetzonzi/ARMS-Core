package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行走控制力模型的回归测试：稳态速率、姿态倍率、地面刹车、跳跃继承与空中转向。
 * <p>
 * 全部在真实 {@link PhysicsSpace} 里按游戏内物理步频（100 Hz，见
 * `docs/ArmsCore双端权威与网络同步实现计划.md` §3.6）驱动
 * {@link MechaCharacter#prePhysicsTick}，因此断言的是**力学结果**而不是中间变量：
 * 速率由力平衡（§3.6）产生、由摩擦（§3.5、§3.9）与空中控制力（§3.7）改写。
 * 不经过 {@code MechaControl}：状态机与这里的力学无关。
 * <p>
 * 判据里的期望值都可由 {@link MechaWalkingAttr} 的常量算出来，写在各测试的注释里。
 *
 * @author Sweetzonzi
 */
class MechaCharacterWalkPhysicsTest {

    /** 与游戏内一致的物理步长：tps = 100（Spark-Core {@code PhysicsLevel.baseStep = 5}） */
    private static final float DT = 1f / 100f;

    /** 平地裸机稳态速率 (m/s)：P_base / (c₀·m·g) = v_ref = 6.0 */
    private static final float CRUISE = MechaWalkingAttr.V_REF;

    private PhysicsSpace space;
    private MechaCharacter kcc;

    /**
     * 一块静态盒子。碰撞组必须显式设置：{@code PhysicsCollisionObject} 默认组与掩码都是
     * {@link CollisionGroups#TERRAIN}，而 KCC 属于 {@link CollisionGroups#PAWN}。
     */
    private static PhysicsRigidBody staticBox(Vector3f halfExtents, Vector3f location) {
        PhysicsRigidBody body = new PhysicsRigidBody(new BoxCollisionShape(halfExtents), 0f);
        body.setPhysicsLocation(location);
        body.setCollisionGroup(CollisionGroups.TERRAIN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
        return body;
    }

    @BeforeEach
    void setUp() {
        space = new PhysicsSpace(new Vector3f(-500f, -500f, -500f), new Vector3f(500f, 500f, 500f));
        // 足够大的地面：10 s 的测试里角色会跑出几十米
        space.addCollisionObject(staticBox(new Vector3f(400f, 0.5f, 400f), new Vector3f(0f, -0.5f, 0f)));

        kcc = new MechaCharacter(MechaBodyPreset.newCapsuleShape(), space);
        kcc.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
        space.addCollisionObject(kcc);
    }

    /** 跑 {@code steps} 个物理步。 */
    private void step(int steps) {
        for (int i = 0; i < steps; i++) {
            kcc.prePhysicsTick(DT);
            space.update(DT, 0, 0x0);
        }
    }

    /** 当前水平速率 (m/s)。 */
    private float hSpeed() {
        return kcc.getHSpeed(DT);
    }

    /** 直接把水平速度设成 (vx, vz) 的 m/s 值（KCC 的 XZ 要传位移，这里代换算好）。 */
    private void setHorizontalVelocity(float vx, float vz) {
        kcc.setLinearVelocity(new Vector3f(vx * DT, kcc.getLinearVelocity(null).y, vz * DT));
    }

    /** 当前水平速度的 X / Z 分量 (m/s)。 */
    private float vx() {
        return kcc.getLinearVelocity(null).x / DT;
    }

    private float vz() {
        return kcc.getLinearVelocity(null).z / DT;
    }

    // ═══════════════════════════════════════════════
    // KCC 速度读回的语义（模型依赖它做反馈）
    // ═══════════════════════════════════════════════

    /**
     * {@code setLinearVelocity} 写入的水平分量在下一次物理步仍读得回来，且只被地面摩擦改写。
     * <p>
     * 控制力模型每步都要读回上一步的速度（§3.8 的手动积分把 KCC 当速度存储），
     * 因此这条语义必须先钉住：写入 = 每物理步位移 (m/tick)，读出 ÷dt = m/s。
     * 无输入时唯一的水平制动是 μ·g = 9.81 m/s²，10 步（0.1 s）应掉约 0.98 m/s。
     */
    @Test
    void writtenHorizontalVelocityIsReadBackAndDecayedByGroundFriction() {
        setHorizontalVelocity(6f, 0f);
        assertEquals(6f, hSpeed(), 0.01f, "写入后应立即读回同一速率");

        step(10);
        assertEquals(6f - MechaWalkingAttr.MU_NAKED * MechaWalkingAttr.GRAVITY * 0.1f, hSpeed(), 0.05f,
                "无输入时地面摩擦刹车按 μ·g 减速");
    }

    // ═══════════════════════════════════════════════
    // 稳态速率：力平衡的产物，不是被钳制出来的
    // ═══════════════════════════════════════════════

    /**
     * 以设计顶速起步并持续施力，速率应停在那里不动。
     * <p>
     * 期望值：{@code min(P/v, F_max)·exp(…) = c₀·m·g} ⇒ {@code v = P_base/(c₀·m·g) = v_ref = 6 m/s}。
     * 若把顶速实现成「速度钳制」，这条会通过而 {@link #walkFromRestApproachesCruiseSpeed()} 不会；
     * 两者一起才能区分「限速」与「力平衡」。
     */
    @Test
    void cruiseSpeedIsTheForceBalanceEquilibrium() {
        kcc.setMoveIntent(1f, 0f);
        setHorizontalVelocity(0f, CRUISE);

        step(200);

        assertEquals(CRUISE, hSpeed(), 0.15f,
                "6 m/s 是力平衡点，持续施力不应偏离");
    }

    /**
     * 从静止起步，速率应向 v_ref 收敛（而不是被某个上限截住）。
     * <p>
     * 功率限力 P/v 在接近 v_ref 时余力趋零，因此收敛是渐近的：4 s 内应已进入 4.5 m/s 以上，
     * 且任何时刻都不越过设计顶速。
     */
    @Test
    void walkFromRestApproachesCruiseSpeed() {
        kcc.setMoveIntent(1f, 0f);

        step(400); // 4 s
        float speed = hSpeed();

        assertTrue(speed > 4.5f, "4 s 后应已接近设计顶速 6 m/s，实际 " + speed + " m/s");
        assertTrue(speed <= CRUISE + 0.05f, "任何时刻都不应越过力平衡给出的顶速，实际 " + speed + " m/s");
    }

    // ═══════════════════════════════════════════════
    // 逻辑层速度倍率：缩放控制力 ⇒ 缩放顶速
    // ═══════════════════════════════════════════════

    /**
     * 蹲伏倍率 0.3 把稳态速率压到 1.8 m/s。
     * <p>
     * 期望值：{@code k·P/v = c₀·m·g} ⇒ {@code v = k·v_ref = 0.3 × 6 = 1.8 m/s}。
     * 这是「倍率作用在控制力上」的判据：倍率若作用在净力上，均衡点不随倍率移动，顶速会回到 6 m/s。
     */
    @Test
    void moveSpeedModifierScalesTheEquilibriumSpeed() {
        kcc.setMoveSpeedModifier(0.3f);
        kcc.setMoveIntent(1f, 0f);

        step(600); // 6 s：倍率下的收敛同样渐近，给足时间
        float crouchSpeed = hSpeed();

        assertEquals(0.3f * CRUISE, crouchSpeed, 0.1f,
                "蹲伏稳态速率应为 0.3 × 6 = 1.8 m/s，实际 " + crouchSpeed + " m/s");
    }

    // ═══════════════════════════════════════════════
    // 无输入制动（§3.9）
    // ═══════════════════════════════════════════════

    /** 松开方向键后，地面摩擦刹车应在 1 s 内把角色停住。 */
    @Test
    void releasingInputBrakesToAStopOnTheGround() {
        kcc.setMoveIntent(1f, 0f);
        step(300);
        assertTrue(hSpeed() > 4f, "先决条件：应已跑起来，实际 " + hSpeed() + " m/s");

        kcc.setMoveIntent(0f, 0f);
        step(100); // 1 s，μ·g = 9.81 m/s² 足够停下

        assertEquals(0f, hSpeed(), 0.05f, "无输入时应被摩擦刹停");
    }

    /**
     * 从静止按住方向键，起步方向只由输入方向决定。
     * <p>
     * yaw = 0（面向南 +Z）时前进落在 +Z，且不应出现横向漂移。
     */
    @Test
    void movesAlongTheInputDirectionOnly() {
        kcc.setMoveIntent(1f, 0f);
        step(200);

        assertTrue(vz() > 3f, "前进应沿 +Z 加速，实际 vz=" + vz());
        assertEquals(0f, vx(), 0.02f, "不应产生横向速度");
    }

    // ═══════════════════════════════════════════════
    // 跳跃：水平速度继承 + 空中「不可加速」
    // ═══════════════════════════════════════════════

    /** 按住跳跃键一拍再松开，触发 KCC 起跳；返回是否成功离地。 */
    private void jumpAndStep() {
        kcc.setJumpInput(true, false);
        step(2);
        kcc.setJumpInput(false, true);
        step(1);
    }

    /**
     * 起跳继承水平速度：离地后不再输入，水平速率应原样带到落地。
     * <p>
     * 空中没有地面接触也就没有摩擦（§3.9 的刹车只在地面生效），因此这里唯一会改变水平速率的
     * 是空中控制力，而它需要输入才会施力。旧行为（无输入也按 μ·g 刹车）会在 0.6 s 内把速度抹平。
     */
    @Test
    void jumpCarriesTheHorizontalVelocityThroughTheAir() {
        kcc.setMoveIntent(1f, 0f);
        step(300);
        float takeoffSpeed = hSpeed();
        assertTrue(takeoffSpeed > 4f, "先决条件：应有水平速度，实际 " + takeoffSpeed + " m/s");

        // 起跳后立刻放开方向键：空中不得有任何水平制动
        jumpAndStep();
        kcc.setMoveIntent(0f, 0f);

        float minAirSpeed = Float.MAX_VALUE;
        int airborneSteps = 0;
        for (int i = 0; i < 200 && !kcc.onGround(); i++) {
            step(1);
            minAirSpeed = Math.min(minAirSpeed, hSpeed());
            airborneSteps++;
        }

        assertTrue(airborneSteps > 20, "应确实离地一段时间，实际空中步数=" + airborneSteps);
        assertEquals(takeoffSpeed, minAirSpeed, 0.15f,
                "空中水平速率应保持起跳时的值，实际最低 " + minAirSpeed + " m/s");
    }

    /**
     * 空中转向受控制力限制：垂直输入按住 0.8 s 只能把速度矢量掰过有限角度。
     * <p>
     * 期望量级：空中控制力 {@code F_max × AIR_CONTROL = 210 N} ⇒ {@code a = 3 m/s²}，
     * 0.8 s 能改变约 2.4 m/s 的横向速度。若实现是「把速度矢量直接改写成输入方向」
     * （限速模型），横向分量在一个物理步内就会跳到与速率同量级（≈ 5 m/s）。
     */
    @Test
    void airSteeringChangesDirectionAtTheControlForceRate() {
        kcc.setMoveIntent(1f, 0f);
        step(300);
        jumpAndStep();
        assertTrue(hSpeed() > 4f, "先决条件：起跳时应有水平速度");

        // 起跳后改成纯左移（yaw=0 面向 +Z 时左移 = +X）
        kcc.setMoveIntent(0f, 1f);
        int steered = 0;
        for (int i = 0; i < 80 && !kcc.onGround(); i++) {
            step(1);
            steered++;
        }

        float lateral = vx();
        assertTrue(steered > 20, "应确实在空中，实际步数=" + steered);
        assertTrue(lateral > 1.8f,
                "空中应保留一点操作手感：0.8 s 的横向速度应接近空中控制力给出的量级，实际 " + lateral + " m/s");
        assertTrue(lateral < 3.2f,
                "空中应难以变向：横向速度不应一步跳到与速率同量级，实际 " + lateral + " m/s");
    }

    /**
     * 空中控制力不得把速率推过地面顶速（§3.7「不可加速」）。
     * <p>
     * 按住前进键起跳：若空中控制力可以自由加速，一次跳跃就能把 6 m/s 推到 8 m/s 以上，
     * 反复起跳会累积成地面永远达不到的速度。空中天花板取地面同倍率下的顶速 = v_ref。
     */
    @Test
    void airControlDoesNotPushSpeedAboveGroundCruise() {
        kcc.setMoveIntent(1f, 0f);
        step(300);
        jumpAndStep();

        float maxAirSpeed = 0f;
        for (int i = 0; i < 200 && !kcc.onGround(); i++) {
            step(1);
            maxAirSpeed = Math.max(maxAirSpeed, hSpeed());
        }

        assertTrue(maxAirSpeed <= CRUISE + 0.2f,
                "空中按住前进不应把速率推过 " + CRUISE + " m/s，实际峰值 " + maxAirSpeed + " m/s");
    }

    /**
     * 空中控制力不得被姿态倍率二次折减：AIR 姿态的倍率必须是中性 1.0。
     * <p>
     * 空中的控制力强度由 {@link MechaWalkingAttr#AIR_CONTROL} 单独给出（§3.7）。若姿态再压一次，
     * 两者相乘（例如 0.05 × 0.30 = 1.5%）会让空中彻底失去操作，而上面那条"空中应保留一点
     * 操作手感"的判据只在 {@code setMoveSpeedModifier} 默认值下成立——这条把它钉在状态机侧。
     */
    @Test
    void airPostureKeepsTheControlScaleNeutral() {
        assertEquals(1.0f, Posture.AIR.speedModifier(), 1.0e-6f,
                "空中控制力由 AIR_CONTROL 给出，姿态倍率必须中性，否则两者相乘会把空中操作压没");
    }

    // ═══════════════════════════════════════════════
    // 闪避冲量（逻辑层产出的一次性位移）
    // ═══════════════════════════════════════════════

    /**
     * 闪避冲量在整个窗口内逐步积分，总位移 ≈ 初速度 × 时长 / 2（线性衰减）。
     * <p>
     * 期望值：{@code 6 × 0.4 / 2 = 1.2 m}。冲量走的是位移叠加通道，不参与控制力积分，
     * 全程只由 {@code requestDodgeImpulse} 给的初速与窗口时长决定，与稳态速率、姿态倍率、
     * 地面 μ 都无关——闪避因此总是同一段距离。
     */
    @Test
    void dodgeImpulseMovesTheDesignDistance() {
        kcc.setMoveIntent(0f, 0f); // 原地闪避：不需要按方向键
        Vector3f from = kcc.getPhysicsLocation(null);

        kcc.requestDodgeImpulse(0f, 1f, MechaControl.DODGE_IMPULSE_SPEED,
                MechaControl.DODGE_INVULNERABLE_SECONDS, 0.4f);
        step(60); // 0.6 s：冲量窗口 0.4 s + 余量

        Vector3f to = kcc.getPhysicsLocation(null);
        float dx = to.x - from.x;
        float dz = to.z - from.z;
        float moved = (float) Math.sqrt(dx * dx + dz * dz);

        assertEquals(1.2f, moved, 0.25f, "单次闪避位移应约为 1.2 m，实际 " + moved + " m");
    }
}
