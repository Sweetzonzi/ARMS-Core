package io.github.sweetzonzi.arms_core.common;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * 机娘宿主 —— 承载一份 {@link ArmsCore} 的实体。
 * <p>
 * 本接口只回答两件事：
 * <ol>
 *   <li><b>绑定关系</b>：这个宿主当前承载哪一个装配体。这是「是不是机娘」的唯一判据。</li>
 *   <li><b>位姿落地</b>：{@link ArmsCore} 把 KCC 算出的位姿写进宿主实体。</li>
 * </ol>
 * 其余一切（相机、交互、背包、无敌帧、护甲、附魔、事件、输入采集、伤害落地）都由原版或别的
 * 机制承担，不进本接口。逐条理由见 `docs/IArmsHost宿主接口设计.md` §四。
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
     * @param capsuleCenter 胶囊中心的世界坐标（<b>不是</b>包围盒底面；实体位置字段的语义是底面，
     *                      两者相差 {@link io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset#HALF_TOTAL}）
     * @param yRot          度制偏航；玩家宿主不写，见上
     * @param yHeadRot      度制头部偏航；玩家宿主不写，见上
     */
    void applyPose(Vec3 capsuleCenter, float yRot, float yHeadRot);

    /**
     * 把控制器刚体的线速度写进宿主实体。由 {@link ArmsCore} 在服务端主线程调用。
     * <p>
     * <b>三个轴同为 m/s。</b> 刚体路线统一了速度的量纲（`docs/角色控制器-刚体动力学方案.md` §6），
     * 不再有「水平是每物理步位移、垂直是 m/s」那种混合语义，因此换算只剩一个因子：
     *
     * <pre>
     * m/s → 每 tick 位移：× 20
     * </pre>
     * <p>
     * 宿主实体的 {@code net.minecraft.world.entity.Entity#setDeltaMovement} 三个分量统一是
     * <b>每 tick 位移</b>，物理步频与服务端 tick 的比值就是 {@code 20}（每 tick 20 个物理步），
     * 与物理空间自己的 {@code baseStep} 无关，因此不需要再传物理步长。
     * <p>
     * {@code noPhysics} 为真时该值不产生实际位移，写入的作用只是让外部查询看到真实速度。
     *
     * @param velocity 控制器线速度 (m/s)，三个轴同单位
     */
    void applyVelocity(Vec3 velocity);
}
