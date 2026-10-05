package io.github.sweetzonzi.arms_core.common.control.state.preset;

import cn.solarmoon.spark_core.state_machine.graph.*;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import io.github.sweetzonzi.arms_core.common.control.state.graph.MechaStateActions;

import java.util.List;
import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.KCC_JUMP_BOOSTING;
import static io.github.sweetzonzi.arms_core.common.control.state.graph.SimpleVariableCondition.*;

/**
 * vertical（垂直）子状态机图常量。
 *
 * <pre>
 * 各 posture 的 vertical 支持范围：
 *   stand:  ground（离地后 posture 切 air，stand_vert 自然停用）
 *   air:    fall / jump_boost / glide / hover / fly
 *   water:  无（水中不受重力约束）
 *   crouch: 无（跳跃被禁用，永远停在 ground）
 *   prone:  无（跳跃被禁用，永远停在 ground）
 * </pre>
 *
 * <p>
 * stand 的 vertical 只含 ground：起跳瞬时化后，地面不存在助推窗口状态。助推窗口只在 air 图里以
 * {@code jump_boost} 表达——冲量在 stand 姿态下即时施放、离地后 posture 切 air，子机先落在
 * {@code fall}，再由 {@code KCC_JUMP_BOOSTING} 迁移进入 {@code jump_boost}。
 *
 * <p>
 * 上升/下降的区分移交给表现层：air posture + fall vertical 统一表示空中无推力阶段，
 * 模组作者通过 {@code vertical_speed} 变量自行分支选择动画。
 *
 * @author Sweetzonzi
 */
public final class VerticalSubGraphs {

    private VerticalSubGraphs() {}

    // ═══════════════════════════════════════════════
    // 条件
    // ═══════════════════════════════════════════════

    private static final StateCondition COND_KCC_BOOSTING = isTrue(KCC_JUMP_BOOSTING);
    private static final StateCondition COND_KCC_NOT_BOOSTING = isFalse(KCC_JUMP_BOOSTING);

    // ═══════════════════════════════════════════════
    // 空常量复用
    // ═══════════════════════════════════════════════

    private static final List<StateTransition> NO_TRANSITIONS = List.of();
    private static final List<StateAction> NO_ACTIONS = List.of();
    private static final Map<String, StateMachineGraph> NO_SUBGRAPHS = Map.of();

    // ═══════════════════════════════════════════════
    // stand 的 vertical 图（仅 ground）
    // ═══════════════════════════════════════════════

    /** stand posture 的 vertical 子机：ground */
    public static final StateMachineGraph STAND = buildStandVert();

    private static StateMachineGraph buildStandVert() {
        // ground: 着地中；起跳冲量由 KCC 在 stand 姿态下即时施放，离地后 posture 切 air
        StateNode groundNode = new StateNode(
                Vertical.GROUND.molangName(),
                NO_TRANSITIONS,
                List.of(MechaStateActions.verticalFlags(Vertical.GROUND),
                        MechaStateActions.enableVerticalInput()),
                NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(groundNode, List.of());
    }

    // ═══════════════════════════════════════════════
    // air 的 vertical 图（fall / jump_boost / glide / hover / fly）
    // ═══════════════════════════════════════════════

    /** air posture 的 vertical 子机：fall / jump_boost / glide / hover / fly */
    public static final StateMachineGraph AIR = buildAirVert();

    private static StateMachineGraph buildAirVert() {
        var setFall      = MechaStateActions.verticalFlags(Vertical.FALL);
        var setJumpBoost = MechaStateActions.verticalFlags(Vertical.JUMP_BOOST);
        var setGlide     = MechaStateActions.verticalFlags(Vertical.GLIDE);
        var setHover     = MechaStateActions.verticalFlags(Vertical.HOVER);
        var setFly       = MechaStateActions.verticalFlags(Vertical.FLY);

        // fall: 默认空中状态——无动力摔落（上升段 + 下落段统一）；助推窗口活跃时进入 jump_boost
        StateNode fallNode = new StateNode(
                Vertical.FALL.molangName(),
                List.of(
                        new StateTransition(null, Vertical.JUMP_BOOST.molangName(), COND_KCC_BOOSTING),
                        new StateTransition("glide_activate", Vertical.GLIDE.molangName(),
                                StateCondition.True.INSTANCE),
                        new StateTransition("hover", Vertical.HOVER.molangName(),
                                StateCondition.True.INSTANCE),
                        new StateTransition("fly", Vertical.FLY.molangName(),
                                StateCondition.True.INSTANCE)
                ),
                List.of(setFall, MechaStateActions.enableVerticalMoveOnly()),
                NO_ACTIONS, NO_SUBGRAPHS);

        // jump_boost: 由 KCC 助推窗口状态驱动；窗口关闭即回 fall（保留空中水平控制）
        StateNode jumpBoostNode = new StateNode(
                Vertical.JUMP_BOOST.molangName(),
                List.of(
                        new StateTransition(null, Vertical.FALL.molangName(), COND_KCC_NOT_BOOSTING)
                ),
                List.of(setJumpBoost, MechaStateActions.enableVerticalMoveOnly()),
                NO_ACTIONS, NO_SUBGRAPHS);

        // glide: 鞘翅滑翔
        StateNode glideNode = new StateNode(
                Vertical.GLIDE.molangName(),
                List.of(
                        new StateTransition("glide_deactivate", Vertical.FALL.molangName(),
                                StateCondition.True.INSTANCE),
                        new StateTransition("hover", Vertical.HOVER.molangName(),
                                StateCondition.True.INSTANCE),
                        new StateTransition("fly", Vertical.FLY.molangName(),
                                StateCondition.True.INSTANCE)
                ),
                List.of(setGlide, MechaStateActions.enableVerticalMoveOnly()),
                NO_ACTIONS, NO_SUBGRAPHS);

        // hover: 推进悬浮
        StateNode hoverNode = new StateNode(
                Vertical.HOVER.molangName(),
                List.of(
                        new StateTransition("hover", Vertical.FALL.molangName(),
                                StateCondition.True.INSTANCE),
                        new StateTransition("fly", Vertical.FLY.molangName(),
                                StateCondition.True.INSTANCE)
                ),
                List.of(setHover, MechaStateActions.enableVerticalMoveOnly()),
                NO_ACTIONS, NO_SUBGRAPHS);

        // fly: 创造飞行
        StateNode flyNode = new StateNode(
                Vertical.FLY.molangName(),
                List.of(
                        new StateTransition("fly", Vertical.FALL.molangName(),
                                StateCondition.True.INSTANCE)
                ),
                List.of(setFly, MechaStateActions.enableVerticalMoveOnly()),
                NO_ACTIONS, NO_SUBGRAPHS);

        // 默认初始态为 fall
        return new StateMachineGraph(fallNode, List.of(jumpBoostNode, glideNode, hoverNode, flyNode));
    }
}