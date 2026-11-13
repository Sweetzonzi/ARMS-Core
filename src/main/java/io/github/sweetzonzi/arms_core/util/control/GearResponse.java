package io.github.sweetzonzi.arms_core.util.control;

import io.github.sweetzonzi.arms_core.common.control.attr.LegDriveAttr;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;

import java.util.List;

/**
 * 档位权重与混合：一阶响应、权重归一与逐件合成的纯数学部分。
 *
 * <p>权重是逻辑档位的响应状态，不是左右脚的物理步态。它只作用于主动地面驱动请求：
 * 重力、碰撞反力、外部冲击与推进器自身响应都不经过这层低通。
 *
 * <p>一阶响应用按步长算的精确离散系数 {@code α = 1 − exp(−dt/τ)}，权重按
 * {@code w_g ← (1−α)·w_g + α·[g=h]} 递推；权重恒非负、总和不超过 1。
 *
 * <p>三个档位按 {@link #GEARS} 的顺序索引，与 {@link LegDriveAttr} 的三个空载速度一一对应。
 *
 * @author Sweetzonzi
 */
public final class GearResponse {

    /** 档位顺序：权重数组、零载速度字段与 {@link #blendedForce} 都按这个顺序解释。 */
    public static final List<Gait> GEARS = List.of(Gait.CREEP, Gait.JOG, Gait.SPRINT);

    private GearResponse() {
        throw new UnsupportedOperationException("公式类，不可实例化");
    }

    /**
     * 一阶响应的本步系数。{@code τ = 0} 表示本步建立完成，直接取 {@code α = 1}，避免除零。
     *
     * @param dt  物理步长（s），须为正
     * @param tau 时间常数（s），非负
     * @return 本步系数 {@code α ∈ (0, 1]}
     */
    public static double alpha(double dt, double tau) {
        if (dt <= 0.0) {
            throw new IllegalArgumentException("步长必须为正，实际为 " + dt);
        }
        if (tau <= 0.0) {
            return 1.0;
        }
        return 1.0 - Math.exp(-dt / tau);
    }

    /** 全零权重：从静止起步时的初始状态，出力自本步开始逐渐建立。 */
    public static double[] zeroWeights() {
        return new double[GEARS.size()];
    }

    /** 只有目标档位为 1 的权重：中途切档时不做过渡、直接取目标曲线。 */
    public static double[] singleGear(int gearIndex) {
        requireGearIndex(gearIndex);
        double[] weights = new double[GEARS.size()];
        weights[gearIndex] = 1.0;
        return weights;
    }

    /**
     * 权重的本步递推 {@code w_g ← (1−α)·w_g + α·[g=h]}，返回新数组、不改入参。
     *
     * @param weights     当前权重，长度须为 {@link #GEARS} 的大小
     * @param alpha       本步系数，取自 {@link #alpha(double, double)}
     * @param targetIndex 本步有效档位在 {@link #GEARS} 中的下标
     * @return 递推后的权重
     */
    public static double[] step(double[] weights, double alpha, int targetIndex) {
        requireWeights(weights);
        requireGearIndex(targetIndex);
        double[] next = new double[weights.length];
        for (int gear = 0; gear < weights.length; gear++) {
            double target = gear == targetIndex ? 1.0 : 0.0;
            next[gear] = (1.0 - alpha) * weights[gear] + alpha * target;
        }
        return next;
    }

    /**
     * 混合后的合力 {@code F_legs(s) = Σ_g w_g · Σ_i f_i,g(s)}。
     *
     * <p>每步各曲线都用当前速度求值，不缓存上一步的实际推力。
     *
     * @param specs      有效驱动源
     * @param weights    档位权重
     * @param alongSpeed 沿意图方向的带符号速度（m/s）
     * @return 混合合力（N）
     */
    public static double blendedForce(List<LegDriveAttr> specs, double[] weights, double alongSpeed) {
        requireWeights(weights);
        double total = 0.0;
        for (int gear = 0; gear < weights.length; gear++) {
            if (weights[gear] == 0.0) {
                continue;
            }
            total += weights[gear] * ForceSpeedCurve.gearForce(specs, GEARS.get(gear), alongSpeed);
        }
        return total;
    }

    /**
     * 混合权重下的零合力速度：令 {@code A = Σ_i,g w_g·F_i}、{@code D = Σ_i,g w_g·F_i/V_i,g²}，取
     * {@code sqrt(A/D)}。
     *
     * <p>混合期的零点一般不等于任何一档的 {@code V_i,g}：它是曲线相加的预期结果，不是标定值。
     * {@code D = 0} 时没有可用驱动，返回 0。
     *
     * @param specs   有效驱动源
     * @param weights 档位权重
     * @return 零合力速度（m/s），非负
     */
    public static double zeroSpeed(List<LegDriveAttr> specs, double[] weights) {
        requireWeights(weights);
        double a = 0.0;
        double d = 0.0;
        for (int gear = 0; gear < weights.length; gear++) {
            double weight = weights[gear];
            if (weight == 0.0) {
                continue;
            }
            for (LegDriveAttr spec : specs) {
                double speed = spec.noLoadSpeed(GEARS.get(gear));
                a += weight * spec.maxForce();
                d += weight * spec.maxForce() / (speed * speed);
            }
        }
        return d > 0.0 ? Math.sqrt(a / d) : 0.0;
    }

    private static void requireWeights(double[] weights) {
        if (weights == null || weights.length != GEARS.size()) {
            throw new IllegalArgumentException("权重长度必须是 " + GEARS.size());
        }
    }

    private static void requireGearIndex(int gearIndex) {
        if (gearIndex < 0 || gearIndex >= GEARS.size()) {
            throw new IllegalArgumentException("档位下标越界: " + gearIndex);
        }
    }
}