package io.github.sweetzonzi.arms_core.util.control;

import io.github.sweetzonzi.arms_core.common.control.attr.LegDriveAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GroundDrive} 的数值判据：有输入／无输入两条支路、整机一次裁剪、附着上限与门值上界。
 * 预期值按力速曲线、动量限幅、附着预算与指数制动的定义手算。
 *
 * @author Sweetzonzi
 */
class GroundDriveTest {

    /** 单源：F = 700 N、B = 700 N、三档零载速度 6 m/s。 */
    private static final LegDriveAttr SOURCE = new LegDriveAttr(700f, 700f, 6f, 6f, 6f);

    /** 常速档权重。 */
    private static final double[] JOG = {0.0, 1.0, 0.0};

    private static final double DT = 0.01;
    private static final double MASS = 70.0;
    private static final double GRAVITY = 9.81;
    /** 平地 μ_pair_base = 1.0 的静态载荷 686.7 N。 */
    private static final double LOAD = 686.7;

    @Test
    void driveRequestIsClampedByTheAdhesionBudget() {
        // 700 N 的请求超过 μ·N = 686.7 N，本条裁剪就是主动力的硬上限
        assertEquals(686.7, GroundDrive.evaluate(List.of(SOURCE), JOG, true, 0.0, DT, MASS, 1.0, LOAD,
                GRAVITY).forceAlong(), 1e-9);
        // v = 3 m/s 时曲线给 700·(1 − 0.25) = 525 N，低于上限
        assertEquals(525.0, GroundDrive.evaluate(List.of(SOURCE), JOG, true, 3.0, DT, MASS, 1.0, LOAD,
                GRAVITY).forceAlong(), 1e-9);
    }

    @Test
    void overspeedDragIsClippedByTheAdhesionBudget() {
        // 前提：v = 12 m/s 时曲线原始值 −2100 N，被反拖上限截到 −700 N
        // （ForceSpeedCurveTest#negativeHalfIsClampedToTheDragLimit）。
        assertEquals(-700.0, ForceSpeedCurve.gearForce(List.of(SOURCE), Gait.JOG, 12.0), 1e-9);
        // 结论：−700 N 再由本步附着上限 μ·N = 686.7 N 截一次。
        assertEquals(-686.7, GroundDrive.evaluate(List.of(SOURCE), JOG, true, 12.0, DT, MASS, 1.0, LOAD,
                GRAVITY).forceAlong(), 1e-9);
    }

    @Test
    void zeroWeightsProduceNoForce() {
        // 响应刚起步、权重还是零时不得凭空出力
        assertEquals(0.0, GroundDrive.evaluate(List.of(SOURCE), GearResponse.zeroWeights(), true, 3.0,
                DT, MASS, 1.0, LOAD, GRAVITY).forceAlong(), 1e-9);
    }

    @Test
    void brakeIsMomentumLimitedAndAdhesionLimited() {
        // 6 m/s：m·v/dt = 42000 N 远超能力，取能力 700 N，再被 686.7 N 截
        assertEquals(686.7, GroundDrive.evaluate(List.of(SOURCE), JOG, false, 6.0, DT, MASS, 1.0, LOAD,
                GRAVITY).brakeMagnitude(), 1e-9);
        // 0.05 m/s：m·v/dt = 350 N，取动量项
        assertEquals(350.0, GroundDrive.evaluate(List.of(SOURCE), JOG, false, 0.05, DT, MASS, 1.0,
                LOAD, GRAVITY).brakeMagnitude(), 1e-9);
    }

    @Test
    void holdSpeedBoundIsTheSmallerOfActuatorAndAdhesion() {
        // 700·0.01/70 = 0.1，μ·g·dt = 0.0981
        assertEquals(0.0981, GroundDrive.evaluate(List.of(SOURCE), JOG, true, 0.0, DT, MASS, 1.0, LOAD,
                GRAVITY).holdSpeedBound(), 1e-12);
    }

