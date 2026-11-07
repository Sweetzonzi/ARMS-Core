package io.github.sweetzonzi.arms_core.common.debug;

import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.common.part.ArmsBodyPhysics;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import net.minecraft.core.Rotations;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 双端 × 双线程的位姿录制：把控制器、锚点与核心 subPart 的位姿 / 速度按时间写成 CSV。
 *
 * <p><b>为什么分两条通道。</b>同一份数据在物理线程与主线程上不是同一件事：物理线程手里的刚体
 * 位姿是求解器当前的真实状态；主线程手里的 {@code DATA_POS} / {@code DATA_VEL} 是准备发给客户端
 * 的那一份，{@code SubPart.transform} / {@code oldTransform} 则是渲染插值的两端。抖动出现在
 * 哪一段，要靠两条通道对齐时间戳才能判断，所以每次录制开两个文件：</p>
 *
 * <pre>
 *   logs/arms_core/server-physics-&lt;时间戳&gt;.csv   物理线程：控制器刚体、锚点刚体、核心 subPart 刚体
 *   logs/arms_core/server-main-&lt;时间戳&gt;.csv      主线程：DATA_POS / DATA_VEL / DATA_YAW、宿主实体位置
 *   logs/arms_core/client-physics-&lt;时间戳&gt;.csv   物理线程：客户端 subPart 刚体（运动学，位姿来自同步）
 *   logs/arms_core/client-main-&lt;时间戳&gt;.csv      主线程：同步值、subPart 的 transform / oldTransform
 * </pre>
 *
 * <p><b>锚点只在物理线程。</b>{@code ArmsBodyPhysics#anchor} 是约束 A 端的运动学刚体，没有客户端副本，
 * 也不参与任何插值；它只出现在 {@code server-physics} 里。客户端那条呈现时间轴在录制的
 * {@code pres_ctrl} 行（控制器呈现缓冲的右端，见 {@link #appendPresentation}），与它无关。</p>
 *
 * <p><b>开关。</b>服务端用 {@code /arms record start|stop}，客户端用 {@code /armsc record start|stop}
 * （客户端命令见 {@code client/ClientTelemetryCommand.java}）。两端各写各的文件；单人游戏下两端在同一个
 * JVM 里，但各走各的静态会话与写线程。</p>
 *
 * <p><b>线程模型。</b>采样点有两个：物理步（物理线程）与主线程 tick（主线程）。物理线程只把已经格式化的行
 * 放进 {@link Channel} 的无锁队列，主线程每 tick 排空写盘；因此磁盘慢不会拖住物理步进。队列本身是线程安全的，
 * 写入口只有一个。</p>
 *
 * <p>列定义见 {@link #HEADER}。{@code role} 的取值：{@code controller}（控制器刚体）、
 * {@code anchor}（运动学锚点，仅服务端物理通道）、{@code sub}（核心 subPart 刚体）、
 * {@code sync_ctrl}（同步值）、{@code host}（宿主实体，两端都记：服务端是回写后的实体位置，客户端是
 * 机体模型被画在的那个渲染原点）、{@code sub_old}（渲染插值的起点）、
 * {@code sub_raw}（核心 subPart 自己的同步值，即它的网络真值）、
 * {@code pres_ctrl}（控制器呈现缓冲的右端，客户端模型锚点的取样源）。速度列：控制器与核心刚体读刚体本身；
 * 锚点写 {@code ArmsBodyPhysics} 保存的本步目标速度（运动学刚体的速度读不回来）；客户端 subPart 也是
 * 运动学刚体，写 {@code NaN}。</p>
 *
 * <p><b>{@code sub} 与 {@code pres_ctrl} 的附加列是缓冲计数。</b>两者的 {@code extra2}{@code extra3}{@code extra4}
 * 依次是「累计停住 tick 数 / 累计丢弃样本数 / 当前余量」，来源是
 * {@code ../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/DestroyableObject.java#presentationStarvedTicks}
 * 与 {@code common/ArmsCore.java#presentationStarvedTicks}。三个数回答的是同一个问题：停帧究竟是供给的样本
 * 少于播放的 tick（停住数一直涨、余量始终 0），还是积压后的追赶（丢弃数上涨）。服务端没有呈现缓冲，
 * 这些列在服务端写 {@code 0/0/-1}。</p>
 *
 * <p><b>{@code host} 的附加列是 {@code xo/yo/zo}。</b>它们与主位置列一起构成渲染期实际使用的那一对
 * {@code lerp(xo, x, partialTick)}，用来核对模型渲染原点与子零件调试框是否插值在同样的两份样本上
 * （见 {@link #appendHost}）。</p>
 *
 * @author Sweetzonzi
 */
public final class MechaTelemetry {

    /**
     * CSV 列名。总列数 26：
     * 4 个整数、5 个字符串、13 个浮点、4 个附加字符串。
     */
    public static final String HEADER = "wall_ms,nanos,tick,seq,side,lane,assembly,role,name,"
            + "x,y,z,qx,qy,qz,qw,vx,vy,vz,wx,wy,wz,extra1,extra2,extra3,extra4";

    private static final String ROW_FORMAT = "%d,%d,%d,%d,%s,%s,%s,%s,%s,"
            + "%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s,%s,%s,%s";

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** 服务端会话；{@code null} 表示未在录制。 */
    private static volatile Session serverSession;
    /** 客户端会话；{@code null} 表示未在录制。 */
    private static volatile Session clientSession;

    private MechaTelemetry() {
    }

    // ==========================================
    // 开关
    // ==========================================

    /** 开/停服务端录制，返回本次操作涉及的文件说明。 */
    public static synchronized String startServer() {
        Session existing = serverSession;
        if (existing != null) return existing.describe();
        Session session = open("server");
        if (session == null) throw new IllegalStateException("创建服务端录制文件失败，详见日志");
        serverSession = session;
        return session.describe();
    }

    /** 停止服务端录制；未在录制时返回 {@code null}。 */
    public static synchronized @Nullable String stopServer() {
        Session session = serverSession;
        if (session == null) return null;
        serverSession = null;
        session.close();
        return session.describe();
    }

    /** 开/停客户端录制，返回本次操作涉及的文件说明。 */
    public static synchronized String startClient() {
        Session existing = clientSession;
        if (existing != null) return existing.describe();
        Session session = open("client");
        if (session == null) throw new IllegalStateException("创建客户端录制文件失败，详见日志");
        clientSession = session;
        return session.describe();
    }

    /** 停止客户端录制；未在录制时返回 {@code null}。 */
    public static synchronized @Nullable String stopClient() {
        Session session = clientSession;
        if (session == null) return null;
        clientSession = null;
        session.close();
        return session.describe();
    }

    public static boolean isServerRecording() {
        return serverSession != null;
    }

    public static boolean isClientRecording() {
        return clientSession != null;
    }

    /** 维度卸载时收尾，避免文件句柄跟着旧维度泄漏。 */
    public static synchronized void stopAll() {
        Session server = serverSession;
        serverSession = null;
        if (server != null) server.close();
        Session client = clientSession;
        clientSession = null;
        if (client != null) client.close();
    }

    // ==========================================
    // 采样入口
    // ==========================================

    /**
     * 物理线程：每个物理步一次。服务端记控制器刚体、锚点刚体与核心 subPart 刚体；
     * 客户端只记核心 subPart 刚体（它没有控制器刚体与锚点刚体）。
     *
     * @param core 刚步进完的装配体
     * @param dt   本物理步的时长 (s)
     */
    public static void onPhysicsStep(ArmsCore core, float dt) {
        Session session = core.isAuthoritative() ? serverSession : clientSession;
        if (session == null) return;

        long wall = System.currentTimeMillis();
        long nanos = System.nanoTime();
        long tick = core.getLevel().getGameTime();
        long seq = session.physicsSeq.incrementAndGet();
        String dtText = format(dt);

        if (core.isAuthoritative()) {
            MechaControl control = core.getMechaControl();
            MechaCharacter controller = control == null ? null : control.getBody();
            if (controller != null) {
                appendRigid(session.physics, wall, nanos, tick, seq, session.side, "physics",
                        core.getAssemblyId(), "controller", "body", controller, dtText);
            }
            ArmsBodyPhysics physics = core.getBodyPhysics();
            if (physics != null) {
                appendAnchor(session.physics, wall, nanos, tick, seq, session.side,
                        core.getAssemblyId(), physics, dtText);
            }
        }
        appendSubPartBodies(session, core, wall, nanos, tick, seq, dtText);
    }

    /**
     * 服务端主线程：{@code syncToClients} 写完 {@code syncedData} 之后调用。
     * 记的是「即将发给客户端的值」，不是刚体当前值。
     */
    public static void onServerMainTick(ArmsCore core) {
        Session session = serverSession;
        if (session == null || core.getMechaControl() == null) return;

        long wall = System.currentTimeMillis();
        long nanos = System.nanoTime();
        long tick = core.getLevel().getGameTime();
        long seq = session.mainSeq.incrementAndGet();

        appendSyncedController(session, core, wall, nanos, tick, seq);
        appendSubPartRaw(session, core, wall, nanos, tick, seq);
        appendHost(session, core, wall, nanos, tick, seq);
    }

    /**
     * 客户端主线程：每 tick 一次，记同步值与渲染插值用的两帧 {@code Transform}。
     *
     * <p>客户端没有控制器刚体，也没有约束锚点：控制器一侧能拿到的是同步后的 {@code DATA_POS} /
     * {@code DATA_VEL}，插值一侧是 {@code SubPart.transform} / {@code oldTransform}。</p>
     * <p>{@code host} 行在客户端记的是宿主实体（本机玩家）的位置，也就是机体模型被画在的那个渲染原点；
     * 它与 {@code sub}（子零件调试框的取样端）指向同一份位姿的两个消费者，两者错位就是
     * 「调试框跑到机体前面」。</p>
     */
    public static void onClientMainTick(ArmsCore core) {
        Session session = clientSession;
        if (session == null) return;

        long wall = System.currentTimeMillis();
        long nanos = System.nanoTime();
        long tick = core.getLevel().getGameTime();
        long seq = session.mainSeq.incrementAndGet();

        appendSyncedController(session, core, wall, nanos, tick, seq);
        appendPresentation(session, core, wall, nanos, tick, seq);
        appendSubPartRaw(session, core, wall, nanos, tick, seq);
        appendPartTransforms(session, core, wall, nanos, tick, seq, "sub", false);
        appendPartTransforms(session, core, wall, nanos, tick, seq, "sub_old", true);
        appendHost(session, core, wall, nanos, tick, seq);
    }

    /** 主线程：把服务端两条通道的队列写盘。 */
    public static void flushServer() {
        Session session = serverSession;
        if (session == null) return;
        session.flush();
    }

    /** 主线程：把客户端两条通道的队列写盘。 */
    public static void flushClient() {
        Session session = clientSession;
        if (session == null) return;
        session.flush();
    }

    // ==========================================
    // 行构造
    // ==========================================

    private static void appendSubPartBodies(Session session, ArmsCore core, long wall, long nanos,
                                            long tick, long seq, String dtText) {
        var corePart = core.getCorePart();
        if (corePart == null) return;
        for (Map.Entry<String, SubPart> entry : corePart.subParts.entrySet()) {
            PhysicsRigidBody body = entry.getValue().body;
            appendRigid(session.physics, wall, nanos, tick, seq, session.side, "physics",
                    core.getAssemblyId(), "sub", entry.getKey(), body, dtText);
        }
    }

    private static void appendRigid(Channel out, long wall, long nanos, long tick, long seq,
                                    String side, String lane, UUID assembly, String role, String name,
                                    PhysicsRigidBody body, String extra1) {
        Vector3f location = body.getPhysicsLocation(null);
        Quaternion rotation = body.getPhysicsRotation(null);
        boolean dynamic = body.isDynamic();
        Vector3f linear = dynamic ? body.getLinearVelocity(null) : null;
        Vector3f angular = dynamic ? body.getAngularVelocity(null) : null;
        append(out, wall, nanos, tick, seq, side, lane, assembly, role, name,
                location, rotation, linear, angular, extra1, "", "", dynamic ? "dynamic" : "kinematic");
    }

    /**
     * 锚点行：位姿直接读刚体，速度用 {@link ArmsBodyPhysics} 保存的本步写入值。
     * 运动学刚体的 {@code getLinearVelocity} 在 Minie 里带 {@code assert isDynamic()}，读不回来。
     */
    private static void appendAnchor(Channel out, long wall, long nanos, long tick, long seq,
                                     String side, UUID assembly, ArmsBodyPhysics physics, String extra1) {
        PhysicsRigidBody anchor = physics.anchor();
        append(out, wall, nanos, tick, seq, side, "physics", assembly, "anchor", "anchor",
                anchor.getPhysicsLocation(null), anchor.getPhysicsRotation(null),
                physics.anchorLinearVelocity(null), physics.anchorAngularVelocity(null),
                extra1, "", "", "kinematic");
    }

    private static void appendSyncedController(Session session, ArmsCore core, long wall, long nanos,
                                               long tick, long seq) {
        var data = core.getSyncedData();
        org.joml.Vector3f position = data.get(ArmsCore.DATA_POS);
        org.joml.Vector3f velocity = data.get(ArmsCore.DATA_VEL);
        Rotations yaw = data.get(ArmsCore.DATA_YAW);

        double halfYaw = Math.toRadians(yaw.getY()) * 0.5;
        Quaternion rotation = new Quaternion(0f, (float) Math.sin(halfYaw), 0f, (float) Math.cos(halfYaw));
        append(session.main, wall, nanos, tick, seq, session.side, "main", core.getAssemblyId(),
                "sync_ctrl", "DATA",
                new Vector3f(position.x, position.y, position.z), rotation,
                new Vector3f(velocity.x, velocity.y, velocity.z), null,
                format(yaw.getY()),
                data.get(ArmsCore.DATA_POSTURE),
                data.get(ArmsCore.DATA_GAIT),
                data.get(ArmsCore.DATA_VERTICAL));
    }

    /**
     * 宿主实体行：位置是实体当前坐标，附加列写 {@code xo/yo/zo}（上一 tick 的坐标）。
     * <p>
     * 两个值合起来就是渲染期实际用的那一对：{@code lerp(xo, x, partialTick)}。客户端据此可以核对
     * 「模型渲染原点的插值区间」与 {@code sub}/{@code sub_old} 给出的「子零件调试框的插值区间」
     * 是否指向同两份样本。
     */
    private static void appendHost(Session session, ArmsCore core, long wall, long nanos, long tick, long seq) {
        IArmsHost host = core.getHost();
        if (host == null) return;
        var entity = host.getHostEntity();
        if (entity == null) return;
        append(session.main, wall, nanos, tick, seq, session.side, "main", core.getAssemblyId(),
                "host", "entity",
                new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ()),
                null, null, null,
                format(entity.xo), format(entity.yo), format(entity.zo), "");
    }

    private static void appendPartTransforms(Session session, ArmsCore core, long wall, long nanos,
                                             long tick, long seq, String role, boolean old) {
        var corePart = core.getCorePart();
        if (corePart == null) return;
        for (Map.Entry<String, SubPart> entry : corePart.subParts.entrySet()) {
            SubPart subPart = entry.getValue();
            Transform transform = old ? subPart.oldTransform : subPart.transform;
            if (transform == null) continue;
            append(session.main, wall, nanos, tick, seq, session.side, "main", core.getAssemblyId(),
                    role, entry.getKey(), transform.getTranslation(), transform.getRotation(),
                    null, null, old ? "oldTransform" : "transform",
                    old ? "" : Long.toString(subPart.presentationStarvedTicks()),
                    old ? "" : Long.toString(subPart.presentationSkippedSamples()),
                    old ? "" : Long.toString(subPart.presentationReserve()));
        }
    }

    /**
     * 核心 subPart 自己的同步值，也就是它这条通道的网络真值。
     * <p>
     * 与 {@code sub}（呈现位姿）和 {@code sync_ctrl}（控制器的同步值）合起来，可以分别算出
     * 「子零件通道的样本供给」与「控制器通道的样本供给」，从而判定呈现缓冲的停帧是不是供给不足。
     * 服务端同一行记的是即将发给客户端的那一份。
     */
    private static void appendSubPartRaw(Session session, ArmsCore core, long wall, long nanos,
                                         long tick, long seq) {
        var corePart = core.getCorePart();
        if (corePart == null) return;
        for (Map.Entry<String, SubPart> entry : corePart.subParts.entrySet()) {
            SubPart subPart = entry.getValue();
            append(session.main, wall, nanos, tick, seq, session.side, "main", core.getAssemblyId(),
                    "sub_raw", entry.getKey(), subPart.getPosition(), subPart.getRotation(),
                    null, null, "DATA_POS", "", "", "");
        }
    }

    /**
     * 控制器呈现缓冲的右端，也就是客户端模型每 tick 取到的那个位置
     * （{@code client/MechaAnimatable.java#samplePresentationPosition}），外加该缓冲的三个计数。
     * <p>
     * 与 {@code sub} 行对照即可看出「模型」与「SubPart 调试框」这两条呈现时间轴各自停在哪里：
     * 两条轴的滞后期望相同，实际偏移随各自的停帧节奏摆动。
     */
    private static void appendPresentation(Session session, ArmsCore core, long wall, long nanos,
                                           long tick, long seq) {
        org.joml.Vector3f position = new org.joml.Vector3f();
        if (!core.lerpPresentationPosition(1f, position)) return;
        double halfYaw = Math.toRadians(core.lerpPresentationYaw(1f)) * 0.5;
        Quaternion rotation = new Quaternion(0f, (float) Math.sin(halfYaw), 0f, (float) Math.cos(halfYaw));
        append(session.main, wall, nanos, tick, seq, session.side, "main", core.getAssemblyId(),
                "pres_ctrl", "DATA",
                new Vector3f(position.x, position.y, position.z), rotation, null, null,
                Long.toString(core.presentationStarvedTicks()),
                Long.toString(core.presentationSkippedSamples()),
                Long.toString(core.presentationReserve()), "");
    }

    /**
     * 写一行。任一位姿缺失时写 {@code NaN}，保持列数不变。
     */
    private static void append(Channel out, long wall, long nanos, long tick, long seq,
                               String side, String lane, UUID assembly, String role, String name,
                               @Nullable Vector3f location, @Nullable Quaternion rotation,
                               @Nullable Vector3f linear, @Nullable Vector3f angular,
                               String extra1, String extra2, String extra3, String extra4) {
        double x = location == null ? Double.NaN : location.x;
        double y = location == null ? Double.NaN : location.y;
        double z = location == null ? Double.NaN : location.z;
        double qx = rotation == null ? Double.NaN : rotation.getX();
        double qy = rotation == null ? Double.NaN : rotation.getY();
        double qz = rotation == null ? Double.NaN : rotation.getZ();
        double qw = rotation == null ? Double.NaN : rotation.getW();
        double vx = linear == null ? Double.NaN : linear.x;
        double vy = linear == null ? Double.NaN : linear.y;
        double vz = linear == null ? Double.NaN : linear.z;
        double wx = angular == null ? Double.NaN : angular.x;
        double wy = angular == null ? Double.NaN : angular.y;
        double wz = angular == null ? Double.NaN : angular.z;

        out.offer(String.format(Locale.ROOT, ROW_FORMAT,
                wall, nanos, tick, seq, side, lane, assembly, role, name,
                x, y, z, qx, qy, qz, qw, vx, vy, vz, wx, wy, wz,
                extra1, extra2, extra3, extra4));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    // ==========================================
    // 会话与通道
    // ==========================================

    private static @Nullable Session open(String side) {
        try {
            Channel physics = Channel.open(side, "physics", HEADER);
            Channel main = Channel.open(side, "main", HEADER);
            return new Session(side, physics, main);
        } catch (IOException e) {
            ARMS.LOGGER.error("[ARMS-Core] 无法创建 {} 录制文件: {}", side, e.toString());
            return null;
        }
    }

    /** 一端一次录制的两条通道与各自的序号。 */
    private static final class Session {
        private final String side;
        private final Channel physics;
        private final Channel main;
        private final AtomicLong physicsSeq = new AtomicLong();
        private final AtomicLong mainSeq = new AtomicLong();

        private Session(String side, Channel physics, Channel main) {
            this.side = side;
            this.physics = physics;
            this.main = main;
        }

        private String describe() {
            return side + " 录制中：physics=" + physics.path() + "（" + physics.samples() + " 行）"
                    + "，main=" + main.path() + "（" + main.samples() + " 行）";
        }

        private void flush() {
            physics.flush();
            main.flush();
        }

        private void close() {
            physics.close();
            main.close();
        }
    }

    /**
     * 一个 CSV 文件。物理线程只入队，主线程每 tick 排空写盘；磁盘慢不会拖住物理步进。
     * {@link #offer(String)} 可在任意线程调用，{@link #flush()} 与 {@link #close()} 必须在主线程调用。
     */
    private static final class Channel implements AutoCloseable {

        private final Path path;
        private final Queue<String> pending = new ConcurrentLinkedQueue<>();
        private final AtomicLong samples = new AtomicLong();

        /** 主线程独占的写入口。 */
        private final BufferedWriter writer;

        /** 是否已停止接收；物理线程读取。 */
        private volatile boolean closed;

        private Channel(Path path, BufferedWriter writer) {
            this.path = path;
            this.writer = writer;
        }

        /** 新建一个采样文件并写入列名。 */
        private static Channel open(String side, String lane, String header) throws IOException {
            Path directory = FMLPaths.GAMEDIR.get().resolve("logs").resolve("arms_core");
            Files.createDirectories(directory);
            String name = side + "-" + lane + "-" + FILE_STAMP.format(LocalDateTime.now()) + ".csv";
            Path file = directory.resolve(name);
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            writer.write(header);
            writer.newLine();
            writer.flush();
            return new Channel(file, writer);
        }

        /** 物理线程：提交一行采样。已关闭时丢弃。 */
        private void offer(String line) {
            if (closed) return;
            pending.add(line);
            samples.incrementAndGet();
        }

        /** 主线程：把队列里的行写盘并 flush。没有排队行时不碰磁盘。 */
        private void flush() {
            if (closed) return;
            try {
                writePending();
            } catch (IOException e) {
                ARMS.LOGGER.error("[ARMS-Core] 录制写盘失败 {}，本路录制结束: {}", path, e.toString());
                close();
            }
        }

        private void writePending() throws IOException {
            String line = pending.poll();
            if (line == null) return;
            do {
                writer.write(line);
                writer.newLine();
            } while ((line = pending.poll()) != null);
            writer.flush();
        }

        /** 主线程：写完剩余行并关闭文件。可重复调用。 */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                writePending();
            } catch (IOException e) {
                ARMS.LOGGER.error("[ARMS-Core] 录制收尾失败 {}: {}", path, e.toString());
            }
            try {
                writer.close();
            } catch (IOException e) {
                ARMS.LOGGER.error("[ARMS-Core] 录制关闭失败 {}: {}", path, e.toString());
            }
        }

        private Path path() {
            return path;
        }

        /** 已提交的行数，含尚未写盘的。 */
        private long samples() {
            return samples.get();
        }
    }
}
