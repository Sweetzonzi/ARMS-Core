package io.github.sweetzonzi.arms_core.common.part;

import cn.solarmoon.spark_core.pack.diagnostic.PackContentException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPartType;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.RegularPartType;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.attr.VariantAttr;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.attr.connector.ConnectorAttr;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 素体的静态装配定义；成员名只在当前定义内寻址。 */
public record ArmsFrame(String core, Map<String, Member> parts, List<Connection> connections) {
    private static final Codec<String> NAME = Codec.STRING.validate(name -> name.isBlank()
            ? DataResult.error(() -> "名称不能为空") : DataResult.success(name));
    public static final Codec<ArmsFrame> CODEC = RecordCodecBuilder.<ArmsFrame>create(instance -> instance.group(
            NAME.fieldOf("core").forGetter(ArmsFrame::core),
            Codec.unboundedMap(NAME, Member.CODEC).fieldOf("parts").forGetter(ArmsFrame::parts),
            Connection.CODEC.listOf().fieldOf("connections").forGetter(ArmsFrame::connections)
    ).apply(instance, ArmsFrame::new)).validate(ArmsFrame::validateTopology);

    public ArmsFrame {
        parts = Map.copyOf(parts);
        connections = List.copyOf(connections);
    }

    /** 一个新建成员引用的部件定义与变体。 */
    public record Member(ResourceLocation part, String variant) {
        public static final Codec<Member> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("part").forGetter(Member::part),
                NAME.optionalFieldOf("variant", "default").forGetter(Member::variant)
        ).apply(instance, Member::new));
    }

    /** 跨部件连接的具体端点，按成员、SubPart、Connector 寻址。 */
    public record Endpoint(String member, String subPart, String connector) {
        public static final Codec<Endpoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                NAME.fieldOf("member").forGetter(Endpoint::member),
                NAME.fieldOf("sub_part").forGetter(Endpoint::subPart),
                NAME.fieldOf("connector").forGetter(Endpoint::connector)
        ).apply(instance, Endpoint::new));
    }

    /** 无向结构边；两端书写顺序没有驱动含义。 */
    public record Connection(Endpoint a, Endpoint b) {
        public static final Codec<Connection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Endpoint.CODEC.fieldOf("a").forGetter(Connection::a),
                Endpoint.CODEC.fieldOf("b").forGetter(Connection::b)
        ).apply(instance, Connection::new));
    }

    /** 检查装配图的成员和占点；可达性允许闭环和使用不同端点的平行边。 */
    private DataResult<ArmsFrame> validateTopology() {
        if (!parts.containsKey(core)) return DataResult.error(() -> "core 未引用素体成员：" + core);
        Map<String, Set<String>> neighbors = new HashMap<>();
        parts.keySet().forEach(member -> neighbors.put(member, new HashSet<>()));
        Set<Endpoint> occupied = new HashSet<>();
        for (Connection edge : connections) {
            if (!parts.containsKey(edge.a.member) || !parts.containsKey(edge.b.member)) {
                return DataResult.error(() -> "连接引用了未知成员：" + edge);
            }
            if (edge.a.member.equals(edge.b.member)) return DataResult.error(() -> "不能连接同一成员：" + edge);
            if (!occupied.add(edge.a) || !occupied.add(edge.b)) {
                return DataResult.error(() -> "连接点重复占用：" + edge);
            }
            neighbors.get(edge.a.member).add(edge.b.member);
            neighbors.get(edge.b.member).add(edge.a.member);
        }
        // 邻接索引只用于可达性；实际连接列表保留每一条边。
        var visited = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        visited.add(core);
        pending.add(core);
        while (!pending.isEmpty()) {
            for (String neighbor : neighbors.get(pending.removeFirst())) {
                if (visited.add(neighbor)) pending.addLast(neighbor);
            }
        }
        if (visited.size() != parts.size()) {
            return DataResult.error(() -> "存在无法连接到核心的成员：" +
                    parts.keySet().stream().filter(member -> !visited.contains(member)).sorted().toList());
        }
        return DataResult.success(this);
    }

    /** 部件发布后解析型号、变体与两端连接资格；此过程不构造运行时部件。 */
    public void validateDefinitions(Map<ResourceLocation, AbstractPartType> definitions) {
        var variants = new HashMap<String, VariantAttr>();
        for (var entry : parts.entrySet()) {
            String name = entry.getKey();
            Member member = entry.getValue();
            var type = definitions.get(member.part);
            if (!(type instanceof BodyPartType) && !(type instanceof RegularPartType)) {
                throw error(name + " 引用了不存在或不受支持的部件：" + member.part);
            }
            if (name.equals(core) && !(type instanceof BodyPartType)) {
                throw error("核心必须是 BodyPart：" + name);
            }
            var variant = type.variants.get(member.variant);
            if (variant == null) throw error(name + " 的部件 " + member.part + " 不存在变体 " + member.variant);
            variants.put(name, variant);
        }
        for (Connection edge : connections) {
            var a = resolveConnector(edge.a, variants);
            var b = resolveConnector(edge.b, variants);
            if (!a.isSimpleConnector() && !b.isSimpleConnector()) {
                throw error("至少一端必须是简单连接点：" + edge);
            }
            Member memberA = parts.get(edge.a.member);
            Member memberB = parts.get(edge.b.member);
            // 素体发布可能早于服务器配置加载，按定义检查两端标签。
            if (!a.matchesTags(definitions.get(memberB.part), memberB.variant)
                    || !b.matchesTags(definitions.get(memberA.part), memberA.variant)) {
                throw error("两端部件不满足连接标签条件：" + edge);
            }
        }
    }

    /** 只允许素体连接外部端点，内部关节由部件定义负责。 */
    private static ConnectorAttr resolveConnector(Endpoint endpoint, Map<String, VariantAttr> variants) {
        var subPart = variants.get(endpoint.member).subParts.get(endpoint.subPart);
        var attr = subPart == null ? null : subPart.connectors.get(endpoint.connector);
        if (attr == null || attr.internal) throw error("找不到可用的外部连接点：" + endpoint);
        return attr;
    }

    private static PackContentException error(String message) {
        return new PackContentException(Component.literal(message));
    }
}
