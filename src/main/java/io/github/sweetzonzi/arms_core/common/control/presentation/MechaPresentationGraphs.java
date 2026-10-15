package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.state_machine.graph.StateAction;
import cn.solarmoon.spark_core.state_machine.graph.StateCondition;
import cn.solarmoon.spark_core.state_machine.graph.StateMachineGraph;
import cn.solarmoon.spark_core.state_machine.graph.StateNode;
import cn.solarmoon.spark_core.state_machine.graph.StateTransition;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;
import static io.github.sweetzonzi.arms_core.common.control.state.graph.SimpleVariableCondition.*;

/**
 * 四台姿态表现层状态机的图（纯数据常量）。
 * <p>
 * 与逻辑层状态图（{@code common/control/state/preset/}）是两套独立的东西：逻辑层由输入与环境推进，
 * 产出 {@code POSTURE} / {@code GAIT} / {@code VERTICAL}；这里读那三项产出的 one-hot 布尔，
 * 只决定播哪个动画。两者共用同一个变量容器，因此条件的读取不需要额外拷贝。
 *
 * <h3>共同骨架</h3>
 * 每台机器一台图，结构相同：一个 {@link #INACTIVE} 初态 + 若干活跃态。四台机器每一个物理步都被推进，
 * 互斥靠各自回 {@code inactive} 完成——同一时刻只有一个姿态为真，因此只有一台机器离开 {@code inactive}。
 * <p>
 * 所有转移都是每步自动求值的（{@code StateTransition} 的 {@code event} 传 {@code null}），
 * 条件读共享变量，不用事件。
 * <p>
 * 节点名取自它要播的动画语义，不取 gait 名：动画数量少于 gait 数量，两者不是一一对应。
 * <p>
 * <b>条件的互斥性</b>：除 STAND 的兜底边之外，各表条件两两互斥（都源自同一步写入的枚举 one-hot
 * 组合），因此同一节点内多条出边的排列顺序不影响结果。兜底边与同表其它边不互斥，靠排在最后保证
 * 只在没有更具体的分支成立时才命中。
 *
 * <h3>未映射的 gait</h3>
 * {@code DODGE} / {@code STUN} / {@code HARD_LAND} 不设节点、也不设出边。它们期间 gait 的其余
 * one-hot 全为假，所在机器的活跃节点没有任何出边成立，于是停在原节点继续播循环动画；这是
 * 「没有出边」的自然结果，不是兜底逻辑。三者的时长由逻辑层常量界定，表现层不重复计时。
 *
 * @author Sweetzonzi
 */
public final class MechaPresentationGraphs {

    /** 四台机器共同的待命态：不播任何动画，不设出边之外的任何动作。 */
    public static final String INACTIVE = "inactive";

    // ═══════════════════════════════════════════════
    // 空列表复用（必须先于下面的图常量初始化）
    // ═══════════════════════════════════════════════

    private static final List<StateAction> NO_ACTIONS = List.of();
    private static final Map<String, StateMachineGraph> NO_SUBGRAPHS = Map.of();

    // ═══════════════════════════════════════════════
    // 图常量
    // ═══════════════════════════════════════════════

    /** STAND 机器：{@code inactive} + {@code idle} / {@code move} / {@code sprint}。 */
    public static final StateMachineGraph STAND = stand();

    /** AIR 机器：{@code inactive} + {@code fall} / {@code glide} / {@code fly}。 */
    public static final StateMachineGraph AIR = air();

    /** WATER 机器：{@code inactive} + {@code float} / {@code swim}。 */
    public static final StateMachineGraph WATER = water();

    /** CROUCH 机器：{@code inactive} + {@code idle} / {@code move}。 */
    public static final StateMachineGraph CROUCH = crouch();

    private MechaPresentationGraphs() {
    }

    /**
     * 四张图，按姿态顺序排列。
     * <p>
     * 顺序只影响 {@code MultiAnimStateMachine} 的推进次序；四台机器的条件都只读变量容器，
     * 而变量的写入发生在推进之前，因此次序不影响结果。
     *
     * @return 不可变列表，元素依次为 STAND / AIR / WATER / CROUCH
     */
    public static List<StateMachineGraph> all() {
        return List.of(STAND, AIR, WATER, CROUCH);
    }

    // ═══════════════════════════════════════════════
    // 四台机器
    // ═══════════════════════════════════════════════

