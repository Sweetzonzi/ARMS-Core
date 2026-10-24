package io.github.sweetzonzi.arms_core.common.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPartData;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.data.SubPartData;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** 身体保存数据，复用公共身份、外观及子部件状态字段。 */
public final class BodyPartData extends AbstractPartData {
    public static final Codec<BodyPartData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("registry_key").forGetter(data -> data.registryKey),
            Codec.STRING.fieldOf("name").forGetter(data -> data.name),
            Codec.STRING.fieldOf("uuid").forGetter(data -> data.uuid),
            Codec.STRING.fieldOf("variant").forGetter(data -> data.variant),
            Codec.FLOAT.optionalFieldOf("shared_durability_ratio", 1f).forGetter(data -> data.sharedDurabilityRatio),
            Codec.STRING.optionalFieldOf("texture_name", "default").forGetter(data -> data.textureName),
            SubPartData.MAP_CODEC.fieldOf("sub_parts").forGetter(data -> data.subParts)
    ).apply(instance, BodyPartData::new));

    public BodyPartData(ResourceLocation id, String name, String uuid, String variant, float ratio,
                        String texture, Map<String, SubPartData> subParts) {
        super(id, name, uuid, variant, ratio, texture, Map.copyOf(subParts));
    }

    public BodyPartData(BodyPart part) { super(part); }
}
