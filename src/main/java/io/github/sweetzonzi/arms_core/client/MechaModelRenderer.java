package io.github.sweetzonzi.arms_core.client;

import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.ModelPose;
import cn.solarmoon.spark_core.animation.model.origin.OBone;
import cn.solarmoon.spark_core.animation.model.origin.OModel;
import cn.solarmoon.spark_core.animation.renderer.ModelRenderHelperKt;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Brightness;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
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
 * 客户端机体渲染：驱动 {@link MechaAnimatable} 并按同步位姿绘制基岩版模型。
 * <p>
 * 客户端没有实体、没有 {@code Part} 装配，因此渲染必须自己驱动：每客户端 tick 采样一次同步位姿
 * 并推进动画体（{@link MechaAnimatable#clientTick(long)}），在 {@code AFTER_ENTITIES} 阶段
 * 逐个装配体绘制模型。
 * <p>
 * <b>动画体的归属</b>：{@link MechaAnimatable} 是装配体之外的独立对象，按装配体 UUID 缓存于
 * {@link #ANIMATABLES}，寿命与 {@link MechaCoreRegistry} 的客户端表一致（创建包到达后建立、
 * 注销或换维度后回收）。{@link ArmsCore} 不持有它，也没有任何动画状态——将来改为逐 Part 渲染时，
 * 删除本类与 {@link MechaAnimatable} 即可。
 * <p>
 * <b>朝向在阶段 1–3 不代表正确</b>：{@code DATA_YAW} 目前恒为初值（无动画根旋转输入，
 * 见 `docs/ArmsCore双端权威与网络同步实现计划.md` §3.12），这里画出的朝向只证明该字段能过线。
 * <p>
 * <b>调试用胶囊外接盒</b>：{@link #DRAW_DEBUG_CAPSULE_BOX} 打开时额外画出控制器胶囊的外接盒与
 * 朝向线段。模型保持 1:1 尺寸而不与胶囊等比（{@code MechaModelPreset} 只对齐底面），
 * 盒子是对位与排查同步的参考物。
 * <p>
 * 渲染走 {@link DebugRenderer#renderFilledBox} 与 {@code ModelRenderHelperKt.render}，
 * 都写入 {@code MultiBufferSource.BufferSource}，由它在 {@code AFTER_ENTITIES} 阶段之后统一刷新，
 * 因此本类不需要自己调用 {@code endBatch}。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public final class MechaModelRenderer {

    private MechaModelRenderer() {
    }

    /** 是否额外绘制胶囊外接盒与朝向线段（对位参考） */
    private static final boolean DRAW_DEBUG_CAPSULE_BOX = true;

    /** 朝向线段长度 (m) */
    private static final float YAW_LINE_LENGTH = 1.2f;

    /** 不透明白色（ARGB） */
    private static final int WHITE = 0xFFFFFFFF;

    /** 装配体 UUID → 客户端动画体 */
    private static final Map<UUID, MechaAnimatable> ANIMATABLES = new HashMap<>();

    /** 渲染用的插值位置缓冲 */
    private static final Vector3f SCRATCH = new Vector3f();

    // ==========================================
    // 采样与驱动
    // ==========================================

    /** 每客户端 tick 采样一次同步位姿、补齐模型并推进动画体。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Level level = mc.player.level();
        if (level == null || !level.isClientSide()) return;

        long tick = level.getGameTime();
        for (ArmsCore core : MechaCoreRegistry.all(level)) {
            ANIMATABLES.computeIfAbsent(core.getAssemblyId(), id -> new MechaAnimatable(core))
                    .clientTick(tick);
        }
        // 注册表里已经没有的 UUID 连同其动画体一起回收
        if (ANIMATABLES.size() > MechaCoreRegistry.size(level)) {
            ANIMATABLES.keySet().removeIf(id -> MechaCoreRegistry.get(level, id) == null);
        }
    }

    // ==========================================
    // 渲染
    // ==========================================

    /** 在 {@code AFTER_ENTITIES} 阶段绘制机体模型（以及可选的胶囊外接盒）。 */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (ANIMATABLES.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Vec3 cameraPos = event.getCamera().getPosition();
        long tick = mc.level.getGameTime();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        poseStack.pushPose();
        // AFTER_ENTITIES 阶段传入的 poseStack 是单位阵，需要自己平移到相机相对坐标系
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        for (MechaAnimatable animatable : ANIMATABLES.values()) {
            if (!animatable.hasSample()) continue;
            animatable.lerpPosition(tick, partialTick, SCRATCH);
            if (DRAW_DEBUG_CAPSULE_BOX) {
                renderDebugCapsule(poseStack, buffers, animatable, tick, partialTick);
            }
            renderModel(poseStack, buffers, mc.level, animatable, SCRATCH, partialTick);
        }
        poseStack.popPose();
    }

    /**
     * 按当前骨骼姿态绘制整具模型。
     * <p>
     * 光照按模型锚点所在方块采样（与 Machine-Max 的 {@code DistantVehicleRenderer} 同机制）；
     * 自发光骨骼（命名约定见 {@code OBone.shouldGlow}）改走 {@code RenderType.eyes} 与全亮。
     */
    private static void renderModel(PoseStack poseStack, MultiBufferSource buffers, Level level,
                                    MechaAnimatable animatable, Vector3f pos, float partialTick) {
        ModelInstance model = animatable.getModelController().getModel();
        if (model == null) return;
        OModel origin = model.getOrigin();
        if (origin.getBones().isEmpty()) return;

        BlockPos blockPos = BlockPos.containing(pos.x, pos.y, pos.z);
        int light = LightTexture.pack(
                level.getBrightness(LightLayer.BLOCK, blockPos),
                level.getBrightness(LightLayer.SKY, blockPos));
        ResourceLocation texture = animatable.getModelController().getTextureLocation();
        ModelPose pose = model.getPose();
        VertexConsumer textured = buffers.getBuffer(RenderType.entityCutout(texture));
        VertexConsumer glowing = null;

        poseStack.pushPose();
        poseStack.mulPose(animatable.getWorldPositionMatrix(partialTick));
        for (OBone bone : origin.getBones().values()) {
            boolean glow = bone.getShouldGlow();
            if (glow && glowing == null) {
                glowing = buffers.getBuffer(RenderType.eyes(texture));
            }
            ModelRenderHelperKt.render(
                    bone,
                    pose,
                    poseStack,
                    glow ? glowing : textured,
                    glow ? Brightness.FULL_BRIGHT.pack() : light,
                    OverlayTexture.NO_OVERLAY,
                    WHITE,
                    partialTick,
                    false);
        }
        poseStack.popPose();
    }

    /** 胶囊外接盒与朝向线段：模型只对齐底面，这个盒子是对位的参考物。 */
    private static void renderDebugCapsule(PoseStack poseStack, MultiBufferSource buffers,
                                           MechaAnimatable animatable, long tick, float partialTick) {
        Vector3f pos = SCRATCH;
        float halfWidth = MechaBodyPreset.CAPSULE_RADIUS;
        float halfHeight = MechaBodyPreset.HALF_TOTAL;

        AABB capsuleBox = new AABB(
                pos.x - halfWidth, pos.y - halfHeight, pos.z - halfWidth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z + halfWidth);
        DebugRenderer.renderFilledBox(poseStack, buffers, capsuleBox, 0.2f, 0.8f, 1f, 0.35f);

        // 朝向线段：把单位朝向按 YAW_LINE_LENGTH 缩放后画成一个扁平盒。
        // 阶段 1–3 该值恒为常量（§3.12），此处只验证字段能过线，不代表朝向正确
        float yawDeg = animatable.lerpYaw(tick, partialTick);
        double yawRad = Math.toRadians(yawDeg);
        double dirX = -Math.sin(yawRad) * YAW_LINE_LENGTH;
        double dirZ = Math.cos(yawRad) * YAW_LINE_LENGTH;
        AABB yawLine = new AABB(
                Math.min(pos.x, pos.x + dirX) - 0.05, pos.y - 0.05, Math.min(pos.z, pos.z + dirZ) - 0.05,
                Math.max(pos.x, pos.x + dirX) + 0.05, pos.y + 0.05, Math.max(pos.z, pos.z + dirZ) + 0.05);
        DebugRenderer.renderFilledBox(poseStack, buffers, yawLine, 1f, 0.4f, 0.2f, 0.8f);
    }

    /** 供调试输出：某个装配体当前的客户端动画体。 */
    public static MechaAnimatable animatableOf(UUID coreId) {
        return ANIMATABLES.get(coreId);
    }
}
