package io.github.sweetzonzi.arms_core.client;

import cn.solarmoon.spark_core.animation.model.ModelPose;
import cn.solarmoon.spark_core.animation.model.origin.OBone;
import cn.solarmoon.spark_core.animation.renderer.ModelRenderHelperKt;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import io.github.sweetzonzi.arms_core.common.control.presentation.MechaMolangContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import java.util.Collection;

/** 绑定装配体的玩家用各 SubPart 的呈现位姿绘制机体。 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public final class MechaPlayerRenderer {

    private static final int WHITE = 0xFFFFFFFF;

    private MechaPlayerRenderer() {
    }

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        ArmsCore core = ((IArmsHost) player).getControlledArmsCore();
        if (core == null || !(player.level() instanceof ClientLevel level)) return;
        if (MechaCoreRegistry.get(level, core.getAssemblyId()) != core) return;

        float partialTick = event.getPartialTick();
        var animation = core.getBodyAnimation();
        if (animation != null) {
            applyHeadRotation(animation.getMolangContext(), player, partialTick);
        }

        event.setCanceled(true);
        renderBodyParts(event.getPoseStack(), event.getMultiBufferSource(), level, core, player, partialTick);
    }

    /** 发布插值后的头部俯仰与相对身体的偏航，供内容包 MoLang 查询。 */
    private static void applyHeadRotation(MechaMolangContext context, Player player, float partialTick) {
        float pitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
        float headYaw = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        context.setHeadRotation(pitch, Mth.wrapDegrees(headYaw - bodyYaw));
    }

    private static void renderBodyParts(PoseStack poseStack, MultiBufferSource buffers, ClientLevel level,
                                        ArmsCore core, Player player, float partialTick) {
        var playerPosition = player.getPosition(partialTick);
        poseStack.pushPose();
        poseStack.translate(-playerPosition.x, -playerPosition.y, -playerPosition.z);
        for (var part : core.getPartMap().values()) {
            var model = part.getModelController().getModel();
            if (model == null || model.getPose().getBonePoseList().isEmpty()) continue;
            for (var sub : part.subParts.values()) {
                var lightPosition = sub.getRenderPosition(partialTick);
                int light = sampleLight(level, BlockPos.containing(lightPosition));
                poseStack.pushPose();
                poseStack.mulPose(sub.getRenderWorldPositionMatrix(partialTick));
                renderBones(sub.getBones().values(), model.getPose(), model.getTextureLocation(), poseStack, buffers, light, partialTick);
                poseStack.popPose();
            }
        }
        poseStack.popPose();
    }

    private static int sampleLight(ClientLevel level, BlockPos pos) {
        return LightTexture.pack(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
    }

    private static void renderBones(Collection<OBone> bones, ModelPose pose, ResourceLocation texture,
                                    PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick) {
        for (OBone bone : bones) {
            boolean glow = bone.getShouldGlow();
            ModelRenderHelperKt.render(
                    bone,
                    pose,
                    poseStack,
                    buffers.getBuffer(glow ? RenderType.eyes(texture) : RenderType.entityCutout(texture)),
                    glow ? Brightness.FULL_BRIGHT.pack() : light,
                    OverlayTexture.NO_OVERLAY,
                    WHITE,
                    partialTick,
                    false);
        }
    }
}
