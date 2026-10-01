package io.github.sweetzonzi.arms_core.network.payload;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端的装配体创建包。
 * <p>
 * 在 {@link ArmsCore} 于服务端注册后广播；玩家登录、换维度、重生 / 重连后补发。
 * <p>
 * <b>全量初值不可省</b>：增量包只在数值变化时发出，而「客户端刚创建」与「服务端上次变更」
 * 之间可能相隔任意久，因此创建包必须携带 {@code getNonDefaultValues()}。服务端上全部字段
 * 都等于默认值（即该方法返回 {@code null}）时，客户端保持默认值即可。
 * <p>
 * {@code hostEntityId} 在宿主接入之前恒为 {@code -1}（无实体宿主）。
 *
 * @param dimension    目标维度，客户端据此校验自己是否在正确维度
 * @param coreId       装配体 UUID
 * @param hostEntityId 宿主实体 id，无实体宿主为 {@code -1}
 * @param initial      {@code getNonDefaultValues()} 的全量非默认字段，可为 {@code null}
 * @author Sweetzonzi
 */
public record ArmsCoreCreatePayload(
        ResourceKey<Level> dimension,
        UUID coreId,
        int hostEntityId,
        @Nullable List<SynchedEntityData.DataValue<?>> initial
) implements CustomPacketPayload {

    /** 载荷 id */
    public static final Type<ArmsCoreCreatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "core_create"));

    /** 无实体宿主时的 {@code hostEntityId} 取值 */
    public static final int NO_HOST_ENTITY = -1;

    /** DataValue 列表的终结符 */
    private static final int TERMINATOR = 255;

    public static final StreamCodec<RegistryFriendlyByteBuf, ArmsCoreCreatePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull ArmsCoreCreatePayload decode(@NotNull RegistryFriendlyByteBuf buffer) {
                    ResourceKey<Level> dimension = buffer.readResourceKey(Registries.DIMENSION);
                    UUID coreId = buffer.readUUID();
                    int hostEntityId = buffer.readInt();
                    List<SynchedEntityData.DataValue<?>> initial = null;
                    int id;
                    while ((id = buffer.readUnsignedByte()) != TERMINATOR) {
                        if (initial == null) initial = new ArrayList<>();
                        initial.add(SynchedEntityData.DataValue.read(buffer, id));
                    }
                    return new ArmsCoreCreatePayload(dimension, coreId, hostEntityId, initial);
                }

                @Override
                public void encode(@NotNull RegistryFriendlyByteBuf buffer, @NotNull ArmsCoreCreatePayload value) {
                    buffer.writeResourceKey(value.dimension());
                    buffer.writeUUID(value.coreId());
                    buffer.writeInt(value.hostEntityId());
                    if (value.initial() != null) {
                        for (SynchedEntityData.DataValue<?> dataValue : value.initial()) {
                            dataValue.write(buffer);
                        }
                    }
                    buffer.writeByte(TERMINATOR);
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端处理器：按 {@code coreId} 幂等建立并注册客户端实例，再应用全量初值。
     * <p>
     * 已存在同 {@code coreId} 的实例时复用并按增量方式应用初值，而不是覆盖重建——
     * 重建会让正在插值的可视锚点跳变。
     */
    public static void handle(ArmsCoreCreatePayload payload, IPayloadContext context) {
        Level level = context.player().level();
        if (!level.dimension().equals(payload.dimension())) {
            ARMS.LOGGER.error("[ARMS-Core] 从错误的维度收到创建请求：期望 {}，实际 {}",
                    payload.dimension().location(), level.dimension().location());
            return;
        }
        ArmsCore core = MechaCoreRegistry.get(level, payload.coreId());
        boolean created = false;
        if (core == null) {
            core = ArmsCore.newClientInstance(level, payload.coreId());
            MechaCoreRegistry.addClient(core);
            created = true;
        }
        List<SynchedEntityData.DataValue<?>> initial = payload.initial();
        if (initial != null) {
            core.getSyncedData().assignValues(initial);
        }
        ARMS.LOGGER.debug("[ARMS-Core] 客户端{}装配体 {}：{} 项初值", created ? "创建" : "复用",
                payload.coreId(), initial == null ? 0 : initial.size());
    }
}
