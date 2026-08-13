package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.IN_WATER;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.POSTURE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PostureLogicGraphsTest extends LogicStateMachineTestSupport {

    @Test
    void initialStateRemainsStable() {
        progress(10);

        assertState(Posture.STAND, Gait.IDLE, Vertical.GROUND);
        assertInitialPermissions();
        assertOneHotConsistency();
    }

    @ParameterizedTest
    @EnumSource(value = Posture.class, names = "RAGDOLL", mode = EnumSource.Mode.EXCLUDE)
    void deathHasPriorityFromEveryNonRagdollPosture(Posture source) {
        enterPosture(source);

        variables.set(StateVariableKeys.IS_DEAD, true);
        variables.set(StateVariableKeys.ON_GROUND, false);
        variables.set(IN_WATER, true);
        machine.progress(TEST_DT);

        assertEquals(Posture.RAGDOLL, variables.get(POSTURE));
        assertFinalPermissions(false, false);
        assertOneHotConsistency();
    }

    @Test
    void shallowWaterLandingGoesDirectlyFromAirToWater() {
        enterPosture(Posture.AIR);

        setEnvironment(true, true, false);
        machine.progress(TEST_DT);

        assertEquals(Posture.WATER, variables.get(POSTURE));
        assertFinalPermissions(true, false);
    }

    @ParameterizedTest
    @EnumSource(value = Posture.class, names = {"CROUCH", "PRONE"})
    void groundPosturesEnterAirWhenFalling(Posture source) {
        enterPosture(source);

        setEnvironment(false, false, false);
        machine.progress(TEST_DT);

        assertEquals(Posture.AIR, variables.get(POSTURE));
        assertFinalPermissions(true, false);
    }

    @ParameterizedTest
    @EnumSource(value = Posture.class, names = {"CROUCH", "PRONE"})
    void groundPosturesEnterWaterWhenSubmerged(Posture source) {
        enterPosture(source);

        setEnvironment(true, true, false);
        machine.progress(TEST_DT);

        assertEquals(Posture.WATER, variables.get(POSTURE));
        assertFinalPermissions(true, false);
    }

    @Test
    void leavingWaterBranchesByGroundState() {
        enterPosture(Posture.WATER);

        setEnvironment(true, false, false);
        machine.progress(TEST_DT);
        assertEquals(Posture.STAND, variables.get(POSTURE));

        setEnvironment(true, true, false);
        machine.progress(TEST_DT);
        assertEquals(Posture.WATER, variables.get(POSTURE));

        setEnvironment(false, false, false);
        machine.progress(TEST_DT);
        assertEquals(Posture.AIR, variables.get(POSTURE));
    }

    @Test
    void mountAndDismountUpdatePostureAndPermissions() {
        machine.broadcastEvent("mount");
        machine.progress(TEST_DT);

        assertEquals(Posture.RIDING, variables.get(POSTURE));
        assertFinalPermissions(false, false);

        machine.broadcastEvent("dismount");
        machine.progress(TEST_DT);

        assertEquals(Posture.STAND, variables.get(POSTURE));
        assertFinalPermissions(true, true);
    }

    @Test
    void ragdollIsTerminalUntilReset() {
        enterPosture(Posture.RAGDOLL);

        setEnvironment(true, false, false);
        machine.broadcastEvent("mount");
        progress(3);

        assertEquals(Posture.RAGDOLL, variables.get(POSTURE));
        assertFinalPermissions(false, false);
    }
}
