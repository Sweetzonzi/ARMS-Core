package io.github.sweetzonzi.arms_core.common.control.state.domain;

/**
 * 水平移动模式枚举 —— 所有姿态共用同一词表，各自有不同速度倍率。
 *
 * <p>
 * Gait 管的是一维的水平移动自由度：是静止、慢速、常速还是高速；
 * 是自主移动还是被强制（硬直、击倒、重落地恢复）。
 *
 * <p>
 * 每个姿态的 gait 子状态机在进入各状态时写入两个变量：
 * <ul>
 *   <li>{@code ctrl.gait} — 本枚举值，供 MoLang 和表现层动画选择</li>
 *   <li>{@code ctrl.move_speed_modifier} — 浮点倍率（0.0~1.8），
 *       KCC 直接读取，无需关心当前 gait 名或 posture</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
public enum Gait {

    /** 静止 — 无自主水平移动 */
    IDLE("idle", 0f),

    /** 低速/精细移动 — 蹲走、匍匐、水中慢游、空中微调等 */
    CREEP("creep", 0.4f),

    /** 常速移动 — 慢跑、游泳、空中转向等 */
    JOG("jog", 1.0f),

    /** 高速移动 — 冲刺、冲刺游泳，可消耗能量 */
    SPRINT("sprint", 1.6f),

    /** 惯性滑行 — 无输入但有残余水平速度（冰面、松键减速期） */
    DRIFT("drift", 0f),

    /** 闪避 — 冲量驱动 + 短暂无敌帧（翻滚、推进器、空中 dash 等） */
    DODGE("dodge", 0f),

    /** 硬直 — 受击后禁止水平输入，重力正常 */
    STUN("stun", 0f),

    /** 重落地恢复 — 高坠落着地后的恢复动画，期间禁止输入，可被 dodge 取消 */
    HARD_LAND("hard_land", 0f);

    private final String molangName;
    private final float baseSpeedModifier;

    Gait(String molangName, float baseSpeedModifier) {
        this.molangName = molangName;
        this.baseSpeedModifier = baseSpeedModifier;
    }

    /** MoLang 表达式中使用的字符串值 */
    public String molangName() { return molangName; }

    /** stand 姿态下的基础速度倍率，各姿态建图时乘上姿态倍率 */
    public float baseSpeedModifier() { return baseSpeedModifier; }
}
