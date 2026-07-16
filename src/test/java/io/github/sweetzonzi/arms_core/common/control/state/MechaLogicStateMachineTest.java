package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import org.junit.jupiter.api.Test;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MechaLogicStateMachineTest extends LogicStateMachineTestSupport {

    @Test
    void parallelChildrenKeepIndependentPermissionSources() {
        setMovement(true, false, false, 1f);
        variables.set(KCC_JUMP_CHARGING, true);

        machine.progress();

        assertState(Posture.STAND, Gait.JOG, Vertical.JUMP_CHARGE);
        assertSourcePermissions(true, true, false, false);
        assertFinalPermissions(false, false);
    }

    @Test
    void dodgeBroadcastChangesGaitButNotVertical() {
        machine.broadcastEvent("dodge");

        assertState(Posture.STAND, Gait.DODGE, Vertical.GROUND);
        assertSourcePermissions(true, true, true, true);
    }

    @Test
    void unknownBroadcastDoesNotChangeState() {
        machine.broadcastEvent("unknown");
        machine.progress();

        assertState(Posture.STAND, Gait.IDLE, Vertical.GROUND);
        assertFinalPermissions(true, true);
    }

    @Test
    void resetRefreshesPermissionsAfterRagdoll() {
        enterPosture(Posture.RAGDOLL);
        assertFinalPermissions(false, false);

        setEnvironment(true, false, false);
        machine.reset();

        assertState(Posture.STAND, Gait.IDLE, Vertical.GROUND);
        assertFinalPermissions(true, true);
    }

    @Test
    void stopThenStartRefreshesPermissions() {
        enterPosture(Posture.RAGDOLL);
        assertFinalPermissions(false, false);

        setEnvironment(true, false, false);
        machine.stop();
        machine.start();

        assertState(Posture.STAND, Gait.IDLE, Vertical.GROUND);
        assertFinalPermissions(true, true);
    }

    @Test
    void stateEnumsAndOneHotFlagsStayConsistentAcrossTransitions() {
        assertOneHotConsistency();

        setMovement(true, false, false, 1f);
        machine.progress();
        assertOneHotConsistency();

        setEnvironment(false, false, false);
        machine.progress();
        assertOneHotConsistency();

        setEnvironment(false, true, false);
        machine.progress();
        assertOneHotConsistency();

        variables.set(StateVariableKeys.IS_DEAD, true);
        machine.progress();
        assertOneHotConsistency();
    }

    @Test
    void separateMachineInstancesDoNotShareVariables() {
        StateVariableContainer otherVariables = new StateVariableContainer();
        otherVariables.set(StateVariableKeys.ON_GROUND, true);
        MechaLogicStateMachine other = new MechaLogicStateMachine(
                otherVariables, new GameplayTagContainer());
        other.reset();

        machine.broadcastEvent("sneak");
        machine.progress();

        assertEquals(Posture.CROUCH, variables.get(POSTURE));
        assertEquals(Posture.STAND, otherVariables.get(POSTURE));
    }
}
