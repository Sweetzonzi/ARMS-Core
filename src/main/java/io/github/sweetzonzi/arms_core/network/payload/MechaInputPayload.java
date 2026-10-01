package io.github.sweetzonzi.arms_core.network.payload;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import io.github.sweetzonzi.arms_core.common.MechaInputHandler;
import io.github.sweetzonzi.arms_core.common.control.MechaEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 客户端 → 服务端的上行输入包。
 * <p>
 * 客户端只表达意图，服务端合并环境状态与权限校验后写入 {@link ArmsCore} 的控制器。
 * <p>
 * <b>连续量可以丢，离散边沿不能丢。</b> {@code forward} / {@code strafe} / {@code viewYaw} /
 * {@code viewPitch} / {@code keyFlags} 是「最新值覆盖」语义，丢包只造成短暂迟滞；
 * 而 {@link MechaEvent}（含 {@link MechaEvent#JUMP_RELEASE} 跳跃松开）是单帧标记，
 * 被控制器在下一个物理步帧首一次性取走，只发一帧则丢包即永久丢失。因此事件载荷带单调递增的
 * {@link #eventSeq()}，由客户端在接下来若干个包里重复携带同一对 {@code (eventSeq, eventBits)}，
 * 服务端只在该序号大于自己记录的序号时投递一次。
 * <p>
 * 载荷方向不同于下行三个包：本包是唯一的 {@code playToServer} 载荷。
 *
 * @param coreId       目标装配体 UUID
 * @param forward      前后输入，[-1, 1]，正 = 前进
 * @param strafe       左右输入，[-1, 1]，正 = 左移
 * @param viewYaw      玩家水平朝向（度）。服务端用它绝对赋值控制器朝向（`MechaControl.applyFacing` →
 *                     `MechaCharacter.setViewYaw`），行走方向再由该朝向解出
 * @param viewPitch    玩家俯仰角（度）
 * @param keyFlags     {@link #BIT_JUMP} / {@link #BIT_SPRINT} / {@link #BIT_WALK} / {@link #BIT_SNEAK} 组成的位集
 * @param eventSeq     单调递增的事件序号；每次「产生一个事件」自增一次，而非每 tick 自增
 * @param eventBits    本包携带的事件类型集合，位序 = {@link MechaEvent#ordinal()}
 * @author Sweetzonzi
 */
public record MechaInputPayload(
        UUID coreId,
        float forward,
        float strafe,
        float viewYaw,
        float viewPitch,
        int keyFlags,
        int eventSeq,
        int eventBits
) implements CustomPacketPayload {

    /** 载荷 id */
    public static final Type<MechaInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "mecha_input"));

    /** 跳跃键按住（连续量） */
    public static final int BIT_JUMP = 1;
    /** 冲刺键按住（连续量） */
    public static final int BIT_SPRINT = 1 << 1;
    /** 慢走键按住（连续量） */
    public static final int BIT_WALK = 1 << 2;
    /** 蹲伏（连续量） */
    public static final int BIT_SNEAK = 1 << 3;

    /**
     * 手写编解码器。
     * <p>
     * 不用 {@code StreamCodec.composite}：它的重载只到 6 个字段，而本载荷有 8 个。
     * 字段顺序即线上顺序，改动它必须同时提升 {@code ARMSNetwork.PROTOCOL_VERSION}。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, MechaInputPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull MechaInputPayload decode(@NotNull RegistryFriendlyByteBuf buffer) {
                    UUID coreId = buffer.readUUID();
                    float forward = buffer.readFloat();
                    float strafe = buffer.readFloat();
                    float viewYaw = buffer.readFloat();
                    float viewPitch = buffer.readFloat();
                    int keyFlags = buffer.readVarInt();
                    int eventSeq = buffer.readVarInt();
                    int eventBits = buffer.readVarInt();
                    return new MechaInputPayload(coreId, forward, strafe, viewYaw, viewPitch,
                            keyFlags, eventSeq, eventBits);
                }

                @Override
                public void encode(@NotNull RegistryFriendlyByteBuf buffer, @NotNull MechaInputPayload value) {
                    buffer.writeUUID(value.coreId());
                    buffer.writeFloat(value.forward());
                    buffer.writeFloat(value.strafe());
                    buffer.writeFloat(value.viewYaw());
                    buffer.writeFloat(value.viewPitch());
                    buffer.writeVarInt(value.keyFlags());
                    buffer.writeVarInt(value.eventSeq());
                    buffer.writeVarInt(value.eventBits());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** {@code eventBits} 中是否置位了给定事件的位。 */
    public boolean hasEvent(MechaEvent event) {
        return (eventBits & (1 << event.ordinal())) != 0;
    }

    /**
     * 服务端处理器：定位实例、校验控制权、合并环境状态后写入控制器。
     * <p>
     * 事件按序号差投递，因此重发窗口内的重复包不会重复触发（幂等）。
     */
    public static void handle(MechaInputPayload payload, IPayloadContext context) {
        ArmsCore core = MechaCoreRegistry.get(context.player().level(), payload.coreId());
        if (core == null) return;
        MechaInputHandler.apply(payload, core, context.player().getUUID());
    }
}
