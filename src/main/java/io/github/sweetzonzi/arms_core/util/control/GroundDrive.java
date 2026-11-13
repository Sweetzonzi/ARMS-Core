package io.github.sweetzonzi.arms_core.util.control;

import io.github.sweetzonzi.arms_core.common.control.attr.LegDriveAttr;

import java.util.List;

/**
 * 地面驱动的本步求值：把有效驱动源、档位权重和相对速度算成沿向／侧向力请求。
 *
 * <pre>
 * 基础分支：
 * 有输入：F_along = F_drive + F_regen
 *         F_side = −sign(v_side)·min(μ·N_eff, m·|v_side|/dt)
 *         两分量合成后按 min(F_cap, J_adhesion/dt) 裁剪模长
 * 无输入：F_request = F_brake                    // −normalize(v_t)·min(B_legs, m·|v_t|/dt)
 * F_execute = clamp(F_request, ±min(F_cap·dt, J_adhesion)/dt)
 * 制动分量 = lerp(基础制动分量, 指数制动分量, 按完整相对速率求出的辅助权重)
 * </pre>
 *
 * <p>这里只做数值：方向、施力通道、接触写入都在调用侧。{@link Plan#forceAlong()} 与
 * {@link Plan#forceSide()} 是输入坐标系的两个有符号分量；无输入时只使用
 * {@link Plan#brakeMagnitude()}，由调用侧按完整相对速度取反向。
 *
 * <p>进入裁剪的量是合成后的整机请求，不是每个源、每个接触点各一份：{@code n} 个接触点不能得到
 * {@code n} 份基础额度。辅助可超过基础力圆及附着预算，沿向推进不参与辅助。
 *
 * @author Sweetzonzi
 */
public final class GroundDrive {

    /** 标准低速响应：组合摩擦为 1 时，每 50 ms 保留 0.546 的受控制速度。 */
    private static final double STANDARD_BRAKE_RATE = -Math.log(0.546) / 0.05;

    private GroundDrive() {
        throw new UnsupportedOperationException("公式类，不可实例化");
    }

    /**
     * 本步的执行计划。
     *
     * @param forceAlong     沿移动意图的有符号力（N），无输入时为 0
     * @param forceSide      垂直于移动意图的有符号制动力（N），无输入时为 0
     * @param brakeMagnitude 无输入时反向制动的模长（N），有输入时为 0
     * @param holdSpeedBound {@code v_hold} 的上界（m/s），见 {@link ContactBudget#holdSpeedBound}
     */
    public record Plan(double forceAlong, double forceSide, double brakeMagnitude, double holdSpeedBound) {
    }

    /**
     * 求本步计划。
     *
     * @param attrs        有效驱动源（无效 Part、失去支撑等硬条件已由调用方剔除）
     * @param weights      档位权重，顺序同 {@link GearResponse#GEARS}
     * @param hasMoveInput 是否有经过死区与控制许可处理的有效移动请求
     * @param alongSpeed   沿移动意图的带符号相对速度（m/s）；无输入时传入切向速率（用于动量限幅）
     * @param dt           物理步长（s）
     * @param mass         参与制动的质量（kg）
     * @param muPairBase   接触对基准组合摩擦，用于基础附着预算与指数制动速率
     * @param effectiveLoad 等效载荷 {@code N_eff}（N），已由 {@link ContactBudget#softCompress} 压缩
     * @param gravity      重力加速度（m/s²），用于 {@code v_hold} 上界
     * @return 本步计划
     */
    public static Plan evaluate(List<LegDriveAttr> attrs, double[] weights, boolean hasMoveInput,
                                double alongSpeed, double dt, double mass, double muPairBase,
                                double effectiveLoad, double gravity) {
        return evaluate(attrs, weights, hasMoveInput, alongSpeed, 0.0, dt, mass, muPairBase,
                effectiveLoad, gravity);
    }

    /**
     * 求二维计划：基础两轴共用整机能力与附着预算，裁剪后的制动分量再参与低速辅助混合。
     *
     * <p>沿向与侧向基须由调用方保持正交、归一化，速度均相对支撑；无输入时
     * {@code alongSpeed} 表示完整切向速率，{@code sideSpeed} 不参与停车计算。
     * 各参数的单位与一维入口相同，{@code sideSpeed} 为带符号侧向速度（m/s）。
     */
    public static Plan evaluate(List<LegDriveAttr> attrs, double[] weights, boolean hasMoveInput,
                                double alongSpeed, double sideSpeed, double dt, double mass,
                                double muPairBase, double effectiveLoad, double gravity) {
        double capability = 0.0;
        for (LegDriveAttr attr : attrs) {
            capability += attr.maxForce();
        }
        double adhesion = ContactBudget.adhesionImpulse(muPairBase, effectiveLoad, dt);
        double holdBound = ContactBudget.holdSpeedBound(capability, mass, dt, muPairBase, gravity);
        double speed = hasMoveInput ? Math.hypot(alongSpeed, sideSpeed) : Math.abs(alongSpeed);
        double assistWeight = brakeAssistWeight(attrs, capability, speed);

        if (hasMoveInput) {
            double blended = GearResponse.blendedForce(attrs, weights, alongSpeed);
            double side = -Math.copySign(ContactBudget.brakeForce(sideSpeed, adhesion / dt, mass, dt),
                    sideSpeed);
            double magnitude = Math.hypot(blended, side);
            // 两轴先合成再裁剪，不能让侧向在已用完的沿向预算之外另拿一份附着。
            double scale = magnitude > 0.0
                    ? ContactBudget.executeForce(magnitude, capability, adhesion, dt) / magnitude
                    : 0.0;
            double assistedSide = assistBrake(Math.abs(side * scale), Math.abs(sideSpeed), assistWeight,
                    mass, muPairBase, dt);
            return new Plan(blended * scale, -Math.copySign(assistedSide, sideSpeed), 0.0, holdBound);
        }

        // 无输入：基础制动先裁剪，再混入指数制动；方向由调用侧按完整相对速度取反。
        double brake = ContactBudget.brakeForce(Math.abs(alongSpeed), capability, mass, dt);
        double executed = ContactBudget.executeForce(brake, capability, adhesion, dt);
        double assistedBrake = assistBrake(Math.max(executed, 0.0), Math.abs(alongSpeed), assistWeight,
                mass, muPairBase, dt);
        return new Plan(0.0, 0.0, assistedBrake, holdBound);
    }

    /** 按最大力加权逐 Part 的辅助比例；完整相对速率使高速前进时的小侧滑也走基础模型。 */
    private static double brakeAssistWeight(List<LegDriveAttr> attrs, double capability, double speed) {
        if (capability == 0.0) {
            return 0.0;
        }
        double weighted = 0.0;
        for (LegDriveAttr attr : attrs) {
            double ratio = speed / attr.jogSpeed();
            double squared = ratio * ratio;
            weighted += attr.maxForce() * (double) attr.brakeAssist() * Math.exp(-squared * squared);
        }
        return Math.min(weighted / capability, 1.0);
    }

    /** 两个制动分支均不超过本步动量；凸组合保持这一性质，无需再按基础附着或最大力裁剪。 */
    private static double assistBrake(double base, double controlledSpeed, double weight,
                                      double mass, double muPairBase, double dt) {
        if (weight == 0.0) {
            return base;
        }
        double fraction = -Math.expm1(-STANDARD_BRAKE_RATE * muPairBase * dt);
        double exponential = mass * fraction * controlledSpeed / dt;
        return base + weight * (exponential - base);
    }
}
