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
 * 当前实现：ATTACK_PRIMARY、ATTACK_SECONDARY、USE_ITEM、INTERACT、TOGGLE_DRIVE、
 * TOGGLE_PRONE、TOGGLE_FLY、DODGE、HURT、STUN、MOUNT、DISMOUNT、JUMP_RELEASE。
 * 其余战术动作（飞扑/战术冲刺/滑铲等）留给未来扩展。
 * <p>
 * <b>线上身份</b>：{@code MechaInputPayload.eventBits} 的位序取 {@link #ordinal()}，
 * 因此新常量只能追加在末尾；在中间插入会让该位置之后的位全部平移，双端事件错配。
 * `src/test/java/io/github/sweetzonzi/arms_core/network/ARMSNetworkCodecTest.java#eventOrdinalsAreAppendOnly`
 * 钉住这份顺序。
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

    /** 蹲伏 ⇄ 卧倒 切换，当前实现 */
    TOGGLE_PRONE,

    /** 飞行模式切换（仅在 air posture 下生效），当前实现 */
    TOGGLE_FLY,

    // ==========================================
    // 动作
    // ==========================================

    /** 闪避（方向由输入+视角决定，逻辑层施加冲量+无敌帧），当前实现 */
    DODGE,

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
    // 跳跃
    // ==========================================

    /**
     * 跳跃键松开（单帧边沿），跳跃助推窗口的终止信号。
     * <p>
     * 与"按住"分开表达：{@code MechaInputPayload.keyFlags} 的 {@code BIT_JUMP} 只表示当前是否按住，
     * 它随每次上行覆盖，被物理线程读到几次不确定；而"松开了"是发生过一次的事实，
     * 必须恰好被 KCC 的助推窗口更新（`MechaCharacter.java#updateJump`）消费一次，
     * 因此走事件闩锁（`MechaControl.java#pendingEventBuffer`）。
     * <p>
     * 追加在枚举末尾而不是与跳跃相关的分组里：{@code eventBits} 的位序取 {@link #ordinal()}，
     * 在中间插入会让 HURT / STUN / MOUNT / DISMOUNT 的位平移（类注释「线上身份」）。
     */
    JUMP_RELEASE,

    // ==========================================
    // 未来扩展（暂不实现）
    // ==========================================

    // TODO: 飞扑 — 向输入方向瞬间冲量 + 自动转入卧倒
    // DIVE,

    // TODO: 战术冲刺 — 短时爆发速度 + 降低侧向机动能力
    // TACTICAL_SPRINT,

    // TODO: 滑铲 — 冲刺中下蹲 → 沿地面滑行减速，低身位躲避投射物
    // SLIDE,
}
