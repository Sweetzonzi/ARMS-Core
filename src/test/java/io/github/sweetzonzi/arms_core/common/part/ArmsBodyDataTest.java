package io.github.sweetzonzi.arms_core.common.part;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArmsBodyDataTest {
    private static BodyPartData part(UUID uuid) {
        return new BodyPartData(ResourceLocation.parse("arms_core:minimal_body"), "body", uuid.toString(),
                "default", 0.5f, "default", Map.of());
    }

    @Test void compositionRejectsAmbiguousIdentities() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ArmsBodyData data = new ArmsBodyData("sample", second, List.of(part(first), part(second)));
        assertTrue(data.validate().isSuccess());
        assertTrue(new ArmsBodyData("", first, List.of(part(first), part(first))).validate().error().isPresent());
        assertTrue(new ArmsBodyData("", second, List.of(part(first))).validate().error().isPresent());
        assertTrue(new ArmsBodyData("", null, List.of(part(first))).validate().error().isPresent());
        assertTrue(new ArmsBodyData("", first, List.of()).validate().error().isPresent());
        assertTrue(ArmsBodyData.codec(false).parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).error().isPresent());
    }

    @Test void collisionModelIsOneBoneOneCubeWithoutUvOrTextureAssets() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/spark_modules/arms_core.builtin/arms_core/models/part/minimal_body.json")) {
            assertNotNull(input);
            var root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            var geometry = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            var bones = geometry.getAsJsonArray("bones");
            assertEquals(1, bones.size());
            assertEquals("body", bones.get(0).getAsJsonObject().get("name").getAsString());
            var cubes = bones.get(0).getAsJsonObject().getAsJsonArray("cubes");
            assertEquals(1, cubes.size());
            assertFalse(cubes.get(0).getAsJsonObject().has("uv"));
            assertFalse(root.has("textures"));
        }
    }
}
