package io.github.sweetzonzi.arms_core.common.gametest;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.part.*;
import io.github.sweetzonzi.machine_max.common.mech.part.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

            ArmsBodyData saved = ArmsBodyData.CODEC.parse(NbtOps.INSTANCE, source.saveBodyTag()).getOrThrow();
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
            helper.assertTrue(!source.acceptsPartType(PartDefinitions.regular(false).values().iterator().next()),
                    "Only implemented body definitions are accepted");
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
