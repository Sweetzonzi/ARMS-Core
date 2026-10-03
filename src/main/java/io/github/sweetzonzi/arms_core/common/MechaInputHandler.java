package io.github.sweetzonzi.arms_core.common;

import io.github.sweetzonzi.arms_core.common.control.MechaConditionSnapshot;
import io.github.sweetzonzi.arms_core.common.control.MechaEvent;
import io.github.sweetzonzi.arms_core.network.payload.MechaInputPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上行输入的服务端处理链。
 * <p>
 * 处理顺序（计划 §3.11）：
 * <ol>
 *   <li>权限校验：该玩家是否是此 {@link ArmsCore} 的控制者</li>
 *   <li>合并：上行（输入 / 视角 / 按键）+ 服务端本地查询（环境类）</li>
 *   <li>{@code writeConditionSnapshot(merged)} —— volatile 发布，物理线程下一步读取</li>
 *   <li>按事件序号差投递 {@link MechaEvent} —— 同序号幂等</li>
 * </ol>
 * <p>
 * <b>环境类字段一律由服务端查询，不采信客户端上行。</b> 上行包只携带输入与视角；
 * {@code inWater} / {@code inLava} / {@code isDead} / {@code isSleeping} / {@code isFallFlying} /
 * {@code isInWall} / {@code isOnFire} 由本类从控制者实体现状读出。这样客户端无法通过伪造
 * 环境状态绕过状态机门控。
 * <p>
 * 线程模型：本类全部方法在主线程（载荷处理器）调用；写入的是 {@code volatile} 快照与
 * 原子事件闩锁，物理线程帧首消费。
 *
 * @author Sweetzonzi
 */
public final class MechaInputHandler {

    private MechaInputHandler() {
    }

    /** 装配体 UUID → 已投递过的最大事件序号 */
    private static final Map<UUID, Integer> LAST_EVENT_SEQ = new ConcurrentHashMap<>();

    // ==========================================
    // 控制权
    // ==========================================

    /**
     * 判断玩家是否有权写入该装配体的输入。
     * <p>
     * 判据是「该玩家的绑定字段是不是这个装配体」（{@link IArmsHost#getControlledArmsCore}），
     * 绑定关系本身由 {@link IArmsHost#setControlledArmsCore} 维护。
     */
    public static boolean isController(ArmsCore core, UUID playerId) {
        ServerPlayer player = resolveServerPlayer(playerId);
        return player instanceof IArmsHost host && host.getControlledArmsCore() == core;
    }

    /**
     * 把该装配体的输入重置为空快照并清空待消费事件。
     * <p>
     * 用于断开连接 / 换维度 / 失去控制的路径。绑定变化时由
     * {@link IArmsHost#setControlledArmsCore} 的实现调用。
     */
    public static void resetInput(ArmsCore core) {
        core.writeConditionSnapshot(MechaConditionSnapshot.EMPTY);
        LAST_EVENT_SEQ.remove(core.getAssemblyId());
    }

    /**
     * 装配体注销时回收每装配体的输入状态。
     * <p>
     * 绑定字段不必在这里清：装配体注销的所有路径都会先解绑宿主，见
     * {@link MechaCoreRegistry#removeServer}。
     */
    public static void forget(UUID coreId) {
        LAST_EVENT_SEQ.remove(coreId);
    }

    /**
     * 在服务端按 UUID 解析玩家实体。
     * <p>
     * 用 {@link net.neoforged.neoforge.server.ServerLifecycleHooks#getCurrentServer()} 而不是
     * {@code Minecraft.getInstance().player}：后者在专用服务端上返回 {@code null}，在单人游戏里
     * 返回的是客户端世界的玩家实体，而绑定字段的两端各有一份。
     *
     * @return 该 UUID 当前所在的服务端玩家实体；离线或尚未加入时返回 {@code null}
     */
    private static @Nullable ServerPlayer resolveServerPlayer(UUID playerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(playerId);
    }

    // ==========================================
    // 输入处理
    // ==========================================

    /**
     * 处理一个上行输入包。
     *
     * @param payload  上行载荷
     * @param core     目标实例（服务端）
     * @param playerId 发送者 UUID
     */
    public static void apply(MechaInputPayload payload, ArmsCore core, UUID playerId) {
        if (!isController(core, playerId)) {
            return;
        }
        core.writeConditionSnapshot(merge(payload, core, playerId));
        if (payload.eventBits() != 0) {
            dispatchEvents(payload, core);
        }
    }

    /**
     * 合并上行输入与服务端本地查询的环境状态。
     * <p>
     * <b>环境类字段一律由服务端查询，不采信客户端上行</b>，因此这里必须解析出控制者实体。
     * 调用方（{@link #apply}）已通过 {@link #isController} 确认发起者就是控制者，而本方法拿到的
     * 正是同一个已登录玩家，所以解析结果不会为 {@code null}。
     */
    private static MechaConditionSnapshot merge(MechaInputPayload payload, ArmsCore core, UUID playerId) {
        ServerPlayer controller = resolveServerPlayer(playerId);
        // 用 var 而不写出 Builder 类型：Lombok 生成的 Builder 不保证是 public，
        // 显式写出类型名会让编译期去找一个不可见的类
        var builder = MechaConditionSnapshot.builder()
                .inputForward(payload.forward())
                .inputStrafe(payload.strafe())
                .jumpPressed((payload.keyFlags() & MechaInputPayload.BIT_JUMP) != 0)
                .sprintPressed((payload.keyFlags() & MechaInputPayload.BIT_SPRINT) != 0)
                .walkKeyPressed((payload.keyFlags() & MechaInputPayload.BIT_WALK) != 0)
                .viewYaw(payload.viewYaw())
                .viewPitch(payload.viewPitch())
                .sneaking((payload.keyFlags() & MechaInputPayload.BIT_SNEAK) != 0);
        if (controller == null) {
            // 控制者已离开（例如换维度途中），环境按最保守的静止形态给出
            return builder.build();
        }
        return builder
                .inWater(controller.isInFluidType())
                .inLava(controller.isInLava())
                .isDead(!controller.isAlive())
                .isSleeping(controller.isSleeping())
                .isFallFlying(controller.isFallFlying())
                .isInWall(controller.isInWall())
                .isOnFire(controller.isOnFire())
                .build();
    }

    /**
     * 按序号差投递离散事件。
     * <p>
     * 客户端在若干个连续包里重复携带同一 {@code (eventSeq, eventBits)}，这里只接受比记录更大的
     * 序号，因此重复包不会重复触发；而序号更大的包即使跳号也照样投递，不依赖连续性。
     * <p>
     * 跳跃松开是 {@link MechaEvent#JUMP_RELEASE}，与 {@code DODGE} 等走同一对
     * {@code (eventSeq, eventBits)}，因此同样只被物理线程消费一次。
     */
    private static void dispatchEvents(MechaInputPayload payload, ArmsCore core) {
        UUID coreId = core.getAssemblyId();
        Integer last = LAST_EVENT_SEQ.get(coreId);
        int seq = payload.eventSeq();
        if (last != null && seq <= last) {
            return;
        }
        LAST_EVENT_SEQ.put(coreId, seq);
        for (MechaEvent event : MechaEvent.values()) {
            if (payload.hasEvent(event)) {
                core.postEvent(event);
            }
        }
    }
}
