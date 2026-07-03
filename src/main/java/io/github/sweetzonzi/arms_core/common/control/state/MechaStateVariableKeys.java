package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.state_machine.graph.StateVariableKey;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import net.minecraft.resources.ResourceLocation;

/**
 * ARMS-Core 逻辑层状态变量键定义。
 * <p>
 * 与 Spark-Core 内置的 {@link cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys}
 * 互补——Spark-Core 提供 ON_GROUND、SPEED 等通用物理键，
 * 本类提供机娘运动系统专属的逻辑层产出变量。
 * <p>
 * 所有 key 遵循 {@code arms_core:} 命名空间约定。
 * <p>
 * <h3>变量类别</h3>
 * <ul>
 *   <li><b>逻辑层产出（字符串）</b> — POSTURE / GAIT / VERTICAL，由状态机 onEntry 写入</li>
 *   <li><b>表现层便利布尔</b> — IS_WALKING / IS_JUMPING / IS_FLYING 等，与字符串同时写入，
 *       表现层动画条件直接查询，无需重复判断速度/输入等条件</li>
 *   <li><b>快照写入</b> — HAS_INPUT / WALK_KEY_DOWN 等，由 MechaControl 从 MechaConditionSnapshot 汇入</li>
 *   <li><b>事件 latch</b> — EVENT_* 系列，由 MechaControl 从 pendingEvents 汇入</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
public final class MechaStateVariableKeys {

    private MechaStateVariableKeys() {}

    // ═══════════════════════════════════════════════
    // 逻辑层产出 — 字符串（由状态机 onEntry 动作写入）
    // ═══════════════════════════════════════════════

    /** 当前姿态：stand / air / water / crouch / prone / ragdoll */
    public static final StateVariableKey<String> POSTURE =
            key("arms_core:posture", "stand");

    /** 当前步态：idle / walk / run / sprint / dodge */
    public static final StateVariableKey<String> GAIT =
            key("arms_core:gait", "idle");

    /** 当前垂直状态：ground / jump_charge / jump / fall / fly */
    public static final StateVariableKey<String> VERTICAL =
            key("arms_core:vertical", "ground");

    /** 驾驶模式开关（独立 flag，非 posture 状态） */
    public static final StateVariableKey<Boolean> ENABLE_DRIVE =
            key("arms_core:enable_drive", false);

    // ═══════════════════════════════════════════════
    // 表现层便利布尔 — posture（one-hot，由状态机 onEntry 写入）
    // 表现层动画条件直接查这些键，无需拼接 GAIT=="walk" && HAS_INPUT 等
    // ═══════════════════════════════════════════════

    /** 站立中 */
    public static final StateVariableKey<Boolean> IS_STANDING =
            key("arms_core:is_standing", true);

    /** 蹲伏中 */
    public static final StateVariableKey<Boolean> IS_CROUCHING =
            key("arms_core:is_crouching", false);

    /** 卧倒中 */
    public static final StateVariableKey<Boolean> IS_PRONE =
            key("arms_core:is_prone", false);

    /** 在空中（离地/跳跃/下落） */
    public static final StateVariableKey<Boolean> IS_AIRBORNE =
            key("arms_core:is_airborne", false);

    /** 姿势为水中（区别于快照 IN_WATER——本键表示状态机在 water posture） */
    public static final StateVariableKey<Boolean> IN_WATER_POSTURE =
            key("arms_core:in_water_posture", false);

    /** 布娃娃/死亡 */
    public static final StateVariableKey<Boolean> IS_RAGDOLLED =
            key("arms_core:is_ragdolled", false);

    // ═══════════════════════════════════════════════
    // 表现层便利布尔 — gait（one-hot）
    // ═══════════════════════════════════════════════

    /** 静止 */
    public static final StateVariableKey<Boolean> IS_IDLE =
            key("arms_core:is_idle", true);

    /** 慢走 */
    public static final StateVariableKey<Boolean> IS_WALKING =
            key("arms_core:is_walking", false);

    /** 慢跑 */
    public static final StateVariableKey<Boolean> IS_RUNNING =
            key("arms_core:is_running", false);

    /** 冲刺 */
    public static final StateVariableKey<Boolean> IS_SPRINTING =
            key("arms_core:is_sprinting", false);

    /** 闪避/翻滚 */
    public static final StateVariableKey<Boolean> IS_DODGING =
            key("arms_core:is_dodging", false);

    /** 匍匐爬行 */
    public static final StateVariableKey<Boolean> IS_CRAWLING =
            key("arms_core:is_crawling", false);

    /** 水中游动 */
    public static final StateVariableKey<Boolean> IS_SWIMMING =
            key("arms_core:is_swimming", false);

    // ═══════════════════════════════════════════════
    // 表现层便利布尔 — vertical（one-hot）
    // ═══════════════════════════════════════════════

    /** 着地（非跳跃/下落/飞行） */
    public static final StateVariableKey<Boolean> IS_GROUNDED =
            key("arms_core:is_grounded", true);

    /** 跳跃蓄力中 */
    public static final StateVariableKey<Boolean> IS_JUMP_CHARGING =
            key("arms_core:is_jump_charging", false);

    /** 上升段 */
    public static final StateVariableKey<Boolean> IS_JUMPING =
            key("arms_core:is_jumping", false);

    /** 下落段 */
    public static final StateVariableKey<Boolean> IS_FALLING =
            key("arms_core:is_falling", false);

    /** 飞行模式 */
    public static final StateVariableKey<Boolean> IS_FLYING =
            key("arms_core:is_flying", false);

    // ═══════════════════════════════════════════════
    // 表现层便利布尔 — 派生
    // ═══════════════════════════════════════════════

    /** 正在移动（任意非 idle gait） */
    public static final StateVariableKey<Boolean> IS_MOVING =
            key("arms_core:is_moving", false);

    // ═══════════════════════════════════════════════
    // 快照写入（由 MechaControl 从 MechaConditionSnapshot 汇入）
    // ═══════════════════════════════════════════════

    /** 是否有移动输入（前进/侧移任一非零） */
    public static final StateVariableKey<Boolean> HAS_INPUT =
            key("arms_core:has_input", false);

    /** 慢走键是否按住（精细移动，触发 walk gait） */
    public static final StateVariableKey<Boolean> WALK_KEY_DOWN =
            key("arms_core:walk_key_down", false);

    /** 是否在水中（眼部位置浸水检测，快照字段） */
    public static final StateVariableKey<Boolean> IN_WATER =
            key("arms_core:in_water", false);

    /** 当前能量值（用于 sprint 消耗和 dodge 消耗） */
    public static final StateVariableKey<Float> ENERGY =
            key("arms_core:energy", 0f);

    // ═══════════════════════════════════════════════
    // 事件 latch（由 MechaControl 从 pendingEvents 汇入）
    // ═══════════════════════════════════════════════

    /** 本帧是否有闪避事件 */
    public static final StateVariableKey<Boolean> EVENT_DODGE =
            key("arms_core:event_dodge", false);

    /** 本帧是否有潜行切换事件 */
    public static final StateVariableKey<Boolean> EVENT_TOGGLE_SNEAK =
            key("arms_core:event_toggle_sneak", false);

    /** 本帧是否有卧倒切换事件 */
    public static final StateVariableKey<Boolean> EVENT_TOGGLE_PRONE =
            key("arms_core:event_toggle_prone", false);

    /** 本帧是否有驾驶模式切换事件 */
    public static final StateVariableKey<Boolean> EVENT_TOGGLE_DRIVE =
            key("arms_core:event_toggle_drive", false);

    /** 本帧是否有飞行切换事件 */
    public static final StateVariableKey<Boolean> EVENT_TOGGLE_FLY =
            key("arms_core:event_toggle_fly", false);

    // ═══════════════════════════════════════════════
    // 工厂方法
    // ═══════════════════════════════════════════════

    /**
     * 便捷构造工厂。
     * @param id   ResourceLocation 格式的唯一标识
     * @param defaultValue 未写入时的默认返回值
     */
    @SuppressWarnings("unchecked")
    private static <T> StateVariableKey<T> key(String id, T defaultValue) {
        KClass<T> kClass = JvmClassMappingKt.getKotlinClass((Class<T>) defaultValue.getClass());
        return new StateVariableKey<>(
                ResourceLocation.parse(id),
                kClass,
                defaultValue
        );
    }
}
