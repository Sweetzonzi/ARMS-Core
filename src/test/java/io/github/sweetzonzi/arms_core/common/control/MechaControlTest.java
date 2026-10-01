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

    private RecordingMechaCharacter kcc;
    private MechaControl control;

    @BeforeAll
    static void createSharedPhysicsSpace() {
        physicsSpace = new PhysicsSpace(
                new Vector3f(-1000f, -1000f, -1000f),
                new Vector3f(1000f, 1000f, 1000f));
    }

    @BeforeEach
    void setUp() {
        kcc = new RecordingMechaCharacter(new CapsuleCollisionShape(0.4f, 1.6f), physicsSpace);
        DummyHolder holder = new DummyHolder();
        control = new MechaControl(holder, kcc);
        holder.mechaControl = control;
    }

    private void applyInput(float forward, float strafe) {
        applyInputWithView(forward, strafe, 0f);
    }

    /** 写一份带视野偏航的快照；环境字段全部取默认值。 */
    private void applyInputWithView(float forward, float strafe, float viewYaw) {
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .inputForward(forward)
                .inputStrafe(strafe)
                .viewYaw(viewYaw)
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

    // ═══════════════════════════════════════════════
    // 朝向：视野偏航 → KCC
    // ═══════════════════════════════════════════════

    /** 快照里的视野偏航每帧绝对写到 KCC 的朝向上（覆盖，不累积）。 */
    @Test
    void viewYawIsWrittenToKccEveryFrame() {
        applyInputWithView(0f, 0f, 90f);
        control.frameLogic(DT);
        assertEquals((float) Math.toRadians(90f), kcc.getCurrentYaw(), 1.0e-5f);

        applyInputWithView(0f, 0f, -45f);
        control.frameLogic(DT);
        assertEquals((float) Math.toRadians(-45f), kcc.getCurrentYaw(), 1.0e-5f);
    }

    /**
     * 转视角后同一个物理步内移动方向就跟着转。
     * <p>
     * 这是「WASD 是当前朝向下的前后左右」的接线判据，同时钉住朝向只被计入一次：
     * 若变换在两层各做一次（等于按 yaw 的两倍旋转），yaw=90 的前进会落到 −Z 而不是 −X。
     */
    @Test
    void turningTheViewTurnsTheWalkDirectionInTheSameStep() {
        applyInputWithView(1f, 0f, 0f);
        control.frameLogic(DT);
        assertEquals(0f, kcc.getInputDirX(), 1.0e-6f);
        assertEquals(1f, kcc.getInputDirZ(), 1.0e-6f);

        applyInputWithView(1f, 0f, 90f);
        control.frameLogic(DT);
        assertEquals((float) Math.toRadians(90f), kcc.getCurrentYaw(), 1.0e-5f);
        assertEquals(-1f, kcc.getInputDirX(), 1.0e-5f, "前进应沿新朝向落在 −X");
        assertEquals(0f, kcc.getInputDirZ(), 1.0e-5f);
    }

    /** 死亡（含 ragdoll）时朝向冻结在最后一帧：此后到达的新视角不会被写入。 */
    @Test
    void deadControllerKeepsLastFacing() {
        applyInputWithView(0f, 0f, 90f);
        control.frameLogic(DT);
        assertEquals((float) Math.toRadians(90f), kcc.getCurrentYaw(), 1.0e-5f);

        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .viewYaw(180f)
                .isDead(true)
                .build());
        control.frameLogic(DT);

        assertEquals((float) Math.toRadians(90f), kcc.getCurrentYaw(), 1.0e-5f,
                "死亡后朝向应停在最后一帧");
    }

    /** 移动许可不门控朝向：硬直（CAN_MOVE=false）中仍能转身。 */
    @Test
    void facingFollowsViewEvenWhenMovementIsDenied() {
        control.setBypassObservation(false);

        applyInputWithView(1f, 0f, 90f);
        control.postEvent(MechaEvent.STUN);
        control.frameLogic(DT);

        assertFalse(control.canMove(), "stun 应禁止水平移动");
        assertFalse(kcc.isInputHasMove(), "禁止移动时移动意图被清零");
        assertEquals((float) Math.toRadians(90f), kcc.getCurrentYaw(), 1.0e-5f,
                "朝向不受 CAN_MOVE 门控");
    }

    @Test
    void bypassObservationPassesJumpRawThrough() {
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .jumpPressed(true)
                .build());
        control.frameLogic(DT);

        assertTrue(kcc.isJumpHeld());
    }

    // ═══════════════════════════════════════════════
    // 跳跃松开边沿：只走事件 latch，且恰好消费一次
    // ═══════════════════════════════════════════════

    /** 快照只表达"按住"：没有任何 JUMP_RELEASE 事件时，KCC 收不到松开边沿。 */
    @Test
    void jumpHeldAloneDoesNotProduceAReleaseEdge() {
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .jumpPressed(true)
                .build());

        control.frameLogic(DT);
        control.frameLogic(DT);

        assertTrue(kcc.isJumpHeld());
        assertEquals(0, kcc.releaseCount);
    }

    /**
     * 一次 {@link MechaEvent#JUMP_RELEASE} 只 latch 一帧。
     * <p>
     * 这条边沿若跨帧重复送达，就会在"松键后很快再按"时把刚开始的蓄力提前放掉
     * （蓄力比接近 0 的弱跳），因此必须钉住"恰好一次"。
     */
    @Test
    void jumpReleaseEventLatchesExactlyOnce() {
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .jumpPressed(false)
                .build());
        control.postEvent(MechaEvent.JUMP_RELEASE);

        control.frameLogic(DT);
        assertTrue(kcc.lastReleased, "收到事件的物理帧应把松开边沿转发给 KCC");

        control.frameLogic(DT);
        assertFalse(kcc.lastReleased, "边沿不得跨帧重复 latch");
        assertEquals(1, kcc.releaseCount);
    }

    /** 反向控制模式下 CAN_JUMP=false 只拦"开始蓄力"，不能吞掉已经开始蓄力的释放边沿。 */
    @Test
    void gatedModeStillForwardsReleaseWhileCharging() {
        control.setBypassObservation(false);
        kcc.chargingForTest = true;

        control.postEvent(MechaEvent.JUMP_RELEASE);
        control.frameLogic(DT);

        assertTrue(kcc.lastReleased);
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

    /**
     * 记录 {@code setJumpInput} 收到了什么的 KCC。
     * <p>
     * 只覆写输入入口与蓄力镜像，不触发物理积分，因此本类不需要在物理空间里真实步进
     * （地面射线检测属 {@code MechaCharacter} 自身的测试范围）。
     */
    private static final class RecordingMechaCharacter extends MechaCharacter {

        /** 最近一次 {@code setJumpInput} 收到的松开标记 */
        private boolean lastReleased;
        /** 收到松开标记的次数 */
        private int releaseCount;
        /** 覆写 {@code isChargingJump()} 的返回值，用于构造"已经在蓄力"的门控场景 */
        private boolean chargingForTest;

        RecordingMechaCharacter(CapsuleCollisionShape shape, PhysicsSpace space) {
            super(shape, space);
        }

        @Override
        public void setJumpInput(boolean held, boolean released) {
            this.lastReleased = released;
            if (released) {
                this.releaseCount++;
            }
            super.setJumpInput(held, released);
        }

        @Override
        public boolean isChargingJump() {
            return chargingForTest;
        }
    }

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
