package io.github.sweetzonzi.arms_core.network;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.control.MechaEvent;
import io.github.sweetzonzi.arms_core.network.payload.ArmsCoreCreatePayload;
import io.github.sweetzonzi.arms_core.network.payload.ArmsCoreRemovePayload;
import io.github.sweetzonzi.arms_core.network.payload.MechaCoreSyncPayload;
import io.github.sweetzonzi.arms_core.network.payload.MechaInputPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Rotations;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四个载荷的编解码往返测试。
 * <p>
 * 承载这些包的是 TCP 语义的连接，格式错配的表现是「连不上」或「解出乱数据」，
 * 而不是崩溃；如果不在这里固定住字段顺序，将来任何一次字段调整都只能靠实机联调发现。
 * <p>
 * 用 {@link RegistryAccess#EMPTY} 作为维度注册表：本模组的载荷只用到维度 {@code ResourceKey}
 * 的编解码，不需要真实的维度注册表内容。
 *
 * @author Sweetzonzi
 */
class ARMSNetworkCodecTest {

    /**
     * 造一个只用于编解码往返的缓冲。
     * <p>
     * 连接类型取 {@link ConnectionType#OTHER}：本测试不经过真实连接，也不涉及任何按对端平台分支的
     * 编解码路径，只验证字段顺序与取值。这里用带连接上下文的三参数重载，因为不带上下文的那条
     * 在 NeoForge 里标了 {@code @Deprecated}（`net.minecraft.network.RegistryFriendlyByteBuf`，
     * 见 `build/moddev/artifacts` 下的 neoforge sources jar）。
     */
    private static RegistryFriendlyByteBuf newBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
    }

    @Test
    void inputPayloadRoundTripsAllEightFields() {
        MechaInputPayload original = new MechaInputPayload(
                UUID.randomUUID(), 0.75f, -0.25f, 123.5f, -42.5f,
                MechaInputPayload.BIT_JUMP | MechaInputPayload.BIT_SPRINT,
                7, 1 << MechaEvent.DODGE.ordinal());

        RegistryFriendlyByteBuf buffer = newBuffer();
        MechaInputPayload.STREAM_CODEC.encode(buffer, original);
        MechaInputPayload decoded = MechaInputPayload.STREAM_CODEC.decode(buffer);

        assertEquals(original.coreId(), decoded.coreId());
        assertEquals(original.forward(), decoded.forward());
        assertEquals(original.strafe(), decoded.strafe());
        assertEquals(original.viewYaw(), decoded.viewYaw());
        assertEquals(original.viewPitch(), decoded.viewPitch());
        assertEquals(original.keyFlags(), decoded.keyFlags());
        assertEquals(original.eventSeq(), decoded.eventSeq());
        assertEquals(original.eventBits(), decoded.eventBits());
    }

    @Test
    void inputPayloadEventBitsMapToTheRightEnum() {
        MechaInputPayload payload = new MechaInputPayload(
                UUID.randomUUID(), 0f, 0f, 0f, 0f, 0, 1,
                (1 << MechaEvent.DODGE.ordinal()) | (1 << MechaEvent.JUMP_RELEASE.ordinal()));

        assertTrue(payload.hasEvent(MechaEvent.DODGE));
        assertTrue(payload.hasEvent(MechaEvent.JUMP_RELEASE));
        assertFalse(payload.hasEvent(MechaEvent.STUN));
    }

    /**
     * 事件位序即 {@link MechaEvent#ordinal()}，因此枚举只能追加。
     * <p>
     * 中间插入会让该位置之后的位全部平移：老客户端发来的 {@code DODGE} 位会在新服务端解成别的
     * 事件，而两端都看不出错。这份清单把顺序钉死，改动它必须同时提升
     * {@link ARMSNetwork#PROTOCOL_VERSION}。
     */
    @Test
    void eventOrdinalsAreAppendOnly() {
        MechaEvent[] expected = {
                MechaEvent.ATTACK_PRIMARY,
                MechaEvent.ATTACK_SECONDARY,
                MechaEvent.USE_ITEM,
                MechaEvent.INTERACT,
                MechaEvent.TOGGLE_DRIVE,
                MechaEvent.TOGGLE_PRONE,
                MechaEvent.TOGGLE_FLY,
                MechaEvent.DODGE,
                MechaEvent.HURT,
                MechaEvent.STUN,
                MechaEvent.MOUNT,
                MechaEvent.DISMOUNT,
                MechaEvent.JUMP_RELEASE,
        };
        assertEquals(expected.length, MechaEvent.values().length,
                "事件枚举的数量变了；新事件只能追加在末尾");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(i, expected[i].ordinal(),
                    "事件 " + expected[i] + " 的 ordinal 变了；说明在中间插入或删除过常量");
        }
    }

    @Test
    void syncPayloadRoundTripsSyncedEntityData() {
        // 用真实的 ArmsCore accessor 构造两条数据：id 与序列化器都来自实际字段表
        List<SynchedEntityData.DataValue<?>> dirty = List.of(
                SynchedEntityData.DataValue.create(ArmsCore.DATA_POS, new Vector3f(1.5f, -2.5f, 3.5f)),
                SynchedEntityData.DataValue.create(ArmsCore.DATA_POSTURE, "crouch"));
        MechaCoreSyncPayload original = new MechaCoreSyncPayload(UUID.randomUUID(), dirty);

        RegistryFriendlyByteBuf buffer = newBuffer();
        MechaCoreSyncPayload.STREAM_CODEC.encode(buffer, original);
        MechaCoreSyncPayload decoded = MechaCoreSyncPayload.STREAM_CODEC.decode(buffer);

        assertEquals(original.coreId(), decoded.coreId());
        assertEquals(2, decoded.dirty().size());
        assertEquals(ArmsCore.DATA_POS.id(), decoded.dirty().get(0).id());
        assertEquals(new Vector3f(1.5f, -2.5f, 3.5f), decoded.dirty().get(0).value());
        assertEquals(ArmsCore.DATA_POSTURE.id(), decoded.dirty().get(1).id());
        assertEquals("crouch", decoded.dirty().get(1).value());
    }

    @Test
    void createPayloadCarriesFullInitialState() {
        List<SynchedEntityData.DataValue<?>> initial = List.of(
                SynchedEntityData.DataValue.create(ArmsCore.DATA_YAW, new Rotations(0f, 90f, 0f)),
                SynchedEntityData.DataValue.create(ArmsCore.DATA_GAIT, "sprint"),
                SynchedEntityData.DataValue.create(ArmsCore.DATA_JUMP_CHARGING, true));
        ArmsCoreCreatePayload original = new ArmsCoreCreatePayload(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld")),
                UUID.randomUUID(), 42, initial);

        RegistryFriendlyByteBuf buffer = newBuffer();
        ArmsCoreCreatePayload.STREAM_CODEC.encode(buffer, original);
        ArmsCoreCreatePayload decoded = ArmsCoreCreatePayload.STREAM_CODEC.decode(buffer);

        assertEquals(original.dimension(), decoded.dimension());
        assertEquals(original.coreId(), decoded.coreId());
        assertEquals(42, decoded.hostEntityId());
        assertNotNull(decoded.initial());
        assertEquals(3, decoded.initial().size());
        assertEquals(true, decoded.initial().get(2).value());
    }

    @Test
    void createPayloadToleratesAllDefaults() {
        // 服务端上全部字段都等于默认值时 getNonDefaultValues() 返回 null，
        // 客户端应保持默认值而不是抛异常
        ArmsCoreCreatePayload original = new ArmsCoreCreatePayload(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld")),
                UUID.randomUUID(), ArmsCoreCreatePayload.NO_HOST_ENTITY, null);

        RegistryFriendlyByteBuf buffer = newBuffer();
        ArmsCoreCreatePayload.STREAM_CODEC.encode(buffer, original);
        ArmsCoreCreatePayload decoded = ArmsCoreCreatePayload.STREAM_CODEC.decode(buffer);

        assertNull(decoded.initial());
        assertEquals(ArmsCoreCreatePayload.NO_HOST_ENTITY, decoded.hostEntityId());
    }

    @Test
    void removePayloadRoundTrips() {
        ArmsCoreRemovePayload original = new ArmsCoreRemovePayload(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:the_nether")),
                UUID.randomUUID());

        RegistryFriendlyByteBuf buffer = newBuffer();
        ArmsCoreRemovePayload.STREAM_CODEC.encode(buffer, original);
        ArmsCoreRemovePayload decoded = ArmsCoreRemovePayload.STREAM_CODEC.decode(buffer);

        assertEquals(original.dimension(), decoded.dimension());
        assertEquals(original.coreId(), decoded.coreId());
    }

    @Test
    void fieldTableHasExactlyEightContiguousIds() {
        // 字段表只允许在末尾追加：id 必须是 [0, 8) 且互不相同。
        // 中间插入或删除会让其后的 id 全部平移，双端线上格式静默错配
        int[] ids = {
                ArmsCore.DATA_POS.id(),
                ArmsCore.DATA_VEL.id(),
                ArmsCore.DATA_YAW.id(),
                ArmsCore.DATA_POSTURE.id(),
                ArmsCore.DATA_GAIT.id(),
                ArmsCore.DATA_VERTICAL.id(),
                ArmsCore.DATA_ENERGY.id(),
                ArmsCore.DATA_JUMP_CHARGING.id(),
        };
        for (int expected = 0; expected < ids.length; expected++) {
            assertEquals(expected, ids[expected],
                    "字段表第 " + expected + " 项的 id 变了；说明中间插入或删除过 accessor");
        }
    }

    @Test
    void protocolVersionIsNamespaced() {
        assertTrue(ARMSNetwork.PROTOCOL_VERSION.startsWith(ARMS.MOD_ID),
                "协议版本串应带模组命名空间，避免与其他模组的版本串混淆");
    }
}
