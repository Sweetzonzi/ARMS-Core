package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.animation.IAnimatable;
import cn.solarmoon.spark_core.molang.SparkMolangContext;
import cn.solarmoon.spark_core.molang.runtime.binding.QueryBinding;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableKey;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys;
import org.jetbrains.annotations.Nullable;

/**
 * 机娘的 MoLang 求值上下文 —— 把内容包用到的 {@code query.*} 落到 ARMS-Core 的真实数据上。
 * <p>
 * 基类 {@link SparkMolangContext} 只提供五个通用查询（{@code anim_time} / {@code lod} 等），
 * 内容包用到的运动与姿态量在引擎里没有来源：未绑定的 {@code query.*} 会被编译成常量 0，
 * 而且它在布尔上下文里会产出非法字节码。本类把这些名字接到本仓库的状态上，
 * 清单与来源见 {@code docs/MoLang查询面接入实现计划.md} §0.3、§3。
 *
 * <h3>为什么泛型取 {@code IAnimatable<ArmsCore>}</h3>
 * 求值对象是装配体 {@link ArmsCore}，经 {@code getEntity().getAnimatable()} 直接取到，
 * 调用方可以直接使用装配体接口。
 * 这条形状与 Machine-Max 的 {@code MechMolangContext} 一致。
 *
 * <h3>值的来源与线程约定</h3>
 * 每个量都是「一个 volatile 字段 + 一个发布方法 + 一个只读 {@link QueryBinding}」：
 * <ul>
 *   <li><b>客户端主线程写、物理线程读</b>：{@link MechaBodyAnimation#updateClientInputs}
 *       每 tick 发布运动、朝向、寿命和每 tick 位移。头部两项从宿主渲染路径发布
 *       （{@code client/MechaPlayerRenderer.java#onRenderPlayerPre}）。</li>
 *   <li><b>服务端物理线程写和读</b>：{@link MechaBodyAnimation#updateServerInputs} 从控制器及其输入取值。</li>
 *   <li><b>物理线程独占</b>：{@link #attachVariables(StateVariableContainer)} 注入的逻辑层变量容器，
 *       读写都在物理线程，因此不加同步。</li>
 * </ul>
 * 求值本身发生在物理线程（表现层在 {@code MechaAnimPresenter#progress} 里推进），
 * 所以绑定方法只读字段、不读 {@code SynchedEntityData}（那个容器只在主线程读）。
 *
 * <h3>类可见性与构造</h3>
 * 类与它的无参构造都必须是 {@code public}：引擎按上下文类缓存，并用无参构造造一个原型实例来扫
 * {@code @QueryBinding} 方法（{@code ../Spark-Core/src/main/java/cn/solarmoon/spark_core/molang/MolangContextRegistry.java#getOrCreateEngine}）。
 *
 * @author Sweetzonzi
 */
public class MechaMolangContext extends SparkMolangContext<IAnimatable<ArmsCore>> {

    /** 水平速度 (m/s)，恒非负 */
    private volatile float groundSpeed;

    /** 垂直速度 (m/s，正为上升) */
    private volatile float verticalSpeed;

    /** 身体偏航角（度，KCC 绝对朝向） */
    private volatile float bodyYRotation;

    /** 偏航角速度（度/秒，已做一阶滤波） */
    private volatile float yawSpeed;

    /** 头部俯仰（度） */
    private volatile float headXRotation;

    /** 头部相对身体的偏航（度） */
    private volatile float headYRotation;

    /** 每 tick 位移的世界系 X 分量 (m/tick) */
    private volatile float positionDeltaX;

    /** 每 tick 位移的世界系 Y 分量 (m/tick) */
    private volatile float positionDeltaY;

    /** 每 tick 位移的世界系 Z 分量 (m/tick) */
    private volatile float positionDeltaZ;

    /** 动画体存在时长（秒） */
    private volatile float lifeTime;

    /** 逻辑层与表现层共用的变量容器；只在物理线程读写，故只保证引用可见 */
    private volatile StateVariableContainer variables;

    /**
     * 无参构造：引擎建原型实例扫 {@code @QueryBinding} 用，必须是 {@code public}。
     */
    public MechaMolangContext() {
        super();
    }

    /**
     * @param animatable 本上下文服务的动画体
     */
    public MechaMolangContext(@Nullable IAnimatable<ArmsCore> animatable) {
        super(animatable);
    }

    // ==========================================
    // 发布（客户端主线程 / 渲染路径）
    // ==========================================

    /**
     * 发布速度。客户端主线程每 tick 从同步的 {@code DATA_VEL} 采样一次。
     *
     * @param groundSpeed   水平速度模长 (m/s)
     * @param verticalSpeed 垂直速度 (m/s，正为上升)
     */
    public void setMotion(float groundSpeed, float verticalSpeed) {
        this.groundSpeed = groundSpeed;
        this.verticalSpeed = verticalSpeed;
    }

    /**
     * 发布朝向。客户端主线程每 tick 从同步的 {@code DATA_YAW} 采样一次。
     *
     * @param bodyYRotation KCC 绝对偏航（度）
     * @param yawSpeed      偏航角速度（度/秒，调用方已完成一阶滤波）
     */
    public void setFacing(float bodyYRotation, float yawSpeed) {
        this.bodyYRotation = bodyYRotation;
        this.yawSpeed = yawSpeed;
    }

