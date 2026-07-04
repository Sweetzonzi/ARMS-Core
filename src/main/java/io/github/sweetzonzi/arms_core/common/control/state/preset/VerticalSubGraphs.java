package io.github.sweetzonzi.arms_core.common.control.state.preset;

import cn.solarmoon.spark_core.state_machine.graph.*;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import io.github.sweetzonzi.arms_core.common.control.state.graph.MechaStateActions;

import java.util.List;
import java.util.Map;

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

    private static final StateCondition COND_ON_GROUND = isTrue(StateVariableKeys.ON_GROUND);

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
                        // 跳跃键按下 → 进入蓄力
                        new StateTransition("jump_start", Vertical.JUMP_CHARGE.molangName(),
                                StateCondition.True.INSTANCE)
                ),
                List.of(setGround, MechaStateActions.enableInput()),  // 地面时恢复输入能力
                NO_ACTIONS, NO_SUBGRAPHS);

        // jump_charge: 跳跃蓄力中（跳跃键按住），离地前松开会回到 ground
        StateNode jumpChargeNode = new StateNode(
                Vertical.JUMP_CHARGE.molangName(),
                List.of(
                        // 跳跃键松开 → 施加冲量（瞬时，KCC 直接调用），回到 ground
                        new StateTransition("jump_release", Vertical.GROUND.molangName(),
                                StateCondition.True.INSTANCE),
                        // 如果离地前松开 → 取消跳跃，回 ground
                        new StateTransition(null, Vertical.GROUND.molangName(), COND_ON_GROUND)
                ),
                List.of(setJumpCharge, MechaStateActions.disableMove(), MechaStateActions.disableJump()),
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
                List.of(setFall),
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
                List.of(setGlide),
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
                List.of(setHover),
                NO_ACTIONS, NO_SUBGRAPHS);

        // fly: 创造飞行=
        StateNode flyNode = new StateNode(
                Vertical.FLY.molangName(),
                List.of(
                        new StateTransition("fly", Vertical.FALL.molangName(),
                                StateCondition.True.INSTANCE)
                ),
                List.of(setFly),
                NO_ACTIONS, NO_SUBGRAPHS);

        // 默认初始态为 fall
        return new StateMachineGraph(fallNode, List.of(glideNode, hoverNode, flyNode));
    }
}
