package io.github.sweetzonzi.arms_core.common.control;

/**
 * 跳跃物理参数（硬编码，后续改为数据驱动）
 * <p>
 * 对应设计文档 §4.2、§4.5、§5.1。
 * 跳跃以冲量建模，支持蓄力机制与多腿叠加。
 * <p>
 * 注：I_MIN / I_MAX / V_EXTEND 为素体裸身值。安装助力腿后，
 * 由 {@code MechaController} 子类覆写 {@code computeJumpImpulse} 等定制点叠加各腿参数。
 *
 * @author Sweetzonzi
 */
public final class MechaJumpAttr {

    private MechaJumpAttr() {
        throw new UnsupportedOperationException("常量类，不可实例化");
    }

    // ==========================================
    // §5.1 素体跳跃参数
    // ==========================================

    /** 轻拍跳跃冲量 (N·s)，轻按跳跃键时的最小冲量 */
    public static final float I_MIN = 325.0f;

    /** 满蓄跳跃冲量 (N·s)，蓄满后施放的最大冲量 */
    public static final float I_MAX = 500.0f;

    /** 满蓄时间 (s)，从轻拍到满蓄所需的按住时长 */
    public static final float T_CHARGE = 1.0f;

    // ==========================================
    // §4.5 起跳速度硬上限
    // ==========================================

    /** 最大伸展速度 (m/s)，§4.5 v_extend — 单腿可贡献的起跳速度硬上限，取最快腿 */
    public static final float V_EXTEND = 10.0f;

    // ==========================================
    // §4.4 蓄力期间行为参数
    // ==========================================

    /**
     * 蓄力时行走速度最大折减比 (0~1)。
     * 满蓄时行走速度降至 (1 - CHARGE_WALK_PENALTY) 倍。
     */
    public static final float CHARGE_WALK_PENALTY = 0.5f;
}
