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
 * （其末端触发 {@code LevelTickEvent.Post}）→ 渲染」。呈现位姿有两个消费者，一个在实体 tick
 * 期间、一个在渲染期：
 * <ul>
 *   <li>{@code client/ClientHostPoseEvents.java#applySyncedPose} 在 {@code PlayerTickEvent.Post}
 *       （属于 {@code tickEntities} 这一段）把宿主实体摆到呈现区间的右端。机体模型画在宿主实体的
 *       渲染原点上（{@code client/MechaPlayerRenderer.java}），因此模型的落点就是这一步写进去的值；</li>
 *   <li>渲染期各处的 {@code lerp(left, right, partialTick)} 读同一对样。子零件的调试框
 *       （{@code ../Machine-Max: VehicleInspectorRenderer}）读的是 Machine-Max 那一套样对，
 *       而那一套在 {@code ../Machine-Max: PartPoseDelayTick} 里于本事件同相位推进。</li>
 * </ul>
 * 若在本 tick 的 {@code LevelTickEvent.Post} 才推进，宿主实体那一步读到的是<b>上一 tick</b>推进出来的
 * 右端，模型的样对整体比子零件调试框落后一份样本：行走时实测相差 0.28 m 上下，表现为「子零件框
 * 跑到机体前面」。推进挪到实体 tick 之前，两边读到的就是同一对样。
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
