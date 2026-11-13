package io.github.sweetzonzi.arms_core.util.control;

import io.github.sweetzonzi.arms_core.common.control.attr.LegDriveAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;

import java.util.List;

/**
 * 有符号力速曲线与逐件合成。
 *
 * <p>全部方法无世界依赖：生产物理步与静态标定调用同一份求值，测试也直接调它。
 * 曲线取平方形式 {@code f = F·(1 − (v/V)²)}，先处理逆向速度、再限幅，最后才参与求和。
 * 把顺序倒过来（先求和再限幅）会改变快慢源混装的平衡点。
 *
 * <p>速度一律是沿移动意图方向的带符号投影 {@code s = dot(v_t, u)}，不是水平速率。
 *
 * @author Sweetzonzi
 */
public final class ForceSpeedCurve {

    /** 峰值功率系数 {@code 2/(3√3)}，来自 {@code v·F·(1 − (v/V)²)} 的极值。 */
    public static final double PEAK_POWER_COEFFICIENT = 2.0 / (3.0 * Math.sqrt(3.0));

    private ForceSpeedCurve() {
        throw new UnsupportedOperationException("公式类，不可实例化");
    }

    /**
     * 单个驱动源、单个档位的带符号出力。
     *
     * <pre>
     * s_eff = max(s, 0)
     * raw   = F·(1 − (s_eff/V)²)
     * f     = clamp(raw, −B, +F)
     * </pre>
     *
     * <p>逆向速度按 {@code s_eff = 0} 处理：沿意图方向给出最多 {@code F} 的正向力，先消除逆向运动。
     * 超速段原始值为负，逐件限幅到 {@code −B}，因此反拖不会随速率无限增长。
     *
     * @param alongSpeed 沿意图方向的带符号速度（m/s）
     * @param spec       该源的力速参数
     * @param gait       档位
     * @return 出力（N），落于 {@code [−B, +F]}
     */
    public static double signedForce(double alongSpeed, LegDriveAttr spec, Gait gait) {
        double effective = Math.max(alongSpeed, 0.0);
        double noLoad = spec.noLoadSpeed(gait);
        double ratio = effective / noLoad;
        double raw = spec.maxForce() * (1.0 - ratio * ratio);
        return Math.min(Math.max(raw, -spec.dragLimit()), spec.maxForce());
    }

    /**
     * 同一档位下逐件求值后求和：{@code F_legs,g(s) = Σ_i f_i,g(s)}。
     *
     * <p>不能先用平均速度配合总力求值，那个结果一般不等于逐件相加。
     *
     * @param specs      有效驱动源
     * @param gait       档位
     * @param alongSpeed 沿意图方向的带符号速度（m/s）
     * @return 该档位的合力（N）
     */
    public static double gearForce(List<LegDriveAttr> specs, Gait gait, double alongSpeed) {
        double total = 0.0;
        for (LegDriveAttr spec : specs) {
            total += signedForce(alongSpeed, spec, gait);
        }
        return total;
    }

    /**
     * 推进区间的瞬时机械功率 {@code P = v·f}。
     *
     * @param alongSpeed 沿意图方向的带符号速度（m/s）
     * @param force      该处出力（N）
     * @return 功率（W）；逆向速度按零计
     */
    public static double power(double alongSpeed, double force) {
        return Math.max(alongSpeed, 0.0) * force;
    }

    /**
     * 单个源的峰值机械功率 {@code 2/(3√3)·F·V}。
     *
     * <p>峰值发生在 {@code v = V/√3}。共享最大力不等于共享功率：{@code V} 越大峰值越高。
     *
     * @param maxForce    正向力上限（N）
     * @param noLoadSpeed 该档零出力速度（m/s）
     * @return 峰值功率（W）
     */
    public static double peakPower(double maxForce, double noLoadSpeed) {
        return PEAK_POWER_COEFFICIENT * maxForce * noLoadSpeed;
    }

    /**
     * 单档、无限幅触发时的零合力速度 {@code sqrt(A/D)}，其中 {@code A = Σ F_i}、{@code D = Σ F_i/V_i²}。
     *
     * <p>这是曲线相加后的模型零点，不是标定结果：有限反拖或外部阻力介入后要按实际合力求根。
     * 没有有效驱动源或所有 {@code F_i = 0} 时返回 0。
     *
     * @param specs 有效驱动源
     * @param gait  档位
     * @return 零合力速度（m/s），非负
     */
    public static double mixedZeroSpeed(List<LegDriveAttr> specs, Gait gait) {
        double a = 0.0;
        double d = 0.0;
        for (LegDriveAttr spec : specs) {
            double speed = spec.noLoadSpeed(gait);
            a += spec.maxForce();
            d += spec.maxForce() / (speed * speed);
        }
        return d > 0.0 ? Math.sqrt(a / d) : 0.0;
    }
}