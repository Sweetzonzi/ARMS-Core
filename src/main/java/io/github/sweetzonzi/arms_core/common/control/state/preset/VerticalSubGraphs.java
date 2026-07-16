package io.github.sweetzonzi.arms_core.common.control.state.preset;

import cn.solarmoon.spark_core.state_machine.graph.*;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import io.github.sweetzonzi.arms_core.common.control.state.graph.MechaStateActions;

import java.util.List;
import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.KCC_JUMP_CHARGING;
import static io.github.sweetzonzi.arms_core.common.control.state.graph.SimpleVariableCondition.*;

/**
 * vertical（垂直）子状态机图常量。
 *
 * <pre>
 * 各 posture 的 vertical 支持范围：
 *   stand:  ground / jump_charge（离地后 posture 切 air，stand_vert 自然停用）
 *   air:    fall / glide / hover / fly
 *   water:  无（水中不受重力约束）
 *   crouch: 无（跳跃被禁用，永远停在 ground）
 *   prone:  无（跳跃被禁用，永远停在 ground）
 * </pre>
 *
 * <p>
 * stand 的 vertical 不再包含 jump/fall/fly——这些状态的前提是离地，
 * 而离地后 posture 已从 stand 切到 air，stand_vert 子机不再活跃。
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

    private static final StateCondition COND_KCC_CHARGING = isTrue(KCC_JUMP_CHARGING);
    private static final StateCondition COND_KCC_NOT_CHARGING = isFalse(KCC_JUMP_CHARGING);

    // ═══════════════════════════════════════════════
    // 空常量复用
    // ═══════════════════════════════════════════════

    private static final List<StateAction> NO_ACTIONS = List.of();
    private static final Map<String, StateMachineGraph> NO_SUBGRAPHS = Map.of();

    // ═══════════════════════════════════════════════
    // stand 的 vertical 图（仅 ground / jump_charge）
    // ═══════════════════════════════════════════════

    /** stand posture 的 vertical 子机：ground / jump_charge */
    public static final StateMachineGraph STAND = buildStandVert();

    private static StateMachineGraph buildStandVert() {
        var setGround = MechaStateActions.verticalFlags(Vertical.GROUND);
        var setJumpCharge = MechaStateActions.verticalFlags(Vertical.JUMP_CHARGE);

        // ground: 着地中，可蓄力起跳
        StateNode groundNode = new StateNode(
                Vertical.GROUND.molangName(),
                List.of(
                        // KCC 开始蓄力后，逻辑层在下一物理步镜像该状态
                        new StateTransition(null, Vertical.JUMP_CHARGE.molangName(), COND_KCC_CHARGING)
                ),
                List.of(setGround, MechaStateActions.enableVerticalInput()),
                NO_ACTIONS, NO_SUBGRAPHS);

        // jump_charge: 由 KCC 蓄力状态驱动，避免 ON_GROUND=true 时立即退出
        StateNode jumpChargeNode = new StateNode(
                Vertical.JUMP_CHARGE.molangName(),
                List.of(
                        new StateTransition(null, Vertical.GROUND.molangName(), COND_KCC_NOT_CHARGING)
                ),
                List.of(setJumpCharge, MechaStateActions.disableVerticalInput()),
                NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(groundNode, List.of(jumpChargeNode));
    }

    // ═══════════════════════════════════════════════
    // air 的 vertical 图（fall / glide / hover / fly）
    // ═══════════════════════════════════════════════

    /** air posture 的 vertical 子机：fall / glide / hover / fly */
    public static final StateMachineGraph AIR = buildAirVert();

    private static StateMachineGraph buildAirVert() {
        var setFall   = MechaStateActions.verticalFlags(Vertical.FALL);
        var setGlide  = MechaStateActions.verticalFlags(Vertical.GLIDE);
        var setHover  = MechaStateActions.verticalFlags(Vertical.HOVER);
        var setFly    = MechaStateActions.verticalFlags(Vertical.FLY);

        // fall: 默认空中状态——无动力摔落（上升段 + 下落段统一）
        StateNode fallNode = new StateNode(
                Vertical.FALL.molangName(),
                List.of(
                        new StateTransition("glide_activate", Vertical.GLIDE.molangName(),
                                StateCondition.True.INSTANCE),
                        new StateTransition("hover", Vertical.HOVER.molangName(),
                                StateCondition.True.INSTANCE),
                        new StateTransition("fly", Vertical.FLY.molangName(),
                                StateCondition.True.INSTANCE)
                ),
                List.of(setFall, MechaStateActions.enableVerticalMoveOnly()),
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

        // fly: 创造飞行=
        StateNode flyNode = new StateNode(
                Vertical.FLY.molangName(),
                List.of(
                        new StateTransition("fly", Vertical.FALL.molangName(),
                                StateCondition.True.INSTANCE)
                ),
                List.of(setFly, MechaStateActions.enableVerticalMoveOnly()),
                NO_ACTIONS, NO_SUBGRAPHS);

        // 默认初始态为 fall
        return new StateMachineGraph(fallNode, List.of(glideNode, hoverNode, flyNode));
    }
}
