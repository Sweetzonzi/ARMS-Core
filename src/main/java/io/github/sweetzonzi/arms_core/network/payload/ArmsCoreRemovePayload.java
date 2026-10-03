package io.github.sweetzonzi.arms_core.network.payload;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 服务端 → 客户端的装配体移除包。
 * <p>
 * 在 {@link ArmsCore} 注销或维度卸载时广播；客户端据此注销实例并释放其可视锚点。
 *
 * @param dimension 目标维度
 * @param coreId    装配体 UUID
 * @author Sweetzonzi
 */
public record ArmsCoreRemovePayload(
        ResourceKey<Level> dimension,
        UUID coreId
) implements CustomPacketPayload {

    /** 载荷 id */
    public static final Type<ArmsCoreRemovePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "core_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArmsCoreRemovePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceKey.streamCodec(Registries.DIMENSION),
                    ArmsCoreRemovePayload::dimension,
                    net.minecraft.core.UUIDUtil.STREAM_CODEC,
                    ArmsCoreRemovePayload::coreId,
                    ArmsCoreRemovePayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端处理器：先解绑本地玩家，再注销实例。重复到达（实例已不存在）只记日志。
     * <p>
     * <b>解绑必须在这里做。</b> 宿主实体上的绑定字段不随注册表清理而消失，只注销实例会让本地玩家
     * 仍认为自己承载着装配体，而 {@code client/ClientHostPoseEvents.java#onPlayerTickPost} 每 tick 都去读
     * 它的 {@code DATA_POS}——那条路径找不到实例，客户端就会停在上一次采样上。服务端一侧同形，见
     * {@link io.github.sweetzonzi.arms_core.common.MechaCoreRegistry#removeServer}。
     */
    public static void handle(ArmsCoreRemovePayload payload, IPayloadContext context) {
        Level level = context.player().level();
        if (!level.dimension().equals(payload.dimension())) {
            ARMS.LOGGER.error("[ARMS-Core] 从错误的维度收到移除请求：期望 {}，实际 {}",
                    payload.dimension().location(), level.dimension().location());
            return;
        }
        ArmsCore removed = MechaCoreRegistry.get(level, payload.coreId());
        if (context.player() instanceof IArmsHost host
                && removed != null
                && host.getControlledArmsCore() == removed) {
            host.setControlledArmsCore(null);
        }
        if (MechaCoreRegistry.removeClient(level, payload.coreId())) {
            ARMS.LOGGER.debug("[ARMS-Core] 客户端注销装配体 {}", payload.coreId());
        } else {
            ARMS.LOGGER.warn("[ARMS-Core] 收到移除不存在装配体的请求：{}", payload.coreId());
        }
    }
}
