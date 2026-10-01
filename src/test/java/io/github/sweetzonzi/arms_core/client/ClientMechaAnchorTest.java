package io.github.sweetzonzi.arms_core.client;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClientMechaAnchor} 的插值测试。
 * <p>
 * 这一段是纯逻辑，但它是客户端唯一的位姿平滑手段，错了的表现是「锚点抖动」或
 * 「传送后拖尾跨越地图」，靠肉眼在游戏里很难区分是插值错了还是同步错了。
 *
 * @author Sweetzonzi
 */
class ClientMechaAnchorTest {

    private ClientMechaAnchor anchor;

    @BeforeEach
    void setUp() {
        anchor = new ClientMechaAnchor();
    }

    @Test
    void firstSampleLandsWithoutInterpolation() {
        anchor.accept(new Vector3f(1f, 2f, 3f), 45f, 100L);

        assertTrue(anchor.hasSample());
        // 单采样时没有前值，任何插值系数都应返回该采样本身
        Vector3f out = new Vector3f();
        anchor.lerpPosition(100L, 0f, out);
        assertEquals(1f, out.x, 1e-6f);
        assertEquals(45f, anchor.lerpYaw(100L, 0f), 1e-6f);
    }

    @Test
    void secondSampleInterpolatesBetweenTheTwo() {
        // 位移取 2 m：小于 TELEPORT_DISTANCE(8)，因此是可插值的常规运动
        anchor.accept(new Vector3f(0f, 0f, 0f), 0f, 100L);
        anchor.accept(new Vector3f(2f, 0f, 0f), 90f, 101L);
        // 间隔 1 tick。刚采到新值时进度为 0 → 位于旧值
        Vector3f out = new Vector3f();
        anchor.lerpPosition(101L, 0f, out);
        assertEquals(0f, out.x, 1e-5f);
        // 进度过半 → 位于中点
        anchor.lerpPosition(101L, 0.5f, out);
        assertEquals(1f, out.x, 1e-4f);
        // 下一 tick 到来前 → 位于新值
        anchor.lerpPosition(102L, 0f, out);
        assertEquals(2f, out.x, 1e-4f);
    }

    @Test
    void interpolationDoesNotExtrapolateBeyondTheLatestSample() {
        // 位移 1 m，在可插值范围内
        anchor.accept(new Vector3f(0f, 0f, 0f), 0f, 100L);
        anchor.accept(new Vector3f(1f, 0f, 0f), 0f, 101L);

        // 长时间没有新包：进度被钳制在 1，停在最新采样上，不外推
        Vector3f out = new Vector3f();
        anchor.lerpPosition(200L, 0.9f, out);
        assertEquals(1f, out.x, 1e-5f);
    }

    @Test
    void distantSampleJumpsInsteadOfInterpolating() {
        anchor.accept(new Vector3f(0f, 0f, 0f), 0f, 100L);
        // 距离远超 TELEPORT_DISTANCE(8)：应判为传送，直接跳变
        anchor.accept(new Vector3f(500f, 0f, 0f), 0f, 101L);

        Vector3f out = new Vector3f();
        anchor.lerpPosition(101L, 0f, out);
        assertEquals(500f, out.x, 1e-5f);
    }

    @Test
    void staleSamplesStopInterpolating() {
        anchor.accept(new Vector3f(0f, 0f, 0f), 0f, 0L);
        // 间隔 10 tick 超过 MAX_INTERPOLATION_TICKS：不再插值
        anchor.accept(new Vector3f(1f, 0f, 0f), 0f, 10L);

        Vector3f out = new Vector3f();
        anchor.lerpPosition(10L, 0f, out);
        assertEquals(1f, out.x, 1e-5f);
    }

    @Test
    void yawInterpolatesAlongTheShortestArc() {
        anchor.accept(new Vector3f(), 350f, 100L);
        anchor.accept(new Vector3f(), 10f, 101L);

        // 350° → 10° 的最短路径是 +20°，中点应是 0°（而不是绕一圈的 180°）
        float mid = anchor.lerpYaw(101L, 0.5f);
        assertEquals(0f, mid, 1e-3f);
    }

    @Test
    void resetClearsSample() {
        anchor.accept(new Vector3f(1f, 1f, 1f), 30f, 5L);
        anchor.reset();
        assertFalse(anchor.hasSample());
        assertEquals(0f, anchor.current().x, 1e-6f);
    }
}
