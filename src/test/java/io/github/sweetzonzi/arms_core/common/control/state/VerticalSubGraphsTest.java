package io.github.sweetzonzi.arms_core.common.control.state;

import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import org.junit.jupiter.api.Test;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.KCC_JUMP_BOOSTING;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.VERTICAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VerticalSubGraphsTest extends LogicStateMachineTestSupport {

    @Test
    void groundRemainsStableWhileKccIsNotBoosting() {
        variables.set(KCC_JUMP_BOOSTING, false);

        progress(10);

        assertEquals(Vertical.GROUND, variables.get(VERTICAL));
        assertSourcePermissions(true, true, true, true);
        assertFinalPermissions(true, true);
    }

    /**
     * air 图的 {@code fall ⇄ jump_boost} 迁移：助推窗口由 KCC 状态镜像，窗口关闭即回 {@code fall}。
     * <p>
     * {@code jump_boost} 只存在于 air 图。冲量在 stand 姿态下即时施放、离地后 posture 才切 air，
     * 因此子机先落在 {@code fall}，再由 {@code KCC_JUMP_BOOSTING} 迁移进入 {@code jump_boost}。
     */
    @Test
    void jumpBoostMirrorsKccBoostingStateInAir() {
        enterPosture(Posture.AIR);
        assertState(Posture.AIR, Gait.IDLE, Vertical.FALL);

        variables.set(KCC_JUMP_BOOSTING, true);
        machine.progress(TEST_DT);

        assertEquals(Vertical.JUMP_BOOST, variables.get(VERTICAL));
        assertSourcePermissions(true, true, true, false);
        assertFinalPermissions(true, false);

        progress(5);
        assertEquals(Vertical.JUMP_BOOST, variables.get(VERTICAL));

        variables.set(KCC_JUMP_BOOSTING, false);
        machine.progress(TEST_DT);

        assertEquals(Vertical.FALL, variables.get(VERTICAL));
        assertSourcePermissions(true, true, true, false);
        assertFinalPermissions(true, false);
    }

    @Test
    void airVerticalAllowsMovementButNotNewJump() {
        enterPosture(Posture.AIR);

        assertState(Posture.AIR, Gait.IDLE, Vertical.FALL);
        assertSourcePermissions(true, true, true, false);
        assertFinalPermissions(true, false);
    }

    @Test
    void flyEventOnlyAffectsActiveAirVerticalMachine() {
        machine.broadcastEvent("fly");
        assertEquals(Vertical.GROUND, variables.get(VERTICAL));

        enterPosture(Posture.AIR);
        machine.broadcastEvent("fly");
        assertEquals(Vertical.FLY, variables.get(VERTICAL));

        machine.broadcastEvent("fly");
        assertEquals(Vertical.FALL, variables.get(VERTICAL));
    }
}