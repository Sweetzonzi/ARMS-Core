package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.animation.IAnimatable;
import cn.solarmoon.spark_core.animation.state.MultiAnimStateMachine;
import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.molang.SparkMolangContext;
import cn.solarmoon.spark_core.state_machine.graph.StateGraphController;
import cn.solarmoon.spark_core.state_machine.graph.StateMachineGraph;
import cn.solarmoon.spark_core.state_machine.graph.StateNode;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import io.github.sweetzonzi.arms_core.common.control.state.graph.MechaStateActions;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四台姿态表现层状态机的转移测试。
 * <p>
 * 用例完全脱离物理与资源：{@code animTargets} 传空列表，因此 {@code MechaPlayAnimAction} 的循环
 * 零次迭代，不会去建 {@code AnimInstance}（那需要真实的动画集与物理级别）。被验证的因此是
 * 「给定变量组合走不走得到预期节点」这一层。
 * <p>
 * 变量用生产口径写入（{@link MechaStateActions} 的容器级入口），不自己拼 one-hot，
 * 这样测试条件语义与逻辑层写出的 one-hot 完全同源。
 *
 * @author Sweetzonzi
 */
class MechaPresentationGraphsTest {

    private static final float DT = 1f / 60f;

    private StateVariableContainer variables;
    private GameplayTagContainer tags;

    // ==========================================
    // 构造与变量写入
    // ==========================================

    private MultiAnimStateMachine start(StateMachineGraph graph) {
        variables = new StateVariableContainer();
        tags = new GameplayTagContainer();
        return newMachine(graph);
    }

    private MultiAnimStateMachine newMachine(StateMachineGraph graph) {
        Function0<SparkMolangContext<?>> provider = new Function0<>() {
            @Override
            public SparkMolangContext<?> invoke() {
                throw new IllegalStateException("测试用的四台图不使用 MoLang 条件");
            }
        };
        MultiAnimStateMachine machine = new MultiAnimStateMachine(
                graph,
                List.<IAnimatable<?>>of(),
                provider,
                null,
                null,
                MechaAnimGroups.LOCOMOTION,
                Map.<String, StateGraphController>of(),
                variables,
                tags);
        machine.reset();
        return machine;
    }

    private void setPosture(Posture posture) {
        MechaStateActions.writePostureFlags(variables, posture);
    }

    private void setGait(Gait gait) {
        MechaStateActions.writeGaitPreservingFlags(variables, gait);
    }

    private void setVertical(Vertical vertical) {
        MechaStateActions.writeVerticalFlags(variables, vertical);
    }

    private static String nodeOf(MultiAnimStateMachine machine) {
        return machine.getCurrentNode().getName();
    }

    // ==========================================
    // STAND
    // ==========================================

