package io.github.sweetzonzi.arms_core.common.control.attr;

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

    /**
     * 裸足摩擦系数（<b>角色侧</b>），§5.1 μ_naked — 无助力腿时兜底。
     * <p>
     * 它写进控制器刚体的 {@code setFriction}，与地形侧摩擦相乘才是引擎实际使用的组合摩擦
     * （`docs/角色控制器-刚体动力学方案.md` §7.3）。取 2.0 时组合值 {@code 2.0 × 0.5 = 1.0}，
     * 爬坡上限 {@code atan(1.0) = 45°}，与 `docs/角色控制器-行走物理设计.md` §8.1 的既有标定
     * 一致；要 35° 就取 1.4。
     * <p>
     * <b>取值 2.0 而不是 1.0 是必须的。</b>组合摩擦同时是控制力的饱和上限
     * {@code μ_eff·N}：{@code m·g = 686.7 N} 在 μ_eff = 0.5 时上限只有 343 N，比
     * {@code F_MAX = 700 N} 低一半，于是低速区的驱动力被锥截一半、起步加速度只有解析值的一半。
     */
    public static final float MU_NAKED = 2.0f;

    /**
     * 地形侧摩擦系数（配置常量），`docs/角色控制器-刚体动力学方案.md` §7.6。
     * <p>
     * 取 Bullet {@code btCollisionObject} 的默认值 0.5：全仓没有任何一处对地形调用
     * {@code setFriction}，因此引擎用的就是它。逐方块材质接入（§7.5）之后这个常量会被
     * 按接触对写入的实际值取代，组合摩擦的形式不变。
     */
    public static final float TERRAIN_FRICTION = 0.5f;

    /**
     * 粘滞阻尼系数，§5.1 c₁ — 一阶速度衰减，0=无阻尼。
     * <p>
     * 映射到控制器刚体的 {@code setLinearDamping}。它是不是速度相关阻力的唯一来源仍未定
     * （`docs/角色控制器-刚体动力学方案.md` §12.5），因此当前取 0 并标注来源待定。
     */
    public static final float C1 = 0.0f;

    /** 超速衰减陡度 (m/s)，§5.1 λ — 越大衰减越缓 */
    public static final float LAMBDA = 2.0f;

    /**
     * 空中控制力缩放因子，§3.7 — 控制力取 {@code F_max × 本值}。
     * <p>
     * 0.30 对应约 3 m/s² 的水平加速度（F_max = 700 N、质量 70 kg），是地面低速加速度
     * （约 8 m/s²）的三分之一左右：一次跳跃的滞空时间里能改变数 m/s 的水平速度，足以
     * 修正落点，但远不足以在空中瞬间掉头——「空中难以变向」由这个量级本身给出，
     * 不需要额外的方向限制。
     * <p>
     * 基准取 {@code F_max} 而不是力-速曲线：功率-力-速曲线是**蹬地推进**模型（肌肉在
     * 身体移动时对地面做功），空中没有可蹬的反力面，控制力因此不随速率衰减——否则
     * 高速飞行时空中控制会趋近于零，与「保留一定操作手感」相反。
     */
    public static final float AIR_CONTROL = 0.30f;

    /** 重力加速度 (m/s²) */
    public static final float GRAVITY = 9.81f;

    /**
     * 素体裸足越障高度 (m)，§5.1 step_height_base。
     * <p>
     * 0.6 的语义是「能上 0.5 m 半砖 / 地毯，上不了 1.0 m 完整方块」。越障由 Bullet 的
     * `../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#stepUp`
     * 完成：先把胶囊抬高 {@code m_stepHeight} 再水平 sweep，因此可越障碍的高度上限就是本值。
     * 上得去的上限还**略小于**本值：水平 sweep 会把形状 margin 临时加大
     * {@code m_addedMargin = 0.02}（同文件的 {@code stepForwardAndStrafe}），等于把胶囊底面又压低 2 cm。
     * 要上 1 格完整方块需靠跳跃，或腿部子系统提供的 {@code step_height_bonus}（§8.2 生效步高）。
     * <p>
     * 回归测试：`MechaCharacterStepTest.java#climbsAHalfSlabStep` 与 `#isBlockedByAFullBlockStep`。
     */
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
