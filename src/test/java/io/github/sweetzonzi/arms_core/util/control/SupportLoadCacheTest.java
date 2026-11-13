package io.github.sweetzonzi.arms_core.util.control;

import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SupportLoadCache} 的判据：一步存活、零冲量即无样本、落地峰值的压缩链。
 *
 * <p>样本值取 {@code src/test/java/io/github/sweetzonzi/arms_core/common/control/GroundDriveRefactorMeasurementTest.java#measureSupportImpulsePhases}
 * 的实测读数（落地步 43.8619 N·s、静置 6.8670 N·s），因此这里量的不是假设值。
 *
 * @author Sweetzonzi
 */
class SupportLoadCacheTest {

    /** 实测静置值：70 kg × 9.81 m/s² × 0.01 s。 */
    private static final double STATIC_IMPULSE = 6.8670;

    /** 实测落地峰值（同一条用例的第 6 步）。 */
    private static final double LANDING_IMPULSE = 43.8619;

    private static final float DT = 0.01f;

    private static final double GRAVITY_LOAD = MechaWalkingAttr.MASS * MechaWalkingAttr.GRAVITY;

    @Test
    void freshCacheFallsBackToGravity() {
        SupportLoadCache cache = new SupportLoadCache();
        assertFalse(cache.hasSample(), "新建的缓存没有样本");
        assertEquals(GRAVITY_LOAD, cache.rawLoad(GRAVITY_LOAD), 1e-6, "没有样本时取静态重力载荷");
        assertEquals(0.0, cache.impulse(), 1e-9);
    }

    @Test
    void sampleSurvivesExactlyOneStep() {
        SupportLoadCache cache = new SupportLoadCache();
        cache.sample(LANDING_IMPULSE, DT);
        assertTrue(cache.hasSample(), "写入后立即可用");

        cache.advance();
        assertTrue(cache.hasSample(), "滞后一步的样本仍在本步可用");
        assertEquals(LANDING_IMPULSE / DT, cache.rawLoad(GRAVITY_LOAD), 1e-6, "载荷是 J/dt");

        cache.advance();
        assertFalse(cache.hasSample(), "再走一步即过期");
        assertEquals(GRAVITY_LOAD, cache.rawLoad(GRAVITY_LOAD), 1e-6, "过期后回落静态估算");
    }

    @Test
    void zeroImpulseIsNoSample() {
        SupportLoadCache cache = new SupportLoadCache();
        cache.sample(STATIC_IMPULSE, DT);
        cache.sample(0.0, DT);
        assertFalse(cache.hasSample(), "求解出的和为零说明本步没有承重，不能当载荷");
        assertEquals(GRAVITY_LOAD, cache.rawLoad(GRAVITY_LOAD), 1e-6);
    }

    @Test
    void nonFiniteOrNonPositiveInputsAreIgnored() {
        SupportLoadCache cache = new SupportLoadCache();
        cache.sample(Double.NaN, DT);
        assertFalse(cache.hasSample());
        cache.sample(STATIC_IMPULSE, 0f);
        assertFalse(cache.hasSample(), "步长为 0 不能做除法");
        cache.sample(-1.0, DT);
        assertFalse(cache.hasSample());
    }

    @Test
    void invalidateDropsTheSample() {
        SupportLoadCache cache = new SupportLoadCache();
        cache.sample(STATIC_IMPULSE, DT);
        cache.invalidate();
        assertFalse(cache.hasSample());
        assertEquals(GRAVITY_LOAD, cache.rawLoad(GRAVITY_LOAD), 1e-6);
    }

    /**
     * 落地峰值走完整条链：{@code 43.8619 N·s} 的样本经软压缩后只批出
     * {@code 25.30 N·s} 的附着预算，而不是原始的 43.86 N·s。
     */
    @Test
    void landingSpikeIsCompressedIntoBudget() {
        SupportLoadCache cache = new SupportLoadCache();
        cache.sample(LANDING_IMPULSE, DT);

        double raw = cache.rawLoad(GRAVITY_LOAD);
        assertEquals(LANDING_IMPULSE / DT, raw, 1e-6, "原始载荷是冲量除以样本自己那一步的步长");

        double effective = ContactBudget.softCompress(raw, GRAVITY_LOAD,
                MechaWalkingAttr.LOAD_KNEE, MechaWalkingAttr.LOAD_LOG_SCALE);
        // x = 6.3873 → h = 2 + ln(1 + 4.3873) = 3.6840
        assertEquals(3.6840, effective / GRAVITY_LOAD, 1e-3, "软压缩后是 3.68 倍静态载荷");
        assertEquals(25.30, ContactBudget.adhesionImpulse(1.0, effective, DT), 0.02,
                "μ=1 时 3.68 倍载荷给出 25.30 N·s 的预算");

        double staticBudget = ContactBudget.adhesionImpulse(1.0, GRAVITY_LOAD, DT);
        assertEquals(STATIC_IMPULSE, staticBudget, 1e-3, "静态载荷的预算就是 m·g·dt");
        assertTrue(ContactBudget.adhesionImpulse(1.0, effective, DT) > 3.0 * staticBudget,
                "落地步的预算应明显高于静置（3 倍以上）");
    }
}
