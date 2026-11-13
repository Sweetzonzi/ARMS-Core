package io.github.sweetzonzi.arms_core.common;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * 机娘宿主 —— 承载一份 {@link ArmsCore} 的实体。
 * <p>
 * 本接口回答三件事：
 * <ol>
 *   <li><b>绑定关系</b>：这个宿主当前承载哪一个装配体。这是「是不是机娘」的唯一判据。</li>
 *   <li><b>位姿落地</b>：{@link ArmsCore} 把控制器算出的位姿与线速度写进宿主实体。</li>
 *   <li><b>状态落地</b>：机体的冲刺步态写成宿主实体的冲刺旗标，见 {@link #applySprinting(boolean)}。</li>
 * </ol>
 * 其余一切（相机、交互、背包、无敌帧、护甲、附魔、事件、输入采集、伤害落地）都由原版或别的
 * 机制承担，不进本接口。
 * <p>
 * <b>命名约束</b>：本接口的方法名刻意避开 {@link Entity} 的既有成员。{@code Entity#setPos(Vec3)}
 * 与 {@code Entity#getPosition(float)} 都是 {@code final}，在 Mixin 里实现同签名方法会导致类加载
 * 失败而不是「没生效」；同名的不同签名虽然合法，但会让阅读者必须数参数才能判断调的是哪一个。
 * 因此位姿写入采用 {@code apply*} 前缀 —— 与
 * {@link io.github.sweetzonzi.arms_core.common.control.MechaControl} 的
 * {@code applyFacing} / {@code applyMoveIntent} / {@code applyLogicOutputToKcc} 同一词汇。
 *
 * @author Sweetzonzi
 */
public interface IArmsHost {

    /** 宿主实体自身。接口由实体实现，因此实现处返回 {@code this}。 */
    LivingEntity getHostEntity();

    /** 当前承载的装配体；{@code null} 表示人类形态（尚未取得机体）。 */
    @Nullable
    ArmsCore getControlledArmsCore();

    /**
     * 建立 / 解除绑定。传 {@code null} 即解绑。
     * <p>
     * 一个宿主实体至多绑定一个装配体：实现必须先解除旧装配体的绑定，再写入新值。
     */
    void setControlledArmsCore(@Nullable ArmsCore core);

    /**
     * 把控制器刚体的位姿写进宿主实体。由 {@link ArmsCore} 在服务端主线程调用。
     * <p>
     * <b>只写位置，不写朝向。</b> 朝向的权威在客户端：视野偏航由客户端上行，服务端把它绝对赋值给
     * 控制器（{@code MechaControl#applyFacing}），再由控制器每物理步的朝向写入落到刚体姿态上。
     * 若这里顺手把控制器的朝向写回宿主实体的 {@code yRot}，而下一次上行读的又是这个字段，两点之间
     * 就构成一个跨网络的滞后反馈环，表现为视角抖动。姿态回写因此只负责位置。
     * <p>
     * {@code yRot} / {@code yHeadRot} 两个参数保留在签名里，供将来的非玩家宿主使用（模型朝向与实体
     * 朝向分离的 Doll 等）；玩家宿主的实现忽略它们。
     *
     * @param controllerCenter 控制器碰撞体中心的世界坐标（<b>不是</b>包围盒底面；实体位置字段的语义是底面，
     *                      两者相差 {@link io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset#HALF_TOTAL}）
     * @param yRot          度制偏航；玩家宿主不写，见上
     * @param yHeadRot      度制头部偏航；玩家宿主不写，见上
     */
    void applyPose(Vec3 controllerCenter, float yRot, float yHeadRot);

    /**
     * 把控制器刚体的线速度写进宿主实体。由 {@link ArmsCore} 在服务端主线程调用。
     * <p>
     * <b>三个轴同为 m/s。</b> 刚体路线统一了速度的量纲，
     * 不再有「水平是每物理步位移、垂直是 m/s」那种混合语义，因此换算只剩一个因子：
     *
     * <pre>
     * m/s → 每 tick 位移：÷ 20
     * </pre>
     * <p>
     * 宿主实体的 {@code net.minecraft.world.entity.Entity#setDeltaMovement} 三个分量统一是
     * <b>每 tick 位移</b>，而 1 tick = 1/20 s，因此 1 m/s 就是每 tick 走 1/20 格。这个因子与物理步频
     * 无关，不需要再传物理步长。
     *
     * @param velocity 控制器线速度 (m/s)，三个轴同单位
     */
    void applyVelocity(Vec3 velocity);

    /**
     * 原版冲刺速度修饰符的 id。
     * <p>
     * 本接口只读它、不写它：判据「冲刺是否已经落到速度属性上」需要知道原版用的是哪一个修饰符，
     * 而 {@link LivingEntity#setSprinting(boolean)} 增删的正是这一个（{@code +30%}，
     * {@code ADD_MULTIPLIED_TOTAL}）。id 写错时的后果是每次都判定为「未生效」、每 tick 重建一次
     * 修饰符，行为仍然正确。
     */
    ResourceLocation SPRINT_MODIFIER_ID = ResourceLocation.withDefaultNamespace("sprinting");

    /**
     * 把机体的冲刺状态落到宿主实体的冲刺旗标上。绑定期间两端每 tick 各调用一次。
     * <p>
     * <b>调用方每 tick 无脑调用，不做比较。</b> 比较放在本方法内，而且必须同时看旗标与速度属性上的
     * 冲刺修饰符：旗标有两个写入者——本模组，以及 {@code SynchedEntityData} 的同步（客户端），而同步
     * 那条只写旗标、不碰修饰符，因此「旗标已经对了、修饰符没跟上」是一个可达状态。只看旗标就会永久
     * 跳过那份修正，表现为冲刺的视野效果时有时无。
     * <p>
     * 两处都已一致时一次写入都不做：{@link LivingEntity#setSprinting(boolean)} 每次调用都会先摘掉
     * 再装回那个修饰符，而修饰符的增删会标记属性脏；服务端的每个脏属性每 tick 换一条属性同步包发给
     * 附近所有玩家。
     * <p>
     * <b>旗标取的是冲刺步态而不是按键。</b> 原版对这个旗标有三处消费：视野缩放（
     * {@link LivingEntity} 的客户端子类按速度属性算，冲刺修饰符正是 {@code +30%}）、攻击击退与暴击
     * 互斥、以及疾跑的速度加成。机体冲刺时这三处都应当与机体同义。
     * <p>
     * 断言点在两侧各自 tick 的末尾（服务端写包相位、客户端实体 tick 之后），因此渲染读到的永远是
     * 本 tick 写下的值；原版在 tick 中途的取消会在同一 tick 内被盖回来。
     *
     * @param sprinting 机体是否处于冲刺步态；解绑前的最后一笔传 {@code false}
     */
    default void applySprinting(boolean sprinting) {
        LivingEntity self = getHostEntity();
        AttributeInstance speed = self.getAttribute(Attributes.MOVEMENT_SPEED);
        boolean speedApplied = speed != null && speed.hasModifier(SPRINT_MODIFIER_ID);
        if (self.isSprinting() == sprinting && speedApplied == sprinting) return;
        self.setSprinting(sprinting);
    }
}
