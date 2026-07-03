package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.state_machine.graph.StateCondition;
import cn.solarmoon.spark_core.state_machine.graph.StateGraphController;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableKey;
import com.mojang.serialization.MapCodec;

import java.util.function.Predicate;

/**
 * Java 友好的状态变量条件实现。
 * <p>
 * 与 Spark-Core 的 {@code CheckVariableCondition} 功能等价，
 * 但使用 Java {@link Predicate} 替代 Kotlin lambda，
 * 避免跨语言函数类型转换问题。
 * <p>
 * 仅用于硬编码状态机，不参与 JSON 序列化（codec 使用 unit 占位）。
 *
 * @author Sweetzonzi
 */
public class SimpleVariableCondition implements StateCondition {

    private final Predicate<StateVariableContainer> predicate;

    public SimpleVariableCondition(Predicate<StateVariableContainer> predicate) {
        this.predicate = predicate;
    }

    @Override
    public boolean check(StateGraphController controller) {
        return predicate.test(controller.getVariables());
    }

    @Override
    @SuppressWarnings("unchecked")
    public MapCodec<? extends StateCondition> getCodec() {
        return MapCodec.unit(this);
    }

    // ═══════════════════════════════════════════════
    // 工厂方法——语义化条件构造
    // ═══════════════════════════════════════════════

    /** 布尔变量为 true */
    public static StateCondition isTrue(StateVariableKey<Boolean> key) {
        return new SimpleVariableCondition(v -> v.get(key));
    }

    /** 布尔变量为 false */
    public static StateCondition isFalse(StateVariableKey<Boolean> key) {
        return new SimpleVariableCondition(v -> !v.get(key));
    }

    /** 字符串变量等于指定值 */
    public static StateCondition strEq(StateVariableKey<String> key, String value) {
        return new SimpleVariableCondition(v -> value.equals(v.get(key)));
    }

    /** 字符串变量不等于指定值 */
    public static StateCondition strNe(StateVariableKey<String> key, String value) {
        return new SimpleVariableCondition(v -> !value.equals(v.get(key)));
    }

    /** 浮点变量大于阈值 */
    public static StateCondition floatGt(StateVariableKey<Float> key, float threshold) {
        return new SimpleVariableCondition(v -> v.get(key) > threshold);
    }

    /** 浮点变量小于等于阈值 */
    public static StateCondition floatLe(StateVariableKey<Float> key, float threshold) {
        return new SimpleVariableCondition(v -> v.get(key) <= threshold);
    }
}
