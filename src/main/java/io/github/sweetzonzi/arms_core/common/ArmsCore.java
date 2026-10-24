package io.github.sweetzonzi.arms_core.common;

import cn.solarmoon.spark_core.api.SparkLevel;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.common.control.MechaControlHolder;
import io.github.sweetzonzi.arms_core.common.control.attr.MechAttr;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.part.ArmsBodyData;
import io.github.sweetzonzi.arms_core.common.part.BodyPart;
import io.github.sweetzonzi.arms_core.common.part.BodyPartData;
import io.github.sweetzonzi.arms_core.common.part.BodyPartType;
import io.github.sweetzonzi.machine_max.common.mech.part.PartFactory;
import io.github.sweetzonzi.machine_max.common.mech.part.PartRestoreMode;
import io.github.sweetzonzi.arms_core.network.payload.ArmsCoreCreatePayload;
import io.github.sweetzonzi.machine_max.common.mech.subsystem.SubsystemController;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.IPartAssembly;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPart;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPartType;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.connector.AbstractConnector;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.Rotations;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 机娘核心类 —— 装配体级逻辑机甲单元。
 * <p>
 * 与 Machine-Max 的 {@code VehicleCore} 同级：由 {@link AbstractPart} 通过 {@link AbstractConnector}
 * 组装而成，实现 {@link IPartAssembly}，并额外持有角色运动控制器 {@link MechaControl}
 * 与逻辑状态机。与载具不同，它不注册为世界实体，生命周期跟随宿主装配体
 * （见 `docs/总体设计文档.md` §2.1）。
 * <p>
 * <b>权威归属</b>：服务端是唯一权威端，客户端不运行角色控制器、不推进逻辑状态树
 * （`docs/ArmsCore双端权威与网络同步实现计划.md` D1、§1.3）。两条构造路径都持有
 * {@link MechaControl}——表现层编排住在它里面，客户端也要有——差别只在身体与逻辑树：
 * <ul>
 *   <li><b>服务端</b>（{@link #ArmsCore(Level, UUID, BodyPart)}）：构造控制器刚体与逻辑状态树，由 Level 级
 *       注册表在物理线程驱动；</li>
 *   <li><b>客户端</b>（{@link #newClientInstance(Level, UUID, ArmsBodyData)}）：构造一个没有控制器刚体、没有逻辑树的
 *       {@link MechaControl}，并作为 {@link SynchedEntityData} 的客户端副本载体与可视锚点的宿主。
 *       逻辑四项不在这里推导，由下行通道写入。</li>
 * </ul>
 * <p>
 * <b>构造前置（仅服务端路径）</b>：构造器会直接从所在 Level 取物理空间
 * （{@code SparkLevel.getPhysicsLevel(level).getWorld()}）并把控制器刚体建在该空间上。该前置在正常流程下由
 * Spark-Core 的初始化顺序保证——
 * `../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/level/PhysicsLevelApplier.kt#load`
 * 在 {@code LevelEvent.Load}
 * 中设置 `PhysicsLevel` 并 `start()`，而 {@code ArmsCore} 总是由更晚的创建路径（宿主登录、
 * 装配体创建包、调试命令）构造。
 * <p>
 * <b>线程模型</b>：构造与同步写入（{@link SynchedEntityData#set}）在主线程；
 * {@link #prePhysicsTick(float)} 只允许在物理线程调用，控制器刚体入世
 * （`setPhysicsLocation` + `addCollisionObject`）须经
 * `SparkLevel.submitImmediateTask` 投递。服务端控制器刚体的位姿与速度由主线程在
 * {@link #syncToClients()} 中直接读取（良性竞态，与 `DestroyableRigidObject.postTick()`
 * 同等接受）；逻辑层四项则走 {@link MechaControl.LogicStateSnapshot} 不可变发布。
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

    /** 控制器刚体的物理位置（世界坐标，控制器碰撞体中心） */
    public static final EntityDataAccessor<org.joml.Vector3f> DATA_POS =
            SynchedEntityData.defineId(ArmsCore.class, EntityDataSerializers.VECTOR3);

    /** 控制器刚体的线速度（三个分量同为 m/s，见 `docs/角色控制器-刚体动力学方案.md` §6） */
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
    }

    // ═══════════════════════════════════════════════
    // 身份与持有关系
    // ═══════════════════════════════════════════════

    /** 装配体网络身份，构造时注入，不可变 */
    @Getter
    private final UUID assemblyId;

    /** 所在世界，构造时注入 */
    @Getter
    private final Level level;

    /** 是否持有控制器刚体与状态机（服务端为 true，客户端为 false） */
    @Getter
    private final boolean authoritative;

    /**
     * 运动控制编排器，两端都非 {@code null}。
     * <p>
     * 两端持有的是不同形态：服务端有控制器刚体与逻辑状态树，客户端两者都没有、只有表现层
     * （动画层的建立与推进住在编排器里，因此客户端也必须有它）。判据是
     * {@link MechaControl#hasBody()} 而不是本引用是否为空。读取入口是
     * {@link MechaControlHolder#getMechaControl()}，本字段生成的 getter 满足该契约。
     */
    @Getter
    private final @Nullable MechaControl mechaControl;

    /** 同步数据容器（服务端为唯一写入方，客户端只读） */
    @Getter
    private final SynchedEntityData syncedData;

    /**
     * 逻辑层状态的不可变快照，物理线程每步发布、主线程读取。
     * <p>
     * 主线程安全：该引用指向不可变 record，物理线程只会替换引用、不会修改已发布对象。
     * 客户端路径恒为 {@code null}——客户端不从容器反推状态，发布它没有消费者
     * （见 {@link #prePhysicsTick(float)}）；物理线程尚未步进过时同样为 {@code null}。
     */
    @Getter
    private volatile MechaControl.@Nullable LogicStateSnapshot logicState;

    /**
     * 承载本装配体的宿主实体；{@code null} 表示尚未绑定宿主。
     * <p>
     * <b>可空是常态，{@code null} 不是错误状态。</b> 装配体在「已注册、尚未绑定宿主」这段区间里
     * 合法存在；客户端在创建包指定本地玩家时绑定宿主。三条使用路径的空值行为：位姿回写只跳过
     * {@code applyPose} / {@code applyVelocity} 两步而写包照常、伤害解析返回 {@code null}
     * （等价于「线段与宿主包围盒无交点」这一出口）、环境字段与控制权校验的调用方本身持有判据
     * 因此拿不到 {@code null}。逐条见 `docs/宿主接入与伤害管线设计.md` §3.1.2。
     * <p>
     * 反向兜底——引用为 {@code null} 时去查「哪个玩家在控制这个装配体」——是禁止的：那会让
     * 「装配体属于谁」有两个来源。
     * <p>
     * 只在主线程读写。宿主实体的全部已知用途（位姿回写、伤害解析的包围盒、环境字段来源）都在
     * 主线程相位，物理线程不读它。
     * <p>
     * Lombok 生成的宿主访问器对应本字段；写入只由
     * {@link IArmsHost#setControlledArmsCore} 调用。
     */
    @Getter
    @Setter
    private volatile @Nullable IArmsHost host;

    /** 是否已加入物理空间（避免重复 addCollisionObject） */
    private final AtomicBoolean inPhysicsSpace = new AtomicBoolean(false);

    /**
     * 宿主位置写入的来源分类与位移摄入（作用域栈 + 锚点 + pin）。
     * <p>
     * 由 {@code mixin/EntityPositionWriteMixin} 的判定点写入、{@link #applyExternalDisplacement} 采纳，
     * 并在 {@code common/ArmsCoreServerEvents.java#syncToClients} 的位姿采样处消费 pin。两端各持有一份：
     * 客户端实例也持有它，因为客户端玩家实体走的是同一条 {@code Entity#setPos} 注入链。
     * <p>
     * 判据与状态机见 `docs/宿主位置权威与位移摄入设计.md` §5、§6。
     */
    @Getter
    private final HostPositionIntake positionIntake;

    /** 可变成员容器；结构修改统一由主线程的装配方法执行。 */
    private final Map<UUID, AbstractPart<?>> mutableParts = new ConcurrentHashMap<>();
    /** 生命周期内身份固定的只读视图，内容随成员增减而变化。 */
    @Getter
    private final Map<UUID, AbstractPart<?>> partMap = Collections.unmodifiableMap(mutableParts);
    /** 核心在构造时确定，换核心需要创建另一具装配体。 */
    @Getter
    private final @NotNull BodyPart corePart;
    @Getter
    private final SubsystemController subsystemController;
    @Getter
    private String assemblyName = "ArmsCore";
    private boolean bodyDirty;
    private boolean disposed;

    // ==========================================
    // 构造
    // ==========================================

    /**
     * 服务端构造：创建控制器刚体与状态机。
     * <p>
     * 控制器几何取自 {@link MechaBodyPreset}，物理空间直接取自所在 Level（见类注释的构造前置），
     * 着地扫掠与越障探针都从这个空间发起。
     * <p>
     * 刚体只被构造、<b>尚未加入物理空间</b>：入世需要调用方另经
     * {@code SparkLevel.submitImmediateTask} 执行 {@link #enterPhysicsSpace(com.jme3.math.Vector3f)}，
     * 见 `docs/ArmsCore双端权威与网络同步实现计划.md` 阶段 1.5。入世之前
     * {@code getCollisionSpace()} 返回 {@code null}，控制器只依赖构造时注入的那一份空间引用。
     *
     * @param level      所在世界；其物理空间必须已初始化（见类注释的构造前置）
     * @param assemblyId 装配体 UUID
     * @param corePart   尚未归属装配体、尚未入世的核心部件
     */
    public ArmsCore(Level level, UUID assemblyId, BodyPart corePart) {
        this(level, assemblyId, new RestoredBody(corePart, List.of(corePart), "ArmsCore"), true);
    }

    /** 从完整组成数据构造服务端装配体，核心由保存数据解析。 */
    public ArmsCore(Level level, UUID assemblyId, ArmsBodyData data) {
        this(level, assemblyId, restoreMembers(level, data), true);
    }

    private ArmsCore(Level level, UUID assemblyId, RestoredBody bodyData, boolean authoritative) {
        this.level = level;
        this.assemblyId = assemblyId;
        this.authoritative = authoritative;
        this.corePart = java.util.Objects.requireNonNull(bodyData.corePart());
        this.subsystemController = new SubsystemController(level);
        // 先校验核心和成员的归属，再建立控制器，防止无效输入产生半成品装配体。
        bodyData.parts().forEach(this::validateMember);
        if (authoritative) {
            MechaCharacter body = new MechaCharacter(
                    MechaBodyPreset.newBodyShape(),
                    SparkLevel.getPhysicsLevel(level).getWorld());
            this.mechaControl = new MechaControl(this, body);
        } else {
            // 客户端也建编排器，但不给身体：表现层编排（常驻并行动画层、姿态状态机）住在它里面，
            // 客户端必须有一个实例才能推进动画。客户端的那一份不推进逻辑树、不碰刚体，
            // 四项由下行通道写入（见 common/control/MechaControl.java 的类注释）。
            this.mechaControl = new MechaControl(this, null);
        }
        this.syncedData = buildSyncedData(this);
        // 放在最后：HostPositionIntake 的判据会读 level 与 authoritative，两者此时都已就位。
        // 不以字段初始化器写在声明处，那样会在 level 赋值之前构造它
        this.positionIntake = new HostPositionIntake(this);
        bodyData.parts().forEach(this::addPart);
        this.assemblyName = bodyData.name();
    }

    /**
     * 客户端构造：不创建控制器刚体与逻辑状态树，只承载同步数据。
     * <p>
     * 客户端不运行物理模拟（D1），因此这里不读物理空间，也就不受服务端那条
     * 「物理空间必须已初始化」的构造前置约束。表现层编排器同时建立（见 {@link #mechaControl}），
     * 但没有身体、没有逻辑树。
     *
     * @param level      客户端所在世界
     * @param assemblyId 装配体 UUID（与服务端一致）
     * @param data       完整身体组成，构造前会校验并恢复成员
     * @return 客户端侧实例
     */
    public static ArmsCore newClientInstance(Level level, UUID assemblyId, ArmsBodyData data) {
        return new ArmsCore(level, assemblyId, restoreMembers(level, data), false);
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

    /**
     * 服务端处理同步数据变化。
     * <p>
     * 服务端一律不把同步数据应用回物理体：位姿的权威方向是「控制器刚体 → syncedData」单向，
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
     * 单个物理步的时长 (s)。
     * <p>
     * 取自已初始化的物理空间，两端各自成立：服务端 {@code ServerPhysicsLevel(level, 5, ...)} 给出
     * {@code tps = baseStep × 20 = 100}，即 {@code 0.01 s}；客户端 {@code ClientPhysicsLevel(level, 3, ...)}
     * 给出 {@code tps = 60}，即约 {@code 0.0167 s}。
     * <p>
     * 刚体的 {@code getLinearVelocity} 三轴统一为 m/s，而宿主实体上
     * {@code deltaMovement} 的三个分量统一是「每 tick 位移」，换算就是 {@code ÷ 20}，与物理步长无关
     * （`docs/角色控制器-刚体动力学方案.md` §6）。本值用于按时长累加的量——物理步长本身、
     * 按秒计时的参数（跳跃助推窗口、闪避无敌）以及诊断。
     *
     * @return 物理步长 (s)
     * @throws IllegalStateException 所在 Level 的物理空间尚未初始化（见类注释的构造前置）
     */
    public float physicsStepSeconds() {
        return 1f / SparkLevel.getPhysicsLevel(level).getTps();
    }

    /**
     * 每物理步调用一次，由 Level 级注册表在 {@code PhysicsLevelTickEvent.Pre} 中扇出。
     * <p>
     * 执行顺序（硬约束）：
     * <ol>
     *   <li>AbstractPart<?> 层动画混合 —— 必须早于 {@code extractAnimRootDelta()}，后者读取
     *       {@code body_root} 骨骼位姿，而该位姿由 AbstractPart<?> 的物理步产出（装配体图接入后生效）</li>
     *   <li>下行状态写入 → 状态机推进 → 表现层推进（状态机与动画时间）→ 动画根位移提取 →
     *       控制器物理步（顺序由 {@link MechaControl#onPhysicsStep(float)} 内部保证）</li>
     *   <li>发布逻辑状态快照供主线程读取（仅权威侧，见下）</li>
     * </ol>
     *
     * @param dt 物理步长 (s)
     */
    public void prePhysicsTick(float dt) {
        MechaControl control = this.mechaControl;
        if (control == null) return;

        // ② 下行状态写入 → 帧逻辑（仅权威侧） → 表现层推进 → extractAnimRootDelta → 控制器物理步
        control.onPhysicsStep(dt);

        // ③ 发布逻辑状态到主线程（位姿由主线程直接读控制器刚体，见计划 §3.5）。
        // 仅权威侧：客户端不从容器反推状态——它的四项是下行通道送来的，发布它们没有消费者，
        // 而同一条容器又会被下一步的下行写入覆盖，等于给同一个量造出两个来源。
        if (!authoritative) return;
        // 四项与上一步全同时沿用旧对象、不新建：读取端（common/ArmsCoreServerEvents.java#writeSyncedData）
        // 每 tick 取一次组件值、不比较引用，因此沿用旧对象对它不可见；而逻辑层每个物理子步推进一次、
        // 主线程每 tick 才取一次读，多数子步的四项与上一步相同。
        MechaControl.LogicStateSnapshot previous = this.logicState;
        this.logicState = previous == null
                ? control.snapshotLogicState()
                : control.snapshotLogicStateIfChanged(previous);
    }

    /**
     * 物理线程：把控制器刚体放入物理空间并设置出生点。
     * <p>
     * 必须在 {@code SparkLevel.submitImmediateTask} 投递的任务内调用。
     * <p>
     * 刚体的位置就是宿主实体的位置（{@code common/ArmsCoreServerEvents.java#applyPoseToHost} 每 tick
     * 把它写进宿主），因此本方法只负责「入世」这一次设置，<b>不记录任何兜底锚点</b>：以出生点为锚点、
     * 偏移超限就把控制器 warp 回去，会把一个正在正常行走的机体拉回出生点，与「刚体位置即玩家位置」直接
     * 冲突。
     * <p>
     * 位置先写、后入世：{@code MechaCharacter} 的构造不读物理空间，重力与扫掠都在入世之后才解析得到，
     * 因此这两步的先后不影响力模型。
     *
     * @param controllerCenter 控制器碰撞体中心的世界坐标（不是脚底坐标，见计划 §3.14）
     */
    public void enterPhysicsSpace(com.jme3.math.Vector3f controllerCenter) {
        if (!authoritative) return;
        PhysicsLevel physicsLevel = SparkLevel.getPhysicsLevel(level);
        MechaCharacter body = getBody();
        body.setPhysicsLocation(controllerCenter);
        if (inPhysicsSpace.compareAndSet(false, true)) {
            physicsLevel.getWorld().addCollisionObject(body);
        }
    }

    // ==========================================
    // 宿主绑定
    // ==========================================

    /**
     * 采纳一次外部位移：改写 {@code DATA_POS} 的来源并提交任务改写控制器刚体的物理位置。
     * <p>
     * 由 {@code mixin/EntityPositionWriteMixin} 在 {@code Entity#setPos(double,double,double)} 的 {@code HEAD}
     * 处、且作用域栈为空时调用。分类判据（栈空不空、目标是否等于锚点）在
     * {@link HostPositionIntake#isInScope()} 与 {@link HostPositionIntake#isAtAnchor(double, double, double)}，
     * 采纳与投递在 {@link HostPositionIntake#intake}，本方法只负责把这次写入的目标转交过去。
     * <p>
     * 目标由调用方传入而不是在这里从实体读：{@code HEAD} 注入点的时点早于原版方法体，此刻
     * {@code Entity#getX()} 一族返回的仍是<b>上一次</b>写入的值，只有入参是这一次的目标。
     * <p>
     * 宿主由本类自己的 {@link #host} 承担，不入参：发起这次写入的实体与该引用由绑定关系维持一致
     * （{@code IArmsHost#setControlledArmsCore} 同时写两个方向），调用方已经用它取到了本实例。
     * <p>
     * 摄入成立时发生三件事：
     * <ol>
     *   <li><b>pin</b> —— 在 warp 真正落地之前，{@code ArmsCoreServerEvents#syncToClients} 用目标填
     *       {@code DATA_POS}，客户端因此不会被旧的刚体位置拉回旧处；</li>
     *   <li><b>warp</b> —— 经 {@code SparkLevel#submitImmediateTask} 投递
     *       {@link MechaCharacter#warp}，位置落到目标（保留水平动量、清垂直分量）；</li>
     *   <li><b>待投递落点</b> —— 落点先写进 {@code HostPositionIntake} 的字段，同一 tick 内多次摄入按最后
     *       写入者生效。</li>
     * </ol>
     * 客户端实例同样走这条路径：它没有控制器刚体，投递的任务是空操作，但 pin 照常武装，客户端手里的
     * {@code DATA_POS} 因此与实体位置一致。
     *
     * @param x 这次写入的目标 X（包围盒底面基准）
     * @param y 这次写入的目标 Y
     * @param z 这次写入的目标 Z
     * @return 已采纳时返回判定结果（目标 + 判定路径）；未采纳时为 {@code null}
     */
    public @Nullable HostPositionIntake.Decision applyExternalDisplacement(double x, double y, double z) {
        return positionIntake.intake(x, y, z);
    }

    /**
     * 宿主实体的网络 id，供创建包携带。
     * <p>
     * 未绑定宿主时返回 {@link ArmsCoreCreatePayload#NO_HOST_ENTITY} —— 客户端的绑定判据是
     * 「{@code hostEntityId} 等于本地玩家实体 id」，用 {@code 0} 兜底会让世界里的第 0 号实体
     * 错误地收到绑定。
     */
    public int getBoundHostEntityId() {
        IArmsHost current = this.host;
        return current == null
                ? ArmsCoreCreatePayload.NO_HOST_ENTITY
                : current.getHostEntity().getId();
    }

    /**
     * 建立 / 解除绑定：宿主实体一侧的便捷入口。
     * <p>
     * 等价的直接写法是
     * {@code ((IArmsHost) host).setControlledArmsCore(core)}——绑定关系的唯一入口就是它，
     * 本方法只负责把参数从 {@link Entity} 转成宿主身份，便于 {@code Entity} 类型的调用点使用。
     * 两个方向的一致性、单宿主唯一性、以及绑定变化时的输入重置都在
     * {@link IArmsHost#setControlledArmsCore} 内完成。
     *
     * @param host 宿主实体；{@code null} 表示不做任何事
     * @param core 该宿主现在承载的装配体；{@code null} 表示解绑
     */
    public static void bindHostOf(@Nullable Entity host, @Nullable ArmsCore core) {
        if (host instanceof IArmsHost armsHost) {
            armsHost.setControlledArmsCore(core);
        }
    }

    /**
     * 返回本核心的控制器刚体。
     * <p>
     * 它由 {@link MechaControl} 持有（服务端构造时创建），此处只是转发，便于外部在
     * 创建路径中直接拿到它做入世与出生点设置。
     *
     * @return 本核心的动力学角色控制器（胶囊刚体）
     * @throws IllegalStateException 在客户端实例上调用（客户端不持有控制器，见 D1）
     */
    public MechaCharacter getBody() {
        MechaControl control = this.mechaControl;
        if (control == null || !control.hasBody()) {
            throw new IllegalStateException(
                    "客户端 ArmsCore 不持有控制器（计划 D1：客户端不运行物理模拟）");
        }
        return control.getBody();
    }

    // ==========================================
    // IPartAssembly
    // ==========================================

    @Override
    public boolean isInLevel() {
        return inPhysicsSpace.get();
    }

    /** 修改名称同时标记组成快照待发送，因此保留带业务逻辑的 setter。 */
    @Override
    public void setAssemblyName(String name) {
        assemblyName = java.util.Objects.requireNonNull(name);
        bodyDirty = true;
    }

    @Override
    public float getTotalMass() {
        return (float) partMap.values().stream().mapToDouble(AbstractPart::getTotalMass).sum();
    }

    @Override
    public boolean acceptsPartType(AbstractPartType type) {
        return type instanceof BodyPartType;
    }

    /** 添加普通身体成员；核心身份由构造器确定。 */
    @Override
    public void addPart(AbstractPart<?> part) {
        validateMember(part);
        // UUID 相同但实例不同属于冲突，禁止覆盖现有成员或核心。
        var current = mutableParts.putIfAbsent(part.uuid, part);
        if (current == part) return;
        if (current != null) throw new IllegalArgumentException("部件 UUID 重复：" + part.uuid);
        part.assembly = this;
        bodyDirty = true;
    }

    /** 检查主线程装配入口的共同前置条件。 */
    private void validateMember(AbstractPart<?> part) {
        if (disposed) throw new IllegalStateException("装配体已释放");
        if (!(part instanceof BodyPart) || part.level != level || part.destroyed
                || part.assembly != null && part.assembly != this) {
            throw new IllegalArgumentException("部件必须是同世界中尚未归属其他装配体的有效 BodyPart");
        }
        if (part.subParts.isEmpty() || part.subParts.values().stream().anyMatch(child -> child.body.isInWorld())) {
            throw new IllegalArgumentException("身体部件必须具有尚未入世的子结构");
        }
    }

    /** 移除非核心成员并释放其结构；核心随整个装配体一起释放。 */
    @Override
    public void removePart(AbstractPart<?> part) {
        if (part == corePart) throw new IllegalArgumentException("核心不能单独移除，请释放整个装配体");
        if (!mutableParts.remove(part.uuid, part)) return;
        part.assembly = null;
        part.destroy();
        bodyDirty = true;
    }

    @Override
    public void connect(AbstractConnector connector1, AbstractConnector connector2, @Nullable AbstractPart<?> newPart) {
        throw new UnsupportedOperationException("身体连接尚未实现");
    }

    @Override
    public void disconnect(AbstractConnector connector) {
        throw new UnsupportedOperationException("身体连接尚未实现");
    }

    @Override
    public void onPartDamage(AbstractPart<?> part, float damage) {
        // 占位身体不参与世界命中，宿主伤害转发由身体伤害接入阶段定义。
    }

    @Override
    public void activatePhysics() {
        if (authoritative) getBody().activate();
    }

    @Override
    public SubPart getRootSubPart() {
        return corePart.rootSubPart;
    }

    @Override
    public MechAttr getAttr() {
        return null;
    }

    @Override
    public AbstractPart<?> findPart(UUID id) {
        return partMap.get(id);
    }

    /** 保存身体组成；核心通过 UUID 引用集合中的唯一实例。 */
    public ArmsBodyData saveBody() {
        if (disposed) throw new IllegalStateException("已释放的装配体不能保存");
        return new ArmsBodyData(assemblyName, corePart.uuid,
                partMap.values().stream().map(part -> ((BodyPart) part).save()).toList());
    }

    /** 生成用于持久化或创建包的组成 NBT。 */
    public CompoundTag saveBodyTag() {
        return (CompoundTag) ArmsBodyData.CODEC.encodeStart(NbtOps.INSTANCE, saveBody()).getOrThrow();
    }

    /** 构造前恢复所需的完整成员集合，失败时清理全部已分配部件。 */
    private static RestoredBody restoreMembers(Level level, ArmsBodyData data) {
        data.validate().getOrThrow();
        // 在创建任何刚体之前验证整批定义和结构，防止无效输入破坏现有装配体。
        for (BodyPartData saved : data.parts()) {
            if (!(PartFactory.validateRestore(saved, level.isClientSide()) instanceof BodyPartType type)
                    || !type.getVariant(saved.variant).subParts.keySet().equals(saved.subParts.keySet())) {
                throw new IllegalArgumentException("身体定义无效或保存结构不完整");
            }
        }
        var allocated = new ArrayList<BodyPart>();
        try {
            for (BodyPartData saved : data.parts()) {
                allocated.add((BodyPart) PartFactory.restore(saved, level, PartRestoreMode.FULL_STATE, BodyPart.CONTEXT));
            }
            BodyPart core = allocated.stream().filter(part -> part.uuid.equals(data.corePartId())).findFirst().orElseThrow();
            return new RestoredBody(core, List.copyOf(allocated), data.name());
        } catch (RuntimeException | Error failure) {
            allocated.forEach(BodyPart::destroy);
            throw failure;
        }
    }

    /** 应用同一具机体的组成快照，保持核心实例及成员容器的身份稳定。 */
    public void applyBodySnapshot(ArmsBodyData data) {
        if (disposed) throw new IllegalStateException("装配体已释放");
        if (!corePart.uuid.equals(data.corePartId())) {
            throw new IllegalArgumentException("组成快照不能更换核心");
        }
        RestoredBody prepared = restoreMembers(level, data);
        Map<UUID, AbstractPart<?>> next = new LinkedHashMap<>();
        try {
            // 先用临时实例验证全部恢复状态；核心只更新状态，不替换对象引用。
            corePart.applySavedState(prepared.corePart().save());
            prepared.parts().forEach(part -> next.put(part.uuid, part == prepared.corePart() ? corePart : part));
        } catch (RuntimeException | Error failure) {
            prepared.parts().forEach(BodyPart::destroy);
            throw failure;
        }
        prepared.corePart().destroy();
        var previous = new ArrayList<>(partMap.values());
        next.values().forEach(part -> part.assembly = this);
        // 修改固定容器的内容；外部持有的只读视图始终对应这一份容器。
        mutableParts.clear();
        mutableParts.putAll(next);
        assemblyName = data.name();
        previous.stream().filter(part -> part != corePart).forEach(part -> {
            part.assembly = null;
            part.destroy();
        });
        bodyDirty = true;
    }

    /** 主线程推进身体成员，并发送成员变化产生的完整组成快照。 */
    public void tickBody() {
        if (disposed) return;
        partMap.values().forEach(AbstractPart::onTick);
        if (authoritative && bodyDirty && MechaCoreRegistry.get(level, assemblyId) == this) {
            MechaCoreRegistry.syncBody(this);
            bodyDirty = false;
        }
    }

    /** 释放全部成员；final 字段保持其引用，已释放对象禁止继续装配。 */
    public void disposeBody() {
        if (disposed) return;
        disposed = true;
        var previous = new ArrayList<>(partMap.values());
        mutableParts.clear();
        previous.forEach(part -> {
            part.assembly = null;
            part.destroy();
        });
        bodyDirty = false;
    }

    /** 私有构造参数，保证进入构造器时核心与完整成员集合均已就绪。 */
    private record RestoredBody(BodyPart corePart, List<BodyPart> parts, String name) {}

    @Override
    public String toString() {
        return "ArmsCore[" + assemblyId + (authoritative ? ", server" : ", client") + "]";
    }
}
