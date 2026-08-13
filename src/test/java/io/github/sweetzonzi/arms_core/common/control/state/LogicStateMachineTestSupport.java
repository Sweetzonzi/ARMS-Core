package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableKey;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import org.junit.jupiter.api.BeforeEach;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class LogicStateMachineTestSupport {

    /** 测试固定步长：20 TPS 一帧 (s) */
    protected static final float TEST_DT = 0.05f;

    protected StateVariableContainer variables;
    protected GameplayTagContainer tags;
    protected MechaLogicStateMachine machine;

    @BeforeEach
    void setUpStateMachine() {
        variables = new StateVariableContainer();
        tags = new GameplayTagContainer();

        variables.set(StateVariableKeys.ON_GROUND, true);
        variables.set(StateVariableKeys.SPEED, 0f);
        variables.set(StateVariableKeys.IS_SPRINTING, false);
        variables.set(StateVariableKeys.IS_DEAD, false);
        variables.set(HAS_INPUT, false);
        variables.set(WALK_KEY_DOWN, false);
        variables.set(IN_WATER, false);
        variables.set(KCC_JUMP_CHARGING, false);
        variables.set(ENERGY, 100f);

        machine = new MechaLogicStateMachine(variables, tags);
        machine.reset();
    }

    protected void setEnvironment(boolean onGround, boolean inWater, boolean dead) {
        variables.set(StateVariableKeys.ON_GROUND, onGround);
        variables.set(IN_WATER, inWater);
        variables.set(StateVariableKeys.IS_DEAD, dead);
    }

    protected void setMovement(boolean hasInput, boolean walkKey, boolean sprint, float speed) {
        variables.set(HAS_INPUT, hasInput);
        variables.set(WALK_KEY_DOWN, walkKey);
        variables.set(StateVariableKeys.IS_SPRINTING, sprint);
        variables.set(StateVariableKeys.SPEED, speed);
    }

    protected void progress(int frames) {
        for (int i = 0; i < frames; i++) {
            machine.progress(TEST_DT);
        }
    }

    protected void enterPosture(Posture posture) {
        switch (posture) {
            case STAND -> {
                setEnvironment(true, false, false);
                machine.reset();
            }
            case AIR -> {
                setEnvironment(false, false, false);
                machine.progress(TEST_DT);
            }
            case WATER -> {
                setEnvironment(true, true, false);
                machine.progress(TEST_DT);
            }
            case CROUCH -> {
                machine.broadcastEvent("sneak");
                machine.progress(TEST_DT);
            }
            case PRONE -> {
                machine.broadcastEvent("sneak");
                machine.broadcastEvent("prone");
                machine.progress(TEST_DT);
            }
            case RIDING -> {
                machine.broadcastEvent("mount");
                machine.progress(TEST_DT);
            }
            case RAGDOLL -> {
                variables.set(StateVariableKeys.IS_DEAD, true);
                machine.progress(TEST_DT);
            }
        }
        assertEquals(posture, variables.get(POSTURE));
    }

    protected void enterSprint() {
        variables.set(ENERGY, 100f);
        setMovement(true, false, true, 1f);
        machine.progress(TEST_DT);
        machine.progress(TEST_DT);
        assertEquals(Gait.SPRINT, variables.get(GAIT));
    }

    protected void assertState(Posture posture, Gait gait, Vertical vertical) {
        assertEquals(posture, variables.get(POSTURE));
        assertEquals(gait, variables.get(GAIT));
        assertEquals(vertical, variables.get(VERTICAL));
    }

    protected void assertSourcePermissions(
            boolean gaitMove, boolean gaitJump, boolean verticalMove, boolean verticalJump) {
        assertEquals(gaitMove, variables.get(GAIT_CAN_MOVE));
        assertEquals(gaitJump, variables.get(GAIT_CAN_JUMP));
        assertEquals(verticalMove, variables.get(VERTICAL_CAN_MOVE));
        assertEquals(verticalJump, variables.get(VERTICAL_CAN_JUMP));
    }

    protected void assertFinalPermissions(boolean canMove, boolean canJump) {
        assertEquals(canMove, machine.canMove());
        assertEquals(canJump, machine.canJump());
        assertEquals(canMove, variables.get(CAN_MOVE));
        assertEquals(canJump, variables.get(CAN_JUMP));
    }

    protected void assertOneHotConsistency() {
        assertEquals(1, countTrue(
                IS_STANDING, IS_AIRBORNE, IN_WATER_POSTURE, IS_CROUCHING,
                IS_PRONE, IS_RIDING, IS_RAGDOLLED));
        assertEquals(1, countTrue(
                IS_IDLE, IS_CREEPING, IS_JOGGING, IS_SPRINTING_G,
                IS_DRIFTING, IS_DODGING, IS_STUNNED, IS_HARD_LANDING));
        assertEquals(1, countTrue(
                IS_GROUNDED, IS_JUMP_CHARGING, IS_FALLING,
                IS_GLIDING, IS_HOVERING, IS_FLYING));

        Posture posture = variables.get(POSTURE);
        assertEquals(posture == Posture.STAND, variables.get(IS_STANDING));
        assertEquals(posture == Posture.AIR, variables.get(IS_AIRBORNE));
        assertEquals(posture == Posture.WATER, variables.get(IN_WATER_POSTURE));
        assertEquals(posture == Posture.CROUCH, variables.get(IS_CROUCHING));
        assertEquals(posture == Posture.PRONE, variables.get(IS_PRONE));
        assertEquals(posture == Posture.RIDING, variables.get(IS_RIDING));
        assertEquals(posture == Posture.RAGDOLL, variables.get(IS_RAGDOLLED));

        Gait gait = variables.get(GAIT);
        assertEquals(gait == Gait.IDLE, variables.get(IS_IDLE));
        assertEquals(gait == Gait.CREEP, variables.get(IS_CREEPING));
        assertEquals(gait == Gait.JOG, variables.get(IS_JOGGING));
        assertEquals(gait == Gait.SPRINT, variables.get(IS_SPRINTING_G));
        assertEquals(gait == Gait.DRIFT, variables.get(IS_DRIFTING));
        assertEquals(gait == Gait.DODGE, variables.get(IS_DODGING));
        assertEquals(gait == Gait.STUN, variables.get(IS_STUNNED));
        assertEquals(gait == Gait.HARD_LAND, variables.get(IS_HARD_LANDING));

        Vertical vertical = variables.get(VERTICAL);
        assertEquals(vertical == Vertical.GROUND, variables.get(IS_GROUNDED));
        assertEquals(vertical == Vertical.JUMP_CHARGE, variables.get(IS_JUMP_CHARGING));
        assertEquals(vertical == Vertical.FALL, variables.get(IS_FALLING));
        assertEquals(vertical == Vertical.GLIDE, variables.get(IS_GLIDING));
        assertEquals(vertical == Vertical.HOVER, variables.get(IS_HOVERING));
        assertEquals(vertical == Vertical.FLY, variables.get(IS_FLYING));
    }

    @SafeVarargs
    private int countTrue(StateVariableKey<Boolean>... keys) {
        int count = 0;
        for (StateVariableKey<Boolean> key : keys) {
            if (variables.get(key)) count++;
        }
        return count;
    }

    protected void assertInitialPermissions() {
        assertTrue(variables.get(GAIT_CAN_MOVE));
        assertTrue(variables.get(GAIT_CAN_JUMP));
        assertTrue(variables.get(VERTICAL_CAN_MOVE));
        assertTrue(variables.get(VERTICAL_CAN_JUMP));
        assertFalse(variables.get(IS_KNOCKED_DOWN));
        assertFinalPermissions(true, true);
    }
}
