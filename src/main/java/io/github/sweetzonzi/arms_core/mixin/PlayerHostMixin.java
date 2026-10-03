package io.github.sweetzonzi.arms_core.mixin;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import io.github.sweetzonzi.arms_core.common.MechaInputHandler;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.extensions.IPlayerExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 玩家宿主的 {@link IArmsHost} 实现。
 * <p>
 * 一个实体要成为机娘宿主，只需要实现该接口的五个方法；玩家经本 Mixin 获得这一身份，其它宿主
 * 形态（Doll 实体、AI 敌人）在自己的类定义上直接实现即可，不经 Mixin。
 * <p>
 * <b>两端都存在。</b> {@link Player} 在客户端同样存在，因此客户端玩家也实现本接口，其绑定字段
 * 填的是客户端 {@link ArmsCore} 实例。区别在 {@code ArmsCore.java#authoritative}：客户端实例不持有
 * KCC，因此 {@link #applyPose} / {@link #applyVelocity} 在客户端永远不会被调用。
 * <p>
 * <b>方法名不加 {@code @Unique}。</b> 这五个方法必须是目标类上真实可调用的接口实现——调用方会写
 * {@code ((IArmsHost) player).applyPose(...)}，方法被重命名或移除会让该调用点失去目标。只有
 * 字段用 {@code armsCore$} 前缀（同时配 {@code @Unique}）避免与其它模组的注入成员撞名。
 * <p>
 * 接口签名、参数基准与单位、以及被排除在接口之外的成员，见 `docs/IArmsHost宿主接口设计.md`。
 *
 * @author Sweetzonzi
 */
@Mixin(Player.class)
public class PlayerHostMixin implements IArmsHost {

    /** 本宿主当前承载的装配体；{@code null} 表示人类形态 */

    @Unique
    private volatile ArmsCore armsCore$controlledCore;

    /**
     * 飞行许可修饰符的 id。
     * <p>
     * 用固定 id 而不是每次新建：{@code AttributeInstance} 按 id 去重，因此重复绑定不会叠加多个修饰符，
     * 解绑时也只需要移除这一个。id 落在本模组命名空间下，不会与别的模组撞名。
     */
    @Unique
    private static final ResourceLocation ARMS_FLIGHT_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "mecha_flight");

    // ==========================================
    // IArmsHost
    // ==========================================

    @Override
    public Player getHostEntity() {
        return (Player) (Object) this;
    }

    @Override
    public ArmsCore getControlledArmsCore() {
        return this.armsCore$controlledCore;
    }

    /**
     * 建立 / 解除绑定。
     * <p>
     * <b>一个宿主实体至多绑定一个装配体。</b> 为了维持这条不变量，本方法覆盖了三条换绑路径：
     * 旧装配体（本宿主此前承载的那个）与新装配体（此前承载于别的宿主）都要先解除对方的绑定，
     * 再写入新值。调用方只需说「这个宿主现在承载谁」，两个方向的一致性由本方法负责。
     * <p>
     * 绑定确实变化时重置输入状态，否则状态机会卡在上一帧（例如 {@code jumpHeld} 永久蓄力）。
     * 同一装配体重复绑定直接返回，连输入都不重置——重置会让正在进行的跳跃蓄力凭空消失。
     */
    @Override
    public void setControlledArmsCore(ArmsCore core) {
        ArmsCore previous = this.armsCore$controlledCore;
        if (previous == core) return;
        if (previous != null) {
            previous.setHost(null);
        }
        if (core != null) {
            IArmsHost oldHost = core.getHost();
            if (oldHost != null && oldHost != this) {
                oldHost.setControlledArmsCore(null);
            }
        }
        this.armsCore$controlledCore = core;
        if (core != null) {
            core.setHost(this);
        } else {
            MechaInputHandler.resetInput(previous);
            if (previous != null) {
                // 解绑时清空分类状态：作用域栈与 pin 都属于这一次绑定，不该留给下一次
                previous.getPositionIntake().reset();
            }
        }
        armsCore$syncFlightAbility(core != null);
    }

    /**
     * 落地 KCC 的位置。
     * <p>
     * 参数是<b>胶囊中心</b>，而实体位置字段的语义是包围盒底面，因此这里先减
     * {@link MechaBodyPreset#HALF_TOTAL}。直接写胶囊中心会把宿主整体抬高
     * {@code HALF_TOTAL}（当前素体取值下为 {@code 1.2 m}）。
     * <p>
     * <b>朝向参数被忽略，这是有意的。</b> 朝向的权威在客户端：客户端上行视野偏航，服务端
     * {@code MechaControl#applyFacing} 把它绝对赋值给 KCC 的 {@code currentYaw}。若在这里把 KCC 的
     * 朝向写回 {@code yRot}，而客户端下一次上行读的又是这个字段，就构成一个跨网络的滞后反馈环
     * ——表现是视角持续抖动。位置没有这个问题：客户端上行的位置被服务端采纳后，立即被本轮 KCC 的
     * 产物覆盖，是单向下行。
     * <p>
     * 位置回写后重置坠落距离：实体被外部搬动时原版会按位置差累计坠落距离，不重置会让玩家持续
     * 受到坠落伤害（与兄弟仓库 Machine-Max 的 {@code SeatSubsystem#onTick} 对乘客所做的处理同形）。
     */
    @Override
    public void applyPose(Vec3 capsuleCenter, float yRot, float yHeadRot) {
        Player self = (Player) (Object) this;
        self.setPos(capsuleCenter.x, capsuleCenter.y - MechaBodyPreset.HALF_TOTAL, capsuleCenter.z);
        self.resetFallDistance();
    }

    /**
     * 落地 KCC 的线速度，并把三个轴从 KCC 的单位换算到实体单位。
     * <p>
     * 入参是 KCC 的原生单位（水平为每物理步位移、垂直为 m/s），而 {@code deltaMovement} 三个分量统一是
     * 每 tick 位移，因此两个方向要乘不同的因子：
     *
     * <pre>
     * 水平：× 20 × physicsStepSeconds   （每物理步位移 → 每 tick 位移）
     * 垂直：÷ 20                        （m/s → 每 tick 位移）
     * </pre>
     *
     * 服务端 {@code physicsStepSeconds} 为 {@code 0.01}（100 Hz），客户端为约 {@code 0.0167}（60 Hz），
     * 所以因子必须由调用方传入；写死任何一个都会让另一端的水平速度差 1.67 倍。
     * <p>
     * {@code noPhysics} 为真时该值不产生实际位移，写入的作用只是让外部查询看到 KCC 的真实速度。
     */
    @Override
    public void applyVelocity(Vec3 velocity, float physicsStepSeconds) {
        float horizontalScale = 20f * physicsStepSeconds;
        ((Player) (Object) this).setDeltaMovement(
                velocity.x * horizontalScale,
                velocity.y / 20f,
                velocity.z * horizontalScale);
    }

    // ==========================================
    // 内部
    // ==========================================

    /**
     * 绑定期间授予飞行许可，解除时收回。
     * <p>
     * 原版「飞行过久」检测的判据之一是 {@code Entity#move} 在 {@code noPhysics} 为真时不再设置
     * {@code verticalCollisionBelow}；该标志连续为真超过
     * {@code ServerGamePacketListenerImpl#getMaximumFlyingTicks}（玩家重力 {@code 0.08} 时为 80 tick）
     * 就会以 {@code multiplayer.disconnect.flying} 断开连接。悬停、滑翔、喷气以及被外力顶在空中
     * 这类「高度不掉且周围无方块」的形态都会触发它。
     * <p>
     * <b>用属性而不用 {@code Abilities#mayfly}。</b> 该字段已被 NeoForge 标记为
     * {@code @Deprecated}（"Modders are discouraged from setting {@link Abilities#mayfly} directly"），
     * 许可的正确判据是 {@code IPlayerExtension#mayFly}，它为真有两种来源：游戏模式，或
     * {@code NeoForgeMod#CREATIVE_FLIGHT} 属性的值大于 0。这里添加 / 移除一个
     * {@code neoforge:creative_flight} 上的 {@code ADD_VALUE} 修饰符（{@code +1}，
     * {@code BooleanAttribute} 的基准值为 {@code false}），因此不会覆盖别的模组或游戏模式给出的飞行许可，
     * 收回时也只撤掉自己那一个修饰符——这正是 {@code mayfly} 直写做不到的。
     */
    @Unique
    private void armsCore$syncFlightAbility(boolean bound) {
        if (!(((Object) this) instanceof ServerPlayer serverPlayer)) return;
        AttributeInstance flight = serverPlayer.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (flight == null) return;
        if (bound) {
            flight.addOrUpdateTransientModifier(new AttributeModifier(
                    ARMS_FLIGHT_MODIFIER_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        } else {
            flight.removeModifier(ARMS_FLIGHT_MODIFIER_ID);
        }
        // 许可被收回而玩家仍在飞：停掉飞行，否则客户端会保持一个服务端不允许的状态
        if (!((IPlayerExtension) serverPlayer).mayFly() && serverPlayer.getAbilities().flying) {
            serverPlayer.getAbilities().flying = false;
        }
        serverPlayer.onUpdateAbilities();
    }
}
