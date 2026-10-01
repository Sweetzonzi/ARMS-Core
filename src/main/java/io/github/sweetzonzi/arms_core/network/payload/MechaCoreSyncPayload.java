package io.github.sweetzonzi.arms_core.network.payload;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端的位姿与状态增量包。
 * <p>
 * 载荷为 {@link ArmsCore} 自身的 {@code SynchedEntityData.packDirty()} 结果，因此只在
 * 数值确实变化时携带条目（变更门控由原版按值判等完成）。
 * <p>
 * <b>顺序前提</b>：客户端必须先持有该 {@code coreId} 的 {@link ArmsCore}
 * （由 {@link ArmsCoreCreatePayload} 建立）。未知 {@code coreId} 的增量包被丢弃并计数告警，
 * 不抛异常——服务端在同一 tick 内先发创建包再发增量包，且两者走同一连接，顺序天然成立。
 * <p>
 * 编解码沿用 Machine-Max {@code SubPartSyncPayload} 的写法：逐条
 * {@link SynchedEntityData.DataValue#write} 后写 {@code 255} 终结符。
 *
 * @param coreId 目标装配体 UUID
 * @param dirty  本批变化的字段
 * @author Sweetzonzi
 */
public record MechaCoreSyncPayload(
        UUID coreId,
        List<SynchedEntityData.DataValue<?>> dirty
) implements CustomPacketPayload {

    /** 载荷 id */
    public static final Type<MechaCoreSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "mecha_core_sync"));

    /** DataValue 列表的终结符 */
    private static final int TERMINATOR = 255;

    public static final StreamCodec<RegistryFriendlyByteBuf, MechaCoreSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull MechaCoreSyncPayload decode(@NotNull RegistryFriendlyByteBuf buffer) {
                    UUID coreId = buffer.readUUID();
                    List<SynchedEntityData.DataValue<?>> dirty = new ArrayList<>();
                    int id;
                    while ((id = buffer.readUnsignedByte()) != TERMINATOR) {
                        dirty.add(SynchedEntityData.DataValue.read(buffer, id));
                    }
                    return new MechaCoreSyncPayload(coreId, dirty);
                }

                @Override
                public void encode(@NotNull RegistryFriendlyByteBuf buffer, @NotNull MechaCoreSyncPayload value) {
                    buffer.writeUUID(value.coreId());
                    for (SynchedEntityData.DataValue<?> dataValue : value.dirty()) {
                        dataValue.write(buffer);
                    }
                    buffer.writeByte(TERMINATOR);
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端处理器：按 {@code coreId} 解析实例并应用增量。
     * <p>
     * 未知 {@code coreId} 只计入告警，不抛异常（R9）。
     */
    public static void handle(MechaCoreSyncPayload payload, IPayloadContext context) {
        ArmsCore core = MechaCoreRegistry.get(context.player().level(), payload.coreId());
        if (core == null) {
            MechaCoreRegistry.reportUnknownSync(payload.coreId());
            return;
        }
        core.getSyncedData().assignValues(payload.dirty());
    }
}
