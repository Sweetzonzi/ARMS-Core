package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.molang.MolangContextRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容包 molang 全量体检：4 个动画文件里取值字段与 timeline 上的每条表达式都要能编译求值，
 * 且结果必须是有限数。
 * <p>
 * 它是本次「空中姿态四肢不绘制」缺陷的回归闸门，也是上游两条修正在本内容包上的验收：
 * <ul>
 *   <li>编译路径除零口径——
 *       不修时 {@code query.vertical_speed/math.abs(query.vertical_speed)} 一类式子得到 {@code NaN}；</li>
 *   <li>多级左值赋值与未实现算子的编译产物——不修时 {@code v.a.b=1;} 抛出
 *       {@code VerifyError}；</li>
 *   <li>查询面绑定——不绑定时布尔上下文里的
 *       {@code q.is_sneaking} 产出非法字节码。</li>
 * </ul>
 * 判据是「一条都不抛、一条都不出现 NaN/Infinity」，另有表达式总数下限：JSON 结构一旦变化
 * 导致遍历取不到东西，本用例应失败而不是静默通过。
 *
 * @author Sweetzonzi
 */
class MechaPackMolangTest {

    /** 内容包里的动画目录，与 {@code MechaModelPreset} 的定位规则一致 */
    private static final String PACK_DIRECTORY =
            "spark_modules/arms_core.builtin/arms_core/animations/frame/bmpt/";

    /** 目录下全部动画文件；新增文件必须同时加进来，否则全量判据会漏掉它 */
    private static final List<String> ANIMATION_FILES = List.of(
            "bmpt_main.animation.json",
            "bmpt_tac.animation.json",
            "bmpt_extra.animation.json",
            "bmpt_carryon.animation.json");

    /** 取值字段名 */
    private static final List<String> VALUE_FIELDS = List.of("rotation", "position", "scale");

    /**
     * 元数据键：值不是 molang 而是 {@code "catmullrom"} 这类插值模式名，
     * 遍历时必须跳过，否则会被当成表达式去求值。
     */
    private static final String INTERPOLATION_MODE_KEY = "lerp_mode";

    /** 表达式总数下限（当前内容包实测 2323 条） */
    private static final int MINIMUM_EXPRESSION_COUNT = 2323;

    @Test
    void everyPackExpressionIsFiniteAndDoesNotThrow() throws IOException {
        List<String> expressions = collectExpressions();
        assertTrue(expressions.size() >= MINIMUM_EXPRESSION_COUNT,
                "只取到 " + expressions.size() + " 条表达式，低于下限 " + MINIMUM_EXPRESSION_COUNT
                        + "：遍历规则与内容包结构已经不匹配");

        MechaMolangContext context = new MechaMolangContext();
        List<String> failures = new ArrayList<>();

        for (String expression : expressions) {
            try {
                double value = MolangContextRegistry.compile(expression, context).evaluate(context);
                if (!Double.isFinite(value)) {
                    failures.add(expression + " -> " + value);
                }
            } catch (Throwable failure) {
                failures.add(expression + " -> " + failure);
            }
        }

        assertEquals(List.of(), failures,
                "内容包里有 " + failures.size() + " 条表达式求值失败或不是有限数");
    }

    // ==========================================
    // 遍历
    // ==========================================

    /**
     * 取出全部 molang 字面量：每个动画的 {@code bones.*.{rotation,position,scale}} 与 {@code timeline}。
     * <p>
     * 取值字段可以是单值、数组，也可以按时间点分档（每档可能是数组，也可能是带
     * {@code pre}/{@code post} 的对象），因此这里统一递归到字符串叶子。
     */
    private static List<String> collectExpressions() throws IOException {
        List<String> expressions = new ArrayList<>();
        for (String file : ANIMATION_FILES) {
            JsonObject root = JsonParser.parseString(readResource(PACK_DIRECTORY + file)).getAsJsonObject();
            JsonObject animations = root.getAsJsonObject("animations");
            if (animations == null) continue;

            for (Map.Entry<String, JsonElement> animation : animations.entrySet()) {
                collectFromAnimation(animation.getValue().getAsJsonObject(), expressions);
            }
        }
        return expressions;
    }

    private static void collectFromAnimation(JsonObject animation, List<String> out) {
        JsonObject bones = animation.getAsJsonObject("bones");
        if (bones != null) {
            for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
                JsonElement boneBody = bone.getValue();
                if (!boneBody.isJsonObject()) continue;
                for (String field : VALUE_FIELDS) {
                    JsonElement value = boneBody.getAsJsonObject().get(field);
                    if (value != null) collectStrings(value, out);
                }
            }
        }
        JsonElement timeline = animation.get("timeline");
        if (timeline != null) collectStrings(timeline, out);
    }

    private static void collectStrings(JsonElement node, List<String> out) {
        if (node.isJsonPrimitive()) {
            if (node.getAsJsonPrimitive().isString()) {
                out.add(node.getAsString());
            }
            return;
        }
        if (node.isJsonArray()) {
            JsonArray array = node.getAsJsonArray();
            for (JsonElement element : array) collectStrings(element, out);
            return;
        }
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                if (INTERPOLATION_MODE_KEY.equals(entry.getKey())) continue;
                collectStrings(entry.getValue(), out);
            }
        }
    }

    private static String readResource(String path) throws IOException {
        ClassLoader loader = MechaPackMolangTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("测试类路径上找不到资源 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
