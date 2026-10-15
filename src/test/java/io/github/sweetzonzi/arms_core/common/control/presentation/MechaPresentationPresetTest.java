package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Gait;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Posture;
import io.github.sweetzonzi.arms_core.common.control.state.domain.Vertical;
import io.github.sweetzonzi.arms_core.common.control.state.graph.MechaStateActions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.github.sweetzonzi.arms_core.common.control.state.MechaStateVariableKeys.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表现层的映射表与客户端下行通道的往返测试。
 * <p>
 * 覆盖三件事：
 * <ol>
 *   <li>三个枚举的 MoLang 名与枚举本身互为逆运算（下行通道靠字符串搬运这三项）；</li>
 *   <li>下行通道写入容器后的可读回性：枚举回填、one-hot 全部就位、gait 那一支不动倍率；</li>
 *   <li>映射表里的每个动画名都真的在内容包里（键写错不会抛异常，只会没有动画）。</li>
 * </ol>
 *
 * @author Sweetzonzi
 */
class MechaPresentationPresetTest {

    /** 内容包里的主动画文件；路径与 {@code MechaModelPreset} 的定位规则一致 */
    private static final String MAIN_ANIMATIONS =
            "spark_modules/arms_core.builtin/arms_core/animations/frame/bmpt/bmpt_main.animation.json";

    // ==========================================
    // 枚举 ↔ MoLang 名
    // ==========================================

    @Test
    void molangNameRoundTripsForEveryEnumValue() {
        for (Posture posture : Posture.values()) {
            assertEquals(posture, Posture.fromMolangName(posture.molangName()));
        }
        for (Gait gait : Gait.values()) {
            assertEquals(gait, Gait.fromMolangName(gait.molangName()));
        }
        for (Vertical vertical : Vertical.values()) {
            assertEquals(vertical, Vertical.fromMolangName(vertical.molangName()));
        }
    }

    @Test
    void unknownOrMissingMolangNameIsNull() {
        assertNull(Posture.fromMolangName(null));
        assertNull(Posture.fromMolangName(""));
        assertNull(Posture.fromMolangName("STAND"));
        assertNull(Gait.fromMolangName("walk"));
        assertNull(Vertical.fromMolangName("grounded"));
    }

    // ==========================================
    // 下行通道写入 → 读回
    // ==========================================

    @Test
    void postureWriterFillsTheEnumAndEveryOneHotFlag() {
        for (Posture posture : Posture.values()) {
            StateVariableContainer variables = new StateVariableContainer();
            MechaStateActions.writePostureFlags(variables, posture);

            assertEquals(posture, variables.get(POSTURE));
            assertEquals(posture == Posture.STAND, variables.get(IS_STANDING));
            assertEquals(posture == Posture.AIR, variables.get(IS_AIRBORNE));
            assertEquals(posture == Posture.WATER, variables.get(IN_WATER_POSTURE));
            assertEquals(posture == Posture.CROUCH, variables.get(IS_CROUCHING));
            assertEquals(posture == Posture.PRONE, variables.get(IS_PRONE));
            assertEquals(posture == Posture.RIDING, variables.get(IS_RIDING));
            assertEquals(posture == Posture.RAGDOLL, variables.get(IS_RAGDOLLED));
        }
    }

    @Test
    void gaitWriterFillsTheEnumAndEveryOneHotFlag() {
        for (Gait gait : Gait.values()) {
            StateVariableContainer variables = new StateVariableContainer();
            MechaStateActions.writeGaitPreservingFlags(variables, gait);

            assertEquals(gait, variables.get(GAIT));
            assertEquals(gait == Gait.IDLE, variables.get(IS_IDLE));
            assertEquals(gait == Gait.CREEP, variables.get(IS_CREEPING));
            assertEquals(gait == Gait.JOG, variables.get(IS_JOGGING));
            assertEquals(gait == Gait.SPRINT, variables.get(IS_SPRINTING_G));
            assertEquals(gait == Gait.DRIFT, variables.get(IS_DRIFTING));
            assertEquals(gait == Gait.DODGE, variables.get(IS_DODGING));
            assertEquals(gait == Gait.STUN, variables.get(IS_STUNNED));
            assertEquals(gait == Gait.HARD_LAND, variables.get(IS_HARD_LANDING));
        }
    }

    /**
     * 客户端不消费倍率，因此下行通道用「保留倍率」那一支：写完之后
     * {@code MOVE_SPEED_MODIFIER} 必须仍是键自带的默认值。
     */
    @Test
    void gaitWriterLeavesTheSpeedModifierUntouched() {
        StateVariableContainer variables = new StateVariableContainer();
        float before = variables.get(MOVE_SPEED_MODIFIER);

        MechaStateActions.writeGaitPreservingFlags(variables, Gait.SPRINT);

        assertEquals(before, variables.get(MOVE_SPEED_MODIFIER), 0f);
        assertFalse(variables.contains(MOVE_SPEED_MODIFIER), "该支不应写入这个键");
    }

    @Test
    void verticalWriterFillsTheEnumAndEveryOneHotFlag() {
        for (Vertical vertical : Vertical.values()) {
            StateVariableContainer variables = new StateVariableContainer();
            MechaStateActions.writeVerticalFlags(variables, vertical);

            assertEquals(vertical, variables.get(VERTICAL));
            assertEquals(vertical == Vertical.GROUND, variables.get(IS_GROUNDED));
            assertEquals(vertical == Vertical.JUMP_BOOST, variables.get(IS_JUMP_BOOSTING));
            assertEquals(vertical == Vertical.FALL, variables.get(IS_FALLING));
            assertEquals(vertical == Vertical.GLIDE, variables.get(IS_GLIDING));
            assertEquals(vertical == Vertical.HOVER, variables.get(IS_HOVERING));
            assertEquals(vertical == Vertical.FLY, variables.get(IS_FLYING));
        }
    }

    // ==========================================
    // 映射表 ↔ 内容包
    // ==========================================

    @Test
    void everyMappedAnimationNameExistsInTheContentPack() throws IOException {
        String json = readMainAnimations();

        List<String> names = new ArrayList<>(List.of(
                MechaAnimPreset.IDLE, MechaAnimPreset.WALK, MechaAnimPreset.RUN,
                MechaAnimPreset.JUMP, MechaAnimPreset.FLY, MechaAnimPreset.ELYTRA_FLY,
                MechaAnimPreset.SWIM, MechaAnimPreset.SWIM_STAND,
                MechaAnimPreset.SNEAK, MechaAnimPreset.SNEAKING));
        names.addAll(MechaAnimPreset.PERSISTENT_SLOT_ANIMATIONS.values());

        for (Posture posture : Posture.values()) {
            for (String state : List.of("idle", "move", "sprint", "fall", "glide", "fly", "float", "swim")) {
                String animation = MechaAnimPreset.animationOf(posture, state);
                if (animation != null) names.add(animation);
            }
        }

        for (String name : names) {
            assertTrue(json.contains("\"" + name + "\""),
                    "内容包的主动画文件里没有动画名 " + name);
        }
    }

    private static String readMainAnimations() throws IOException {
        ClassLoader loader = MechaPresentationPresetTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(MAIN_ANIMATIONS)) {
            if (in == null) {
                throw new IllegalStateException("测试类路径上找不到资源 " + MAIN_ANIMATIONS);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
