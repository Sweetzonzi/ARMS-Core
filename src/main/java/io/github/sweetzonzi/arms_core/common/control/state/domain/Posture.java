package io.github.sweetzonzi.arms_core.common.control.state.domain;

/**
 * 机娘姿态枚举 —— 逻辑层 posture 顶层状态机的可能取值。
 *
 * <p>
 * 姿态之间互斥，即时切换无过渡。决定 KCC 物理胶囊配置：
 * 碰撞体尺寸、跳跃开关、基础移动速度上限、步高等。
 *
 * <p>
 * 最终移动速度修正系数 = {@code posture.speedModifier() × gait.baseSpeedModifier()}，
 * 它是**控制力的缩放系数**（行走物理设计 §3.8.1）：稳态速率随之等比缩放，不是另设一道速度上限。
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

    /**
     * 空中 — 离地状态，1.8m 胶囊。
     * <p>
     * 空中倍率取中性 1.0：空中的控制力强度由 {@code MechaWalkingAttr.AIR_CONTROL}（§3.7）单独
     * 给出，姿态这里再压一次会把两者相乘（0.05 × 0.30 = 1.5%），空中彻底失去操作。
     * 空中相对地面的差异来自行走物理模型的分支（无侧向抓地、无摩擦刹车、控制力换成
     * {@code F_max × AIR_CONTROL}），不来自姿态倍率。
     */
    AIR("air", 1.0f),

    /** 水中 — 浸水状态，无地面摩擦，浮力接管 */
    WATER("water", 0.5f),

    /** 蹲伏 — 1.2m 胶囊，降低轮廓，禁用跳跃 */
    CROUCH("crouch", 0.3f),

    /** 卧倒 — 0.6m 胶囊，最低轮廓，禁用跳跃 */
    PRONE("prone", 0.1f),

    /** 骑乘 — 作为乘客乘坐载具，KCC 完全抑制，无自主移动 */
    RIDING("riding", 0f),

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
