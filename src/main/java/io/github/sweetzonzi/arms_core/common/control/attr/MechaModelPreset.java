package io.github.sweetzonzi.arms_core.common.control.attr;

import cn.solarmoon.spark_core.animation.model.ModelIndex;
import io.github.sweetzonzi.arms_core.ARMS;
import net.minecraft.resources.ResourceLocation;

/** AllBody 共用的 bmpt 源骨架和动画集索引。 */
public final class MechaModelPreset {

    private MechaModelPreset() {
        throw new UnsupportedOperationException("常量类，不可实例化");
    }

    /** 素体机体模型（BMPT 机娘） */
    public static final ModelIndex FRAME_MODEL = new ModelIndex(
            "frame", ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "bmpt"));
}
