package io.github.sweetzonzi.arms_core.common;

import cn.solarmoon.spark_core.event.PhysicsLevelTickEvent;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.network.payload.MechaCoreSyncPayload;
import net.minecraft.core.Rotations;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * {@link ArmsCore} 的服务端生命周期与两个相位的事件接线。
 * <p>
 * 两个相位的职责（计划 D8、D9、§3.9）：
 * <ul>
 *   <li>{@code PhysicsLevelTickEvent.Pre}（物理线程）：按 Level 扇出
 *       {@link ArmsCore#prePhysicsTick(float)}，每步恰好一次。与 {@code VehicleCore} 的
 *       {@code ObjectManager.onPrePhysicsTick} 同相位。</li>
 *   <li>{@code LevelTickEvent.Post}（主线程）：读逻辑状态快照与 KCC 位姿 → 写
 *       {@code syncedData} → {@code packDirty()} → 非空则广播。</li>
 * </ul>
 * <p>
 * 不在 {@code LevelTickEvent.Pre} 做同步：Spark-Core 自身在该相位（{@code HIGH}）调用
 * {@code physicsLevel.requestStep()}，避开与请求步进竞争。
 * <p>
 * 本类不标注 {@code Dist}，因此其订阅者只在存在服务端的 JVM 中生效；单人游戏的集成服务端
 * 也走这条路径，专用服务端同理。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID)
public final class ArmsCoreServerEvents {

    private ArmsCoreServerEvents() {
    }

    // ==========================================
    // 物理线程：唯一的物理步驱动
    // ==========================================

    /**
     * 按 Level 扇出物理步。
     * <p>
     * 驱动源是 Level 级注册表而不是宿主：宿主形态各异、驱动时机不同，漏调一次就停摆。
     * <p>
     * {@code dt} 取 {@code 1f / tps}：单步 dt 是恒定值，负载自适应调整的是每 tick 的步数
     * （计划 §3.6）。
     */
    @SubscribeEvent
    public static void onPrePhysicsTick(PhysicsLevelTickEvent.Pre event) {
        Level level = event.getLevel().getMcLevel();
        if (level.isClientSide()) return;
        float dt = 1f / event.getLevel().getTps();
        for (ArmsCore core : MechaCoreRegistry.all(level)) {
            core.applyDriftFallback();
            core.prePhysicsTick(dt);
        }
    }

    // ==========================================
    // 主线程：同步写包
    // ==========================================

    /** 主线程每 tick 采样一次位姿与逻辑状态，按脏数据发包。 */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onPostTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        for (ArmsCore core : MechaCoreRegistry.all(level)) {
            syncToClients(core);
        }
    }

    /**
     * 把一个装配体的当前状态写进同步容器并在有脏数据时广播。
     * <p>
     * 位姿、速度、朝向三项在主线程直接读 KCC —— KCC 是幽灵体，其世界变换由 {@code playerStep}
     * 末尾一次性写入，并发读取拿到的是某一次完整步进的结果；这与 Machine-Max
     * {@code DestroyableRigidObject.postTick()} 已接受的竞态同类（计划 §3.5、R4）。
     */
    public static void syncToClients(ArmsCore core) {
        MechaControl control = core.getMechaControl();
        if (control == null) return;
        MechaCharacter kcc = control.getKcc();

        // 位姿与速度：每次读都取新对象，不能把复用缓冲直接交给 set
        // （DataItem.setValue 直接存引用，复用会让已入队的旧值被改写）
        Vector3f position = kcc.getPhysicsLocation(null);
        Vector3f velocity = kcc.getLinearVelocity(null);
        core.getSyncedData().set(ArmsCore.DATA_POS, toJoml(position));
        core.getSyncedData().set(ArmsCore.DATA_VEL, toJoml(velocity));

        // 朝向：控制器侧绝对 Y 朝向，单位度。Rotations 的构造器对分量取 % 360，
        // 因此这里传入未归一化弧度换算出的度数也能得到有界值。
        core.getSyncedData().set(ArmsCore.DATA_YAW,
                new Rotations(0f, (float) Math.toDegrees(kcc.getCurrentYaw()), 0f));

        // 逻辑层五项：主线程不得读 StateVariableContainer，只读物理线程发布的不可变快照
        MechaControl.LogicStateSnapshot state = core.getLogicState();
        if (state != null) {
            core.getSyncedData().set(ArmsCore.DATA_POSTURE, state.posture().molangName());
            core.getSyncedData().set(ArmsCore.DATA_GAIT, state.gait().molangName());
            core.getSyncedData().set(ArmsCore.DATA_VERTICAL, state.vertical().molangName());
            core.getSyncedData().set(ArmsCore.DATA_ENERGY, state.energy());
            core.getSyncedData().set(ArmsCore.DATA_JUMP_CHARGING, state.jumpCharging());
        }

        List<SynchedEntityData.DataValue<?>> dirty = core.getSyncedData().packDirty();
        if (dirty != null && !dirty.isEmpty()) {
            PacketDistributor.sendToPlayersInDimension((ServerLevel) core.getLevel(),
                    new MechaCoreSyncPayload(core.getAssemblyId(), dirty));
        }
    }

    private static org.joml.Vector3f toJoml(Vector3f source) {
        return new org.joml.Vector3f(source.x, source.y, source.z);
    }

    // ==========================================
    // 登录 / 换维度 / 重生补发
    // ==========================================

    /**
     * 玩家登录或换维度后把所在维度的装配体逐个补发。
     * <p>
     * 用 {@link EntityJoinLevelEvent} 而不是 {@code PlayerLoggedInEvent}：前者在玩家实体真正
     * 加入维度后触发，此时 {@code player.level()} 已是目标维度，正是创建包需要校验的维度。
     * 换维度路径同时由 {@link PlayerEvent.PlayerChangedDimensionEvent} 覆盖（重生也走该事件）。
     */
    @SubscribeEvent
    public static void onPlayerJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MechaCoreRegistry.sendAllTo(player);
    }

    /** 换维度 / 重生：清掉该玩家在旧维度的控制权，再补发新维度。 */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        releaseCoresControlledBy(player.getUUID());
        MechaCoreRegistry.sendAllTo(player);
    }

    /** 断线：该玩家控制的所有装配体必须回到空快照，否则状态机卡在最后一帧（R11）。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        releaseCoresControlledBy(event.getEntity().getUUID());
    }

    private static void releaseCoresControlledBy(UUID playerId) {
        for (ServerLevel level : MechaCoreRegistry.serverLevels()) {
            for (ArmsCore core : MechaCoreRegistry.snapshot(level)) {
                if (playerId.equals(MechaInputHandler.controllerOf(core.getAssemblyId()))) {
                    MechaInputHandler.releaseController(core);
                }
            }
        }
    }

    // ==========================================
    // 维度卸载
    // ==========================================

    /**
     * 维度卸载时清空注册表。
     * <p>
     * 服务端卸载与客户端退出共用同一入口：客户端换维度时同样会卸载旧的 {@code ClientLevel}，
     * 此时清空本地表，随后由服务端补发的创建包重新填充。
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof Level level)) return;
        MechaCoreRegistry.onLevelUnload(level);
    }
}
