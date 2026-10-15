package io.github.sweetzonzi.arms_core.common.control;

import io.github.sweetzonzi.arms_core.common.control.attr.MechAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 客户端（无身体）形态的 MechaControl 接线测试。
 * <p>
 * 覆盖双端化之后客户端侧的三条不变量与下行通道：
 * <ul>
 *   <li>没有控制器刚体、没有逻辑状态机，许可查询不再解引用空逻辑树；</li>
 *   <li>物理步在无身体时可以安全跑完（只跑下行写入与表现层推进）；</li>
 *   <li>四项与 one-hot 由下行通道写入，且写入发生在物理步帧首而不是发布当刻。</li>
 * </ul>
 * 这些用例不构造 PhysicsSpace，也不触碰 jme3 native：客户端形态本来就不建刚体。
 *
 * @author Sweetzonzi
 */
class MechaControlClientTest {

    private static final float DT = 1f / 60f;

    private final MechaControlHolder holder = new DummyHolder();

    @Test
    void clientInstanceHasNoBodyAndNoLogicTree() {
        MechaControl control = new MechaControl(holder, null);

        assertFalse(control.hasBody());
        assertNull(control.getBody());
        assertNull(control.getLogicStateMachine());
        assertFalse(control.canMove(), "没有逻辑树时不得解引用空引用");
        assertFalse(control.canJump());
    }

    @Test
    void downlinkStateReachesTheContainerOnlyOnTheNextPhysicsStep() {
        MechaControl control = new MechaControl(holder, null);

        control.applySyncedLogicState(new MechaControl.LogicStateSnapshot(
                Posture.CROUCH, Gait.SPRINT, Vertical.FALL, 42f));

        assertEquals(Posture.STAND, control.getCurrentPosture(),
                "发布当刻不得生效：写入点是物理步帧首");
        assertEquals(Gait.IDLE, control.getCurrentGait());

        control.onPhysicsStep(DT);

        assertEquals(Posture.CROUCH, control.getCurrentPosture());
        assertEquals(Gait.SPRINT, control.getCurrentGait());
        assertEquals(Vertical.FALL, control.snapshotLogicState().vertical());
        assertEquals(42f, control.snapshotLogicState().energy(), 1.0e-6f);
    }

    /** 客户端物理步不碰角色控制器，因此在没有身体时也必须安全。 */
    @Test
    void clientPhysicsStepSurvivesInputAndEventsWithoutABody() {
        MechaControl control = new MechaControl(holder, null);
        control.applyConditionSnapshot(MechaConditionSnapshot.builder()
                .inputForward(1f)
                .inputStrafe(-1f)
                .viewYaw(37f)
                .jumpPressed(true)
                .isDead(false)
                .build());
        control.postEvent(MechaEvent.DODGE);
        control.postEvent(MechaEvent.JUMP_RELEASE);

        assertDoesNotThrow(() -> control.onPhysicsStep(DT));
        assertDoesNotThrow(() -> control.onPhysicsStep(DT));
    }

    /** 同一个装配体的表现层编排器在客户端形态下也必须存在（动画推进住在它里面）。 */
    @Test
    void clientInstanceStillHasAPresentationOrchestrator() {
        MechaControl control = new MechaControl(holder, null);

        assertFalse(control.getPresentation().isStarted(),
                "尚未注入动画目标，表现层不应建立");
        assertEquals(0, control.getPresentation().getTargets().size());
        assertDoesNotThrow(() -> control.getPresentation().progress(DT));
    }

    private static final class DummyHolder implements MechaControlHolder {

        private MechaControl mechaControl;

        @Override
        public MechaControl getMechaControl() {
            return mechaControl;
        }

        @Override
        public SubPart getRootSubPart() {
            return null;
        }

        @Override
        public MechAttr getAttr() {
            return null;
        }
    }
}
