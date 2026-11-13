package io.github.sweetzonzi.arms_core.util.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContactBudget} 的数值判据：动量限幅制动、保持门与门值上界、载荷软压缩、附着冲量与执行裁剪。
 * 预期值按动量限幅、门值上界、软压缩与执行裁剪的定义手算。
 *
 * @author Sweetzonzi
 */
class ContactBudgetTest {

    private static final double MASS = 70.0;
    private static final double GRAVITY = 9.81;
    private static final double DT = 0.01;

    @Test
    void brakeRequestIsMomentumLimitedNearZero() {
        // 6 m/s 时 m·v/dt = 42000 N 远超 600 N，取能力上限
        assertEquals(600.0, ContactBudget.brakeForce(6.0, 600.0, MASS, DT), 1e-9);
        // 0.05 m/s 时 m·v/dt = 350 N，取动量项
        assertEquals(350.0, ContactBudget.brakeForce(0.05, 600.0, MASS, DT), 1e-9);
        assertEquals(350.0, ContactBudget.brakeForce(-0.05, 600.0, MASS, DT), 1e-9);
        assertEquals(0.0, ContactBudget.brakeForce(0.0, 600.0, MASS, DT), 1e-9);
    }

    @Test
    void holdSpeedBoundIsTheSmallerOfActuatorAndAdhesion() {
        // B·dt/m = 0.085714…，μ·g·dt = 0.0981
        assertEquals(0.08571428571428572,
                ContactBudget.holdSpeedBound(600.0, MASS, DT, 1.0, GRAVITY), 1e-12);
        // 300 N 时主动项 0.042857 更小
        assertEquals(0.04285714285714286,
                ContactBudget.holdSpeedBound(300.0, MASS, DT, 1.0, GRAVITY), 1e-12);
        // 低附着时 μ·g·dt 更小
        assertEquals(0.00981,
                ContactBudget.holdSpeedBound(600.0, MASS, DT, 0.1, GRAVITY), 1e-12);
    }

    @Test
    void holdGateRequiresEveryCondition() {
        assertTrue(ContactBudget.holdGate(true, true, false, 0.05, 0.02, 0.086, 0.5));
        assertFalse(ContactBudget.holdGate(false, true, false, 0.05, 0.02, 0.086, 0.5));
        assertFalse(ContactBudget.holdGate(true, false, false, 0.05, 0.02, 0.086, 0.5));
        assertFalse(ContactBudget.holdGate(true, true, true, 0.05, 0.02, 0.086, 0.5));
        assertFalse(ContactBudget.holdGate(true, true, false, 0.10, 0.02, 0.086, 0.5));
        assertFalse(ContactBudget.holdGate(true, true, false, 0.05, 0.60, 0.086, 0.5));
        // 逆向相对速度按绝对值判
        assertTrue(ContactBudget.holdGate(true, true, false, -0.05, -0.02, 0.086, 0.5));
    }

    @Test
    void softCompressionKeepsTruthBelowTheKneeAndLogsAbove() {
        double ng = MASS * GRAVITY; // 686.7 N
        assertEquals(1.0 * ng, ContactBudget.softCompress(1.0 * ng, ng, 2.0, 1.0), 1e-9);
        assertEquals(2.0 * ng, ContactBudget.softCompress(2.0 * ng, ng, 2.0, 1.0), 1e-9);
        // 2 + ln(9) = 4.197225
        assertEquals(4.19722457733622 * ng,
                ContactBudget.softCompress(10.0 * ng, ng, 2.0, 1.0), 1e-9);
        // 2 + ln(89) = 6.48863636973214
        assertEquals(6.48863636973214 * ng,
                ContactBudget.softCompress(90.0 * ng, ng, 2.0, 1.0), 1e-9);
    }

    @Test
    void softCompressionIsMonotonicAndContinuousAtTheKnee() {
        double ng = 100.0;
        double below = ContactBudget.softCompress(2.0 * ng - 1e-6, ng, 2.0, 1.0);
        double above = ContactBudget.softCompress(2.0 * ng + 1e-6, ng, 2.0, 1.0);
        assertTrue(above > below);
        // 跨越阈值 2e-6 的载荷差，乘上 ng = 100 N 后是 2e-4 的量级
        assertTrue(above - below < 1e-3);
        assertTrue(ContactBudget.softCompress(1000.0 * ng, ng, 2.0, 1.0)
                > ContactBudget.softCompress(100.0 * ng, ng, 2.0, 1.0));
    }

    @Test
    void zeroGravityLoadDisablesCompressionInsteadOfDividing() {
        assertEquals(50.0, ContactBudget.softCompress(50.0, 0.0, 2.0, 1.0), 1e-9);
    }

    @Test
    void adhesionImpulseIsMuTimesLoadTimesStep() {
        assertEquals(6.867, ContactBudget.adhesionImpulse(1.0, MASS * GRAVITY, DT), 1e-9);
        assertEquals(0.6867, ContactBudget.adhesionImpulse(0.1, MASS * GRAVITY, DT), 1e-9);
    }

    @Test
    void executionIsClampedByBothCapabilityAndAdhesion() {
        // 两个上限都不触发的正常区间：6.867 N·s 一步对应 686.7 N，够 600 N
        assertEquals(600.0, ContactBudget.executeForce(600.0, 700.0, 6.867, DT), 1e-9);
        // 能力上限 700 N 先到：7 N·s 小于 7.5 N·s 的附着额度
        assertEquals(700.0, ContactBudget.executeForce(800.0, 700.0, 7.5, DT), 1e-9);
        // 附着上限 686.7 N 先到：6.867 N·s 小于 10 N·s 的能力额度
        assertEquals(686.7, ContactBudget.executeForce(800.0, 1000.0, 6.867, DT), 1e-6);
        // 低附着甲板：3 N·s 一步只能兑现 300 N
        assertEquals(300.0, ContactBudget.executeForce(600.0, 1000.0, 3.0, DT), 1e-9);
        assertEquals(-300.0, ContactBudget.executeForce(-600.0, 1000.0, 3.0, DT), 1e-9);
    }
}