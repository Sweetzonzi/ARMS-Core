package io.github.sweetzonzi.arms_core.common.control.state.preset;

import cn.solarmoon.spark_core.state_machine.graph.*;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.graph.MechaStateActions;

import java.util.List;
import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;
import static io.github.sweetzonzi.arms_core.common.control.state.graph.SimpleVariableCondition.*;

/**
 * gait（水平移动模式）子状态机图常量。
 *
 * <p>
 * 所有姿态共用同一 {@link Gait} 词表，各姿态的 gait 图参数不同。
 * 进入每个 gait 状态时写入的 {@code move_speed_modifier} =
 * {@code posture.speedModifier() × gait.baseSpeedModifier()}，
 * 最终由 KCC 直接读取，无需关心各层枚举值。
 *
 * <pre>
 * 各 posture 的 gait 支持范围：
 *   stand:  idle / creep / jog / sprint / drift / dodge / stun / hard_land
 *   air:    idle / creep / jog / drift / dodge / stun
 *   water:  idle / creep / jog / sprint / drift / dodge / stun
 *   crouch: idle / creep / jog / sprint / drift / dodge / stun
 *   prone:  idle / creep / jog / drift / dodge / stun
 * </pre>
 *
 * <p>
 * drift 是"有残余水平速度但无自主输入"的状态，与 idle (速度≈0) 区分。
 * 所有姿态均支持 drift，特别在 air 和 water 中极为常见。
 *
 * @author Sweetzonzi
 */
public final class GaitSubGraphs {

    private GaitSubGraphs() {}

    // ═══════════════════════════════════════════════
    // 通用条件
    // ═══════════════════════════════════════════════

    private static final StateCondition COND_HAS_INPUT = isTrue(HAS_INPUT);
    private static final StateCondition COND_NO_INPUT = isFalse(HAS_INPUT);
    private static final StateCondition COND_WALK_KEY = isTrue(WALK_KEY_DOWN);

    /** 有输入 + 慢走键 → creep gait */
    private static final StateCondition COND_CREEP =
            new StateCondition.All(List.of(COND_HAS_INPUT, COND_WALK_KEY));

    /** 有输入 + 非慢走键 → jog gait（默认常速） */
    private static final StateCondition COND_JOG =
            new StateCondition.All(List.of(COND_HAS_INPUT, isFalse(WALK_KEY_DOWN)));

    /** sprint 条件：冲刺键按住 + 能量 > 0 */
    private static final StateCondition COND_SPRINT = new StateCondition.All(List.of(
            isTrue(StateVariableKeys.IS_SPRINTING),
            floatGt(ENERGY, 0f)
    ));

    /** sprint 退出条件：松开冲刺键 或 能量耗尽 */
    private static final StateCondition COND_NOT_SPRINT = new StateCondition.Any(List.of(
            isFalse(StateVariableKeys.IS_SPRINTING),
            floatLe(ENERGY, 0f)
    ));

    // ═══════════════════════════════════════════════
    // 空常量复用
    // ═══════════════════════════════════════════════

    private static final List<StateTransition> NO_TRANSITIONS = List.of();
    private static final List<StateAction> NO_ACTIONS = List.of();
    private static final Map<String, StateMachineGraph> NO_SUBGRAPHS = Map.of();

    // ═══════════════════════════════════════════════
    // 各姿态 gait 图声明
    // ═══════════════════════════════════════════════

    public static final StateMachineGraph STAND  = buildGaitGraph(Posture.STAND, true, true);
    public static final StateMachineGraph AIR    = buildGaitGraph(Posture.AIR, false, false);
    public static final StateMachineGraph CROUCH = buildGaitGraph(Posture.CROUCH, true, false);
    public static final StateMachineGraph PRONE  = buildGaitGraph(Posture.PRONE, false, false);
    public static final StateMachineGraph WATER  = buildGaitGraph(Posture.WATER, true, false);

    // ═══════════════════════════════════════════════
    // 通用 gait 图工厂
    // ═══════════════════════════════════════════════