    @Test
    void noSourceMeansNoForceAndNoBudget() {
        GroundDrive.Plan plan = GroundDrive.evaluate(List.of(), JOG, true, 3.0, DT, MASS, 1.0, LOAD,
                GRAVITY);
        assertEquals(0.0, plan.forceAlong(), 1e-12);
        assertEquals(0.0, plan.holdSpeedBound(), 1e-12);
        assertEquals(0.0, GroundDrive.evaluate(List.of(), JOG, false, 6.0, DT, MASS, 1.0, LOAD,
                GRAVITY).brakeMagnitude(), 1e-12);
    }

    @Test
    void multipleSourcesAreSummedBeforeClipping() {
        // 两个 400 N 源在 v = 0 的合计 800 N，被 686.7 N 截一次，而不是各截一次
        LegDriveAttr half = new LegDriveAttr(400f, 400f, 6f, 6f, 6f);
        assertEquals(686.7, GroundDrive.evaluate(List.of(half, half), JOG, true, 0.0, DT, MASS, 1.0,
                LOAD, GRAVITY).forceAlong(), 1e-9);
    }

    @Test
    void sideBrakeAtCruiseUsesFrictionAndDropsWithGrip() {
        // 沿向正好 6 m/s，推进力为零；侧速的制动单独检验摩擦量级。
        GroundDrive.Plan full = GroundDrive.evaluate(List.of(SOURCE), JOG, true, 6.0, 2.0,
                DT, MASS, 1.0, LOAD, GRAVITY);
        GroundDrive.Plan ice = GroundDrive.evaluate(List.of(SOURCE), JOG, true, 6.0, 2.0,
                DT, MASS, 0.1, LOAD, GRAVITY);
        assertEquals(0.0, full.forceAlong(), 1e-9);
        assertEquals(-686.7, full.forceSide(), 1e-9);
        assertEquals(-68.67, ice.forceSide(), 1e-9);
    }

    @Test
    void smallSideSpeedStopsWithoutCrossingZeroInEitherDirection() {
        for (double sideSpeed : new double[]{-0.001, 0.0, 0.001}) {
            // 3 m/s 的沿向请求 525 N 留有附着余量，小侧速只需最多 7 N。
            GroundDrive.Plan plan = GroundDrive.evaluate(List.of(SOURCE), JOG, true, 3.0, sideSpeed,
                    DT, MASS, 1.0, LOAD, GRAVITY);
            assertEquals(525.0, plan.forceAlong(), 1e-9);
            assertEquals(0.0, sideSpeed + plan.forceSide() * DT / MASS, 1e-12);
        }
    }

    @Test
    void twoAxesShareOneCircularAdhesionBudget() {
        GroundDrive.Plan plan = GroundDrive.evaluate(List.of(SOURCE), JOG, true, 0.0, 5.0,
                DT, MASS, 0.1, LOAD, GRAVITY);
        assertTrue(plan.forceAlong() > 0.0);
        assertTrue(plan.forceSide() < 0.0);
        assertEquals(68.67, Math.hypot(plan.forceAlong(), plan.forceSide()), 1e-9);
        // 若先把沿向单独截到 68.67 N，再加侧向请求，比例会被改成 1:1。
        assertEquals(700.0 / 68.67, plan.forceAlong() / -plan.forceSide(), 1e-9);
    }

    @Test
    void sideBrakeAndOverspeedDragShareTheActuatorLimit() {
        GroundDrive.Plan plan = GroundDrive.evaluate(List.of(SOURCE), JOG, true, 12.0, -4.0,
                DT, MASS, 1.0, 2000.0, GRAVITY);
        assertTrue(plan.forceAlong() < 0.0);
        assertTrue(plan.forceSide() > 0.0);
        assertEquals(700.0, Math.hypot(plan.forceAlong(), plan.forceSide()), 1e-9);
        assertEquals(0.0, plan.brakeMagnitude(), 1e-12);
    }

