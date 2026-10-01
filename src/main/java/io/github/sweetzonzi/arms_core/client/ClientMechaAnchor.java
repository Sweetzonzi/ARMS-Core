package io.github.sweetzonzi.arms_core.client;

import io.github.sweetzonzi.arms_core.common.ArmsCore;
import net.minecraft.core.Rotations;
import org.joml.Vector3f;

/**
 * 客户端可视锚点的位姿状态。
 * <p>
 * 客户端不为 {@link ArmsCore} 重建任何物理体，只按同步来的 {@code DATA_POS} / {@code DATA_YAW}
 * 摆放一个非实体锚点（计划 D18、§3.13）。本类负责两件事：
 * <ol>
 *   <li>把最近两次采样保存在 {@code from} / {@code to}，供渲染时线性插值——这是本期唯一的
 *       位姿平滑手段，不做外推（D12、§6）；</li>
 *   <li>识别「跳跃式变化」（首采样、传送、长时间无包），此时直接跳变而不插值，
 *       避免锚点跨越地图拖尾（阶段 3.3）。</li>
 * </ol>
 * <p>
 * 插值的自变量是「距最近一次采样过了多少 tick」而不是「距最近一次渲染过了多少 tick」。
 * 同步按 20 Hz 主线程 tick 发出，渲染帧率远高于 20，因此相邻两帧的采样序号通常相同，
 * 插值进度由帧内的 {@code partialTick} 提供连续变化。
 * <p>
 * 线程模型：全部字段只在客户端主线程读写（同步数据在主线程应用，渲染在同一线程）。
 * 渲染期读到的采样即当前采样，不跨线程。
 *
 * @author Sweetzonzi
 */
public final class ClientMechaAnchor {

    /** 相邻采样位置差超过该值时判定为传送 / 断流，直接跳变 (m) */
    private static final double TELEPORT_DISTANCE = 8.0;

    /** 采样间隔超过该 tick 数时判定为断流，不再插值 */
    private static final long MAX_INTERPOLATION_TICKS = 4L;

    /** 上一次采样的位置 */
    private final Vector3f from = new Vector3f();

    /** 当前采样的位置 */
    private final Vector3f to = new Vector3f();

    /** 上一次采样的偏航角（度） */
    private float fromYaw;

    /** 当前采样的偏航角（度） */
    private float toYaw;

    /** 当前采样被接收时的客户端 tick */
    private long lastAcceptTick = -1L;

    /** 当前采样与上一次采样之间的 tick 间隔；未形成间隔时为 0 */
    private long acceptInterval;

    /** 是否已有两个可插值的采样 */
    private boolean interpolatable;

    /**
     * 用一次同步采样刷新锚点。
     *
     * @param position   KCC 胶囊中心位置（同步来的 {@code DATA_POS}）
     * @param yaw        KCC 绝对 Y 朝向（同步来的 {@code DATA_YAW} 的 Y 分量，度）
     * @param clientTick 当前客户端 tick
     */
    public void accept(Vector3f position, float yaw, long clientTick) {
        if (lastAcceptTick < 0L) {
            // 首采样：没有前值可比，直接落位，不插值
            to.set(position);
            from.set(position);
            toYaw = yaw;
            fromYaw = yaw;
            lastAcceptTick = clientTick;
            acceptInterval = 0L;
            interpolatable = false;
            return;
        }

        double distance = Math.sqrt(
                Math.pow(position.x - to.x, 2) + Math.pow(position.y - to.y, 2) + Math.pow(position.z - to.z, 2));

        from.set(to);
        fromYaw = toYaw;
        to.set(position);
        toYaw = yaw;
        acceptInterval = Math.max(1L, clientTick - lastAcceptTick);
        lastAcceptTick = clientTick;

        // 跳跃式变化（传送 / 断流后首包）直接跳变，不产生横跨地图的插值拖尾
        interpolatable = distance <= TELEPORT_DISTANCE;
    }

    /**
     * 计算插值进度。
     * <p>
     * 结果被钳制在 [0, 1]：超过 1 意味着距上次采样已过一个采样周期而没有新包，
     * 此时停在最新采样上（不外推，D12）。
     */
    private float alpha(long clientTick, float partialTick) {
        if (!interpolatable || acceptInterval <= 0L || acceptInterval > MAX_INTERPOLATION_TICKS) {
            return 1f;
        }
        float elapsed = (clientTick - lastAcceptTick) + partialTick;
        if (elapsed <= 0f) return 0f;
        return Math.min(1f, elapsed / acceptInterval);
    }

    /**
     * 取插值后的锚点位置，写入 {@code dest}。
     *
     * @param clientTick  当前客户端 tick
     * @param partialTick 渲染部分 tick，[0, 1)
     * @param dest        输出缓冲（避免渲染期分配）
     */
    public void lerpPosition(long clientTick, float partialTick, Vector3f dest) {
        dest.set(from).lerp(to, alpha(clientTick, partialTick));
    }

    /**
     * 取插值后的偏航角（度）。
     * <p>
     * 走最短角路径插值，避免 {@code 359° → 1°} 时反向绕行一整圈；返回值归一化到
     * {@code [-180, 180)}，否则两端落在边界两侧时会插出 {@code 360} 这类越界值，
     * 调用方拿它算 {@code sin}/{@code cos} 恰好等价，但拿它做显示或比较就会出错。
     */
    public float lerpYaw(long clientTick, float partialTick) {
        if (!interpolatable) return wrapDegrees(toYaw);
        float a = alpha(clientTick, partialTick);
        return wrapDegrees(fromYaw + wrapDegrees(toYaw - fromYaw) * a);
    }

    /** 当前位置（不做插值），用于调试输出。 */
    public Vector3f current() {
        return to;
    }
    /** 是否已经收到过至少一次采样。 */
    public boolean hasSample() {
        return lastAcceptTick >= 0L;
    }

    /** 手工置位（换维度 / 注销后重置）。 */
    public void reset() {
        from.set(0, 0, 0);
        to.set(0, 0, 0);
        fromYaw = 0;
        toYaw = 0;
        lastAcceptTick = -1L;
        acceptInterval = 0L;
        interpolatable = false;
    }

    /** 从同步数据构造一次采样并写入本锚点。 */
    public void acceptFromSyncedData(ArmsCore core, long clientTick) {
        Vector3f position = core.getSyncedData().get(ArmsCore.DATA_POS);
        Rotations rotations = core.getSyncedData().get(ArmsCore.DATA_YAW);
        accept(position, rotations.getY(), clientTick);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }
}