    /**
     * STAND 机器。三个活跃态各自播一个基础移动动画：{@code idle} 播 idle、{@code move} 播 walk、
     * {@code sprint} 播 run。
     * <p>
     * 出边（同一源节点的多条按声明顺序求值，取第一条成立的）：
     * <ul>
     *   <li>{@code inactive → sprint}：{@code IS_SPRINTING_G}</li>
     *   <li>{@code inactive → move}：{@code IS_CREEPING || IS_JOGGING}</li>
     *   <li>{@code inactive → idle}：裸 {@code IS_STANDING}，<b>排在最后</b>。这是兜底边：
     *       姿态刚变到 STAND 而 gait 恰好是某个没有映射的值（典型是空中落地那一刻的
     *       {@code DRIFT}）时，靠它落到有动画的节点上。它与前两条不互斥，靠顺序保证只在
     *       没有更具体的分支成立时才命中。</li>
     *   <li>{@code idle} / {@code move} / {@code sprint → inactive}：{@code !IS_STANDING}</li>
     *   <li>{@code idle → sprint}、{@code move → sprint}：{@code IS_SPRINTING_G}</li>
     *   <li>{@code move} / {@code sprint → idle}：<b>不含 {@code IS_DRIFTING} 的裸
     *       {@code IS_IDLE}</b>。{@code DRIFT} 期间这两条不成立，机器留在原节点继续播，
     *       冲刺松手后的减速收尾因此由 gait 子机的滞回自动结束——回落阈值取
     *       {@code common/control/state/preset/GaitSubGraphs.java#STOP_SPEED_THRESHOLD}
     *       （0.05 m/s），进入阈值取同文件的
     *       {@code common/control/state/preset/GaitSubGraphs.java#DRIFT_ENTRY_SPEED_THRESHOLD}
     *       （0.15 m/s）。把 {@code IS_DRIFTING} 加回这两条会立刻破坏它：冲刺松手时会硬切回
     *       站立动画。</li>
     *   <li>{@code sprint → move}：{@code IS_CREEPING || IS_JOGGING}</li>
     * </ul>
     */
    private static StateMachineGraph stand() {
        StateCondition sprinting = isTrue(IS_SPRINTING_G);
        StateCondition moving = any(isTrue(IS_CREEPING), isTrue(IS_JOGGING));
        StateCondition standing = isTrue(IS_STANDING);
        StateCondition notStanding = isFalse(IS_STANDING);
        StateCondition idle = isTrue(IS_IDLE);

        StateNode idleNode = animated(Posture.STAND, "idle", List.of(
                new StateTransition(null, "sprint", sprinting),
                new StateTransition(null, "move", moving),
                new StateTransition(null, INACTIVE, notStanding)));
        StateNode moveNode = animated(Posture.STAND, "move", List.of(
                new StateTransition(null, "sprint", sprinting),
                new StateTransition(null, "idle", idle),
                new StateTransition(null, INACTIVE, notStanding)));
        StateNode sprintNode = animated(Posture.STAND, "sprint", List.of(
                new StateTransition(null, "move", moving),
                new StateTransition(null, "idle", idle),
                new StateTransition(null, INACTIVE, notStanding)));

        StateNode inactiveNode = new StateNode(
                INACTIVE,
                List.of(
                        new StateTransition(null, "sprint", sprinting),
                        new StateTransition(null, "move", moving),
                        new StateTransition(null, "idle", standing)),
                NO_ACTIONS, NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(inactiveNode, List.of(idleNode, moveNode, sprintNode));
    }

    /**
     * AIR 机器。{@code IS_JUMP_BOOSTING} 与 {@code IS_FALLING} 合并进 {@code fall}：资源里只有一个
     * jump 动画，没有上升 / 下落两套，细分段要等按同步速度做表现层判定之后才成立。
     */
    private static StateMachineGraph air() {
        Map<String, StateCondition> states = new LinkedHashMap<>();
        states.put("fall", any(isTrue(IS_FALLING), isTrue(IS_JUMP_BOOSTING)));
        states.put("glide", isTrue(IS_GLIDING));
        states.put("fly", any(isTrue(IS_HOVERING), isTrue(IS_FLYING)));
        return compact(Posture.AIR, states, isFalse(IS_AIRBORNE));
    }

    /**
     * WATER 机器。两条活跃态把所在姿态的全部取值覆盖完（{@code IS_IDLE} 与其取反），
     * 因此 {@code DRIFT} / {@code DODGE} / {@code STUN} 期间的水中机体照常播移动动画。
     */
    private static StateMachineGraph water() {
        Map<String, StateCondition> states = new LinkedHashMap<>();
        states.put("float", all(isTrue(IN_WATER_POSTURE), isTrue(IS_IDLE)));
        states.put("swim", all(isTrue(IN_WATER_POSTURE), isFalse(IS_IDLE)));
        return compact(Posture.WATER, states, isFalse(IN_WATER_POSTURE));
    }

    /**
     * CROUCH 机器。与 WATER 同构：两条活跃态覆盖所在姿态的全部取值，蹲下时继续播移动动画。
     */
    private static StateMachineGraph crouch() {
        Map<String, StateCondition> states = new LinkedHashMap<>();
        states.put("idle", all(isTrue(IS_CROUCHING), isTrue(IS_IDLE)));
        states.put("move", all(isTrue(IS_CROUCHING), isFalse(IS_IDLE)));
        return compact(Posture.CROUCH, states, isFalse(IS_CROUCHING));
    }

    // ═══════════════════════════════════════════════
    // 构造辅助
    // ═══════════════════════════════════════════════

    /**
     * 生成 AIR / WATER / CROUCH 三台共用的紧凑图。
     * <p>
     * 这三台的活跃态进入条件已经覆盖所在姿态的全部取值且两两互斥，因此「按节点给的进入条件」与
     * 「按边给的出边条件」等价：每个活跃态从 {@code inactive} 与其它活跃态各有一条指向它的边，
     * 条件都是它自己的进入条件；每个活跃态另有一条回 {@code inactive} 的边，条件是不在该姿态。
     * <p>
     * STAND 不用这个生成器：它有三条互不覆盖的活跃态条件，外加一条不互斥的兜底边，边表必须手写。
     *
     * @param posture          所属姿态，用于查动画名映射
     * @param entryConditions  活跃态名 → 进入条件，保持插入顺序
     * @param notInPosture     该姿态之外的兜底条件（回 {@code inactive}）
     * @return 以 {@code inactive} 为初态的图
     */
    private static StateMachineGraph compact(Posture posture,
                                             Map<String, StateCondition> entryConditions,
                                             StateCondition notInPosture) {
        List<StateNode> nodes = new ArrayList<>(entryConditions.size());
        for (String state : entryConditions.keySet()) {
            List<StateTransition> transitions = new ArrayList<>(entryConditions.size() + 1);
            for (Map.Entry<String, StateCondition> entry : entryConditions.entrySet()) {
                if (entry.getKey().equals(state)) continue;
                transitions.add(new StateTransition(null, entry.getKey(), entry.getValue()));
            }
            transitions.add(new StateTransition(null, INACTIVE, notInPosture));
            nodes.add(animated(posture, state, transitions));
        }

        List<StateTransition> inactiveTransitions = new ArrayList<>(entryConditions.size());
        for (Map.Entry<String, StateCondition> entry : entryConditions.entrySet()) {
            inactiveTransitions.add(new StateTransition(null, entry.getKey(), entry.getValue()));
        }
        StateNode inactiveNode = new StateNode(
                INACTIVE, inactiveTransitions, NO_ACTIONS, NO_ACTIONS, NO_SUBGRAPHS);

        return new StateMachineGraph(inactiveNode, nodes);
    }

    /**
     * 造一个活跃态：进入时播该姿态状态映射到的动画，无映射则不播（进入动作留空，不回退到绑定姿态以外的东西）。
     */
    private static StateNode animated(Posture posture, String state, List<StateTransition> transitions) {
        String animation = MechaAnimPreset.animationOf(posture, state);
        List<StateAction> onEntry = animation == null
                ? NO_ACTIONS
                : List.of(new MechaPlayAnimAction(
                        animation, MechaAnimPreset.POSTURE_BLEND_SECONDS));
        return new StateNode(state, transitions, onEntry, NO_ACTIONS, NO_SUBGRAPHS);
    }

    /** 逻辑或。 */
    private static StateCondition any(StateCondition... conditions) {
        return new StateCondition.Any(List.of(conditions));
    }

    /** 逻辑与。 */
    private static StateCondition all(StateCondition... conditions) {
        return new StateCondition.All(List.of(conditions));
    }
}
