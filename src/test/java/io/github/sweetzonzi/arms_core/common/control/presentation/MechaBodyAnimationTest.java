package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.animation.IAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimController;
import cn.solarmoon.spark_core.animation.anim.AnimInstance;
import cn.solarmoon.spark_core.animation.anim.origin.OAnimationSet;
import cn.solarmoon.spark_core.animation.model.ModelController;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.animation.model.origin.OModel;
import cn.solarmoon.spark_core.pack.graph.SparkPackMetaInfo;
import cn.solarmoon.spark_core.pack.graph.SparkPackage;
import cn.solarmoon.spark_core.pack.modules.ModelModule;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import lombok.Getter;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MechaBodyAnimationTest {
    private static final String PACK = "/spark_modules/arms_core.builtin/arms_core/";
    private static final ModelIndex MODEL = new ModelIndex("frame", ResourceLocation.parse("arms_core:shared_animation_test"));

    @Test
    void sharedWalkClipMovesEveryHipAndKneeAlongItsBoneChain() throws IOException {
        var empty = Component.empty();
        var pack = new SparkPackage(new SparkPackMetaInfo(MODEL.getLocation(), "1", empty, empty, empty, List.of()), new HashMap<>());
        new ModelModule().read("arms_core", List.of("frame"), "shared_animation_test.json",
                resource("models/frame/bmpt.json"), pack, false, true);
        try {
            OModel rig = OModel.getOrEmpty(MODEL);
            var holder = new TestTarget();
            holder.getMolangContext().setMotion(5, 0);
            String animations = new String(resource("animations/frame/bmpt/bmpt_main.animation.json"), StandardCharsets.UTF_8);
            var animation = OAnimationSet.getCODEC().parse(JsonOps.INSTANCE, JsonParser.parseString(animations))
                    .getOrThrow().getAnimation(MechaAnimPreset.WALK);
            assertNotNull(animation);
            var instance = new AnimInstance(holder, animation);
            instance.setInTransitionTime(0);
            instance.enter();
            instance.physTick(1);

            for (var entry : Map.of("LeftLeg", "AllBody", "RightLeg", "AllBody",
                    "LeftLowerLeg", "LeftLeg", "RightLowerLeg", "RightLeg").entrySet()) {
                var chain = MechaBodyAnimation.chainBetween(rig, entry.getValue(), entry.getKey());
                assertFalse(chain.isEmpty(), entry.getKey());
                var inverseRest = MechaBodyAnimation.relativeRotation(chain, name -> Vec3.ZERO).conjugate();
                float min = Float.POSITIVE_INFINITY;
                float max = Float.NEGATIVE_INFINITY;
                for (int step = 0; step < 60; step++) {
                    instance.setTime(animation.getAnimationLength() * step / 60);
                    var sampled = MechaBodyAnimation.relativeRotation(chain,
                            name -> holder.getAnimController().blendBone(name).getRotation());
                    float angle = MechaBodyAnimation.xRotation(sampled.premul(inverseRest));
                    assertTrue(Float.isFinite(angle), entry.getKey());
                    min = Math.min(min, angle);
                    max = Math.max(max, angle);
                }
                assertTrue(max - min > Math.toRadians(5), entry.getKey() + " 缺少有效摆动");
            }
        } finally {
            OModel.getORIGINS().remove(MODEL);
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = MechaBodyAnimationTest.class.getResourceAsStream(PACK + path)) {
            assertNotNull(stream, path);
            return stream.readAllBytes();
        }
    }

    @Getter
    private static final class TestTarget implements IAnimatable<io.github.sweetzonzi.arms_core.common.ArmsCore> {
        private final ModelController modelController = new ModelController(this);
        private final AnimController animController = new AnimController(this);
        private final Map<String, Object> variables = new HashMap<>();
        private final MechaMolangContext molangContext = new MechaMolangContext(this);

        @Override public io.github.sweetzonzi.arms_core.common.ArmsCore getAnimatable() { return null; }
        @Override public Level getAnimLevel() { return null; }
        @Override public ModelIndex getDefaultModelIndex() { return MODEL; }
        @Override public Matrix4f getWorldPositionMatrix(Number partialTicks) { return new Matrix4f(); }
    }
}
