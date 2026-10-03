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
     * 按 {@code DATA_POS} 摆放本地玩家实体。
     * <p>
     * 位置换算与宿主实现共用同一条约定：{@code DATA_POS} 是<b>胶囊中心</b>，而实体位置字段的语义是
     * 包围盒底面，因此这里要减 {@link MechaBodyPreset#HALF_TOTAL}。把胶囊中心直接写进去会让客户端
     * 玩家比服务端高出一个 {@code HALF_TOTAL}。
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
     * {@code DATA_YAW} 的消费者是渲染路径（{@code client/ClientMechaAnchor.java} 的插值，以及
     * {@code client/MechaPlayerRenderer.java} 的调试参考物），不是玩家实体的朝向字段。
     */
    private static void applySyncedPose(LocalPlayer player, ArmsCore core) {
        org.joml.Vector3f synced = core.getSyncedData().get(ArmsCore.DATA_POS);

        HostPositionIntake intake = core.getPositionIntake();
        int outerDepth = intake.enterScope(HostPositionIntake.SCOPE_RUNTIME_WRITEBACK);
        try {
            player.setPos(synced.x, synced.y - MechaBodyPreset.HALF_TOTAL, synced.z);
        } finally {
            intake.exitScope(outerDepth);
        }
        player.setDeltaMovement(Vec3.ZERO);
    }
}
