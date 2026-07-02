package io.github.sweetzonzi.arms_core.common.control;

/**
 * MechaControl 离散事件枚举。
 * <p>
 * 与 {@link MechaConditionSnapshot} 的职责边界：
 * <ul>
 *   <li>Snapshot 字段是"此刻的状态"（每帧覆盖，不保留历史）</li>
 *   <li>Event 是"发生过的事件"（写入后 latch，直到 MechaControl 消费完一帧后清除）</li>
 * </ul>
 * <p>
 * 事件不经过 Machine-Max 信号总线（ISignalBus）。Holder 通过
 * {@link MechaControlHolder#postEvent(MechaEvent)} 写入，
 * MechaControl 在每物理帧驱动状态机前消费，消费后自动清除。
 * <p>
 * 当前实现：ATTACK_PRIMARY、USE_ITEM、INTERACT、TOGGLE_DRIVE、HURT、MOUNT/DISMOUNT。
 * 战术动作（潜行/卧倒/飞扑/滑铲等）留给未来扩展。
 *
 * @author Sweetzonzi
 */
public enum MechaEvent {

    // ==========================================
    // 战斗（当前阶段实现）
    // ==========================================

    /** 主手攻击（左键），触发武器开火动画与状态转移 */
    ATTACK_PRIMARY,

    /** 副手攻击 */
    ATTACK_SECONDARY,

    /** 使用物品（拉弓、吃、喝等），触发使用动画 */
    USE_ITEM,

    /** 右键交互（开门、上载具等） */
    INTERACT,

    // ==========================================
    // 状态切换
    // ==========================================

    /** WALK ⇄ DRIVE 切换，当前实现 */
    TOGGLE_DRIVE,

    // ==========================================
    // 受击 / 控制
    // ==========================================

    /** 受到伤害，触发受击动画与短暂硬直 */
    HURT,

    /** 硬直（眩晕/控制技能），输入被锁 */
    STUN,

    // ==========================================
    // 乘降
    // ==========================================

    /** 骑上载具（成为乘客） */
    MOUNT,

    /** 从载具下来 */
    DISMOUNT,

    // ==========================================
    // 未来扩展：战术动作（暂不实现）
    // ==========================================

    // TODO: 潜行/蹲下 — 压低控制器胶囊高度，降低行走速度，减小碰撞截面
    // TOGGLE_SNEAK,

    // TODO: 卧倒 — 胶囊改为低趴碰撞体，大幅降低受击截面和移动速度
    // TOGGLE_PRONE,

    // TODO: 飞扑 — 向输入方向瞬间冲量 + 自动转入卧倒
    // DIVE,

    // TODO: 战术冲刺 — 短时爆发速度 + 降低侧向机动能力
    // TACTICAL_SPRINT,

    // TODO: 滑铲 — 冲刺中下蹲 → 沿地面滑行减速，低身位躲避投射物
    // SLIDE,
}
