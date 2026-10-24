package io.github.sweetzonzi.arms_core.common.part;

import com.mojang.serialization.Codec;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPartType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** 使用固定最小结构的身体定义，内容包暂不提供身体专属配置字段。 */
public final class BodyPartType extends AbstractPartType {
    public static final String KIND = "arms_core:body";
    public static final Codec<BodyPartType> CODEC = Codec.unit(() -> new BodyPartType(BodyPartPreset.loadingClientSide()));

    public BodyPartType(boolean clientSide) {
        super(ResourceLocation.withDefaultNamespace("missingno"), true, 1,
                Map.of("default", BodyPartPreset.variant(clientSide)));
    }

    @Override public String kindId() { return KIND; }
}
