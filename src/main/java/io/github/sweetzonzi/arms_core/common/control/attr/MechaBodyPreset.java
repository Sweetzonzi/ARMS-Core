package io.github.sweetzonzi.arms_core.common.control.attr;

import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;

/**
 * 控制器胶囊几何参数（临时权威取值）。
 * <p>
 * 这组值是**目前仓库中唯一的胶囊尺寸来源**：`docs/角色控制器-行走物理设计.md` §5.1
 * 「素体级参数表」只定义行走物理参数（功率、速度、摩擦等），不含胶囊尺寸与质量；
 * `mech_chassis.json` 的 `controller` 段尚未落地。因此本类就是当前权威，
 * 服务端出生点与客户端锚点都必须读它，不得各自硬编码。
 * <p>
 * `docs/ArmsCore双端权威与网络同步实现计划.md` 的阶段 4.6 会用素体定义替换本类，
 * 届时这组值需要补进上述文档 §5.1 的参数表。
 * <p>
 * 全部采用国际单位制，单位 m。
 *
 * @author Sweetzonzi
 */
public final class MechaBodyPreset {

    private MechaBodyPreset() {
        throw new UnsupportedOperationException("常量类，不可实例化");
    }

    /** 胶囊半径 (m)，即两端半球半径与圆柱段半径 */
    public static final float CAPSULE_RADIUS = 0.4f;

    /** 胶囊圆柱段高度 (m)，不含两端半球 */
    public static final float CAPSULE_HEIGHT = 1.6f;

    /** 胶囊几何中心到胶囊底面的距离 (m)：圆柱段一半 + 一个半球半径 */
    public static final float HALF_TOTAL = CAPSULE_HEIGHT / 2f + CAPSULE_RADIUS;

    /**
     * 蹲伏姿态的胶囊圆柱段高度 (m)。
     * <p>
     * 由目标全高 1.2 m 反推：{@code 1.2 − 2 × CAPSULE_RADIUS = 0.4}。
     */
    public static final float CROUCH_HEIGHT = 0.4f;

    /**
     * 卧倒姿态的胶囊圆柱段高度 (m)。
     * <p>
     * 目标全高 0.6 m 在半径 0.4 m 下不可达（{@code 0.6 − 2 × 0.4} 为负），因此取圆柱段的最小
     * 合法值，全高即 {@code 0.4 + 2 × 0.4 = 1.2 m}，与蹲伏同高——这是半径 0.4 m 下能达到的
     * 最低轮廓。要真正压到 0.6 m 需要同时缩小半径，那属于素体定义（阶段 4.6）的范围。
     * <p>
     * <b>不得取 0</b>：{@code new CapsuleCollisionShape(0.4, 0)} 会构造出零长度圆柱的退化形状，
     * Bullet 在 {@code PhysicsCharacter.setCollisionShape} 内部的原生断言会直接中止进程
     * （Windows 上表现为 JVM 退出码 {@code 0xC0000409}），那是原生 abort，Java 侧无法捕获。
     */
    public static final float PRONE_HEIGHT = 0.4f;

    /**
     * 胶囊圆柱段高度的下限 (m)。
     * <p>
     * 取一个正数而不是 0：零长度圆柱是退化形状，Bullet 会在原生断言里中止进程。
     * 这个下限同时是 {@link #capsuleHeightFor} 的钳制值，保证任何姿态都不会构造出非法形状。
     */
    public static final float MIN_CAPSULE_HEIGHT = 0.1f;

    /**
     * 创建控制器胶囊碰撞形状。
     * <p>
     * {@link CapsuleCollisionShape#getHeight()} 返回圆柱段高度、{@link CapsuleCollisionShape#getRadius()}
     * 返回半径，因此胶囊全高 = {@link #CAPSULE_HEIGHT} + 2 × {@link #CAPSULE_RADIUS}。
     *
     * @return 新的胶囊形状实例（Bullet 形状不可跨物理空间共享，每次构造新实例）
     */
    public static CapsuleCollisionShape newCapsuleShape() {
        return new CapsuleCollisionShape(CAPSULE_RADIUS, CAPSULE_HEIGHT);
    }

    /**
     * 由脚底坐标推算胶囊中心坐标。
     * <p>
     * 胶囊中心是 Bullet 幽灵体的世界变换位置，也是 `DATA_POS` 的语义；脚底坐标则与玩家
     * 实体 {@code position()} 的语义一致。两者相差 {@link #HALF_TOTAL}。
     *
     * @param feetX 脚底 X (m)
     * @param feetY 脚底 Y (m)
     * @param feetZ 脚底 Z (m)
     * @return 胶囊中心坐标，形如 {@code [feetX, feetY + HALF_TOTAL, feetZ]}
     */
    public static float[] capsuleCenterFromFeet(float feetX, float feetY, float feetZ) {
        return new float[]{feetX, feetY + HALF_TOTAL, feetZ};
    }

    // ==========================================
    // 姿态特化几何
    // ==========================================

    /**
     * 某一姿态下的胶囊圆柱段高度 (m)。
     * <p>
     * 站立与空中用 {@link #CAPSULE_HEIGHT}；蹲伏与卧倒压低轮廓以穿过低矮通道。
     * 其余姿态（水中、骑乘、布娃娃）沿用站立尺寸：它们的差别体现在运动学而不是轮廓上。
     * <p>
     * 返回值被钳制到 {@link #MIN_CAPSULE_HEIGHT}：Bullet 对退化形状会在原生断言里直接中止
     * 进程，而这种中止无法在 Java 侧捕获，因此把非法值挡在构造之前。
     *
     * @param posture 姿态
     * @return 圆柱段高度 (m)，不小于 {@link #MIN_CAPSULE_HEIGHT}
     */
    public static float capsuleHeightFor(Posture posture) {
        float height = switch (posture) {
            case CROUCH -> CROUCH_HEIGHT;
            case PRONE -> PRONE_HEIGHT;
            default -> CAPSULE_HEIGHT;
        };
        return Math.max(MIN_CAPSULE_HEIGHT, height);
    }

    /**
     * 某一姿态下的胶囊中心到胶囊底面的距离 (m)。
     * <p>
     * 等于 {@code capsuleHeightFor(posture) / 2 + CAPSULE_RADIUS}。蹲伏 / 卧倒时胶囊变矮，
     * 若维持中心不动则脚会离地，因此 KCC 换形体后由重力自然落回地面；本值用于把
     * 出生点、可视锚点盒子等按当前姿态对齐到同一基准。
     *
     * @param posture 姿态
     * @return 中心到底面的距离 (m)
     */
    public static float halfTotalFor(Posture posture) {
        return capsuleHeightFor(posture) / 2f + CAPSULE_RADIUS;
    }

    /**
     * 按姿态创建胶囊碰撞形状。
     * <p>
     * Bullet 的形状不可跨物理空间共享，且 {@code PhysicsCharacter.setCollisionShape} 会
     * 在物理线程内重新挂接形状，因此每次调用都返回新实例。
     *
     * @param posture 姿态
     * @return 新的胶囊形状实例
     */
    public static CapsuleCollisionShape newCapsuleShape(Posture posture) {
        return new CapsuleCollisionShape(CAPSULE_RADIUS, capsuleHeightFor(posture));
    }
}
