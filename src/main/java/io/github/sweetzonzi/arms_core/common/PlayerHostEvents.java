package io.github.sweetzonzi.arms_core.common;

import io.github.sweetzonzi.arms_core.ARMS;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 宿主实体每 tick 的 {@code noPhysics} 置位与坠距重置，双端共用。
 * <p>
 * <b>为什么每次都要置位</b>：{@code net.minecraft.world.entity.player.Player#tick} 的首行把它重置为
 * {@code isSpectator()}。置位后 {@code net.minecraft.world.entity.Entity#move} 直接位移而不做碰撞
 * 求解，KCC 承担全部地形交互。
 * <p>
 * <b>为什么是这个相位</b>：{@code Player#tick} 在开头与结尾分别派发
 * {@code net.neoforged.neoforge.event.tick.PlayerTickEvent} 的 {@code Pre} 与 {@code Post}，而本量每
 * tick 都被首行清掉。末尾置位不改变本 tick 内实体自身的移动（{@code aiStep} / {@code travel} /
 * {@code Entity#move} 都跑在此前），真正需要它为真的读点也都在 tick 之外——
 * {@code net.minecraft.server.network.ServerGamePacketListenerImpl#handleMovePlayer} 在包处理时读它，
 * 而包处理跨 tick 发生。
 * <p>
 * <b>本类只做双端一致的那件事。</b> 服务端的位置回写不在这里（会被
 * {@code ServerGamePacketListenerImpl#tick} 的基准坐标覆盖，见
 * {@link ArmsCoreServerEvents#syncToClients} 的注释）；客户端按同步位姿摆放自己的实体在
 * {@code client/ClientHostEvents.java}，那个类标注了 {@code Dist.CLIENT}，避免专用服务端 JVM 去解析
 * 仅客户端存在的 {@code net.minecraft.client.player.LocalPlayer}。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID)
public final class PlayerHostEvents {

    private PlayerHostEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof Player player)) return;
        player.noPhysics = true;
        player.resetFallDistance();
    }
}
