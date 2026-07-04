package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateGraphController;
import cn.solarmoon.spark_core.state_machine.graph.StateNode;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.state.preset.GaitSubGraphs;
import io.github.sweetzonzi.arms_core.common.control.state.preset.PostureLogicGraphs;
import io.github.sweetzonzi.arms_core.common.control.state.preset.VerticalSubGraphs;

import java.util.Map;

/**
 * 机娘逻辑层控制器 —— 封装 posture 顶层状态机及其子控制器。
 *
 * <p>
 * 子控制器激活/关闭机制（继承自 {@link StateGraphController}）：
 * <ol>
 *   <li>构造时传入全部子控制器到 {@code children} Map</li>
 *   <li>posture 状态机进入某节点时，查找该节点声明的 {@code subGraphs} 键名</li>
 *   <li>匹配的 children 被 {@code reset()} 并加入 {@code activeChildren}</li>
 *   <li>离开节点时，活跃子控全部 {@code onExit} + 清空</li>
 * </ol>
 *
 * <p>
 * 使用方式（MechaControl 端）：
 * <pre>
 *   // 1. 构造（容器由 MechaControl 创建，表现层也共用）
 *   StateVariableContainer vars = new StateVariableContainer();
 *   GameplayTagContainer tags = new GameplayTagContainer();
 *   MechaLogicStateMachine logic = new MechaLogicStateMachine(vars, tags);
 *
 *   // 2. 每帧：写入快照 + KCC 状态到 variables
 *   logic.getVariables().set(ON_GROUND, kcc.onGround());
 *   logic.getVariables().set(HAS_INPUT, snapshot.inputForward != 0 || ...);
 *   // ...
 *
 *   // 3. 注入离散事件
 *   if (pendingEvents.contains(TOGGLE_SNEAK)) logic.triggerEvent("sneak");
 *   if (pendingEvents.contains(TOGGLE_PRONE)) logic.triggerEvent("prone");
 *   if (pendingEvents.contains(DODGE))        logic.triggerEvent("dodge");
 *   if (pendingEvents.contains(TOGGLE_FLY))   logic.triggerEvent("fly");
 *
 *   // 4. 驱动状态机
 *   logic.progress();  // 递归驱动子控 + 自身 auto 转移
 *
 *   // 5. 读取产出变量
 *   String posture = logic.getVariables().get(POSTURE);  // "stand" / "air" / ...
 *   String gait    = logic.getVariables().get(GAIT);     // "idle" / "walk" / ...
 *   String vert    = logic.getVariables().get(VERTICAL); // "ground" / "jump" / ...
 * </pre>
 *
 * @author Sweetzonzi
 */
public class MechaLogicStateMachine extends StateGraphController {

    /**
     * 构造逻辑层控制器，包含 posture 顶层状态机及全部子控。
     * <p>
     * 子控键名需与 {@link PostureLogicGraphs} 中各节点声明的 subGraphs 键名一致。
     * 所有子控共享外部传入的 {@code variables} 和 {@code tags} 容器，
     * 确保快照写入（HAS_INPUT 等）与子控条件求值在同一容器上。
     *
     * @param variables 共享变量容器（由 MechaControl 创建，表现层也共用）
     * @param tags      共享标签容器
     */
    public MechaLogicStateMachine(StateVariableContainer variables, GameplayTagContainer tags) {
        super(PostureLogicGraphs.GRAPH, Map.of(
                "stand_gait",  new StateGraphController(GaitSubGraphs.STAND, Map.of(), variables, tags),
                "stand_vert",  new StateGraphController(VerticalSubGraphs.STAND, Map.of(), variables, tags),
                "air_gait",    new StateGraphController(GaitSubGraphs.AIR, Map.of(), variables, tags),
                "air_vert",    new StateGraphController(VerticalSubGraphs.AIR, Map.of(), variables, tags),
                "water_gait",  new StateGraphController(GaitSubGraphs.WATER, Map.of(), variables, tags),
                "crouch_gait", new StateGraphController(GaitSubGraphs.CROUCH, Map.of(), variables, tags),
                "prone_gait",  new StateGraphController(GaitSubGraphs.PRONE, Map.of(), variables, tags)
        ), variables, tags);
    }

    // ═══════════════════════════════════════════════
    // 调试日志（生产环境关闭）
    // ═══════════════════════════════════════════════

    private static final boolean DEBUG_LOG = false;

    @Override
    public void onTriggered(ActionEvent event, StateNode source, StateNode target) {
        super.onTriggered(event, source, target);
        if (DEBUG_LOG && target != null && event.getType() != null) {
            ARMS.LOGGER.debug("[MechaLogic] {} --({})--> {}",
                    source != null ? source.getName() : "?",
                    event.getType(),
                    target.getName());
        }
    }

    @Override
    public void onEntry(StateNode node) {
        super.onEntry(node);
        if (DEBUG_LOG) {
            ARMS.LOGGER.debug("[MechaLogic] >> 进入 {}", node.getName());
        }
    }

    @Override
    public void onExit(StateNode node) {
        if (DEBUG_LOG) {
            ARMS.LOGGER.debug("[MechaLogic] << 离开 {}", node.getName());
        }
        super.onExit(node);
    }
}
