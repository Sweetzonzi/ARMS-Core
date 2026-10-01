package io.github.sweetzonzi.arms_core.common;

import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.common.control.MechaControlHolder;
import io.github.sweetzonzi.arms_core.common.control.attr.MechAttr;
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
 * <b>当前完成度</b>：身份（{@link #getAssemblyId()}）与所在世界（{@link #getLevel()}）已接入；
 * 装配体图（{@link #addPart} / {@link #getRootSubPart()} / {@link #getAttr()}）与
 * {@link #prePhysicsTick()} 仍为待实现成员。
 * <p>
 * <b>线程模型</b>：{@link #mechaControl} 的输入发布（快照与事件）可在主线程调用，
 * 物理推进 {@link #prePhysicsTick()} 只允许在物理线程调用。
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
     * 构造一个未接入物理世界的机娘核心。
     * <p>
     * {@link #mechaControl} 内部的 KCC 为 {@code null}，因此本实例<b>不可被驱动</b>：
     * 调用 {@link #prePhysicsTick()} 会在 {@code MechaControl.writeStateInputs} 读取 KCC 状态时
     * 抛 {@code NullPointerException}。可安全使用的部分只有身份与本类自身的成员。
     *
     * @param level      所在世界
     * @param assemblyId 装配体 UUID
     */
    public ArmsCore(Level level, UUID assemblyId) {
        this(level, assemblyId, null);
    }

    /**
     * 构造一个机娘核心。
     *
     * @param level        所在世界
     * @param assemblyId   装配体 UUID
     * @param mechaCharacter 已初始化并加入物理世界的 KCC；传 {@code null} 时本实例不可被驱动
     *                       （见 {@link #ArmsCore(Level, UUID)}）
     */
    public ArmsCore(Level level, UUID assemblyId, @Nullable MechaCharacter mechaCharacter) {
        this.level = level;
        this.assemblyId = assemblyId;
        this.mechaControl = new MechaControl(this, mechaCharacter);
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
     *       {@code body_root} 骨骼位姿，而该位姿由 Part 的物理步产出</li>
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
