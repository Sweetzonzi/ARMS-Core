package io.github.sweetzonzi.arms_core.common.part;

import cn.solarmoon.spark_core.util.SparkMathKt;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.mojang.datafixers.util.Pair;
import io.github.sweetzonzi.machine_max.common.mech.part.AbstractPart;
import io.github.sweetzonzi.machine_max.common.mech.part.PartDefinitions;
import io.github.sweetzonzi.machine_max.common.mech.part.PartFactory;
import io.github.sweetzonzi.machine_max.common.mech.part.PartRestoreMode;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.RegularPartType;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.SubPart;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.connector.AbstractConnector;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.connector.SimpleConnector;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.data.ConnectionData;
import io.github.sweetzonzi.machine_max.util.data.PosRot;
import jme3utilities.math.MyMath;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 在入世之前准备完整身体。负责新建布局和恢复参考系，装配归属由 ArmsCore 设置。 */
public record ArmsBodyAssembly(BodyPart core, List<AbstractPart<?>> parts, List<Link> links, String name) {
    private static final Comparator<ArmsFrame.Endpoint> ENDPOINT_ORDER = Comparator
            .comparing(ArmsFrame.Endpoint::member).thenComparing(ArmsFrame.Endpoint::subPart)
            .thenComparing(ArmsFrame.Endpoint::connector);

    /** 真实外部边及其安装时参考系。客户端保留端点与参考系供展示使用。 */
    public record Link(AbstractConnector advanced, SimpleConnector simple, Transform frameA, Transform frameS) {
        public ConnectionData save() {
            return new ConnectionData(advanced.subPart.part.uuid.toString(), advanced.subPart.name, advanced.name,
                    new PosRot(frameA.clone()), simple.subPart.part.uuid.toString(), simple.subPart.name,
                    simple.name, new PosRot(frameS.clone()));
        }

        /** 构造服务器的约束，刚体及约束此时均留在物理空间之外。 */
        private void attach() {
            advanced.actualTransform = frameA.clone();
            simple.actualTransform = frameS.clone();
            if (!advanced.attach(simple)) throw new IllegalArgumentException("无法建立身体连接：" + save());
        }

        /** 快照复用现有核心时，将边的核心端重新指向正式实例。 */
        public Link withCore(BodyPart core) {
            return new Link(remap(advanced, core), (SimpleConnector) remap(simple, core), frameA, frameS);
        }

        private static AbstractConnector remap(AbstractConnector endpoint, BodyPart core) {
            return endpoint.subPart.part.uuid.equals(core.uuid)
                    ? core.subParts.get(endpoint.subPart.name).connectors.get(endpoint.name) : endpoint;
        }
    }

    /** 从定义分配成员，生成树只负责布放，随后用整张图建立关节。 */
    public static ArmsBodyAssembly create(Level level, ArmsFrame frame, Transform rootPose) {
        var definitions = PartDefinitions.all(level.isClientSide());
        frame.validateDefinitions(definitions);
        Map<String, AbstractPart<?>> members = new HashMap<>();
        try {
            for (var entry : frame.parts().entrySet()) {
                var member = entry.getValue();
                var part = PartFactory.create(definitions.get(member.part()), member.variant(), level, BodyPart.CONTEXT);
                members.put(entry.getKey(), part);
                validateInternalStructure(part);
            }
            var core = (BodyPart) members.get(frame.core());
            // 在原点附近闭合挂点，避免远离世界原点时单精度坐标吞掉误差。
            core.setTransform(new Transform(Vector3f.ZERO, rootPose.getRotation()));
            var links = layout(frame, members);
            for (var part : members.values()) {
                var pose = part.rootSubPart.body.getTransform(null);
                pose.setTranslation(pose.getTranslation().add(rootPose.getTranslation()));
                part.setTransform(pose);
            }
            links.forEach(Link::attach);
            return new ArmsBodyAssembly(core, List.copyOf(members.values()), links, "ArmsCore");
        } catch (RuntimeException | Error failure) {
            members.values().forEach(AbstractPart::discardUnattached);
            throw failure;
        }
    }

    /** 先验证存档结构，随后按保存位姿和参考系恢复约束。 */
    public static ArmsBodyAssembly restore(Level level, ArmsBodyData data, boolean attachJoints) {
        data.validate().getOrThrow();
        for (var saved : data.parts()) {
            var type = PartFactory.validateRestore(saved, level.isClientSide());
            if (!(type instanceof BodyPartType || type instanceof RegularPartType)
                    || !type.getVariant(saved.variant).subParts.keySet().equals(saved.subParts.keySet())) {
                throw new IllegalArgumentException("身体定义无效或保存结构不完整：" + saved.registryKey);
            }
        }
        Map<UUID, AbstractPart<?>> members = new HashMap<>();
        try {
            for (var saved : data.parts()) {
                var part = PartFactory.restore(saved, level, PartRestoreMode.FULL_STATE, BodyPart.CONTEXT);
                members.put(part.uuid, part);
                validateInternalStructure(part);
            }
            var core = (BodyPart) members.get(data.corePartId());
            var links = new ArrayList<Link>();
            for (var saved : data.connections()) {
                var a = resolve(members.get(UUID.fromString(saved.partUuidA)), saved.subPartNameA, saved.advConnectorName);
                var b = resolve(members.get(UUID.fromString(saved.partUuidS)), saved.subPartNameS, saved.simpleConnectorName);
                if (!(b instanceof SimpleConnector simple)) {
                    throw new IllegalArgumentException("保存连接的对接端必须是简单连接点：" + saved);
                }
                links.add(new Link(a, simple, saved.posRotA.toTransform(), saved.posRotS.toTransform()));
            }
            if (attachJoints) links.forEach(Link::attach);
            return new ArmsBodyAssembly(core, List.copyOf(members.values()), List.copyOf(links), data.name());
        } catch (RuntimeException | Error failure) {
            members.values().forEach(AbstractPart::discardUnattached);
            throw failure;
        }
    }

