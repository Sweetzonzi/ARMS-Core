package io.github.sweetzonzi.arms_core.common.control.state;

import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import org.junit.jupiter.api.Test;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.KCC_JUMP_CHARGING;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.VERTICAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VerticalSubGraphsTest extends LogicStateMachineTestSupport {

    @Test
    void groundRemainsStableWhileKccIsNotCharging() {
        variables.set(KCC_JUMP_CHARGING, false);

        progress(10);

        assertEquals(Vertical.GROUND, variables.get(VERTICAL));
        assertSourcePermissions(true, true, true, true);
        assertFinalPermissions(true, true);
    }

    @Test
    void jumpChargeMirrorsKccChargingState() {
        variables.set(KCC_JUMP_CHARGING, true);
        machine.progress(TEST_DT);

        assertEquals(Vertical.JUMP_CHARGE, variables.get(VERTICAL));
        assertSourcePermissions(true, true, false, false);
        assertFinalPermissions(false, false);

        progress(5);
        assertEquals(Vertical.JUMP_CHARGE, variables.get(VERTICAL));

        variables.set(KCC_JUMP_CHARGING, false);
        machine.progress(TEST_DT);

        assertEquals(Vertical.GROUND, variables.get(VERTICAL));
        assertFinalPermissions(true, true);
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

    @Test
    void postureWithoutVerticalMachineIgnoresStaleVerticalDenial() {
        variables.set(KCC_JUMP_CHARGING, true);
        machine.progress(TEST_DT);
        assertFinalPermissions(false, false);

        setEnvironment(true, true, false);
        machine.progress(TEST_DT);

        assertEquals(Posture.WATER, variables.get(io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.POSTURE));
        assertSourcePermissions(true, true, false, false);
        assertFinalPermissions(true, false);
    }
}
