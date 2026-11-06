package io.github.sweetzonzi.arms_core.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.debug.MechaTelemetry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 客户端录制开关：{@code /armsc record [start|stop]}。
 *
 * <p>为什么单独一条命令而不是让服务端的 {@code /arms record} 分包通知：录制的两条通道里，客户端这一份
 * 只有本机进程拿得到（同步值、{@code SubPart.transform} / {@code oldTransform}），而服务端命令无法
 * 判断哪个客户端正在看这具机体。拆成两条命令后，两端各自在自己的终端里开关，不新增网络载荷，
 * 协议版本也不用动。</p>
 *
 * <p>客户端命令由 NeoForge 的 {@link RegisterClientCommandsEvent} 注册，只在客户端存在；
 * 它不会发到服务端，因此即使在别人的服务器上也能本地录制。</p>
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public final class ClientTelemetryCommand {

    private ClientTelemetryCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("armsc")
                .then(Commands.literal("record")
                        .executes(ctx -> record(ctx, null))
                        .then(Commands.literal("start").executes(ctx -> record(ctx, true)))
                        .then(Commands.literal("stop").executes(ctx -> record(ctx, false)))));
    }

    /**
     * @param start {@code null} 表示切换
     */
    private static int record(CommandContext<CommandSourceStack> ctx, Boolean start) {
        CommandSourceStack source = ctx.getSource();
        boolean beginning = start != null ? start : !MechaTelemetry.isClientRecording();
        if (beginning) {
            try {
                String description = MechaTelemetry.startClient();
                source.sendSuccess(() -> Component.literal("客户端录制已开始：" + description), false);
                return 1;
            } catch (IllegalStateException error) {
                source.sendFailure(Component.literal(error.getMessage()));
                return 0;
            }
        }
        String description = MechaTelemetry.stopClient();
        if (description == null) {
            source.sendFailure(Component.literal("客户端未在录制"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("客户端录制已停止：" + description), false);
        return 1;
    }
}
