package io.github.sweetzonzi.arms_core.common.control;

import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaWalkingAttr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KCC 自动越障（auto-step）阈值测试。
 * <p>
 * 越障由 Bullet 的 {@code btKinematicCharacterController} 完成：`stepUp` 先把胶囊抬高
 * {@code m_stepHeight}（值来自 {@link MechaWalkingAttr#STEP_HEIGHT_BASE}，经
 * {@code PhysicsCharacter.setStepHeight} 下发）再水平 sweep，因此**可越障碍高度的上限就是步高**，
 * 且实际上限还略低一点 —— 水平 sweep 会把形状 margin 临时加大 {@code m_addedMargin = 0.02}
 * （`../Libbulletjme/src/main/native/bullet3/BulletDynamics/Character/btKinematicCharacterController.cpp#stepForwardAndStrafe`）。
 * 本类把这个阈值钉成可执行判据：半砖上得去，1 格完整方块上不去。
 * <p>
 * 直接搭真实 {@link PhysicsSpace}（地面 + 台阶），按游戏内物理步频（100 Hz，见
 * `docs/ArmsCore双端权威与网络同步实现计划.md` §3.6）驱动
 * {@link MechaCharacter#prePhysicsTick}。不经过 {@code MechaControl}：状态机与逻辑层产出与越障无关。
 *
 * @author Sweetzonzi
 */
class MechaCharacterStepTest {

    /** 与游戏内一致的物理步长：tps = 100（Spark-Core `PhysicsLevel.baseStep = 5`） */
    private static final float DT = 1f / 100f;

    /** 步数：4 s，足够走完 2 m 的接近段再上台阶 */
    private static final int STEPS = 400;

    /**
     * 一块静态盒子。
     * <p>
     * 碰撞组必须显式设置：{@code PhysicsCollisionObject} 默认组与掩码都是
     * {@code COLLISION_GROUP_01}（即 {@link CollisionGroups#TERRAIN}），而 KCC 属于
     * {@link CollisionGroups#PAWN}，不加掩码的话两者不会发生碰撞。
     */
    private static PhysicsRigidBody staticBox(Vector3f halfExtents, Vector3f location) {
        PhysicsRigidBody body = new PhysicsRigidBody(new BoxCollisionShape(halfExtents), 0f);
        body.setPhysicsLocation(location);
        body.setCollisionGroup(CollisionGroups.TERRAIN);
        body.setCollideWithGroups(CollisionGroups.TERRAIN | CollisionGroups.PAWN);
        return body;
    }

    /**
     * 朝台阶走满 {@link #STEPS} 步，返回过程中的最高脚底高度与结束位姿。
     * <p>
     * 取「最高脚底」而不是「结束脚底」：越过台阶后角色会继续前进并可能走下台阶远端。
     *
     * @param obstacleHeight 台阶高度 (m)，顶面 y = obstacleHeight，前沿 z = 2.0
     */
    private static WalkResult walkIntoAStepOfHeight(float obstacleHeight) {
        PhysicsSpace space = new PhysicsSpace(
                new Vector3f(-50f, -50f, -50f), new Vector3f(50f, 50f, 50f));
        // 地面：顶面 y = 0
        space.addCollisionObject(staticBox(new Vector3f(50f, 0.5f, 50f), new Vector3f(0f, -0.5f, 0f)));
        // 台阶：底面贴地，顶面 y = obstacleHeight
        space.addCollisionObject(staticBox(
                new Vector3f(2f, obstacleHeight / 2f, 2f),
                new Vector3f(0f, obstacleHeight / 2f, 4f)));

        MechaCharacter kcc = new MechaCharacter(MechaBodyPreset.newCapsuleShape(), space);
        kcc.setPhysicsLocation(new Vector3f(0f, MechaBodyPreset.HALF_TOTAL, 0f));
        space.addCollisionObject(kcc);

        float maxFeetY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < STEPS; i++) {
            kcc.setMoveInput(0f, 1f); // 世界 +Z（朝台阶）
            kcc.prePhysicsTick(DT);
            space.update(DT, 0, 0x0);
            maxFeetY = Math.max(maxFeetY, kcc.getPhysicsLocation(null).y - MechaBodyPreset.HALF_TOTAL);
        }
        return new WalkResult(maxFeetY, kcc.getPhysicsLocation(null));
    }

    /** 一次行走的观测结果 */
    private record WalkResult(float maxFeetY, Vector3f finalPosition) {
    }

    /** 步高确实交到了 Bullet 控制器手上，而不是只写在常量里。 */
    @Test
    void stepHeightReachesTheController() {
        PhysicsSpace space = new PhysicsSpace(
                new Vector3f(-50f, -50f, -50f), new Vector3f(50f, 50f, 50f));
        MechaCharacter kcc = new MechaCharacter(MechaBodyPreset.newCapsuleShape(), space);

        assertEquals(MechaWalkingAttr.STEP_HEIGHT_BASE, kcc.getStepHeight(), 1.0e-6f);
    }

    /** 半砖（0.5 m）落在素体裸足步高的余量内，应能直接走上去。 */
    @Test
    void climbsAHalfSlabStep() {
        WalkResult result = walkIntoAStepOfHeight(0.5f);

        assertTrue(result.maxFeetY() > 0.4f,
                "半砖（0.5 m）应能走上去，实际脚底最高 y=" + result.maxFeetY()
                        + "（步高 = " + MechaWalkingAttr.STEP_HEIGHT_BASE + " m）");
        assertTrue(result.maxFeetY() < 0.6f,
                "半砖顶面只有 0.5 m，脚底不应被抬到更高处，实际脚底最高 y=" + result.maxFeetY());
        assertTrue(result.finalPosition().z > 2.5f,
                "应越过半砖前沿 z=2.0，实际 z=" + result.finalPosition().z);
    }

    /**
     * 1 格完整方块（1.0 m）超出素体裸足步高，应被挡在台阶前沿。
     * <p>
     * 想上 1 格需要跳跃，或等腿部子系统提供 {@code step_height_bonus}（行走物理设计 §8.2）。
     */
    @Test
    void isBlockedByAFullBlockStep() {
        WalkResult result = walkIntoAStepOfHeight(1.0f);

        assertTrue(result.maxFeetY() < 0.3f,
                "1 格方块（1.0 m）超出步高，不应被抬上去，实际脚底最高 y=" + result.maxFeetY()
                        + "（步高 = " + MechaWalkingAttr.STEP_HEIGHT_BASE + " m）");
        assertTrue(result.finalPosition().z < 2.0f,
                "应被挡在台阶前沿 z=2.0 之外，实际 z=" + result.finalPosition().z);
    }
}
