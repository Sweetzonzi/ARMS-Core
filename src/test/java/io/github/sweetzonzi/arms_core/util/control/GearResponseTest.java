package io.github.sweetzonzi.arms_core.util.control;

import io.github.sweetzonzi.arms_core.common.control.attr.LegDriveAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GearResponse} 的数值判据：一阶响应系数、权重递推、混合合力与混合零点。
 * 预期值按一阶响应的离散系数与混合合力的定义手算。
 *
 * @author Sweetzonzi
 */
class GearResponseTest {

    /** F = 600 N、三档零载速度 6 / 10 / 12 m/s。 */
    private static final LegDriveAttr SPEC = new LegDriveAttr(600f, 600f, 6f, 10f, 12f);

    @Test
    void alphaIsTheExactDiscreteCoefficient() {
        assertEquals(0.09516258196404048, GearResponse.alpha(0.01, 0.1), 1e-12);
        assertEquals(1.0, GearResponse.alpha(0.01, 0.0), 1e-12);
        assertEquals(1.0, GearResponse.alpha(0.01, -1.0), 1e-12);
    }

    @Test
    void alphaRejectsNonPositiveStep() {
        assertThrows(IllegalArgumentException.class, () -> GearResponse.alpha(0.0, 0.1));
    }

    @Test
    void weightsRampTowardTheTargetGear() {
        double[] start = GearResponse.zeroWeights();
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, start, 1e-12);

        double[] first = GearResponse.step(start, 0.5, 1);
        assertArrayEquals(new double[]{0.0, 0.5, 0.0}, first, 1e-12);

        double[] second = GearResponse.step(first, 0.5, 1);
        assertArrayEquals(new double[]{0.0, 0.75, 0.0}, second, 1e-12);
    }

    @Test
    void switchingGearRedistributesWithoutExceedingOne() {
        double[] weights = GearResponse.step(new double[]{0.5, 0.5, 0.0}, 0.5, 2);
        assertArrayEquals(new double[]{0.25, 0.25, 0.5}, weights, 1e-12);
        double sum = weights[0] + weights[1] + weights[2];
        assertEquals(1.0, sum, 1e-12);
    }

    @Test
    void singleGearAndBoundsAreChecked() {
        assertArrayEquals(new double[]{0.0, 1.0, 0.0}, GearResponse.singleGear(1), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> GearResponse.singleGear(3));
        assertThrows(IllegalArgumentException.class,
                () -> GearResponse.step(new double[]{1.0, 0.0}, 0.5, 0));
    }

    @Test
    void blendedForceWeighsEachGearCurve() {
        // v = 6：慢走档零出力，常速档 600·(1 − 36/100) = 384 N
        double[] half = {0.5, 0.5, 0.0};
        assertEquals(192.0, GearResponse.blendedForce(List.of(SPEC), half, 6.0), 1e-9);
        assertEquals(0.0, GearResponse.blendedForce(List.of(SPEC), GearResponse.singleGear(0), 6.0), 1e-9);
        assertEquals(384.0, GearResponse.blendedForce(List.of(SPEC), GearResponse.singleGear(1), 6.0), 1e-9);
    }

    @Test
    void blendedZeroSpeedIsNotAnySingleGearValue() {
        assertEquals(6.0, GearResponse.zeroSpeed(List.of(SPEC), GearResponse.singleGear(0)), 1e-9);
        assertEquals(10.0, GearResponse.zeroSpeed(List.of(SPEC), GearResponse.singleGear(1)), 1e-9);
        assertEquals(12.0, GearResponse.zeroSpeed(List.of(SPEC), GearResponse.singleGear(2)), 1e-9);
        // √(600 / (8.3333 + 3)) = 7.27607
        assertEquals(7.2760690, GearResponse.zeroSpeed(List.of(SPEC), new double[]{0.5, 0.5, 0.0}), 1e-6);
    }

    @Test
    void gearOrderMatchesTheThreeSpeeds() {
        assertEquals(List.of(Gait.CREEP, Gait.JOG, Gait.SPRINT), GearResponse.GEARS);
    }
}