    /** 检查 MM 的内部配对结果，禁止任何孤立子刚体随整件发布。 */
    private static void validateInternalStructure(AbstractPart<?> part) {
        var visited = new HashSet<SubPart>();
        var pending = new ArrayDeque<SubPart>();
        visited.add(part.rootSubPart);
        pending.add(part.rootSubPart);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            for (var connector : current.connectors.values()) {
                if (!connector.internal) continue;
                var other = connector.attachedConnector;
                if (other == null || other.subPart.part != part || connector.joint == null) {
                    throw new IllegalArgumentException("内部连接未配对：" + part.name + "/" + current.name + "/" + connector.name);
                }
                if (visited.add(other.subPart)) pending.addLast(other.subPart);
            }
        }
        if (visited.size() != part.subParts.size()) throw new IllegalArgumentException("子部件未连接到部件根：" + part.name);
    }

    private static List<Link> layout(ArmsFrame frame, Map<String, AbstractPart<?>> members) {
        var edges = frame.connections().stream()
                .map(edge -> ENDPOINT_ORDER.compare(edge.a(), edge.b()) <= 0
                        ? edge : new ArmsFrame.Connection(edge.b(), edge.a()))
                .sorted(Comparator.comparing(ArmsFrame.Connection::a, ENDPOINT_ORDER)
                        .thenComparing(ArmsFrame.Connection::b, ENDPOINT_ORDER)).toList();
        Map<String, List<ArmsFrame.Connection>> incident = new HashMap<>();
        members.keySet().forEach(name -> incident.put(name, new ArrayList<>()));
        Map<ArmsFrame.Connection, Link> links = new HashMap<>();
        for (var edge : edges) {
            incident.get(edge.a().member()).add(edge);
            incident.get(edge.b().member()).add(edge);
            var a = resolve(members.get(edge.a().member()), edge.a().subPart(), edge.a().connector());
            var b = resolve(members.get(edge.b().member()), edge.b().subPart(), edge.b().connector());
            var pair = b instanceof SimpleConnector simple ? Pair.of(a, simple) : Pair.of(b, (SimpleConnector) a);
            links.put(edge, new Link(pair.getFirst(), pair.getSecond(), pair.getFirst().offsetFromMassCenter.clone(),
                    pair.getFirst().calculateExtraTransform(pair.getSecond(), 0f)));
        }

        var placed = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        placed.add(frame.core());
        pending.add(frame.core());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            for (var edge : incident.get(current)) {
                String next = edge.a().member().equals(current) ? edge.b().member() : edge.a().member();
                if (!placed.add(next)) continue;
                placePart(links.get(edge), members.get(next));
                pending.addLast(next);
            }
        }
        // 树外边也保留；闭合检查不再挪动成员，也不改写模型枢轴。
        for (var edge : edges) {
            var link = links.get(edge);
            var a = worldFrame(link.advanced, link.frameA);
            var b = worldFrame(link.simple, link.frameS);
            if (a.getTranslation().subtract(b.getTranslation()).lengthSquared() > 1e-8f
                    || Math.abs(SparkMathKt.toQuaternionf(a.getRotation()).dot(SparkMathKt.toQuaternionf(b.getRotation())))
                    < 0.999999f) {
                throw new IllegalArgumentException("素体挂点没有闭合：" + edge);
            }
        }
        return edges.stream().map(links::get).toList();
    }

    private static void placePart(Link link, AbstractPart<?> movingPart) {
        boolean movingIsSimple = link.simple.subPart.part == movingPart;
        var moving = movingIsSimple ? link.simple : link.advanced;
        var fixed = movingIsSimple ? worldFrame(link.advanced, link.frameA) : worldFrame(link.simple, link.frameS);
        var movingLocal = movingIsSimple ? link.frameS : link.frameA;
        // 连接点可以在非根 SubPart 上，先求它的目标位姿，再把整件按原相对布局移动。
        var target = MyMath.combine(movingLocal.invert(), fixed, new Transform());
        var rootInMoving = MyMath.combine(movingPart.rootSubPart.body.getTransform(null),
                moving.subPart.body.getTransform(null).invert(), new Transform());
        movingPart.setTransform(MyMath.combine(rootInMoving, target, new Transform()));
    }

    private static Transform worldFrame(AbstractConnector endpoint, Transform local) {
        return MyMath.combine(local, endpoint.subPart.body.getTransform(null), new Transform());
    }

    private static AbstractConnector resolve(AbstractPart<?> part, String subPart, String connector) {
        var child = part.subParts.get(subPart);
        var endpoint = child == null ? null : child.connectors.get(connector);
        if (endpoint == null || endpoint.internal || endpoint.hasPart()) {
            throw new IllegalArgumentException("找不到可用的外部连接点：" + part.name + "/" + subPart + "/" + connector);
        }
        return endpoint;
    }
}
