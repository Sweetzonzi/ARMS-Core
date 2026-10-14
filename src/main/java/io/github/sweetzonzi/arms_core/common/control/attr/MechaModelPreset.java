package io.github.sweetzonzi.arms_core.common.control.attr;

import cn.solarmoon.spark_core.animation.model.ModelIndex;
import io.github.sweetzonzi.arms_core.ARMS;
import net.minecraft.resources.ResourceLocation;

/**
 * 素体模型的标识与摆放参数（临时权威取值）。
 * <p>
 * 与 {@link MechaBodyPreset} 同级：那一个给出控制器胶囊几何，本类给出机体的渲染模型。
 * 两者都服务于「阶段 1–3 只有 {@code ArmsCore}、没有 {@code Part} 装配」这段时间，
 * 并由 `docs/ArmsCore双端权威与网络同步实现计划.md` 阶段 4.6 的素体定义（{@code mech_chassis.json}）
 * 取代。
 *
 * <h3>模型与贴图的定位规则</h3>
 * 资源放在 {@code src/main/resources/spark_modules/arms_core.builtin/} 下，启动时由
 * {@code SparkPackResourceLoader.loadModule} 整体打包成 {@code run/spark_modules/arms_core.builtin.zip}，
 * 再按 {@code <命名空间>/<模块名>/<文件路径>} 的结构读取。因此：
 * <ul>
 *   <li>模型文件 {@code arms_core/models/frame/bmpt.json} → 模型类型取模块目录下的第一段
 *       {@code frame}，模型名取文件名去 {@code .json} 后的 {@code bmpt}，
 *       即 {@link #FRAME_MODEL}（{@code SparkPackLoader.kt} 的分组规则 +
 *       {@code ModelModule.kt:83-85} 的 key 构造）；</li>
 *   <li>贴图 {@code arms_core/textures/frame/bmpt/bmpt.png} → {@link #FRAME_TEXTURE}
 *       （{@code TextureModule.kt:37-47} 的路径拼接）。</li>
 * </ul>
 * {@code ModelInstance} 默认按 {@code textures/<类型>/<模型名>.png} 推导贴图路径，
 * 即 {@code arms_core:textures/frame/bmpt.png}——该文件不存在，因此贴图必须显式设置
 * （{@code ModelController.setTextureLocation}）。贴图另放一层同名目录是内容包的既有约定，
 * 可在内容包里对照同一形态的写法：
 * {@code run/spark_modules/Machine-Max_Official_Pack.zip} 内的
 * {@code machine_max/models/part/van/van.geo.json} 与 {@code machine_max/textures/part/van/van.png}。
 *
 * <h3>动画与状态机的定位规则（与模型必须逐字对齐）</h3>
 * 动画集与动画控制器<b>不是</b>按文件名去找的，而是按<b>模型自己的索引</b>反查
 * （{@code AnimController.kt:34}：{@code OAnimationSet.getOrEmpty(model.index)}；
 * 控制器查的是 {@code model.index.location}）。Spark-Core 侧构造 key 的方式是：
 * <ul>
 *   <li>动画：{@code animations/<类型>/<目录名>/x.animation.json} → 键
 *       {@code ModelIndex(类型, 命名空间:目录名)}，<b>文件名不参与索引</b>
 *       （{@code AnimationModule.kt:34}）；</li>
 *   <li>控制器：{@code controller/<类型>/<目录名>/x.json} → 键
 *       {@code 命名空间:目录名}（{@code AnimStateModule.kt}）。</li>
 * </ul>
 * 于是「模型文件的主名」同时是「动画 / 控制器的目录名」，三者必须完全相同，否则资源能装进包、
 * 也能通过加载，但运行时一个动画都取不到。本模型的对应关系是：
 * {@code models/frame/bmpt.json} ↔ {@code animations/frame/bmpt/*.animation.json}
 * ↔ {@code controller/frame/bmpt/*.json}（最后一项尚未放置）。
 * <p>
 * 注意模型名<b>不带</b>{@code .geo} 后缀：一旦带上，上面那两个目录名就得连带写成
 * {@code bmpt.geo/}——{@code spark_core.default} 自身用的是不带后缀的写法
 * （{@code minecraft/models/entity/player.json} ↔ {@code animations/entity/player/}）。
 *
 * <h3>摆放：只有底面与胶囊底面对齐</h3>
 * 模型的原始尺寸按 1 单位 = 1/16 m 计（{@code ModelModule.kt:51}）。从
 * {@code bmpt.json} 实测：272 根骨骼、3238 个方块，方块底面（{@code origin.y − inflate}，
 * 即 {@code OVertexSet} 的底面顶点）最低 {@code −0.29828} 单位、最高 {@code 57.20427} 单位，
 * 即机体高 {@code 57.50255} 单位 ≈ {@code 3.594 m}。胶囊全高只有 {@code 2.4 m}
 * （{@link MechaBodyPreset#HALF_TOTAL} 的两倍），因此两者不可能同时对齐：
 * <b>只要求模型底面与胶囊底面对齐</b>，尺寸保持 1:1，碰撞体与外观的尺寸差是接受的现状。
 * <p>
 * 最低点是 {@code LeftFoot_1} 骨骼里一块 {@code 0.25 × 2.15 × 2} 的薄板，底面落在 {@code y = 0}
 * 之下约 {@code 1.9 cm}——这不是误差，是该模型脚部几何的真实最低点，故 {@link #FRAME_FEET_Y} 为负。
 * 上一版占位模型 gourai 的同一量是 {@code +0.66006} 单位（脚底悬在原点之上 {@code 4.1 cm}），
 * 两者符号相反，可见这个偏移不能当常数用。
 * <p>
 * 竖直方向的合成是「先缩放到模型自身尺度、再整体平移」，故偏移量在世界空间表达：
 * {@code 模型底面世界 Y = 胶囊中心 Y + FRAME_RENDER_Y_OFFSET + FRAME_RENDER_SCALE × FRAME_FEET_Y}，
 * 取 {@link #FRAME_RENDER_Y_OFFSET} 的表达式即得 {@code 胶囊中心 Y − HALF_TOTAL}，与胶囊底面重合
 * （{@link #FRAME_FEET_Y} 为负时该式同样成立，不依赖它的符号）。
 *
 * @author Sweetzonzi
 */
