package io.github.sweetzonzi.arms_core.common.control.state;

import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.ENERGY;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.GAIT;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.GAIT_CAN_JUMP;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.GAIT_CAN_MOVE;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.MOVE_SPEED_MODIFIER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ═══════════════════════════════════════════════
    // 时长型状态（item 5：dodge / stun 驻留时长退出）
    // ═══════════════════════════════════════════════

    @Test
    void dodgeLastsForDurationThenExitsBySpeed() {
        setMovement(true, false, false, 1f);
        machine.progress(TEST_DT);
        assertEquals(Gait.JOG, variables.get(GAIT));

        machine.broadcastEvent("dodge");
        assertEquals(Gait.DODGE, variables.get(GAIT));

        // 时长内保持 dodge：0.4s @ 20tps，7 帧后 stateTime=0.35 < 0.4
        setMovement(false, false, false, 0f);
        progress(7);
        assertEquals(Gait.DODGE, variables.get(GAIT));

        // 第 8 帧 stateTime=0.4 → 无输入且速度归零 → idle
        machine.progress(TEST_DT);
        assertEquals(Gait.IDLE, variables.get(GAIT));
    }

    @Test
    void dodgeExitsToDriftWhenResidualSpeedRemains() {
        setMovement(true, false, false, 1f);
        machine.progress(TEST_DT);
        machine.broadcastEvent("dodge");

        // 时长结束后无输入但有残余速度 → drift
        setMovement(false, false, false, 1f);
        progress(8);

        assertEquals(Gait.DRIFT, variables.get(GAIT));
    }

    @Test
    void stunEnteredByEventBlocksInputAndLastsForDuration() {
        setMovement(true, false, false, 1f);
        machine.progress(TEST_DT);
        assertEquals(Gait.JOG, variables.get(GAIT));

        machine.broadcastEvent("stun");
        assertEquals(Gait.STUN, variables.get(GAIT));
        assertFalse(variables.get(GAIT_CAN_MOVE));
        assertFalse(variables.get(GAIT_CAN_JUMP));

        // 时长内保持硬直：0.8s @ 20tps，15 帧后 stateTime=0.75 < 0.8
        setMovement(false, false, false, 0f);
        progress(15);
        assertEquals(Gait.STUN, variables.get(GAIT));

        // 第 16 帧 stateTime=0.8 → 解除 → idle
        machine.progress(TEST_DT);
        assertEquals(Gait.IDLE, variables.get(GAIT));
    }

    @Test
    void stunCanBeEnteredFromSprint() {
        enterSprint();

        machine.broadcastEvent("stun");

        assertEquals(Gait.STUN, variables.get(GAIT));
        assertFalse(variables.get(GAIT_CAN_MOVE));
    }

    // ═══════════════════════════════════════════════
    // dodge 不写倍率：闪避不剥夺自主移动能力
    // ═══════════════════════════════════════════════

    /**
     * 进入 dodge 不改变 {@code MOVE_SPEED_MODIFIER}。
     * <p>
     * 倍率缩放的是标准 WASD 控制力，而闪避是一次速度矢量赋值（{@code MechaCharacter.requestDodgeImpulse}），
     * 两者量纲不同、互不干涉。因此冲刺中闪避必须保留 1.6 而不是掉到 0：掉到 0 会让闪避期间无法用
     * 方向键移动，也会让闪避窗口内的稳态速率塌回常速。
     * <p>
     * 对照组是同一个图里的 stun：它写 0 且 {@code disableGaitInput()}，才是真正夺取控制权的状态。
     */
    @Test
    void dodgePreservesTheSpeedModifierAndKeepsMoving() {
        enterSprint();
        float sprintModifier = variables.get(MOVE_SPEED_MODIFIER);
        assertEquals(Gait.SPRINT, variables.get(GAIT));
        assertTrue(sprintModifier > 1f, "先决条件：冲刺倍率应大于常速，实际 " + sprintModifier);

        machine.broadcastEvent("dodge");

        assertEquals(Gait.DODGE, variables.get(GAIT));
        assertEquals(sprintModifier, variables.get(MOVE_SPEED_MODIFIER), 1.0e-6f,
                "dodge 必须保留进入前的倍率");
        assertTrue(variables.get(GAIT_CAN_MOVE), "dodge 期间仍应允许自主移动");
        assertTrue(variables.get(GAIT_CAN_JUMP), "dodge 不禁用跳跃");
    }
}
