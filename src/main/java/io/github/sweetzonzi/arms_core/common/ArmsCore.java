package io.github.sweetzonzi.arms_core.common;

import cn.solarmoon.spark_core.api.SparkLevel;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.common.control.MechaControlHolder;
import io.github.sweetzonzi.arms_core.common.control.attr.MechAttr;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.machine_max.common.mech.subsystem.SubsystemController;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.IPartAssembly;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.Part;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.connector.AbstractConnector;
import net.minecraft.core.Rotations;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 机娘核心类 —— 装配体级逻辑机甲单元。
 * <p>
 * 与 Machine-Max 的 {@code VehicleCore} 同级：由 {@link Part} 通过 {@link AbstractConnector}
 * 组装而成，实现 {@link IPartAssembly}，并额外持有角色运动控制器 {@link MechaControl}
 * 与逻辑状态机。与载具不同，它不注册为世界实体，生命周期跟随宿主装配体
 * （见 `docs/总体设计文档.md:82`）。
 * <p>
 * <b>权威归属</b>：服务端是唯一权威端，客户端不运行 KCC 与状态机
 * （`docs/ArmsCore双端权威与网络同步实现计划.md` D1、§1.3）。因此本类有两条构造路径：
 * <ul>
 *   <li><b>服务端</b>（{@link #ArmsCore(Level, UUID)}）：构造 KCC，由 Level 级注册表在物理线程驱动；</li>
 *   <li><b>客户端</b>（{@link #newClientInstance(Level, UUID)}）：不构造 KCC，只作为
 *       {@link SynchedEntityData} 的客户端副本载体与可视锚点的宿主。</li>
 * </ul>
 * <p>
 * <b>构造前置（仅服务端路径）</b>：构造器会直接从所在 Level 取物理空间
 * （{@code SparkLevel.getPhysicsLevel(level).getWorld()}）并创建 KCC。该前置在正常流程下由
 * Spark-Core 的初始化顺序保证——`PhysicsLevelApplier.kt:24-36` 在 {@code LevelEvent.Load}
 * 中设置 `PhysicsLevel` 并 `start()`，而 {@code ArmsCore} 总是由更晚的创建路径（宿主登录、
 * 装配体创建包、调试命令）构造。
 * <p>
 * <b>线程模型</b>：构造与同步写入（{@link SynchedEntityData#set}）在主线程；
 * {@link #prePhysicsTick(float)} 只允许在物理线程调用，KCC 入世
 * （`setPhysicsLocation` + `addCollisionObject`）须经
 * `SparkLevel.submitImmediateTask` 投递。服务端 KCC 的位姿与速度由主线程在
 * {@link #syncToClients()} 中直接读取（良性竞态，与 `DestroyableRigidObject.postTick()`
 * 同等接受）；逻辑层五项则走 {@link MechaControl.LogicStateSnapshot} 不可变发布。
 *
 * @author Sweetzonzi
 */
public class ArmsCore implements IPartAssembly, MechaControlHolder, SyncedDataHolder {

    // ═══════════════════════════════════════════════
    // 同步字段（accessor 表，只追加）
    // ═══════════════════════════════════════════════

    // 全部 accessor 必须是 static final，且其静态初始化先于任何实例构造，
    // 这样 SynchedEntityData.Builder 在构造器内看到的是完整 id 区间 [0, count)。
    // 字段 id 由 defineId 的调用顺序（即下面的文本顺序）决定，因此只允许在末尾追加；
    // 删除、重排或在中间插入会让该位置之后的 id 全部平移，导致双端线上格式错配。

    /** KCC 物理位置（世界坐标，胶囊中心） */
    public static final EntityDataAccessor<org.joml.Vector3f> DATA_POS =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.VECTOR3);

    /** KCC 线速度（水平分量为每物理步位移，垂直分量为 m/s，见计划 §3.10） */
    public static final EntityDataAccessor<org.joml.Vector3f> DATA_VEL =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.VECTOR3);

    /** 控制器侧绝对 Y 朝向（度，仅偏航有效） */
    public static final EntityDataAccessor<Rotations> DATA_YAW =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.ROTATIONS);

    /** 姿态（{@code Posture.molangName()}） */
    public static final EntityDataAccessor<String> DATA_POSTURE =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.STRING);

    /** 水平移动模式（{@code Gait.molangName()}） */
    public static final EntityDataAccessor<String> DATA_GAIT =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.STRING);

    /** 垂直模式（{@code Vertical.molangName()}） */
    public static final EntityDataAccessor<String> DATA_VERTICAL =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.STRING);

    /** 逻辑层能量值 */
    public static final EntityDataAccessor<Float> DATA_ENERGY =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.FLOAT);

    /** KCC 是否正在蓄力跳跃 */
    public static final EntityDataAccessor<Boolean> DATA_JUMP_CHARGING =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.BOOLEAN);

    /**
     * 全部 accessor 的标签与默认值，供构造器一次性定义。
     * <p>
     * 与上面的 accessor 声明一一对应，顺序即 id 顺序，同样只允许在末尾追加。
     */
    private static final Map<EntityDataAccessor<?>, Object> DEFAULT_VALUES = new HashMap<>();

    static {
        DEFAULT_VALUES.put(DATA_POS, new org.joml.Vector3f());
        DEFAULT_VALUES.put(DATA_VEL, new org.joml.Vector3f());
        DEFAULT_VALUES.put(DATA_YAW, new Rotations(0f, 0f, 0f));
        DEFAULT_VALUES.put(DATA_POSTURE, "stand");
        DEFAULT_VALUES.put(DATA_GAIT, "idle");
        DEFAULT_VALUES.put(DATA_VERTICAL, "ground");
        DEFAULT_VALUES.put(DATA_ENERGY, 0f);
        DEFAULT_VALUES.put(DATA_JUMP_CHARGING, false);
    }

    // ═══════════════════════════════════════════════
    // 身份与持有关系
    // ═══════════════════════════════════════════════

    /** 装配体网络身份，构造时注入，不可变 */
    private final UUID assemblyId;

    /** 所在世界，构造时注入 */
    private final Level level;

    /** 是否持有 KCC 与状态机（服务端为 true，客户端为 false） */
    private final boolean authoritative;

    /**
     * 运动控制编排器。
     * <p>
     * 服务端路径持有完整的 KCC 与状态机；客户端路径为 {@code null}
     * （客户端不运行 KCC 与状态机，见 D1）。读取入口是
     * {@link #getMechaControl()}，它同时满足 {@link MechaControlHolder} 的契约。
     */
    private final @Nullable MechaControl mechaControl;

    /** 同步数据容器（服务端为唯一写入方，客户端只读） */
    private final SynchedEntityData syncedData;

    /**
     * {@inheritDoc}
     * <p>
     * 服务端实例返回完整的控制器；客户端实例返回 {@code null}——客户端不运行 KCC 与状态机
     * （D1），因此没有控制器可返回。
     */
    @Override
    public @Nullable MechaControl getMechaControl() {
        return mechaControl;
    }

    /**
     * 逻辑层状态的不可变快照，物理线程每步发布、主线程读取。
     * <p>
     * 客户端路径恒为 {@code null}。
     */
    private volatile MechaControl.@Nullable LogicStateSnapshot logicState;

    /** 入世时记录的胶囊中心位置，兜底 warp 的目标点 */
    private volatile com.jme3.math.@Nullable Vector3f anchorPos;

    /** 兜底阈值：KCC 与锚点距离超过该值 (m) 时 warp 回锚点 */
    private static final float DRIFT_WARP_THRESHOLD = 8f;

    /** 是否已加入物理空间（避免重复 addCollisionObject） */
    private final AtomicBoolean inPhysicsSpace = new AtomicBoolean(false);

    // ==========================================
    // 构造
    // ==========================================

    /**
     * 服务端构造：创建 KCC 与状态机。
     * <p>
     * KCC 的胶囊几何取自 {@link MechaBodyPreset}，用于地面射线检测的物理空间直接取自
     * 所在 Level（见类注释的构造前置）。
     * <p>
     * KCC 只被构造、尚未加入物理空间：入世需要调用方另经
     * {@code SparkLevel.submitImmediateTask} 执行 {@link #enterPhysicsSpace(com.jme3.math.Vector3f)}，
     * 见 `docs/ArmsCore双端权威与网络同步实现计划.md` 阶段 1.5。
     *
     * @param level      所在世界；其物理空间必须已初始化（见类注释的构造前置）
     * @param assemblyId 装配体 UUID
     */
    public ArmsCore(Level level, UUID assemblyId) {
        this(level, assemblyId, true);
    }

    private ArmsCore(Level level, UUID assemblyId, boolean authoritative) {
        this.level = level;
        this.assemblyId = assemblyId;
        this.authoritative = authoritative;
        if (authoritative) {
            MechaCharacter kcc = new MechaCharacter(
                    MechaBodyPreset.newCapsuleShape(),
                    SparkLevel.getPhysicsLevel(level).getWorld());
            this.mechaControl = new MechaControl(this, kcc);
        } else {
            this.mechaControl = null;
        }
        this.syncedData = buildSyncedData(this);
    }

    /**
     * 客户端构造：不创建 KCC 与状态机，只承载同步数据。
     * <p>
     * 客户端不运行物理模拟（D1），因此这里不读物理空间，也就不受服务端那条
     * 「物理空间必须已初始化」的构造前置约束。
     *
     * @param level      客户端所在世界
     * @param assemblyId 装配体 UUID（与服务端一致）
     * @return 客户端侧实例
     */
    public static ArmsCore newClientInstance(Level level, UUID assemblyId) {
        return new ArmsCore(level, assemblyId, false);
    }

    /**
     * 按 {@link #DEFAULT_VALUES} 定义并构建同步数据容器。
     * <p>
     * {@code Builder} 以运行时类 {@code ArmsCore.class} 取容量（沿父类链起点为 0），
     * {@code build()} 要求 id 区间连续且无空槽，因此这里必须定义全部 accessor。
     */
    private static SynchedEntityData buildSyncedData(ArmsCore holder) {
        SynchedEntityData.Builder builder = new SynchedEntityData.Builder(holder);
        Defaults.applyTo(builder);
        return builder.build();
    }

    /**
     * 把 {@link #DEFAULT_VALUES} 里的每一项按 accessor 定义进 builder。
     * <p>
     * 单独抽成一个方法而不用 {@code DEFAULT_VALUES.forEach(builder::define)}：后者的
     * {@code BiConsumer} 无法承载 {@code EntityDataAccessor<?>} 与 {@code Object} 之间的
     * 通配符捕获，编译期推不出 {@code define} 的类型参数。
     */
    private static final class Defaults {
        private Defaults() {
        }

        @SuppressWarnings("unchecked")
        static void applyTo(SynchedEntityData.Builder builder) {
            for (Map.Entry<EntityDataAccessor<?>, Object> entry : DEFAULT_VALUES.entrySet()) {
                builder.define((EntityDataAccessor<Object>) entry.getKey(), entry.getValue());
            }
        }
    }

    // ==========================================
    // 同步
    // ==========================================

    /** 返回同步数据容器；服务端写入、客户端读取。 */
    public SynchedEntityData getSyncedData() {
        return syncedData;
    }

    /** 本实例是否持有 KCC 与状态机（服务端为 true，客户端为 false）。 */
    public boolean isAuthoritative() {
        return authoritative;
    }

    /**
     * 服务端处理同步数据变化。
     * <p>
     * 服务端一律不把同步数据应用回物理体：位姿的权威方向是「KCC → syncedData」单向，
     * 反向应用会与物理线程形成反馈回路（计划 §3.7、R3）。
     */
    @Override
    public void onSyncedDataUpdated(@NotNull EntityDataAccessor<?> key) {
        if (!level.isClientSide()) return;
    }

    /**
     * 客户端处理整批同步数据。
     * <p>
     * 可视锚点由渲染路径按需读取 {@link #DATA_POS} / {@link #DATA_YAW}，无需在此缓存，
     * 因此本方法不做处理；保留它是为了显式表达「客户端不把同步数据推回物理体」。
     */
    @Override
    public void onSyncedDataUpdated(@NotNull List<SynchedEntityData.DataValue<?>> dataValues) {
    }

    // ==========================================
    // 物理步驱动
    // ==========================================

    /**
     * 每物理步调用一次，由 Level 级注册表在 {@code PhysicsLevelTickEvent.Pre} 中扇出。
     * <p>
     * 执行顺序（硬约束）：
     * <ol>
     *   <li>Part 层动画混合 —— 必须早于 {@code extractAnimRootDelta()}，后者读取
     *       {@code body_root} 骨骼位姿，而该位姿由 Part 的物理步产出（装配体图接入后生效）</li>
     *   <li>状态机推进 → 动画根位移提取 → KCC 积分（顺序由 {@link MechaControl} 内部保证）</li>
     *   <li>发布逻辑状态快照供主线程读取</li>
     * </ol>
     *
     * @param dt 物理步长 (s)
     */
    public void prePhysicsTick(float dt) {
        MechaControl control = this.mechaControl;
        if (control == null) return;

        // ② 状态机推进 → extractAnimRootDelta → KCC 积分
        control.onPhysicsStep(dt);

        // ③ 发布逻辑状态到主线程（位姿由主线程直接读 KCC，见计划 §3.5）
        this.logicState = control.snapshotLogicState();
    }

    /**
     * 返回最近一次物理步发布的逻辑层状态快照。
     * <p>
     * 主线程安全：该引用指向不可变 record，物理线程只会替换引用、不会修改已发布对象。
     *
     * @return 最近一次快照；物理线程尚未步进过时为 {@code null}
     */
    public MechaControl.@Nullable LogicStateSnapshot getLogicState() {
        return logicState;
    }

    /**
     * 物理线程：把 KCC 放入物理空间并设置出生点。
     * <p>
     * 必须在 {@code SparkLevel.submitImmediateTask} 投递的任务内调用。
     *
     * @param capsuleCenter 胶囊中心的世界坐标（不是脚底坐标，见计划 §3.14）；
     *                      同时被记为兜底 warp 的锚点
     */
    public void enterPhysicsSpace(com.jme3.math.Vector3f capsuleCenter) {
        if (!authoritative) return;
        PhysicsLevel physicsLevel = SparkLevel.getPhysicsLevel(level);
        MechaCharacter kcc = getKcc();
        kcc.setPhysicsLocation(capsuleCenter);
        this.anchorPos = capsuleCenter.clone();
        if (inPhysicsSpace.compareAndSet(false, true)) {
            physicsLevel.getWorld().addCollisionObject(kcc);
        }
    }

    /**
     * 物理线程兜底：KCC 与入世锚点偏离超过 {@link #DRIFT_WARP_THRESHOLD} 时拉回。
     * <p>
     * 触发原因是约束、外力或异常传送把幽灵体推离了目标点；不处理会让同步通道把
     * 一个已经跑飞的位姿持续广播给客户端。
     */
    public void applyDriftFallback() {
        if (!authoritative) return;
        com.jme3.math.Vector3f anchor = this.anchorPos;
        if (anchor == null) return;
        MechaCharacter kcc = getKcc();
        com.jme3.math.Vector3f current = kcc.getPhysicsLocation(null);
        if (current.distance(anchor) > DRIFT_WARP_THRESHOLD) {
            ARMS.LOGGER.warn("[ARMS-Core] KCC 偏离锚点 {} m，warp 回 {}", current.distance(anchor), anchor);
            kcc.warp(anchor);
        }
    }

    /**
     * 返回本核心的 KCC。
     * <p>
     * KCC 由 {@link MechaControl} 持有（服务端构造时创建），此处只是转发，便于外部在
     * 创建路径中直接拿到它做入世与出生点设置。
     *
     * @return 本核心的运动学角色控制器
     * @throws IllegalStateException 在客户端实例上调用（客户端不持有 KCC，见 D1）
     */
    public MechaCharacter getKcc() {
        MechaControl control = this.mechaControl;
        if (control == null) {
            throw new IllegalStateException(
                    "客户端 ArmsCore 不持有 KCC（计划 D1：客户端不运行物理模拟）");
        }
        return control.getKcc();
    }

    // ==========================================
    // IPartAssembly
    // ==========================================

    @Override
    public UUID getAssemblyId() {
        return assemblyId;
    }

    @Override
    public Level getLevel() {
        return level;
    }

    @Override
    public boolean isInLevel() {
        return false;
    }

    @Override
    public String getAssemblyName() {
        return "";
    }

    @Override
    public void setAssemblyName(String name) {

    }

    @Override
    public float getTotalMass() {
        return 0;
    }

    @Override
    public void addPart(Part part) {

    }

    @Override
    public void removePart(Part part) {

    }

    @Override
    public void connect(AbstractConnector connector1, AbstractConnector connector2, @Nullable Part newPart) {

    }

    @Override
    public void disconnect(AbstractConnector connector) {

    }

    @Override
    public SubsystemController getSubsystemController() {
        return null;
    }

    @Override
    public void onPartDamage(Part part, float damage) {

    }

    @Override
    public void activatePhysics() {

    }

    @Override
    public SubPart getRootSubPart() {
        return null;
    }

    @Override
    public MechAttr getAttr() {
        return null;
    }

    @Override
    public String toString() {
        return "ArmsCore[" + assemblyId + (authoritative ? ", server" : ", client") + "]";
    }
}
