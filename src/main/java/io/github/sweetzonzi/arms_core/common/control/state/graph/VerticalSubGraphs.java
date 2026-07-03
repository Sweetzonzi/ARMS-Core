package io.github.sweetzonzi.arms_core.common.control.state.graph;

import cn.solarmoon.spark_core.state_machine.graph.*;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import io.github.sweetzonzi.arms_core.common.control.state.MechaStateActions;
import io.github.sweetzonzi.arms_core.common.control.state.SimpleVariableCondition;

import java.util.List;
import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;
import static io.github.sweetzonzi.arms_core.common.control.state.SimpleVariableCondition.*;

/**
 * vertical（垂直）子状态机图常量。
 *
 * <pre>
 * 各 posture 的 vertical 支持范围：
 *   stand: ground / jump_charge / jump / fall / fly
 *   air:   jump / fall / fly（不包含 ground——空中起跳后自然进入 jump）
 *   water: 无（水中不受重力约束，不需要 vertical 区分）
 *   crouch: 无（跳跃被禁用，永远停在 ground）
 *   prone:  无（跳跃被禁用，永远停在 ground）
 * </pre>
 *
 * <p>
 * 每个 vertical 图进入活跃状态时写入 {@code ctrl.vertical} 变量；
 * 表现层通过读取该变量决定动画切换。
 *
 * @author Sweetzonzi
 */
public final class VerticalSubGraphs {

    private VerticalSubGraphs() {}

    // ═══════════════════════════════════════════════
    // 条件
    // ═══════════════════════════════════════════════

    private static final StateCondition COND_ON_GROUND = isTrue(StateVariableKeys.ON_GROUND);
    private static final StateCondition COND_NOT_ON_GROUND = isFalse(StateVariableKeys.ON_GROUND);

    /** 是否正在上升（vertical_speed > 0.1 m/s） */
    private static final StateCondition COND_ASCENDING =
            SimpleVariableCondition.floatGt(StateVariableKeys.VERTICAL_SPEED, 0.1f);

    /** 是否正在下降（vertical_speed < -0.1 m/s） */
    private static final StateCondition COND_DESCENDING =
            new StateCondition.Reverse(COND_ASCENDING);

    // ═══════════════════════════════════════════════
    // 空常量复用
    // ═══════════════════════════════════════════════

    private static final List<StateAction> NO_ACTIONS = List.of();
    private static final Map<String, StateMachineGraph> NO_SUBGRAPHS = Map.of();

    // ═══════════════════════════════════════════════
    // stand 的 vertical 图
    // ═══════════════════════════════════════════════

    /** stand posture 的 vertical 子机：ground / jump_charge / jump / fall / fly */
    public static final StateMachineGraph STAND = buildStandVert();

    private static StateMachineGraph buildStandVert() {
        var setGround = MechaStateActions.verticalFlags("ground");
        var setJumpCharge = MechaStateActions.verticalFlags("jump_charge");
        var setJump = MechaStateActions.verticalFlags("jump");
        var setFall = MechaStateActions.verticalFlags("fall");
        var setFly = MechaStateActions.verticalFlags("fly");

        // ground: 着地中
        StateNode groundNode = new StateNode("ground",
                List.of(
                        new StateTransition("jump_start", "jump_charge", StateCondition.True.INSTANCE),
                        new StateTransition("fly", "fly", StateCondition.True.INSTANCE)
                ),
                List.of(setGround), NO_ACTIONS, NO_SUBGRAPHS);

        // jump_charge: 跳跃蓄力中（跳跃键按住）
        StateNode jumpChargeNode = new StateNode("jump_charge",
                List.of(
                        new StateTransition("jump_release", "jump", StateCondition.True.INSTANCE),
                        new StateTransition(null, "ground", COND_ON_GROUND)  // 离地前松开会回到 ground
                ),
                List.of(setJumpCharge), NO_ACTIONS, NO_SUBGRAPHS);

        // jump: 上升段
        StateNode jumpNode = new StateNode("jump",
                List.of(
                        new StateTransition(null, "fall", COND_DESCENDING),   // auto: 顶点后 → fall
                        new StateTransition(null, "ground", COND_ON_GROUND),  // auto: 着地 → ground
                        new StateTransition("fly", "fly", StateCondition.True.INSTANCE)
                ),
                List.of(setJump), NO_ACTIONS, NO_SUBGRAPHS);

        // fall: 下落段
        StateNode fallNode = new StateNode("fall",
                List.of(
                        new StateTransition(null, "ground", COND_ON_GROUND),  // auto: 着地 → ground
                        new StateTransition("fly", "fly", StateCondition.True.INSTANCE)
                ),
                List.of(setFall), NO_ACTIONS, NO_SUBGRAPHS);

        // fly: 飞行模式
        StateNode flyNode = new StateNode("fly",
                List.of(
                        new StateTransition("fly", "fall", StateCondition.True.INSTANCE),
                        new StateTransition(null, "ground", COND_ON_GROUND)
                ),
                List.of(setFly), NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(groundNode,
                List.of(jumpChargeNode, jumpNode, fallNode, flyNode));
    }

    // ═══════════════════════════════════════════════
    // air 的 vertical 图（无 ground 入口）
    // ═══════════════════════════════════════════════

    /** air posture 的 vertical 子机：jump / fall / fly（无 ground） */
    public static final StateMachineGraph AIR = buildAirVert();

    private static StateMachineGraph buildAirVert() {
        var setJump = MechaStateActions.verticalFlags("jump");
        var setFall = MechaStateActions.verticalFlags("fall");
        var setFly = MechaStateActions.verticalFlags("fly");

        // 进入 air posture 时默认 fall（空中状态）
        StateNode jumpNode = new StateNode("jump",
                List.of(
                        new StateTransition(null, "fall", COND_DESCENDING),
                        new StateTransition("fly", "fly", StateCondition.True.INSTANCE)
                ),
                List.of(setJump), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode fallNode = new StateNode("fall",
                List.of(
                        new StateTransition(null, "jump", COND_ASCENDING),
                        new StateTransition("fly", "fly", StateCondition.True.INSTANCE)
                ),
                List.of(setFall), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode flyNode = new StateNode("fly",
                List.of(
                        new StateTransition("fly", "fall", StateCondition.True.INSTANCE)
                ),
                List.of(setFly), NO_ACTIONS, NO_SUBGRAPHS);

        // fall 为初始态（进入 air posture 时默认判定为下落）
        return new StateMachineGraph(fallNode, List.of(jumpNode, flyNode));
    }
}
