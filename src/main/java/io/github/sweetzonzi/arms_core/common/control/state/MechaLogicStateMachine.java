package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateGraphController;
import cn.solarmoon.spark_core.state_machine.graph.StateNode;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.preset.GaitSubGraphs;
import io.github.sweetzonzi.arms_core.common.control.state.preset.PostureLogicGraphs;
import io.github.sweetzonzi.arms_core.common.control.state.preset.VerticalSubGraphs;

import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;

/**
 * 机娘逻辑层控制器 —— 封装 posture 顶层状态机及其子控制器。
 *
 * <p>
 * 生命周期要求：构造完成后控制器处于 <b>stopped</b> 状态，必须先调用
 * {@link #reset()} 或 {@link #start()} 才能接收事件或推进。
 * </p>
 *
 * <p>
 * 子控制器激活/关闭机制（继承自 {@link StateGraphController}）：
 * <ol>
 *   <li>构造时传入全部子控制器到 {@code children} Map（均为 stopped 状态）</li>
 *   <li>posture 状态机进入某节点时，查找该节点声明的 {@code subGraphs} 键名</li>
 *   <li>匹配的 children 被 {@code start()} 并加入 {@code activeChildren}</li>
 *   <li>离开节点时，活跃子控全部 {@code stop()} + 清空</li>
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
 *   // 2. 启动（stopped → 进入初始节点）
 *   logic.reset();  // 或 start()
 *
 *   // 3. 每帧：写入快照 + KCC 状态到 variables
 *   logic.getVariables().set(ON_GROUND, kcc.onGround());
 *   logic.getVariables().set(HAS_INPUT, snapshot.inputForward != 0 || ...);
 *   // ...
 *
 *   // 4. 注入离散事件（broadcastEvent 沿活跃树递归传播）
 *   if (pendingEvents.contains(TOGGLE_SNEAK)) logic.broadcastEvent("sneak");
 *   if (pendingEvents.contains(TOGGLE_PRONE)) logic.broadcastEvent("prone");
 *   if (pendingEvents.contains(DODGE))        logic.broadcastEvent("dodge");
 *   if (pendingEvents.contains(TOGGLE_FLY))   logic.broadcastEvent("fly");
 *
 *   // 5. 驱动状态机（dt 为物理步长，驱动 stateTime / controllerTime 累积）
 *   logic.progress(dt);  // 递归驱动子控 + 自身 auto 转移
 *
 *   // 6. 读取产出变量
 *   Posture posture = logic.getVariables().get(POSTURE);
 *   Gait gait       = logic.getVariables().get(GAIT);
 *   Vertical vert   = logic.getVariables().get(VERTICAL);
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

    /**
     * 完成所有活跃子机和 posture 推进后，再合并本帧最终输入许可。
     *
     * @param dt 本帧物理步长 (s)，透传给基类用于 stateTime / controllerTime 累积
     */
    @Override
    public void progress(float dt) {
        super.progress(dt);
        updateInputPermissions();
    }

    /**
     * 合并 posture、gait 和 vertical 的输入许可。
     * 没有 vertical 子机的 posture 不读取上一个 vertical 子机遗留的输出。
     */
    private void updateInputPermissions() {
        StateVariableContainer variables = getVariables();
        Posture posture = variables.get(POSTURE);
        boolean postureAllowsMove = posture != Posture.RIDING && posture != Posture.RAGDOLL;
        boolean postureAllowsJump = posture == Posture.STAND;
        boolean hasVerticalMachine = posture == Posture.STAND || posture == Posture.AIR;

        boolean verticalAllowsMove = !hasVerticalMachine || variables.get(VERTICAL_CAN_MOVE);
        boolean verticalAllowsJump = !hasVerticalMachine || variables.get(VERTICAL_CAN_JUMP);

        variables.set(CAN_MOVE,
                postureAllowsMove && variables.get(GAIT_CAN_MOVE) && verticalAllowsMove);
        variables.set(CAN_JUMP,
                postureAllowsJump && variables.get(GAIT_CAN_JUMP) && verticalAllowsJump);
    }

    /** 当前逻辑状态是否允许水平移动。 */
    public boolean canMove() {
        return getVariables().get(CAN_MOVE);
    }

    /** 当前逻辑状态是否允许开始跳跃。 */
    public boolean canJump() {
        return getVariables().get(CAN_JUMP);
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
        // StateGraphController.start() 不可重载；在根节点 entry 后覆盖 start/reset 路径。
        updateInputPermissions();
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
