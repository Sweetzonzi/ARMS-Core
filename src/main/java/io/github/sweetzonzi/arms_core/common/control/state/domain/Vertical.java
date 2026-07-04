package io.github.sweetzonzi.arms_core.common.control.state.domain;

/**
 * 垂直模式枚举 —— 区分在地面、蓄力起跳、空中摔落、滑翔、悬浮、飞行等持久状态。
 *
 * <p>
 * 仅 stand 和 air posture 拥有 vertical 子状态机。
 * stand 的 vertical 仅含地面和跳跃蓄力；离地后姿势切 air，垂直由 air_vert 接管。
 *
 * <p>
 * 瞬时事件（跳跃施加冲量、二段跳）不进状态机，直接在 KCC 上调用。
 *
 * @author Sweetzonzi
 */
public enum Vertical {

    /** 着地 — 正常站立/蹲伏/卧倒状态 */
    GROUND("ground"),

    /** 跳跃蓄力 — 按住跳跃键期间，降低移动速度（准备起跳） */
    JUMP_CHARGE("jump_charge"),

    /** 自然摔落 — 无动力的空中上升或下落（全重力），统一为 fall */
    FALL("fall"),

    /** 鞘翅滑翔 — 减重力 + 俯仰→升力转换，需要水平速度 */
    GLIDE("glide"),

    /** 推进悬浮 — 持续推力平衡重力，可水平移动，消耗能量 */
    HOVER("hover"),

    /** 创造飞行 — 6DOF 自由垂直控制 */
    FLY("fly");

    private final String molangName;

    Vertical(String molangName) {
        this.molangName = molangName;
    }

    /** MoLang 表达式中使用的字符串值 */
    public String molangName() { return molangName; }
}
