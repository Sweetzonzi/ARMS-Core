package io.github.sweetzonzi.arms_core.common.part;

import com.mojang.serialization.Codec;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPartType;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.attr.VariantAttr;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** 身体部件的静态定义；变体、子结构和连接点沿用 MM 的格式。 */
public final class BodyPartType extends AbstractPartType {
    public static final String KIND = "arms_core:body";
    public static final Codec<BodyPartType> CODEC = VARIANT_MAP_CODEC.fieldOf("variants").codec()
            .xmap(BodyPartType::new, type -> type.variants);

    public BodyPartType(Map<String, VariantAttr> variants) {
        super(ResourceLocation.withDefaultNamespace("missingno"), true, 1, variants);
    }

    /** 在定义发布时检查身体专属的刚体和骨骼约束。 */
    @Override
    public void validate(Map<ResourceLocation, AbstractPartType> candidates) {
        super.validate(candidates);
        for (var entry : variants.entrySet()) {
            var variant = entry.getValue();
            float largestMass = 0f;
            int roots = 0;
            for (var child : variant.subParts.entrySet()) {
                var subPart = child.getValue();
                String path = entry.getKey() + "/" + child.getKey();
                if (!Float.isFinite(subPart.mass) || subPart.mass <= 0f) {
                    throw new IllegalArgumentException(path + " 的 mass 必须是有限正数");
                }
                // 复用 MM 按最大质量选根的规则；并列时没有稳定的身体锚点。
                if (subPart.mass > largestMass) {
                    largestMass = subPart.mass;
                    roots = 1;
                } else if (subPart.mass == largestMass) {
                    roots++;
                }
                var bones = subPart.getBones(variant);
                if (!subPart.startBone.isEmpty() && !bones.containsKey(subPart.startBone)) {
                    throw new IllegalArgumentException(path + " 的 start_bone 不存在或不在骨骼范围内");
                }
                for (String hitBox : subPart.hitBoxes.keySet()) {
                    var bone = bones.get(hitBox);
                    if (bone == null || bone.getCubes().isEmpty()) {
                        throw new IllegalArgumentException(path + " 的命中框骨骼无有效 cube：" + hitBox);
                    }
                }
            }
            if (roots != 1) {
                throw new IllegalArgumentException(entry.getKey() + " 必须有唯一的最大质量子部件");
            }
        }
    }

    @Override public String kindId() { return KIND; }
}
