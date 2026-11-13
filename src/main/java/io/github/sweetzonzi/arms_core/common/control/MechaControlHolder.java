package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.PhysicsHost;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import io.github.sweetzonzi.arms_core.common.control.attr.LegDriveAttr;
import io.github.sweetzonzi.arms_core.common.control.attr.MechAttr;
import io.github.sweetzonzi.machine_max.common.entity.MMPartEntity;
import io.github.sweetzonzi.machine_max.common.mech.ObjectManager;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.IPartAssembly;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * MechaControl 的持有者接口。
 * <p>
 * 两条产品线各自实现：
 * <ul>
 *   <li><b>机娘/外骨骼</b>：{@code ArmsCore} 实现此接口。MechaControl 为其私有字段，
 *       非子系统，UGC 不可见。</li>
 *   <li><b>可驾驶机甲</b>：{@code MechControllerSubsystem}（继承 Machine-Max BasicSubsystem）
 *       实现此接口。MechaControl 由子系统持有，兼容 SeatSubsystem → 信号总线输入路径。</li>
 * </ul>
 * <p>
 * 接口提供两个方向的契约：
 * <ol>
 *   <li><b>信息获取</b> — 让 MechaControl 和外部系统查询关键结构信息（根 SubPart、素体定义）</li>
 *   <li><b>输入写入</b> — 提供统一的输入入口，default 方法委托给内部 MechaControl</li>
 * </ol>
 * <p>
 * 构造时建立双向绑定：{@code new MechaControl(this)} → controller 持有 holder 引用，
 * holder 持有 controller 引用。
 * <p>
 * 本接口同时是控制器刚体的 {@link PhysicsHost}：{@code allPhysicsBodies} 只登记本 holder
 * 拥有的控制器刚体；装配体内的 SubPart 各自是 PhysicsHost，不在这个 map 里重复登记。
 *
 * @author Sweetzonzi
 */
public interface MechaControlHolder extends PhysicsHost {

    // ==========================================
    // 子类必须实现 — 信息获取
    // ==========================================

    /**
     * 当前持有的 MechaControl 实例，用于 default 方法委托。
     */
    MechaControl getMechaControl();

    /**
     * 本装配体在当前物理步贡献的地面驱动能力。
     *
     * <p>只返回<b>本步有效</b>的驱动源：未装配、毁坏、失去支撑等硬条件由实现方在这里剔除，
     * 控制器不再逐 Part 复查。没有驱动 Part 的实现方返回空表，控制器据此回到兜底的地面力律。
     *
     * <p>线程：物理线程，每步调用一次。
     *
     * @return 驱动能力列表，按装配顺序；无驱动能力时为空表
     */
    default List<LegDriveAttr> getDrivingAttrs() {
        return List.of();
    }

    /**
     * 根 SubPart（躯干锚点）。
     * <p>
     * 调用方可从根 SubPart 追溯到装配体整体：
     * {@code subPart.part → partNet → 全体 Part → 全体 SubPart}。
     * <p>
     * 实现方式：
     * <ul>
     *   <li>机娘/外骨骼（ArmsCore）：从 {@code mech_chassis.json → chassis.root} 指定的
     *       Part + SubPart 查找，即躯干 SubPart</li>
     *   <li>可驾驶机甲（MechControllerSubsystem）：本子系统安装所在的 SubPart</li>
     * </ul>
     */
    SubPart getRootSubPart();

    /**
     * 素体定义元数据。
     * <p>
     * 包含：控制器胶囊尺寸、质量、关节马达力曲线、默认装配方案、肢体映射等。
     * 由 {@code mech_chassis.json} 加载。
     */
    MechAttr getAttr();

    /**
     * 承载本装配体的宿主实体。
     * <p>
     * 机娘/外骨骼（{@code ArmsCore}）返回其绑定的宿主；安装在载具上的子系统没有外部宿主，
     * 返回 {@code null}。窄相否决用它判断乘客态：宿主正在乘坐的载具装配体不应与本装配体
     * 发生接触（见 {@link #getRiddenAssembly()}）。
     */
    @Nullable
    default IArmsHost getHost() {
        return null;
    }

    /**
     * 宿主当前乘坐的载具装配体（乘客态的唯一判定入口）。
     *
     * <p>解析链：{@link #getHost()} → 宿主实体 → {@code Entity#getVehicle()} → {@link MMPartEntity}
     * → 它的 {@code SubPart} 所属装配体。客户端 {@code MMPartEntity.subPart} 要等零件表恢复之后
     * 才非空，这一段空窗期用实体携带的 {@code vehicleUUID} 反查载具；{@code getVehicle()} 不是
     * MM 代理时再看一次根载具，覆盖「乘客坐在某个中间载具实体上」的情形。</p>
     *
     * <p>没有外部宿主（安装在载具上的子系统）时恒为 {@code null}：它不是乘客，也就无所谓
     * 「所乘客的载具」。</p>
     *
     * <p>线程：物理线程（{@code PhysicsLevelTickEvent.Pre} 相位）。{@code ArmsCore#host} 是 volatile
     * 字段，{@code Entity#getVehicle()} 与 MM 自己的碰撞回调读取同一类实体字段，属于同一线程约定。
     * 调用方按物理步缓存结果即可——{@code ArmsCore#prePhysicsTick} 每步解析一次并写进
     * {@code ArmsCore#riddenAssembly}，窄相否决每个接触点只读那个字段。</p>
     *
     * @return 载具装配体；没有宿主、没有乘坐、或载具不是 MM 零件代理实体时返回 {@code null}
     */
    default @Nullable IPartAssembly getRiddenAssembly() {
        IArmsHost host = getHost();
        if (host == null) return null;
        LivingEntity entity = host.getHostEntity();
        if (entity == null) return null;

        Entity vehicle = entity.getVehicle();
        if (!(vehicle instanceof MMPartEntity) && entity.getRootVehicle() instanceof MMPartEntity root) {
            vehicle = root;
        }
        if (!(vehicle instanceof MMPartEntity partEntity)) return null;
        if (partEntity.subPart != null) {
            return partEntity.subPart.part.assembly;
        }
        if (partEntity.vehicleUUID != null) {
            return ObjectManager.getVehicle(partEntity.level(), partEntity.vehicleUUID);
        }
        return null;
    }

    // ==========================================
    // 默认方法 — 输入写入
    // ==========================================

    /**
     * 每物理帧写入条件快照。
     * <p>
     * 由 Holder 在物理步开始时调用（在状态机推进之前）。
     * 快照包含外部输入和宿主环境条件；KCC 推导状态由 MechaControl 内部采集。
     *
     * @param snapshot 本帧采集的条件快照
     */
    default void writeConditionSnapshot(MechaConditionSnapshot snapshot) {
        getMechaControl().applyConditionSnapshot(snapshot);
    }

    /**
     * 投递离散事件。
     * <p>
     * 事件 latch 到帧末，由 MechaControl 在状态机推进前消费，消费后自动清除。
     * 不耗时——只是往事件队列里 add。
     *
     * @param event 事件类型
     */
    default void postEvent(MechaEvent event) {
        getMechaControl().postEvent(event);
    }

    /**
     * 批量投递离散事件。
     *
     * @param events 事件集合
     */
    default void postEvents(Collection<MechaEvent> events) {
        MechaControl ctrl = getMechaControl();
        for (MechaEvent e : events) {
            ctrl.postEvent(e);
        }
    }
}
