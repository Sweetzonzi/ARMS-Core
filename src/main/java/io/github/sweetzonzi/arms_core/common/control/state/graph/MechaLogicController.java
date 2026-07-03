package io.github.sweetzonzi.arms_core.common.control.state.graph;

import cn.solarmoon.spark_core.state_machine.graph.StateGraphController;
import cn.solarmoon.spark_core.state_machine.graph.StateNode;

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
 *   // 1. 构造
 *   MechaLogicController logic = new MechaLogicController();
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
public class MechaLogicController extends StateGraphController {

    /**
     * 构造逻辑层控制器，包含 posture 顶层状态机及全部子控。
     * <p>
     * 子控键名需与 {@link PostureLogicGraphs} 中各节点声明的 subGraphs 键名一致。
     */
    public MechaLogicController() {
        super(PostureLogicGraphs.GRAPH, Map.of(
                "stand_gait",  new StateGraphController(GaitSubGraphs.STAND),
                "stand_vert",  new StateGraphController(VerticalSubGraphs.STAND),
                "air_vert",    new StateGraphController(VerticalSubGraphs.AIR),
                "water_gait",  new StateGraphController(GaitSubGraphs.WATER),
                "crouch_gait", new StateGraphController(GaitSubGraphs.CROUCH),
                "prone_gait",  new StateGraphController(GaitSubGraphs.PRONE)
        ));
    }

    // ═══════════════════════════════════════════════
    // 调试日志（生产环境关闭）
    // ═══════════════════════════════════════════════

    private static final boolean DEBUG_LOG = false;

    @Override
    public void onTriggered(ActionEvent event, StateNode source, StateNode target) {
        if (DEBUG_LOG && target != null && event.getType() != null) {
            System.out.printf("[MechaLogic] %s --(%s)--> %s%n",
                    source != null ? source.getName() : "?",
                    event.getType(),
                    target.getName());
        }
    }

    @Override
    public void onEntry(StateNode node) {
        super.onEntry(node);
        if (DEBUG_LOG) {
            System.out.printf("[MechaLogic] >> 进入 %s%n", node.getName());
        }
    }

    @Override
    public void onExit(StateNode node) {
        if (DEBUG_LOG) {
            System.out.printf("[MechaLogic] << 离开 %s%n", node.getName());
        }
        super.onExit(node);
    }
}
