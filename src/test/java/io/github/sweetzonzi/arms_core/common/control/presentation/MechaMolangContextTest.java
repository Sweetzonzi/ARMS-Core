package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.molang.MolangContextRegistry;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.IN_WATER_POSTURE;
import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.IS_CROUCHING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MechaMolangContext} 的查询面单测：发布值能否被 {@code q.*} 读回、未发布时是否读 0、
 * 内容包原式在除零点上是否仍有限、逻辑状态两项是否跟着变量容器走。
 * <p>
 * 求值走生产同一条路（{@code MolangContextRegistry#compile}）：它按上下文类建引擎、
 * 用无参构造造原型来扫 {@code @QueryBinding}，因此本用例同时守住「类与无参构造必须是 public」
 * 这条硬要求。
 * <p>
 * 全量内容包的有限性判据在 {@link MechaPackMolangTest}。
 *
 * @author Sweetzonzi
 */
class MechaMolangContextTest {

    /** 内容包里 jump 动画左臂旋转分量的原文（{@code bmpt_main.animation.json}，vertical_speed → 本上下文） */
    private static final String JUMP_LIMB_EXPRESSION =
            "-30+(q.vertical_speed/math.abs(q.vertical_speed)*(1-math.exp(-0.1*math.abs(q.vertical_speed)))*30)";

    /** 本上下文提供的全部无参查询 */
    private static final List<String> ALL_QUERIES = List.of(
            "q.ground_speed", "q.vertical_speed", "q.body_y_rotation", "q.yaw_speed",
            "q.head_x_rotation", "q.head_y_rotation", "q.life_time", "q.is_in_water", "q.is_sneaking");

    private static double eval(MechaMolangContext context, String expression) {
        return MolangContextRegistry.compile(expression, context).evaluate(context);
    }

    // ==========================================
    // 第一组：两个运动量
    // ==========================================

    @Test
    void motionQueriesReadBackPublishedValues() {
        MechaMolangContext context = new MechaMolangContext();
        context.setMotion(4.5f, -2.5f);

        assertEquals(4.5, eval(context, "q.ground_speed"), 1e-9);
        assertEquals(-2.5, eval(context, "q.vertical_speed"), 1e-9);
        assertEquals(4.5, eval(context, "query.ground_speed"), 1e-9, "全名与简写必须同源");
    }

    @Test
    void unpublishedQueriesReadZero() {
        MechaMolangContext context = new MechaMolangContext();

        for (String query : ALL_QUERIES) {
            assertEquals(0.0, eval(context, query), 1e-9, query + " 未发布时必须是常量 0");
        }
        assertEquals(0.0, eval(context, "q.position_delta(0)"), 1e-9);
        assertEquals(0.0, eval(context, "q.position_delta(2)"), 1e-9);
    }

    /**
     * 缺陷现象的原式：{@code vertical_speed} 为 0 时分子分母同时为 0。
     * <p>
     * 除零口径由 Spark-Core 的编译路径修正给出（返回 0），本仓库只负责把这一路喂成真实速度；
     * 这里两个取值都必须落在有限数上，{@code NaN} 会让整条骨骼子树不绘制。
     */
    @Test
    void contentPackLimbExpressionStaysFinite() {
        MechaMolangContext context = new MechaMolangContext();

        context.setMotion(0f, 0f);
        double atRest = eval(context, JUMP_LIMB_EXPRESSION);
        assertTrue(Double.isFinite(atRest), "垂直速度为 0 时不得产出 NaN/Infinity: " + atRest);
        assertEquals(-30.0, atRest, 1e-9);

        context.setMotion(0f, 3f);
        double rising = eval(context, JUMP_LIMB_EXPRESSION);
        assertTrue(Double.isFinite(rising), "垂直速度为 3 时必须有限: " + rising);
        assertEquals(-22.224546620451537, rising, 1e-6);
    }

    /**
     * 内容包里以一元负号开头的调用：{@code -math.clamp(query.ground_speed*4,0,40)} 是 jump / fly
     * 手臂旋转的分量之一（{@code bmpt_main.animation.json:2861} 等 6 处），驱动随水平速度的摆臂。
     * <p>
     * 解析器对「负号直接贴住成员函数调用」这一形状错位时，这两条断言都得到 0。
     */
    @Test
    void negatedClampCurveKeepsItsValue() {
        MechaMolangContext context = new MechaMolangContext();

        context.setMotion(10f, 0f);
        assertEquals(-40.0, eval(context, "-math.clamp(q.ground_speed*4,0,40)"), 1e-9,
                "静止以外的速度必须真的摆臂，不能恒为 0");

        context.setMotion(0f, 0f);
        assertEquals(0.0, eval(context, "-math.clamp(q.ground_speed*4,0,40)"), 1e-9,
                "速度为零时曲线上端点为 0");
    }

    // ==========================================
    // 第二组：朝向、位移、寿命
    // ==========================================

    @Test
    void facingAndLifeTimeQueriesReadBackPublishedValues() {
        MechaMolangContext context = new MechaMolangContext();
        context.setFacing(123.5f, 240f);
        context.setHeadRotation(-17.5f, 31.25f);
        context.setPositionDelta(0.25f, -0.1f, -0.05f);
        context.setLifeTime(12.5f);

        assertEquals(123.5, eval(context, "q.body_y_rotation"), 1e-6);
        assertEquals(240.0, eval(context, "q.yaw_speed"), 1e-6);
        assertEquals(-17.5, eval(context, "q.head_x_rotation"), 1e-6);
        assertEquals(31.25, eval(context, "q.head_y_rotation"), 1e-6);
        // 发布的是 float 字段，比较容差取 float 精度而不是 double
        assertEquals(0.25, eval(context, "q.position_delta(0)"), 1e-7);
        assertEquals(-0.1, eval(context, "q.position_delta(1)"), 1e-7);
        assertEquals(-0.05, eval(context, "q.position_delta(2)"), 1e-7);
        assertEquals(0.0, eval(context, "q.position_delta(9)"), 1e-9, "越界轴读 0");
        assertEquals(12.5, eval(context, "q.life_time"), 1e-6);
    }

    // ==========================================
    // 第二组：逻辑状态两项
    // ==========================================

    @Test
    void postureFlagsFollowTheInjectedVariableContainer() {
        MechaMolangContext context = new MechaMolangContext();
        assertEquals(0.0, eval(context, "q.is_in_water"), 1e-9, "未注入容器时读 0");
        assertEquals(0.0, eval(context, "q.is_sneaking"), 1e-9);

        StateVariableContainer variables = new StateVariableContainer();
        context.attachVariables(variables);
        assertEquals(0.0, eval(context, "q.is_in_water"), 1e-9, "同一容器里两项默认都是 false");

        variables.set(IN_WATER_POSTURE, true);
        variables.set(IS_CROUCHING, true);
        assertEquals(1.0, eval(context, "q.is_in_water"), 1e-9);
        assertEquals(1.0, eval(context, "q.is_sneaking"), 1e-9);

        variables.set(IN_WATER_POSTURE, false);
        assertEquals(0.0, eval(context, "q.is_in_water"), 1e-9);
        assertEquals(1.0, eval(context, "q.is_sneaking"), 1e-9, "两项互不影响");
    }

    /**
     * 内容包里 {@code q.is_sneaking} 出现在布尔上下文里（{@code !q.is_sneaking} 与
     * {@code q.is_sneaking && …}）。绑定之后这两处必须编译得出可加载的类——未绑定时
     * 兜底分支发的是裸 {@code double}，与期待的 {@code int} 不匹配，类会以
     * {@code VerifyError: Bad type on operand stack} 拒绝加载。
     */
    @Test
    void booleanContextsCompileWithTheBoundQuery() {
        MechaMolangContext context = new MechaMolangContext();
        StateVariableContainer variables = new StateVariableContainer();
        context.attachVariables(variables);

        variables.set(IS_CROUCHING, true);
        assertEquals(1.0, eval(context, "(!q.is_sneaking)?0:1"), 1e-9);
        assertEquals(1.0, eval(context, "q.is_sneaking&&q.ground_speed==0?1:0"), 1e-9);

        variables.set(IS_CROUCHING, false);
        assertEquals(0.0, eval(context, "(!q.is_sneaking)?0:1"), 1e-9);
    }
}
