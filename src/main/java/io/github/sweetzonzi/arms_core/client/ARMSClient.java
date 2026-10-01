package io.github.sweetzonzi.arms_core.client;

import cn.solarmoon.spark_core.api.SparkLevel;
import cn.solarmoon.spark_core.event.PhysicsLevelTickEvent;
import cn.solarmoon.spark_core.physics.PhysicsHelperKt;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.MechaConditionSnapshot;
import io.github.sweetzonzi.arms_core.common.control.MechaEvent;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.math.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * ARMS-Core 客户端入口（单玩家测试闭环）。
 * <p>
 * 链路：主线程采集 WASD/跳跃/冲刺/潜行边沿 → 写入 {@link ClientMechaTestRig} 的
 * {@link MechaConditionSnapshot} 与 {@link MechaEvent}；物理线程每步调用
 * {@code MechaControl.onPhysicsStep(dt)} 完成状态机推进与 KCC 物理积分。
 * <p>
 * 当前处于<b>旁路观测模式</b>（MechaControl 默认）：状态机照常运行并输出调试状态，
 * 但 CAN_MOVE / CAN_JUMP 暂不门控 KCC，保证原 KCC 行走/跳跃无回归。
 * <p>
 * 线程模型：主线程只做输入采集与 volatile/原子发布，物理线程只读物理状态；
 * 两端不直接共享可变物理数据。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public class ARMSClient {

    /** 测试夹具（MechaControl + KCC） */
    private static volatile ClientMechaTestRig rig;

    /** 上帧跳跃键状态（用于检测松开边沿） */
    private static boolean wasJumpDown;
    /** 是否已初始化 */
    private static boolean initialized;

    // ═══════════════════════════════════════════════
    // 主线程输入读取
    // ═══════════════════════════════════════════════

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // 懒初始化控制器（等待玩家和物理世界就绪）
        if (!initialized) {
            if (mc.level != null && player.isAlive()) {
                initRig(player);
                initialized = true;
            }
            return;
        }

        ClientMechaTestRig rigLocal = rig;
        if (rigLocal == null) return;

        // ── 连续输入 → 条件快照（不可变 record，volatile 发布） ──
        // strafe 约定与原版 Input.leftImpulse 一致：正 = 左移（A），负 = 右移（D）
        float forward = 0f;
        float strafe = 0f;
        if (mc.options.keyUp.isDown()) forward += 1f;
        if (mc.options.keyDown.isDown()) forward -= 1f;
        if (mc.options.keyLeft.isDown()) strafe += 1f;
        if (mc.options.keyRight.isDown()) strafe -= 1f;

        boolean jumpDown = mc.options.keyJump.isDown();
        boolean jumpReleased = wasJumpDown && !jumpDown;
        wasJumpDown = jumpDown;
        MechaConditionSnapshot snapshot = MechaConditionSnapshot.builder()
                .inputForward(forward)
                .inputStrafe(strafe)
                .jumpPressed(jumpDown)
                .jumpReleased(jumpReleased)
                .sprintPressed(mc.options.keySprint.isDown())
                // 慢走键：测试夹具暂未绑定独立按键，保持 false（creep 由单元测试覆盖）
                .walkKeyPressed(false)
                // 蹲伏：连续状态，直接映射 posture stand ↔ crouch（蹲下即 crouch、站直即 stand）
                .sneaking(player.isCrouching())
                .viewYaw(player.getYRot())
                .viewPitch(player.getXRot())
                .inWater(player.isInFluidType()) // 任何流体
                .inLava(player.isInLava())
                .isDead(!player.isAlive())
                .isSleeping(player.isSleeping())
                .isFallFlying(player.isFallFlying())
                .isInWall(player.isInWall())
                .isOnFire(player.isOnFire())
                .build();

        rigLocal.writeConditionSnapshot(snapshot);
    }

    // ═══════════════════════════════════════════════
    // 物理线程回调
    // ═══════════════════════════════════════════════

    @SubscribeEvent
    public static void onPrePhysicsTick(PhysicsLevelTickEvent.Pre event) {
        ClientMechaTestRig rigLocal = rig;
        if (rigLocal == null) return;
        // 仅处理本客户端的物理世界
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event.getLevel().getMcLevel() != mc.player.level()) return;

        if (event.getLevel().getTickCount() % 600 == 0) {
            rigLocal.getKcc().warp(PhysicsHelperKt.toBVector3f(mc.player.position()));
        }
        rigLocal.getMechaControl().onPhysicsStep(1f / event.getLevel().getTps());
    }

    // ═══════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════

    private static void initRig(LocalPlayer player) {
        CapsuleCollisionShape shape = MechaBodyPreset.newCapsuleShape();
        PhysicsSpace space = SparkLevel.getPhysicsLevel(player.level()).getWorld();
        ClientMechaTestRig rigLocal = new ClientMechaTestRig(shape, space);

        // 初始位置设为玩家位置（胶囊中心在玩家脚底上方 HALF_TOTAL 处）
        float[] center = MechaBodyPreset.capsuleCenterFromFeet(
                (float) player.getX(), (float) player.getY(), (float) player.getZ());
        Vector3f startPos = new Vector3f(center[0], center[1], center[2]);

        // 在物理线程设置位置并加入物理世界
        SparkLevel.submitImmediateTask(player.level(),
                cn.solarmoon.spark_core.util.PPhase.ALL,
                () -> {
                    rigLocal.getKcc().setPhysicsLocation(startPos);
                    space.addCollisionObject(rigLocal.getKcc());
                }
        );

        // 测试闭环：开启状态变化日志（posture/gait/vertical 变化时打印一行）
        rigLocal.getMechaControl().setDebugLog(true);

        rig = rigLocal;
    }
}
