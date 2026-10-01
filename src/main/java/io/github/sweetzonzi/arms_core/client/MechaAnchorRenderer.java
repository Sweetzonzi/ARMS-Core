package io.github.sweetzonzi.arms_core.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端可视锚点的采样与调试渲染。
 * <p>
 * 客户端没有 KCC、没有实体、也没有 {@code Part} 装配（计划 D18、阶段 1–3 的表现层缺失
 * §3.12），因此用最简单的方式把同步结果显示出来：在插值后的 {@code DATA_POS} 处画一个
 * 胶囊外接盒，并按 {@code DATA_YAW} 画一条朝向线段。
 * <p>
 * 朝向在阶段 1–3 是常量（没有动画根旋转输入，{@code currentYaw} 不变化，§3.12），
 * 因此这里画出的朝向只证明「该字段能过线」，不代表朝向正确。
 * <p>
 * 渲染走 {@link DebugRenderer#renderFilledBox}，它写入 {@code RenderType.debugFilledBox()}，
 * 会自动被 {@code MultiBufferSource.BufferSource} 在 {@code AFTER_ENTITIES} 阶段之后刷新，
 * 因此本类不需要自己调用 {@code endBatch}。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public final class MechaAnchorRenderer {

    private MechaAnchorRenderer() {
    }

    /** 朝向线段长度 (m) */
    private static final float YAW_LINE_LENGTH = 1.2f;

    /** UUID → 锚点状态 */
    private static final Map<UUID, ClientMechaAnchor> ANCHORS = new HashMap<>();

    /** 已发出过告警的未知 UUID，避免刷屏 */
    private static final Map<UUID, Boolean> WARNED = new HashMap<>();

    /** 渲染用的插值位置缓冲 */
    private static final Vector3f SCRATCH = new Vector3f();

    // ==========================================
    // 采样
    // ==========================================

    /** 每客户端 tick 从同步数据采样一次，供渲染插值。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Level level = mc.player.level();
        if (level == null || !level.isClientSide()) return;

        long tick = level.getGameTime();
        for (ArmsCore core : MechaCoreRegistry.all(level)) {
            ANCHORS.computeIfAbsent(core.getAssemblyId(), id -> new ClientMechaAnchor())
                    .acceptFromSyncedData(core, tick);
        }
        // 注册表里已经没有的 UUID 连同其锚点一起回收
        if (ANCHORS.size() > MechaCoreRegistry.size(level)) {
            ANCHORS.keySet().removeIf(id -> MechaCoreRegistry.get(level, id) == null);
            WARNED.keySet().removeIf(id -> MechaCoreRegistry.get(level, id) == null);
        }
    }

    // ==========================================
    // 渲染
    // ==========================================

    /** 在 {@code AFTER_ENTITIES} 阶段画胶囊外接盒与朝向线段。 */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (ANCHORS.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        com.mojang.blaze3d.vertex.PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Vec3 cameraPos = event.getCamera().getPosition();
        long tick = mc.level.getGameTime();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        poseStack.pushPose();
        // AFTER_ENTITIES 阶段传入的 poseStack 是单位阵，需要自己平移到相机相对坐标系
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        renderAll(poseStack, buffers, tick, partialTick);
        poseStack.popPose();
    }

    private static void renderAll(PoseStack poseStack, MultiBufferSource buffers,
                                  long tick, float partialTick) {
        float halfWidth = MechaBodyPreset.CAPSULE_RADIUS;
        float halfHeight = MechaBodyPreset.HALF_TOTAL;

        for (ClientMechaAnchor anchor : ANCHORS.values()) {
            if (!anchor.hasSample()) continue;
            anchor.lerpPosition(tick, partialTick, SCRATCH);
            double x = SCRATCH.x;
            double y = SCRATCH.y;
            double z = SCRATCH.z;

            AABB capsuleBox = new AABB(
                    x - halfWidth, y - halfHeight, z - halfWidth,
                    x + halfWidth, y + halfHeight, z + halfWidth);
            DebugRenderer.renderFilledBox(poseStack, buffers, capsuleBox, 0.2f, 0.8f, 1f, 0.35f);

            // 朝向线段：把单位朝向按 YAW_LINE_LENGTH 缩放后画成一个扁平盒。
            // 阶段 1–3 该值恒为常量（§3.12），此处只验证字段能过线，不代表朝向正确
            float yawDeg = anchor.lerpYaw(tick, partialTick);
            double yawRad = Math.toRadians(yawDeg);
            double dirX = -Math.sin(yawRad) * YAW_LINE_LENGTH;
            double dirZ = Math.cos(yawRad) * YAW_LINE_LENGTH;
            AABB yawLine = new AABB(
                    Math.min(x, x + dirX) - 0.05, y - 0.05, Math.min(z, z + dirZ) - 0.05,
                    Math.max(x, x + dirX) + 0.05, y + 0.05, Math.max(z, z + dirZ) + 0.05);
            DebugRenderer.renderFilledBox(poseStack, buffers, yawLine, 1f, 0.4f, 0.2f, 0.8f);
        }
    }

    /** 供调试输出：某个装配体当前的锚点。 */
    public static ClientMechaAnchor anchorOf(UUID coreId) {
        return ANCHORS.get(coreId);
    }
}
