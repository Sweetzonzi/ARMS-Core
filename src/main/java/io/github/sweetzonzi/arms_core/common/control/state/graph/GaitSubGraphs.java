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
 * gait（步态）子状态机图常量。
 *
 * <pre>
 * 各 posture 的 gait 支持范围：
 *   stand:  idle / walk / run / sprint / dodge（全支持）
 *   crouch: idle / walk / run / dodge（无 sprint，dodge 为滚翻）
 *   prone:  idle / crawl（最低移动速度，无 dodge）
 *   water:  idle / swim / swim_sprint / water_dodge（推进器侧推）
 * </pre>
 *
 * <p>
 * 每个 gait 图进入活跃状态时写入 {@code ctrl.gait} 变量；
 * 表现层通过读取该变量决定动画切换，不重复判断条件。
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

    // ═══════════════════════════════════════════════
    // 空常量复用
    // ═══════════════════════════════════════════════

    private static final List<StateTransition> NO_TRANSITIONS = List.of();
    private static final List<StateAction> NO_ACTIONS = List.of();
    private static final Map<String, StateMachineGraph> NO_SUBGRAPHS = Map.of();

    // ═══════════════════════════════════════════════
    // stand 的完整 gait 图
    // ═══════════════════════════════════════════════

    /** stand posture 的 gait 子机：idle / walk / run / sprint / dodge */
    public static final StateMachineGraph STAND = buildStandGait();

    private static StateMachineGraph buildStandGait() {
        var setIdle = MechaStateActions.gaitFlags("idle");
        var setWalk = MechaStateActions.gaitFlags("walk");
        var setRun = MechaStateActions.gaitFlags("run");
        var setSprint = MechaStateActions.gaitFlags("sprint");
        var setDodge = MechaStateActions.gaitFlags("dodge");

        // sprint 条件：冲刺键按住 + 能量 > 0
        var condSprint = new StateCondition.All(List.of(
                isTrue(StateVariableKeys.IS_SPRINTING),
                floatGt(ENERGY, 0f)
        ));

        StateNode idleNode = new StateNode("idle",
                List.of(
                        new StateTransition(null, "walk",    // auto: 有输入 + walk键
                                new StateCondition.All(List.of(COND_HAS_INPUT, COND_WALK_KEY))),
                        new StateTransition(null, "run",     // auto: 有输入 + 非walk键（默认慢跑）
                                new StateCondition.All(List.of(COND_HAS_INPUT, isFalse(WALK_KEY_DOWN)))),
                        new StateTransition("dodge", "dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setIdle), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode walkNode = new StateNode("walk",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "run",
                                new StateCondition.All(List.of(COND_HAS_INPUT, isFalse(WALK_KEY_DOWN)))),
                        new StateTransition("dodge", "dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setWalk), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode runNode = new StateNode("run",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "walk",
                                new StateCondition.All(List.of(COND_HAS_INPUT, COND_WALK_KEY))),
                        new StateTransition(null, "sprint", condSprint),
                        new StateTransition("dodge", "dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setRun), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode sprintNode = new StateNode("sprint",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "run",
                                new StateCondition.Any(List.of(
                                        isFalse(StateVariableKeys.IS_SPRINTING),
                                        floatGt(ENERGY, 0f).not()
                                ))),
                        new StateTransition("dodge", "dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setSprint), NO_ACTIONS, NO_SUBGRAPHS);

        // dodge 瞬态：自动退回 idle/walk/run 取决于当前输入状态
        StateNode dodgeNode = new StateNode("dodge",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "walk",
                                new StateCondition.All(List.of(COND_HAS_INPUT, COND_WALK_KEY))),
                        new StateTransition(null, "run",
                                new StateCondition.All(List.of(COND_HAS_INPUT, isFalse(WALK_KEY_DOWN))))
                ),
                List.of(setDodge), NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(idleNode,
                List.of(walkNode, runNode, sprintNode, dodgeNode));
    }

    // ═══════════════════════════════════════════════
    // crouch 的 gait 图（无 sprint，dodge 为滚翻）
    // ═══════════════════════════════════════════════

    /** crouch posture 的 gait 子机：idle / walk / run / dodge（滚翻） */
    public static final StateMachineGraph CROUCH = buildCrouchGait();

    private static StateMachineGraph buildCrouchGait() {
        var setIdle = MechaStateActions.gaitFlags("idle");
        var setWalk = MechaStateActions.gaitFlags("walk");
        var setRun = MechaStateActions.gaitFlags("run");
        var setDodge = MechaStateActions.gaitFlags("dodge");  // 滚翻

        StateNode idleNode = new StateNode("idle",
                List.of(
                        new StateTransition(null, "walk",
                                new StateCondition.All(List.of(COND_HAS_INPUT, COND_WALK_KEY))),
                        new StateTransition(null, "run",
                                new StateCondition.All(List.of(COND_HAS_INPUT, isFalse(WALK_KEY_DOWN)))),
                        new StateTransition("dodge", "dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setIdle), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode walkNode = new StateNode("walk",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "run",
                                new StateCondition.All(List.of(COND_HAS_INPUT, isFalse(WALK_KEY_DOWN)))),
                        new StateTransition("dodge", "dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setWalk), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode runNode = new StateNode("run",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "walk",
                                new StateCondition.All(List.of(COND_HAS_INPUT, COND_WALK_KEY))),
                        new StateTransition("dodge", "dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setRun), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode dodgeNode = new StateNode("dodge",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "walk",
                                new StateCondition.All(List.of(COND_HAS_INPUT, COND_WALK_KEY))),
                        new StateTransition(null, "run",
                                new StateCondition.All(List.of(COND_HAS_INPUT, isFalse(WALK_KEY_DOWN))))
                ),
                List.of(setDodge), NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(idleNode,
                List.of(walkNode, runNode, dodgeNode));
    }

    // ═══════════════════════════════════════════════
    // prone 的 gait 图（仅 crawl，最低移动速度）
    // ═══════════════════════════════════════════════

    /** prone posture 的 gait 子机：idle / crawl（最低移动速度，无 dodge） */
    public static final StateMachineGraph PRONE = buildProneGait();

    private static StateMachineGraph buildProneGait() {
        var setIdle = MechaStateActions.gaitFlags("idle");
        var setCrawl = MechaStateActions.gaitFlags("crawl");

        StateNode idleNode = new StateNode("idle",
                List.of(
                        new StateTransition(null, "crawl", COND_HAS_INPUT)
                ),
                List.of(setIdle), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode crawlNode = new StateNode("crawl",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT)
                ),
                List.of(setCrawl), NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(idleNode, List.of(crawlNode));
    }

    // ═══════════════════════════════════════════════
    // water 的 gait 图（推进器侧推为 dodge）
    // ═══════════════════════════════════════════════

    /** water posture 的 gait 子机：idle / swim / swim_sprint / water_dodge */
    public static final StateMachineGraph WATER = buildWaterGait();

    private static StateMachineGraph buildWaterGait() {
        var setIdle = MechaStateActions.gaitFlags("idle");
        var setSwim = MechaStateActions.gaitFlags("swim");
        var setSwimSprint = MechaStateActions.gaitFlags("swim_sprint");
        var setWaterDodge = MechaStateActions.gaitFlags("water_dodge");

        var condSwimSprint = new StateCondition.All(List.of(
                isTrue(StateVariableKeys.IS_SPRINTING),
                floatGt(ENERGY, 0f)
        ));

        StateNode idleNode = new StateNode("idle",
                List.of(
                        new StateTransition(null, "swim", COND_HAS_INPUT),
                        new StateTransition("dodge", "water_dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setIdle), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode swimNode = new StateNode("swim",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "swim_sprint", condSwimSprint),
                        new StateTransition("dodge", "water_dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setSwim), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode swimSprintNode = new StateNode("swim_sprint",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "swim",
                                new StateCondition.Any(List.of(
                                        isFalse(StateVariableKeys.IS_SPRINTING),
                                        floatGt(ENERGY, 0f).not()
                                ))),
                        new StateTransition("dodge", "water_dodge", StateCondition.True.INSTANCE)
                ),
                List.of(setSwimSprint), NO_ACTIONS, NO_SUBGRAPHS);

        StateNode waterDodgeNode = new StateNode("water_dodge",
                List.of(
                        new StateTransition(null, "idle", COND_NO_INPUT),
                        new StateTransition(null, "swim", COND_HAS_INPUT)
                ),
                List.of(setWaterDodge), NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(idleNode,
                List.of(swimNode, swimSprintNode, waterDodgeNode));
    }
}
