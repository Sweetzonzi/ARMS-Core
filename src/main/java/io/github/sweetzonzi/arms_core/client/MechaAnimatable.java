package io.github.sweetzonzi.arms_core.client;

import cn.solarmoon.spark_core.animation.IAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimController;
import cn.solarmoon.spark_core.animation.model.ModelController;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.origin.OModel;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaModelPreset;
import io.github.sweetzonzi.arms_core.common.control.presentation.MechaMolangContext;
import lombok.Getter;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * 机娘的客户端动画体 —— Spark-Core 渲染基岩版模型所要求的 {@link IAnimatable} 实现。
 * <p>
 * 本类是与 {@link ArmsCore} 分离的独立对象：装配体侧不持有任何动画状态，
 * 模型与骨骼姿态只存在于本对象里。这条分工与 Machine-Max 一致：那边的装配体
 * {@code VehicleCore} 全文不含 {@code AnimController} / {@code ModelController}，
 * 模型与骨骼姿态属于 {@code Part}，逐零件渲染时由渲染器反过来读
 * {@code subPart.part.getModelController()}
 * （{@code PartEntityRenderer.java:108}、{@code DistantVehicleRenderer.java:108}）。
 * 装配体级动画体只存在于「没有可渲染实体」的场景，也就是本模组当前的全部场景：
 * {@code ArmsCore} 不注册为世界实体（`docs/总体设计文档.md:82`），{@code Part} / {@code SubPart}
 * 尚未接入。因此这里是一个**占位**动画体：等 {@code Part} 装配落地后，渲染改为逐 Part
 * （每个 Part 自带模型与姿态），本类整体删除即可，{@link ArmsCore} 不受影响。
 *
 * <h3>为什么类型参数是 ArmsCore</h3>
 * {@code IAnimatable<T>} 的 {@code T} 是「动画体的持有者」，MoLang 查询经
 * {@code getAnimatable()} 落到它身上（{@code IAnimatable.kt:21}）。本对象只是渲染适配器，
 * 真正的持有者是装配体，所以 {@link #getAnimatable()} 返回构造时注入的 {@link ArmsCore}，
 * 使 {@code q.*} 一类查询直接在 {@code ArmsCore} 上求值。
 *
 * <h3>位姿来源</h3>
 * 客户端不为 {@code ArmsCore} 重建物理体（计划 D18），位姿只有同步来的 {@code DATA_POS} /
 * {@code DATA_YAW}，以及它们经呈现缓冲平滑后的那对样点
 * （{@code common/ArmsCore.java#lerpPresentationPosition}；缓冲的播放与传送跳变规则见
 * {@code ../Machine-Max/src/main/java/io/github/sweetzonzi/machine_max/common/mech/PoseDelayBuffer.java#push}）。
 * 本类负责把这对样点组装成模型矩阵：
 * {@link #getModelSpaceMatrix(Number, double, double, double, float)} 供"画在宿主实体上"那条路径使用
 * （锚点就地，位置由实体提供），{@link #getWorldPositionMatrix(Number)} 是接口要求的绝对位姿版本，
 * 按呈现缓冲的插值位姿组装。朝向按 {@code rotateY(π − yaw)} 组装，与 Spark-Core 自己的实体公式同式
 * （{@code IEntityAnimatable.kt:31-35}）。
 *
 * <h3>生命周期与线程</h3>
 * 由 {@link MechaPlayerRenderer} 在主线程按装配体 UUID 创建并驱动（一个客户端实例只在一个维度，
 * 与 {@code MechaCoreRegistry} 的客户端表同寿命）：每客户端 tick 采样一次
 * （{@link #clientTick()}）并调 {@code AnimController.tick()} 发布骨骼姿态，渲染在同一线程读取。
 * <p>
 * 另一半在物理线程：本对象作为动画目标注入表现层编排器
 * （{@code common/control/presentation/MechaAnimPresenter.java}），由后者在每个物理子步推进动画
 * 时间与权重（{@code AnimController.physTick()}）并混合骨骼。这是 Spark-Core 自身的分工
 * （{@code physTick} 在物理线程、{@code tick} 在主线程），不是本项目的取舍。因此本对象被两个线程
 * 触碰：主线程调 {@link #clientTick()}，物理线程调 {@code AnimController.physTick()}。
 *
 * @author Sweetzonzi
 */
public final class MechaAnimatable implements IAnimatable<ArmsCore> {

    /**
     * 本动画体所代表的装配体，同时是 MoLang 的求值对象：{@code q.*} 一类查询经
     * {@code getAnimatable()} 落到它身上（{@code IAnimatable.kt:21}）。
     */
    @Getter
    @NotNull
    private final ArmsCore animatable;

    /** 素体模型标识（{@link MechaModelPreset#FRAME_MODEL}）；构造期即被 {@link ModelController} 读取，必须先于它就位 */
    @Getter
    @NotNull
    private final ModelIndex defaultModelIndex;

    @Getter
    @NotNull
    private final ModelController modelController;

    @Getter
    @NotNull
    private final AnimController animController;

    /**
     * MoLang 变量表。
     * <p>
     * 必须是可变映射：{@code IAnimatable.putVariable} 与
     * {@code controllerAllAnimationsFinished} 的 setter 都会写入这里
     * （{@code IAnimatable.kt:43}、{@code :94}）。
     */
    @Getter
    @NotNull
    private final Map<String, Object> variables = new HashMap<>();

    /**
     * 一次性缓存的 MoLang 上下文（接口默认实现每次调用都新建，类注释见 {@code IAnimatable.kt:85}）。
     * <p>
     * 类型是本仓库自己的 {@link MechaMolangContext}：内容包用到的 {@code query.*} 由它提供，
     * 表现层编排器把它当 {@code contextProvider} 传给四台姿态机，因此姿态图条件与骨骼 molang
     * 读到的是同一份值。它的取值与发布点见该类注释。
     */
    @Getter
    @NotNull
    private final MechaMolangContext molangContext;

    /**
     * 最近一次客户端 tick 采到的呈现位姿（控制器碰撞体中心，世界坐标）。
     * <p>
     * 读它的两处都在物理线程：{@code AnimController#physTick} 的 LOD 分档与 MoLang 的
     * {@code q.camera_distance}（经 {@link #getRenderPosition(Number)}）；写它的只有主线程的
     * {@link #clientTick()}。因此按三个 {@code volatile} 分量发布而不是发布一个共享对象：跨 tick 撕裂
     * （x 来自本 tick、y 来自下一 tick）在这两处都无所谓，它们要的是一个 20 Hz 量级的位置。
     */
    private volatile float sampledX;
    private volatile float sampledY;
    private volatile float sampledZ;

    /** 采样用的复用缓冲；只在主线程的 {@link #samplePresentationPosition()} 里用 */
    private final Vector3f sampleScratch = new Vector3f();

    /** 服务器 tick 率：同步速度 (m/s) 与每 tick 位移 (m/tick) 之间换算，也是角速度差分的时间基 */
    private static final float TICKS_PER_SECOND = 20f;

    /**
     * {@code yaw_speed} 的一阶滤波系数。
     * <p>
     * 时间常数取 0.1 s：每 tick 采样一次，α = 1 − e^(−dt/τ) = 1 − e^(−0.5) ≈ 0.39。
     * 滤波的理由是差分放大量化误差——同步偏航按 1/256 度量化，20 Hz 差分后噪声是真实角速度的
     * 可观分量，未滤波的值会让内容包里的 {@code math.abs(yaw_speed)>120} 一类判据误触发。
     */
    private static final float YAW_SPEED_SMOOTHING = 0.4f;

    /** 上一 tick 的同步偏航（度）；首次采样时为 NaN，表示还没有前值可差分 */
    private float lastYaw = Float.NaN;

    /** 偏航角速度（度/秒）的滤波状态，由上式逐步收敛 */
    private float yawSpeed;

    /** 动画体存在时长（秒），每客户端 tick 累加一个 tick */
    private float lifeTimeSeconds;

    /**
     * 呈现位姿的复用缓冲。
     * <p>
     * 只服务于接口契约那条路径（{@link #getWorldPositionMatrix(Number)}，渲染线程在两端样本间插值）；
     * 模型画在宿主实体上时不用它，因为那条路径的位置来自实体自身。
     */
    private final Vector3f worldScratchPos = new Vector3f();

    /**
     * 为一个客户端装配体建立动画体。
     * <p>
     * <b>构造顺序是硬约束</b>：{@link ModelController} 在构造期就读取
     * {@link #getDefaultModelIndex()} 并据此展开整棵骨骼姿态（{@code ModelController.kt:19}、
     * {@code ModelPose.kt:21-29}），所以模型标识必须先赋值。
     *
     * @param core 客户端侧的装配体实例（{@code ArmsCore.newClientInstance} 构造）
     */
    public MechaAnimatable(ArmsCore core) {
        this.animatable = core;
        this.defaultModelIndex = MechaModelPreset.FRAME_MODEL;
        this.modelController = new ModelController(this);
        this.animController = new AnimController(this);
        this.molangContext = new MechaMolangContext(this);
        // 贴图另放一层同名目录（frame/bmpt/bmpt.png），ModelInstance 默认推导的
        // textures/frame/bmpt.png 不存在，必须显式指定
        this.modelController.setTextureLocation(MechaModelPreset.FRAME_TEXTURE);
    }

    // ==========================================
    // 每客户端 tick
    // ==========================================

    /**
     * 客户端主线程每 tick 调用一次：采样呈现位姿、补齐模型、发布骨骼姿态。
     * <p>
     * 采样必须每 tick 做一次而不是渲染时做：{@link #getRenderPosition(Number)} 的调用者在物理线程，
     * 而 {@code SynchedEntityData} 与呈现缓冲只允许主线程读，所以这里把当前值取出来发布一次。
     */
    public void clientTick() {
        samplePresentationPosition();
        publishMolangInputs();
        refreshModelIfNeeded();
        animController.tick();
    }

    /**
     * 采样本 tick 的呈现位姿（呈现区间右端，即这一 tick 模型应该站的位置），发布给物理线程。
     * <p>
     * 用右端而不是两端插值：本方法只喂 LOD 分档与 {@code q.camera_distance}，两处都只需要
     * 「这一刻在哪」。呈现缓冲还没有样本时（首包之前、刚入世）退回同步真值，避免把位置读成原点。
     */
    private void samplePresentationPosition() {
        if (animatable.lerpPresentationPosition(1f, sampleScratch)) {
            sampledX = sampleScratch.x;
            sampledY = sampleScratch.y;
            sampledZ = sampleScratch.z;
            return;
        }
        Vector3f synced = animatable.getSyncedData().get(ArmsCore.DATA_POS);
        sampledX = synced.x;
        sampledY = synced.y;
        sampledZ = synced.z;
    }

    /**
     * 把本 tick 的同步数据发布给 MoLang 上下文。
     * <p>
     * 发布点选在这里而不是 {@code @QueryBinding} 方法里：同步数据容器（{@code SynchedEntityData}）
     * 只在主线程读，而 molang 求值在物理线程；本方法把需要的那几个数读出来存进上下文的 volatile 字段，
     * 物理线程因此只读普通字段。发布值都是「最近一次采样」，与渲染插值无关（插值在别处做）。
     * <p>
     * 一次采样发布四组量：速度、每 tick 位移、朝向与角速度、存在时长。
     * 头部朝向不在这里——它唯一的来源（宿主实体）只在渲染路径上拿得到，
     * 由 {@code client/MechaPlayerRenderer.java#onRenderPlayerPre} 发布。
     */
    private void publishMolangInputs() {
        Vector3f velocity = animatable.getSyncedData().get(ArmsCore.DATA_VEL);
        float groundSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        molangContext.setMotion(groundSpeed, velocity.y);
        // 每 tick 位移 = 同步线速度 / tick 率：与 DATA_POS 的相邻采样之差同义，
        // 但取速度这一路不会有传送帧的孤立大值
        molangContext.setPositionDelta(
                velocity.x / TICKS_PER_SECOND,
                velocity.y / TICKS_PER_SECOND,
                velocity.z / TICKS_PER_SECOND);

        float yaw = animatable.getSyncedData().get(ArmsCore.DATA_YAW).getY();
        if (!Float.isNaN(lastYaw)) {
            float delta = Mth.wrapDegrees(yaw - lastYaw) * TICKS_PER_SECOND;
            yawSpeed += (delta - yawSpeed) * YAW_SPEED_SMOOTHING;
        }
        lastYaw = yaw;
        molangContext.setFacing(yaw, yawSpeed);

        lifeTimeSeconds += 1f / TICKS_PER_SECOND;
        molangContext.setLifeTime(lifeTimeSeconds);
    }

    /**
     * 内容包尚未读取时补建模型实例。
     * <p>
     * {@link ModelController} 构造时就展开骨骼姿态，若那一刻模型表还是空的，
     * 这具姿态会永远没有骨骼（{@code OModel.getOrEmpty} 返回空模型）。内容包由
     * Spark-Core 在资源 / 数据包加载阶段读入，时序上可能晚于创建包到达客户端，
     * 因此每次 tick 做一次廉价检查：模型表里有本模型、而当前姿态没有骨骼时，重建一次
     * {@link ModelInstance}（与 `BlockEntityItemRenderer.java:55` 的做法一致）。
     * <p>
     * <b>本方法只覆盖「当前姿态从来就没有骨骼」这一段。</b> 内容包重载之后旧姿态里留着的仍是旧骨骼
     * 对象，上面那条判据不再成立，重建由表现层编排器统一触发（它每物理步比较
     * {@code OAnimationSet.getORIGINS()} 与 {@code OModel.getORIGINS()} 里的对象引用），
     * 本方法不参与，两者因此不会各有一套重建路径。
     */
    private void refreshModelIfNeeded() {
        ModelInstance model = modelController.getModel();
        if (model != null && !model.getPose().getBonePoseList().isEmpty()) return;
        if (!OModel.getORIGINS().containsKey(defaultModelIndex)) return;
        modelController.setModel(new ModelInstance(this, defaultModelIndex));
        modelController.setTextureLocation(MechaModelPreset.FRAME_TEXTURE);
    }

    // ==========================================
    // IAnimatable<ArmsCore>
    // ==========================================

    /** 客户端世界；本对象只在客户端构造。 */
    @Override
    public @Nullable Level getAnimLevel() {
        return animatable.getLevel();
    }

    /**
     * 世界位姿矩阵：把模型空间换算到世界空间，锚点是<b>胶囊中心</b>（呈现缓冲的插值位姿，
     * 缓冲还没有样本时退回同步来的 {@code DATA_POS}）。
     * <p>
     * 这是 {@code IAnimatable} 要求的绝对位姿出口，{@code IGeoRenderer.kt:25} 一类通用渲染路径会调它。
     * <b>本模组当前的绘制不走这条路</b>：模型挂在宿主实体的渲染事件上，位置由实体提供、朝向取宿主的
     * <b>头朝向</b>（{@code client/MechaPlayerRenderer.java#onRenderPlayerPre}），走 {@link #getModelSpaceMatrix}。
     * 保留本方法是为了满足接口契约，也为了将来出现"没有宿主实体可挂"的渲染需求（远景 LOD、镜像、剪影）时有现成出口。
     * <p>
     * 每次调用返回新矩阵：Spark-Core 会在拿到结果后继续 {@code mul}（{@code BonePose.kt:77-88}），
     * 复用同一个实例会被就地改写。
     */
    @Override
    public @NotNull Matrix4f getWorldPositionMatrix(@NotNull Number partialTicks) {
        float partial = partialTicks.floatValue();
        if (!animatable.lerpPresentationPosition(partial, worldScratchPos)) {
            // 呈现缓冲还没有样本：退回同步真值，与 lerpPresentationYaw 内部同一条降级路径
            Vector3f synced = animatable.getSyncedData().get(ArmsCore.DATA_POS);
            worldScratchPos.set(synced);
        }
        return getModelSpaceMatrix(partial,
                worldScratchPos.x, worldScratchPos.y, worldScratchPos.z,
                animatable.lerpPresentationYaw(partial));
    }

    /**
     * 模型空间矩阵：锚点就地，只做「平移 → 缩放 → 偏航旋转」。
     * <p>
     * 由 {@code client/MechaPlayerRenderer.java} 使用：那条路径的 {@code PoseStack} 已经位于宿主实体的
     * 渲染原点，而宿主实体的位置<b>就是</b>胶囊中心（{@code common/ArmsCoreServerEvents.java#applyPoseToHost}
     * 每 tick 写入 {@code 胶囊中心 − HALF_TOTAL}，玩家渲染再按 {@code y + 0.0} 平移，即实体位置本身），
     * 因此那里传 {@code (0, 0, 0)}，位置不会被计入两遍。
     * <p>
     * 收益是模型跟着<b>实体自身</b>的插值走（原版对实体位置与头朝向都做相邻 tick 插值），
     * 而不是跟着 {@code DATA_POS} 的同步延迟走。
     *
     * @param partialTicks 渲染部分 tick
     * @param x            锚点相对宿主渲染原点的偏移 X；画在宿主身上时传 {@code 0}
     * @param y            同上，Y
     * @param z            同上，Z
     * @param yawDeg       宿主实体本帧的朝向（度）；画在宿主身上时传宿主的头朝向（本机玩家为
     *                     {@code getYRot()}，他人为 {@code yHeadRot} 的插值结果），它也是 KCC
     *                     {@code currentYaw} 的来源
     * @return 形如 {@code translate(...).scale(...).rotateY(π − yaw)} 的新矩阵
     */
    public @NotNull Matrix4f getModelSpaceMatrix(@NotNull Number partialTicks,
                                                 double x, double y, double z, float yawDeg) {
        return new Matrix4f()
                .translate((float) x, (float) (y + MechaModelPreset.FRAME_RENDER_Y_OFFSET), (float) z)
                .scale(MechaModelPreset.FRAME_RENDER_SCALE)
                .rotateY((float) (Math.PI - Math.toRadians(yawDeg)));
    }

    /**
     * LOD 用的轻量世界坐标。
     * <p>
     * 覆写是为了避开接口默认实现里的矩阵构造（{@code IAnimatable.kt:58-61}）。取最近一次采样发布的位置
     * 而不做插值：它只用于距离分档，插值没有意义。返回的是新对象，因为调用方可能是物理线程，而
     * {@link #samplePresentationPosition()} 每 tick 在主线程改写发布值。
     */
    @Override
    public @NotNull Vec3 getRenderPosition(@NotNull Number partialTicks) {
        return new Vec3(sampledX, sampledY, sampledZ);
    }
}
