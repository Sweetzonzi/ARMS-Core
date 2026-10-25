package io.github.sweetzonzi.arms_core.common.part;

import io.github.sweetzonzi.machine_max.common.mech.part.PartKind;
import io.github.sweetzonzi.machine_max.common.mech.part.PartKinds;
import io.github.sweetzonzi.machine_max.common.mech.part.PartFactory;
import net.minecraft.world.level.Level;

/** 身体类型注册与默认部件工厂。 */
public final class BodyParts {
    private BodyParts() {}

    /** 通用初始化阶段注册身体定义、动态存档 Codec 及构造入口。 */
    public static void register() {
        // 固定结构也经过发布校验，保持生产定义至少具有一个 SubPart 的约束。
        PartKinds.register(BodyPartType.KIND, new PartKind<>(BodyPartType.class, BodyPartType.CODEC,
                BodyPartData.class, BodyPartData.CODEC,
                (type, variant, level, context) -> new BodyPart(type, variant, level),
                (type, data, level, mode, context) -> new BodyPart(type, data, level, mode),
                (type, context) -> {
                    if (type.variants.isEmpty() || type.variants.values().stream().anyMatch(v -> v.subParts.isEmpty())) {
                        throw new IllegalArgumentException("身体定义的每个变体必须具有有效子结构");
                    }
                }));
    }

    /** 创建尚未归属装配体、尚未入世的默认身体，供新建构造器显式接收。 */
    public static BodyPart createDefault(Level level) {
        var type = PartFactory.resolve(level.isClientSide(), BodyPartPreset.DEFINITION);
        return (BodyPart) PartFactory.create(type, "default", level, BodyPart.CONTEXT);
    }
}
