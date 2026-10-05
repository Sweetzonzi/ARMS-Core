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
        // jump_boost 只在 air 图存在：先离地进入 air，再置助推标志
        setMovement(true, false, false, 1f);
        setEnvironment(false, false, false);
        variables.set(KCC_JUMP_BOOSTING, true);

        machine.progress(TEST_DT);   // STAND → AIR，air 子机启动
        machine.progress(TEST_DT);   // fall → jump_boost

        assertState(Posture.AIR, Gait.JOG, Vertical.JUMP_BOOST);
        assertSourcePermissions(true, true, true, false);
        assertFinalPermissions(true, false);
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
        machine.progress(TEST_DT);

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
        machine.progress(TEST_DT);
        assertOneHotConsistency();

        setEnvironment(false, false, false);
        machine.progress(TEST_DT);
        assertOneHotConsistency();

        setEnvironment(false, true, false);
        machine.progress(TEST_DT);
        assertOneHotConsistency();

        variables.set(StateVariableKeys.IS_DEAD, true);
        machine.progress(TEST_DT);
        assertOneHotConsistency();
    }

    @Test
    void separateMachineInstancesDoNotShareVariables() {
        StateVariableContainer otherVariables = new StateVariableContainer();
        otherVariables.set(StateVariableKeys.ON_GROUND, true);
        MechaLogicStateMachine other = new MechaLogicStateMachine(
                otherVariables, new GameplayTagContainer());
        other.reset();

        variables.set(IS_SNEAKING, true);
        machine.progress(TEST_DT);

        assertEquals(Posture.CROUCH, variables.get(POSTURE));
        assertEquals(Posture.STAND, otherVariables.get(POSTURE));
    }
}
