package io.github.sweetzonzi.arms_core.common.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 身体组成快照；核心必须存在于成员列表，控制器位姿和宿主身份由各自通道负责。 */
public record ArmsBodyData(String name, UUID corePartId, List<BodyPartData> parts) {
    private static final Codec<ArmsBodyData> FIELDS = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("name", "ArmsCore").forGetter(ArmsBodyData::name),
            UUIDUtil.CODEC.fieldOf("core_part").forGetter(ArmsBodyData::corePartId),
            BodyPartData.CODEC.listOf().optionalFieldOf("parts", List.of()).forGetter(ArmsBodyData::parts)
    ).apply(instance, ArmsBodyData::new));
    public static final Codec<ArmsBodyData> CODEC = FIELDS.validate(ArmsBodyData::validate);

    public ArmsBodyData { parts = List.copyOf(parts); }

    /** 校验保存数据的成员身份，结构和定义校验由恢复入口执行。 */
    public DataResult<ArmsBodyData> validate() {
        try {
            // 先排除重复身份，再验证核心确实属于这份成员集合。
            var ids = new HashSet<UUID>();
            for (var part : parts) {
                if (!ids.add(UUID.fromString(part.uuid))) return DataResult.error(() -> "身体部件 UUID 重复");
            }
            if (corePartId == null || !ids.contains(corePartId)) {
                return DataResult.error(() -> "核心必须指向成员集合中的部件");
            }
            return DataResult.success(this);
        } catch (IllegalArgumentException error) {
            return DataResult.error(() -> "身体部件 UUID 无效");
        }
    }
}
