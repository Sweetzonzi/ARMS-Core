package io.github.sweetzonzi.arms_core.common.part;

import io.github.sweetzonzi.machine_max.common.mech.part.PartKind;
import io.github.sweetzonzi.machine_max.common.mech.part.PartKinds;
import io.github.sweetzonzi.machine_max.common.mech.part.PartFactory;
import io.github.sweetzonzi.arms_core.ARMS;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** 身体类型注册与默认部件工厂。 */
public final class BodyParts {
    private static final ResourceLocation DEFAULT_ID = ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "minimal_body");
    private BodyParts() {}

    /** 通用初始化阶段注册身体定义、动态存档 Codec 及构造入口。 */
    public static void register() {
        PartKinds.register(BodyPartType.KIND, new PartKind<>(BodyPartType.class, BodyPartType.CODEC,
                BodyPartData.class, BodyPartData.CODEC,
                (type, variant, level, context) -> new BodyPart(type, variant, level),
                (type, data, level, mode, context) -> new BodyPart(type, data, level, mode)));
    }

    /** 创建尚未归属装配体、尚未入世的默认身体，供新建构造器显式接收。 */
    public static BodyPart createDefault(Level level) {
        var type = PartFactory.resolve(level.isClientSide(), DEFAULT_ID);
        return (BodyPart) PartFactory.create(type, "default", level, BodyPart.CONTEXT);
    }
}
