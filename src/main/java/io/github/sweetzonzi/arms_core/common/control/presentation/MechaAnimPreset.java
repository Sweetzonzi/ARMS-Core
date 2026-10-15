package io.github.sweetzonzi.arms_core.common.control.presentation;

import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 表现层的动画名与「姿态状态 → 动画名」映射表。
 * <p>
 * 之所以把映射集中在这里而不是写进图里：图的形状（节点与边）由
 * {@link MechaPresentationGraphs} 定义，而「一个节点播哪个动画」是内容侧的对应关系，
 * 将来由内容包提供 {@code animation_controllers} 替换姿态选择时，要换的正是这一张表
 * （{@code docs/表现层动画接入实现计划.md} §4.4、§8.3）。
 * <p>
 * 映射的键是「姿态 + 状态名」，状态名与 {@link MechaPresentationGraphs} 的节点名逐字相同；
 * 查不到映射的节点（例如 {@link MechaPresentationGraphs#INACTIVE}）不播动画，这是合法取值，
 * 不设回退。
 *
 * @author Sweetzonzi
 */
public final class MechaAnimPreset {

    private MechaAnimPreset() {
    }

    // ═══════════════════════════════════════════════
    // 基础移动动画名（内容包 {@code arms_core/animations/frame/bmpt/} 下的条目名）
    // ═══════════════════════════════════════════════

    /** STAND 静止 */
    public static final String IDLE = "idle";
    /** STAND 常速 */
    public static final String WALK = "walk";
    /** STAND 冲刺 */
    public static final String RUN = "run";
    /** AIR 下落 / 助推 */
    public static final String JUMP = "jump";
    /** AIR 飞行 / 悬浮 */
    public static final String FLY = "fly";
    /** AIR 滑翔 */
    public static final String ELYTRA_FLY = "elytra_fly";
    /** WATER 移动 */
    public static final String SWIM = "swim";
    /** WATER 静止 */
    public static final String SWIM_STAND = "swim_stand";
    /** CROUCH 移动 */
    public static final String SNEAK = "sneak";
    /** CROUCH 静止 */
    public static final String SNEAKING = "sneaking";

    // ═══════════════════════════════════════════════
    // 常驻并行动画层
    // ═══════════════════════════════════════════════

    /**
     * 常驻层槽位号 → 动画名。槽位号直接作为 Spark-Core 的
     * {@code AnimGroups.persistent(int)} 入参，首版接入槽位 0、1。
     * <p>
     * 槽位缺失要静默跳过（{@code docs/表现层动画接入实现计划.md} §4.8 第 1 条）：
     * 资源侧的 {@code pre_parallel2}~{@code pre_parallel7} 是空占位，替换资源包时也可能整体缺席。
     */
    public static final Map<Integer, String> PERSISTENT_SLOT_ANIMATIONS = Map.of(
            0, "pre_parallel0",
            1, "pre_parallel1"
    );

    // ═══════════════════════════════════════════════
    // 姿态状态 → 动画名
    // ═══════════════════════════════════════════════

    /**
     * 映射键：姿态 + 该姿态下机器的节点名。
     * <p>
     * 带姿态是因为节点名只在单台机器内唯一——STAND 与 CROUCH 各有一个名为
     * {@code idle} 的节点，它们播的不是同一个动画。
     *
     * @param posture 所属姿态
     * @param state   节点名
     */
    public record StateKey(Posture posture, String state) {
    }

    private static final Map<StateKey, String> POSTURE_STATE_ANIMATIONS = createPostureStateAnimations();

    private static Map<StateKey, String> createPostureStateAnimations() {
        Map<StateKey, String> map = new HashMap<>();
        map.put(new StateKey(Posture.STAND, "idle"), IDLE);
        map.put(new StateKey(Posture.STAND, "move"), WALK);
        map.put(new StateKey(Posture.STAND, "sprint"), RUN);
        map.put(new StateKey(Posture.AIR, "fall"), JUMP);
        map.put(new StateKey(Posture.AIR, "glide"), ELYTRA_FLY);
        map.put(new StateKey(Posture.AIR, "fly"), FLY);
        map.put(new StateKey(Posture.WATER, "float"), SWIM_STAND);
        map.put(new StateKey(Posture.WATER, "swim"), SWIM);
        map.put(new StateKey(Posture.CROUCH, "idle"), SNEAKING);
        map.put(new StateKey(Posture.CROUCH, "move"), SNEAK);
        return Map.copyOf(map);
    }

    /**
     * 查一个姿态状态要播的动画名。
     *
     * @param posture 所属姿态
     * @param state   节点名
     * @return 动画名；该状态不播动画时返回 {@code null}
     */
    public static @Nullable String animationOf(Posture posture, String state) {
        return POSTURE_STATE_ANIMATIONS.get(new StateKey(posture, state));
    }

    // ═══════════════════════════════════════════════
    // 过渡时长
    // ═══════════════════════════════════════════════

    /**
     * 姿态切换的进出场过渡时长 (s)。
     * <p>
     * 取的是 {@code PlayAnimAction} 自身的默认值，实机上定值后改这里
     * （{@code docs/表现层动画接入实现计划.md} §9 第 1 条）。
     */
    public static final float POSTURE_BLEND_SECONDS = 0.15f;

    /**
     * 常驻层的进出场过渡时长 (s)，取 0。
     * <p>
     * 常驻实例从建立到拆除之间一直播放，入场淡入在它身上没有意义；取 0 后实例进入当步即满权重。
     */
    public static final float PERSISTENT_BLEND_SECONDS = 0f;
}
