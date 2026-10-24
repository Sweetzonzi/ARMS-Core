package io.github.sweetzonzi.arms_core.common.part;

import cn.solarmoon.spark_core.pack.diagnostic.SparkPackIssues;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.attr.HitBoxAttr;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.attr.MaterialAttr;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.attr.SubPartAttr;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.attr.VariantAttr;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 双端各共享一份最小结构定义，每个部件实例独立持有刚体。 */
public final class BodyPartPreset {
    public static final String ROOT = "body";
    public static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "minimal_body");
    public static final ResourceLocation DEFINITION = ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "minimal_body");
    private static final Map<Boolean, VariantAttr> VARIANTS = new ConcurrentHashMap<>();

    private BodyPartPreset() {}

    /** 从内容包加载上下文识别逻辑端，避免用发行版区分集成服务端。 */
    public static boolean loadingClientSide() {
        var frame = SparkPackIssues.currentFrame();
        if (frame == null) throw new IllegalStateException("身体定义解码需要内容包加载上下文");
        return frame.getContext().isClientSide();
    }

    /** 同端复用子部件属性及碰撞缓存，双端之间隔离。 */
    public static VariantAttr variant(boolean clientSide) {
        return VARIANTS.computeIfAbsent(clientSide, ignored -> newVariant());
    }

    private static VariantAttr newVariant() {
        // 同一根骨骼同时提供碰撞 cube 与子部件骨骼范围，不需要连接点或贴图资源。
        HitBoxAttr hitBox = new HitBoxAttr(ROOT, "box", "", "", MaterialAttr.DEFAULT_ID, 1f, "true", null);
        SubPartAttr subPart = new SubPartAttr(ROOT, List.of(), 20f, 1f, "mass_center", Vec3.ZERO,
                "false", -1f, false, Map.of(ROOT, hitBox), Map.of(), Map.of(), Map.of(), 0, Map.of());
        return new VariantAttr(List.of(), MODEL, Map.of("default", VariantAttr.EMPTY_TEXTURE),
                VariantAttr.EMPTY_ANIM, Map.of(ROOT, subPart));
    }
}