public final class MechaModelPreset {

    private MechaModelPreset() {
        throw new UnsupportedOperationException("常量类，不可实例化");
    }

    /** 素体机体模型（BMPT 机娘） */
    public static final ModelIndex FRAME_MODEL = new ModelIndex(
            "frame", ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "bmpt"));

    /** 素体机体贴图 */
    public static final ResourceLocation FRAME_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ARMS.MOD_ID, "textures/frame/bmpt/bmpt.png");

    /**
     * 模型渲染缩放。
     * <p>
     * 保持 1：外观按模型原始尺寸（约 {@code 3.594 m} 高）渲染，不与 {@code 2.4 m} 的胶囊等比。
     */
    public static final float FRAME_RENDER_SCALE = 1f;

    /**
     * 模型自身坐标系里最低顶点的高度 (m)。
     * <p>
     * 由 {@code bmpt.json} 全部方块的 {@code origin.y − inflate} 取最小值得 {@code −0.29828} 单位，
     * 换算为米。基岩版 geometry 的 {@code origin} 是方块的最小角、{@code inflate} 再把六个面
     * 向外推，所以底面顶点就是这两者之差（{@code OVertexSet.kt:22}）；本模型的最低方块
     * {@code inflate} 为 {@code 0}，两种口径取值相同。
     * <p>
     * <b>为负</b>：脚部几何比模型原点还低约 {@code 1.9 cm}，对齐底面时必须把它加回去。
     * 这正是本值不能照抄上一版占位模型的原因。
     */
    public static final float FRAME_FEET_Y = -0.29828f / 16f;

    /**
     * 模型渲染的竖直偏移 (m)：让模型底面落在胶囊底面上。
     * <p>
     * 取负的胶囊半高再减去缩放后的底面高度，推导见类注释。
     */
    public static final float FRAME_RENDER_Y_OFFSET =
            -MechaBodyPreset.HALF_TOTAL - FRAME_FEET_Y * FRAME_RENDER_SCALE;
}