    @Test
    void standStartsInactiveAndEntersIdleThroughTheFallbackEdge() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.STAND);
        assertEquals(MechaPresentationGraphs.INACTIVE, nodeOf(machine));

        setPosture(Posture.STAND);
        setGait(Gait.IDLE);
        machine.progress(DT);

        assertEquals("idle", nodeOf(machine));
    }

    /**
     * 兜底边的用途：姿态刚变到 STAND 而 gait 是 DRIFT（典型是空中落地那一刻），
     * 没有任何具名分支成立，机器仍要落到有动画的节点而不是停在待命态。
     */
    @Test
    void standFallbackEdgeCatchesAnUnmappedGait() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.STAND);

        setPosture(Posture.STAND);
        setGait(Gait.DRIFT);
        machine.progress(DT);

        assertEquals("idle", nodeOf(machine));
    }

    @Test
    void standSelectsIdleMoveAndSprintByGait() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.STAND);
        setPosture(Posture.STAND);

        setGait(Gait.JOG);
        machine.progress(DT);
        assertEquals("move", nodeOf(machine));

        setGait(Gait.SPRINT);
        machine.progress(DT);
        assertEquals("sprint", nodeOf(machine));

        setGait(Gait.CREEP);
        machine.progress(DT);
        assertEquals("move", nodeOf(machine), "creep 与 jog 同属 move 节点");

        setGait(Gait.IDLE);
        machine.progress(DT);
        assertEquals("idle", nodeOf(machine));
    }

    /**
     * 冲刺松手后 run 继续播：DRIFT 期间 sprint 节点没有出边。
     * <p>
     * 减速收尾由 gait 子机的滞回结束（DRIFT 在速度降到 STOP_SPEED_THRESHOLD 以下时回 IDLE），
     * 表现层只跟着新的 gait 走。
     */
    @Test
    void standKeepsSprintAnimationDuringDrift() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.STAND);
        setPosture(Posture.STAND);

        setGait(Gait.SPRINT);
        machine.progress(DT);
        assertEquals("sprint", nodeOf(machine));

        setGait(Gait.DRIFT);
        machine.progress(DT);
        assertEquals("sprint", nodeOf(machine), "DRIFT 期间不得切回 idle");

        setGait(Gait.IDLE);
        machine.progress(DT);
        assertEquals("idle", nodeOf(machine), "滞回结束（gait 回 IDLE）后才切节点");
    }

    /**
     * 受击、闪避、重落地这三条 gait 不设节点也不设出边：所在节点没有任何出边成立，
     * 机器停在进入前的节点继续播循环动画。
     */
    @Test
    void standUnmappedGaitKeepsThePreviousNode() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.STAND);
        setPosture(Posture.STAND);
        setGait(Gait.JOG);
        machine.progress(DT);
        assertEquals("move", nodeOf(machine));

        for (Gait unmapped : List.of(Gait.DODGE, Gait.STUN, Gait.HARD_LAND)) {
            setGait(unmapped);
            machine.progress(DT);
            assertEquals("move", nodeOf(machine), unmapped + " 期间应沿用进入前的节点");
        }
    }

    @Test
    void standReturnsToInactiveWhenThePostureChanges() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.STAND);
        setPosture(Posture.STAND);
        setGait(Gait.JOG);
        machine.progress(DT);
        assertEquals("move", nodeOf(machine));

        setPosture(Posture.AIR);
        setVertical(Vertical.FALL);
        machine.progress(DT);

        assertEquals(MechaPresentationGraphs.INACTIVE, nodeOf(machine));
    }

    // ==========================================
    // AIR
    // ==========================================

    @Test
    void airSelectsByVertical() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.AIR);
        assertEquals(MechaPresentationGraphs.INACTIVE, nodeOf(machine));

        setPosture(Posture.AIR);
        setVertical(Vertical.FALL);
        machine.progress(DT);
        assertEquals("fall", nodeOf(machine));

        setVertical(Vertical.JUMP_BOOST);
        machine.progress(DT);
        assertEquals("fall", nodeOf(machine), "助推与下落合并到同一节点");

        setVertical(Vertical.GLIDE);
        machine.progress(DT);
        assertEquals("glide", nodeOf(machine));

        setVertical(Vertical.HOVER);
        machine.progress(DT);
        assertEquals("fly", nodeOf(machine));

        setVertical(Vertical.FLY);
        machine.progress(DT);
        assertEquals("fly", nodeOf(machine));

        setPosture(Posture.STAND);
        setVertical(Vertical.GROUND);
        machine.progress(DT);
        assertEquals(MechaPresentationGraphs.INACTIVE, nodeOf(machine));
    }

    // ==========================================
    // WATER / CROUCH
    // ==========================================

    @Test
    void waterSelectsFloatOrSwimByIdleFlag() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.WATER);

        setPosture(Posture.WATER);
        setGait(Gait.IDLE);
        machine.progress(DT);
        assertEquals("float", nodeOf(machine));

        setGait(Gait.DRIFT);
        machine.progress(DT);
        assertEquals("swim", nodeOf(machine), "水中 DRIFT 也算「还在动」");

        setPosture(Posture.STAND);
        machine.progress(DT);
        assertEquals(MechaPresentationGraphs.INACTIVE, nodeOf(machine));
    }

    @Test
    void crouchSelectsIdleOrMoveByIdleFlag() {
        MultiAnimStateMachine machine = start(MechaPresentationGraphs.CROUCH);

        setPosture(Posture.CROUCH);
        setGait(Gait.IDLE);
        machine.progress(DT);
        assertEquals("idle", nodeOf(machine));

        setGait(Gait.CREEP);
        machine.progress(DT);
        assertEquals("move", nodeOf(machine));
    }

    // ==========================================
    // 四台互斥
    // ==========================================

    @Test
    void onlyTheMachineOfTheCurrentPostureLeavesInactive() {
        variables = new StateVariableContainer();
        tags = new GameplayTagContainer();
        List<MultiAnimStateMachine> machines = new ArrayList<>();
        for (StateMachineGraph graph : MechaPresentationGraphs.all()) {
            machines.add(newMachine(graph));
        }

        setPosture(Posture.STAND);
        setGait(Gait.JOG);
        setVertical(Vertical.GROUND);
        for (MultiAnimStateMachine machine : machines) {
            machine.progress(DT);
        }

        assertEquals("move", nodeOf(machines.get(0)), "STAND 机器应进入 move");
        for (int i = 1; i < machines.size(); i++) {
            assertEquals(MechaPresentationGraphs.INACTIVE, nodeOf(machines.get(i)),
                    "非当前姿态的第 " + i + " 台机器必须留在待命态");
        }
    }

    // ==========================================
    // 图与映射表的一致性
    // ==========================================

    /**
     * 每个活跃态都必须有动画映射、且恰好一个进入动作；待命态必须没有进入动作。
     * <p>
     * 这条挡住的是「节点名与映射表的键写岔了」——那种错不会报任何异常，只是该姿态没有动画。
     */
    @Test
    void everyActiveNodeHasAnAnimationMappingAndExactlyOneEntryAction() {
        assertAnimated(MechaPresentationGraphs.STAND, Posture.STAND);
        assertAnimated(MechaPresentationGraphs.AIR, Posture.AIR);
        assertAnimated(MechaPresentationGraphs.WATER, Posture.WATER);
        assertAnimated(MechaPresentationGraphs.CROUCH, Posture.CROUCH);
    }

    private static void assertAnimated(StateMachineGraph graph, Posture posture) {
        List<StateNode> nodes = new ArrayList<>(graph.getNodes());
        nodes.add(graph.getInitialNode());
        for (StateNode node : nodes) {
            String where = posture + " 的节点 " + node.getName();
            if (MechaPresentationGraphs.INACTIVE.equals(node.getName())) {
                assertTrue(node.getOnEntry().isEmpty(), where + " 是待命态，不应有进入动作");
                continue;
            }
            assertNotNull(MechaAnimPreset.animationOf(posture, node.getName()),
                    where + " 没有动画映射");
            assertEquals(1, node.getOnEntry().size(), where + " 应有且仅有一个播放动作");
        }
    }
}
