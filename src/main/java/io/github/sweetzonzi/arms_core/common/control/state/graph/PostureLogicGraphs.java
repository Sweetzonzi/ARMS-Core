package io.github.sweetzonzi.arms_core.common.control.state.graph;

import cn.solarmoon.spark_core.state_machine.graph.*;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import io.github.sweetzonzi.arms_core.common.control.state.MechaStateActions;
import io.github.sweetzonzi.arms_core.common.control.state.SimpleVariableCondition;

import java.util.List;
import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.SimpleVariableCondition.*;
import static cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys.*;

/**
 * posture 逻辑层顶层状态机图 —— 硬编码常量，不可被 UGC 替换。
 *
 * <pre>
 * 状态转移图：
 *     [*] ──▶ stand ──(!onGround)──▶ air
 *              │   ├──(onGround && inWater)──▶ water
 *              │   ├──(event:sneak)──▶ crouch
 *              │   └──(isDead)──▶ ragdoll
 *           air ──(onGround)──▶ stand
 *              ├──(!onGround && inWater)──▶ water
 *              └──(isDead)──▶ ragdoll
 *         water ──(onGround && !inWater)──▶ stand
 *              └──(isDead)──▶ ragdoll
 *        crouch ──(event:sneak)──▶ stand
 *              ├──(event:prone)──▶ prone
 *              └──(isDead)──▶ ragdoll
 *         prone ──(event:prone)──▶ crouch
 *              └──(isDead)──▶ ragdoll
 *       ragdoll 终态，无出口
 * </pre>
 *
 * <p>
 * 子图声明（进入对应节点时自动激活，离开时自动关闭）：
 * <ul>
 *   <li>stand: "stand_gait" + "stand_vert"</li>
 *   <li>air: "air_vert"</li>
 *   <li>water: "water_gait"</li>
 *   <li>crouch: "crouch_gait"</li>
 *   <li>prone: "prone_gait"</li>
 *   <li>ragdoll: 无</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
public final class PostureLogicGraphs {

    private PostureLogicGraphs() {}

    // ═══════════════════════════════════════════════
    // 转移条件常量（复用，避免重复创建）
    // ═══════════════════════════════════════════════

    /** onGround == true（着地） */
    private static final StateCondition COND_ON_GROUND = isTrue(ON_GROUND);
    /** onGround == false（离地） */
    private static final StateCondition COND_NOT_ON_GROUND = isFalse(ON_GROUND);
    /** inWater == true */
    private static final StateCondition COND_IN_WATER = isTrue(IN_WATER);
    /** inWater == false */
    private static final StateCondition COND_NOT_IN_WATER = isFalse(IN_WATER);
    /** isDead == true */
    private static final StateCondition COND_IS_DEAD = isTrue(IS_DEAD);

    /** onGround && inWater → stand → water */
    private static final StateCondition COND_STAND_TO_WATER =
            new StateCondition.All(List.of(COND_ON_GROUND, COND_IN_WATER));

    /** !onGround && inWater → air → water */
    private static final StateCondition COND_AIR_TO_WATER =
            new StateCondition.All(List.of(COND_NOT_ON_GROUND, COND_IN_WATER));

    /** onGround && !inWater → water → stand */
    private static final StateCondition COND_WATER_TO_STAND =
            new StateCondition.All(List.of(COND_ON_GROUND, COND_NOT_IN_WATER));

    // ═══════════════════════════════════════════════
    // 进入/退出动作常量
    // ═══════════════════════════════════════════════

    private static final StateAction SET_POSTURE_STAND = MechaStateActions.postureFlags("stand");
    private static final StateAction SET_POSTURE_AIR = MechaStateActions.postureFlags("air");
    private static final StateAction SET_POSTURE_WATER = MechaStateActions.postureFlags("water");
    private static final StateAction SET_POSTURE_CROUCH = MechaStateActions.postureFlags("crouch");
    private static final StateAction SET_POSTURE_PRONE = MechaStateActions.postureFlags("prone");
    private static final StateAction SET_POSTURE_RAGDOLL = MechaStateActions.postureFlags("ragdoll");

    // ═══════════════════════════════════════════════
    // 空列表复用
    // ═══════════════════════════════════════════════

    private static final List<StateTransition> NO_TRANSITIONS = List.of();
    private static final List<StateAction> NO_ACTIONS = List.of();
    private static final Map<String, StateMachineGraph> NO_SUBGRAPHS = Map.of();

    // ═══════════════════════════════════════════════
    // 图常量
    // ═══════════════════════════════════════════════

    /**
     * posture 顶层状态机图。
     * <p>
     * 由 {@link MechaLogicController} 构造时传入。
     * 子图键名需与 {@code MechaLogicController} 的 children Map 键名一致。
     */
    public static final StateMachineGraph GRAPH = buildGraph();

    private static StateMachineGraph buildGraph() {
        // —— stand 节点 ——
        StateNode standNode = new StateNode(
                "stand",
                List.of(
                        new StateTransition(null, "air", COND_NOT_ON_GROUND),      // auto: 离地 → air
                        new StateTransition(null, "water", COND_STAND_TO_WATER),   // auto: 入水 → water
                        new StateTransition("sneak", "crouch", StateCondition.True.INSTANCE),  // event
                        new StateTransition(null, "ragdoll", COND_IS_DEAD)         // auto: 死亡
                ),
                List.of(SET_POSTURE_STAND),
                NO_ACTIONS,
                Map.of(
                        "stand_gait", GaitSubGraphs.STAND,    // stand 的 gait 子机
                        "stand_vert", VerticalSubGraphs.STAND  // stand 的 vertical 子机
                )
        );

        // —— air 节点 ——
        StateNode airNode = new StateNode(
                "air",
                List.of(
                        new StateTransition(null, "stand", COND_ON_GROUND),        // auto: 着地 → stand
                        new StateTransition(null, "water", COND_AIR_TO_WATER),     // auto: 空中入水 → water
                        new StateTransition("fly", "air_fly", StateCondition.True.INSTANCE),  // event: 飞行切换
                        new StateTransition(null, "ragdoll", COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_AIR),
                NO_ACTIONS,
                Map.of("air_vert", VerticalSubGraphs.AIR)  // air 的 vertical 子机
        );

        // —— water 节点 ——
        StateNode waterNode = new StateNode(
                "water",
                List.of(
                        new StateTransition(null, "stand", COND_WATER_TO_STAND),   // auto: 出水 → stand
                        new StateTransition(null, "ragdoll", COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_WATER),
                NO_ACTIONS,
                Map.of("water_gait", GaitSubGraphs.WATER)  // water 的 gait 子机
        );

        // —— crouch 节点 ——
        StateNode crouchNode = new StateNode(
                "crouch",
                List.of(
                        new StateTransition("sneak", "stand", StateCondition.True.INSTANCE),
                        new StateTransition("prone", "prone", StateCondition.True.INSTANCE),
                        new StateTransition(null, "ragdoll", COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_CROUCH),
                NO_ACTIONS,
                Map.of("crouch_gait", GaitSubGraphs.CROUCH)  // crouch 的 gait 子机（无 sprint）
        );

        // —— prone 节点 ——
        StateNode proneNode = new StateNode(
                "prone",
                List.of(
                        new StateTransition("prone", "crouch", StateCondition.True.INSTANCE),
                        new StateTransition(null, "ragdoll", COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_PRONE),
                NO_ACTIONS,
                Map.of("prone_gait", GaitSubGraphs.PRONE)  // prone 的 gait 子机（仅 crawl）
        );

        // —— ragdoll 节点（终态） ——
        StateNode ragdollNode = new StateNode(
                "ragdoll",
                NO_TRANSITIONS,  // 终态，无出口
                List.of(SET_POSTURE_RAGDOLL),
                NO_ACTIONS,
                NO_SUBGRAPHS
        );

        // 组装图：stand 为初始状态
        return new StateMachineGraph(
                standNode,
                List.of(airNode, waterNode, crouchNode, proneNode, ragdollNode)
        );
    }
}
