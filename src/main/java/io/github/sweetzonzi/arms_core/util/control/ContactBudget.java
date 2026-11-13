package io.github.sweetzonzi.arms_core.util.control;

/**
 * 接触预算：主动力上限、保持门、载荷软压缩与制动限幅的纯数学部分。
 *
 * <p>这里只做数值裁剪与估计，不读写刚体、接触点或事件：
 * <ul>
 *   <li><b>基础主动冲量上限</b> {@code |J_base| ≤ min(F_cap·dt, J_adhesion)}：
 *       裁剪作用在合成后的整机基础请求上一次。低速制动辅助在基础裁剪后混合，
 *       最终制动力可超过此额度，见 {@link GroundDrive#evaluate}</li>
 *   <li><b>保持门</b>：无输入、两项相对速度都小于门限才把接触对基准摩擦写回；门本身只看输入与
 *       相对速度，不看载荷缓存</li>
 *   <li><b>保持速度门的上界</b> {@code v_hold ≤ min(B_legs·dt/m, μ·g·dt)}：门打开的那一步，
 *       剩余相对速度由求解器一步抹掉，因此门值是交接冲量的尺度，不只是“站稳”的尺度</li>
 *   <li><b>载荷软压缩</b>：按重力归一化的对数压缩，低载荷取真值、高载荷增长趋缓</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
public final class ContactBudget {

    private ContactBudget() {
        throw new UnsupportedOperationException("公式类，不可实例化");
    }

    /**
     * 按动量限幅的制动请求大小 {@code min(B, m·|v_t|/dt)}。
     *
     * <p>只限制“单个制动力把速度推过零”的那一部分，不承诺其他力源不使速度过零。
     *
     * @param tangentialSpeed 相对支撑的切向速率（m/s），取绝对值
     * @param brakeCapability 整机主动制动力 {@code B_legs}（N）
     * @param mass            参与制动的质量（kg）
     * @param dt              物理步长（s）
     * @return 制动请求的大小（N），非负
     */
    public static double brakeForce(double tangentialSpeed, double brakeCapability, double mass, double dt) {
        requirePositive("dt", dt);
        requirePositive("mass", mass);
        double momentumLimited = mass * Math.abs(tangentialSpeed) / dt;
        return Math.max(Math.min(brakeCapability, momentumLimited), 0.0);
    }

    /**
     * 保持速度门的上界 {@code min(B_legs·dt/m, μ_pair_base·g·dt)}。
     *
     * <p>第一项使交接力不超过主动制动力，第二项是一步内原生摩擦最多能抹掉的相对速度。
     *
     * @param brakeCapability 整机主动制动力 {@code B_legs}（N）
     * @param mass            控制器质量（kg）
     * @param dt              物理步长（s）
     * @param muPairBase      接触对基准组合摩擦
     * @param gravity         重力加速度（m/s²）
     * @return 门值上界（m/s）
     */
    public static double holdSpeedBound(double brakeCapability, double mass, double dt,
                                        double muPairBase, double gravity) {
        requirePositive("mass", mass);
        requirePositive("dt", dt);
        requireNonNegative("brakeCapability", brakeCapability);
        requireNonNegative("muPairBase", muPairBase);
        requireNonNegative("gravity", gravity);
        double byActuator = brakeCapability * dt / mass;
        double byAdhesion = muPairBase * gravity * dt;
        return Math.min(byActuator, byAdhesion);
    }

    /**
     * 保持门：受控、有有效支撑、没有有效移动输入、两项相对速度都小于门限。
     *
     * <p>每物理步在控制阶段算一次并冻结，主动请求与接触写入共用同一份结果；命中当步写入接触对的
     * 基准组合摩擦，未命中则写 0。
     *
     * @param controlEnabled  地面控制是否被许可
     * @param validSupport    本步是否有有效支撑
     * @param hasMoveInput    是否经过死区与控制许可处理后的有效移动请求
     * @param tangentialSpeed 相对支撑的切向速率（m/s）
     * @param normalSpeed     相对支撑的法向速率（m/s）
     * @param holdSpeed       切向门限 {@code v_hold}（m/s）
     * @param normalHoldSpeed 法向门限 {@code v_normal_hold}（m/s）
     * @return true 表示本步允许被动保持
     */
    public static boolean holdGate(boolean controlEnabled, boolean validSupport, boolean hasMoveInput,
                                   double tangentialSpeed, double normalSpeed,
                                   double holdSpeed, double normalHoldSpeed) {
        return controlEnabled
                && validSupport
                && !hasMoveInput
                && Math.abs(tangentialSpeed) < holdSpeed
                && Math.abs(normalSpeed) < normalHoldSpeed;
    }

    /**
     * 按重力归一化的对数软压缩 {@code N_eff = N_g·h(N_raw/N_g)}。
     *
     * <pre>
     * h(x) = x                            x ≤ κ
     * h(x) = κ + a·ln(1 + (x − κ)/a)      x > κ
     * </pre>
     *
     * <p>低载荷区域取真值，阈值处函数值与一阶斜率连续，高载荷下单调增长而增长率趋零。
     * 它表达腿能用于蹬地／制动的等效载荷，不改写真实碰撞冲量。
     *
     * <p>{@code gravityLoad = 0} 时没有归一化基准，此时关闭压缩、原样返回 {@code rawLoad}：
     * 除零结果不能驱动预算。
     *
     * @param rawLoad     原始有效载荷 {@code N_raw}（N），上一次 Post 的法向冲量除以步长
     * @param gravityLoad 静态重力载荷 {@code N_g = m·g·cosθ}（N）
     * @param kappa       真值区上界（无量纲，{@code > 0}）
     * @param a           对数尺度（无量纲，{@code > 0}）
     * @return 等效载荷 {@code N_eff}（N），非负
     */
    public static double softCompress(double rawLoad, double gravityLoad, double kappa, double a) {
        requirePositive("kappa", kappa);
        requirePositive("a", a);
        if (gravityLoad <= 0.0) {
            return Math.max(rawLoad, 0.0);
        }
        double x = Math.max(rawLoad, 0.0) / gravityLoad;
        double h = x <= kappa ? x : kappa + a * Math.log(1.0 + (x - kappa) / a);
        return gravityLoad * h;
    }

    /**
     * 本步附着冲量预算 {@code J_adhesion = μ_pair_base·N_eff·dt}。
     *
     * <p>这是主动力能兑现的上限：腿力再高也只是逼近它，超过部分不会被地面兑现。
     *
     * @param muPairBase  接触对基准组合摩擦
     * @param effectiveLoad 等效载荷 {@code N_eff}（N）
     * @param dt          物理步长（s）
     * @return 冲量预算（N·s），非负
     */
    public static double adhesionImpulse(double muPairBase, double effectiveLoad, double dt) {
        requireNonNegative("muPairBase", muPairBase);
        requireNonNegative("effectiveLoad", effectiveLoad);
        requirePositive("dt", dt);
        return muPairBase * effectiveLoad * dt;
    }

    /**
     * 合成后基础请求的执行裁剪 {@code F_execute = clamp(F_request, ±J_max/dt)}，
     * {@code J_max = min(F_cap·dt, J_adhesion)}。
     *
     * <p>裁剪只作用一次：既不在各 Part 上分别扣，也不在各接触点上分别扣，否则 {@code n} 个接触点
     * 会得到 {@code n} 份额度。主动阶段原生摩擦为 0，求解器不替这次裁剪兜底。
     *
     * @param request        合成后的整机请求（N），有符号
     * @param forceCap       当前请求对应的能力上限 {@code F_cap}（N），非负
     * @param adhesionBudget 本步附着冲量 {@code J_adhesion}（N·s），非负
     * @param dt             物理步长（s）
     * @return 基础分支的执行力（N），低速制动辅助在调用侧混合
     */
    public static double executeForce(double request, double forceCap, double adhesionBudget, double dt) {
        requirePositive("dt", dt);
        requireNonNegative("forceCap", forceCap);
        requireNonNegative("adhesionBudget", adhesionBudget);
        double impulseLimit = Math.min(forceCap * dt, adhesionBudget);
        double forceLimit = impulseLimit / dt;
        return Math.min(Math.max(request, -forceLimit), forceLimit);
    }

    private static void requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " 必须是有限的非负数，实际为 " + value);
        }
    }

    private static void requirePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " 必须是有限的正数，实际为 " + value);
        }
    }
}
