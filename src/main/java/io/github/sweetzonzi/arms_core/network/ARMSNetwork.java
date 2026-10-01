package io.github.sweetzonzi.arms_core.network;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.network.payload.ArmsCoreCreatePayload;
import io.github.sweetzonzi.arms_core.network.payload.ArmsCoreRemovePayload;
import io.github.sweetzonzi.arms_core.network.payload.MechaCoreSyncPayload;
import io.github.sweetzonzi.arms_core.network.payload.MechaInputPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 载荷注册器。
 * <p>
 * 版本串 {@link #PROTOCOL_VERSION} 在两端必须一致：任意一侧的载荷集合、字段顺序或字段类型
 * 变化都必须同时提升该版本，否则 NeoForge 会拒绝连接。配合
 * {@code ArmsCore} 字段表的「只追加」纪律，这是双端线上格式的两道闸门。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID)
public final class ARMSNetwork {

    private ARMSNetwork() {
    }

    /** 线上协议版本；字段表或载荷集合发生变化时必须提升 */
    public static final String PROTOCOL_VERSION = "arms_core:2";

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        // 下行：服务端 → 客户端
        registrar.playToClient(
                ArmsCoreCreatePayload.TYPE,
                ArmsCoreCreatePayload.STREAM_CODEC,
                new MainThreadPayloadHandler<>(ArmsCoreCreatePayload::handle));
        registrar.playToClient(
                ArmsCoreRemovePayload.TYPE,
                ArmsCoreRemovePayload.STREAM_CODEC,
                new MainThreadPayloadHandler<>(ArmsCoreRemovePayload::handle));
        registrar.playToClient(
                MechaCoreSyncPayload.TYPE,
                MechaCoreSyncPayload.STREAM_CODEC,
                new MainThreadPayloadHandler<>(MechaCoreSyncPayload::handle));

        // 上行：客户端 → 服务端。处理器写入的是 volatile 快照与原子事件闩锁，
        // 与 MechaConditionSnapshot 现有的跨线程纪律一致。
        registrar.playToServer(
                MechaInputPayload.TYPE,
                MechaInputPayload.STREAM_CODEC,
                new MainThreadPayloadHandler<>(MechaInputPayload::handle));
    }
}
