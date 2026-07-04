package io.github.sweetzonzi.arms_core.client;

import cn.solarmoon.spark_core.api.SparkLevel;
import cn.solarmoon.spark_core.event.PhysicsLevelTickEvent;
import cn.solarmoon.spark_core.physics.PhysicsHelperKt;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.math.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * ARMS-Core 客户端入口。
 * <p>
 * 负责懒初始化 {@link MechaCharacter} 并每帧读取玩家 WASD/跳跃输入，
 * 转换到世界坐标系后写入控制器的 volatile 字段。
 * <p>
 * 物理步进由 {@link #onPrePhysicsTick} 委托给 {@link MechaCharacter#prePhysicsTick}。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public class ARMSClient {

    /** 胶囊半径 (m) */
    private static final float CAPSULE_RADIUS = 0.4f;
    /** 胶囊圆柱段高度 (m)，不含两端半球 */
    private static final float CAPSULE_HEIGHT = 1.6f;

    /** 控制器实例 */
    private static volatile MechaCharacter controller;
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
                initController(player);
                initialized = true;
            }
            return;
        }

        MechaCharacter ctrl = controller;
        if (ctrl == null) return;

        // ── WASD 输入 → 世界坐标方向 ──
        float forward = 0f;
        float strafe = 0f;
        if (mc.options.keyUp.isDown()) forward += 1f;
        if (mc.options.keyDown.isDown()) forward -= 1f;
        if (mc.options.keyLeft.isDown()) strafe -= 1f;
        if (mc.options.keyRight.isDown()) strafe += 1f;

        // 将玩家相对方向（以 yaw 为前向）转为世界坐标方向
        float yawRad = player.getYRot() * (float) Math.PI / 180f;
        float sinYaw = (float) Math.sin(yawRad);
        float cosYaw = (float) Math.cos(yawRad);
        float worldDirX = strafe * cosYaw - forward * sinYaw;
        float worldDirZ = forward * cosYaw + strafe * sinYaw;

        ctrl.setMoveInput(worldDirX, worldDirZ);

        // ── 跳跃键（检测按下/松开边沿） ──
        boolean jumpDown = mc.options.keyJump.isDown();
        boolean jumpReleased = wasJumpDown && !jumpDown;
        ctrl.setJumpInput(jumpDown, jumpReleased);
        wasJumpDown = jumpDown;
    }

    // ═══════════════════════════════════════════════
    // 物理线程回调
    // ═══════════════════════════════════════════════

    @SubscribeEvent
    public static void onPrePhysicsTick(PhysicsLevelTickEvent.Pre event) {
        MechaCharacter ctrl = controller;
        if (ctrl == null) return;
        // 仅处理本客户端的物理世界
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event.getLevel().getMcLevel() != mc.player.level()) return;

        if(event.getLevel().getTickCount() % 600 == 0) {
            ctrl.warp(PhysicsHelperKt.toBVector3f(mc.player.position()));
        }
        ctrl.prePhysicsTick(1f/event.getLevel().getTps());
    }

    // ═══════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════

    private static void initController(LocalPlayer player) {
        CapsuleCollisionShape shape = new CapsuleCollisionShape(CAPSULE_RADIUS, CAPSULE_HEIGHT);
        PhysicsSpace space = SparkLevel.getPhysicsLevel(player.level()).getWorld();
        MechaCharacter ctrl = new MechaCharacter(shape, space);

        // 初始位置设为玩家位置（胶囊中心在玩家脚底上方 capsuleHalfTotal 处）
        float halfTotal = shape.getHeight() / 2f + shape.getRadius();
        Vector3f startPos = new Vector3f(
                (float) player.getX(),
                (float) player.getY() + halfTotal,
                (float) player.getZ()
        );

        // 在物理线程设置位置并加入物理世界
        SparkLevel.submitImmediateTask(player.level(),
                cn.solarmoon.spark_core.util.PPhase.ALL,
                () -> {
                    ctrl.setPhysicsLocation(startPos);
                    space.addCollisionObject(ctrl);
                }
        );

        controller = ctrl;
    }
}