    /**
     * 根据姿态参数构造 gait 状态机图。
     *
     * <p>
     * 写入 MOVE_SPEED_MODIFIER = posture.speedModifier × gait.baseSpeedModifier。
     * KCC 每帧读此值，不查 posture 也不查 gait 名。
     *
     * @param posture   姿态枚举（携带 speedModifier）
     * @param hasSprint 此姿态是否支持 sprint
     * @param hasHardLand 此姿态是否支持重落地恢复（仅 stand）
     */
    private static StateMachineGraph buildGaitGraph(
            Posture posture, boolean hasSprint, boolean hasHardLand) {

        float pSpeed = posture.speedModifier();

        //  最终倍率 = 姿态基准 × gait 基准
        float idleMod   = 0f;
        float creepMod  = Gait.CREEP.baseSpeedModifier()  * pSpeed;
        float jogMod    = Gait.JOG.baseSpeedModifier()    * pSpeed;
        float sprintMod = Gait.SPRINT.baseSpeedModifier() * pSpeed;

        var setIdle     = MechaStateActions.gaitWithModifier(Gait.IDLE, idleMod);
        var setCreep    = MechaStateActions.gaitWithModifier(Gait.CREEP, creepMod);
        var setJog      = MechaStateActions.gaitWithModifier(Gait.JOG, jogMod);
        var setSprint   = MechaStateActions.gaitWithModifier(Gait.SPRINT, sprintMod);
        var setDrift    = MechaStateActions.gaitWithModifier(Gait.DRIFT, 0f);
        var setDodge    = MechaStateActions.gaitWithModifier(Gait.DODGE, 0f);
        var setStun     = MechaStateActions.gaitWithModifier(Gait.STUN, 0f);
        var setHardLand = MechaStateActions.gaitWithModifier(Gait.HARD_LAND, 0f);

        // ═══ idle — 速度≈0，无输入 ═══
        var idleTransitions = new java.util.ArrayList<StateTransition>();
        idleTransitions.add(new StateTransition(null, Gait.CREEP.molangName(), COND_CREEP));
        idleTransitions.add(new StateTransition(null, Gait.JOG.molangName(), COND_JOG));
        // 无输入 + 有残余速度 → drift
        idleTransitions.add(new StateTransition(null, Gait.DRIFT.molangName(), COND_NO_INPUT));
        // TODO: 精确条件：无输入 && horizontal_speed > 阈值
        idleTransitions.add(new StateTransition("dodge", Gait.DODGE.molangName(),
                StateCondition.True.INSTANCE));

        var idleActions = new java.util.ArrayList<StateAction>();
        idleActions.add(setIdle);
        idleActions.add(MechaStateActions.enableInput());

        StateNode idleNode = new StateNode(Gait.IDLE.molangName(),
                idleTransitions, idleActions, NO_ACTIONS, NO_SUBGRAPHS);

        // ═══ creep — 低速精细移动 ═══
        var creepTransitions = new java.util.ArrayList<StateTransition>();
        creepTransitions.add(new StateTransition(null, Gait.IDLE.molangName(), COND_NO_INPUT));
        creepTransitions.add(new StateTransition(null, Gait.JOG.molangName(), COND_JOG));
        creepTransitions.add(new StateTransition("dodge", Gait.DODGE.molangName(),
                StateCondition.True.INSTANCE));

        StateNode creepNode = new StateNode(Gait.CREEP.molangName(),
                creepTransitions, List.of(setCreep), NO_ACTIONS, NO_SUBGRAPHS);

        // ═══ jog — 常速移动 ═══
        var jogTransitions = new java.util.ArrayList<StateTransition>();
        jogTransitions.add(new StateTransition(null, Gait.IDLE.molangName(), COND_NO_INPUT));
        jogTransitions.add(new StateTransition(null, Gait.CREEP.molangName(), COND_CREEP));
        if (hasSprint)
            jogTransitions.add(new StateTransition(null, Gait.SPRINT.molangName(), COND_SPRINT));
        jogTransitions.add(new StateTransition("dodge", Gait.DODGE.molangName(),
                StateCondition.True.INSTANCE));

        StateNode jogNode = new StateNode(Gait.JOG.molangName(),
                jogTransitions, List.of(setJog), NO_ACTIONS, NO_SUBGRAPHS);

        // ═══ sprint — 高速移动 ═══
        StateNode sprintNode = null;
        if (hasSprint) {
            var sprintTransitions = new java.util.ArrayList<StateTransition>();
            sprintTransitions.add(new StateTransition(null, Gait.IDLE.molangName(), COND_NO_INPUT));
            sprintTransitions.add(new StateTransition(null, Gait.JOG.molangName(), COND_NOT_SPRINT));
            sprintTransitions.add(new StateTransition("dodge", Gait.DODGE.molangName(),
                    StateCondition.True.INSTANCE));
            sprintNode = new StateNode(Gait.SPRINT.molangName(),
                    sprintTransitions, List.of(setSprint), NO_ACTIONS, NO_SUBGRAPHS);
        }

        // ═══ drift — 惯性滑行（所有姿态均有）════
        var driftTransitions = new java.util.ArrayList<StateTransition>();
        // 速度归零 → idle
        driftTransitions.add(new StateTransition(null, Gait.IDLE.molangName(), COND_NO_INPUT));
        // TODO: 精确条件：horizontal_speed ≈ 0
        // 恢复输入 → creep 或 jog
        driftTransitions.add(new StateTransition(null, Gait.CREEP.molangName(), COND_CREEP));
        driftTransitions.add(new StateTransition(null, Gait.JOG.molangName(), COND_JOG));
        driftTransitions.add(new StateTransition("dodge", Gait.DODGE.molangName(),
                StateCondition.True.INSTANCE));

        StateNode driftNode = new StateNode(Gait.DRIFT.molangName(),
                driftTransitions, List.of(setDrift), NO_ACTIONS, NO_SUBGRAPHS);

        // ═══ dodge — 闪避/翻滚/推进器/空中 dash ═══
        var dodgeTransitions = new java.util.ArrayList<StateTransition>();
        dodgeTransitions.add(new StateTransition(null, Gait.IDLE.molangName(), COND_NO_INPUT));
        dodgeTransitions.add(new StateTransition(null, Gait.CREEP.molangName(), COND_CREEP));
        dodgeTransitions.add(new StateTransition(null, Gait.JOG.molangName(), COND_JOG));

        StateNode dodgeNode = new StateNode(Gait.DODGE.molangName(),
                dodgeTransitions, List.of(setDodge), NO_ACTIONS, NO_SUBGRAPHS);

        // ═══ stun — 硬直（禁止水平输入）════
        StateNode stunNode = new StateNode(Gait.STUN.molangName(),
                List.of(
                        new StateTransition(null, Gait.IDLE.molangName(), COND_NO_INPUT)
                        // TODO: 真实实现需用计时器条件替代 COND_NO_INPUT
                ),
                List.of(setStun, MechaStateActions.disableMove()),
                NO_ACTIONS, NO_SUBGRAPHS);

        // ═══ hard_land — 重落地恢复（可被 dodge 取消）════
        StateNode hardLandNode = null;
        if (hasHardLand) {
            var hardLandTransitions = new java.util.ArrayList<StateTransition>();
            hardLandTransitions.add(new StateTransition(null, Gait.IDLE.molangName(), COND_NO_INPUT));
            // TODO: 真实实现需用动画播完条件替代 COND_NO_INPUT
            hardLandTransitions.add(new StateTransition("dodge", Gait.DODGE.molangName(),
                    StateCondition.True.INSTANCE)); // dodge 提前取消

            hardLandNode = new StateNode(Gait.HARD_LAND.molangName(),
                    hardLandTransitions,
                    List.of(setHardLand, MechaStateActions.disableMove()),
                    NO_ACTIONS, NO_SUBGRAPHS);
        }

        // 组装非 idle 节点列表（剔除 null）
        var extraNodes = new java.util.ArrayList<StateNode>();
        extraNodes.add(creepNode);
        extraNodes.add(jogNode);
        extraNodes.add(driftNode);
        extraNodes.add(dodgeNode);
        extraNodes.add(stunNode);
        if (sprintNode != null) extraNodes.add(sprintNode);
        if (hardLandNode != null) extraNodes.add(hardLandNode);

        return new StateMachineGraph(idleNode, extraNodes);
    }
}