    @Test
    void noInputAndNoActuatorsDoNotCreateAnExtraSideRequest() {
        GroundDrive.Plan brake = GroundDrive.evaluate(List.of(SOURCE), JOG, false, 0.05, 9.0,
                DT, MASS, 1.0, LOAD, GRAVITY);
        assertEquals(350.0, brake.brakeMagnitude(), 1e-9);
        assertEquals(0.0, brake.forceSide(), 1e-12);
        GroundDrive.Plan empty = GroundDrive.evaluate(List.of(), JOG, true, 0.0, 5.0,
                DT, MASS, 1.0, LOAD, GRAVITY);
        assertEquals(0.0, Math.hypot(empty.forceAlong(), empty.forceSide()), 1e-12);
    }

    @Test
    void assistDefaultsToZeroAndRejectsInvalidFractions() {
        assertEquals(0f, SOURCE.brakeAssist());
        LegDriveAttr explicitZero = new LegDriveAttr(700f, 700f, 6f, 6f, 6f, 0f);
        for (boolean input : new boolean[]{false, true}) {
            assertEquals(GroundDrive.evaluate(List.of(SOURCE), JOG, input, 2.0, -3.0,
                            DT, MASS, 1.0, LOAD, GRAVITY),
                    GroundDrive.evaluate(List.of(explicitZero), JOG, input, 2.0, -3.0,
                            DT, MASS, 1.0, LOAD, GRAVITY));
        }
        for (float value : new float[]{-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LegDriveAttr(700f, 700f, 1.6f, 4f, 6f, value));
        }
    }

    @Test
    void assistFractionBlendsAfterBaseClippingAndCanExceedItsLimits() {
        double base = assistedPlan(0f, false, 2.0, 0.0, 1.0, DT).brakeMagnitude();
        double full = assistedPlan(1f, false, 2.0, 0.0, 1.0, DT).brakeMagnitude();
        double half = assistedPlan(0.5f, false, 2.0, 0.0, 1.0, DT).brakeMagnitude();
        // 2 m/s、JOG 参考 4 m/s：w=exp(-1/16)，标准指数力约 1595.86 N。
        double exponential = MASS * (1.0 - Math.pow(0.546, DT / 0.05)) * 2.0 / DT;
        assertEquals(686.7, base, 1e-9);
        assertEquals(base + Math.exp(-1.0 / 16.0) * (exponential - base), full, 1e-9);
        assertEquals((base + full) / 2.0, half, 1e-9);
        assertTrue(full > 700.0, "辅助允许超过基础执行能力及 μN");
    }

    @Test
    void sideAssistPreservesTheBaseForwardForce() {
        GroundDrive.Plan base = assistedPlan(0f, true, 1.0, -2.0, 1.0, DT);
        GroundDrive.Plan assisted = assistedPlan(1f, true, 1.0, -2.0, 1.0, DT);
        assertEquals(base.forceAlong(), assisted.forceAlong());
        assertTrue(assisted.forceSide() > base.forceSide());
        assertEquals(0.0, assisted.brakeMagnitude());
        assertEquals(0.0, assistedPlan(1f, true, 1.0, 0.0, 1.0, DT).forceSide(), 0.0);
    }

    @Test
    void highTotalSpeedDisablesAssistEvenWhenSideSpeedIsSmall() {
        GroundDrive.Plan base = assistedPlan(0f, true, 12.0, 0.5, 1.0, DT);
        GroundDrive.Plan assisted = assistedPlan(1f, true, 12.0, 0.5, 1.0, DT);
        assertEquals(base.forceSide(), assisted.forceSide(), 1e-12);
        assertEquals(assistedPlan(0f, false, 12.0, 0.0, 1.0, DT).brakeMagnitude(),
                assistedPlan(1f, false, 12.0, 0.0, 1.0, DT).brakeMagnitude(), 1e-12);
        assertTrue(Math.abs(assistedPlan(1f, true, 0.0, 2.0, 1.0, DT).forceSide()) > 700.0);
    }

