package io.github.sweetzonzi.arms_core.common.control;

/**
 * 行走物理参数（硬编码，后续改为数据驱动）
 * <p>
 * 对应设计文档 §5.1 素体级参数。
 * 全部采用国际单位制。
 *
 * @author Sweetzonzi
 */
public final class MechaWalkingAttr {

    private MechaWalkingAttr() {
        throw new UnsupportedOperationException("常量类，不可实例化");
    }

    // ==========================================
    // §5.1 素体级参数
    // ==========================================

    /** 控制器质量 (kg)，对应设计文档 §1.5 — 等于机体总质量，仅用于手动积分 a=F/m */
    public static final float MASS = 70.0f;

    /** 素体本体肌肉功率 (W)，§5.1 P_base */
    public static final float P_BASE = 800.0f;

    /** 期望裸机行走速度 (m/s)，§5.1 v_ref — 仅用于导出内阻系数 c₀，不参与力-速曲线 */
    public static final float V_REF = 6.0f;

    /** 本体额定速度 (m/s)，§5.1 v_rated — 力-速曲线的超速衰减起点 */
    public static final float V_RATED = 6.0f;

    /** 本体最大出力 (N)，§5.1 F_max — 低速区间力上限 */
    public static final float F_MAX = 700.0f;

    /** 裸足静摩擦系数，§5.1 μ_naked — 无助力腿时兜底 */
    public static final float MU_NAKED = 1.0f;

    /** 粘滞阻尼系数，§5.1 c₁ — 一阶速度衰减，0=无阻尼。由手动积分自处理，KCC 内部置 0 */
    public static final float C1 = 0.0f;

    /** 超速衰减陡度 (m/s)，§5.1 λ — 越大衰减越缓 */
    public static final float LAMBDA = 2.0f;

    /** 空中控制力衰减因子，§3.7 — 地面值的 5% */
    public static final float AIR_CONTROL = 0.05f;

    /** 重力加速度 (m/s²) */
    public static final float GRAVITY = 9.81f;

    /** 素体裸足越障高度 (m)，§5.1 step_height_base — 默认能上半砖但不能上完整方块 */
    public static final float STEP_HEIGHT_BASE = 0.6f;

    /** 控制器-身体最大分离距离 (m)，§5.1 sep_max — 超过后触发 RAGDOLL */
    public static final float SEP_MAX = 2.0f;

    // ==========================================
    // 导出常量
    // ==========================================

    /**
     * 内阻系数 c₀（无量纲），由设计参数自动导出。
     * <p>
     * c₀ = P_base / (v_ref × m_naked × g)
     * <p>
     * 表征素体"生物力学效率"，质量不变则不变，决定裸机最高匀速。
     */
    public static final float C0 = P_BASE / (V_REF * MASS * GRAVITY);

    /** 数值精度阈值，用于避免除零奇点 */
    public static final float EPSILON = 0.01f;
}
