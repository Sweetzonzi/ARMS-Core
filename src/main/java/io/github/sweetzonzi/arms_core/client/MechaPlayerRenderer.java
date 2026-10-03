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
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端机体渲染 —— 动画体的持有、驱动与绘制都在本类。
 * <p>
 * 职责有三，全部围绕"把一具基岩版模型画在宿主实体身上"：
 * <ol>
 *   <li><b>持有动画体</b>：按装配体 UUID 缓存 {@link MechaAnimatable}，寿命与
 *       {@link MechaCoreRegistry} 的客户端表一致（创建包到达后建立、注销或换维度后回收）；</li>
 *   <li><b>驱动动画</b>：每客户端 tick 采样一次同步位姿并推进动画（{@link MechaAnimatable#clientTick(long)}）。
 *       采样必须每 tick 做一次而不是渲染时做，因为 {@code ClientMechaAnchor} 的插值自变量是"距最近一次
 *       采样过了多少 tick"；</li>
 *   <li><b>绘制</b>：在 {@link RenderPlayerEvent.Pre} 里取消原版玩家模型，并把机体就地画在同一位姿上。</li>
 * </ol>
 *
 * <h3>为什么挂在玩家渲染事件上</h3>
 * 有机体时，玩家模型必须让位给机体，而"让位"与"替补上场"必须用同一份位姿、同一个部分 tick，否则两具
 * 模型会在切换帧上错开。{@code RenderPlayerEvent.Pre} 正好提供这两样：它是可取消事件（取消后原版不再
 * 渲染该玩家，对应的 {@code Post} 也不触发，不会出现"半个玩家"），而它的 {@code PoseStack} 已经位于该
 * 玩家的渲染原点。
 * <p>
 * 另一个理由是位姿精度：世界阶段渲染必须自己给出模型的世界坐标，而客户端唯一能拿到的独立位姿是同步来的
 * {@code DATA_POS} / {@code DATA_YAW}，它落后服务端一至两个 tick。搬进玩家渲染后，位置取宿主的渲染原点、
 * 朝向取 {@code yBodyRot}，两者都由原版按部分 tick 插值，模型因此不会与实体自身的插值打架。这正是
 * "玩家位置即 KCC 位置"（{@code common/ArmsCoreServerEvents.java#applyPoseToHost}）在渲染侧的收益：
 * 实体在哪，模型就在哪，两者之间没有第三个位姿来源。
 *
 * <h3>基点的换算</h3>
 * 实体位置是包围盒底面中心，而模型锚点是<b>胶囊中心</b>，两者相差 {@link MechaBodyPreset#HALF_TOTAL}；
 * 胶囊固定使用站立尺寸，因为服务端写入宿主实体的位置就是"KCC 胶囊中心 − {@code HALF_TOTAL}"
 * （姿态轮廓未接入 KCC 换形状，见 {@code docs/ArmsCore双端权威与网络同步实现计划.md} §3.12.1）。
 * 因此这里在实体的局部 Y 上加回 {@code HALF_TOTAL}，模型底面与实体底面因此对齐。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public final class MechaPlayerRenderer {

    private MechaPlayerRenderer() {
    }

    /** 不透明白色（ARGB） */
    private static final int WHITE = 0xFFFFFFFF;

    /** 装配体 UUID → 客户端动画体 */
    private static final Map<UUID, MechaAnimatable> ANIMATABLES = new HashMap<>();

    // ==========================================
    // 动画体的持有与驱动
    // ==========================================

    /** 每客户端 tick 采样一次同步位姿、补齐模型并推进动画体。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Level level = mc.player.level();
        if (!level.isClientSide()) return;

        long tick = level.getGameTime();

        // 回收：本 tick 注册表里查不到的 UUID 一律丢弃。放在创建之前，淘汰就不依赖后面的
        // 创建循环能否跑完（循环中途抛异常也不会把淘汰一起跳过）。
        // 不用「两张表的大小比较」来省这一次遍历：那等于用集合大小推断差集，只在
        // 「创建循环必定覆盖注册表全部条目」这个隐含前提下成立；前提一旦被破坏
        // （创建中途抛异常、注册表出现遍历不到的条目），残留项就永远留在表里。
        ANIMATABLES.keySet().removeIf(id -> MechaCoreRegistry.get(level, id) == null);

        for (ArmsCore core : MechaCoreRegistry.all(level)) {
            ANIMATABLES.computeIfAbsent(core.getAssemblyId(), id -> new MechaAnimatable(core))
                    .clientTick(tick);
        }
    }

    /**
     * 某个装配体当前的客户端动画体。
     *
     * @return 尚未建立或已回收时返回 {@code null}
     */
    public static MechaAnimatable findAnimatable(UUID coreId) {
        return ANIMATABLES.get(coreId);
    }

    // ==========================================
    // 绘制
    // ==========================================

    /**
     * 玩家开始渲染：有机体则取消玩家模型并改画机体。
     * <p>
     * 只处理"这个玩家正好承载着某个客户端装配体"的情形。判据同时要求注册表里能查到那个装配体，
     * 否则服务端已注销而移除包还在路上时会画出一具已经不该存在的机体。
     */
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        ArmsCore core = ((IArmsHost) player).getControlledArmsCore();
        if (core == null) return;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (MechaCoreRegistry.get(level, core.getAssemblyId()) == null) return;

        MechaAnimatable animatable = findAnimatable(core.getAssemblyId());
        if (animatable == null) return;

        // 取消原版玩家渲染：玩家模型、其全部 RenderLayer（护甲 / 手持物 / 披风 / 鞘翅）与阴影一并消失。
        // 名称牌是独立的渲染通道，不受本取消影响。
        event.setCanceled(true);

        float partialTick = event.getPartialTick();
        // 身体偏航取实体自身并做相邻 tick 插值：玩家朝向的权威就在这个字段上（客户端上行 viewYaw
        // 读的也是它），因此这里不存在"写回朝向"那条反馈环的顾虑。
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        renderModel(event.getPoseStack(), event.getMultiBufferSource(), level, animatable,
                player, bodyYaw, partialTick);
    }

    /**
     * 在宿主的渲染原点画出整具模型。
     * <p>
     * 矩阵顺序是"按胶囊中心抬升 → 模型空间矩阵（缩放 + 偏航）"。抬升用
     * {@link MechaBodyPreset#HALF_TOTAL}（站立尺寸）而不是按姿态分档的
     * {@code MechaBodyPreset#halfTotalFor}：KCC 不允许在世换碰撞形状，胶囊始终是站立轮廓，宿主实体的
     * 位置也是按它写入的，用姿态分档会让模型在蹲伏时浮起来。
     * <p>
     * 光照按宿主实体眼睛所在方块采样（与 Machine-Max 的 {@code DistantVehicleRenderer} 同机制）；
     * 自发光骨骼（命名约定见 {@code OBone.getShouldGlow}）改走 {@code RenderType.eyes} 与全亮。
     */
    private static void renderModel(PoseStack poseStack, MultiBufferSource buffers, ClientLevel level,
                                    MechaAnimatable animatable, Player player,
                                    float bodyYaw, float partialTick) {
        ModelInstance model = animatable.getModelController().getModel();
        if (model == null) return;
        OModel origin = model.getOrigin();
        if (origin.getBones().isEmpty()) return;

        // 光照取眼睛所在方块而不是脚下：脚下在坑里时方块光是 0，整具模型会突然变暗
        BlockPos lightPos = BlockPos.containing(player.getEyePosition(partialTick));
        int light = LightTexture.pack(
                level.getBrightness(LightLayer.BLOCK, lightPos),
                level.getBrightness(LightLayer.SKY, lightPos));

        ResourceLocation texture = animatable.getModelController().getTextureLocation();
        ModelPose pose = model.getPose();
        VertexConsumer textured = buffers.getBuffer(RenderType.entityCutout(texture));
        VertexConsumer glowing = null;

        poseStack.pushPose();
        poseStack.translate(0.0, MechaBodyPreset.HALF_TOTAL, 0.0);
        poseStack.mulPose(animatable.getModelSpaceMatrix(partialTick, 0.0, 0.0, 0.0, bodyYaw));
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
}