    @Test
    void partsBlendAssistByMaximumForceAndTheirOwnJogSpeed() {
        LegDriveAttr slow = new LegDriveAttr(100f, 100f, 1f, 2f, 10f, 1f);
        LegDriveAttr fast = new LegDriveAttr(300f, 300f, 1f, 4f, 10f, 0.5f);
        LegDriveAttr decoration = new LegDriveAttr(0f, 0f, 1f, 30f, 50f, 1f);
        double expectedWeight = (100.0 * Math.exp(-1.0) + 150.0 * Math.exp(-1.0 / 16.0)) / 400.0;
        double exponential = MASS * (1.0 - Math.pow(0.546, DT / 0.05)) * 2.0 / DT;
        for (double[] weights : new double[][]{{1, 0, 0}, JOG, {0, 0, 1}, {0, 0, 0}}) {
            GroundDrive.Plan plan = GroundDrive.evaluate(List.of(slow, fast, decoration), weights,
                    false, 2.0, DT, MASS, 1.0, LOAD, GRAVITY);
            assertEquals(400.0 + expectedWeight * (exponential - 400.0), plan.brakeMagnitude(), 1e-9);
        }
        assertEquals(0.0, GroundDrive.evaluate(List.of(decoration), JOG, false, 2.0,
                DT, MASS, 1.0, LOAD, GRAVITY).brakeMagnitude());
    }

    @Test
    void exponentialResponseRetainsMaterialDependence() {
        double fullGrip = assistedPlan(1f, false, 2.0, 0.0, 1.0, DT).brakeMagnitude();
        double lowGrip = assistedPlan(1f, false, 2.0, 0.0, 0.1, DT).brakeMagnitude();
        assertTrue(fullGrip > lowGrip);
        assertTrue(lowGrip > 0.0);
        assertEquals(0.0, assistedPlan(1f, false, 2.0, 0.0, 0.0, DT).brakeMagnitude());
        assertEquals(0.0, assistedPlan(1f, true, 1.0, 2.0, 0.0, DT).forceSide(), 0.0);
    }

    @Test
    void lowSpeedLimitMatchesStandardDecayAcrossTimeSteps() {
        double speed = 1e-5; // 此时速度包络按双精度取 1，直接测指数极限。
        double whole = speed - assistedPlan(1f, false, speed, 0.0, 1.0, 0.05)
                .brakeMagnitude() * 0.05 / MASS;
        double half = speed - assistedPlan(1f, false, speed, 0.0, 1.0, 0.025)
                .brakeMagnitude() * 0.025 / MASS;
        half -= assistedPlan(1f, false, half, 0.0, 1.0, 0.025).brakeMagnitude() * 0.025 / MASS;
        assertEquals(speed * 0.546, whole, 1e-16);
        assertEquals(whole, half, 1e-16);
    }

    @Test
    void assistedBrakingIsDissipativeAndCannotReverseVelocityByItself() {
        for (double dt : new double[]{0.001, 0.01, 0.05, 0.2}) {
            for (double mu : new double[]{0.0, 0.1, 1.0, 10.0}) {
                for (double speed : new double[]{0.0, 1e-8, 0.05, 0.5, 2.0, 4.0, 12.0}) {
                    for (float assist : new float[]{0f, 0.5f, 1f}) {
                        double brake = assistedPlan(assist, false, speed, 0.0, mu, dt).brakeMagnitude();
                        assertTrue(brake >= 0.0 && brake * dt <= MASS * speed + 1e-9);
                        for (double sign : new double[]{-1.0, 1.0}) {
                            double side = sign * speed;
                            double force = assistedPlan(assist, true, 0.0, side, mu, dt).forceSide();
                            assertTrue(force * side <= 0.0);
                            assertTrue((side + force * dt / MASS) * sign >= -1e-9);
                        }
                    }
                }
            }
        }
    }

    private static GroundDrive.Plan assistedPlan(float assist, boolean input, double along,
                                                  double side, double mu, double dt) {
        LegDriveAttr source = new LegDriveAttr(700f, 700f, 1.6f, 4f, 6f, assist);
        return GroundDrive.evaluate(List.of(source), JOG, input, along, side,
                dt, MASS, mu, LOAD, GRAVITY);
    }
}
