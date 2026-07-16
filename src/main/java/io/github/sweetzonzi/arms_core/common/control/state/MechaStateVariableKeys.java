package io.github.sweetzonzi.arms_core.common.control.state;

import cn.solarmoon.spark_core.state_machine.graph.StateVariableKey;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import net.minecraft.resources.ResourceLocation;

/**
 * ARMS-Core 逻辑层状态变量键定义。
 *
 * <p>
 * 与 Spark-Core 内置的 {@link cn.solarmoon.spark_core.state_machine.presets.StateVariableKeys}
 * 互补——Spark-Core 提供 ON_GROUND、SPEED 等通用物理键，
 * 本类提供机娘运动系统专属的逻辑层产出变量。
 *
 * <p>
 * POSTURE / GAIT / VERTICAL 三大核心键使用枚举类型（{@link Posture} / {@link Gait} / {@link Vertical}），
 * 提供编译期类型安全。同时输出对应的一组 one-hot 布尔键，
 * 表现层动画条件直接查询布尔值，无需与枚举比较。
 *
 * <h3>变量类别</h3>
 * <ul>
 *   <li><b>逻辑层产出（枚举）</b> — POSTURE / GAIT / VERTICAL，由状态机 onEntry 写入</li>
 *   <li><b>表现层便利布尔</b> — IS_CREEPING / IS_FALLING 等，与枚举同时写入</li>
 *   <li><b>输入许可</b> — gait / vertical 分别产出许可，MechaControl 合并为 CAN_MOVE / CAN_JUMP</li>
 *   <li><b>浮点</b> — MOVE_SPEED_MODIFIER，KCC 直接读取</li>
 *   <li><b>快照写入</b> — HAS_INPUT / WALK_KEY_DOWN 等</li>
 *   <li><b>事件 latch</b> — EVENT_* 系列</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
public final class MechaStateVariableKeys {

    private MechaStateVariableKeys() {}

    // ═══════════════════════════════════════════════
    // 逻辑层产出 — 枚举（由状态机 onEntry 动作写入）
    // ═══════════════════════════════════════════════

    /** 当前姿态 */
    public static final StateVariableKey<Posture> POSTURE =
            key("arms_core:posture", Posture.STAND);

    /** 当前水平移动模式（所有姿态共用同一词表） */
    public static final StateVariableKey<Gait> GAIT =
            key("arms_core:gait", Gait.IDLE);

    /** 当前垂直模式（仅 stand 和 air 拥有子机） */
    public static final StateVariableKey<Vertical> VERTICAL =
            key("arms_core:vertical", Vertical.GROUND);

    /** 驾驶模式开关（独立 flag，非 posture 状态） */
    public static final StateVariableKey<Boolean> ENABLE_DRIVE =
            key("arms_core:enable_drive", false);

    // ═══════════════════════════════════════════════
    // 浮点 — KCC 直接读取
    // ═══════════════════════════════════════════════

    /**
     * 移动速度修正系数（0.0 ~ 1.8）。
     * 各姿态的 gait 状态进入时写入姿态特化倍率，
     * KCC 每帧读取此值乘以 posture 基准速度。
     */
    public static final StateVariableKey<Float> MOVE_SPEED_MODIFIER =
            key("arms_core:move_speed_modifier", 0f);

    /** 当前能量值（用于 sprint 消耗和 dodge 消耗） */
    public static final StateVariableKey<Float> ENERGY =
            key("arms_core:energy", 0f);

    // ═══════════════════════════════════════════════
    // 表现层便利布尔 — posture（one-hot）
    // ═══════════════════════════════════════════════

    public static final StateVariableKey<Boolean> IS_STANDING =
            key("arms_core:is_standing", true);
    public static final StateVariableKey<Boolean> IS_AIRBORNE =
            key("arms_core:is_airborne", false);
    public static final StateVariableKey<Boolean> IN_WATER_POSTURE =
            key("arms_core:in_water_posture", false);
    public static final StateVariableKey<Boolean> IS_CROUCHING =
            key("arms_core:is_crouching", false);
    public static final StateVariableKey<Boolean> IS_PRONE =
            key("arms_core:is_prone", false);
    public static final StateVariableKey<Boolean> IS_RAGDOLLED =
            key("arms_core:is_ragdolled", false);

    /** 骑乘 — 作为乘客乘坐载具中 */
    public static final StateVariableKey<Boolean> IS_RIDING =
            key("arms_core:is_riding", false);

    // ═══════════════════════════════════════════════
    // 表现层便利布尔 — gait（one-hot）
    // ═══════════════════════════════════════════════

    /** 静止 — 无自主水平移动 */
    public static final StateVariableKey<Boolean> IS_IDLE =
            key("arms_core:is_idle", true);

    /** 低速移动 — 蹲走、匍匐、慢游、空中微调 */
    public static final StateVariableKey<Boolean> IS_CREEPING =
            key("arms_core:is_creeping", false);

    /** 常速移动 — 慢跑、游泳、空中转向 */
    public static final StateVariableKey<Boolean> IS_JOGGING =
            key("arms_core:is_jogging", false);

    /** 高速移动 — 冲刺 */
    public static final StateVariableKey<Boolean> IS_SPRINTING_G =
            key("arms_core:is_sprinting_gait", false);

    /** 惯性滑行 — 无输入但有残余速度 */
    public static final StateVariableKey<Boolean> IS_DRIFTING =
            key("arms_core:is_drifting", false);

    /** 闪避/翻滚/空中 dash/推进器冲量 */
    public static final StateVariableKey<Boolean> IS_DODGING =
            key("arms_core:is_dodging", false);

    /** 硬直（禁止水平输入） */
    public static final StateVariableKey<Boolean> IS_STUNNED =
            key("arms_core:is_stunned", false);

    /** 重落地恢复中 */
    public static final StateVariableKey<Boolean> IS_HARD_LANDING =
            key("arms_core:is_hard_landing", false);

    // ═══════════════════════════════════════════════
    // 表现层便利布尔 — vertical（one-hot）
    // ═══════════════════════════════════════════════

    /** 着地 */
    public static final StateVariableKey<Boolean> IS_GROUNDED =
            key("arms_core:is_grounded", true);

    /** 跳跃蓄力中 */
    public static final StateVariableKey<Boolean> IS_JUMP_CHARGING =
            key("arms_core:is_jump_charging", false);

    /** 自然摔落 — 空中无推力阶段（上升与下落统一） */
    public static final StateVariableKey<Boolean> IS_FALLING =
            key("arms_core:is_falling", false);

    /** 鞘翅滑翔 */
    public static final StateVariableKey<Boolean> IS_GLIDING =
            key("arms_core:is_gliding", false);

    /** 推进悬浮 */
    public static final StateVariableKey<Boolean> IS_HOVERING =
            key("arms_core:is_hovering", false);

    /** 创造飞行 */
    public static final StateVariableKey<Boolean> IS_FLYING =
            key("arms_core:is_flying", false);

    // ═══════════════════════════════════════════════
    // 表现层便利布尔 — 派生
    // ═══════════════════════════════════════════════

    /** 正在自主移动（gait 非 idle / stun / hard_land） */
    public static final StateVariableKey<Boolean> IS_MOVING =
            key("arms_core:is_moving", false);

    // ═══════════════════════════════════════════════
    // 派生 — 输入 gating（MechaControl 查询用）
    // ═══════════════════════════════════════════════

    /** gait 子机是否允许水平移动输入 */
    public static final StateVariableKey<Boolean> GAIT_CAN_MOVE =
            key("arms_core:gait_can_move", true);

    /** gait 子机是否允许开始跳跃 */
    public static final StateVariableKey<Boolean> GAIT_CAN_JUMP =
            key("arms_core:gait_can_jump", true);

    /** vertical 子机是否允许水平移动输入 */
    public static final StateVariableKey<Boolean> VERTICAL_CAN_MOVE =
            key("arms_core:vertical_can_move", true);

    /** vertical 子机是否允许开始跳跃 */
    public static final StateVariableKey<Boolean> VERTICAL_CAN_JUMP =
            key("arms_core:vertical_can_jump", true);

    /** 是否允许水平移动输入（综合：非 stun、非硬直、非 ragdoll、非击倒起身） */
    public static final StateVariableKey<Boolean> CAN_MOVE =
            key("arms_core:can_move", true);

    /** 是否允许跳跃输入（综合：stand posture、非 stun、非蓄力中） */
    public static final StateVariableKey<Boolean> CAN_JUMP =
            key("arms_core:can_jump", true);

    /** 被击倒（强制 prone + 起身动画） */
    public static final StateVariableKey<Boolean> IS_KNOCKED_DOWN =
            key("arms_core:is_knocked_down", false);

    // ═══════════════════════════════════════════════
    // 快照写入（由 MechaControl 从 MechaConditionSnapshot 汇入）
    // ═══════════════════════════════════════════════

    /** 是否有移动输入（前进/侧移任一非零） */
    public static final StateVariableKey<Boolean> HAS_INPUT =
            key("arms_core:has_input", false);

    /** 慢走键是否按住（精细移动，触发 creep gait） */
    public static final StateVariableKey<Boolean> WALK_KEY_DOWN =
            key("arms_core:walk_key_down", false);

    /** 是否在水中（眼部位置浸水检测，快照字段） */
    public static final StateVariableKey<Boolean> IN_WATER =
            key("arms_core:in_water", false);

    /** KCC 是否正在蓄力跳跃；作为 vertical 子机的权威输入，不是表现层产出 */
    public static final StateVariableKey<Boolean> KCC_JUMP_CHARGING =
            key("arms_core:kcc_jump_charging", false);

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

    /** 本帧是否有击倒事件 */
    public static final StateVariableKey<Boolean> EVENT_KNOCKDOWN =
            key("arms_core:event_knockdown", false);

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
