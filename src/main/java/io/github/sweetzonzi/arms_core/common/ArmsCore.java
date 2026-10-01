package io.github.sweetzonzi.arms_core.common;

import cn.solarmoon.spark_core.api.SparkLevel;
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
import lombok.Getter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 机娘核心类 —— 装配体级逻辑机甲单元。
 * <p>
 * 与 Machine-Max 的 {@code VehicleCore} 同级：由 {@link Part} 通过 {@link AbstractConnector}
 * 组装而成，实现 {@link IPartAssembly}，并额外持有角色运动控制器 {@link MechaControl}
 * 与逻辑状态机。与载具不同，它不注册为世界实体，生命周期跟随宿主装配体
 * （见 `docs/总体设计文档.md:82`）。
 * <p>
 * <b>构造前置</b>：构造器会直接从所在 Level 取物理空间
 * （{@code SparkLevel.getPhysicsLevel(level).getWorld()}）并创建 KCC。该前置在正常流程下由
 * Spark-Core 的初始化顺序保证——`PhysicsLevelApplier.kt:24-36` 在 {@code LevelEvent.Load}
 * 中设置 `PhysicsLevel` 并 `start()`，而 `ArmsCore` 总是由更晚的创建路径（宿主登录、
 * 装配体创建包、调试命令）构造。
 * <p>
 * <b>当前完成度</b>：身份、所在世界与 KCC 已接入；装配体图
 * （{@link #addPart} / {@link #getRootSubPart()} / {@link #getAttr()}）与
 * {@link #prePhysicsTick(float)} 仍为待实现成员。
 * <p>
 * <b>线程模型</b>：构造与输入发布（快照、事件）在主线程；{@link #prePhysicsTick(float)}
 * 只允许在物理线程调用，KCC 入世（`setPhysicsLocation` + `addCollisionObject`）须经
 * `SparkLevel.submitImmediateTask` 投递。
 *
 * @author Sweetzonzi
 */
public class ArmsCore implements IPartAssembly, MechaControlHolder {

    /** 装配体网络身份，构造时注入，不可变 */
    private final UUID assemblyId;

    /** 所在世界，构造时注入 */
    private final Level level;

    /** 运动控制编排器 */
    @Getter
    public final MechaControl mechaControl;

    /**
     * 构造一个机娘核心，并创建其 KCC。
     * <p>
     * KCC 的胶囊几何取自 {@link MechaBodyPreset}，用于地面射线检测的物理空间直接取自
     * 所在 Level（见类注释的构造前置）。
     * <p>
     * KCC 只被构造、尚未加入物理空间：入世需要调用方另经
     * {@code SparkLevel.submitImmediateTask} 执行 `setPhysicsLocation` 与
     * `addCollisionObject`，见 `docs/ArmsCore双端权威与网络同步实现计划.md` 阶段 1.5。
     *
     * @param level      所在世界；其物理空间必须已初始化（见类注释的构造前置）
     * @param assemblyId 装配体 UUID
     */
    public ArmsCore(Level level, UUID assemblyId) {
        this.level = level;
        this.assemblyId = assemblyId;
        MechaCharacter kcc = new MechaCharacter(
                MechaBodyPreset.newCapsuleShape(),
                SparkLevel.getPhysicsLevel(level).getWorld());
        this.mechaControl = new MechaControl(this, kcc);
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
        // TODO(阶段 1.4)：① Part 层动画混合；② mechaControl.onPhysicsStep(dt)；③ 发布 LogicStateSnapshot
        throw new UnsupportedOperationException("ArmsCore.prePhysicsTick 尚未实现（计划阶段 1.4）");
    }

    /**
     * 返回本核心的 KCC。
     * <p>
     * KCC 由 {@link MechaControl} 持有（构造本类时创建），此处只是转发，便于外部在
     * 创建路径中直接拿到它做入世与出生点设置。
     *
     * @return 本核心的运动学角色控制器，非 {@code null}
     */
    public MechaCharacter getKcc() {
        return mechaControl.getKcc();
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
}
