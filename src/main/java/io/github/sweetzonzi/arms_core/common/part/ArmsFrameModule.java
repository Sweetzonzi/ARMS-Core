package io.github.sweetzonzi.arms_core.common.part;

import cn.solarmoon.spark_core.pack.diagnostic.PackCodec;
import cn.solarmoon.spark_core.pack.diagnostic.PackContentException;
import cn.solarmoon.spark_core.pack.diagnostic.PackSeverity;
import cn.solarmoon.spark_core.pack.diagnostic.SparkPackIssues;
import cn.solarmoon.spark_core.pack.diagnostic.event.PackAllFinishedEvent;
import cn.solarmoon.spark_core.pack.graph.SparkPackage;
import cn.solarmoon.spark_core.pack.modules.SparkPackModule;
import io.github.sweetzonzi.machine_max.common.mech.part.PartDefinitions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分逻辑端暂存素体，在 MM 部件发布后一次性公开合法定义。 */
public final class ArmsFrameModule implements SparkPackModule {
    public static final ArmsFrameModule INSTANCE = new ArmsFrameModule();
    public static final String ID = "arms_frames";
    private final Side server = new Side();
    private final Side client = new Side();

    private ArmsFrameModule() {}

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public void onStart(boolean clientSide, boolean fromServer) {
        if (!fromServer) return;
        Side side = side(clientSide);
        side.candidates.clear();
        side.published = Map.of();
    }

    @Override
    public void read(String namespace, List<String> pathSegments, String fileName, byte[] content,
                     SparkPackage pack, boolean clientSide, boolean fromServer) {
        if (!fromServer || !fileName.endsWith(".json")) return;
        var id = ResourceLocation.fromNamespaceAndPath(namespace, fileName.substring(0, fileName.length() - 5));
        var candidates = side(clientSide).candidates;
        // 同名覆盖的文件解码失败时，也不能继续使用被覆盖的候选。
        candidates.remove(id);
        String location = namespace + ":" + ID + "/" +
                (pathSegments.isEmpty() ? "" : String.join("/", pathSegments) + "/") + fileName;
        candidates.put(id, new Candidate(PackCodec.read(ArmsFrame.CODEC, content), location));
    }

    /** 在本端全部模块结束后解析跨模块引用，无效候选只进诊断，不进权威表。 */
    public void publish(PackAllFinishedEvent event) {
        var context = event.getContext();
        if (!context.getFromServer()) return;
        boolean clientSide = context.isClientSide();
        Side side = side(clientSide);
        var next = new LinkedHashMap<ResourceLocation, ArmsFrame>();
        var definitions = PartDefinitions.all(clientSide);
        for (var entry : side.candidates.entrySet()) {
            Candidate candidate = entry.getValue();
            try {
                candidate.frame.validateDefinitions(definitions);
                next.put(entry.getKey(), candidate.frame);
            } catch (PackContentException error) {
                SparkPackIssues.report(ID, candidate.location, PackSeverity.ERROR, error.getUserMessage(), null, context);
            } catch (RuntimeException error) {
                SparkPackIssues.report(ID, candidate.location, PackSeverity.ERROR,
                        Component.literal(error.getMessage() == null ? error.toString() : error.getMessage()), error, context);
            }
        }
        side.published = Map.copyOf(next);
        side.candidates.clear();
    }

    /** 获取本端已校验定义；装载中或定义无效时返回空。 */
    public @Nullable ArmsFrame get(boolean clientSide, ResourceLocation id) {
        return side(clientSide).published.get(id);
    }

    private Side side(boolean clientSide) {
        return clientSide ? client : server;
    }

    private record Candidate(ArmsFrame frame, String location) {}

    private static final class Side {
        private final Map<ResourceLocation, Candidate> candidates = new LinkedHashMap<>();
        private volatile Map<ResourceLocation, ArmsFrame> published = Map.of();
    }
}
