package io.github.sweetzonzi.arms_core.common.control.state;

import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.ENERGY;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.GAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GaitSubGraphsTest extends LogicStateMachineTestSupport {

    @Test
    void idleDoesNotOscillateAtRest() {
        setMovement(false, false, false, 0f);

        progress(20);

        assertEquals(Gait.IDLE, variables.get(GAIT));
    }

    @ParameterizedTest
    @CsvSource({
            "0.05, IDLE",
            "0.15, IDLE",
            "0.151, DRIFT"
    })
    void idleUsesHigherDriftEntryThreshold(float speed, Gait expected) {
        setMovement(false, false, false, speed);

        machine.progress(TEST_DT);

        assertEquals(expected, variables.get(GAIT));
    }

    @Test
    void driftUsesLowerStopThreshold() {
        setMovement(false, false, false, 0.151f);
        machine.progress(TEST_DT);
        assertEquals(Gait.DRIFT, variables.get(GAIT));

        setMovement(false, false, false, 0.051f);
        progress(5);
        assertEquals(Gait.DRIFT, variables.get(GAIT));

        setMovement(false, false, false, 0.05f);
        machine.progress(TEST_DT);
        assertEquals(Gait.IDLE, variables.get(GAIT));
    }

    @ParameterizedTest
    @CsvSource({
            "0.05, IDLE",
            "0.051, DRIFT"
    })
    void jogBranchesToIdleOrDriftWhenInputStops(float speed, Gait expected) {
        setMovement(true, false, false, 1f);
        machine.progress(TEST_DT);
        assertEquals(Gait.JOG, variables.get(GAIT));

        setMovement(false, false, false, speed);
        machine.progress(TEST_DT);

        assertEquals(expected, variables.get(GAIT));
    }

    @Test
    void walkKeySwitchesBetweenJogAndCreep() {
        setMovement(true, false, false, 1f);
        machine.progress(TEST_DT);
        assertEquals(Gait.JOG, variables.get(GAIT));

        setMovement(true, true, false, 1f);
        machine.progress(TEST_DT);
        assertEquals(Gait.CREEP, variables.get(GAIT));

        setMovement(true, false, false, 1f);
        machine.progress(TEST_DT);
        assertEquals(Gait.JOG, variables.get(GAIT));
    }

    @Test
    void sprintRequiresInputSprintKeyAndEnergy() {
        enterSprint();

        assertEquals(Gait.SPRINT, variables.get(GAIT));
    }

    @Test
    void sprintSwitchesDirectlyToCreepWhenWalkKeyIsPressed() {
        enterSprint();

        setMovement(true, true, true, 1f);
        machine.progress(TEST_DT);

        assertEquals(Gait.CREEP, variables.get(GAIT));
    }

    @ParameterizedTest
    @CsvSource({
            "0.05, IDLE",
            "0.051, DRIFT"
    })
    void sprintBranchesByResidualSpeedWhenInputStops(float speed, Gait expected) {
        enterSprint();

        setMovement(false, false, false, speed);
        machine.progress(TEST_DT);

        assertEquals(expected, variables.get(GAIT));
    }

    @Test
    void sprintExitsWhenEnergyIsDepleted() {
        enterSprint();

        variables.set(ENERGY, 0f);
        machine.progress(TEST_DT);

        assertEquals(Gait.JOG, variables.get(GAIT));
    }

    @Test
    void zeroEnergyPreventsSprintEntry() {
        variables.set(ENERGY, 0f);
        setMovement(true, false, true, 1f);

        progress(3);

        assertEquals(Gait.JOG, variables.get(GAIT));
    }
}
