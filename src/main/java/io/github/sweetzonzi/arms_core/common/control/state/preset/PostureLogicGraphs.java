package io.github.sweetzonzi.arms_core.common.control.state.preset;

import cn.solarmoon.spark_core.state_machine.graph.*;
import cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.graph.MechaStateActions;

import java.util.List;
import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;
import static io.github.sweetzonzi.arms_core.common.control.state.graph.SimpleVariableCondition.*;

/**
 * posture 逻辑层顶层状态机图 —— 硬编码常量，不可被 UGC 替换。
 *
 * <pre>
 * 状态转移图：
 *     [*] ──▶ stand ──(!onGround)──▶ air
 *              │   ├──(onGround && inWater)──▶ water
 *              │   ├──(event:sneak)──▶ crouch
 *              │   ├──(event:mount)──▶ ride
 *              │   ├──(event:knockdown)──▶ prone
 *              │   └──(isDead)──▶ ragdoll
 *           air ──(onGround)──▶ stand
 *              ├──(!onGround && inWater)──▶ water
 *              ├──(event:mount)──▶ ride
 *              ├──(event:knockdown)──▶ prone
 *              └──(isDead)──▶ ragdoll
 *         water ──(onGround && !inWater)──▶ stand
 *              ├──(event:mount)──▶ ride
 *              ├──(event:knockdown)──▶ prone
 *              └──(isDead)──▶ ragdoll
 *        crouch ──(event:sneak)──▶ stand
 *              ├──(event:prone)──▶ prone
 *              ├──(event:mount)──▶ ride
 *              ├──(event:knockdown)──▶ prone
 *              └──(isDead)──▶ ragdoll
 *         prone ──(event:prone)──▶ crouch
 *              ├──(event:mount)──▶ ride
 *              └──(isDead)──▶ ragdoll
 *         ride  ──(event:dismount)──▶ stand
 *              └──(isDead)──▶ ragdoll
 *       ragdoll 终态，无出口
 * </pre>
 *
 * <p>
 * 子图声明（进入对应节点时自动激活，离开时自动关闭）：
 * <ul>
 *   <li>stand: "stand_gait" + "stand_vert"</li>
 *   <li>air: "air_gait" + "air_vert"</li>
 *   <li>water: "water_gait"</li>
 *   <li>crouch: "crouch_gait"</li>
 *   <li>prone: "prone_gait"</li>
 *   <li>ride: 无（骑乘期间 KCC 完全抑制，无需 gait/vert）</li>
 *   <li>ragdoll: 无</li>
 * </ul>
 *
 * <p>
 * 飞行切换（fly toggle）由 MechaControl 直接操作 air_vert 子控，不进 posture 图。
 * 击倒（knockdown）从任意非 ragdoll 姿态强制转入 prone。
 *
 * @author Sweetzonzi
 */
public final class PostureLogicGraphs {

    private PostureLogicGraphs() {}

    // ═══════════════════════════════════════════════
    // 转移条件常量（复用，避免重复创建）
    // ═══════════════════════════════════════════════

    private static final StateCondition COND_ON_GROUND = isTrue(StateVariableKeys.ON_GROUND);
    private static final StateCondition COND_NOT_ON_GROUND = isFalse(StateVariableKeys.ON_GROUND);
    private static final StateCondition COND_IN_WATER = isTrue(IN_WATER);
    private static final StateCondition COND_NOT_IN_WATER = isFalse(IN_WATER);
    private static final StateCondition COND_IS_DEAD = isTrue(StateVariableKeys.IS_DEAD);

    /** onGround && inWater → stand → water */
    private static final StateCondition COND_STAND_TO_WATER =
            new StateCondition.All(List.of(COND_ON_GROUND, COND_IN_WATER));

    /** !onGround && inWater → air → water */
    private static final StateCondition COND_AIR_TO_WATER =
            new StateCondition.All(List.of(COND_NOT_ON_GROUND, COND_IN_WATER));

    /** onGround && !inWater → water → stand */
    private static final StateCondition COND_WATER_TO_STAND =
            new StateCondition.All(List.of(COND_ON_GROUND, COND_NOT_IN_WATER));

    /** 有击倒事件 */
    private static final StateCondition COND_KNOCKDOWN = isTrue(EVENT_KNOCKDOWN);

    // ═══════════════════════════════════════════════
    // 进入/退出动作常量
    // ═══════════════════════════════════════════════