    /**
     * 发布头部朝向。由渲染路径每帧调用一次：只有那里拿得到宿主实体。
     *
     * @param headXRotation 头部俯仰（度）
     * @param headYRotation 头部相对身体偏航（度）
     */
    public void setHeadRotation(float headXRotation, float headYRotation) {
        this.headXRotation = headXRotation;
        this.headYRotation = headYRotation;
    }

    /**
     * 发布每 tick 位移。客户端主线程每 tick 从同步的 {@code DATA_VEL} 除以 tick 率得到。
     *
     * @param x X 分量 (m/tick)
     * @param y Y 分量 (m/tick)
     * @param z Z 分量 (m/tick)
     */
    public void setPositionDelta(float x, float y, float z) {
        this.positionDeltaX = x;
        this.positionDeltaY = y;
        this.positionDeltaZ = z;
    }

    /**
     * 发布动画体存在时长。客户端主线程每 tick 累加一个 tick 的长度。
     *
     * @param seconds 秒
     */
    public void setLifeTime(float seconds) {
        this.lifeTime = seconds;
    }

    /**
     * 注入逻辑层与表现层共用的变量容器，由表现层编排器在建立四台姿态机之前调一次。
     * <p>
     * 注入的容器只在物理线程读写（逻辑层写、本类的绑定方法读），因此这里只发布引用本身。
     *
     * @param variables 变量容器
     */
    public void attachVariables(StateVariableContainer variables) {
        this.variables = variables;
    }

    // ==========================================
    // 运动与朝向
    // ==========================================

    /** 水平速度 (m/s)。内容包用它当转身/摆臂强度，见计划 §3.4。 */
    @QueryBinding("ground_speed")
    public double queryGroundSpeed() {
        return groundSpeed;
    }

    /** 垂直速度 (m/s，正为上升)。内容包用它的符号区分上升与下落。 */
    @QueryBinding("vertical_speed")
    public double queryVerticalSpeed() {
        return verticalSpeed;
    }

    /** 身体偏航角（度）。 */
    @QueryBinding("body_y_rotation")
    public double queryBodyYRotation() {
        return bodyYRotation;
    }

    /** 偏航角速度（度/秒）。 */
    @QueryBinding("yaw_speed")
    public double queryYawSpeed() {
        return yawSpeed;
    }

    // ==========================================
    // 头部
    // ==========================================

    /** 头部俯仰（度）。 */
    @QueryBinding("head_x_rotation")
    public double queryHeadXRotation() {
        return headXRotation;
    }

    /** 头部相对身体偏航（度）。 */
    @QueryBinding("head_y_rotation")
    public double queryHeadYRotation() {
        return headYRotation;
    }

    // ==========================================
    // 位移与寿命
    // ==========================================

    /**
     * 每 tick 位移的分量 (m/tick)，世界系。
     * <p>
     * 内容是 {@code q.position_delta(0)}（X）与 {@code q.position_delta(2)}（Z）；越界轴返回 0。
     * 取 m/tick 而不是 m/s 的依据是用法的量纲：内容包把它与 {@code bone_pivot_abs('Root')/16}
     * 这类「格」量相加减（{@code bmpt_main.animation.json} 里 {@code v.head_forward_delta} 那一行），
     * 乘上内容侧系数 2.2 之后必须仍在格的量级上。
     *
     * @param axis 0=X、1=Y、2=Z
     * @return 该轴每 tick 位移 (m/tick)
     */
    @QueryBinding("position_delta")
    public double queryPositionDelta(int axis) {
        return switch (axis) {
            case 0 -> positionDeltaX;
            case 1 -> positionDeltaY;
            case 2 -> positionDeltaZ;
            default -> 0.0;
        };
    }

    /** 动画体存在时长（秒）。 */
    @QueryBinding("life_time")
    public double queryLifeTime() {
        return lifeTime;
    }

    // ==========================================
    // 逻辑状态（变量容器）
    // ==========================================

    /** 水中姿态。 */
    @QueryBinding("is_in_water")
    public double queryIsInWater() {
        return flag(MechaStateVariableKeys.IN_WATER_POSTURE);
    }

    /**
     * 蹲伏姿态。
     * <p>
     * 取逻辑层的姿态 one-hot（{@code IS_CROUCHING}）而不是快照里的按键状态
     * （{@code IS_SNEAKING}）：内容包用这个名字问「机体是不是蹲着」，
     * 决定蹲姿的那条链是「按键 → posture」，重定向或强制姿态时两者会分叉。
     */
    @QueryBinding("is_sneaking")
    public double queryIsSneaking() {
        return flag(MechaStateVariableKeys.IS_CROUCHING);
    }

    /** 读一个布尔键：容器未注入或键为 false 时返回 0。 */
    private double flag(StateVariableKey<Boolean> key) {
        StateVariableContainer container = variables;
        if (container == null) return 0.0;
        return Boolean.TRUE.equals(container.get(key)) ? 1.0 : 0.0;
    }
}
