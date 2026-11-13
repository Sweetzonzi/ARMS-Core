package io.github.sweetzonzi.arms_core.util.control;

import io.github.sweetzonzi.arms_core.common.control.attr.LegDriveAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ForceSpeedCurve} 的数值判据。预期值按力速曲线的定义手算，
 * 不调用被测公式本身充当期望。
 *
 * @author Sweetzonzi
 */
class ForceSpeedCurveTest {

    /** 单源基线：F = 600 N、B = 600 N、三档零载速度同为 6 m/s。 */
    private static final LegDriveAttr SIX = new LegDriveAttr(600f, 600f, 6f, 6f, 6f);

    @Test
    void curvePassesThroughZeroSpeedFullForceAndZeroForcePoint() {
        assertEquals(600.0, ForceSpeedCurve.signedForce(0.0, SIX, Gait.JOG), 1e-9);
        assertEquals(450.0, ForceSpeedCurve.signedForce(3.0, SIX, Gait.JOG), 1e-9);
        assertEquals(0.0, ForceSpeedCurve.signedForce(6.0, SIX, Gait.JOG), 1e-9);
    }

    @Test
    void squareCurveIsAboveLinearInTheMiddle() {
        // 线性 1−v/V 在 v/V = 0.5 处给 0.5，平方曲线给 0.75
        assertEquals(450.0, ForceSpeedCurve.signedForce(3.0, SIX, Gait.JOG), 1e-9);
        assertEquals(600.0 * (1.0 - 0.8 * 0.8), ForceSpeedCurve.signedForce(4.8, SIX, Gait.JOG), 1e-9);
    }

    @Test
    void overSpeedContributionTurnsNegativeBeforeTheLimitBites() {
        // v = √48 ≈ 6.9282：1 − 48/36 = −1/3，因此 −200 N
        assertEquals(-200.0, ForceSpeedCurve.signedForce(Math.sqrt(48.0), SIX, Gait.JOG), 1e-9);
    }

    @Test
    void negativeHalfIsClampedToTheDragLimit() {
        // 原始值 −1800 N，上限 B = 600 N
        assertEquals(-600.0, ForceSpeedCurve.signedForce(12.0, SIX, Gait.JOG), 1e-9);
        LegDriveAttr tight = new LegDriveAttr(600f, 200f, 6f, 6f, 6f);
        assertEquals(-200.0, ForceSpeedCurve.signedForce(12.0, tight, Gait.JOG), 1e-9);
    }

    @Test
    void reverseSpeedIsNotFoldedIntoTheCurve() {
        // 逆向运动按 s_eff = 0 处理：平方曲线在 s = −V 处原始值为 0，更负时会给出错误的负力
        assertEquals(600.0, ForceSpeedCurve.signedForce(-2.5, SIX, Gait.JOG), 1e-9);
        assertEquals(600.0, ForceSpeedCurve.signedForce(-6.0, SIX, Gait.JOG), 1e-9);
    }

    @Test
    void gearSelectsItsOwnNoLoadSpeed() {
        LegDriveAttr threeSpeed = new LegDriveAttr(600f, 600f, 4f, 8f, 12f);
        assertEquals(0.0, ForceSpeedCurve.signedForce(8.0, threeSpeed, Gait.JOG), 1e-9);
        // 同一速度在慢走档已远超零出力点，反拖限幅生效
        assertEquals(-600.0, ForceSpeedCurve.signedForce(8.0, threeSpeed, Gait.CREEP), 1e-9);
        assertEquals(0.0, ForceSpeedCurve.signedForce(12.0, threeSpeed, Gait.SPRINT), 1e-9);
    }

    @Test
    void nonDrivingGaitHasNoCurve() {
        assertThrows(IllegalArgumentException.class, () -> SIX.noLoadSpeed(Gait.IDLE));
        assertThrows(IllegalArgumentException.class, () -> SIX.noLoadSpeed(Gait.STUN));
    }

    @Test
    void perSourceSumEqualsTheSumOfItsParts() {
        LegDriveAttr slow = new LegDriveAttr(600f, 600f, 6f, 6f, 6f);
        LegDriveAttr fast = new LegDriveAttr(300f, 300f, 12f, 12f, 12f);
        List<LegDriveAttr> specs = List.of(slow, fast);

        assertEquals(900.0, ForceSpeedCurve.gearForce(specs, Gait.JOG, 0.0), 1e-9);
        // 快慢源在 √48 处各自过零：−200 + 200 = 0
        assertEquals(0.0, ForceSpeedCurve.gearForce(specs, Gait.JOG, Math.sqrt(48.0)), 1e-6);
        // v = 3：慢速源 600·(1 − 0.25) = 450 N，快速源 300·(1 − 1/16) = 281.25 N
        assertEquals(731.25, ForceSpeedCurve.gearForce(specs, Gait.JOG, 3.0), 1e-9);
    }

    @Test
    void mixedZeroSpeedMatchesTheRootOfTheSummedCurve() {
        LegDriveAttr slow = new LegDriveAttr(600f, 600f, 6f, 6f, 6f);
        LegDriveAttr fast = new LegDriveAttr(300f, 300f, 12f, 12f, 12f);
        assertEquals(Math.sqrt(48.0),
                ForceSpeedCurve.mixedZeroSpeed(List.of(slow, fast), Gait.JOG), 1e-9);
        assertEquals(6.0, ForceSpeedCurve.mixedZeroSpeed(List.of(slow), Gait.JOG), 1e-9);
        assertEquals(0.0, ForceSpeedCurve.mixedZeroSpeed(List.of(), Gait.JOG), 1e-9);
    }

    @Test
    void peakPowerIsTwoOverThreeRootThreeTimesFV() {
        assertEquals(1385.6406460551018, ForceSpeedCurve.peakPower(600.0, 6.0), 1e-9);
        assertEquals(461.88021535170066, ForceSpeedCurve.peakPower(600.0, 2.0), 1e-9);
        assertEquals(2309.401076758503, ForceSpeedCurve.peakPower(600.0, 10.0), 1e-9);
    }

    @Test
    void powerIsZeroForReverseMotion() {
        assertEquals(0.0, ForceSpeedCurve.power(-1.0, 600.0), 1e-9);
        assertEquals(1200.0, ForceSpeedCurve.power(2.0, 600.0), 1e-9);
    }
}