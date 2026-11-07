package io.github.sweetzonzi.arms_core.client;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端呈现位姿的推进点：每客户端 tick 一次，把全部 {@link ArmsCore} 的呈现样对沿时间轴推进一步。
 *
 * <h3>为什么相位必须早于实体 tick，而不是 {@code LevelTickEvent.Post}</h3>
 * {@code Minecraft#tick} 的相位顺序是「{@code runAllTasks()}（同步包在这里应用）→
 * {@code ClientTickEvent.Pre} → {@code level.tickEntities()} → {@code level.tick()}
 * （其末端触发 {@code LevelTickEvent.Post}）→ 渲染」。两个相位读取装配体的呈现样对：
 * <ul>
 *   <li>{@code client/ClientHostPoseEvents.java#applySyncedPose} 在 {@code PlayerTickEvent.Post}
 *       （属于 {@code tickEntities} 这一段）把宿主实体摆到呈现区间的右端。</li>
 *   <li>{@code common/ArmsCore.java#tickBody} 在 level tick 末端采样右端位置用于动画 LOD。</li>
 * </ul>
 * 在实体 tick 前推进样对，宿主能在本 tick 使用最新的右端样本。
 * 零件渲染读各自的呈现样对，见 {@link MechaPlayerRenderer}。
 *
 * <h3>为什么暂停时要跳过</h3>
 * 暂停时游戏时间不前进（{@code partialTick} 冻结），但 {@code Minecraft#tick} 仍在跑。
 * 缓冲的余量按「真实前进的 tick」消耗，暂停期间照常推进会把余量吃光，恢复后第一次掉包就退化成停帧。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class MechaPoseDelayTick {

    private MechaPoseDelayTick() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.isPaused()) return;
        for (ArmsCore core : MechaCoreRegistry.all(minecraft.level)) {
            core.advancePresentationPose();
        }
    }
}
