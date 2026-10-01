package io.github.sweetzonzi.arms_core.common.control.attr;

import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;

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
}
