package io.github.sweetzonzi.arms_core.common.part;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPartData;
import io.github.sweetzonzi.machine_max.common.mech.part.PartFactory;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.data.ConnectionData;
import io.github.sweetzonzi.machine_max.util.data.PosRot;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 身体组成快照。素体以资源 ID 引用，成员和连接记录实际实例状态。 */
public record ArmsBodyData(String name, UUID corePartId, List<AbstractPartData> parts, List<ConnectionData> connections,
                           Optional<ResourceLocation> frameId) {
    private static final Codec<ArmsBodyData> SERVER_CODEC = createCodec(false);
    private static final Codec<ArmsBodyData> CLIENT_CODEC = createCodec(true);

    public ArmsBodyData {
        parts = List.copyOf(parts);
        connections = List.copyOf(connections);
    }

    /** 旧存档没有连接字段，仍按独立成员恢复。 */
    public ArmsBodyData(String name, UUID corePartId, List<AbstractPartData> parts) {
        this(name, corePartId, parts, List.of());
    }

    public ArmsBodyData(String name, UUID corePartId, List<AbstractPartData> parts, List<ConnectionData> connections) {
        this(name, corePartId, parts, connections, Optional.empty());
    }

    /** 以本端已发布的部件定义分派成员 Codec。 */
    public static Codec<ArmsBodyData> codec(boolean clientSide) {
        return clientSide ? CLIENT_CODEC : SERVER_CODEC;
    }

    private static Codec<ArmsBodyData> createCodec(boolean clientSide) {
        Codec<AbstractPartData> partCodec = new Codec<>() {
            @Override
            public <T> DataResult<Pair<AbstractPartData, T>> decode(DynamicOps<T> ops, T input) {
                return PartFactory.decodeData(clientSide, ops, input).map(part -> Pair.of(part, ops.empty()));
            }

            @Override
            public <T> DataResult<T> encode(AbstractPartData part, DynamicOps<T> ops, T prefix) {
                return PartFactory.encodeData(clientSide, ops, part).flatMap(ops::getMap)
                        .flatMap(map -> ops.mergeToMap(prefix, map));
            }
        };
        return RecordCodecBuilder.<ArmsBodyData>create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "ArmsCore").forGetter(ArmsBodyData::name),
                UUIDUtil.CODEC.fieldOf("core_part").forGetter(ArmsBodyData::corePartId),
                partCodec.listOf().optionalFieldOf("parts", List.of()).forGetter(ArmsBodyData::parts),
                ConnectionData.CODEC.listOf().optionalFieldOf("connections", List.of()).forGetter(ArmsBodyData::connections),
                ResourceLocation.CODEC.optionalFieldOf("arms_frame").forGetter(ArmsBodyData::frameId)
        ).apply(instance, ArmsBodyData::new)).validate(ArmsBodyData::validate);
    }

    /** 校验保存数据的成员身份，结构和定义校验由恢复入口执行。 */
    public DataResult<ArmsBodyData> validate() {
        try {
            // 先排除重复身份，再验证核心确实属于这份成员集合。
            var ids = new HashSet<UUID>();
            boolean bodyCore = false;
            for (var part : parts) {
                UUID id = UUID.fromString(part.uuid);
                if (!ids.add(id)) return DataResult.error(() -> "身体部件 UUID 重复");
                if (id.equals(corePartId)) bodyCore = part instanceof BodyPartData;
            }
            if (!bodyCore) return DataResult.error(() -> "核心必须指向成员集合中的 BodyPart");
            var occupied = new HashSet<Endpoint>();
            for (var edge : connections) {
                var a = new Endpoint(UUID.fromString(edge.partUuidA), edge.subPartNameA, edge.advConnectorName);
                var b = new Endpoint(UUID.fromString(edge.partUuidS), edge.subPartNameS, edge.simpleConnectorName);
                if (a.part.equals(b.part) || !ids.contains(a.part) || !ids.contains(b.part)) {
                    return DataResult.error(() -> "连接必须指向两个不同的成员：" + edge);
                }
                if (!occupied.add(a) || !occupied.add(b)) return DataResult.error(() -> "连接点重复占用：" + edge);
                if (!validFrame(edge.posRotA) || !validFrame(edge.posRotS)) {
                    return DataResult.error(() -> "连接参考系无效：" + edge);
                }
            }
            return DataResult.success(this);
        } catch (IllegalArgumentException error) {
            return DataResult.error(() -> "身体部件 UUID 无效");
        }
    }

    private static boolean validFrame(PosRot frame) {
        var position = frame.getPosition();
        float rotationNorm = frame.getRotation().lengthSquared();
        return com.jme3.math.Vector3f.isValidVector(position) && Math.abs(rotationNorm - 1f) < 0.001f;
    }

    private record Endpoint(UUID part, String subPart, String connector) {}
}
