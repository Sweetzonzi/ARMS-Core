package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.SolverMode;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.ManifoldPoints;
import com.jme3.bullet.collision.PersistentManifolds;
import com.jme3.bullet.collision.shapes.CompoundCollisionShape;
import com.jme3.bullet.collision.shapes.CylinderCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地形<b>接缝</b>上的接触行为 —— 真 {@link PhysicsSpace} 上的纯物理测量，不接 Minecraft。
 * <p>
 * 待回答的问题来自一条现场表现：地面行走时偶发「离地一下」，高速（连续闪避）时会飞起来。
 * 怀疑对象是承载地形的形状不是一个整块，而是许多个盒：{@code ../Spark-Core} 的地形按
 * <b>区块 section</b> 切成静态刚体，每一节内部又由 {@code BlockMerger} 合并出的若干个盒拼成
 * （{@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/terrain/PhysicsChunkSection.kt#buildCollisionShape}
 * 与 {@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/physics/terrain/BlockMerger.kt#mergeLayer}）。
 * 两个盒的面贴合处就是一条接缝，接缝两侧各有一个凸棱。
 * <p>
 * <b>结论：接缝上会生成一条歪掉的接触法线，求解器照它把前进动量拆成「向上一份、向后一份」。</b>
 * 最坏相位上的读数（接缝在 {@code x = 0}，起始位置在一个步长内扫 {@link #PHASES} 个相位后取最坏）：
 * <pre>
 *   速度      裸圆柱 抬升 / 峰值 vy / 最长离地 / 水平顿挫   控制器 抬升 / 峰值 vy / 最长离地
 *    6 m/s    0.043 m / 0.87 m/s / 14 步 /  2.6 m/s        0.032 m / 0.84 m/s /  7 步
 *   12 m/s    0.229 m / 2.07 m/s / 29 步 /  7.5 m/s        0.231 m / 2.08 m/s / 42 步
 *   24 m/s      —         —          —         —           0.994 m / 4.54 m/s / 46 步
 *   36 m/s      —         —          —         —           1.460 m / 5.99 m/s / 33 步
 *   48 m/s      —         —          —         —           1.805 m / 8.40 m/s / 25 步
 *   25 m/s    0.573 m / 4.51 m/s / 15 步 / 18.6 m/s          —
 *   40 m/s    0.559 m / 6.40 m/s / 10 步 / 27.9 m/s          —
 * </pre>
 * 「踩上」的相位随速度变少（6 m/s 时 9/12，40 m/s 时 2/12），但踩上之后的量级随速度变大：
 * 步长越短越容易落在接缝那条窄带上，而一旦落上，被抹掉的速度正比于前进速率。
 * <p>
 * <b>踢起来那一步的全部接触。</b>下面两行是 {@link #dumpContacts} 在 12 m/s 那一档的最坏相位上
 * 打出来的原始读数（法线是 jme3 的 {@code PhysicsCollisionEvent#getNormalWorldOnB} 约定：
 * 本刚体是 {@code B}、地形是 {@code A}，因此竖直站立的接触应当读到 {@code (0, −1, 0)}）：
 * <pre>
 *   法线 (-0.000, -1.000, +0.000)，距离 -0.000132 m，冲量   0.000 N·s，接触点 x = -0.4733
 *   法线 (+0.380, -0.925, -0.000)，距离 +0.006336 m，冲量 278.187 N·s，接触点 x = +0.0224
 * </pre>
 * 第一条是平地上的正常接触：法线竖直、几乎不施加冲量。第二条是成因——接触点落在邻居盒那条棱上
 * （{@code x = +0.0224} 在棱的圆角带内），法线相对竖直歪了 {@code asin(0.380) = 22.3°}，
 * 而两个面<b>还没碰上</b>（距离 {@code +6.34 mm}，求解器允许 {@code 距离/Δt = 0.63 m/s} 的接近速度）。
 * 机体的前进速率沿这条歪法线的分量是 {@code 0.380 × 11.9 = 4.52 m/s}，超出允许量的那一段被整段抹掉：
 * {@code 70 kg × (4.52 − 0.63) ≈ 272 N·s}，与读到的 278 N·s 相符。冲量的两笔后果与观察一致：
 * 沿法线的竖直分量给出峰值 vy（{@code 3.89 × 0.925 ≈ 3.6 m/s} 量级），水平分量与它解锁的摩擦
 * （上限 {@code μ·J}，最多再抹 {@code 3.89 × 1.0 ≈ 3.9 m/s}）合起来把水平速率从 11.9 打到 6.7 m/s。
 * <p>
 * <b>三个被排除的怀疑。</b>同一片几何换拼法或换边距，读数几乎不变：
 * <ul>
 *   <li><b>不是「多个刚体」。</b>{@code 一个刚体 + 分块子形状}与{@code 逐 section 拼接}的读数逐位相同
 *       ——窄相按<b>形状</b>分对，子形状与独立刚体在那里是同一种东西</li>
 *   <li><b>不是盒的碰撞边距（棱上的圆角）。</b>边距从 0.04 压到 {@code 1e-4} 之后读数不变</li>
 *   <li><b>不是圆柱自己的底缘圆角。</b>圆柱形状的边距压到 {@code 1e-4} 之后读数同样不变</li>
 * </ul>
 * 把每个盒在水平方向外扩 0.05 m 也不去掉它：外扩只是把那条棱挪进邻盒内部，棱本身还在。
 * 对照组的「一整块盒」在四档速度、全部相位上的抬升都是 {@code +0.0000 m}。
 * <p>
 * <b>能压低它的旋钮是圆柱自己的底缘边距，但压不干净。</b>边距 0.04 → 0.10 → 0.20（平底半径
 * 0.36 → 0.30 → 0.20 m，三档平地落定脚底都是 {@code +0.00000 m}，外表面守恒）时：行走档最坏
 * 抬升 0.043 → 0.029 → 0.016 m，24 m/s 控制器 0.994 → 0.868 → 0.728 m，但命中的相位从 4/12
 * 升到 6/12（从「少而猛」变成「多而轻」）；代价是悬出棱线 0.30 m 时脚底从 {@code +0.00000 m}
 * 沉到 {@code −0.00583 m}（边距 0.10）与 {@code −0.04475 m}（边距 0.20）。
 * <p>
 * <b>为什么必须扫相位。</b>接缝是<b>离散</b>碰撞检测下的一条窄带：圆柱每物理步前进
 * {@code v·Δt}（6 m/s 时 6 cm，48 m/s 时 48 cm），它是否恰好在某一步里「踩」到那条带子，取决于
 * 步进栅格与接缝的相对相位。同一档速度换一个起始位置就可能从「弹起来」变成「什么都没发生」，
 * 因此单次过缝的读数没有意义——本类每一档都扫一整个步长的相位（{@link #PHASES} 个），
 * 报的是<b>这一档里最坏的那个相位</b>与「有多少个相位真的踩上了」。
 * <p>
 * <b>五档地形。</b>同一片几何，只换拼法，用来把「接缝几何」与「多个刚体」分开：
 * <table>
 *   <tr><th>档</th><th>构造</th><th>接缝</th><th>多个刚体</th></tr>
 *   <tr><td>{@code 一整块盒}</td><td>一个盒</td><td>无</td><td>否</td></tr>
 *   <tr><td>{@code 一个刚体 + 分块子形状}</td><td>一个刚体，子形状逐 section 铺</td><td>有</td><td>否</td></tr>
 *   <tr><td>{@code 逐 section 拼接}</td><td>每 section 一个刚体（Spark-Core 的形态）</td><td>有</td><td>是</td></tr>
 *   <tr><td>{@code 逐 section、边距 1e-4}</td><td>同上，子形状碰撞边距压到 1e-4</td><td>有，棱不带圆角</td><td>是</td></tr>
 *   <tr><td>{@code 逐 section、盒外扩 0.05}</td><td>同上，盒在水平方向外扩，把棱埋进邻盒</td><td>有，棱在邻盒内部</td><td>是</td></tr>
 *   <tr><td>{@code 逐 section、盒边距 0.10 / 0.20}</td><td>同上，子形状碰撞边距由 0.04 放大</td><td>有，棱的圆角更粗</td><td>是</td></tr>
 * </table>
 * <p>
 * <b>三组测量。</b>
 * <ol>
 *   <li><b>裸圆柱定速滑行</b>（{@link #slide}）—— 只留碰撞求解器：每步把水平速度写回目标值、
 *       垂直分量原样交给求解器，因此量到的垂直响应与水平推进解耦</li>
 *   <li><b>正式控制器过单条接缝</b>（{@link #crossing}）—— 用 {@link MechaCharacter} 先加速到
 *       指定速率（闪避次数决定），再从接缝上压过去</li>
 *   <li><b>圆柱底缘边距阶梯</b>（{@link #measureCylinderRimMarginLadder}）—— 同一根圆柱只换碰撞
 *       边距（0.04 / 0.10 / 0.20 / 0.30 / 0.40），量过缝弹起、以及「悬出棱线 0.10 与 0.30 m 时
 *       脚底沉多少」的代价。边距等于半径（0.40）那一档是阶梯的端点：核心半径缩到 0，平底消失，
 *       下半个形体是一颗半径 0.40 m 的半球，也就是把底面换成球的那一档</li>
 * </ol>
 * <p>
 * <b>本类以输出数值为主，不做基线断言。</b> 断言的只有场景前提（真的越过了接缝、真的到了那个速率），
 * 因为场景不成立时量到的数没有意义；接缝该不该抬脚、抬多高，由量出来的数定。
 * <p>
 * 与 {@code RigidBodyControllerMeasurementTest} 同类：真 {@code PhysicsSpace}、100 Hz、
 * 不接 Minecraft。差别是那片测试用的是一整块平地，本类用的是<b>拼出来的</b>地形。
 *
 * @author Sweetzonzi
 */
class TerrainSeamContactTest {

    // ═══════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════

    /** 与游戏内一致的物理步长：tps = 100 */
    private static final float DT = 1f / 100f;

    /** 所有场景的地面顶面高度 (m)：脚底读数一律相对它 */
    private static final float SURFACE_Y = 0f;

    /** 区块 section 的边长（格 = m），与 {@code PhysicsChunkSection} 的 16³ 同值 */
    private static final float SECTION_SPAN = 16f;

    /** 一节里的层数 */
    private static final int SECTION_LAYERS = 16;

    /**
     * 地形侧摩擦系数。全仓没有一处对地形刚体调用 {@code setFriction}，因此引擎用的是
     * {@code btCollisionObject} 的默认 0.5（`docs/角色控制器-刚体动力学方案.md` §7.3）。
     */
    private static final float TERRAIN_FRICTION = 0.5f;

    /** 组合摩擦 {@code μ_eff} = 角色侧 × 地形侧 */
    private static final float MU_EFF = MechaWalkingAttr.MU_NAKED * TERRAIN_FRICTION;

    /** 落定步数：先把接触建立起来再开始量 */
    private static final int SETTLE_STEPS = 40;

    /** 「离地」的判据：脚底高出落定值这么多 (m) */
    private static final float AIRBORNE_LIFT = 0.02f;

    /** 「踩上接缝」的判据：抬升超过这么多 (m) 才算一次弹起 */
    private static final float POP_LIFT = 0.01f;

    /**
     * 「求解器吃掉了水平动量」的判据 (m/s)：{@link #slide} 在第一次越过它的那一步打出全部接触。
     * 抬升读数要过一两步才越线，冲量却只在踢起来的那一步存在——按顿挫触发才打得到接触本身。
     */
    private static final float POP_JOLT = 0.5f;

    /** 每档速度扫多少个相位 */
    private static final int PHASES = 12;

    /** 行走车道所在的 section（z 方向），车道取它的中线上，因此只跨 x 方向的接缝 */
    private static final int LANE_SZ = 0;

    /** 行走车道的 z 坐标 (m)，取 section 中线 */
    private static final float LANE_Z = LANE_SZ * SECTION_SPAN + SECTION_SPAN / 2f;

    /** 地形只有 x = 0 一条接缝：两节 section 拼成 x ∈ [−16, 16] */
    private static final int SEAM_STRIP_FROM = -1;

    /** 见 {@link #SEAM_STRIP_FROM} */
    private static final int SEAM_STRIP_TO = 0;

    /** 裸圆柱的基准起点 x (m)：接缝之前 2 m（相位在它之后一个步长内散开） */
    private static final float BARE_START_X = -2f;

    /** 裸圆柱的终点 x (m)：越过它就不再走 */
    private static final float BARE_STOP_X = 3f;

    /** 控制器基准起点 x (m) */
    private static final float CROSSING_START_X = -6f;

    /** 控制器终点 x (m) */
    private static final float CROSSING_STOP_X = 4f;

    /** 接缝分窗的半宽 (m)：|x| 落在这个范围内就记进接缝的窗 */
    private static final float SEAM_WINDOW = 2f;

    /** 打印逐步轨迹的窗口半宽 (m) */
    private static final float TRACE_WINDOW = 0.8f;

    /** 驱动方向：视野偏航 −90°（面朝 +X）下按前进 */
    private static final float VIEW_YAW = -90f;

    /** 地形档「不显式设碰撞边距」的哨兵：{@code < 0} 表示用 Bullet / jme3 的默认值 0.04 m */
    private static final float DEFAULT_MARGIN = -1f;

    /**
     * 圆柱底缘边距的<b>参照档</b> (m)：Bullet / jme3 对凸体的默认碰撞边距。
     * <p>
     * ①② 两组（接缝对比与速度阶梯）固定在这一档，这样它们的读数不随现役形体取值变动；
     * 边距本身的扫描在 ③ 组（{@link #measureCylinderRimMarginLadder}）里做，现役取值见
     * {@link MechaBodyPreset#RIM_MARGIN}。
     */
    private static final float REFERENCE_RIM_MARGIN = 0.04f;

    /** 候选改法之一：每个盒在水平方向外扩这么多 (m)，把内部立面埋进邻盒 */
    private static final float OVERLAP_EXTEND = 0.05f;

    /** 候选改法之二：圆柱自身的碰撞边距（默认 0.04），压到这么小时底缘几乎不带圆角 */
    private static final float TIGHT_RIM_MARGIN = 1.0e-4f;

    /**
     * 地形侧旋钮的第一档：逐 section 的子形状碰撞边距从 Bullet 默认的 0.04 放大到 0.10。
     * 盒的边距把棱倒成半径等于边距的圆角，而窄相是在「缩掉边距的核心」上算的，因此它同时
     * 决定核心边缘离接缝多远、以及核心顶面比顶面低多少——两样都进法线的倾斜角。
     */
    private static final float LOOSE_BOX_MARGIN_10 = 0.10f;

    /** 地形侧旋钮的第二档：逐 section 的子形状碰撞边距放大到 0.20。 */
    private static final float LOOSE_BOX_MARGIN_20 = 0.20f;

    // ═══════════════════════════════════════════════
    // 地形
    // ═══════════════════════════════════════════════

    /** 四档地形拼法。 */
    private enum Terrain {
        /** 一个盒，无接缝 */
        MONOLITH("一整块盒"),
        /** 一个刚体 + 逐 section 的子形状：接缝几何与 {@link #SECTIONS} 相同，但是同一个碰撞体 */
        TILED("一个刚体+分块子形状"),
        /** 每 section 一个刚体，Spark-Core 的形态 */
        SECTIONS("逐 section 拼接"),
        /** 同 {@link #SECTIONS}，但子形状的碰撞边距压到 1e-4（盒棱不再有圆角） */
        SECTIONS_TIGHT("逐section、边距1e-4"),
        /** 同 {@link #SECTIONS}，但每个盒在水平方向外扩 {@link #OVERLAP_EXTEND}（把内部立面埋进邻盒） */
        SECTIONS_OVERLAP("逐section、盒外扩0.05"),
        /** 同 {@link #SECTIONS}，但子形状的碰撞边距放大到 {@link #LOOSE_BOX_MARGIN_10} */
        SECTIONS_LOOSE_10("逐section、盒边距0.10"),
        /** 同 {@link #SECTIONS}，但子形状的碰撞边距放大到 {@link #LOOSE_BOX_MARGIN_20} */
        SECTIONS_LOOSE_20("逐section、盒边距0.20");

        private final String label;

        Terrain(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private static void build(PhysicsSpace space, Terrain terrain) {
        switch (terrain) {
            case MONOLITH -> addMonolith(space);
            case TILED -> addTiledMonolith(space, SEAM_STRIP_FROM, SEAM_STRIP_TO);
            case SECTIONS -> addSectionStrip(space, SEAM_STRIP_FROM, SEAM_STRIP_TO, DEFAULT_MARGIN, 0f);
            case SECTIONS_TIGHT -> addSectionStrip(space, SEAM_STRIP_FROM, SEAM_STRIP_TO, 1.0e-4f, 0f);
            case SECTIONS_OVERLAP -> addSectionStrip(space, SEAM_STRIP_FROM, SEAM_STRIP_TO, DEFAULT_MARGIN,
                    OVERLAP_EXTEND);
            case SECTIONS_LOOSE_10 -> addSectionStrip(
                    space, SEAM_STRIP_FROM, SEAM_STRIP_TO, LOOSE_BOX_MARGIN_10, 0f);
            case SECTIONS_LOOSE_20 -> addSectionStrip(
                    space, SEAM_STRIP_FROM, SEAM_STRIP_TO, LOOSE_BOX_MARGIN_20, 0f);
        }
    }

    private static PhysicsSpace newSpace() {
        PhysicsSpace space = new PhysicsSpace(
                new Vector3f(-200f, -200f, -200f), new Vector3f(200f, 200f, 200f));
        space.setGravity(new Vector3f(0f, -MechaWalkingAttr.GRAVITY, 0f));
        // 解算器模式取 PhysicsWorld 构造时的那一组（../Spark-Core/.../physics/level/PhysicsWorld.kt 的
        // init 块）：接缝处必然同时出现多条接触，CacheFriendly / SIMD / Interleave 决定它们的迭代
        // 顺序与打包方式，不写清楚就不知道量到的是哪一套
        space.getSolverInfo().setMode(SolverMode.SIMD
                | SolverMode.WarmStart
                | SolverMode.CacheFriendly
                | SolverMode.CacheDirection
                | SolverMode.ArticulatedWarmStart
                | SolverMode.Interleave);
        return space;
    }

    /** 地形刚体的公共参数：静态、地形组、只与地形与角色碰撞。 */
    private static void configureTerrain(PhysicsRigidBody body) {
        body.setFriction(TERRAIN_FRICTION);
        body.setRestitution(0f);
        body.setCollisionGroup(CollisionGroups.TERRAIN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
    }

    /**
     * 一节地形刚体，形状与 {@code PhysicsChunkSection#buildCollisionShape} 对<b>整层实心</b>的产物同形：
     * 每一层合并成一个 {@code 16×16×1} 的盒，盒中心相对 section 中心为 {@code (0, levelY + 0.5 − 8, 0)}。
     * <p>
     * 顶面落在 {@code y = SURFACE_Y} 上的那一节是 {@code y ∈ [−16, 0)}，即 section 坐标 −1；
     * 与 {@code PhysicsChunkSection#createPhysicsBody} 一样，刚体位置取 section 中心
     * （{@code minBlock + 8}），因此相邻两节的盒面<b>严格共面</b>，接缝上既没有缝也没有重叠。
     *
     * @param boxMargin        子形状的碰撞边距 (m)；{@code < 0} 表示用 Bullet 的默认值 0.04
     * @param horizontalExtend  子形状在水平方向额外外扩的量 (m)；0 = 相邻两节的盒面严格共面
     */
    private static PhysicsRigidBody section(PhysicsSpace space, int sx, int sz, float boxMargin,
                                            float horizontalExtend) {
        CompoundCollisionShape compound = new CompoundCollisionShape();
        for (int levelY = 0; levelY < SECTION_LAYERS; levelY++) {
            BoxCollisionShape layer = new BoxCollisionShape(new Vector3f(
                    SECTION_SPAN / 2f + horizontalExtend, 0.5f, SECTION_SPAN / 2f + horizontalExtend));
            if (boxMargin >= 0f) {
                layer.setMargin(boxMargin);
            }
            compound.addChildShape(layer, new Vector3f(0f, levelY + 0.5f - SECTION_SPAN / 2f, 0f));
        }
        PhysicsRigidBody body = new PhysicsRigidBody(compound, 0f);
        body.setPhysicsLocation(new Vector3f(
                sx * SECTION_SPAN + SECTION_SPAN / 2f,
                -SECTION_SPAN + SECTION_SPAN / 2f,
                sz * SECTION_SPAN + SECTION_SPAN / 2f));
        configureTerrain(body);
        space.addCollisionObject(body);
        return body;
    }

    /** 沿 X 拼接的一排 section 刚体，接缝在 {@code x = 16k} 上。 */
    private static void addSectionStrip(PhysicsSpace space, int fromSx, int toSx, float boxMargin,
                                        float horizontalExtend) {
        for (int sx = fromSx; sx <= toSx; sx++) {
            section(space, sx, LANE_SZ, boxMargin, horizontalExtend);
        }
    }

    /** 一整块静态盒，顶面同样在 {@code y = SURFACE_Y}：没有接缝的对照组。 */
    private static void addMonolith(PhysicsSpace space) {
        PhysicsRigidBody body = new PhysicsRigidBody(
                new BoxCollisionShape(new Vector3f(200f, 8f, 200f)), 0f);
        body.setPhysicsLocation(new Vector3f(0f, SURFACE_Y - 8f, 0f));
        configureTerrain(body);
        space.addCollisionObject(body);
    }

    /**
     * 一个刚体 + 多个子形状，子形状与 {@link #section} 的盒一模一样：
     * 几何与逐 section 拼接<b>完全相同</b>（同样的接缝、同样的凸棱），区别只在它们是同一个
     * {@code btCollisionObject}。用来把「接缝几何」与「多个刚体」两个原因分开。
     */
    private static void addTiledMonolith(PhysicsSpace space, int fromSx, int toSx) {
        CompoundCollisionShape compound = new CompoundCollisionShape();
        for (int sx = fromSx; sx <= toSx; sx++) {
            for (int levelY = 0; levelY < SECTION_LAYERS; levelY++) {
                compound.addChildShape(
                        new BoxCollisionShape(new Vector3f(SECTION_SPAN / 2f, 0.5f, SECTION_SPAN / 2f)),
                        new Vector3f(
                                sx * SECTION_SPAN + SECTION_SPAN / 2f,
                                -SECTION_SPAN + levelY + 0.5f,
                                LANE_SZ * SECTION_SPAN + SECTION_SPAN / 2f));
            }
        }
        PhysicsRigidBody body = new PhysicsRigidBody(compound, 0f);
        body.setPhysicsLocation(new Vector3f(0f, 0f, 0f));
        configureTerrain(body);
        space.addCollisionObject(body);
    }

    /**
     * 控制器碰撞体：取 {@link MechaBodyPreset#newBodyShape()}（它已带上现役的
     * {@link MechaBodyPreset#RIM_MARGIN}），{@code rimMargin > 0} 时把碰撞边距改写成该值。
     * 因此本方法的调用方一律传显式取值，读数不随现役取值漂移。
     * <p>
     * Bullet 的碰撞边距把凸体的棱做成一圈圆角：圆柱底面因此是「平底 + 半径等于边距的圆角底缘」，
     * 平底半径 = {@link MechaBodyPreset#RADIUS} − 边距。压小边距把底缘削尖、加大边距把底缘倒圆
     * 同时缩小平底；{@link #measureCylinderRimMarginLadder} 沿这条轴量过缝弹起与悬出棱线时的沉降。
     * <p>
     * 盒与圆柱上的 {@code setMargin} 只改圆角半径、不改外表面（jme3 的 {@code setMargin} 会同步缩
     * 掉核心的隐式尺寸），因此底面为平面的形体落定脚底应当恒为 0——阶梯里那一行就是这个前提的自检。
     */
    private static CylinderCollisionShape controllerShape(float rimMargin) {
        CylinderCollisionShape shape = MechaBodyPreset.newBodyShape();
        if (rimMargin > 0f) {
            shape.setMargin(rimMargin);
        }
        return shape;
    }

    /**
     * 裸圆柱：与控制器同一个形状、质量、摩擦与姿态自由度，但不跑任何控制器逻辑。
     *
     * @param rimMargin 圆柱形状的碰撞边距 (m)；{@code > 0} 时覆盖 {@link MechaBodyPreset#RIM_MARGIN}，
     *                  {@link #TIGHT_RIM_MARGIN} 是削尖底缘那一档
     */
    private static PhysicsRigidBody bareCylinder(PhysicsSpace space, float x, float rimMargin) {
        PhysicsRigidBody body = new PhysicsRigidBody(
                controllerShape(rimMargin), MechaWalkingAttr.MASS);
        body.setPhysicsLocation(new Vector3f(x, SURFACE_Y + MechaBodyPreset.HALF_TOTAL, LANE_Z));
        body.setFriction(MechaWalkingAttr.MU_NAKED);
        body.setRestitution(0f);
        body.setLinearDamping(0f);
        body.setAngularDamping(0f);
        // 姿态自由度取控制器构造器里的那一组：锁 pitch / roll、留 yaw
        body.setAngularFactor(new Vector3f(0f, 1f, 0f));
        body.setCollisionGroup(CollisionGroups.PAWN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN);
        space.addCollisionObject(body);
        return body;
    }

    /**
     * 把刚体当前参与的所有接触打出来：世界系法线、距离（负 = 穿透）、已施加的冲量、接触点位置。
     * <p>
     * 只在踢起来的那一步调用，用来回答「求解器到底在跟谁、沿着什么方向使力」——接缝弹起是不是
     * 一条法线歪掉的接触，看这一行就知道。法线是<b>由 B 指向 A</b> 的方向（jme3 的
     * {@code PhysicsCollisionEvent#getNormalWorldOnB}），因此竖直站立的接触应当读到 {@code (0, +1, 0)}。
     */
    private static List<String> dumpContacts(PhysicsSpace space, PhysicsRigidBody body) {
        List<String> lines = new ArrayList<>();
        for (long manifoldId : space.listManifoldIds()) {
            if (PersistentManifolds.getBodyAId(manifoldId) != body.nativeId()
                    && PersistentManifolds.getBodyBId(manifoldId) != body.nativeId()) {
                continue;
            }
            for (long pointId : PersistentManifolds.listPointIds(manifoldId)) {
                Vector3f normal = new Vector3f();
                ManifoldPoints.getNormalWorldOnB(pointId, normal);
                Vector3f point = new Vector3f();
                ManifoldPoints.getPositionWorldOnB(pointId, point);
                lines.add(String.format(
                        "接触：法线 (%+.3f, %+.3f, %+.3f)，距离 %+.6f m，冲量 %.3f N·s，"
                                + "接触点 x = %+7.4f，y = %+7.4f",
                        normal.x, normal.y, normal.z, ManifoldPoints.getDistance1(pointId),
                        ManifoldPoints.getAppliedImpulse(pointId), point.x, point.y));
            }
        }
        if (lines.isEmpty()) {
            lines.add("接触：无");
        }
        return lines;
    }

    /** 脚底相对地面顶面的高度 (m)：0 = 站在顶面上，正 = 离地，负 = 压进地里。 */
    private static float feet(PhysicsRigidBody body) {
        return body.getPhysicsLocation(null).y - MechaBodyPreset.HALF_TOTAL - SURFACE_Y;
    }

    // ═══════════════════════════════════════════════
    // ① 裸圆柱定速滑行
    // ═══════════════════════════════════════════════

    /**
     * 一次定速滑行的读数。
     *
     * @param restFeet   落定后的脚底高度 (m)，后续所有抬升量都相对它
     * @param maxLift    滑行期间脚底相对落定值的最大抬升 (m)
     * @param liftX      最大抬升发生时的中心 x (m)
     * @param maxVy      滑行期间求解器给出的最大向上速度 (m/s)
     * @param minFeet    滑行期间脚底的最低值 (m)，负值即压进地里
     * @param maxJolt    每步末被求解器吃掉的水平速率的最大值 (m/s)
     * @param maxFlight  最长的一次连续离地步数
     * @param finalX     终态中心 x (m)
     * @param trace      过缝前后的逐步轨迹（{@code trace} 为真时才填）
     */
    private record Slide(float restFeet, float maxLift, float liftX, float maxVy, float minFeet,
                         float maxJolt, int maxFlight, float finalX, List<String> trace) {
    }

    /**
     * 定速滑行：每步把水平速度写回 {@code speed}、垂直分量原样留给求解器，然后步进一次。
     * <p>
     * 水平速度被写回意味着量到的垂直响应与水平推进解耦：脚底抬起来只可能来自求解器在接触上
     * 给出的法向冲量，不可能是驱动力或摩擦的副产品。代价是看不到「接缝吃掉了多少水平动量」，
     * 那个量用「步末速率与写回值之差」代替记录（{@code maxJolt}）。
     */
    private static Slide slide(Terrain terrain, float speed, float startX, float rimMargin,
                               boolean trace) {
        PhysicsSpace space = newSpace();
        build(space, terrain);
        PhysicsRigidBody body = bareCylinder(space, startX, rimMargin);

        for (int i = 0; i < SETTLE_STEPS; i++) {
            space.update(DT, 0, 0x0);
        }
        float restFeet = feet(body);

        float maxLift = 0f;
        float liftX = Float.NaN;
        float maxVy = 0f;
        float minFeet = restFeet;
        float maxJolt = 0f;
        int maxFlight = 0;
        int flight = 0;
        float previousLift = 0f;
        float previousJolt = 0f;
        List<String> traceLines = new ArrayList<>();
        Vector3f velocity = new Vector3f();
        for (int i = 0; i < 400; i++) {
            body.setLinearVelocity(velocity.set(speed, body.getLinearVelocity(null).y, 0f));
            space.update(DT, 0, 0x0);

            Vector3f position = body.getPhysicsLocation(null);
            Vector3f after = body.getLinearVelocity(null);
            float currentFeet = position.y - MechaBodyPreset.HALF_TOTAL - SURFACE_Y;
            float lift = currentFeet - restFeet;
            if (lift > maxLift) {
                maxLift = lift;
                liftX = position.x;
            }
            maxVy = Math.max(maxVy, after.y);
            minFeet = Math.min(minFeet, currentFeet);
            float jolt = Math.abs(speed - after.x);
            maxJolt = Math.max(maxJolt, jolt);
            flight = lift > AIRBORNE_LIFT ? flight + 1 : 0;
            maxFlight = Math.max(maxFlight, flight);
            if (trace && position.x >= -TRACE_WINDOW && (lift > POP_LIFT || position.x <= TRACE_WINDOW)) {
                traceLines.add(String.format("x = %+7.4f m，脚底 %+.4f m，vy %+.4f m/s，"
                                + "水平速率 %.3f m/s", position.x, lift, after.y,
                        (float) Math.sqrt(after.x * after.x + after.z * after.z)));
                // 踢起来的那一步把所有接触打出来：法线与冲量就是「求解器在干什么」的原始读数
                if (jolt > POP_JOLT && previousJolt <= POP_JOLT) {
                    traceLines.addAll(dumpContacts(space, body));
                }
            }
            previousLift = lift;
            previousJolt = jolt;
            if (position.x > BARE_STOP_X) {
                break;
            }
        }
        return new Slide(restFeet, maxLift, liftX, maxVy, minFeet, maxJolt, maxFlight,
                body.getPhysicsLocation(null).x, traceLines);
    }

    // ═══════════════════════════════════════════════
    // ② 正式控制器过单条接缝
    // ═══════════════════════════════════════════════

    /**
     * 一次过缝的读数。
     *
     * @param restFeet      落定后的脚底高度 (m)
     * @param entrySpeed    过缝前的水平速率 (m/s)
     * @param peakSpeed     全程最高水平速率 (m/s)
     * @param maxLift       全程脚底相对落定值的最大抬升 (m)
     * @param liftX         最大抬升发生时的 x (m)
     * @param windowLift    接缝窗口 {@code |x| ≤ }{@link #SEAM_WINDOW} 内的最大抬升 (m)
     * @param maxVy         求解器给出的最大向上速度 (m/s)
     * @param maxFlight     最长的一次连续离地步数
     * @param groundLosses  着地判据由真翻假的次数
     * @param stepUps       越障写入次数
     * @param trace         过缝前后的逐步轨迹
     */
    private record Crossing(float restFeet, float entrySpeed, float peakSpeed, float maxLift, float liftX,
                            float windowLift, float maxVy, int maxFlight, int groundLosses,
                            int stepUps, List<String> trace) {
    }

    /**
     * 用正式控制器过一条接缝：视野转到 +X、按住前进，先在头 {@code dodges} 个物理步里连按闪避
     * （每步一个 {@code MechaControl.DODGE_IMPULSE}，速度因此逐次叠加 {@code I/m = 12 m/s}），
     * 再让它在接缝上压过去。
     * <p>
     * 轨迹行记的是求解器<b>步进之后</b>的读数：{@code x}、脚底相对落定值、垂直速度、着地判据、
     * 本步有没有越障写入。着地兜底写在 {@code updateWalk} 里、步进之前，因此「步进后的垂直速度」
     * 是兜底之后又被求解器改过的那一份。
     */
    private static Crossing crossing(Terrain terrain, int dodges, float startX, float rimMargin,
                                     boolean trace) {
        PhysicsSpace space = newSpace();
        build(space, terrain);
        MechaCharacter body = new MechaCharacter(controllerShape(rimMargin), space);
        body.setPhysicsLocation(new Vector3f(startX, SURFACE_Y + MechaBodyPreset.HALF_TOTAL, LANE_Z));
        space.addCollisionObject(body);

        for (int i = 0; i < SETTLE_STEPS; i++) {
            body.prePhysicsTick(DT);
            space.update(DT, 0, 0x0);
        }
        float restFeet = feet(body);

        float entrySpeed = 0f;
        float peakSpeed = 0f;
        float maxLift = 0f;
        float liftX = Float.NaN;
        float windowLift = 0f;
        float maxVy = 0f;
        int maxFlight = 0;
        int flight = 0;
        int groundLosses = 0;
        int stepUps = 0;
        boolean groundedBefore = body.isOnGround();
        List<String> traceLines = new ArrayList<>();

        for (int i = 0; i < 600; i++) {
            body.setViewYaw(VIEW_YAW);
            body.setMoveIntent(1f, 0f);
            if (i < dodges) {
                body.requestDodgeImpulse(1f, 0f, MechaControl.DODGE_IMPULSE,
                        MechaControl.DODGE_INVULNERABLE_SECONDS);
            }
            body.prePhysicsTick(DT);
            space.update(DT, 0, 0x0);

            Vector3f position = body.getPhysicsLocation(null);
            Vector3f velocity = body.getLinearVelocity(null);
            float lift = feet(body) - restFeet;
            float hSpeed = body.getHSpeed();
            boolean steppedUp = body.isSteppedUpThisStep();
            boolean grounded = body.isOnGround();

            peakSpeed = Math.max(peakSpeed, hSpeed);
            if (lift > maxLift) {
                maxLift = lift;
                liftX = position.x;
            }
            if (Math.abs(position.x) <= SEAM_WINDOW && lift > windowLift) {
                windowLift = lift;
            }
            if (position.x < 0f && hSpeed > entrySpeed) {
                entrySpeed = hSpeed;
            }
            maxVy = Math.max(maxVy, velocity.y);
            flight = lift > AIRBORNE_LIFT ? flight + 1 : 0;
            maxFlight = Math.max(maxFlight, flight);
            if (groundedBefore && !grounded) {
                groundLosses++;
            }
            groundedBefore = grounded;
            if (steppedUp) {
                stepUps++;
            }
            if (trace && position.x >= -TRACE_WINDOW && (lift > POP_LIFT || position.x <= TRACE_WINDOW)) {
                traceLines.add(String.format(
                        "x = %+7.4f m，脚底 %+.4f m，vy %+.4f m/s，着地 %s，越障 %s",
                        position.x, lift, velocity.y, grounded ? "是" : "否", steppedUp ? "是" : "否"));
            }
            if (position.x > CROSSING_STOP_X) {
                break;
            }
        }
        return new Crossing(restFeet, entrySpeed, peakSpeed, maxLift, liftX, windowLift, maxVy,
                maxFlight, groundLosses, stepUps, traceLines);
    }

    // ═══════════════════════════════════════════════
    // 相位扫描
    // ═══════════════════════════════════════════════

    /**
     * 一档速度扫完所有相位之后的分布。
     *
     * @param restFeet          落定后的脚底高度 (m)：边距是否改了外表面，看这一项
     * @param worstLift         最坏那个相位的最大抬升 (m)
     * @param popping           抬升超过 {@link #POP_LIFT} 的相位数
     * @param worstVy           所有相位里求解器给出的最大向上速度 (m/s)
     * @param worstFlight       所有相位里最长的一次连续离地步数
     * @param worstJolt         裸圆柱：所有相位里被求解器吃掉的最大水平速率 (m/s)
     * @param worstStepUps      控制器：最坏相位的越障写入次数
     * @param worstGroundLosses 控制器：最坏相位的着地翻假次数
     * @param trace             最坏相位的逐步轨迹
     */
    private record Sweep(float speed, float restFeet, float worstLift, float worstLiftX, int popping,
                         float worstVy,
                         int worstFlight, float worstJolt, float worstPenetration, int worstStepUps,
                         int worstGroundLosses, List<String> trace) {
    }

    /**
     * 裸圆柱的相位扫描：同一个速度跑 {@link #PHASES} 个相位（起始位置在一个步长
     * {@code v·Δt} 内均匀散开），报最坏的那个相位，并把它的轨迹取回来。
     */
    private static Sweep sweepBare(Terrain terrain, float speed, float rimMargin) {
        float pitch = speed * DT;
        float worstLift = 0f;
        float worstLiftX = Float.NaN;
        float worstVy = 0f;
        float worstJolt = 0f;
        float worstPenetration = 0f;
        int worstFlight = 0;
        int popping = 0;
        float worstStart = BARE_START_X;
        float restFeet = 0f;
        for (int k = 0; k < PHASES; k++) {
            float startX = BARE_START_X + k * pitch / PHASES;
            Slide s = slide(terrain, speed, startX, rimMargin, false);
            restFeet = s.restFeet();
            if (s.maxLift() > POP_LIFT) {
                popping++;
            }
            if (s.maxLift() > worstLift) {
                worstLift = s.maxLift();
                worstLiftX = s.liftX();
                worstStart = startX;
            }
            worstVy = Math.max(worstVy, s.maxVy());
            worstJolt = Math.max(worstJolt, s.maxJolt());
            worstPenetration = Math.min(worstPenetration, s.minFeet() - s.restFeet());
            worstFlight = Math.max(worstFlight, s.maxFlight());
        }
        List<String> trace = worstLift > POP_LIFT
                ? slide(terrain, speed, worstStart, rimMargin, true).trace()
                : List.of();
        return new Sweep(speed, restFeet, worstLift, worstLiftX, popping, worstVy, worstFlight,
                worstJolt, worstPenetration, 0, 0, trace);
    }

    /** 正式控制器的相位扫描，与 {@link #sweepBare} 同构。 */
    private static Sweep sweepController(Terrain terrain, int dodges, float speed, float rimMargin) {
        float pitch = speed * DT;
        float worstLift = 0f;
        float worstLiftX = Float.NaN;
        float worstVy = 0f;
        int worstFlight = 0;
        int popping = 0;
        int worstStepUps = 0;
        int worstGroundLosses = 0;
        float worstStart = CROSSING_START_X;
        float restFeet = 0f;
        for (int k = 0; k < PHASES; k++) {
            float startX = CROSSING_START_X + k * pitch / PHASES;
            Crossing c = crossing(terrain, dodges, startX, rimMargin, false);
            restFeet = c.restFeet();
            if (c.maxLift() > POP_LIFT) {
                popping++;
            }
            if (c.maxLift() > worstLift) {
                worstLift = c.maxLift();
                worstLiftX = c.liftX();
                worstStart = startX;
                worstStepUps = c.stepUps();
                worstGroundLosses = c.groundLosses();
            }
            worstVy = Math.max(worstVy, c.maxVy());
            worstFlight = Math.max(worstFlight, c.maxFlight());
        }
        List<String> trace = worstLift > POP_LIFT
                ? crossing(terrain, dodges, worstStart, rimMargin, true).trace()
                : List.of();
        return new Sweep(speed, restFeet, worstLift, worstLiftX, popping, worstVy, worstFlight,
                0f, 0f, worstStepUps, worstGroundLosses, trace);
    }

    /**
     * 第 ① 组：裸圆柱定速滑行（{@code x = −2 → 3}，接缝在 0），四档地形 × 四档速度 ×
     * {@link #PHASES} 个相位。
     * <p>
     * 速度取四档：6 m/s（裸足行走的顶速）、12 m/s（一次闪避的速度增量 {@code I/m = 840/70}）、
     * 25 与 40 m/s（连续闪避叠出来的量级）。
     */
    /** 打印一档裸圆柱扫描：最坏相位的那一行数。 */
    private static void printBareSweep(float speed, Sweep sweep) {
        System.out.printf("         %6.1f m/s（步长 %.3f m）：最坏相位抬升 %+.4f m（x = %+7.3f），"
                        + "踩上的相位 %d/%d，峰值 vy %+.4f m/s，最长离地 %d 步，"
                        + "最深下压 %+.4f m，最大水平顿挫 %.3f m/s%n",
                speed, speed * DT, sweep.worstLift(), sweep.worstLiftX(), sweep.popping(),
                PHASES, sweep.worstVy(), sweep.worstFlight(), sweep.worstPenetration(),
                sweep.worstJolt());
    }

    @Test
    void measureBareCylinderAcrossASectionSeam() {
        final float[] speeds = {6f, 12f, 25f, 40f};

        System.out.printf("[接缝①] 裸圆柱定速滑行（μ_eff = %.2f，x = %.1f → %.0f m，接缝在 x = 0，"
                        + "每档扫 %d 个相位）：%n", MU_EFF, BARE_START_X, BARE_STOP_X, PHASES);
        for (Terrain terrain : Terrain.values()) {
            System.out.println("         ── " + terrain.label() + " ──");
            for (float speed : speeds) {
                printBareSweep(speed, sweepBare(terrain, speed, REFERENCE_RIM_MARGIN));
            }
        }
        System.out.println("         ── 候选改法：逐 section、圆柱底缘边距 1e-4 ──");
        for (float speed : speeds) {
            printBareSweep(speed, sweepBare(Terrain.SECTIONS, speed, TIGHT_RIM_MARGIN));
        }

        // 轨迹打两条：逐 section 拼接的 12 m/s 与 40 m/s（后者水平顿挫最大）
        for (float speed : new float[]{12f, 40f}) {
            Sweep sweep = sweepBare(Terrain.SECTIONS, speed, REFERENCE_RIM_MARGIN);
            System.out.printf("         ── 轨迹：%s / %.1f m/s / 最坏相位 ──%n",
                    Terrain.SECTIONS.label(), speed);
            for (String line : sweep.trace()) {
                System.out.println("                  " + line);
            }
        }

        // 盒外扩 0.05 的那一档读数与不扩的一样，因此把它的接触也打出来：是谁给的法线
        for (Terrain terrain : new Terrain[]{Terrain.SECTIONS_TIGHT, Terrain.SECTIONS,
                Terrain.SECTIONS_LOOSE_20, Terrain.SECTIONS_OVERLAP}) {
            Sweep sweep = sweepBare(terrain, 6f, REFERENCE_RIM_MARGIN);
            System.out.printf("         ── 轨迹：%s / 6.0 m/s / 最坏相位 ──%n", terrain.label());
            for (String line : sweep.trace()) {
                System.out.println("                  " + line);
            }
        }

        Sweep control = sweepBare(Terrain.MONOLITH, 12f, REFERENCE_RIM_MARGIN);
        assertTrue(control.worstLift() < POP_LIFT,
                "前提：一整块盒上没有接缝，任何相位都不该弹起，实际最坏 " + control.worstLift() + " m");
    }

    /**
     * 第 ② 组：正式控制器压过 x = 0 这一条接缝，四档地形 × 五档速率
     * （闪避 0～4 次 → 约 6 / 12 / 24 / 36 / 48 m/s）× {@link #PHASES} 个相位。
     * <p>
     * 常速行走档对「行走时偶尔离地一下」，连按闪避的那几档对「高速时会飞起来」。
     */
    /** 打印一档控制器扫描：最坏相位的那一行数。 */
    private static void printControllerSweep(int dodges, float speed, Sweep sweep) {
        System.out.printf("         闪避 %d 次（≈%4.1f m/s，步长 %.3f m）：最坏相位抬升 %+.4f m"
                        + "（x = %+7.3f），踩上的相位 %d/%d，峰值 vy %+.4f m/s，"
                        + "最长离地 %d 步，越障写入 %d 次，着地翻假 %d 次%n",
                dodges, speed, speed * DT, sweep.worstLift(), sweep.worstLiftX(), sweep.popping(),
                PHASES, sweep.worstVy(), sweep.worstFlight(), sweep.worstStepUps(),
                sweep.worstGroundLosses());
    }

    @Test
    void measureControllerCrossingASectionSeam() {
        final float[] speeds = {6f, 12f, 24f, 36f, 48f};

        System.out.printf("[接缝②] 正式控制器过单条接缝（μ_eff = %.2f，x = %.1f → %.0f m，接缝在 x = 0，"
                        + "每档扫 %d 个相位）：%n", MU_EFF, CROSSING_START_X, CROSSING_STOP_X, PHASES);
        for (Terrain terrain : Terrain.values()) {
            System.out.println("         ── " + terrain.label() + " ──");
            for (int dodges = 0; dodges < speeds.length; dodges++) {
                printControllerSweep(dodges, speeds[dodges],
                        sweepController(terrain, dodges, speeds[dodges], REFERENCE_RIM_MARGIN));
            }
        }
        System.out.println("         ── 候选改法：逐 section、圆柱底缘边距 1e-4 ──");
        for (int dodges = 0; dodges < speeds.length; dodges++) {
            printControllerSweep(dodges, speeds[dodges],
                    sweepController(Terrain.SECTIONS, dodges, speeds[dodges], TIGHT_RIM_MARGIN));
        }

        for (Terrain terrain : new Terrain[]{Terrain.SECTIONS, Terrain.SECTIONS_OVERLAP}) {
            for (int dodges : new int[]{0, 3}) {
                Sweep sweep = sweepController(terrain, dodges, speeds[dodges], REFERENCE_RIM_MARGIN);
                System.out.printf("         ── 轨迹：%s / 闪避 %d 次 / 最坏相位 ──%n",
                        terrain.label(), dodges);
                for (String line : sweep.trace()) {
                    System.out.println("                  " + line);
                }
            }
        }

        Sweep control = sweepController(Terrain.MONOLITH, 4, 48f, REFERENCE_RIM_MARGIN);
        assertTrue(control.worstLift() < POP_LIFT,
                "前提：一整块盒上没有接缝，48 m/s 也不该抬脚，实际最坏 " + control.worstLift() + " m");
    }

    // ═══════════════════════════════════════════════
    // ③ 圆柱底缘边距阶梯
    // ═══════════════════════════════════════════════

    /**
     * 悬出棱线的一次落定读数。
     *
     * @param restFeet 无输入落定之后的脚底高度 (m)，负值即沉到顶面以下
     * @param driftX   落定期间中心沿 X 的位移 (m)，负值即被棱线推回
     * @param grounded 落定之后的着地判据
     */
    private record EdgeRun(float restFeet, float driftX, boolean grounded) {
    }

    /**
     * 把控制器摆在一块顶面在 {@code y = SURFACE_Y}、只占 {@code x ≤ 0} 的盒上，中心放在棱线外
     * {@code overhang} 处，无输入落定，量脚底沉了多少、中心被推回多少。
     * <p>
     * 这是「把底缘倒圆」的代价一侧：底面为平面的形体悬出棱线时，接触落在平底与盒顶面之间，最低点
     * 停在顶面高度；底缘一旦被倒成半径 {@code m} 的圆角，平底半径只剩 {@code 半径 − m}，悬出量超过它
     * 之后接触只剩圆角那一段，求解器沿斜法线把机体往棱线的另一侧推，脚底随之沉下去。
     */
    private static EdgeRun edgeOverhang(float rimMargin, float overhang, int steps) {
        PhysicsSpace space = newSpace();
        PhysicsRigidBody ledge = new PhysicsRigidBody(
                new BoxCollisionShape(new Vector3f(8f, 8f, 8f)), 0f);
        ledge.setPhysicsLocation(new Vector3f(-8f, SURFACE_Y - 8f, LANE_Z));
        configureTerrain(ledge);
        space.addCollisionObject(ledge);

        MechaCharacter body = new MechaCharacter(controllerShape(rimMargin), space);
        body.setPhysicsLocation(new Vector3f(
                overhang, SURFACE_Y + MechaBodyPreset.HALF_TOTAL, LANE_Z));
        space.addCollisionObject(body);
        for (int i = 0; i < steps; i++) {
            body.prePhysicsTick(DT);
            space.update(DT, 0, 0x0);
        }
        float x = body.getPhysicsLocation(null).x;
        return new EdgeRun(feet(body), x - overhang, body.isOnGround());
    }

    /**
     * 第 ③ 组：圆柱底缘边距阶梯。同一根圆柱（半径 0.4 m、全高 2.4 m），只换碰撞边距，三处一起量：
     * <ol>
     *   <li><b>平地落定</b> —— 边距有没有连带改掉外表面（底面为平面的形体落定脚底应当恒为 0）</li>
     *   <li><b>过缝</b> —— 裸圆柱 6 / 24 m/s 与正式控制器 24 m/s（闪避 2 次）的最坏相位</li>
     *   <li><b>悬出棱线 0.10 / 0.30 m</b> —— 底缘倒圆的代价：脚底沉多少、中心被推回多少</li>
     * </ol>
     * {@link #REFERENCE_RIM_MARGIN} 一档是 Bullet / jme3 的默认 0.04 m，也是现役形体的原始取值；
     * {@link MechaBodyPreset#RADIUS} 那一档是阶梯的端点（核心半径 0，底面变成半球）。
     */
    @Test
    void measureCylinderRimMarginLadder() {
        final float[] margins = {REFERENCE_RIM_MARGIN, 0.10f, 0.20f, 0.30f, MechaBodyPreset.RADIUS};
        final float[] overhangs = {0.10f, 0.30f};
        final float shipping = MechaBodyPreset.newBodyShape().getMargin();

        System.out.printf("[接缝③] 圆柱底缘边距阶梯（半径 %.2f m、全高 %.2f m；边距把底缘倒成半径 = 边距"
                        + "的圆角，平底半径 = 半径 − 边距）：%n",
                MechaBodyPreset.RADIUS, MechaBodyPreset.BODY_HEIGHT);
        for (float margin : margins) {
            System.out.printf("         ── 边距 %.3f m（平底半径 %.3f m）%s──%n",
                    margin, MechaBodyPreset.RADIUS - margin,
                    margin == shipping ? "← 现役 MechaBodyPreset#RIM_MARGIN" : "");

            Slide flat = slide(Terrain.MONOLITH, 6f, BARE_START_X, margin, false);
            System.out.printf("         平地落定：脚底 %+.5f m（应为 0，否则外表面也被改了）%n",
                    flat.restFeet());

            printBareSweep(6f, sweepBare(Terrain.SECTIONS, 6f, margin));
            printBareSweep(24f, sweepBare(Terrain.SECTIONS, 24f, margin));
            printControllerSweep(0, 6f, sweepController(Terrain.SECTIONS, 0, 6f, margin));
            printControllerSweep(2, 24f, sweepController(Terrain.SECTIONS, 2, 24f, margin));

            for (float overhang : overhangs) {
                EdgeRun edge = edgeOverhang(margin, overhang, 100);
                System.out.printf("         悬出棱线 %.2f m：落定脚底 %+.5f m，中心位移 %+.5f m，着地 %s%n",
                        overhang, edge.restFeet(), edge.driftX(), edge.grounded() ? "是" : "否");
            }
        }
    }
}
