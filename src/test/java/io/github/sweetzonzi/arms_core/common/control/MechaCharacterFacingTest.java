package io.github.sweetzonzi.arms_core.common.control;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制器朝向与「移动意图 → 世界方向」变换的判据。
 * <p>
 * 覆盖两条契约：
 * <ol>
 *   <li>{@link MechaCharacter#setViewYaw} 是朝向的绝对权威 —— 写入即等于该角（并归一化到
 *       [−π, π)），不累积；</li>
 *   <li>{@link MechaCharacter#setMoveIntent} 是唯一一处「意图 → 世界」变换 —— 前进沿当前朝向，
 *       左移是朝向的左侧，且朝向只被计入一次。</li>
 * </ol>
 * 全部断言不步进物理（不调 {@code prePhysicsTick}），因此不需要地形与 native 射线检测；
 * 构造函数仍需 jme3 native 可加载（NeoForge unitTest 运行时提供），与
 * {@code MechaControlTest} 同前提。带物理步的朝向判据见
 * {@code MechaCharacterStepTest#walkDirectionFollowsFacing}。
 *
 * @author Sweetzonzi
 */
class MechaCharacterFacingTest {

    private MechaCharacter kcc;

    @BeforeEach
    void setUp() {
        PhysicsSpace space = new PhysicsSpace(
                new Vector3f(-50f, -50f, -50f), new Vector3f(50f, 50f, 50f));
        kcc = new MechaCharacter(MechaBodyPreset.newCapsuleShape(), space);
    }

    // ═══════════════════════════════════════════════
    // 朝向权威
    // ═══════════════════════════════════════════════

    /** 朝向初值为 0（面向南 +Z），直到被显式写入。 */
    @Test
    void facingStartsAtZero() {
        assertEquals(0f, kcc.getCurrentYaw(), 1.0e-6f);
    }

    /** 写入的视野偏航即朝向：按度制写入、以弧度存储，不做任何累积。 */
    @Test
    void viewYawBecomesFacing() {
        kcc.setViewYaw(90f);
        assertEquals((float) Math.toRadians(90f), kcc.getCurrentYaw(), 1.0e-5f);

        // 再写一次是覆盖而不是叠加：值回到 0 而不是留在 90 或变成 180
        kcc.setViewYaw(0f);
        assertEquals(0f, kcc.getCurrentYaw(), 1.0e-6f);
    }

    /** 朝向归一化到 [−π, π)：跨圈与半圈边界都落在范围内。 */
    @Test
    void facingIsWrappedToPmPi() {
        kcc.setViewYaw(370f);
        assertEquals((float) Math.toRadians(10f), kcc.getCurrentYaw(), 1.0e-5f);

        kcc.setViewYaw(-370f);
        assertEquals((float) Math.toRadians(-10f), kcc.getCurrentYaw(), 1.0e-5f);

        // 180° 归一到 −180°，保证同一方向只有一个表示
        kcc.setViewYaw(180f);
        assertEquals((float) -Math.PI, kcc.getCurrentYaw(), 1.0e-5f);

        kcc.setViewYaw(540f);
        assertEquals((float) -Math.PI, kcc.getCurrentYaw(), 1.0e-5f);
    }

    // ═══════════════════════════════════════════════
    // 意图 → 世界方向的唯一变换
    // ═══════════════════════════════════════════════

    /** 默认朝向下前进 = 世界 +Z（南）；左移 = 世界 +X（东）。 */
    @Test
    void intentMapsToWorldAtDefaultFacing() {
        kcc.setMoveIntent(1f, 0f);
        assertTrue(kcc.isInputHasMove());
        assertEquals(0f, kcc.getInputDirX(), 1.0e-6f);
        assertEquals(1f, kcc.getInputDirZ(), 1.0e-6f);

        kcc.setMoveIntent(0f, 1f);
        assertEquals(1f, kcc.getInputDirX(), 1.0e-6f);
        assertEquals(0f, kcc.getInputDirZ(), 1.0e-6f);
    }

    /** yaw=90（面向西 −X）：前进 → 西，左移 → 南。 */
    @Test
    void intentMapsToWorldWhenFacingWest() {
        kcc.setViewYaw(90f);

        kcc.setMoveIntent(1f, 0f);
        assertEquals(-1f, kcc.getInputDirX(), 1.0e-5f);
        assertEquals(0f, kcc.getInputDirZ(), 1.0e-5f);

        kcc.setMoveIntent(0f, 1f);
        assertEquals(0f, kcc.getInputDirX(), 1.0e-5f);
        assertEquals(1f, kcc.getInputDirZ(), 1.0e-5f);
    }

    /**
     * 朝向只被计入一次。
     * <p>
     * 判据取「一次旋转」与「两次旋转」的分歧点：yaw=90 时前进应落在 −X；若变换在两层各做一次
     * （等于按 yaw 的两倍旋转），世界方向会落到 −Z，本断言即失败。
     */
    @Test
    void facingIsCountedExactlyOnce() {
        kcc.setViewYaw(90f);
        kcc.setMoveIntent(1f, 0f);

        assertEquals(-1f, kcc.getInputDirX(), 1.0e-5f, "前进应落在 −X（西），而不是 −Z（北）");
        assertEquals(0f, kcc.getInputDirZ(), 1.0e-5f);
    }

    /** 斜向意图被归一化：世界方向长度为 1，且落在朝向的两轴之间。 */
    @Test
    void diagonalIntentIsNormalized() {
        kcc.setMoveIntent(1f, 1f);

        float length = (float) Math.sqrt(
                kcc.getInputDirX() * kcc.getInputDirX() + kcc.getInputDirZ() * kcc.getInputDirZ());
        assertEquals(1f, length, 1.0e-6f);
        // 前（南 +Z）+ 左（东 +X） → 东南
        assertEquals(0.70710678f, kcc.getInputDirX(), 1.0e-5f);
        assertEquals(0.70710678f, kcc.getInputDirZ(), 1.0e-5f);
    }

    /** 零意图清空世界方向（上层据此触发无输入制动）。 */
    @Test
    void zeroIntentClearsWorldDirection() {
        kcc.setMoveIntent(1f, 0f);
        assertTrue(kcc.isInputHasMove());

        kcc.setMoveIntent(0f, 0f);
        assertFalse(kcc.isInputHasMove());
        assertEquals(0f, kcc.getInputDirX(), 1.0e-6f);
        assertEquals(0f, kcc.getInputDirZ(), 1.0e-6f);
    }

    /** 同一意图在朝向改变后重发，世界方向随之前转到新朝向。 */
    @Test
    void reissuedIntentFollowsNewFacing() {
        kcc.setMoveIntent(1f, 0f);
        assertEquals(1f, kcc.getInputDirZ(), 1.0e-6f);

        kcc.setViewYaw(90f);
        kcc.setMoveIntent(1f, 0f);
        assertEquals(-1f, kcc.getInputDirX(), 1.0e-5f);
        assertEquals(0f, kcc.getInputDirZ(), 1.0e-5f);
    }

    /**
     * 世界方向是 {@code setMoveIntent} 调用时刻的产物，仅改朝向不会改写它。
     * <p>
     * 编排层因此每个物理步都重发一次意图（{@code MechaControl.frameLogic} →
     * {@code forwardInputToKCC}），朝向变化在同一步内即反映到移动方向上。本测试把这条前提
     * 钉成可执行判据：若将来改为在物理步内延迟解算，这里会失败并提醒同步更新编排层。
     */
    @Test
    void worldDirectionIsResolvedWhenIntentIsSet() {
        kcc.setMoveIntent(1f, 0f);
        kcc.setViewYaw(90f);

        assertEquals(0f, kcc.getInputDirX(), 1.0e-6f);
        assertEquals(1f, kcc.getInputDirZ(), 1.0e-6f);
    }
}
