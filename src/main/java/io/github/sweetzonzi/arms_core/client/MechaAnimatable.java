package io.github.sweetzonzi.arms_core.client;

import cn.solarmoon.spark_core.animation.IAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimController;
import cn.solarmoon.spark_core.animation.model.ModelController;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.origin.OModel;
import cn.solarmoon.spark_core.molang.SparkMolangContext;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaModelPreset;
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
 * {@code DATA_YAW}。采样与插值复用 {@link ClientMechaAnchor}（含传送 / 断流跳变判据），
 * 本类只负责把它的结果组装成 {@link #getWorldPositionMatrix(Number)}——
 * Spark-Core 的渲染入口只认这一个出口（{@code IGeoRenderer.kt:25}）。朝向按
 * {@code rotateY(π − yaw)} 组装，与 Spark-Core 自己的实体公式同式（{@code IEntityAnimatable.kt:31-35}）。
 *
 * <h3>生命周期与线程</h3>
 * 由 {@link MechaModelRenderer} 在主线程按装配体 UUID 创建并驱动（一个客户端实例只在一个维度，
 * 与 {@code MechaCoreRegistry} 的客户端表同寿命）：每客户端 tick 采样一次
 * （{@link #clientTick(long)}）并调 {@code AnimController.tick()} 发布骨骼姿态，渲染在同一线程读取。
 * 当前没有动画层，{@code AnimController.physTick()} 不需要调用（无活跃层时它立即返回），
 * 因此本类不涉及物理线程。
 *
 * @author Sweetzonzi
 */
public final class MechaAnimatable implements IAnimatable<ArmsCore> {

    /** 本动画体所代表的装配体，同时是 MoLang 的求值对象 */
    private final ArmsCore core;

    /** 占位模型标识；构造期即被 {@link ModelController} 读取，必须先于它就位 */
    private final ModelIndex defaultModelIndex;

    private final ModelController modelController;

    private final AnimController animController;

    /**
     * MoLang 变量表。
     * <p>
     * 必须是可变映射：{@code IAnimatable.putVariable} 与
     * {@code controllerAllAnimationsFinished} 的 setter 都会写入这里
     * （{@code IAnimatable.kt:43}、{@code :94}）。
     */
    private final Map<String, Object> variables = new HashMap<>();

    /** 一次性缓存的 MoLang 上下文（接口默认实现每次调用都新建，类注释见 {@code IAnimatable.kt:85}） */
    private final SparkMolangContext<MechaAnimatable> molangContext;

    /** 同步位姿的采样与插值状态 */
    private final ClientMechaAnchor anchor = new ClientMechaAnchor();

    /** 组装世界矩阵用的复用缓冲，避免渲染期分配位置对象 */
    private final Vector3f scratchPos = new Vector3f();

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
        this.core = core;
        this.defaultModelIndex = MechaModelPreset.FRAME_MODEL;
        this.modelController = new ModelController(this);
        this.animController = new AnimController(this);
        this.molangContext = new SparkMolangContext<>(this);
        // 模型名带 .geo 后缀、贴图另放一层目录，默认推导出的路径不存在，必须显式指定
        this.modelController.setTextureLocation(MechaModelPreset.FRAME_TEXTURE);
    }

    // ==========================================
    // 每客户端 tick
    // ==========================================

    /**
     * 客户端主线程每 tick 调用一次：采样同步位姿、补齐模型、发布骨骼姿态。
     * <p>
     * 采样必须每 tick 做一次而不是渲染时做：{@link ClientMechaAnchor} 的插值自变量是
     * 「距最近一次采样过了多少 tick」，采样频率决定了插值的两个端点。
     *
     * @param clientTick 当前客户端 tick（{@code Level.getGameTime()}）
     */
    public void clientTick(long clientTick) {
        anchor.acceptFromSyncedData(core, clientTick);
        refreshModelIfNeeded();
        animController.tick();
    }

    /**
     * 内容包尚未读取时补建模型实例。
     * <p>
     * {@link ModelController} 构造时就展开骨骼姿态，若那一刻模型表还是空的，
     * 这具姿态会永远没有骨骼（{@code OModel.getOrEmpty} 返回空模型）。内容包由
     * Spark-Core 在资源 / 数据包加载阶段读入，时序上可能晚于创建包到达客户端，
     * 因此每次 tick 做一次廉价检查：模型表里有本模型、而当前姿态没有骨骼时，重建一次
     * {@link ModelInstance}（与 `BlockEntityItemRenderer.java:55` 的做法一致）。
     */
    private void refreshModelIfNeeded() {
        ModelInstance model = modelController.getModel();
        if (model != null && !model.getPose().getBonePoseList().isEmpty()) return;
        if (!OModel.getORIGINS().containsKey(defaultModelIndex)) return;
        modelController.setModel(new ModelInstance(this, defaultModelIndex));
        modelController.setTextureLocation(MechaModelPreset.FRAME_TEXTURE);
    }

    // ==========================================
    // 位姿：采样与组装
    // ==========================================

    /** 是否已收到过至少一次同步采样。 */
    public boolean hasSample() {
        return anchor.hasSample();
    }

    /**
     * 取插值后的锚点位置（胶囊中心）。
     *
     * @param clientTick  当前客户端 tick
     * @param partialTick 渲染部分 tick
     * @param dest        输出缓冲
     */
    public void lerpPosition(long clientTick, float partialTick, Vector3f dest) {
        anchor.lerpPosition(clientTick, partialTick, dest);
    }

    /** 取插值后的偏航角（度）。 */
    public float lerpYaw(long clientTick, float partialTick) {
        return anchor.lerpYaw(clientTick, partialTick);
    }

    // ==========================================
    // IAnimatable<ArmsCore>
    // ==========================================

    /** {@inheritDoc} —— 持有者是装配体本身，MoLang 查询落在 {@link ArmsCore} 上。 */
    @Override
    public @NotNull ArmsCore getAnimatable() {
        return core;
    }

    /** 客户端世界；本对象只在客户端构造。 */
    @Override
    public @Nullable Level getAnimLevel() {
        return core.getLevel();
    }

    @Override
    public @NotNull ModelIndex getDefaultModelIndex() {
        return defaultModelIndex;
    }

    @Override
    public @NotNull AnimController getAnimController() {
        return animController;
    }

    @Override
    public @NotNull ModelController getModelController() {
        return modelController;
    }

    @Override
    public @NotNull Map<String, Object> getVariables() {
        return variables;
    }

    @Override
    public @NotNull SparkMolangContext<?> getMolangContext() {
        return molangContext;
    }

    /**
     * 世界位姿矩阵：把模型空间换算到世界空间。
     * <p>
     * 组装顺序为「先按 yaw 旋转、再按模型尺度缩放、最后平移到锚点并对齐底面」，
     * 与 joml 的调用序一致（{@code translate(...).scale(...).rotateY(...)} 右起作用于顶点）。
     * 偏移与缩放的取值见 {@link MechaModelPreset}。
     * <p>
     * 每次调用返回新矩阵：Spark-Core 会在拿到结果后继续 {@code mul}（{@code BonePose.kt:77-88}），
     * 复用同一个实例会被就地改写。
     */
    @Override
    public @NotNull Matrix4f getWorldPositionMatrix(@NotNull Number partialTicks) {
        float partial = partialTicks.floatValue();
        long clientTick = core.getLevel().getGameTime();
        anchor.lerpPosition(clientTick, partial, scratchPos);
        float yaw = anchor.lerpYaw(clientTick, partial);
        return new Matrix4f()
                .translate(scratchPos.x, scratchPos.y + MechaModelPreset.FRAME_RENDER_Y_OFFSET, scratchPos.z)
                .scale(MechaModelPreset.FRAME_RENDER_SCALE)
                .rotateY((float) (Math.PI - Math.toRadians(yaw)));
    }

    /**
     * LOD 用的轻量世界坐标。
     * <p>
     * 覆写是为了避开接口默认实现里的矩阵构造（{@code IAnimatable.kt:58-61}）。
     * 取最近一次采样的位置而不做插值：它只用于距离分档，插值没有意义。
     */
    @Override
    public @NotNull Vec3 getRenderPosition(@NotNull Number partialTicks) {
        Vector3f pos = anchor.current();
        return new Vec3(pos.x, pos.y, pos.z);
    }
}