    private static final StateAction SET_POSTURE_STAND   = MechaStateActions.postureFlags(Posture.STAND);
    private static final StateAction SET_POSTURE_AIR     = MechaStateActions.postureFlags(Posture.AIR);
    private static final StateAction SET_POSTURE_WATER   = MechaStateActions.postureFlags(Posture.WATER);
    private static final StateAction SET_POSTURE_CROUCH  = MechaStateActions.postureFlags(Posture.CROUCH);
    private static final StateAction SET_POSTURE_PRONE   = MechaStateActions.postureFlags(Posture.PRONE);
    private static final StateAction SET_POSTURE_RIDING  = MechaStateActions.postureFlags(Posture.RIDING);
    private static final StateAction SET_POSTURE_RAGDOLL = MechaStateActions.postureFlags(Posture.RAGDOLL);

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
     * 子图键名需与 {@code MechaLogicStateMachine} 的 children Map 键名一致。
     */
    public static final StateMachineGraph GRAPH = buildGraph();

    private static StateMachineGraph buildGraph() {
        // —— stand 节点 ——
        StateNode standNode = new StateNode(
                Posture.STAND.molangName(),
                List.of(
                        new StateTransition(null, Posture.AIR.molangName(), COND_NOT_ON_GROUND),
                        new StateTransition(null, Posture.WATER.molangName(), COND_STAND_TO_WATER),
                        new StateTransition("sneak", Posture.CROUCH.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition("mount", Posture.RIDING.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition("knockdown", Posture.PRONE.molangName(), COND_KNOCKDOWN),
                        new StateTransition(null, Posture.RAGDOLL.molangName(), COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_STAND),
                NO_ACTIONS,
                Map.of(
                        "stand_gait", GaitSubGraphs.STAND,
                        "stand_vert", VerticalSubGraphs.STAND
                )
        );

        // —— air 节点 ——
        StateNode airNode = new StateNode(
                Posture.AIR.molangName(),
                List.of(
                        new StateTransition(null, Posture.STAND.molangName(), COND_ON_GROUND),
                        new StateTransition(null, Posture.WATER.molangName(), COND_AIR_TO_WATER),
                        new StateTransition("mount", Posture.RIDING.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition("knockdown", Posture.PRONE.molangName(), COND_KNOCKDOWN),
                        new StateTransition(null, Posture.RAGDOLL.molangName(), COND_IS_DEAD)
                        // 飞行切换（fly toggle）由 MechaControl 直接操作 air_vert 子控
                ),
                List.of(SET_POSTURE_AIR),
                NO_ACTIONS,
                Map.of(
                        "air_gait", GaitSubGraphs.AIR,    // 空中水平移动
                        "air_vert", VerticalSubGraphs.AIR
                )
        );

        // —— water 节点 ——
        StateNode waterNode = new StateNode(
                Posture.WATER.molangName(),
                List.of(
                        new StateTransition(null, Posture.STAND.molangName(), COND_WATER_TO_STAND),
                        new StateTransition("mount", Posture.RIDING.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition("knockdown", Posture.PRONE.molangName(), COND_KNOCKDOWN),
                        new StateTransition(null, Posture.RAGDOLL.molangName(), COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_WATER),
                NO_ACTIONS,
                Map.of("water_gait", GaitSubGraphs.WATER)
        );

        // —— crouch 节点 ——
        StateNode crouchNode = new StateNode(
                Posture.CROUCH.molangName(),
                List.of(
                        new StateTransition("sneak", Posture.STAND.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition("prone", Posture.PRONE.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition("mount", Posture.RIDING.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition("knockdown", Posture.PRONE.molangName(), COND_KNOCKDOWN),
                        new StateTransition(null, Posture.RAGDOLL.molangName(), COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_CROUCH),
                NO_ACTIONS,
                Map.of("crouch_gait", GaitSubGraphs.CROUCH)
        );

        // —— prone 节点 ——
        StateNode proneNode = new StateNode(
                Posture.PRONE.molangName(),
                List.of(
                        new StateTransition("prone", Posture.CROUCH.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition("mount", Posture.RIDING.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition(null, Posture.RAGDOLL.molangName(), COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_PRONE),
                NO_ACTIONS,
                Map.of("prone_gait", GaitSubGraphs.PRONE)
        );

        // —— ride 节点 — 骑乘姿态，无子控，KCC 完全抑制 ——
        StateNode rideNode = new StateNode(
                Posture.RIDING.molangName(),
                List.of(
                        new StateTransition("dismount", Posture.STAND.molangName(), StateCondition.True.INSTANCE),
                        new StateTransition(null, Posture.RAGDOLL.molangName(), COND_IS_DEAD)
                ),
                List.of(SET_POSTURE_RIDING),
                NO_ACTIONS,
                NO_SUBGRAPHS
        );

        // —— ragdoll 节点（终态） ——
        StateNode ragdollNode = new StateNode(
                Posture.RAGDOLL.molangName(),
                NO_TRANSITIONS,
                List.of(SET_POSTURE_RAGDOLL),
                NO_ACTIONS,
                NO_SUBGRAPHS
        );

        return new StateMachineGraph(
                standNode,
                List.of(airNode, waterNode, crouchNode, proneNode, rideNode, ragdollNode)
        );
    }
}
