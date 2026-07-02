package io.github.sweetzonzi.arms_core.common.control;

/**
 * 每物理帧由 {@link MechaControlHolder} 采集的条件快照。
 * <p>
 * 包含外部输入（玩家按键、视角）和宿主环境条件（水中、死亡、睡觉等）。
 * 不包括 KCC 推导状态（onGround、speed 等）—— 这些由 MechaControl 内部
 * 从 {@link MechaController} 直接采集，与快照一同汇入 StateVariableContainer。
 * <p>
 * 字段全量保留，当前不支持的条件固定返回 false / 0。
 * <p>
 * 线程：由 Holder 在主线程或物理线程采集，通过
 * {@link MechaControlHolder#writeConditionSnapshot(MechaConditionSnapshot)} 写入 MechaControl。
 *
 * @author Sweetzonzi
 */
public class MechaConditionSnapshot {

    // ==========================================
    // 玩家输入
    // ==========================================

    /** 前后输入，[-1, 1]，正=前进 */
    public float inputForward;

    /** 左右输入，[-1, 1]，正=右移 */
    public float inputStrafe;

    /** 跳跃键是否按住（持续状态，不同于离散事件） */
    public boolean jumpPressed;

    /** 冲刺键是否按住 */
    public boolean sprintPressed;

    // ==========================================
    // 视角
    // ==========================================

    /** 水平朝向（度，0=南，90=西，与 Minecraft yaw 一致） */
    public float viewYaw;

    /** 俯仰角（度，正=上） */
    public float viewPitch;

    // ==========================================
    // 宿主环境条件
    // ==========================================

    /** 宿主眼部位置在水中（用于游泳状态判断） */
    public boolean inWater;

    /** 宿主在熔岩中 */
    public boolean inLava;

    /** 宿主已死亡 */
    public boolean isDead;

    /** 宿主在睡觉 */
    public boolean isSleeping;

    /** 鞘翅飞行中 */
    public boolean isFallFlying;

    /** 卡墙中（碰撞体积与方块重叠） */
    public boolean isInWall;

    /** 宿主着火中 */
    public boolean isOnFire;

    // ==========================================
    // 工厂方法
    // ==========================================

    /**
     * 创建一个全零/全 false 的空快照。
     * Holder 在此基础上填充实际采集的值。
     */
    public static MechaConditionSnapshot createEmpty() {
        return new MechaConditionSnapshot();
    }

    /**
     * 从另一个快照复制所有字段。
     *
     * @param src 源快照
     */
    public void copyFrom(MechaConditionSnapshot src) {
        this.inputForward = src.inputForward;
        this.inputStrafe = src.inputStrafe;
        this.jumpPressed = src.jumpPressed;
        this.sprintPressed = src.sprintPressed;
        this.viewYaw = src.viewYaw;
        this.viewPitch = src.viewPitch;
        this.inWater = src.inWater;
        this.inLava = src.inLava;
        this.isDead = src.isDead;
        this.isSleeping = src.isSleeping;
        this.isFallFlying = src.isFallFlying;
        this.isInWall = src.isInWall;
        this.isOnFire = src.isOnFire;
    }
}
