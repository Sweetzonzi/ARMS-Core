package io.github.sweetzonzi.arms_core.common.control.attr;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 控制器胶囊几何测试（临时权威取值）。
 * <p>
 * {@code ArmsCore} 的构造会从 Level 取物理空间，因此其构造路径需要真实 Level，不在单元测试
 * 范围内；本类覆盖它依赖的胶囊几何。该几何此前散落在 {@code ARMSClient} 的私有常量与
 * {@code MechaCharacter} 的构造式中，现已集中到 {@link MechaBodyPreset}，KCC 半高、
 * 服务端出生点与客户端锚点共用同一组值。
 *
 * @author Sweetzonzi
 */
class MechaBodyPresetTest {

    @Test
    void halfTotalMatchesCapsuleGeometry() {
        // 胶囊全高 = 圆柱段 + 2 × 半径；半高 = 全高 / 2
        assertEquals(1.2f, MechaBodyPreset.HALF_TOTAL, 1.0e-6f);
        assertEquals((MechaBodyPreset.CAPSULE_HEIGHT + 2f * MechaBodyPreset.CAPSULE_RADIUS) / 2f,
                MechaBodyPreset.HALF_TOTAL, 1.0e-6f);
    }

    @Test
    void newCapsuleShapeCarriesRadiusAndHeight() {
        var shape = MechaBodyPreset.newCapsuleShape();
        assertEquals(MechaBodyPreset.CAPSULE_RADIUS, shape.getRadius(), 1.0e-6f);
        assertEquals(MechaBodyPreset.CAPSULE_HEIGHT, shape.getHeight(), 1.0e-6f);
    }

    @Test
    void capsuleCenterIsFeetPlusHalfTotal() {
        float[] center = MechaBodyPreset.capsuleCenterFromFeet(10f, 64f, -3f);
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
