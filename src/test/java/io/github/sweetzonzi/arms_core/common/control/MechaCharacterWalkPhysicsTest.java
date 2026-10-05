package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaJumpAttr;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        // 覆写 getBoostForce() 返回 0：屏蔽助推窗口、只保留冲量相，使空中力学断言
        // （水平速度继承、空中转向、空中闪避）与助推无关
        kcc = new MechaCharacter(MechaBodyPreset.newCapsuleShape(), space) {
            @Override
            protected float getBoostForce() {
                return 0f;
            }
        };
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

    /**
     * 一次闪避在默认素体质量下给出的速度增量 {@code Δv = I_dodge / m} (m/s)。
     * <p>
     * 闪避以冲量衡量，期望值必须经质量换算，不能直接拿 {@code DODGE_IMPULSE} 当速率用。
     */
    private float dodgeDv() {
        return MechaControl.DODGE_IMPULSE / kcc.getControllerMass();
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

    /**
     * 轻拍跳跃键：按下当步即施放冲量并离地，下一物理步松开终止助推窗口。
     * <p>
     * 本测试的 KCC 把 {@link MechaCharacter#getBoostForce()} 覆写为 0，因此这次轻拍只体现冲量相。
     */
    private void tapJump() {
        kcc.setJumpInput(true, false);
        step(1);
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
        tapJump();
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
        tapJump();
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
        tapJump();

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
    // 闪避（冲量 → Δv = I/m 的速度矢量赋值，由通用力模型接管）
    // ═══════════════════════════════════════════════

    /**
     * 一次闪避 = 一次水平速度矢量赋值，并被<b>同一步</b>的地面制动力削减，此后按同一减速度继续衰减。
     * <p>
     * 从静止闪避时赋值结果就是 {@code Δv = I_dodge / m}，因此本条同时是「赋值进了速度通道而不是位移
     * 叠加通道」的判据。分两段，合起来钉住「闪避与普通动量共用同一份力学预算」：
     * <ul>
     *   <li>施加闪避后的第一步位移对应的步首速率 ≈ {@code Δv}：本步位移
     *       {@code = (Δv − μ·g·dt)·dt}，因此反解出 {@code Δv} 只需补回那一步的摩擦量。
     *       若闪避走的是位移叠加通道，同样的反解会得到 {@code μ·g·dt} 那一档，而不是 Δv；</li>
     *   <li>此后每步按 {@code μ·g} 递减，与无输入刹车的减速度一致——即闪避没有自己的衰减曲线。</li>
     * </ul>
     */
    @Test
    void dodgeImpulseStepChangesTheVelocityVector() {
        kcc.setMoveIntent(0f, 0f);

        Vector3f before = kcc.getPhysicsLocation(null);
        kcc.requestDodgeImpulse(0f, 1f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(1);
        Vector3f after = kcc.getPhysicsLocation(null);

        float brakePerStep = MechaWalkingAttr.MU_NAKED * MechaWalkingAttr.GRAVITY * DT;
        assertEquals(0f, (after.x - before.x) / DT, 1.0e-3f, "冲量方向是 +Z，X 分量应保持 0");
        assertEquals(dodgeDv(), (after.z - before.z) / DT + brakePerStep, 0.02f,
                "闪避本步的位移应等于 Δv 减去同一步的摩擦量，反解出 Δv");

        step(10); // 0.1 s
        assertEquals(dodgeDv() - brakePerStep - MechaWalkingAttr.MU_NAKED * MechaWalkingAttr.GRAVITY * 0.1f,
                hSpeed(), 0.05f, "闪避带来的速度此后应由地面摩擦按 μ·g 衰减，没有独立曲线");
    }

    /**
     * 闪避以冲量衡量：同一个 {@code I_dodge} 在越重的机体上得到越小的 Δv。
     * <p>
     * 与跳跃同构（{@code v_takeoff = I_jump / m}）：两者都在控制器内除以
     * {@link MechaCharacter#getControllerMass()}。这里用两倍素体质量的控制器跑同一次闪避，判据取
     * 「动量守恒」——同一份冲量下 {@code m × Δv} 两边相等，而速率一边是一边的一半。
     * 若 Δv 是与质量无关的常数，两边的速率会相同、动量差一倍。
     */
    @Test
    void dodgeImpulseIsDividedByTheControllerMass() {
        float doubleMass = 2f * MechaWalkingAttr.MASS;
        PhysicsSpace heavySpace = new PhysicsSpace(
                new Vector3f(-500f, -500f, -500f), new Vector3f(500f, 500f, 500f));
        heavySpace.addCollisionObject(staticBox(new Vector3f(400f, 0.5f, 400f), new Vector3f(0f, -0.5f, 0f)));
        MechaCharacter heavy = new MechaCharacter(MechaBodyPreset.newCapsuleShape(), heavySpace) {
            @Override
            protected float getControllerMass() {
                return doubleMass;
            }
        };
        heavy.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
        heavySpace.addCollisionObject(heavy);

        float brakePerStep = MechaWalkingAttr.MU_NAKED * MechaWalkingAttr.GRAVITY * DT;

        // 对照组：素体质量
        kcc.setMoveIntent(0f, 0f);
        kcc.requestDodgeImpulse(0f, 1f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(1);
        float lightDv = vz() + brakePerStep; // 反解回赋值速率

        // 两倍质量：同一份冲量
        heavy.setMoveIntent(0f, 0f);
        heavy.requestDodgeImpulse(0f, 1f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        heavy.prePhysicsTick(DT);
        heavySpace.update(DT, 0, 0x0);
        float heavyDv = heavy.getLinearVelocity(null).z / DT + brakePerStep;

        assertEquals(MechaWalkingAttr.MASS * lightDv, doubleMass * heavyDv, 12f,
                "同一份冲量下两边动量相等：m × Δv = I_dodge");
        assertEquals(lightDv / 2f, heavyDv, 0.1f, "两倍质量得到一半的 Δv");
    }

    /**
     * 闪避沿原方向时保留全部同向动量并叠加 Δv。
     * <p>
     * 期望：先以 {@code v} 平移，再沿同一方向闪避，赋值结果是 {@code max(v·u, 0) + Δv = v + Δv}。
     * 若闪避走的是位移叠加通道，速率会保持在 {@code v} 不动；若赋值是无条件归零（连同向分量也丢掉），
     * 速率会回到 {@code Δv}。
     */
    @Test
    void dodgeKeepsTheSameDirectionComponentAndAddsTheImpulse() {
        kcc.setMoveIntent(0f, 0f);
        setHorizontalVelocity(0f, 4f);

        kcc.requestDodgeImpulse(0f, 1f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(1);

        assertEquals(4f + dodgeDv(), vz(), 0.1f,
                "同向闪避应在已有的同向速度上叠加 Δv");
    }

    /**
     * 闪避把速度赋值到闪避轴上：垂直于闪避方向的动量被整段抹掉。
     * <p>
     * 以 9.6 m/s（sprint 顶速 {@code 1.6 × v_ref}）沿 +Z 进入，向 +X 闪避（轴与运动方向垂直）。
     * 赋值结果是 {@code (max(v·u, 0) + Δv)·u = Δv·u}，即 +X 方向的 Δv，+Z 的方向上不应留下任何
     * 残余。本步同时受无输入制动 {@code μ·g·dt} 削减，因此沿轴速率要反解补回那一步。
     */
    @Test
    void dodgeAssignsTheVelocityVectorOntoTheDodgeAxis() {
        kcc.setMoveIntent(0f, 0f);
        setHorizontalVelocity(0f, 9.6f);

        kcc.requestDodgeImpulse(1f, 0f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(1);

        float brakePerStep = MechaWalkingAttr.MU_NAKED * MechaWalkingAttr.GRAVITY * DT;
        assertEquals(0f, vz(), 0.02f, "垂直于闪避轴的动量应被抹掉");
        assertEquals(dodgeDv(), vx() + brakePerStep, 0.05f,
                "沿轴速率应等于 Δv（本步再被 μ·g 削减一步）");
    }

    /**
     * 闪避方向与运动方向相反时，结果是干净的 Δv 反向位移，而不是「抵消旧动量后剩下的残余」。
     * <p>
     * 以 9.6 m/s 沿 +Z 进入、向 −Z 闪避：赋值把沿轴的负分量截断为 0 再叠加 Δv，因此得到 −Δv。
     * 若闪避是把 Δv 加到旧动量上，这里会剩下 {@code 9.6 − Δv}，人仍在朝原方向运动。
     */
    @Test
    void dodgeReversesTheVelocityWhenItOpposesTheMotion() {
        kcc.setMoveIntent(0f, 0f);
        setHorizontalVelocity(0f, 9.6f);

        kcc.requestDodgeImpulse(0f, -1f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(1);

        float brakePerStep = MechaWalkingAttr.MU_NAKED * MechaWalkingAttr.GRAVITY * DT;
        assertEquals(-(dodgeDv() - brakePerStep), vz(), 0.05f,
                "反向闪避应得到 Δv 大小的反向速度，不留旧方向的残余");
    }

    /**
     * 空中闪避同样把速度赋值到闪避轴上，且赋值结果成为空中天花板本身。
     * <p>
     * 进入速率 9.6 m/s 与闪避轴垂直：赋值后沿轴 Δv、垂直分量归零，因此本步的
     * {@code hSpeed = max(airCeiling, Δv) = Δv}——空中天花板取的是赋值后的速率，赋值结果被原样保留，
     * 而进入时的 9.6 m/s 不会把天花板抬到更高的那一档。
     */
    @Test
    void airDodgeAssignsTheVelocityAndTakesOverTheCeiling() {
        kcc.setMoveIntent(0f, 1f);
        step(2);
        tapJump();
        assertFalse(kcc.onGround(), "先决条件：应已离地");

        // 输入沿 +Z（闪避轴同向），进入速度沿 +X：赋值把 +X 的分量整段抹掉
        kcc.setMoveIntent(1f, 0f);
        setHorizontalVelocity(9.6f, 0f);
        kcc.requestDodgeImpulse(0f, 1f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(1);

        assertEquals(dodgeDv(), vz(), 0.05f,
                "空中闪避应完整保留赋值后的速率");
        assertEquals(0f, vx(), 0.02f, "垂直于闪避轴的动量应被抹掉，空中没有摩擦也一样");
    }

    /**
     * 空中闪避不被「不可加速」天花板吃掉（§3.7）。
     * <p>
     * 这条钉住赋值顺序：{@code hSpeed} 必须在赋值之后读，否则 {@code ceiling = max(airCeiling,
     * hSpeed)} 拿到的是不含本次闪避的旧速率（空中约 0.47 m/s），赋值后的速率会被 {@code ceiling/speedNow}
     * 静默缩掉——量级 6 → 0.47，且不会报任何错。用一个极小的闪避把它与"被缩掉"区分开：
     * 期望读回 ≈ Δv，而被缩掉时只剩 0.47 m/s。
     */
    @Test
    void dodgeImpulseSurvivesTheAirCeiling() {
        kcc.setMoveIntent(0f, 1f);
        step(2);

        tapJump();
        assertFalse(kcc.onGround(), "先决条件：应已离地");

        kcc.setMoveIntent(0f, 0f); // 松开输入：唯一会改变速率的就是这次闪避
        setHorizontalVelocity(0f, 0f);

        // 取一份恰好给出 Δv = 1 m/s 的冲量，这样「被缩掉」时的 0.47 m/s 与 Δv 差一个量级
        kcc.requestDodgeImpulse(0f, 1f, 1f * MechaWalkingAttr.MASS,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(1);

        assertEquals(1f, vz(), 0.1f,
                "空中没有摩擦，赋值结果应被完整保留（被天花板缩掉时会降到约 0.47 m/s）");
    }

    /**
     * 空中闪避比地面闪避更远：同一份冲量，地面被摩擦磨掉、空中原样保留。
     * <p>
     * 两个场景除了「是否着地」以外完全一致（同一份冲量、同样无输入），因此位移差全部来自
     * {@code μ·g} 这条地面制动力。
     */
    @Test
    void dodgeGoesFartherInTheAirThanOnTheGround() {
        // 地面：无输入，制动按 μ·g 磨
        Vector3f groundFrom = kcc.getPhysicsLocation(null);
        kcc.requestDodgeImpulse(0f, 1f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(100); // 1 s：足够把赋值带来的速率磨完
        Vector3f groundTo = kcc.getPhysicsLocation(null);
        float groundMoved = groundTo.z - groundFrom.z;

        // 空中：同样一份冲量，没有摩擦
        kcc.setMoveIntent(0f, 1f);
        step(2);
        tapJump();
        assertFalse(kcc.onGround(), "先决条件：应已离地");

        kcc.setMoveIntent(0f, 0f);
        setHorizontalVelocity(0f, 0f);
        Vector3f airFrom = kcc.getPhysicsLocation(null);
        kcc.requestDodgeImpulse(0f, 1f, MechaControl.DODGE_IMPULSE,
                MechaControl.DODGE_INVULNERABLE_SECONDS);
        step(1);
        float airStep = (kcc.getPhysicsLocation(null).z - airFrom.z) / DT;

        assertTrue(groundMoved > 0.2f, "地面闪避应确实位移，实际 " + groundMoved + " m");
        assertEquals(dodgeDv(), airStep, 0.1f,
                "空中闪避应完整保留赋值后的速率，实际 " + airStep + " m/s");
        assertTrue(airStep > groundMoved, "空中闪避的瞬时速率应高于被摩擦磨过的地面闪避");
    }

    // ═══════════════════════════════════════════════
    // 瞬时冲量 + 持续助推窗口（§1 模型的不变量）
    // ═══════════════════════════════════════════════

    /** 一个独立的物理空间 + 地面 + 角色，用于助推窗口生效的力学测试。 */
    private record Rig(PhysicsSpace space, MechaCharacter body) {
    }

    /**
     * 造一个助推窗口生效、质量为 {@code mass}、助推力为 {@code boostForce} 的控制器。
     * <p>
     * 与 {@link #kcc} 不同，这里不屏蔽助推，并自带独立物理空间，避免与 {@link #kcc} 互相碰撞。
     */
    private static Rig newRig(float mass, float boostForce) {
        PhysicsSpace rigSpace = new PhysicsSpace(
                new Vector3f(-500f, -500f, -500f), new Vector3f(500f, 500f, 500f));
        rigSpace.addCollisionObject(staticBox(new Vector3f(400f, 0.5f, 400f), new Vector3f(0f, -0.5f, 0f)));
        MechaCharacter body = new MechaCharacter(MechaBodyPreset.newCapsuleShape(), rigSpace) {
            @Override
            protected float getControllerMass() {
                return mass;
            }

            @Override
            protected float getBoostForce() {
                return boostForce;
            }
        };
        body.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
        rigSpace.addCollisionObject(body);
        return new Rig(rigSpace, body);
    }

    /** 在独立 rig 上跑 {@code steps} 个物理步。 */
    private static void stepRig(Rig rig, int steps) {
        for (int i = 0; i < steps; i++) {
            rig.body().prePhysicsTick(DT);
            rig.space().update(DT, 0, 0x0);
        }
    }

    /**
     * 按住 {@code holdSteps} 个物理步后松开，继续步进直到落地，返回相对起点的最大上升高度 (m)。
     * <p>
     * 松开后不再按住，因此落地不会再次起跳（§8 的「落地时若仍按住则再次起跳」因此不参与本测量）。
     * 落地判据取 {@link MechaCharacter#onGround()}。
     */
    private static float maxRiseWhileHolding(Rig rig, int holdSteps) {
        float y0 = rig.body().getPhysicsLocation(null).y;
        rig.body().setJumpInput(true, false);
        stepRig(rig, holdSteps);
        rig.body().setJumpInput(false, true);

        float maxRise = 0f;
        for (int i = 0; i < 400; i++) {
            stepRig(rig, 1);
            maxRise = Math.max(maxRise, rig.body().getPhysicsLocation(null).y - y0);
            if (i > 2 && rig.body().onGround()) break;
        }
        return maxRise;
    }

    /**
     * 不变量 1：轻拍（只走冲量相）。{@code v0 = I_BASE/m = 325/70 = 4.643 m/s}，
     * 高度 = {@code v0²/(2g) ≈ 1.10 m}，上升时长 = {@code v0/g ≈ 0.473 s}。
     */
    @Test
    void tapJumpRisesToTheImpulseOnlyHeight() {
        Rig rig = newRig(MechaWalkingAttr.MASS, 0f);
        float y0 = rig.body().getPhysicsLocation(null).y;

        rig.body().setJumpInput(true, false);
        stepRig(rig, 1);                      // 冲量相：按下即起跳
        rig.body().setJumpInput(false, true);
        stepRig(rig, 1);                      // 松开：窗口立即终止

        float maxRise = 0f;
        int upSteps = 0;
        for (int i = 0; i < 300 && !rig.body().onGround(); i++) {
            stepRig(rig, 1);
            maxRise = Math.max(maxRise, rig.body().getPhysicsLocation(null).y - y0);
            if (rig.body().getLinearVelocity(null).y > 0f) upSteps++;
        }

        assertEquals(1.10f, maxRise, 0.05f, "轻拍高度应由 v0²/(2g) 给出");
        assertEquals(0.473f, upSteps * DT, 0.05f, "上升时长应为 v0/g");
    }

    /**
     * 不变量 2：按住至截止高度 ≈ 1.74 m（{@code m = 70}、{@code F_BOOST = 500}、
     * {@code T_BOOST_MAX = 0.20}）。
     */
    @Test
    void holdingToTheBoostCutoffReachesTheTallerHeight() {
        Rig rig = newRig(MechaWalkingAttr.MASS, MechaJumpAttr.F_BOOST);
        float maxRise = maxRiseWhileHolding(rig, 40);   // 0.4 s > T_BOOST_MAX
        assertEquals(1.74f, maxRise, 0.06f, "按住至截止的高度应由冲量 + 助推积分给出");
    }

    /** 不变量 3：按住越久越高（冲量相同，助推注入的动量随按住时长增加）。 */
    @Test
    void holdingLongerNeverLowersTheJump() {
        float h5 = maxRiseWhileHolding(newRig(MechaWalkingAttr.MASS, MechaJumpAttr.F_BOOST), 5);
        float h10 = maxRiseWhileHolding(newRig(MechaWalkingAttr.MASS, MechaJumpAttr.F_BOOST), 10);
        float h20 = maxRiseWhileHolding(newRig(MechaWalkingAttr.MASS, MechaJumpAttr.F_BOOST), 20);

        assertTrue(h5 < h10, "0.05 s 的按住应低于 0.10 s，实际 " + h5 + " / " + h10);
        assertTrue(h10 < h20, "0.10 s 的按住应低于 0.20 s，实际 " + h10 + " / " + h20);
    }

    /** 不变量 4：松开即停——松开后垂直加速度恰为 {@code −g}。 */
    @Test
    void releasingEndsTheBoostSoGravityTakesOver() {
        Rig rig = newRig(MechaWalkingAttr.MASS, MechaJumpAttr.F_BOOST);
        rig.body().setJumpInput(true, false);
        stepRig(rig, 5);                      // 窗口内按住
        rig.body().setJumpInput(false, true);
        stepRig(rig, 1);                      // 松开那一物理步：不注入助推

        float v1 = rig.body().getLinearVelocity(null).y;
        stepRig(rig, 1);
        float v2 = rig.body().getLinearVelocity(null).y;

        assertEquals(-MechaWalkingAttr.GRAVITY, (v2 - v1) / DT, 0.1f,
                "松开后应只剩重力，垂直加速度为 −g");
    }

    /**
     * 不变量 5：时间兜底。轻质量（{@code m = 35}）下 {@code F_BOOST/m ≥ g}，窗口内不出现上止点，
     * 只有 {@code T_BOOST_MAX} 能收住；按住不放也必须转为下降且不再上升。
     */
    @Test
    void lightBodyCannotHoverUnderAContinuousHold() {
        float lightMass = 35f;
        Rig rig = newRig(lightMass, MechaJumpAttr.F_BOOST);
        assertTrue(MechaJumpAttr.F_BOOST / lightMass >= MechaWalkingAttr.GRAVITY,
                "先决条件：轻质量下助推加速度不弱于重力");

        rig.body().setJumpInput(true, false);
        boolean descended = false;
        for (int i = 0; i < 180; i++) {        // 1.8 s：窗口 0.2 s + 上升余量，落地前收住
            stepRig(rig, 1);
            float vy = rig.body().getLinearVelocity(null).y;
            if (vy <= 0f) {
                descended = true;
            }
            if (descended) {
                assertTrue(vy <= 0.05f, "时间兜底后不得再上升，实际 vy=" + vy);
            }
        }
        assertTrue(descended, "轻质量机体按住不放也必转为下降，不得悬浮");
    }

    /**
     * 不变量 6：窗口末速（{@code m = 70}）为 {@code v0 + (F_BOOST/m − g)·T_BOOST_MAX ≈ 4.11 m/s}；
     * 该末速可超过 {@code V_EXTEND}（{@code m = 35} 时 ≈ 10.18 m/s > 10），说明上限只钳制冲量相。
     */
    @Test
    void boostWindowSpeedIsTheImpulsePlusBoostIntegral() {
        int windowSteps = Math.round(MechaJumpAttr.T_BOOST_MAX / DT);   // 0.20 s / 0.01 s = 20

        Rig standard = newRig(MechaWalkingAttr.MASS, MechaJumpAttr.F_BOOST);
        standard.body().setJumpInput(true, false);
        stepRig(standard, windowSteps);

        assertEquals(4.11f, standard.body().getLinearVelocity(null).y, 0.05f,
                "窗口末速 = v0 + (F_BOOST/m − g)·T_BOOST_MAX");

        Rig light = newRig(35f, MechaJumpAttr.F_BOOST);
        light.body().setJumpInput(true, false);
        stepRig(light, windowSteps);

        float lightVy = light.body().getLinearVelocity(null).y;
        assertTrue(lightVy > MechaJumpAttr.V_EXTEND,
                "窗口末速可超过 V_EXTEND（上限只钳制冲量相），实际 " + lightVy + " m/s");
    }
}
