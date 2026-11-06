package io.github.sweetzonzi.arms_core.common.part;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 素体解码约束：允许闭环，拒绝占点冲突和不可达成员。 */
class ArmsFrameTest {
    @Test
    void preservesCyclesAndParallelEdges() {
        var json = JsonParser.parseString("""
                {
                  "core": "torso",
                  "parts": {
                    "torso": {"part": "arms_core:allbody_torso"},
                    "leg": {"part": "arms_core:allbody_thigh", "variant": "left"},
                    "brace": {"part": "machine_max:brace"}
                  },
                  "connections": [
                    {"a":{"member":"torso","sub_part":"body","connector":"hip"},
                     "b":{"member":"leg","sub_part":"body","connector":"hip"}},
                    {"a":{"member":"leg","sub_part":"body","connector":"knee"},
                     "b":{"member":"brace","sub_part":"body","connector":"lower"}},
                    {"a":{"member":"torso","sub_part":"body","connector":"back"},
                     "b":{"member":"brace","sub_part":"body","connector":"upper"}},
                    {"a":{"member":"torso","sub_part":"body","connector":"side"},
                     "b":{"member":"brace","sub_part":"body","connector":"side"}}
                  ]
                }
                """).getAsJsonObject();
        var decoded = ArmsFrame.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(4, decoded.connections().size());
        assertEquals("default", decoded.parts().get("torso").variant());
        assertEquals("left", decoded.parts().get("leg").variant());
        assertEquals(decoded, ArmsFrame.CODEC.parse(JsonOps.INSTANCE,
                ArmsFrame.CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow()).getOrThrow());

        // 同一部件可有多条边，但端点不能被再次占用。
        var edges = json.getAsJsonArray("connections");
        edges.add(edges.get(0).deepCopy());
        assertTrue(ArmsFrame.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        edges.asList().clear();
        assertTrue(ArmsFrame.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
    }
}
