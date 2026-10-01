package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import lombok.Getter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.ENERGY;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.GAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MechaControl 接线测试：验证旁路观测/反向控制门控、事件 latch、ENERGY 初始化。
 * <p>
 * 只调用 {@code frameLogic}（不触碰 KCC 物理积分与 native rayTest），
 * 但 MechaCharacter / PhysicsSpace 构造本身需要 jme3 native 库可加载
 * （NeoForge unitTest 运行时提供）。
 *
 * @author Sweetzonzi
 */
class MechaControlTest {

    private static final float DT = 1f / 20f;

    /** 测试共享物理空间（frameLogic 不实际使用，仅满足 KCC 构造） */
    private static PhysicsSpace physicsSpace;

    private MechaCharacter kcc;
    private MechaControl control;

    @BeforeAll
    static void createSharedPhysicsSpace() {
        physicsSpace = new PhysicsSpace(
                new Vector3f(-1000f, -1000f, -1000f),
                new Vector3f(1000f, 1000f, 1000f));
    }

    @BeforeEach
    void setUp() {
        kcc = new MechaCharacter(new CapsuleCollisionShape(0.4f, 1.6f), physicsSpace);
        DummyHolder holder = new DummyHolder();
        control = new MechaControl(holder, kcc);
        holder.mechaControl = control;
    }

    private void applyInput(float forward, float strafe) {
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .inputForward(forward)
                .inputStrafe(strafe)
                .viewYaw(0f)
                .viewPitch(0f)
                .build());
    }

    // ═══════════════════════════════════════════════
    // ENERGY 初始化（item 3）
    // ═══════════════════════════════════════════════

    @Test
    void energyIsInitializedToFullSoSprintWorksOutOfTheBox() {
        StateVariableContainer vars = control.getLogicStateMachine().getVariables();
        assertEquals(MechaControl.INITIAL_ENERGY, vars.get(ENERGY));
        assertTrue(vars.get(ENERGY) > 0f);
    }

    // ═══════════════════════════════════════════════
    // 旁路观测 / 反向控制门控
    // ═══════════════════════════════════════════════

    @Test
    void bypassObservationIsDefaultAndPassesRawInputThrough() {
        assertTrue(control.isBypassObservation());

        applyInput(1f, 0f);
        control.frameLogic(DT);

        assertTrue(kcc.isInputHasMove());
        // yaw=0：前进 = (-sin0, cos0) = (0, 1)
        assertEquals(0f, kcc.getInputDirX(), 1e-6f);
        assertEquals(1f, kcc.getInputDirZ(), 1e-6f);
    }

    @Test
    void strafeLeftMapsToEastWhenFacingSouth() {
        // yaw=0（面向南 +Z），str 正=左移：按左（str=+1）→ 东（+X）
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .inputForward(0f)
                .inputStrafe(1f)
                .viewYaw(0f)
                .viewPitch(0f)
                .build());
        control.frameLogic(DT);

        assertEquals(1f, kcc.getInputDirX(), 1e-6f);
        assertEquals(0f, kcc.getInputDirZ(), 1e-6f);
    }

    @Test
    void forwardMapsToWestWhenFacingWest() {
        // yaw=90（面向西 -X）：前进 → 西（-X）
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .inputForward(1f)
                .inputStrafe(0f)
                .viewYaw(90f)
                .viewPitch(0f)
                .build());
        control.frameLogic(DT);

        assertEquals(-1f, kcc.getInputDirX(), 1e-6f);
        assertEquals(0f, kcc.getInputDirZ(), 1e-6f);
    }

    @Test
    void strafeLeftMapsToSouthWhenFacingWest() {
        // yaw=90（面向西 -X）：按左（str=+1）→ 南（+Z）
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .inputForward(0f)
                .inputStrafe(1f)
                .viewYaw(90f)
                .viewPitch(0f)
                .build());
        control.frameLogic(DT);

        assertEquals(0f, kcc.getInputDirX(), 1e-6f);
        assertEquals(1f, kcc.getInputDirZ(), 1e-6f);
    }

    @Test
    void bypassObservationPassesJumpRawThrough() {
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .jumpPressed(true)
                .jumpReleased(false)
                .build());
        control.frameLogic(DT);

        assertTrue(kcc.isJumpHeld());
    }

    @Test
    void gatedModeAllowsMovementWhenStateAllows() {
        control.setBypassObservation(false);

        applyInput(1f, 0f);
        control.frameLogic(DT);

        assertTrue(kcc.isInputHasMove());
    }

    @Test
    void gatedModeBlocksMovementWhenStateDenies() {
        control.setBypassObservation(false);

        // 死亡 → ragdoll（终态）→ CAN_MOVE=false → 移动输入清零
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .inputForward(1f)
                .inputStrafe(0f)
                .viewYaw(0f)
                .viewPitch(0f)
                .isDead(true)
                .build());
        control.frameLogic(DT);

        assertFalse(kcc.isInputHasMove());
    }

    // ═══════════════════════════════════════════════
    // 事件 latch（跨线程投递 → 帧首消费）
    // ═══════════════════════════════════════════════

    @Test
    void postEventReachesStateMachineInNextFrame() {
        control.postEvent(MechaEvent.DODGE);
        control.frameLogic(DT);

        assertEquals(Gait.DODGE, control.getLogicStateMachine().getVariables().get(GAIT));
    }

    @Test
    void eventsAreDrainedAfterConsumption() {
        control.postEvent(MechaEvent.DODGE);
        control.frameLogic(DT);
        assertEquals(Gait.DODGE, control.getLogicStateMachine().getVariables().get(GAIT));

        // 下一帧无事件：dodge 因时长未到继续保持，不会因事件重复触发
        control.frameLogic(DT);
        assertEquals(Gait.DODGE, control.getLogicStateMachine().getVariables().get(GAIT));
        assertFalse(control.hasPendingEvent(MechaEvent.DODGE));
    }

    @Test
    void concurrentEventPostsAreNotLost() throws Exception {
        int threadCount = 4;
        int postsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < postsPerThread; i++) {
                    control.postEvent(MechaEvent.DODGE);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        control.frameLogic(DT);

        // 事件去重后至少保留一个，且必须被消费到状态机
        assertEquals(Gait.DODGE, control.getLogicStateMachine().getVariables().get(GAIT));

        // 事件不残留到下一帧：再进一帧后本帧批应为空
        control.frameLogic(DT);
        assertFalse(control.hasPendingEvent(MechaEvent.DODGE));
    }

    // ═══════════════════════════════════════════════
    // 测试 Holder
    // ═══════════════════════════════════════════════

    private static final class DummyHolder implements MechaControlHolder {
        @Getter
        private MechaControl mechaControl;

        @Override
        public SubPart getRootSubPart() {
            return null;
        }

        @Override
        public MechAttr getAttr() {
            return new MechAttr();
        }
    }
}
