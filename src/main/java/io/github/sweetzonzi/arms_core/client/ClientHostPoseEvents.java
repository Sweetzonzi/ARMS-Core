package io.github.sweetzonzi.arms_core.client;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.HostPositionIntake;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 客户端按服务端同步来的位姿摆放本地玩家实体。
 * <p>
 * <b>为什么必须单独一个 {@code Dist.CLIENT} 类。</b> {@link LocalPlayer} 只在客户端发行版里存在，
 * 把它写进双端共用的类里会让专用服务端在构造事件订阅者时就去解析该类，加载期直接报
 * 「Attempted to load class net/minecraft/client/player/LocalPlayer for invalid dist DEDICATED_SERVER」。
 * 因此 {@code noPhysics} 与坠距重置留在 {@code common/PlayerHostEvents.java}（双端一致），本类只放
 * 客户端专属的那一半。
 * <p>
 * <b>位置权威的方向</b>：服务端从不把玩家自己的位置发给他本人的客户端（原版如此），位置由客户端
 * 上行、服务端采纳。因此本类写的位置会经 {@code LocalPlayer#sendPosition} 回到服务端，服务端玩家
 * 代理因此落后约一至两 tick，这是既定代价。
 * <p>
 * <b>为什么写的位置赶得上发包</b>：{@code LocalPlayer#tick} 先调 {@code super.tick()}（整个
 * {@code Player#tick}，末尾派发本事件）然后才调 {@code LocalPlayer#sendPosition()}。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public final class ClientHostPoseEvents {

    private ClientHostPoseEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || event.getEntity() != player) return;

        ArmsCore core = ((IArmsHost) player).getControlledArmsCore();
        if (core == null) return;
        applySyncedPose(player, core);
    }

    /**
     * 按呈现位姿摆放本地玩家实体。
     * <p>
     * 位置换算与宿主实现共用同一条约定：{@code DATA_POS} 是<b>胶囊中心</b>，而实体位置字段的语义是
     * 包围盒底面，因此这里要减 {@link MechaBodyPreset#HALF_TOTAL}。把胶囊中心直接写进去会让客户端
     * 玩家比服务端高出一个 {@code HALF_TOTAL}。
     * <p>
     * <b>取的是呈现区间的右端，不是 {@code DATA_POS} 本身。</b> 同步按 20 Hz 到、客户端按 20 Hz 走，
     * 两者相位不同步；直接把 {@code DATA_POS} 写进实体，实体的 {@code xo/x} 这一对就会在某些 tick
     * 上相等（压扁，画面冻结）、在另一些 tick 上跨两个样本（速度翻倍）。呈现缓冲
     * （{@code common/ArmsCore.java#presentationPose}）按客户端 tick 推进样对，实体与模型因此
     * 走同一条时间轴，代价是固定的一至两个 tick 呈现延迟。
     * <p>
     * <b>推进必须早于本方法。</b> 推进点在 {@code client/MechaPoseDelayTick.java#onClientTickPre}
     * （{@code ClientTickEvent.Pre}），位于实体 tick 之前，因此本相位读到的右端就是本 tick 的那一份；
     * 渲染期按 {@code partialTick} 在 {@code xo}（本方法上一次写入的值）与本次写入之间插值，与子零件
     * 调试框读的那一对样完全一致。若推进挪到实体 tick 之后，本方法只会读到上一 tick 的右端，
     * 模型整体比子零件调试框落后一份样本，行走时相差 0.3 m 上下。
     * <p>
     * 写位置后必须把速度清为零矢量：{@code Entity#setPos} 自己不清 {@code deltaMovement}，而下一
     * tick 的 {@code aiStep} / {@code travel} 会按客户端自己的速度再把实体推走。原版处理
     * {@code ClientboundPlayerPositionPacket} 的 {@code ClientPacketListener#handleMovePlayer}
     * 同样这么做。
     * <p>
     * <b>这次写入落在第 4 类作用域内。</b> 它和服务端的
     * {@code common/ArmsCoreServerEvents.java#applyPoseToHost} 是同一条链的两端（服务端把 KCC 位姿写进
     * 宿主实体、客户端按 {@code DATA_POS} 摆放本机玩家），因此在
     * {@code common/HostPositionIntake.java} 的分类里同属「本模组运行时回写」：不摄入，但按目标推进锚点。
     * 不压栈的话，客户端会在每个 tick 把这次镜像写入判成一次外部位移。
     * <p>
     * <b>不写朝向。</b> 视野偏航是本机输入，服务端只是接收方：{@code ARMSClient} 把玩家实体的
     * {@code yRot} 上行，服务端 {@code MechaControl#applyFacing} 把它绝对赋值给 KCC 的
     * {@code currentYaw}，{@code DATA_YAW} 只是这个值的回显。若把回显写回 {@code yRot}，就形成
     * 「本机视角 → 上行 → 服务端 → 下行 → 本机视角」的滞后反馈环，表现为视角持续抖动。
     * {@code DATA_YAW} 的消费者是渲染路径（{@code common/ArmsCore.java#lerpPresentationYaw}，以及
     * {@code client/MechaPlayerRenderer.java} 的调试参考物），不是玩家实体的朝向字段。
     */
    private static void applySyncedPose(LocalPlayer player, ArmsCore core) {
        // 复用缓冲而不是直接持有 DataItem 里的对象：lerpPresentationPosition 会覆写 dest
        org.joml.Vector3f target = new org.joml.Vector3f();
        // 取区间右端（partialTick = 1）：实体自己的 xo 是上一 tick 写入的值，渲染时
        // lerp(xo, x, partialTick) 因此恰好等于呈现区间的插值结果，实体与模型没有帧内错位
        if (!core.lerpPresentationPosition(1f, target)) {
            // 首包之前（还凑不出呈现样本）按同步值摆放，一次性的落地偏差，之后由缓冲接管
            target.set(core.getSyncedData().get(ArmsCore.DATA_POS));
        }

        HostPositionIntake intake = core.getPositionIntake();
        int outerDepth = intake.enterScope(HostPositionIntake.SCOPE_RUNTIME_WRITEBACK);
        try {
            player.setPos(target.x, target.y - MechaBodyPreset.HALF_TOTAL, target.z);
        } finally {
            intake.exitScope(outerDepth);
        }
        player.setDeltaMovement(Vec3.ZERO);
        // 冲刺旗标：客户端这一份决定视野缩放（原版按 MOVEMENT_SPEED 上的冲刺修饰符算），而原版自己的
        // 维持条件读的是傀儡宿主的水平碰撞与前进输入，冲刺途中会被反复取消。断言必须落在实体 tick
        // 之后、渲染之前——视野是渲染期按当时的旗标算的，中途取消会直接表现为视野退回常态。
        ((IArmsHost) player).applySprinting(core.isSprintGait());
    }
}
