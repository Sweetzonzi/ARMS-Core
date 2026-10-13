package io.github.sweetzonzi.arms_core.common.control.attr;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 控制器碰撞体几何测试（临时权威取值）。
 * <p>
 * {@code ArmsCore} 的构造会从 Level 取物理空间，因此其构造路径需要真实 Level，不在单元测试
 * 范围内；本类覆盖它依赖的碰撞体几何。该几何此前散落在 {@code ARMSClient} 的私有常量与
 * {@code MechaCharacter} 的构造式中，现已集中到 {@link MechaBodyPreset}，半高、服务端出生点
 * 与客户端锚点共用同一组值。
 *
 * @author Sweetzonzi
 */
class MechaBodyPresetTest {

    @Test
    void halfTotalMatchesBodyGeometry() {
        // 圆柱底面是平面，因此半高恰好是全高的一半
        assertEquals(1.2f, MechaBodyPreset.HALF_TOTAL, 1.0e-6f);
        assertEquals(MechaBodyPreset.BODY_HEIGHT / 2f, MechaBodyPreset.HALF_TOTAL, 1.0e-6f);
    }

    @Test
    void newBodyShapeCarriesRadiusHeightAndAxis() {
        var shape = MechaBodyPreset.newBodyShape();
        // 圆柱形状不暴露 getRadius()：半径存在垂直于轴的那两个半长里
        var halfExtents = shape.getHalfExtents(new Vector3f());
        assertEquals(PhysicsSpace.AXIS_Y, shape.getAxis());
        assertEquals(MechaBodyPreset.RADIUS, halfExtents.x, 1.0e-6f);
        assertEquals(MechaBodyPreset.RADIUS, halfExtents.z, 1.0e-6f);
        assertEquals(MechaBodyPreset.BODY_HEIGHT, shape.getHeight(), 1.0e-6f);
    }

    @Test
    void postureShapeShrinksHeightAndKeepsAFlatBottom() {
        var crouch = MechaBodyPreset.newBodyShape(Posture.CROUCH);
        assertEquals(MechaBodyPreset.CROUCH_HEIGHT, crouch.getHeight(), 1.0e-6f);
        // 中心到底面的距离随高度减半，脚底因此停在与站立同一个基准上
        assertEquals(crouch.getHeight() / 2f, MechaBodyPreset.halfTotalFor(Posture.CROUCH), 1.0e-6f);
    }

    @Test
    void bodyCenterIsFeetPlusHalfTotal() {
        float[] center = MechaBodyPreset.controllerCenterFromFeet(10f, 64f, -3f);
        assertEquals(10f, center[0], 1.0e-6f);
        assertEquals(64f + MechaBodyPreset.HALF_TOTAL, center[1], 1.0e-6f);
        assertEquals(-3f, center[2], 1.0e-6f);
    }

    @Test
    void physicsSpaceIsAvailableInUnitTestRuntime() {
        // 与 MechaControlTest 同一前提：jme3 native 在 NeoForge unitTest 运行时可用
        assertNotNull(new PhysicsSpace(
                new Vector3f(-1f, -1f, -1f),
                new Vector3f(1f, 1f, 1f)));
    }
}
