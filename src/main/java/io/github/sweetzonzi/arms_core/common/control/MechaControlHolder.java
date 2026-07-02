package io.github.sweetzonzi.arms_core.common.control;

import io.github.sweetzonzi.machine_max.common.mech.vehicle.data.SubPart;

import java.util.Collection;

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
 *
 * @author Sweetzonzi
 */
public interface MechaControlHolder {

    // ==========================================
    // 子类必须实现 — 信息获取
    // ==========================================

    /**
     * 当前持有的 MechaControl 实例，用于 default 方法委托。
     */
    MechaControl getMechaControl();

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
    MechChassisDefinition getChassis();

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
