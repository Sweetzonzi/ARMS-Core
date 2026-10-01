package io.github.sweetzonzi.arms_core.common.control.attr;

import cn.solarmoon.spark_core.animation.model.ModelIndex;
import io.github.sweetzonzi.arms_core.ARMS;
import net.minecraft.resources.ResourceLocation;

/**
 * 素体占位模型的标识与摆放参数（临时权威取值）。
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
 *   <li>模型文件 {@code arms_core/models/frame/gourai.geo.json} → 模型类型取模块目录下的第一段
 *       {@code frame}，模型名取文件名去 {@code .json} 后的 {@code gourai.geo}，
 *       即 {@link #FRAME_MODEL}（{@code SparkPackLoader.kt} 的分组规则 +
 *       {@code ModelModule.kt:83-85} 的 key 构造）；</li>
 *   <li>贴图 {@code arms_core/textures/frame/gourai/gourai.png} → {@link #FRAME_TEXTURE}
 *       （{@code TextureModule.kt:37-47} 的路径拼接）。</li>
 * </ul>
 * {@code ModelInstance} 默认按 {@code textures/<类型>/<模型名>.png} 推导贴图路径，
 * 即 {@code arms_core:textures/frame/gourai.geo.png}——该文件不存在，因此贴图必须显式设置
 * （{@code ModelController.setTextureLocation}）。带 {@code .geo} 后缀的模型名与另放一层目录的贴图
 * 都是 Spark-Core 内容包的既有约定，可在内容包里对照同一形态的写法：
 * {@code run/spark_modules/Machine-Max_Official_Pack.zip} 内的
 * {@code machine_max/models/part/van/van.geo.json} 与 {@code machine_max/textures/part/van/van.png}。
 *
 * <h3>摆放：只有底面与胶囊底面对齐</h3>
 * 模型的原始尺寸按 1 单位 = 1/16 m 计（{@code ModelModule.kt:51}）。从
 * {@code gourai.geo.json} 实测：107 根骨骼、732 个立方体，Y 覆盖 {@code [0.66006, 94.4281]} 单位，
 * 即机体高约 {@code 5.86 m}。胶囊全高只有 {@code 2.4 m}（{@link MechaBodyPreset#HALF_TOTAL}
 * 的两倍），因此两者不可能同时对齐：<b>只要求模型底面与胶囊底面对齐</b>，尺寸保持 1:1，
 * 碰撞体与外观的尺寸差是接受的现状。
 * <p>
 * 竖直方向的合成是「先缩放到模型自身尺度、再整体平移」，故偏移量在世界空间表达：
 * {@code 模型底面世界 Y = 胶囊中心 Y + FRAME_RENDER_Y_OFFSET + FRAME_RENDER_SCALE × FRAME_FEET_Y}，
 * 取 {@link #FRAME_RENDER_Y_OFFSET} 的表达式即得 {@code 胶囊中心 Y − HALF_TOTAL}，与胶囊底面重合。
 *
 * @author Sweetzonzi
 */
public final class MechaModelPreset {

    private MechaModelPreset() {
        throw new UnsupportedOperationException("常量类，不可实例化");
    }

    /** 占位机体模型（素体帧模型） */
    public static final ModelIndex FRAME_MODEL = new ModelIndex(
            "frame", ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "gourai.geo"));

    /** 占位机体贴图 */
    public static final ResourceLocation FRAME_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ARMS.MOD_ID, "textures/frame/gourai/gourai.png");

    /**
     * 模型渲染缩放。
     * <p>
     * 保持 1：外观按模型原始尺寸（约 5.86 m 高）渲染，不与 2.4 m 的胶囊等比。
     */
    public static final float FRAME_RENDER_SCALE = 1f;

    /**
     * 模型自身坐标系里最低顶点的高度 (m)。
     * <p>
     * 由 {@code gourai.geo.json} 全部立方体的 {@code origin.y + size.y} 取最小值得 {@code 0.66006} 单位，
     * 换算为米。模型并非从 0 起步，这个 4 cm 的差就是"对齐底面"必须减掉的部分。
     */
    public static final float FRAME_FEET_Y = 0.66006f / 16f;

    /**
     * 模型渲染的竖直偏移 (m)：让模型底面落在胶囊底面上。
     * <p>
     * 取负的胶囊半高再减去缩放后的底面高度，推导见类注释。
     */
    public static final float FRAME_RENDER_Y_OFFSET =
            -MechaBodyPreset.HALF_TOTAL - FRAME_FEET_Y * FRAME_RENDER_SCALE;
}
