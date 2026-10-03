package io.github.sweetzonzi.arms_core.common.control.state.graph;

import cn.solarmoon.spark_core.state_machine.graph.StateAction;
import cn.solarmoon.spark_core.state_machine.graph.StateGraphController;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableKey;
import com.mojang.serialization.MapCodec;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;

import java.util.Map;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;

/**
 * ARMS-Core 逻辑层状态动作集合。
 *
 * <p>
 * 每个动作在状态机 onEntry 时执行，负责：
 * <ul>
 *   <li>写入逻辑层产出枚举（POSTURE / GAIT / VERTICAL）</li>
 *   <li>写入表现层便利布尔（one-hot）</li>
 *   <li>写入 gait / vertical 各自的输入许可，由 MechaControl 合并最终结果</li>
 *   <li>写入 KCC 可读的浮点（MOVE_SPEED_MODIFIER）</li>
 * </ul>
 *
 * <p>
 * 所有姿势/步态/垂直动作现在接收对应的枚举实例（{@link io.github.sweetzonzi.arms_core.common.control.state.domain.Posture Posture} /
 * {@link io.github.sweetzonzi.arms_core.common.control.state.domain.Gait Gait} /
 * {@link io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical Vertical}），
 * 编译期类型安全，IDE 自动补全。
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
    // Posture — 写入枚举 + all one-hot 布尔
    // ═══════════════════════════════════════════════

    /**
     * 写入 posture 枚举 + 8 个 one-hot 布尔键。
     *
     * @param p 当前姿态
     */
    public static StateAction postureFlags(Posture p) {
        return new BatchWriteAction(Map.of(
                POSTURE, p,
                IS_STANDING, p == Posture.STAND,
                IS_AIRBORNE, p == Posture.AIR,
                IN_WATER_POSTURE, p == Posture.WATER,
                IS_CROUCHING, p == Posture.CROUCH,
                IS_PRONE, p == Posture.PRONE,
                IS_RIDING, p == Posture.RIDING,
                IS_RAGDOLLED, p == Posture.RAGDOLL
        ));
    }

    // ═══════════════════════════════════════════════
    // Gait — 写入枚举 + 倍率 + all one-hot + 派生
    // ═══════════════════════════════════════════════

    /**
     * 写入 gait 枚举 + 移动速度修正系数 + all one-hot 布尔 + 派生键。
     *
     * <p>
     * 派生键包括：
     * <ul>
     *   <li>{@code IS_STUNNED} — gait 为 STUN 时 true，否则 false</li>
     *   <li>{@code IS_MOVING} — 自主移动中的 gait 为 true</li>
     * </ul>
     *
     * @param g         当前 gait 枚举值
     * @param speedMod  姿态特化移动速度倍率（KCC 直接读取）
     */
    public static StateAction gaitWithModifier(Gait g, float speedMod) {
        java.util.Map<StateVariableKey<?>, Object> entries = gaitEntries(g);
        entries.put(MOVE_SPEED_MODIFIER, speedMod);
        return new BatchWriteAction(entries);
    }

    /**
     * 只写入 gait 枚举与 one-hot 布尔，<b>不动</b> {@code MOVE_SPEED_MODIFIER}。
     * <p>
     * 供 dodge 使用：倍率缩放的是标准 WASD 控制力（{@code MechaCharacter#controlForceScale()}），
     * 而闪避是一次速度阶跃（{@code MechaCharacter#requestDodgeImpulse}），两者量纲不同、互不干涉。
     * 因此进入 dodge 时保留进入前的倍率——冲刺中闪避不会掉回常速、站立闪避照常是全速，而闪避
     * 期间仍然可以正常使用方向键（dodge 不是「夺取控制权」的状态；{@code stun} / {@code hard_land}
     * 才是，它们另有 {@link #disableGaitInput()}）。
     * <p>
     * <b>这是 {@link #gaitWithModifier} 的不变量的唯一例外</b>，需要新增 gait 状态时不要「顺手补齐」
     * 一个倍率写入：{@code Gait.DODGE} 的 {@code baseSpeedModifier()} 因此没有消费者。
     *
     * @param g 当前 gait 枚举值
     */
    public static StateAction gaitPreservingModifier(Gait g) {
        return new BatchWriteAction(gaitEntries(g));
    }

    /**
     * gait 状态进入动作的公共部分：枚举本身 + 全部 one-hot 布尔 + {@code IS_MOVING} 派生键。
     * <p>
     * 刻意<b>不</b>包含 {@code MOVE_SPEED_MODIFIER}：出参由调用方决定是否补上该键，见
     * {@link #gaitPreservingModifier}。
     */
    private static java.util.Map<StateVariableKey<?>, Object> gaitEntries(Gait g) {
        boolean moving = g != Gait.IDLE
                && g != Gait.STUN
                && g != Gait.HARD_LAND
                && g != Gait.DRIFT;
        java.util.Map<StateVariableKey<?>, Object> entries = new java.util.HashMap<>();
        entries.put(GAIT, g);
        entries.put(IS_IDLE, g == Gait.IDLE);
        entries.put(IS_CREEPING, g == Gait.CREEP);
        entries.put(IS_JOGGING, g == Gait.JOG);
        entries.put(IS_SPRINTING_G, g == Gait.SPRINT);
        entries.put(IS_DRIFTING, g == Gait.DRIFT);
        entries.put(IS_DODGING, g == Gait.DODGE);
        entries.put(IS_STUNNED, g == Gait.STUN);
        entries.put(IS_HARD_LANDING, g == Gait.HARD_LAND);
        entries.put(IS_MOVING, moving);
        return entries;
    }

    // ═══════════════════════════════════════════════
    // Vertical — 写入枚举 + all one-hot 布尔
    // ═══════════════════════════════════════════════

    /**
     * 写入 vertical 枚举 + 6 个 one-hot 布尔键。
     *
     * @param v 当前垂直模式
     */
    public static StateAction verticalFlags(Vertical v) {
        return new BatchWriteAction(Map.of(
                VERTICAL, v,
                IS_GROUNDED, v == Vertical.GROUND,
                IS_JUMP_CHARGING, v == Vertical.JUMP_CHARGE,
                IS_FALLING, v == Vertical.FALL,
                IS_GLIDING, v == Vertical.GLIDE,
                IS_HOVERING, v == Vertical.HOVER,
                IS_FLYING, v == Vertical.FLY
        ));
    }

    // ═══════════════════════════════════════════════
    // 派生布尔
    // ═══════════════════════════════════════════════

    /** gait 子机恢复水平移动和跳跃许可。 */
    public static StateAction enableGaitInput() {
        return new BatchWriteAction(Map.of(
                GAIT_CAN_MOVE, true,
                GAIT_CAN_JUMP, true,
                IS_KNOCKED_DOWN, false
        ));
    }

    /** gait 子机禁止水平移动和开始跳跃（stun / hard_land）。 */
    public static StateAction disableGaitInput() {
        return new BatchWriteAction(Map.of(
                GAIT_CAN_MOVE, false,
                GAIT_CAN_JUMP, false
        ));
    }

    /** vertical 子机允许地面移动和开始跳跃。 */
    public static StateAction enableVerticalInput() {
        return new BatchWriteAction(Map.of(
                VERTICAL_CAN_MOVE, true,
                VERTICAL_CAN_JUMP, true
        ));
    }

    /** vertical 子机允许空中移动，但禁止开始新的跳跃。 */
    public static StateAction enableVerticalMoveOnly() {
        return new BatchWriteAction(Map.of(
                VERTICAL_CAN_MOVE, true,
                VERTICAL_CAN_JUMP, false
        ));
    }

    /** vertical 子机在跳跃蓄力期间禁止移动和重复开始跳跃。 */
    public static StateAction disableVerticalInput() {
        return new BatchWriteAction(Map.of(
                VERTICAL_CAN_MOVE, false,
                VERTICAL_CAN_JUMP, false
        ));
    }

    // ═══════════════════════════════════════════════
    // 批量写入动作实现
    // ═══════════════════════════════════════════════

    /**
     * 批量写入动作——一次性设置多个键值对。
     * <p>
     * Map 的值类型有 Posture/Gait/Vertical 枚举、String、Boolean、Float，
     * 用 Object 做泛型擦除。
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
