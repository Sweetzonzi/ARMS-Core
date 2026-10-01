package io.github.sweetzonzi.arms_core.common;

import cn.solarmoon.spark_core.event.PhysicsLevelInitEvent;
import cn.solarmoon.spark_core.util.PPhase;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.network.payload.ArmsCoreCreatePayload;
import io.github.sweetzonzi.arms_core.network.payload.ArmsCoreRemovePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 装配体注册表 —— 同时服务物理步扇出与同步写包。
 * <p>
 * 对应 Machine-Max 的 {@code ObjectManager}（`common/mech/ObjectManager.java`）：它是装配体
 * 注册表而非网络类型，因此放在 {@code common/} 下。
 * <p>
 * 两张表各管一头：
 * <ul>
 *   <li>{@link #SERVER_CORES} 键为服务端 {@link ServerLevel} 对象。断线清理需要枚举
 *       「当前存在哪些维度」，而 {@code Level} 对象是现成的枚举源，不必再把维度 id
 *       反查回 Level。</li>
 *   <li>{@link #CLIENT_CORES} 为客户端单维度表（一个客户端同一时刻只在一个维度）。</li>
 * </ul>
 * <p>
 * 线程模型：注册与注销都在主线程；物理步扇出在物理线程只读遍历
 * （{@code ConcurrentHashMap} 的弱一致迭代器允许并发读，且不在遍历中做结构性修改）。
 *
 * @author Sweetzonzi
 */
public final class MechaCoreRegistry {

    private MechaCoreRegistry() {
    }

    /**
     * 服务端：Level → 装配体 UUID → 实例。
     * <p>
     * 键是 {@link ServerLevel} 对象本身而不是维度 id。这不是风格问题：客户端和服务端
     * 在同一个 JVM 里（单人游戏、以及 {@code runClient} 的集成服务端），两端各有自己的
     * {@code Level} 实例，但维度 id 相同。若按维度 id 建表，客户端换维度时的清表会把
     * 服务端同维度的表项一并删掉，表现为「换维度后服务端装配体凭空消失」。
     * <p>
     * 这与 Machine-Max 的 {@code ObjectManager.levelVehicles}（`common/mech/ObjectManager.java:45`）
     * 的做法一致：按 {@code Level} 索引，两端天然分表。
     */
    private static final Map<ServerLevel, Map<UUID, ArmsCore>> SERVER_CORES = new ConcurrentHashMap<>();

    /** 客户端：当前维度的装配体 UUID → 实例 */
    private static final Map<UUID, ArmsCore> CLIENT_CORES = new ConcurrentHashMap<>();

    /** 收到未知 {@code coreId} 增量包的累计次数，用于告警节流 */
    private static final AtomicLong UNKNOWN_SYNC_COUNT = new AtomicLong();

    // ==========================================
    // 查询
    // ==========================================

    /**
     * 按所在 Level 与 UUID 取实例；两端通用。
     *
     * @return 未注册时返回 {@code null}
     */
    public static @Nullable ArmsCore get(Level level, UUID coreId) {
        if (level instanceof ServerLevel serverLevel) {
            Map<UUID, ArmsCore> inLevel = SERVER_CORES.get(serverLevel);
            return inLevel == null ? null : inLevel.get(coreId);
        }
        return level.isClientSide() ? CLIENT_CORES.get(coreId) : null;
    }

    /**
     * 取某维度当前全部实例的可遍历视图。
     * <p>
     * 物理线程每步都要遍历，因此不复制集合；返回值是并发映射的视图。
     */
    public static Iterable<ArmsCore> all(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            Map<UUID, ArmsCore> inLevel = SERVER_CORES.get(serverLevel);
            return inLevel == null ? List.of() : inLevel.values();
        }
        return level.isClientSide() ? CLIENT_CORES.values() : List.of();
    }

    /** 某维度当前的实例个数。 */
    public static int size(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            Map<UUID, ArmsCore> inLevel = SERVER_CORES.get(serverLevel);
            return inLevel == null ? 0 : inLevel.size();
        }
        return level.isClientSide() ? CLIENT_CORES.size() : 0;
    }

    /** 服务端已登记的全部维度（快照，可安全在遍历中注销）。 */
    public static List<ServerLevel> serverLevels() {
        return new ArrayList<>(SERVER_CORES.keySet());
    }

    /** 该服务端维度当前登记的装配体（快照）。 */
    public static List<ArmsCore> snapshot(ServerLevel level) {
        Map<UUID, ArmsCore> inLevel = SERVER_CORES.get(level);
        return inLevel == null ? List.of() : new ArrayList<>(inLevel.values());
    }

    // ==========================================
    // 服务端注册 / 注销
    // ==========================================

    /**
     * 服务端注册一个装配体，并向该维度广播创建包。
     * <p>
     * 创建包携带 {@code getNonDefaultValues()} 全量初值：增量包只在变化时发出，
     * 后加入的客户端否则永远看不到当前状态。
     *
     * @param core         待注册实例
     * @param hostEntityId 宿主实体 id；无实体宿主传
     *                     {@link ArmsCoreCreatePayload#NO_HOST_ENTITY}
     * @return 是否注册成功（已存在同 UUID 时返回 {@code false} 且不重复注册）
     */
    public static boolean addServer(ArmsCore core, int hostEntityId) {
        Level level = core.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("addServer 只能在服务端调用");
        }
        if (inLevel(serverLevel).putIfAbsent(core.getAssemblyId(), core) != null) {
            ARMS.LOGGER.warn("[ARMS-Core] 服务端重复注册装配体 {}，忽略", core.getAssemblyId());
            return false;
        }
        PacketDistributor.sendToPlayersInDimension(serverLevel, createPayload(core, hostEntityId));
        ARMS.LOGGER.info("[ARMS-Core] 注册装配体 {} 于 {}", core.getAssemblyId(), level.dimension().location());
        return true;
    }

    /**
     * 服务端注销一个装配体，并向该维度广播移除包。
     *
     * @return 该 UUID 此前是否已注册
     */
    public static boolean removeServer(ArmsCore core) {
        Level level = core.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("removeServer 只能在服务端调用");
        }
        Map<UUID, ArmsCore> inLevel = SERVER_CORES.get(serverLevel);
        if (inLevel == null || inLevel.remove(core.getAssemblyId()) == null) {
            return false;
        }
        MechaInputHandler.forget(core.getAssemblyId());
        PacketDistributor.sendToPlayersInDimension(serverLevel,
                new ArmsCoreRemovePayload(level.dimension(), core.getAssemblyId()));
        ARMS.LOGGER.info("[ARMS-Core] 注销装配体 {} 于 {}", core.getAssemblyId(), level.dimension().location());
        return true;
    }

    /** 服务端某个 Level 卸载时清理它自己的表项，不广播（连接已经不在了）。 */
    public static void clearLevel(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        Map<UUID, ArmsCore> removed = SERVER_CORES.remove(serverLevel);
        if (removed != null && !removed.isEmpty()) {
            ARMS.LOGGER.info("[ARMS-Core] 清理维度 {} 的 {} 个装配体",
                    level.dimension().location(), removed.size());
        }
    }

    /**
     * Level 卸载的统一入口。
     * <p>
     * <b>只清理被卸载的那个 Level 实例</b>，不做「按端整体清空」：客户端与服务端同处一个
     * JVM，客户端换维度时会卸载旧的 {@code ClientLevel}，若此时顺手清掉服务端的表，
     * 服务端的装配体会凭空消失（本类的 {@link #SERVER_CORES} 注释记录了这个坑）。
     * 客户端那一侧同理，只需要清掉自己这一个 {@code Level} 的条目。
     * <p>
     * 客户端清空后由服务端补发的创建包重新填充：服务端在玩家换维度、重生、登录时都会
     * 遍历该维度注册表逐个发创建包，因此客户端不需要额外的反向请求包。
     */
    public static void onLevelUnload(Level level) {
        if (level.isClientSide()) {
            clearClient();
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            for (ArmsCore core : snapshot(serverLevel)) {
                MechaInputHandler.forget(core.getAssemblyId());
            }
        }
        clearLevel(level);
    }

    // ==========================================
    // 客户端注册 / 注销
    // ==========================================

    /** 客户端注册由创建包构造的实例（不广播）。 */
    public static void addClient(ArmsCore core) {
        CLIENT_CORES.put(core.getAssemblyId(), core);
    }

    /**
     * 客户端注销实例。
     *
     * @return 该 UUID 此前是否已注册
     */
    public static boolean removeClient(Level level, UUID coreId) {
        if (!level.isClientSide()) return false;
        return CLIENT_CORES.remove(coreId) != null;
    }

    /**
     * 客户端换维度 / 重连时清空本地表。
     * <p>
     * 清空后由服务端补发的创建包重新填充：服务端在玩家换维度、重生、登录时都会
     * 遍历该维度注册表逐个发创建包，因此客户端不需要额外的反向请求包。
     */
    public static void clearClient() {
        CLIENT_CORES.clear();
    }

    // ==========================================
    // 登录 / 换维度补发
    // ==========================================

    /**
     * 把某维度内全部装配体的创建包发给单个玩家。
     * <p>
     * 与 {@link #addServer} 使用同一份载荷构造逻辑，因此全量初值的语义不会在两处漂移。
     */
    public static void sendAllTo(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        List<ArmsCore> cores = snapshot(serverLevel);
        if (cores.isEmpty()) return;
        for (ArmsCore core : cores) {
            PacketDistributor.sendToPlayer(player, createPayload(core, ArmsCoreCreatePayload.NO_HOST_ENTITY));
        }
        ARMS.LOGGER.info("[ARMS-Core] 向玩家 {} 补发维度 {} 的 {} 个装配体",
                player.getGameProfile().getName(), player.level().dimension().location(), cores.size());
    }

    // ==========================================
    // 物理入世
    // ==========================================

    /**
     * 提交「把 KCC 放入物理空间」的任务。
     * <p>
     * 出生点参数是**胶囊中心**坐标，不是脚底坐标（计划 §3.14）。任务体本身只允许在
     * 物理线程执行，因此这里统一经 {@code SparkLevel.submitImmediateTask} 投递。
     *
     * @param core          目标实例（服务端实例）
     * @param capsuleCenter 胶囊中心的世界坐标
     */
    public static void enterPhysicsSpace(ArmsCore core, Vector3f capsuleCenter) {
        Level level = core.getLevel();
        Vector3f target = capsuleCenter.clone();
        cn.solarmoon.spark_core.api.SparkLevel.submitImmediateTask(level, PPhase.ALL,
                () -> core.enterPhysicsSpace(target));
    }

    /** 记录物理空间就绪，便于排查「构造早于物理空间初始化」。 */
    public static void onPhysicsLevelInit(PhysicsLevelInitEvent event) {
        ARMS.LOGGER.debug("[ARMS-Core] 维度 {} 物理空间就绪",
                event.getLevel().getMcLevel().dimension().location());
    }

    // ==========================================
    // 告警计数
    // ==========================================

    /** 记录一次「增量包先于创建包到达」。按 2 的幂节流打印，避免刷屏。 */
    public static void reportUnknownSync(UUID coreId) {
        long count = UNKNOWN_SYNC_COUNT.incrementAndGet();
        if (Long.bitCount(count) == 1) {
            ARMS.LOGGER.warn("[ARMS-Core] 收到未知装配体 {} 的增量包，累计丢弃 {} 次", coreId, count);
        }
    }

    // ==========================================
    // 内部
    // ==========================================

    private static Map<UUID, ArmsCore> inLevel(ServerLevel level) {
        return SERVER_CORES.computeIfAbsent(level, key -> new ConcurrentHashMap<>());
    }

    private static ArmsCoreCreatePayload createPayload(ArmsCore core, int hostEntityId) {
        return new ArmsCoreCreatePayload(
                core.getLevel().dimension(),
                core.getAssemblyId(),
                hostEntityId,
                core.getSyncedData().getNonDefaultValues());
    }
}
