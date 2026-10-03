package io.github.sweetzonzi.arms_core.network.payload;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
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
 * {@code hostEntityId} 由服务端从 {@code ArmsCore#getBoundHostEntityId} 取值：未绑定宿主时为
 * {@link #NO_HOST_ENTITY}，绑定宿主时为该宿主的网络 id。客户端只在它与本地玩家实体 id 相等时
 * 建立绑定。
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
     * 客户端处理器：按 {@code coreId} 幂等建立并注册客户端实例，再应用全量初值，最后按
     * {@code hostEntityId} 建立本地玩家的绑定。
     * <p>
     * 已存在同 {@code coreId} 的实例时复用并按增量方式应用初值，而不是覆盖重建——
     * 重建会让正在插值的可视锚点跳变。
     * <p>
     * <b>{@code hostEntityId} 的比对不可省。</b> 创建包按维度广播，同一个 {@code hostEntityId}
     * 会被维度里每个客户端收到，漏掉比对会让每个玩家都把自己绑定到同一个装配体。
     * 两条绑定路径都不能少：新建分支与复用分支都要写，因为换维度、重生、登录后的补发走的是
     * 复用分支（客户端实例通常已经存在），只在新建分支写会让这些路径全部失去绑定。
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
        bindLocalHost(payload, context, core);
        ARMS.LOGGER.debug("[ARMS-Core] 客户端{}装配体 {}：{} 项初值", created ? "创建" : "复用",
                payload.coreId(), initial == null ? 0 : initial.size());
    }

    /**
     * 若本包指向的宿主实体就是本地玩家，则把客户端实例绑定到本地玩家实体上。
     * <p>
     * 不匹配时还要处理反向：本地玩家此前绑定的装配体若正是本包这个 {@code coreId}，说明服务端已经
     * 把它交给了别人（{@code hostEntityId} 变了），本地必须解绑，否则客户端会继续按一个已经不属于
     * 自己的装配体的位姿摆放玩家。
     */
    private static void bindLocalHost(ArmsCoreCreatePayload payload, IPayloadContext context, ArmsCore core) {
        if (!(context.player() instanceof IArmsHost host)) return;
        if (payload.hostEntityId() == host.getHostEntity().getId()) {
            host.setControlledArmsCore(core);
        } else if (host.getControlledArmsCore() == core) {
            host.setControlledArmsCore(null);
        }
    }
}
