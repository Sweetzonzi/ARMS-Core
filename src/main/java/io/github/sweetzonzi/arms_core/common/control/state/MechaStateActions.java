package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.state_machine.graph.StateAction;
import cn.solarmoon.spark_core.state_machine.graph.StateGraphController;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableKey;
import com.mojang.serialization.MapCodec;

import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;

/**
 * ARMS-Core 逻辑层状态动作集合。
 * <p>
 * 每个动作在状态机的 onEntry/onExit 时执行，负责：
 * <ul>
 *   <li>写入逻辑层产出变量（POSTURE / GAIT / VERTICAL 字符串）</li>
 *   <li>写入表现层便利布尔（IS_WALKING / IS_JUMPING 等）</li>
 * </ul>
 * <p>
 * 便利布尔采用 one-hot 写入策略——进入某状态时，该域的全体布尔键被覆盖写入，
 * 仅匹配值为 true，其余全 false。表现层动画条件可直接查询布尔键，
 * 无需重复拼接 GAIT=="walk" && HAS_INPUT 等复合条件。
 *
 * @author Sweetzonzi
 */
public final class MechaStateActions {

    private MechaStateActions() {}

    // ═══════════════════════════════════════════════
    // 通用单键写入动作
    // ═══════════════════════════════════════════════

    /** 写入字符串变量 */
    public static StateAction writeVar(StateVariableKey<String> key, String value) {
        return new SimpleWriteAction<>(key, value);
    }

    /** 写入布尔变量 */
    public static StateAction writeBool(StateVariableKey<Boolean> key, boolean value) {
        return new SimpleWriteAction<>(key, value);
    }

    /** 写入浮点变量 */
    public static StateAction writeFloat(StateVariableKey<Float> key, float value) {
        return new SimpleWriteAction<>(key, value);
    }

    /**
     * 泛型单键写入动作实现。
     */
    private static final class SimpleWriteAction<T> implements StateAction {
        private final StateVariableKey<T> key;
        private final T value;

        SimpleWriteAction(StateVariableKey<T> key, T value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void execute(StateGraphController controller) {
            controller.getVariables().set(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public MapCodec<? extends StateAction> getCodec() {
            return MapCodec.unit(this);
        }

        @Override
        public String toString() {
            return "Write(" + key.getId() + "=" + value + ")";
        }
    }

    // ═══════════════════════════════════════════════
    // 便利布尔批量写入动作（one-hot）
    // ═══════════════════════════════════════════════

    /**
     * 写入全部 posture 便利布尔（one-hot）。
     * <p>
     * 同时写入 POSTURE 字符串 + 6 个布尔键，
     * 仅匹配 {@code posture} 的键为 true，其余 false。
     *
     * @param posture 当前姿态名："stand" / "air" / "water" / "crouch" / "prone" / "ragdoll"
     */
    public static StateAction postureFlags(String posture) {
        return new BatchWriteAction(Map.of(
                POSTURE, posture,
                IS_STANDING, "stand".equals(posture),
                IS_AIRBORNE, "air".equals(posture),
                IN_WATER_POSTURE, "water".equals(posture),
                IS_CROUCHING, "crouch".equals(posture),
                IS_PRONE, "prone".equals(posture),
                IS_RAGDOLLED, "ragdoll".equals(posture)
        ));
    }

    /**
     * 写入全部 gait 便利布尔（one-hot）。
     * <p>
     * 同时写入 GAIT 字符串 + 所有 gait 布尔键 + IS_MOVING 派生键。
     *
     * @param gait 当前步态名
     */
    public static StateAction gaitFlags(String gait) {
        boolean moving = !"idle".equals(gait);
        return new BatchWriteAction(Map.of(
                GAIT, gait,
                IS_IDLE, "idle".equals(gait),
                IS_WALKING, "walk".equals(gait),
                IS_RUNNING, "run".equals(gait),
                IS_SPRINTING, "sprint".equals(gait),
                IS_DODGING, "dodge".equals(gait),
                IS_CRAWLING, "crawl".equals(gait),
                IS_SWIMMING, "swim".equals(gait),
                IS_MOVING, moving
        ));
    }

    /**
     * 写入全部 vertical 便利布尔（one-hot）。
     * <p>
     * 同时写入 VERTICAL 字符串 + 所有 vertical 布尔键。
     *
     * @param vertical 当前垂直状态名
     */
    public static StateAction verticalFlags(String vertical) {
        return new BatchWriteAction(Map.of(
                VERTICAL, vertical,
                IS_GROUNDED, "ground".equals(vertical),
                IS_JUMP_CHARGING, "jump_charge".equals(vertical),
                IS_JUMPING, "jump".equals(vertical),
                IS_FALLING, "fall".equals(vertical),
                IS_FLYING, "fly".equals(vertical)
        ));
    }

    /**
     * 批量写入动作——一次性设置多个键值对。
     * <p>
     * Map 的值类型有 String 和 Boolean，用 Object 做泛型擦除。
     */
    private static final class BatchWriteAction implements StateAction {
        private final Map<StateVariableKey<?>, Object> entries;

        BatchWriteAction(Map<StateVariableKey<?>, Object> entries) {
            this.entries = entries;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void execute(StateGraphController controller) {
            var vars = controller.getVariables();
            entries.forEach((key, value) -> vars.set((StateVariableKey) key, value));
        }

        @Override
        @SuppressWarnings("unchecked")
        public MapCodec<? extends StateAction> getCodec() {
            return MapCodec.unit(this);
        }

        @Override
        public String toString() {
            return "BatchWrite(" + entries.size() + " keys)";
        }
    }
}
