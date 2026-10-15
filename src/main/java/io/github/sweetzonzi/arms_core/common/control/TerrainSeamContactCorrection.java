package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.PhysicsBodyEvent;
import cn.solarmoon.spark_core.physics.body.PhysicsBodyExtensionKt;
import cn.solarmoon.spark_core.physics.terrain.PhysicsChunkSection;
import com.jme3.bullet.collision.ManifoldPoints;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;

/**
 * 地形 section 拼缝上那条幻影接触的窄相修正：把「朝上但歪」的法线摆正。
 * <p>
 * <b>为什么修接触而不是调形状。</b>地形按区块 section 切成静态刚体、节内再合并成盒
 * （{@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/terrain/PhysicsChunkSection.kt#buildCollisionShape}），
 * 窄相算在「缩掉碰撞边距的核心」上：圆柱核心的底缘压过邻块盒核心的棱时，最近的那对特征给出的
 * 法线是斜的（成因、法线式与边距阶梯见 {@code docs/角色控制器-刚体原型与引擎约束.md} §11.10）。
 * 求解器沿这条斜法线把前进动量拆成「向上一份、向后一份」，于是过缝抬起来一下、高速时会飞起来。
 * 同一条 manifold 里已经有平底对顶面那条正确的 ±Y 接触，因此摆正这条多余的接触比压形状参数直接：
 * 形状侧的旋钮只能把幻影的倾角压小，这里把它的方向换成正确的那条。
 * <p>
 * <b>门：只处理地形 section。</b>Spark-Core 的刚体 owner 就是 jme3 的 {@code userObject}
 * （{@code .../physics/body/PhysicsBodyExtension.kt#owner}），而全仓 {@code CollisionGroups.TERRAIN}
 * 只赋给地形 section 与 Create 装置（{@code .../physics/terrain/PhysicsChunkSection.kt#createPhysicsBody}
 * 与 {@code .../compat/create/CreateContraptionPhysicsHost.kt}）。装置是带任意旋转的刚体，它的面
 * 法线在世界系里本来就是斜的，因此判据只认「owner 是 {@link PhysicsChunkSection}」这一支；
 * 装置内部拼缝上同源的幻影不在本修正的范围内。
 * <p>
 * <b>判据与写法。</b>门之外只有一条几何判据：{@code 0.2 < |n.y| < 0.999}（朝上但歪）。距离不设条件
 * ——贴着的与尚未接触的一起处理，实测前者是高速残留的唯一来源；写法只改法线：深度进求解器的唯一
 * 去处是速度约束的容许接近量（实测按新法线重投影、甚至归零，四项读数逐位相同），接触点位置进
 * 求解器的只有力臂（锁转丢掉了 pitch/roll 力矩，实测改与不改的 yaw 逐位相同）。逐档读数见
 * {@code src/test/java/io/github/sweetzonzi/arms_core/common/control/TerrainSeamContactTest.java}。
 * <p>
 * <b>时机。</b>本类挂在刚体的 {@code Collide.Pre} 事件上，由窄相里的
 * {@code onContactConceived} 派发（{@code .../physics/level/PhysicsWorld.kt#onContactConceived}），
 * 因此在求解器之前；步进之后（{@code PhysicsTickListener}）改读数的做法与不修正逐位相同。游戏内
 * 该回调由物理步的 {@code contactConceived} 标志打开（{@code .../physics/level/PhysicsLevel.kt#stepPhysics}）。
 */
public final class TerrainSeamContactCorrection {

    /**
     * 法线门的下界：{@code |n.y|} 不大于本值的接触不够「朝上」，属于竖直面（墙、台阶立面）那一类，不碰。
     */
    static final float NORMAL_FLOOR = 0.2f;

    /**
     * 法线门的上界：{@code |n.y|} 不小于本值的接触已经贴着世界竖直方向，不碰。
     * <p>
     * 上下界之间就是「朝上但歪」。落在这一区间意味着 {@code n.y} 的符号与那条正确的竖直法线
     * 相同，因此摆正时沿用读到的符号即可——窄相的原始法线方向由 manifold 的 A/B 归属决定，
     * 同一对刚体的归属固定，读到的符号在幻影与正确接触之间是一致的。
     */
    static final float NORMAL_CEIL = 0.999f;

    private final Vector3f normal = new Vector3f();
    private final Vector3f rewrite = new Vector3f();

    private TerrainSeamContactCorrection() {
    }

    /**
     * 把修正挂到控制器刚体上。
     * <p>
     * 处理器存在刚体自己的事件表里
     * （{@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/util/InlineEventHandler.kt#eventHandlers}），
     * 随刚体一起回收。控制器刚体的生命周期与 {@code ArmsCore} 相同，因此这里不留解绑入口。
     *
     * @param body 控制器刚体（{@link MechaCharacter}）
     */
    public static void attachTo(PhysicsRigidBody body) {
        TerrainSeamContactCorrection correction = new TerrainSeamContactCorrection();
        PhysicsBodyExtensionKt.onCollidePre(body, correction::onContactConceived);
    }

    /** 窄相回调：只读点、只改法线，不取消这条碰撞。 */
    private boolean onContactConceived(PhysicsBodyEvent.Collide.Pre event) {
        if (isTerrainSection(event.getO2())) {
            correctNormal(event.getO1Point().getId(), normal, rewrite);
        }
        return true;
    }

    /**
     * 门：对方刚体的 owner 是地形 section。
     *
     * @param other 接触对里的另一方
     */
    static boolean isTerrainSection(PhysicsCollisionObject other) {
        return other.getUserObject() instanceof PhysicsChunkSection;
    }

    /**
     * 判据 + 改写（与门分开，便于在纯物理用例里直接调用）。
     *
     * @param pointId 窄相接触点的原生 id（{@code ManifoldPoint#getId()}）
     * @param scratch 读原始法线的临时向量
     * @param store   写回法线的临时向量
     * @return true 表示改写了这条接触
     */
    static boolean correctNormal(long pointId, Vector3f scratch, Vector3f store) {
        ManifoldPoints.getNormalWorldOnB(pointId, scratch);
        float ny = Math.abs(scratch.y);
        if (ny <= NORMAL_FLOOR || ny >= NORMAL_CEIL) {
            return false;
        }
        ManifoldPoints.setNormalWorldOnB(pointId, store.set(0f, Math.signum(scratch.y), 0f));
        return true;
    }
}
