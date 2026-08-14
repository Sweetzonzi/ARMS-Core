package io.github.sweetzonzi.arms_core.common.control;

import lombok.Builder;

/**
 * 每物理帧由 {@link MechaControlHolder} 采集的条件快照。
 * <p>
 * 包含外部输入（玩家按键、视角）和宿主环境条件（水中、死亡、睡觉等）。
 * 不包括 KCC 推导状态（onGround、speed 等）—— 这些由 MechaControl 内部
 * 从 {@link MechaCharacter} 直接采集，与快照一同汇入 StateVariableContainer。
 * <p>
 * 线程模型：不可变 record。Holder 在主线程（或物理线程）采集后，通过
 * {@link MechaControlHolder#writeConditionSnapshot(MechaConditionSnapshot)} 发布；
 * MechaControl 以 volatile 引用持有，物理线程帧首读取。final 字段 + volatile
 * 引用保证安全发布，不会出现字段撕裂。
 * <p>
 * 连续状态（WASD、视角、按键按住与否）放在快照里；离散边沿
 * （jumpReleased、TOGGLE_*、DODGE 等）由 {@link MechaEvent} 单独 latch，
 * 不能依赖“最新快照恰好被物理线程看到”。
 *
 * @author Sweetzonzi
 */
@Builder
public record MechaConditionSnapshot(
        /** 前后输入，[-1, 1]，正=前进 */
        float inputForward,
        /** 左右输入，[-1, 1]，正=左移（与原版 Input.leftImpulse 约定一致） */
        float inputStrafe,
        /** 跳跃键是否按住（持续状态，用于蓄力） */
        boolean jumpPressed,
        /** 跳跃键本帧松开（单帧标记，用于 jump_charge → jump 触发） */
        boolean jumpReleased,
        /** 冲刺键是否按住 */
        boolean sprintPressed,
        /** 慢走键是否按住（精细移动，触发 creep gait） */
        boolean walkKeyPressed,
        /** 玩家是否处于蹲伏（连续状态，hold 语义：蹲下即 crouch、站直即 stand，驱动 posture stand ↔ crouch） */
        boolean sneaking,
        /** 水平朝向（度，0=南，90=西，与 Minecraft yaw 一致） */
        float viewYaw,
        /** 俯仰角（度，正=上） */
        float viewPitch,
        /** 宿主眼部位置在水中（用于游泳状态判断） */
        boolean inWater,
        /** 宿主在熔岩中 */
        boolean inLava,
        /** 宿主已死亡 */
        boolean isDead,
        /** 宿主在睡觉 */
        boolean isSleeping,
        /** 鞘翅飞行中 */
        boolean isFallFlying,
        /** 卡墙中（碰撞体积与方块重叠） */
        boolean isInWall,
        /** 宿主着火中 */
        boolean isOnFire
) {

    /** 全零/全 false 的空快照（MechaControl 构造初值）。 */
    public static final MechaConditionSnapshot EMPTY = MechaConditionSnapshot.builder().build();
}
