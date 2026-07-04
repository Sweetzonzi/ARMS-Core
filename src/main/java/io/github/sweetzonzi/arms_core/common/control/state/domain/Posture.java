package io.github.sweetzonzi.arms_core.common.control.state.domain;

/**
 * 机娘姿态枚举 —— 逻辑层 posture 顶层状态机的可能取值。
 *
 * <p>
 * 姿态之间互斥，即时切换无过渡。决定 KCC 物理胶囊配置：
 * 碰撞体尺寸、跳跃开关、基础移动速度上限、步高等。
 *
 * <p>
 * 最终移动速度修正系数 = {@code posture.speedModifier() × gait.baseSpeedModifier()}。
 * 例如：crouch(=0.3) + creep(=0.4) → 0.12，stand(=1.0) + sprint(=1.6) → 1.6。
 * KCC 直接读取 {@code ctrl.move_speed_modifier}，不需要查询 posture 或 gait。
 *
 * <p>
 * MoLang 侧通过 {@code ctrl.posture == 'stand'} 等条件读取，
 * 本枚举的 {@link #molangName()} 提供对应的字符串值。
 *
 * @author Sweetzonzi
 */
public enum Posture {

    /** 站立 — 默认姿态，1.8m 胶囊，全行动能力 */
    STAND("stand", 1.0f),

    /** 空中 — 离地状态，1.8m 胶囊，极小地面摩擦 */
    AIR("air", 0.05f),

    /** 水中 — 浸水状态，无地面摩擦，浮力接管 */
    WATER("water", 0.5f),

    /** 蹲伏 — 1.2m 胶囊，降低轮廓，禁用跳跃 */
    CROUCH("crouch", 0.3f),

    /** 卧倒 — 0.6m 胶囊，最低轮廓，禁用跳跃 */
    PRONE("prone", 0.1f),

    /** 布娃娃 — 物理完全接管，无自主移动 */
    RAGDOLL("ragdoll", 0f);

    private final String molangName;
    private final float speedModifier;

    Posture(String molangName, float speedModifier) {
        this.molangName = molangName;
        this.speedModifier = speedModifier;
    }

    /** MoLang 表达式中使用的字符串值，如 {@code ctrl.posture == 'stand'} */
    public String molangName() { return molangName; }

    /** 姿态基准速度倍率。最终 MOVE_SPEED_MODIFIER = speedModifier × gait.baseSpeedModifier */
    public float speedModifier() { return speedModifier; }
}
