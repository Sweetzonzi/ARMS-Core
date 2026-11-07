package io.github.sweetzonzi.arms_core.common;

import cn.solarmoon.spark_core.api.SparkLevel;
import cn.solarmoon.spark_core.physics.body.PhysicsBodyEvent;
import cn.solarmoon.spark_core.physics.body.PhysicsBodyExtensionKt;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import cn.solarmoon.spark_core.util.PPhase;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.common.control.MechaControlHolder;
import io.github.sweetzonzi.arms_core.common.control.attr.MechAttr;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.presentation.MechaBodyAnimation;
import io.github.sweetzonzi.arms_core.common.debug.MechaTelemetry;
import io.github.sweetzonzi.arms_core.common.part.ArmsBodyData;
import io.github.sweetzonzi.arms_core.common.part.ArmsBodyAssembly;
import io.github.sweetzonzi.arms_core.common.part.ArmsBodyPhysics;
import io.github.sweetzonzi.arms_core.common.part.ArmsFrame;
import io.github.sweetzonzi.arms_core.common.part.ArmsFrameModule;
import io.github.sweetzonzi.arms_core.common.part.BodyPart;
import io.github.sweetzonzi.arms_core.common.part.BodyPartType;
import io.github.sweetzonzi.arms_core.network.payload.ArmsCoreCreatePayload;
import io.github.sweetzonzi.machine_max.common.mech.ObjectManager;
import io.github.sweetzonzi.machine_max.common.mech.PoseDelayBuffer;
import io.github.sweetzonzi.machine_max.common.mech.subsystem.SubsystemController;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.IPartAssembly;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPart;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPartType;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.RegularPartType;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.collision.CollisionPostSettlement;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
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

    public static final ResourceLocation DEFAULT_FRAME_ID =
            ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "allbody_joint_test");

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
     * 客户端呈现位姿的抖动缓冲；服务端为 {@code null}。
     * <p>
     * 服务端按 20 Hz 发 {@link #DATA_POS} / {@link #DATA_YAW}，客户端也按 20 Hz 走 tick，
     * 两者相位不同步：某些 tick 里一个包都没有、另一些 tick 里挤进两个。呈现位姿若直接取
     * {@code DATA_POS}，就会出现「冻结一帧 + 下一帧补两倍」的拍频，表现是角色与相机一起顿。
     * 本缓冲让呈现位姿按<b>客户端 tick</b>前进、与到达事件无关；规则与三档余量的取舍见
     * {@link PoseDelayBuffer}。
     * <p>
     * 消费者只有两个：客户端摆放本地玩家实体的
     * {@code client/ClientHostPoseEvents.java#applySyncedPose}，以及取右端计算 LOD 距离的
     * {@link MechaBodyAnimation#updateClientInputs}。
     */
    private final @Nullable PoseDelayBuffer presentationPose;

    /** 稳态余量（样本数）；{@code 1} 即呈现落后最新样本 1～2 tick。与 {@code DestroyableObject#PRESENTATION_SPARE} 取同一个值 */
    public static final int PRESENTATION_SPARE = 1;

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
     * 本装配体拥有的物理体：控制器刚体（按稳定名字索引）。
     * <p>
     * 只登记控制器；装配体内的 SubPart 各自是 {@code PhysicsHost}，由它们自己登记。
     * 物理线程写（入世 / 离世），主线程读（调试与查询），因此用并发容器。
     */
    private final Map<String, PhysicsCollisionObject> physicsBodies = new ConcurrentHashMap<>();

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
    /** 身体组成引用的素体 ID；直接构造的身体可以没有素体。 */
    private final Optional<ResourceLocation> frameId;
    @Getter
    private final @Nullable MechaBodyAnimation bodyAnimation;
    /** 服务端创建或恢复时解析的定义，用于约束目标。 */
    private final @Nullable ArmsFrame frame;
    /** 身体的实际外部连接；客户端只持有端点和参考系。 */
    @Getter
    private List<ArmsBodyAssembly.Link> bodyLinks;
    @Getter
    private final SubsystemController subsystemController;
    @Getter
    private String assemblyName = "ArmsCore";
    private boolean bodyDirty;
    private volatile boolean isRemoved;
    /** 只在物理线程读写，维度关闭后由主线程清空。 */
    @Getter
    @Nullable
    private ArmsBodyPhysics bodyPhysics;
    private final CompletableFuture<Void> bodyReleased = new CompletableFuture<>();

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
        this(level, assemblyId, true, Optional.empty(), null,
                new ArmsBodyAssembly(corePart, List.of(corePart), List.of(), "ArmsCore"));
    }

    /** 从素体构造服务端机体，核心根使用指定世界位姿；成员和关节均未入世。 */
    public static ArmsCore fromFrame(Level level, UUID assemblyId, ResourceLocation frameId, Transform rootPose) {
        if (level.isClientSide()) throw new IllegalArgumentException("素体只能由服务端新建");
        ArmsFrame frame = requireFrame(frameId);
        var prepared = ArmsBodyAssembly.create(level, frame, rootPose);
        try {
            return new ArmsCore(level, assemblyId, true, Optional.of(frameId), frame, prepared);
        } catch (RuntimeException | Error failure) {
            prepared.parts().forEach(AbstractPart::discardUnattached);
            throw failure;
        }
    }

    /** 先解析素体定义，再按存档中的实际成员和连接恢复身体。 */
    public ArmsCore(Level level, UUID assemblyId, ArmsBodyData data) {
        this(level, assemblyId, true, data.frameId(), data.frameId().map(ArmsCore::requireFrame).orElse(null),
                ArmsBodyAssembly.restore(level, data, true));
    }

    private static ArmsFrame requireFrame(ResourceLocation id) {
        ArmsFrame frame = ArmsFrameModule.INSTANCE.get(false, id);
        if (frame == null) throw new IllegalArgumentException("找不到已发布的素体 " + id);
        return frame;
    }

    private ArmsCore(Level level, UUID assemblyId, boolean authoritative, Optional<ResourceLocation> frameId,
                     @Nullable ArmsFrame frame, ArmsBodyAssembly bodyData) {
        this.level = level;
        this.assemblyId = assemblyId;
        this.authoritative = authoritative;
        this.corePart = java.util.Objects.requireNonNull(bodyData.core());
        this.frameId = frameId;
        this.frame = frame;
        this.bodyLinks = bodyData.links();
        this.subsystemController = new SubsystemController(level);
        // 先校验核心和成员的归属，再建立控制器，防止无效输入产生半成品装配体。
        bodyData.parts().forEach(this::validateMember);
        if (authoritative) {
            MechaCharacter body = new MechaCharacter(
                    MechaBodyPreset.newBodyShape(),
                    SparkLevel.getPhysicsLevel(level).getWorld());
            attachRideContactHandlers(body);
            this.mechaControl = new MechaControl(this, body);
        } else {
            // 客户端也建编排器，但不给身体：表现层编排（常驻并行动画层、姿态状态机）住在它里面，
            // 客户端必须有一个实例才能推进动画。客户端的那一份不推进逻辑树、不碰刚体，
            // 四项由下行通道写入（见 common/control/MechaControl.java 的类注释）。
            this.mechaControl = new MechaControl(this, null);
        }
        this.bodyAnimation = DEFAULT_FRAME_ID.equals(frameId.orElse(null)) ? new MechaBodyAnimation(this) : null;
        if (bodyAnimation != null) mechaControl.getPresentation().attach(List.of(bodyAnimation));
        this.syncedData = buildSyncedData(this);
        // 呈现缓冲只在客户端分配：服务端没有呈现路径，{@code DATA_POS} 在那边就是权威值本身
        this.presentationPose = level.isClientSide()
                ? new PoseDelayBuffer(PRESENTATION_SPARE, PoseDelayBuffer.DEFAULT_SNAP_DISTANCE)
                : null;
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
        return new ArmsCore(level, assemblyId, false, data.frameId(), null,
                ArmsBodyAssembly.restore(level, data, false));
    }

    /** 客户端注册服务端分配的子部件 ID，并仅将运动学刚体加入物理空间。 */
    synchronized void enterClientBody() {
        var children = collectSubParts(partMap.values());
        children.forEach(ObjectManager::addDestroyableObject);
        SparkLevel.getPhysicsLevel(level).submitImmediateTask(PPhase.PRE, () -> {
            synchronized (this) {
                if (isRemoved) return null;
                var world = SparkLevel.getPhysicsLevel(level).getWorld();
                for (var child : children) {
                    if (!child.isRemoved() && !child.body.isInWorld()) world.addCollisionObject(child.body);
                }
            }
            return null;
        });
    }

    private static List<SubPart> collectSubParts(Collection<? extends AbstractPart<?>> parts) {
        return parts.stream().flatMap(part -> part.subParts.values().stream()).toList();
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
     * 位姿样本从这里入队：一个包一个样本，样本序号因此就是服务端 tick 序号的镜像，呈现缓冲
     * （{@link #presentationPose}）才能按「每 tick 一个样本」的节奏播放。整批回调而不是单字段回调，
     * 是因为一次应用里 {@code DATA_POS} 与 {@code DATA_YAW} 会各自触发一次单字段回调，
     * 按后者入队会多出一个「新位置 + 旧朝向」的幻影样本；包内两者都没有时则不入队，
     * 否则只是把一个与上一个完全相同的样本再压一遍，白拉慢一帧。
     * <p>
     * 本方法仍不把同步数据推回物理体（客户端没有控制器刚体）。
     */
    @Override
    public void onSyncedDataUpdated(@NotNull List<SynchedEntityData.DataValue<?>> dataValues) {
        PoseDelayBuffer buffer = this.presentationPose;
        if (buffer == null) return;
        int positionId = DATA_POS.id();
        int yawId = DATA_YAW.id();
        for (SynchedEntityData.DataValue<?> value : dataValues) {
            int id = value.id();
            if (id == positionId || id == yawId) {
                org.joml.Vector3f position = getSyncedData().get(DATA_POS);
                float yaw = getSyncedData().get(DATA_YAW).getY();
                buffer.push(new Vector3f(position.x, position.y, position.z), yawQuaternion(yaw));
                return;
            }
        }
    }

    // ==========================================
    // 呈现位姿（客户端）
    // ==========================================

    /**
     * 把一个客户端 tick 的时间推进到呈现位姿上。
     * <p>
     * <b>调用线程：客户端主线程，每 tick 恰好一次</b>，由
     * {@code client/MechaPoseDelayTick.java#onClientTickPre} 在 {@code ClientTickEvent.Pre} 扇出。
     * <p>
     * <b>相位是硬约束，必须早于实体 tick。</b> 本对样本有两个消费者：{@code PlayerTickEvent.Post}
     * 把宿主实体摆到右端（机体模型画在宿主实体的渲染原点上），渲染期按 {@code partialTick} 在两端之间
     * 插值。放到本 tick 更晚的相位（例如 {@code LevelTickEvent.Post}），宿主实体那一步读到的就是上一
     * tick 推进出来的右端，模型的样对整体比子零件调试框落后一份样本，行走时相差 0.3 m 上下。
     * <p>
     * 服务端是 no-op。
     */
    public void advancePresentationPose() {
        PoseDelayBuffer buffer = this.presentationPose;
        if (buffer == null) return;
        buffer.advance();
    }

    /**
     * 取呈现区间的插值位置（控制器碰撞体中心，世界坐标）。
     *
     * @param partialTick 渲染部分 tick，{@code [0, 1)}；取 {@code 1f} 即区间右端
     * @param dest        输出（{@code org.joml.Vector3f}），避免渲染期分配
     * @return {@code false} 表示还没有样本，{@code dest} 未被写入
     */
    public boolean lerpPresentationPosition(float partialTick, org.joml.@NotNull Vector3f dest) {
        PoseDelayBuffer buffer = this.presentationPose;
        if (buffer == null || !buffer.hasSample()) return false;
        Vector3f from = buffer.left().getTranslation();
        Vector3f to = buffer.right().getTranslation();
        dest.set(from.x + (to.x - from.x) * partialTick,
                from.y + (to.y - from.y) * partialTick,
                from.z + (to.z - from.z) * partialTick);
        return true;
    }

    /**
     * 取呈现区间的插值朝向（度，归约到 {@code [-180, 180)}）。
     * <p>
     * 走最短角路径：样本里的旋转是「只由偏航构造的四元数」，两端各自解出偏航后按
     * {@link Mth#rotLerp} 插值，因此 {@code 350° → 10°} 走 {@code +20°} 而不是绕一整圈。
     *
     * @param partialTick 渲染部分 tick，{@code [0, 1)}
     * @return 朝向（度）；还没有样本时退化为同步值本身
     */
    public float lerpPresentationYaw(float partialTick) {
        PoseDelayBuffer buffer = this.presentationPose;
        if (buffer == null || !buffer.hasSample()) {
            return getSyncedData().get(DATA_YAW).getY();
        }
        return Mth.wrapDegrees(Mth.rotLerp(partialTick,
                yawDegrees(buffer.left().getRotation()),
                yawDegrees(buffer.right().getRotation())));
    }

    /**
     * 呈现缓冲累计停住的 tick 数：余量耗尽、这一 tick 没有前进。
     * <p>
     * 诊断用（录制进 CSV 的附加列）。它与 {@link #presentationReserve()} 一起回答「停帧是供给不足
     * 还是播放太快」：停住数持续增长而余量始终为 0，就是供给的样本比播放的 tick 少。
     *
     * @return 累计次数；服务端或缓冲未建立时为 0
     */
    public long presentationStarvedTicks() {
        PoseDelayBuffer buffer = this.presentationPose;
        return buffer == null ? 0L : buffer.starvedTicks();
    }

    /**
     * 呈现缓冲累计丢掉的样本数：积压超过 {@code spare + 2} 时向前跳、把延迟限回稳态值的累计量。
     *
     * @return 累计样本数；服务端或缓冲未建立时为 0
     */
    public long presentationSkippedSamples() {
        PoseDelayBuffer buffer = this.presentationPose;
        return buffer == null ? 0L : buffer.skippedSamples();
    }

    /**
     * 当前余量（样本数）：除了呈现区间右端之外还留着的样本数，{@code 0} 表示下一 tick 就会停住。
     *
     * @return 余量；服务端或缓冲未建立时为 {@code -1}
     */
    public long presentationReserve() {
        PoseDelayBuffer buffer = this.presentationPose;
        return buffer == null ? -1L : buffer.reserve();
    }

    /** 把偏航（度）打包成只绕世界 Y 轴的四元数。 */
    private static Quaternion yawQuaternion(float degrees) {
        // 只给 Y 分量：x = z = 0 时 fromAngles 就是一次纯偏航旋转，与 yawDegrees 的取法互为逆
        return new Quaternion().fromAngles(0f, (float) Math.toRadians(degrees), 0f);
    }

    /** 从只绕世界 Y 轴的四元数解回偏航（度）。 */
    private static float yawDegrees(Quaternion rotation) {
        // 纯偏航四元数：y = sin(θ/2)、w = cos(θ/2)，θ ∈ (-π, π]
        return (float) Math.toDegrees(2.0 * Math.atan2(rotation.getY(), rotation.getW()));
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
     *   <li>消费本步控制器位姿和速度，更新身体约束的目标，再交给 Bullet 求解。</li>
     *   <li>发布逻辑状态快照供主线程读取（仅权威侧，见下）</li>
     * </ol>
     *
     * @param dt 物理步长 (s)
     */
    public void prePhysicsTick(float dt) {
        // 乘客态每步刷新：窄相否决的判据必须与本步的乘坐状态一致
        riddenAssembly = isRemoved ? null : getRiddenAssembly();
        MechaControl control = this.mechaControl;
        if (control == null || isRemoved || authoritative && !isInLevel()) return;

        if (bodyAnimation != null) {
            bodyAnimation.ensureModel();
            if (authoritative) bodyAnimation.updateServerInputs(control, dt);
        }
        // ② 下行状态写入 → 帧逻辑（仅权威侧） → 表现层推进 → extractAnimRootDelta → 控制器物理步
        control.onPhysicsStep(dt);
        if (bodyPhysics != null) {
            bodyPhysics.followControllerStep(getBody());
            if (bodyAnimation != null) bodyAnimation.drive(control);
        } else if (!authoritative && bodyAnimation != null) {
            var model = bodyAnimation.getModelController().getModel();
            if (model != null) {
                for (var part : partMap.values()) {
                    if (part instanceof BodyPart bodyPart) bodyPart.applySharedAnimation(model.getPose());
                }
            }
        }
        // 调试录制：未开启时只是一次 volatile 读，开销可忽略（见 common/debug/MechaTelemetry）
        MechaTelemetry.onPhysicsStep(this, dt);

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
     * 物理线程：设置出生点，在同一任务中加入控制器、身体与约束。
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
    public synchronized void enterPhysicsSpace(Vector3f controllerCenter) {
        if (!authoritative || isRemoved || isInLevel()) return;
        var space = SparkLevel.getPhysicsLevel(level).getWorld();
        MechaCharacter body = getBody();
        body.setPhysicsLocation(controllerCenter);
        var physicalBody = new ArmsBodyPhysics(corePart, partMap.values(), body.getTransform(null),
                frame == null ? 0f : frame.coreHeightOffset());
        try {
            // owner 让碰撞过滤能从控制器刚体反查到本装配体；名字先固定，setOwner 用它做索引键。
            body.name = "controller";
            PhysicsBodyExtensionKt.setOwner(body, this);
            space.addCollisionObject(body);
            physicalBody.enter(space, body);
            bodyPhysics = physicalBody;
            inPhysicsSpace.set(true);
        } catch (RuntimeException | Error failure) {
            physicalBody.leave(space);
            if (body.isInWorld()) space.removeCollisionObject(body);
            PhysicsBodyExtensionKt.setOwner(body, null);
            physicalBody.release(body);
            throw failure;
        }
    }

    /** 在同一物理阶段传送控制器和身体，保持关节之间的相对姿态。 */
    void warpController(Vector3f target) {
        if (isRemoved) return;
        var controller = getBody();
        var delta = target.subtract(controller.getPhysicsLocation(null));
        controller.warp(target);
        if (bodyPhysics != null) bodyPhysics.warp(delta, controller.getLinearVelocity(null));
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
     * 客户端实例同样走这条路径：它没有控制器刚体，只武装 pin，客户端手里的
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

    @Override
    public PhysicsLevel getPhysicsLevel() {
        return SparkLevel.getPhysicsLevel(level);
    }

    @Override
    public Map<String, PhysicsCollisionObject> getAllPhysicsBodies() {
        return physicsBodies;
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
        return type instanceof BodyPartType || type instanceof RegularPartType;
    }

    /** 添加普通身体成员；核心身份由构造器确定。 */
    @Override
    public synchronized void addPart(AbstractPart<?> part) {
        validateMember(part);
        // UUID 相同但实例不同属于冲突，禁止覆盖现有成员或核心。
        var current = mutableParts.putIfAbsent(part.uuid, part);
        if (current == part) return;
        if (current != null) throw new IllegalArgumentException("部件 UUID 重复：" + part.uuid);
        part.assembly = this;
        // 肢体刚体的窄相处理与控制器同源：乘坐时本装配体的任何刚体都不与载具的 SubPart 产生接触响应
        for (SubPart subPart : part.subParts.values()) {
            attachRideContactHandlers(subPart.body);
        }
        bodyDirty = true;
    }

    /** 检查主线程装配入口的共同前置条件。 */
    private void validateMember(AbstractPart<?> part) {
        if (isRemoved) throw new IllegalStateException("装配体已释放");
        if (isInLevel()) throw new IllegalStateException("身体入世后暂不支持更换成员");
        if (!acceptsPartType(part.type) || part.level != level || part.isDestroyed()
                || part instanceof BodyPart body && body.isDisposed()
                || part.assembly != null && part.assembly != this) {
            throw new IllegalArgumentException("部件必须是同世界中尚未归属其他装配体的有效身体部件或普通部件");
        }
        if (part.subParts.isEmpty() || part.subParts.values().stream().anyMatch(child -> child.body.isInWorld())) {
            throw new IllegalArgumentException("身体部件必须具有尚未入世的子结构");
        }
    }

    /** 移除非核心成员并释放其结构；核心随整个装配体一起释放。 */
    @Override
    public synchronized void removePart(AbstractPart<?> part) {
        if (isInLevel()) throw new IllegalStateException("身体入世后暂不支持更换成员");
        if (part == corePart) throw new IllegalArgumentException("核心不能单独移除，请释放整个装配体");
        if (!mutableParts.remove(part.uuid, part)) return;
        bodyLinks = bodyLinks.stream().filter(link -> link.advanced().subPart.part != part
                && link.simple().subPart.part != part).toList();
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
        // MM 也会从冲击路径请求断开；身体分裂接入前保留约束。
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

    /**
     * 本装配体当前乘坐的载具装配体；物理线程每步刷新，窄相回调里只读。
     *
     * <p>乘客态是窄相否决的判据，而 {@code Collide.Pre} 的回调本身不带「本装配体是否在乘坐」的信息，
     * 只能由这里提供。两端都刷新：客户端的运动学肢体同样不该与所乘客的载具产生接触对。</p>
     */
    @Nullable
    private volatile IPartAssembly riddenAssembly;

    /**
     * 窄相否决：本装配体的刚体与所乘客的载具的 SubPart 之间不产生接触点（物理线程）。
     *
     * <p><b>为什么不用 {@code NeedsCollisionEvent}</b>：Spark 的 {@code needsCollision} 挂在 Bullet 的
     * 重叠过滤回调上（{@code ../Libbulletjme/src/main/native/glue/jmeCollisionSpace.cpp} 的
     * {@code jmeFilterCallback::needBroadphaseCollision}），而该回调只在配对进入重叠缓存时被求值
     * （{@code ../Libbulletjme/src/main/native/bullet3/BulletCollision/BroadphaseCollision/btOverlappingPairCache.h}
     * 的 {@code btHashedOverlappingPairCache::addOverlappingPair}）；上/下车改变的是<b>已有配对</b>的结论，
     * 缓存不重算，于是乘坐期间早已存在的肢体-车体配对继续参与求解。</p>
     *
     * <p><b>为什么不用 ignore list</b>：忽略条目确实每个物理步都重查（{@code btCollisionDispatcher::needsCollision}
     * 里两向 {@code checkCollideWith}，见 {@code ../Libbulletjme/src/main/native/bullet3/BulletCollision/CollisionDispatch/btCollisionDispatcher.cpp}），
     * 但它让 {@code processCollision} 整个跳过，那条 manifold 不再刷新：残留接触点仍被求解器按旧冲量
     * 积分。实测（Libbulletjme 23.1.2、100 Hz、两个盒子）屏蔽后恒速推开约 1.2 cm/步且不衰减。</p>
     *
     * <p><b>为什么只有否决不够</b>：{@code onContactConceived} 只在新接触点产生时被问，上车前已有的
     * 接触点由各算法的 {@code refreshContactPoints()} 继续维持。同场景实测：接触点建立之后再开始否决，
     * 6 步共下移 0.11 m 且每步仍在增大，而 {@code onContactProcessed} 每步仍为 4 条旧点派发。
     * 这批旧点由 {@link #neutralizeRideContact} 在同一物理步里取消求解。</p>
     *
     * <p><b>为什么是这里</b>：{@code Collide.Pre} 由窄相里的 {@code onContactConceived} 派发
     * （{@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/level/PhysicsWorld.kt} 的
     * {@code PhysicsWorld.onContactConceived}），返回 {@code false} 即丢弃该接触点：接触点不进 manifold，
     * 求解器没有约束，也不触发 {@code Collide.Processed}——MM 的碰撞音效与 {@code part_collision} 伤害
     * 都挂在那条路径上（{@code ../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/vehicle/collision/CollisionHandler.java}
     * 的 {@code CollisionHandler.onContactProcessed}）。判据每个接触点现算，与配对何时建立无关，
     * 因此上/下车立即生效。</p>
     *
     * <p>本方法只拦<b>新</b>接触点。上车之前就已经存在的接触点由
     * {@link #neutralizeRideContact} 在同一个物理步里取消求解。</p>
     *
     * @param event 窄相接触点事件；{@code o1} 是挂着本监听器的刚体，{@code o2} 是对方
     * @return true 表示保留这条接触
     */
    private boolean vetoRideContact(PhysicsBodyEvent.Collide.Pre event) {
        IPartAssembly vehicle = riddenAssembly;
        if (vehicle == null) return true;
        return !(event.getO2().getUserObject() instanceof SubPart other && other.part.assembly == vehicle);
    }

    /**
     * 为刚体挂上乘坐期的两段窄相处理：拒绝新的接触点（{@link #vetoRideContact}），
     * 并取消已有接触点的求解（{@link #neutralizeRideContact}）。
     *
     * <p>两段必须成对：只挂前者，上车前已存在的接触点会一直留在 manifold 里被求解；
     * 只挂后者，新接触点会在被取消之前先求解一步。</p>
     *
     * @param body 本装配体的刚体（控制器或某个 SubPart）
     */
    private void attachRideContactHandlers(PhysicsCollisionObject body) {
        PhysicsBodyExtensionKt.onCollidePre(body, this::vetoRideContact);
        PhysicsBodyExtensionKt.onCollideProcessed(body, event -> {
            neutralizeRideContact(event);
            return null;
        });
    }

    /**
     * 已存在接触点的取消求解：把这一步的距离改成「分离」，让求解器建不出有效约束（物理线程）。
     *
     * <p><b>为什么需要</b>：{@link #vetoRideContact} 挂在 {@code onContactConceived} 上，只拦新接触点。
     * 上车时肢体与载具已经接触的话，那条 manifold 里的旧点不会被删除——各算法的
     * {@code refreshContactPoints()} 只要两个刚体保持接触就持续刷新它们
     * （{@code ../Libbulletjme/src/main/native/bullet3/BulletCollision/NarrowPhaseCollision/btPersistentManifold.cpp}
     * 的 {@code btPersistentManifold::refreshContactPoints}），求解器于是继续按旧冲量积分：
     * 应用侧收不到任何接触回调，两个刚体却仍被稳定推开。</p>
     *
     * <p><b>为什么改距离有效</b>：{@code Collide.Processed} 由 {@code onContactProcessed} 派发，
     * 位置在 {@code refreshContactPoints()} 重算完距离之后、同一个物理步的求解器之前
     * （{@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/level/PhysicsWorld.kt}
     * 的 {@code PhysicsWorld.onContactProcessed}）。把距离写成
     * {@link CollisionPostSettlement#DISABLED_DISTANCE} 后，求解器读到的
     * {@code penetration = distance + linearSlop} 为正
     * （{@code ../Libbulletjme/src/main/native/bullet3/BulletDynamics/ConstraintSolver/btSequentialImpulseConstraintSolver.cpp}
     * 的 {@code setupContactConstraint}）：位置修正项归零、速度项被推成大负值、冲量在下界 0 处截断，
     * 这条点本步不再产生推力。三个缓存冲量一并清零，否则 warm start 会在本步先施加一次旧冲量
     * （同文件 {@code convertContact}）。下一步 {@code refreshContactPoints()} 会按真实几何重算距离，
     * 所以每步都要重做。</p>
     *
     * <p>球-球接触不派发 {@code onContactProcessed}（见 {@code PhysicsSpace#onContactProcessed} 的注释），
     * 本装配体的刚体不是球体，与载具 SubPart 的配对不会落到那一路。</p>
     *
     * @param event 窄相接触点事件；{@code o1} 是挂着本监听器的刚体，{@code o2} 是对方
     */
    private void neutralizeRideContact(PhysicsBodyEvent.Collide.Processed event) {
        IPartAssembly vehicle = riddenAssembly;
        if (vehicle == null) return;
        if (!(event.getO2().getUserObject() instanceof SubPart other) || other.part.assembly != vehicle) return;
        var point = event.getO1Point();
        point.setDistance(CollisionPostSettlement.DISABLED_DISTANCE);
        point.setAppliedImpulse(0f);
        point.setAppliedImpulseLateral1(0f);
        point.setAppliedImpulseLateral2(0f);
    }

    /** 保存身体组成；核心通过 UUID 引用集合中的唯一实例。 */
    public ArmsBodyData saveBody() {
        if (isRemoved) throw new IllegalStateException("已释放的装配体不能保存");
        return new ArmsBodyData(assemblyName, corePart.uuid,
                partMap.values().stream().map(AbstractPart::save).toList(),
                bodyLinks.stream().map(ArmsBodyAssembly.Link::save).toList(), frameId);
    }

    /** 生成用于持久化或创建包的组成 NBT。 */
    public CompoundTag saveBodyTag() {
        return (CompoundTag) ArmsBodyData.codec(level.isClientSide()).encodeStart(NbtOps.INSTANCE, saveBody()).getOrThrow();
    }

    /** 应用同一具机体的组成快照，保持核心实例及成员容器的身份稳定。 */
    public synchronized void applyBodySnapshot(ArmsBodyData data) {
        if (isRemoved) throw new IllegalStateException("装配体已释放");
        if (!corePart.uuid.equals(data.corePartId())) {
            throw new IllegalArgumentException("组成快照不能更换核心");
        }
        if (!frameId.equals(data.frameId())) {
            throw new IllegalArgumentException("组成快照不能更换素体 ID");
        }
        if (authoritative && (isInLevel() || !bodyLinks.isEmpty() || !data.connections().isEmpty())) {
            throw new IllegalArgumentException("服务端连接由构造和恢复入口建立");
        }
        ArmsBodyAssembly prepared = ArmsBodyAssembly.restore(level, data, false);
        // 临时核心只用于验证恢复，发布的核心仍使用原实例。
        corePart.applySavedState(prepared.core().save());
        var nextLinks = prepared.links().stream().map(link -> link.withCore(corePart)).toList();
        Map<UUID, AbstractPart<?>> next = new LinkedHashMap<>();
        prepared.parts().forEach(part -> next.put(part.uuid, part == prepared.core() ? corePart : part));
        var previous = new ArrayList<>(partMap.values());
        next.values().forEach(part -> part.assembly = this);
        // 修改固定容器的内容；外部持有的只读视图始终对应这一份容器。
        mutableParts.clear();
        mutableParts.putAll(next);
        bodyLinks = nextLinks;
        assemblyName = data.name();
        previous.stream().filter(part -> part != corePart).forEach(part -> {
            part.assembly = null;
            part.destroy();
        });
        if (!authoritative) enterClientBody();
        bodyDirty = true;
    }

    /** 主线程推进身体成员，并发送成员变化产生的完整组成快照。 */
    public void tickBody() {
        if (isRemoved) return;
        if (bodyAnimation != null) {
            if (!authoritative) bodyAnimation.updateClientInputs();
            bodyAnimation.getAnimController().tick();
        }
        partMap.values().forEach(AbstractPart::onTick);
        if (authoritative && bodyDirty && MechaCoreRegistry.get(level, assemblyId) == this) {
            MechaCoreRegistry.syncBody(this);
            // 先发送组成及初始位姿，再允许 MM 按整数 ID 广播增量。
            collectSubParts(partMap.values()).forEach(ObjectManager::addDestroyableObject);
            bodyDirty = false;
        }
    }

    /** 停止步进，先在物理线程移除约束与刚体，再回主线程释放成员。 */
    public synchronized CompletableFuture<Void> disposeBody() {
        if (isRemoved) return bodyReleased;
        isRemoved = true;
        collectSubParts(partMap.values()).forEach(ObjectManager::removeDestroyableObject);
        if (authoritative && isInLevel()) {
            var physicsLevel = SparkLevel.getPhysicsLevel(level);
            physicsLevel.submitImmediateTask(PPhase.PRE, () -> {
                if (bodyAnimation != null) bodyAnimation.releaseDrives();
                var space = physicsLevel.getWorld();
                if (bodyPhysics != null) {
                    bodyPhysics.leave(space);
                    bodyPhysics.release(getBody());
                }
                if (getBody().isInWorld()) space.removeCollisionObject(getBody());
                PhysicsBodyExtensionKt.setOwner(getBody(), null);
                bodyPhysics = null;
                inPhysicsSpace.set(false);
                java.util.Objects.requireNonNull(level.getServer()).execute(this::releaseMembers);
                return null;
            });
        } else {
            releaseMembers();
        }
        return bodyReleased;
    }

    /** Spark 先关闭维度物理空间，此处只完成尚未执行的主线程清理。 */
    synchronized void disposeBodyAfterWorldClosed() {
        isRemoved = true;
        collectSubParts(partMap.values()).forEach(ObjectManager::removeDestroyableObject);
        inPhysicsSpace.set(false);
        if (bodyPhysics != null) {
            bodyPhysics.release(getBody());
            bodyPhysics = null;
        }
        if (authoritative) PhysicsBodyExtensionKt.setOwner(getBody(), null);
        releaseMembers();
    }

    private void releaseMembers() {
        if (bodyReleased.isDone()) return;
        var previous = new ArrayList<>(partMap.values());
        mutableParts.clear();
        bodyLinks = List.of();
        previous.forEach(part -> {
            part.assembly = null;
            part.destroy();
            part.subParts.values().forEach(child -> PhysicsBodyExtensionKt.setOwner(child.body, null));
        });
        bodyDirty = false;
        bodyReleased.complete(null);
    }

    @Override
    public String toString() {
        return "ArmsCore[" + assemblyId + (authoritative ? ", server" : ", client") + "]";
    }
}
