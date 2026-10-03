package io.github.sweetzonzi.arms_core.client;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import io.github.sweetzonzi.arms_core.common.control.MechaEvent;
import io.github.sweetzonzi.arms_core.network.payload.MechaInputPayload;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * ARMS-Core 客户端输入采集与上行发送。
 * <p>
 * 客户端不构造 {@code MechaCharacter}、不运行 {@link io.github.sweetzonzi.arms_core.common.control.MechaControl}、
 * 不推进状态机，只做两件事：
 * <ol>
 *   <li>每客户端 tick 采集 WASD / 跳跃 / 冲刺 / 慢走 / 蹲伏 / 视角，打包上行；</li>
 *   <li>维护事件序号与重发窗口，保证单帧边沿跨网络不丢。</li>
 * </ol>
 * <p>
 * <b>发送条件</b>（计划 §3.11）：连续量只在值变化时发包；另有两条强制发包条件——
 * 重发窗口未清空（有待确认事件），以及距上次发包超过 {@link #HEARTBEAT_MS}。
 * 心跳的目的不是补齐延迟，而是让服务端能区分「玩家没动」与「这个客户端的包断了」。
 * <p>
 * <b>为什么离散边沿要带序号。</b> 跳跃松开与 {@link MechaEvent} 的其余项都是单帧标记，
 * 服务端控制器在下一个物理步帧首就会整批取走。只发一帧则丢一个包就永久丢失。
 * 因此客户端把「一个事件」编码为自增序号 + 事件位集，并在接下来
 * {@link #RESEND_WINDOW} 个上行包里重复携带同一对；服务端只接受序号更大的包，
 * 因此重复包不会重复触发（幂等），而丢 1–2 个包也不会丢事件。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public final class ARMSClient {

    private ARMSClient() {
    }

    /** 重发窗口：同一对 {@code (eventSeq, eventBits)} 连续携带的包数 */
    private static final int RESEND_WINDOW = 3;

    /** 心跳间隔 (ms)：无输入变化时也发一包，便于服务端判断上行是否断了 */
    private static final long HEARTBEAT_MS = 1000L;

    /** 本客户端当前控制的装配体；{@code null} 表示尚未认领 */
    @Getter
    private static volatile UUID targetCoreId;

    /** 上帧跳跃键状态，用于检测松开边沿 */
    private static boolean wasJumpDown;

    /** 上帧是否已随目标切换重置过跟踪状态 */
    private static Level trackedLevel;

    // ── 连续量：用于「仅在值变化时发包」 ──

    private static float lastForward;
    private static float lastStrafe;
    private static float lastViewYaw;
    private static float lastViewPitch;
    private static int lastKeyFlags;
    private static boolean hasSentOnce;

    // ── 离散事件：序号 + 重发窗口 ──

    /** 事件序号，每次「产生一个事件」自增一次，而不是每 tick 自增 */
    private static int eventSeq;

    /** 待重发的本批事件位集 */
    private static int pendingEventBits;

    /** 重发窗口剩余包数；为 0 表示窗口已关闭 */
    private static int resendRemaining;

    private static long lastSendMs;

    // ==========================================
    // 主线程 tick：采集 + 发送
    // ==========================================

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Level level = player.level();
        if (level == null) return;

        // 换维度 / 重连后本地注册表随旧 ClientLevel 一并失效，这里补一次清理并重新认领
        if (level != trackedLevel) {
            trackedLevel = level;
            targetCoreId = null;
            hasSentOnce = false;
            pendingEventBits = 0;
            resendRemaining = 0;
        }

        UUID coreId = resolveTargetCore(level, player);
        if (coreId == null) return;

        collectAndSend(player, coreId);
    }

    /**
     * 选取要控制的装配体。
     * <p>
     * 绑定字段是唯一判据：创建包携带 {@code hostEntityId}，客户端只在它等于本地玩家实体 id 时
     * 建立绑定，因此这里直接读本地玩家承载的那一个。
     * <p>
     * 绑定为空时回落到「当前维度内恰好有一个装配体就自动认领」，只为保留调试路径（{@code /arms spawn}
     * 会绑定，但手工构造的、{@code hostEntityId} 为
     * {@link io.github.sweetzonzi.arms_core.network.payload.ArmsCoreCreatePayload#NO_HOST_ENTITY}
     * 的装配体没有宿主）。多个装配体且都没有绑定本地玩家时不做选择。
     */
    private static UUID resolveTargetCore(Level level, LocalPlayer player) {
        ArmsCore bound = ((IArmsHost) player).getControlledArmsCore();
        if (bound != null && MechaCoreRegistry.get(level, bound.getAssemblyId()) != null) {
            targetCoreId = bound.getAssemblyId();
            return targetCoreId;
        }
        UUID current = targetCoreId;
        if (current != null && MechaCoreRegistry.get(level, current) != null) {
            return current;
        }
        UUID only = null;
        int count = 0;
        for (ArmsCore core : MechaCoreRegistry.all(level)) {
            only = core.getAssemblyId();
            count++;
            if (count > 1) break;
        }
        if (count != 1) {
            targetCoreId = null;
            return null;
        }
        targetCoreId = only;
        ARMS.LOGGER.info("[ARMS-Core] 客户端认领装配体 {}", only);
        return only;
    }

    private static void collectAndSend(LocalPlayer player, UUID coreId) {
        Minecraft mc = Minecraft.getInstance();

        // ── 连续输入 ──
        float forward = 0f;
        float strafe = 0f;
        if (mc.options.keyUp.isDown()) forward += 1f;
        if (mc.options.keyDown.isDown()) forward -= 1f;
        if (mc.options.keyLeft.isDown()) strafe += 1f;
        if (mc.options.keyRight.isDown()) strafe -= 1f;

        boolean jumpDown = mc.options.keyJump.isDown();
        boolean jumpReleased = wasJumpDown && !jumpDown;
        wasJumpDown = jumpDown;

        int keyFlags = 0;
        if (jumpDown) keyFlags |= MechaInputPayload.BIT_JUMP;
        if (mc.options.keySprint.isDown()) keyFlags |= MechaInputPayload.BIT_SPRINT;
        if (player.isCrouching()) keyFlags |= MechaInputPayload.BIT_SNEAK;
        // 慢走键：暂未绑定独立按键，保持 false（creep 由单元测试与 /arms 命令覆盖）

        float viewYaw = player.getYRot();
        float viewPitch = player.getXRot();

        boolean changed = !hasSentOnce
                || forward != lastForward
                || strafe != lastStrafe
                || viewYaw != lastViewYaw
                || viewPitch != lastViewPitch
                || keyFlags != lastKeyFlags;

        if (changed) {
            lastForward = forward;
            lastStrafe = strafe;
            lastViewYaw = viewYaw;
            lastViewPitch = viewPitch;
            lastKeyFlags = keyFlags;
        }

        // 重发窗口已结束：清空本批事件位，本批事件不会出现在后续任何包里
        if (resendRemaining <= 0) {
            pendingEventBits = 0;
        }

        // 跳跃松开边沿：与 DODGE 等一样进事件通道，由服务端按序号幂等地投递一次
        // （必须在上面清空之后入队，否则本 tick 刚产生的事件会被窗口清空吞掉）
        if (jumpReleased) {
            queueEvent(MechaEvent.JUMP_RELEASE);
        }

        int eventBits = pendingEventBits;

        boolean heartbeat = System.currentTimeMillis() - lastSendMs >= HEARTBEAT_MS;
        boolean forced = eventBits != 0;
        if (!changed && !forced && !heartbeat) return;

        PacketDistributor.sendToServer(new MechaInputPayload(
                coreId, forward, strafe, viewYaw, viewPitch,
                keyFlags, eventSeq, eventBits));

        hasSentOnce = true;
        lastSendMs = System.currentTimeMillis();
        if (resendRemaining > 0) {
            resendRemaining--;
        }
    }

    // ==========================================
    // 事件产生入口
    // ==========================================

    /**
     * 记录一个待上行事件并开启重发窗口。
     * <p>
     * 供按键绑定、跳跃松开边沿与将来的游戏内输入路径调用。事件停在客户端不会自行消失：
     * 窗口未清空前，每 tick 的发送条件都会因 {@code eventBits != 0} 而强制成包。
     * <p>
     * 同一 tick 内多次调用（例如同一帧里既松开跳跃又按下闪避）会各自自增序号并把位并进位集：
     * 服务端只判断序号是否变大，因此这一批事件整批投递一次。
     *
     * @param event 事件类型
     */
    public static void queueEvent(MechaEvent event) {
        pendingEventBits |= 1 << event.ordinal();
        eventSeq++;
        resendRemaining = RESEND_WINDOW;
    }

    /** 待上行事件位集的只读视图数量，用于调试显示。 */
    public static int pendingEventCount() {
        Set<MechaEvent> set = EnumSet.noneOf(MechaEvent.class);
        for (MechaEvent value : MechaEvent.values()) {
            if ((pendingEventBits & (1 << value.ordinal())) != 0) set.add(value);
        }
        return set.size();
    }
}
