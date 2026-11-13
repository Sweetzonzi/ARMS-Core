package io.github.sweetzonzi.arms_core.util.control;

/**
 * 支撑载荷缓存：把上一物理步求解出的法向冲量留到下一物理步使用。
 *
 * <p>窄相回调（写组合摩擦的地方）早于求解器，那一刻本步的法向冲量还不存在，
 * 能读到的只是上一步求解留下的值；该值逐点等于上一步的求解结果。因此这里存的是<b>同一个量</b>，
 * 不是额外的估计量，代价是滞后一步。
 *
 * <p>两条失效规则：
 * <ul>
 *   <li><b>只用一步</b>：样本写入后最多存活 {@link #MAX_AGE_STEPS} 次 {@link #advance()} 调用。
 *       接触一旦断开（该步没有任何承重点）就没有样本，下一次 {@link #advance()} 即自然过期；
 *       调用方也可以显式 {@link #invalidate()}。</li>
 *   <li><b>零冲量等于没有样本</b>：求解出的和为零说明本步没有承重，此时不能把「0」当成载荷，
 *       必须回落到静态重力估算，否则附着预算会归零、主动力一步都发不出来。</li>
 * </ul>
 *
 * <p>本类不做平滑、不跨步滤波：设计上接受一步估计误差，落地峰值由
 * {@link ContactBudget#softCompress} 压缩后再进入预算，压缩参数归控制器共用。
 *
 * <p>线程：物理线程独占，字段无锁。唯一的写入点是 Post 相位，唯一的读取点是下一步的 Pre。
 *
 * @author Sweetzonzi
 */
public final class SupportLoadCache {

    /**
     * 样本的最大年龄（次 {@link #advance()} 调用），超出后视为没有样本。
     *
     * <p>取 1：Post 写入之后的那一个物理步仍可用，再往后一律回落静态估算。这是「一步延迟」
     * 的固定上界，不能取更大——更长的存活期会把落地冲击带进后续若干步的附着预算。
     */
    public static final int MAX_AGE_STEPS = 1;

    /** 上一步求解出的法向冲量之和（N·s）。 */
    private double impulse;

    /** 产生 {@link #impulse} 的那一步的步长（s）。 */
    private float dt;

    /** 距样本写入已经过的步数；初值即过期，表示「还没有样本」。 */
    private int age = MAX_AGE_STEPS + 1;

    /**
     * 写入本步求解出的支撑法向冲量之和。
     *
     * <p>非有限值、非正冲量或非正步长一律等于没有样本（见类注释的第二条失效规则），
     * 因此调用方不需要在写之前判空。
     *
     * @param impulse 法向冲量之和（N·s），非负
     * @param dt      产生该冲量的步长（s），正数
     */
    public void sample(double impulse, float dt) {
        if (!(impulse > 0.0) || !Double.isFinite(impulse) || !(dt > 0f) || !Float.isFinite(dt)) {
            invalidate();
            return;
        }
        this.impulse = impulse;
        this.dt = dt;
        this.age = 0;
    }

    /** 丢弃样本：下一步起回落静态估算。 */
    public void invalidate() {
        this.impulse = 0.0;
        this.dt = 0f;
        this.age = MAX_AGE_STEPS + 1;
    }

    /** 推进一个物理步：每步在控制相位开始处调用一次，超出 {@link #MAX_AGE_STEPS} 即过期。 */
    public void advance() {
        if (age <= MAX_AGE_STEPS) {
            age++;
        }
    }

    /**
     * 本步是否还有可用样本。
     *
     * @return true 表示 {@link #rawLoad(double)} 会返回上一步的观测值
     */
    public boolean hasSample() {
        return age <= MAX_AGE_STEPS && impulse > 0.0 && dt > 0f;
    }

    /**
     * 本步的原始载荷 {@code N_raw}：有样本取 {@code J/dt}，否则取静态重力载荷。
     *
     * <p>除以的是样本自己那一步的步长，因此步长变化不会把冲量读成错误的力。
     *
     * @param gravityLoad 静态重力载荷 {@code N_g = m·g·cosθ}（N）
     * @return 原始载荷（N），非负
     */
    public double rawLoad(double gravityLoad) {
        if (hasSample()) {
            return impulse / dt;
        }
        return Math.max(gravityLoad, 0.0);
    }

    /**
     * 样本里的法向冲量之和（N·s），没有样本时为 0；仅供读数与诊断。
     *
     * @return 法向冲量之和（N·s）
     */
    public double impulse() {
        return impulse;
    }

    /**
     * 样本的年龄（次物理步），没有样本时大于 {@link #MAX_AGE_STEPS}；仅供读数与诊断。
     *
     * @return 已过步数
     */
    public int ageSteps() {
        return age;
    }
}
