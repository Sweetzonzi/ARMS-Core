package io.github.sweetzonzi.arms_core.common.gametest;

import cn.solarmoon.spark_core.api.SparkLevel;
import cn.solarmoon.spark_core.util.PPhase;
import cn.solarmoon.spark_core.util.SparkMathKt;
import com.jme3.bullet.joints.motors.MotorParam;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import io.github.sweetzonzi.arms_core.common.control.MechaConditionSnapshot;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import io.github.sweetzonzi.arms_core.common.part.*;
import io.github.sweetzonzi.machine_max.common.mech.ObjectManager;
import io.github.sweetzonzi.machine_max.common.mech.part.*;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.RegularPart;
import jme3utilities.math.MyMath;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 身体组成的服务端集成验收，覆盖身份稳定性、共享结构与恢复边界。 */
@GameTestHolder(ARMS.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BodyPartGameTest {
    /** 验证内置包发布的是实际 AllBody 定义与经过端点校验的素体。 */
    @GameTest(template = "empty_platform", batch = "armsBody", timeoutTicks = 100)
    public static void allBodyFrameLoadsFromContentPack(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "allbody_joint_test");
        var frame = ArmsFrameModule.INSTANCE.get(false, id);
        if (frame == null) {
            helper.fail("AllBody 素体未发布");
            return;
        }
        helper.assertTrue(frame.core().equals("torso") && frame.parts().size() == 5 && frame.connections().size() == 4,
                "素体应包含核心、两条腿和四个关节");

        // 素体发布时只验证定义；这里独立验证工厂取得真实模型与挂点，且尚未入世。
        var leg = frame.parts().get("left_thigh");
        var type = PartFactory.resolve(false, leg.part());
        helper.assertTrue(type instanceof BodyPartType && type.variants.keySet().equals(java.util.Set.of("left", "right")),
                "大腿必须具有两种真实变体");
        var part = (BodyPart) PartFactory.create(type, leg.variant(), helper.getLevel(), BodyPart.CONTEXT);
        try {
            helper.assertTrue(part.rootSubPart.attr.mass == 7f
                            && part.rootSubPart.connectors.containsKey("left_knee_socket")
                            && !part.rootSubPart.body.isInWorld(),
                    "工厂应使用指定变体的质量与挂点，保持刚体未入世");
        } finally {
            part.destroy();
        }
        helper.succeed();
    }

    /** 构造完整约束，再从 NBT 恢复弯曲姿态与原安装参考系。 */
    @GameTest(template = "empty_platform", batch = "armsBody", timeoutTicks = 100)
    public static void allBodyFrameConstructsAndRestoresJoints(GameTestHelper helper) {
        var level = helper.getLevel();
        var id = ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "allbody_joint_test");
        var frame = ArmsFrameModule.INSTANCE.get(false, id);
        var pose = new Transform(new Vector3f(2f, 3f, 4f), new Quaternion().fromAngles(0f, 0.4f, 0f));
        ArmsCore source = ArmsCore.fromFrame(level, UUID.randomUUID(), id, pose);
        ArmsCore restored = null;
        ArmsCore second = null;
        try {
            second = ArmsCore.fromFrame(level, UUID.randomUUID(), id, pose);
            float lowestBodyY = (float) source.getPartMap().values().stream()
                    .flatMap(part -> part.subParts.values().stream())
                    .mapToDouble(child -> child.body.boundingBox(null).getMin(null).y)
                    .min().orElseThrow();
            helper.assertTrue(Math.abs(lowestBodyY + MechaBodyPreset.HALF_TOTAL
                            + frame.coreHeightOffset() - pose.getTranslation().y) < 0.01f,
                    "默认站姿下，校准后的身体底面应贴合控制器脚底");
            helper.assertTrue(source.getPartMap().size() == 5 && source.getBodyLinks().size() == 4
                            && second.getPartMap().keySet().stream().noneMatch(source.getPartMap()::containsKey),
                    "同一素体的两次构造应产生各自的五个成员和四条边");
            helper.assertTrue(source.getCorePart().rootSubPart.body.getPhysicsLocation(null)
                            .subtract(pose.getTranslation()).lengthSquared() < 1e-8f,
                    "核心根应放在调用者指定的锚点");
            for (var link : source.getBodyLinks()) {
                helper.assertTrue(link.advanced().joint != null && link.advanced().joint == link.simple().joint
                                && link.advanced().joint.getPhysicsSpace() == null
                                && !link.advanced().subPart.body.isInWorld() && !link.simple().subPart.body.isInWorld(),
                        "两端应持有同一真实约束，整套身体保持未入世");
            }
            var knee = source.getBodyLinks().stream()
                    .filter(link -> link.advanced().name.equals("left_knee_socket")).findFirst().orElseThrow();
            var shin = knee.simple().subPart.part;
            var before = shin.rootSubPart.body.getTransform(null);
            // 绕当前枢轴弯曲小腿，检验恢复不拿当前弯曲姿态重设关节零位。
            var pivot = MyMath.combine(knee.frameS(), before, new Transform()).getTranslation();
            var axis = before.getRotation().mult(knee.frameS().getRotation()).toRotationMatrix()
                    .mult(Vector3f.UNIT_X, new Vector3f());
            var bend = SparkMathKt.toBQuaternion(new org.joml.Quaternionf().rotationAxis(0.5f, axis.x, axis.y, axis.z));
            var bent = new Transform(bend.toRotationMatrix().mult(before.getTranslation().subtract(pivot), new Vector3f())
                    .addLocal(pivot),
                    bend.mult(before.getRotation()));
            shin.setTransform(bent);

            // 混装存档由本端定义选择 Codec，同时保留普通部件的专用状态。
            var armorType = PartFactory.resolve(false, ResourceLocation.parse("machine_max:2s38_addon_armor"));
            var armor = (RegularPart) PartFactory.create(armorType, "left_1", level, BodyPart.CONTEXT);
            armor.setMaterialProgress(2);
            source.addPart(armor);
            var tag = source.saveBodyTag();
            var saved = ArmsBodyData.codec(false).parse(NbtOps.INSTANCE, tag).getOrThrow();
            restored = new ArmsCore(level, UUID.randomUUID(), saved);
            helper.assertTrue(restored.saveBody().frameId().orElseThrow().equals(id)
                            && tag.getString("arms_frame").equals(id.toString()) && !tag.contains("core_height_offset"),
                    "存档应保留素体 ID，定义中的高度偏移留在素体文件中");

            // 服务端依赖素体定义计算控制器目标。未知引用必须在恢复部件前拒绝。
            var missingFrame = tag.copy();
            missingFrame.putString("arms_frame", "arms_core:missing_frame");
            try {
                new ArmsCore(level, UUID.randomUUID(),
                        ArmsBodyData.codec(false).parse(NbtOps.INSTANCE, missingFrame).getOrThrow()).disposeBody();
                helper.fail("找不到素体定义时不能恢复");
            } catch (IllegalArgumentException expected) {
                helper.assertTrue(expected.getMessage().contains("arms_core:missing_frame"), "错误应指出缺失的素体");
            }
            helper.assertTrue(restored.findPart(armor.uuid) instanceof RegularPart restoredArmor
                            && restoredArmor.variantName.equals("left_1") && restoredArmor.materialProgress == 2,
                    "混装恢复应保留普通部件的种类、变体与材料进度");
            var edge = saved.connections().getFirst();
            helper.assertTrue(new ArmsBodyData(saved.name(), saved.corePartId(), saved.parts(), List.of(edge, edge))
                            .validate().error().isPresent(),
                    "存档不能重复占用同一端点");
            var restoredKnee = restored.getBodyLinks().stream()
                    .filter(link -> link.advanced().name.equals(knee.advanced().name)).findFirst().orElseThrow();
            var after = restored.findPart(shin.uuid).rootSubPart.body.getTransform(null);
            helper.assertTrue(after.getTranslation().subtract(bent.getTranslation()).lengthSquared() < 1e-8f
                            && Math.abs(SparkMathKt.toQuaternionf(after.getRotation())
                            .dot(SparkMathKt.toQuaternionf(bent.getRotation()))) > 0.999999f,
                    "恢复应保留弯曲小腿的世界位姿");
            helper.assertTrue(restoredKnee.frameA().equals(knee.frameA()) && restoredKnee.frameS().equals(knee.frameS())
                            && restoredKnee.simple().subPart.part == restored.findPart(shin.uuid)
                            && restoredKnee.advanced().joint != null,
                    "恢复应沿用原参考系，并连接正式恢复实例");
        } finally {
            source.disposeBody();
            if (restored != null) restored.disposeBody();
            if (second != null) second.disposeBody();
        }
        helper.succeed();
    }

    /** 走正常宿主入世路径，验证核心跟随、被动膝关节和完整注销。 */
    @GameTest(template = "empty_platform", batch = "armsBody", timeoutTicks = 400)
    public static void allBodyRunsAlongsidePlayerController(GameTestHelper helper) {
        var level = helper.getLevel();
        var physics = SparkLevel.getPhysicsLevel(level);
        var frameId = ResourceLocation.parse("arms_core:allbody_joint_test");
        var frame = ArmsFrameModule.INSTANCE.get(false, frameId);
        var source = ArmsCore.fromFrame(level, UUID.randomUUID(), frameId, new Transform());
        ArmsCore core;
        try {
            var saved = ArmsBodyData.codec(false).parse(NbtOps.INSTANCE, source.saveBodyTag()).getOrThrow();
            core = new ArmsCore(level, UUID.randomUUID(), saved);
        } finally {
            source.disposeBody();
        }
        var host = helper.makeMockPlayer(GameType.CREATIVE);
        ArmsCore.bindHostOf(host, core);
        core.writeConditionSnapshot(MechaConditionSnapshot.builder().inputForward(1f).build());
        MechaCoreRegistry.addServer(core, core.getBoundHostEntityId());
        // GameTest 场地随机落在远原点，测试身体用近原点的空中位置读取浮点关节量。
        var spawn = new Vector3f(12f, 180f, 12f);
        MechaCoreRegistry.enterPhysicsSpace(core, spawn);
        var knee = core.getBodyLinks().stream()
                .filter(link -> link.advanced().name.equals("left_knee_socket")).findFirst().orElseThrow();
        var kneeJoint = knee.advanced().joint;
        var root = core.getCorePart().rootSubPart.body;
        var members = core.getPartMap().values().stream()
                .flatMap(part -> part.subParts.values().stream()).toList();
        var joints = new CompletableFuture<List<com.jme3.bullet.joints.PhysicsJoint>>();
        var sample = new CompletableFuture<BodyStep>();
        var turn = new CompletableFuture<TurnStep>();
        var rapidTurn = new CompletableFuture<TurnStep>();
        var stepError = new CompletableFuture<Float>();
        var warpError = new CompletableFuture<Float>();
        physics.submitImmediateTask(PPhase.PRE, () -> {
            joints.complete(List.of(root.listJoints()));
            float initialHeightError = root.getPhysicsLocation(null).y - spawn.y - frame.coreHeightOffset();
            var axis = kneeJoint.getBodyA().getPhysicsRotation(null).mult(knee.frameA().getRotation())
                    .toRotationMatrix().mult(Vector3f.UNIT_X, new Vector3f());
            kneeJoint.getBodyB().setAngularVelocity(axis.mult(8f));
            physics.submitDelayedTask(PPhase.POST, 35, () -> {
                var controller = core.getBody();
                var a = MyMath.combine(knee.frameA(), kneeJoint.getBodyA().getTransform(null), new Transform());
                var b = MyMath.combine(knee.frameS(), kneeJoint.getBodyB().getTransform(null), new Transform());
                sample.complete(new BodyStep(
                        controller.getPhysicsLocation(null).z - spawn.z, controller.getLinearVelocity(null).z,
                        root.getPhysicsLocation(null).subtract(controller.getPhysicsLocation(null)
                                .addLocal(0f, frame.coreHeightOffset(), 0f)).length(),
                        kneeJoint.getAngles(null).x, kneeJoint.get(MotorParam.UpperLimit, 3),
                        a.getTranslation().subtract(b.getTranslation()).length(), controller.countJoints(), initialHeightError));
                return null;
            });
            return null;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(sample.isDone(), "等待控制器和身体完成物理步"))
                .thenExecute(() -> {
                    helper.assertTrue(core.getPositionIntake().getIntakeCount() == 0, "普通前进不应触发外部位移摄入");
                    helper.assertTrue(core.getRootSubPart().getPosition().z > spawn.z,
                            "MM 的主线程采样应发布身体的前进位姿");
                    sampleTurn(core, 60f, turn);
                })
                .thenWaitUntil(() -> helper.assertTrue(turn.isDone(), "等待核心跟随偏航"))
                .thenExecute(() -> sampleTurn(core, -90f, rapidTurn))
                .thenWaitUntil(() -> helper.assertTrue(rapidTurn.isDone(), "等待核心跟随 150° 急转"))
                .thenExecute(() -> physics.submitImmediateTask(PPhase.PRE, () -> {
                    // 模拟控制器小幅落位，走常规跟随路径，保留原有身体动量。
                    var controller = core.getBody();
                    controller.setPhysicsLocation(controller.getPhysicsLocation(null).addLocal(0.3f, 0f, 0f));
                    physics.submitDelayedTask(PPhase.POST, 5, () -> {
                        stepError.complete(Math.abs(root.getPhysicsLocation(null).x - controller.getPhysicsLocation(null).x));
                        return null;
                    });
                    return null;
                }))
                .thenWaitUntil(() -> helper.assertTrue(stepError.isDone(), "等待核心跟随控制器平移"))
                .thenExecute(() -> {
                    host.setPos(64, 180, 12);
                    physics.submitImmediateTask(PPhase.PRE, () -> {
                        // 和宿主摄入任务共用 PRE 队列，随后读取控制器与核心的新位置。
                        var controller = core.getBody().getPhysicsLocation(null);
                        warpError.complete(Math.max(Math.abs(controller.x - 64f),
                                root.getPhysicsLocation(null).subtract(controller
                                        .addLocal(0f, frame.coreHeightOffset(), 0f)).length()));
                        return null;
                    });
                })
                .thenWaitUntil(() -> helper.assertTrue(warpError.isDone(), "等待宿主位移摄入"))
                .thenExecute(() -> {
                    try {
                        var step = sample.join();
                        helper.assertTrue(step.displacement > 0.1f && step.speed > 0.5f
                                        && step.controllerJoints == 0 && step.rootError < 0.15f,
                                "核心应跟随由玩家输入驱动前进的控制器：" + step);
                        helper.assertTrue(Math.abs(step.initialHeightError) < 0.001f,
                                "核心入世时应直接放到带偏移的锚点，误差 " + step.initialHeightError);
                        helper.assertTrue(Math.abs(step.kneeAngle) > 0.15f && Math.abs(step.kneeAngle) < step.kneeLimit + 0.10f
                                        && step.pivotError < 0.08f,
                                "膝应受扰动转动，并保持限位和挂点约束：" + step);
                        helper.assertTrue(warpError.join() < 0.15f,
                                "宿主传送时身体与控制器应整体移动，误差 " + warpError.join());
                        var facing = turn.join();
                        var fastFacing = rapidTurn.join();
                        ARMS.LOGGER.info("AllBody 跟随：60° 转向 {}，150° 急转 {}，0.3 m 平移误差 {} m",
                                facing, fastFacing, stepError.join());
                        helper.assertTrue(Math.abs(facing.controllerYaw - 60f) < 1f
                                        && Math.abs(Mth.wrapDegrees(facing.bodyForwardYaw - facing.controllerYaw)) < 8f,
                                "正偏航应转向西，核心应在六个子步内跟上：" + facing);
                        helper.assertTrue(Math.abs(fastFacing.controllerYaw + 90f) < 1f
                                        && Math.abs(Mth.wrapDegrees(fastFacing.bodyForwardYaw - fastFacing.controllerYaw)) < 8f,
                                "急转后核心仍应跟随偏航：" + fastFacing);
                        helper.assertTrue(stepError.join() < 0.065f,
                                "核心应在五个子步内跟上控制器平移，误差 " + stepError.join());
                        helper.assertTrue(members.stream().allMatch(child ->
                                        ObjectManager.getDestroyableObject(level, child.getId()) == child),
                                "ARMS 子部件应走 MM 的同步注册表");
                    } finally {
                        ArmsCore.bindHostOf(host, null);
                        MechaCoreRegistry.removeServer(core);
                    }
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(core.disposeBody().isDone(), "等待物理线程完成注销");
                    helper.assertTrue(!core.getBody().isInWorld() && members.stream().noneMatch(child -> child.body.isInWorld())
                                    && members.stream().noneMatch(child -> ObjectManager.getDestroyableObject(level, child.getId()) == child)
                                    && joints.join().stream().allMatch(joint -> joint.getPhysicsSpace() == null)
                                    && kneeJoint.getPhysicsSpace() == null,
                            "控制器、身体和约束都应离开物理空间");
                })
                .thenSucceed();
    }

    private record BodyStep(float displacement, float speed, float rootError, float kneeAngle, float kneeLimit,
                            float pivotError, int controllerJoints, float initialHeightError) {}

    private record TurnStep(float controllerYaw, float bodyForwardYaw) {}

    /** 写入玩家视野朝向，并在同一物理线程观察接下来的六步。 */
    private static void sampleTurn(ArmsCore core, float yaw, CompletableFuture<TurnStep> result) {
        var physics = SparkLevel.getPhysicsLevel(core.getLevel());
        core.writeConditionSnapshot(MechaConditionSnapshot.builder().viewYaw(yaw).build());
        physics.submitImmediateTask(PPhase.POST, () -> {
            physics.submitDelayedTask(PPhase.POST, 6, () -> {
                // 身体以前轴 -Z 读数，控制器以前轴 +Z 读数。
                result.complete(new TurnStep(bodyYaw(core.getBody()),
                        Mth.wrapDegrees(bodyYaw(core.getRootSubPart().body) + 180f)));
                return null;
            });
            return null;
        });
    }

    /** 以 Minecraft 偏航读取局部前轴的水平投影。 */
    private static float bodyYaw(com.jme3.bullet.objects.PhysicsRigidBody body) {
        var rotation = body.getPhysicsRotationMatrix(new com.jme3.math.Matrix3f());
        return (float) Math.toDegrees(Math.atan2(-rotation.get(0, 2), rotation.get(2, 2)));
    }

    @GameTest(template = "empty_platform", batch = "armsBody", timeoutTicks = 100)
    public static void bodyCompositionRoundTripsWithoutEnteringPhysics(GameTestHelper helper) {
        BodyPart first = BodyParts.createDefault(helper.getLevel());
        ArmsCore source = new ArmsCore(helper.getLevel(), UUID.randomUUID(), first);
        ArmsCore restored = null;
        BodyPart skeleton = null;
        try {
            // 新建构造器接收调用者准备好的核心，装配体内部不另造一份核心。
            helper.assertTrue(source.getCorePart() == first, "构造器必须保留传入的核心实例");
            helper.assertTrue(first != null && first.rootSubPart != null, "Default body has a physical root");
            var sourceView = source.getPartMap();
            BodyPart second = (BodyPart) PartFactory.create(first.type, "default", helper.getLevel(), BodyPart.CONTEXT);
            source.addPart(second);
            helper.assertTrue(source.getPartMap() == sourceView && sourceView.size() == 2,
                    "成员表视图身份固定，添加成员只改变内容");
            source.setAssemblyName("body probe");
            helper.assertTrue(first.rootSubPart != second.rootSubPart && first.rootSubPart.body != second.rootSubPart.body,
                    "Instances own distinct SubParts and rigid bodies");
            helper.assertTrue(first.rootSubPart.attr == second.rootSubPart.attr
                    && first.rootSubPart.collisionShape == second.rootSubPart.collisionShape,
                    "Same-side instances share the preset and collision shape");
            helper.assertTrue(first.rootSubPart.getBones().size() == 1 && first.rootSubPart.collisionShape.countChildren() == 1,
                    "Minimal model has one bone and one collision cube");
            first.setSharedDurability(7f);
            for (int tick = 0; tick < 10; tick++) source.tickBody();
            helper.assertTrue(!first.isDestroyed() && !second.isDestroyed(), "Bodies survive membership ticks");
            helper.assertTrue(!first.rootSubPart.body.isInWorld() && !second.rootSubPart.body.isInWorld()
                    && first.rootSubPart.entity == null, "No world bodies or MM proxies");
            helper.assertTrue(!first.crowbarRemovable && !second.crowbarRemovable, "Bodies deny crowbar removal");

            ArmsBodyData saved = ArmsBodyData.codec(false).parse(NbtOps.INSTANCE, source.saveBodyTag()).getOrThrow();
            helper.assertTrue(saved.frameId().isEmpty(), "直接构造的身体不需要素体引用");
            // 反序列化入口只接收保存数据，从其中恢复核心与成员。
            restored = new ArmsCore(helper.getLevel(), UUID.randomUUID(), saved);
            helper.assertTrue(restored.getPartMap().size() == 2 && restored.getCorePart().uuid.equals(first.uuid),
                    "Membership and selected core survive NBT");
            helper.assertTrue(restored.getCorePart() == restored.findPart(first.uuid)
                    && restored.getCorePart().assembly == restored, "Core references the canonical owned instance");
            BodyPart restoredFirst = (BodyPart) restored.findPart(first.uuid);
            helper.assertTrue(Math.abs(restoredFirst.getSharedDurability() - 7f) < 0.001f
                    && restored.getAssemblyName().equals("body probe"), "Full state restores damage and name");
            helper.assertTrue(!restoredFirst.rootSubPart.body.isInWorld(), "Restore does not enter physics");
            skeleton = (BodyPart) PartFactory.restore(first.save(), helper.getLevel(), PartRestoreMode.STRUCTURE_ONLY, BodyPart.CONTEXT);
            helper.assertTrue(Math.abs(skeleton.getSharedDurability() - skeleton.getSharedMaxDurability()) < 0.001f,
                    "Structure-only restore skips damage");
            BodyPartData incomplete = new BodyPartData(first.type.getRegistryKey(), first.name, first.uuid.toString(),
                    "default", 1f, "default", Map.of());
            var previous = restored.getPartMap();
            boolean rejected = false;
            try {
                restored.applyBodySnapshot(new ArmsBodyData("invalid", first.uuid, List.of(incomplete)));
            } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected && restored.getPartMap() == previous && !restoredFirst.isDisposed(),
                    "Invalid replacement preserves the previous body");
            helper.assertTrue(source.acceptsPartType(PartDefinitions.regular(false).values().iterator().next()),
                    "ArmsCore can host regular parts alongside body parts");
            boolean coreRemovalRejected = false;
            try { source.removePart(first); }
            catch (IllegalArgumentException expected) { coreRemovalRejected = true; }
            helper.assertTrue(coreRemovalRejected && source.getCorePart() == first, "核心随装配体存在，不能单独移除");
            boolean coreReplacementRejected = false;
            try { restored.applyBodySnapshot(new ArmsBodyData(saved.name(), second.uuid, saved.parts())); }
            catch (IllegalArgumentException expected) { coreReplacementRejected = true; }
            helper.assertTrue(coreReplacementRejected && restored.getCorePart() == restoredFirst,
                    "快照不能更换核心身份");
            source.removePart(second);
            helper.assertTrue(second.assembly == null && second.isDisposed() && source.findPart(second.uuid) == null,
                    "Removal clears ownership and disposes the member");
            helper.assertTrue(cn.solarmoon.spark_core.physics.body.PhysicsBodyExtensionKt.getOwner(second.rootSubPart.body) == null,
                    "Disposal clears the owner of an unattached rigid body");
            var previousCoreBody = restoredFirst.rootSubPart.body;
            var previousSecond = (BodyPart) restored.findPart(second.uuid);
            // 更新已有机体时保留核心及容器；只替换普通成员并更新核心状态。
            first.setSharedDurability(9f);
            first.name = "核心同步名称";
            restored.applyBodySnapshot(source.saveBody());
            helper.assertTrue(restored.getPartMap() == previous && restored.getPartMap().size() == 1
                    && restored.getCorePart() == restoredFirst && restoredFirst.rootSubPart.body == previousCoreBody
                    && !restoredFirst.isDisposed() && previousSecond.isDisposed()
                    && Math.abs(restoredFirst.getSharedDurability() - 9f) < 0.001f
                    && restoredFirst.name.equals("核心同步名称"),
                    "组成更新必须保持核心、核心刚体和成员容器身份稳定");
            helper.succeed();
        } finally {
            if (skeleton != null) skeleton.destroy();
            source.disposeBody();
            if (restored != null) restored.disposeBody();
        }
    }
}
