package io.github.sweetzonzi.arms_core.common.control.attr;

/**
 * 跳跃物理参数（硬编码，后续改为数据驱动）
 * <p>
 * 对应设计文档 `docs/跳跃-瞬时冲量持续助推设计.md` §1.2。
 * 跳跃以「瞬时冲量 + 持续助推窗口」建模：按下当步按 {@link #I_BASE} 施放冲量并离地，
 * 保持按住期间在窗口内持续施加 {@link #F_BOOST}，窗口硬上限为 {@link #T_BOOST_MAX}。
 * <p>
 * 注：{@link #I_BASE} / {@link #V_EXTEND} 为素体裸身值。安装助力腿后，
 * 由 {@code MechaCharacter} 子类覆写 {@code computeJumpImpulse} / {@code getBoostForce}
 * 等定制点叠加各腿参数。
 *
 * @author Sweetzonzi
 */
public final class MechaJumpAttr {

    private MechaJumpAttr() {
        throw new UnsupportedOperationException("常量类，不可实例化");
    }

    // ==========================================
    // 瞬时冲量（冲量相）
    // ==========================================

    /** 基础跳跃冲量 (N·s)，按下当步施放的动量输入 */
    public static final float I_BASE = 325.0f;

    // ==========================================
    // 持续助推窗口
    // ==========================================

    /** 窗口内的持续向上助推力 (N) */
    public static final float F_BOOST = 500.0f;

    /**
     * 助推窗口硬时长上限 (s)，与质量无关。
     * <p>
     * 取物理步长的整数倍（服务端 1/100 s、客户端约 1/60 s），避免最后一帧被截在半个步长上
     * 导致双端微不一致。0.20 s 在两端分别表现为 20 步与约 12 步。
     */
    public static final float T_BOOST_MAX = 0.20f;

    // ==========================================
    // 冲量相起跳速度上限
    // ==========================================

    /** 冲量相的起跳速度上限 (m/s)，只钳制冲量相的起跳速度，不参与助推窗口内的速度钳制 */
    public static final float V_EXTEND = 10.0f;
}