package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.animation.IAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimGroups;
import cn.solarmoon.spark_core.animation.anim.AnimInstance;
import cn.solarmoon.spark_core.animation.anim.origin.OAnimation;
import cn.solarmoon.spark_core.animation.anim.origin.OAnimationSet;
import cn.solarmoon.spark_core.animation.model.ModelController;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.origin.OModel;
import cn.solarmoon.spark_core.animation.state.MultiAnimStateMachine;
import cn.solarmoon.spark_core.api.SparkLevel;
import cn.solarmoon.spark_core.gas.GameplayTagContainer;
import cn.solarmoon.spark_core.molang.SparkMolangContext;
import cn.solarmoon.spark_core.state_machine.graph.StateMachineGraph;
import cn.solarmoon.spark_core.state_machine.graph.StateVariableContainer;
import io.github.sweetzonzi.arms_core.ARMS;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 机娘表现层编排器 —— 常驻并行动画层与四台姿态状态机的持有者与推进者。
 * <p>
 * 它由 {@code common/control/MechaControl.java} 持有并随每个物理步推进（见该文件
 * {@code #onPhysicsStep}），因此表现层与逻辑层同处一个物理线程，条件读取的是逻辑层刚写下的变量。
 * 它不认识渲染：动画目标以 {@link IAnimatable} 的形式从外部注入，客户端那一种实现
 * （{@code client/MechaAnimatable.java}）留在 client 包，本类不引用它。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li><b>常驻并行动画层</b>：每个槽位一个独立层，各放一个永不停下的循环实例。这些实例负责只在
 *       {@code pre_parallel*} 里出现的骨骼（表情、挂架、装甲），姿态机的层覆盖不到它们。</li>
 *   <li><b>四台姿态状态机</b>：每台一台 {@link MultiAnimStateMachine}，共用
 *       {@link MechaAnimGroups#LOCOMOTION} 层，靠各自 {@code inactive} 实现互斥。</li>
 *   <li><b>时间推进</b>：按目标逐个调 {@code AnimController#physTick()}。这一步不能省：
 *       状态机只负责在各目标上起实例，不负责推进它们的时间与权重
 *       （{@code ../Spark-Core/src/main/kotlin/cn/solarmoon/spark_core/animation/state/MultiAnimStateMachine.kt#MultiAnimStateMachine}）。</li>
 *   <li><b>就绪守卫与重建</b>：三个就绪条件到达顺序不保证，且内容包重载会让已入层的实例持有的资源
 *       对象失效，因此入口写成幂等，失效时整体清空并重建。</li>
 * </ol>
 *
 * <h3>线程模型</h3>
 * {@link #attach(List)} 由客户端主线程调用（注入动画目标），只写一个 volatile 引用；
 * {@link #progress(float)} 由物理线程调用，其余字段都只被物理线程访问。
 *
 * <h3>服务端为空转</h3>
 * 权威侧不注入动画目标（首版的唯一目标是客户端对象），因此 {@code targets} 恒为空，
 * {@link #progress(float)} 在第一步就返回。表现层首版只在客户端产出画面。
 *
 * @author Sweetzonzi
 */
public final class MechaAnimPresenter {

    /** 四台姿态状态机与逻辑层共用的变量容器 */
    private final StateVariableContainer variables;

    /** 四台姿态状态机与逻辑层共用的标签容器 */
    private final GameplayTagContainer tags;

    /** 注入的动画目标（主线程写、物理线程读） */
    private volatile List<IAnimatable<?>> targets = List.of();

    /** 是否已注入过。注入是幂等的：第一次非空注入生效，之后不再改变 */
    private final AtomicBoolean attached = new AtomicBoolean(false);

    /** 四台姿态状态机，按 {@link MechaPresentationGraphs#all()} 的顺序 */
    private final List<MultiAnimStateMachine> postureMachines = new ArrayList<>(4);

    /** 常驻层实例；与姿态机同寿命，资源失效时一并清空 */
    private final List<AnimInstance> persistentInstances = new ArrayList<>();

    /** 建立时观察到的资源对象：模型索引 → 当时的动画集与模型 */
    private final Map<ModelIndex, ResourceStamp> resourceStamps = new LinkedHashMap<>();

    /** 四台机器共用的 MoLang 上下文；首版的条件与动作都不求值 MoLang，建好备用 */
    private SparkMolangContext<?> molangContext;

    /** 常驻层与四台机器是否已就位 */
    private boolean started;

    /**
     * @param variables 逻辑层与表现层共用的变量容器；必须与逻辑状态机是同一个实例，
     *                  否则姿态机的条件读不到逻辑层的产出
     * @param tags      共用标签容器，同理由
     */
    public MechaAnimPresenter(StateVariableContainer variables, GameplayTagContainer tags) {
        this.variables = variables;
        this.tags = tags;
    }

    // ==========================================
    // 注入
    // ==========================================

    /**
     * 注入动画目标，幂等。
     * <p>
     * 由客户端主线程在第一次遍历到该装配体时调用一次，内容为单元素列表
     * {@code [mechaAnimatable]}；装配体改为逐零件渲染之后，注入者换成装配体、内容换成全体零件的
     * 动画体，本类不需要改动。
     * <p>
     * 空列表无效：它表达的是「还没准备好」，不是一次注入。
     *
     * @param newTargets 动画目标列表
     */
    public void attach(List<IAnimatable<?>> newTargets) {
        if (newTargets == null || newTargets.isEmpty()) return;
        if (!attached.compareAndSet(false, true)) return;
        this.targets = List.copyOf(newTargets);
    }

    /** 当前注入的动画目标；尚未注入时为空列表。 */
    public List<IAnimatable<?>> getTargets() {
        return targets;
    }

    // ==========================================
    // 每物理步
    // ==========================================

    /**
     * 每物理步推进一次：就绪守卫 → 资源失效检查 → 惰性建立 → 状态机推进 → 动画时间推进。
     * <p>
     * <b>必须按物理子步调用，不能按游戏刻调用。</b> {@code AnimInstance#physTick} 每次只推进
     * {@code 1 / tps} 秒，而客户端 {@code tps = 60}、游戏刻是 20 Hz：每游戏刻调用一次会让动画以
     * 三分之一速度播放，并让按秒计时的驻留判据差三倍。
     *
     * @param dt 物理步长 (s)
     */
    public void progress(float dt) {
        List<IAnimatable<?>> current = targets;
        if (current.isEmpty()) return;

        // 判据落在目标粒度：某个目标还没好不该拖累其余目标，因此过滤而不是整体返回
        List<IAnimatable<?>> ready = new ArrayList<>(current.size());
        for (IAnimatable<?> target : current) {
            if (isTargetReady(target)) ready.add(target);
        }
        if (ready.isEmpty()) return;

        // 资源对象失效：清空并重建，本步不再推进（重建在下一步的 start 里完成）
        if (resourcesReplaced(ready)) {
            teardown(current);
            return;
        }

        if (!started) {
            start(ready);
            if (!started) return;
        }

        for (MultiAnimStateMachine machine : postureMachines) {
            machine.progress(dt);
        }
        for (IAnimatable<?> target : current) {
            target.getAnimController().physTick();
        }
    }

    // ==========================================
    // 就绪判据
    // ==========================================

    /**
     * 目标是否可用于建立表现层。两个条件：
     * <ol>
     *   <li><b>内容包已读到本模型的模型与动画集</b>——两个 ORIGINS 都有本模型索引的条目，且当前
     *       模型实例的骨骼姿态非空。动画集与模型是两件独立的事（客户端收到装配体创建包不保证已经
     *       读到内容包），缺动画集时不建立：常驻层会全部静默跳过而无从重试。</li>
     *   <li><b>客户端物理级别已建立</b>——用可空入口判空，不用捕获
     *       {@code getPhysicsLevel()} 的异常：后者在未就绪时抛 IllegalStateException，
     *       拿它做流程控制既慢又会吞掉真正的初始化错误。</li>
     * </ol>
     */
    private static boolean isTargetReady(IAnimatable<?> target) {
        ModelIndex index = target.getDefaultModelIndex();
        if (!OModel.getORIGINS().containsKey(index)) return false;
        if (!OAnimationSet.getORIGINS().containsKey(index)) return false;

        ModelInstance model = target.getModelController().getModel();
        if (model == null || model.getPose().getBonePoseList().isEmpty()) return false;

        Level level = target.getAnimLevel();
        return level != null && SparkLevel.getPhysicsLevelOrNull(level) != null;
    }

    // ==========================================
    // 建立
    // ==========================================

    private void start(List<IAnimatable<?>> ready) {
        List<IAnimatable<?>> readyTargets = List.copyOf(ready);
        molangContext = readyTargets.get(0).getMolangContext();

        startPersistentLayers(readyTargets);
        startPostureMachines(readyTargets);
        captureResourceStamps(readyTargets);
        started = true;
    }

    /**
     * 建常驻并行动画层：一槽位一层，每层一个实例。
     * <p>
     * 起一次即完成，不需要每步干预——动画自身的循环设置让它持续播放，永不结束。
     * <p>
     * 槽位缺失静默跳过：查找走返回可空值的入口，不用找不到即抛异常的构造器；
     * 资源侧的若干个 {@code pre_parallel} 槽位是空占位，替换资源包时也可能整体缺席。
     * <p>
     * 这里是经 {@code AnimInstance#enter()} 投递到物理线程的，而任务在本次物理步的事件处理
     * 返回之后才被消费，因此新建实例的第一个 {@code physTick} 发生在下一个物理步，属预期。
     */
    private void startPersistentLayers(List<IAnimatable<?>> readyTargets) {
        for (Map.Entry<Integer, String> slot : MechaAnimPreset.PERSISTENT_SLOT_ANIMATIONS.entrySet()) {
            int layer = AnimGroups.INSTANCE.persistent(slot.getKey());
            for (IAnimatable<?> target : readyTargets) {
                OAnimation animation = findAnimation(target, slot.getValue());
                if (animation == null) continue;

                AnimInstance instance = new AnimInstance(target, animation);
                instance.setGroup(layer);
                instance.setWeight(1f);
                instance.setInTransitionTime(MechaAnimPreset.PERSISTENT_BLEND_SECONDS);
                instance.setOutTransitionTime(MechaAnimPreset.PERSISTENT_BLEND_SECONDS);
                instance.enter();
                persistentInstances.add(instance);
            }
        }
    }

    /**
     * 建四台姿态状态机。
     * <p>
     * 载体取广播式（{@link MultiAnimStateMachine}）而不是单个动画体的那种：装配体改为逐零件渲染之后
     * 目标要扩成多元素列表，届时不用改图结构，附带收益是三级动画回退可用。
     * <p>
     * 变量与标签容器传的是 {@link #variables} / {@link #tags}（外部实例），不是 null：
     * 传 null 会让控制器自建一个空容器，条件就读不到逻辑层写下的值。
     */
    private void startPostureMachines(List<IAnimatable<?>> readyTargets) {
        for (StateMachineGraph graph : MechaPresentationGraphs.all()) {
            MultiAnimStateMachine machine = new MultiAnimStateMachine(
                    graph,
                    readyTargets,
                    () -> molangContext,
                    null,
                    null,
                    MechaAnimGroups.LOCOMOTION,
                    Map.of(),
                    variables,
                    tags);
            machine.reset();
            postureMachines.add(machine);
        }
    }

    private void captureResourceStamps(List<IAnimatable<?>> readyTargets) {
        resourceStamps.clear();
        for (IAnimatable<?> target : readyTargets) {
            ModelIndex index = target.getDefaultModelIndex();
            if (resourceStamps.containsKey(index)) continue;
            resourceStamps.put(index, new ResourceStamp(
                    OAnimationSet.getORIGINS().get(index),
                    OModel.getORIGINS().get(index)));
        }
    }

    // ==========================================
    // 资源失效与重建
    // ==========================================

    /**
     * 已入层的实例持有的资源对象是否仍然属于当前资源集。
     * <p>
     * 判据取「两个 ORIGINS 里该模型索引对应的对象引用与建立时记录的不同」：内容包重载会先把两个表
     * 清空再重填，重载后取到的是新对象。两个表都要判——只判动画集的话，模型文件被替换而动画文件
     * 没变时，骨骼姿态里留着的仍是旧骨骼。
     */
    private boolean resourcesReplaced(List<IAnimatable<?>> ready) {
        if (resourceStamps.isEmpty()) return false;
        for (IAnimatable<?> target : ready) {
            ModelIndex index = target.getDefaultModelIndex();
            ResourceStamp stamp = resourceStamps.get(index);
            if (stamp == null) continue;
            if (OAnimationSet.getORIGINS().get(index) != stamp.animations) return true;
            if (OModel.getORIGINS().get(index) != stamp.model) return true;
        }
        return false;
    }

    /**
     * 清空表现层并等待重建。
     * <p>
     * <b>必须先清空所有层再丢弃引用</b>：动画实例在层里持有动画对象，直接丢引用会让它成为孤儿，
     * 而重载已经清空了动画集。清空用 {@code AnimController#stopAllAnimation()}，它会逐个请求退出
     * （出场过渡照常走完）。
     * <p>
     * 顺序是「清层 → 停状态机 → 清实例表与资源基线 → 重建模型实例」。重建模型实例是因为内容包重载
     * 之后旧姿态里留着的仍是旧骨骼对象，而既有的补建判据（姿态没有骨骼而模型表里有本模型）
     * 在那时不会成立，重建触发点因此收敛到这一处。贴图位置从旧实例上继承，避免重建后回落到
     * 按模型索引推导的、可能不存在的默认路径。
     */
    private void teardown(List<IAnimatable<?>> current) {
        for (IAnimatable<?> target : current) {
            target.getAnimController().stopAllAnimation();
        }
        for (MultiAnimStateMachine machine : postureMachines) {
            if (machine.isStarted()) machine.stop();
        }
        postureMachines.clear();
        persistentInstances.clear();
        resourceStamps.clear();
        molangContext = null;
        started = false;

        for (IAnimatable<?> target : current) {
            rebuildModel(target);
        }
        ARMS.LOGGER.info("[ARMS-Core] 内容包资源对象已更换，表现层动画层与状态机已清空，等待重建");
    }

    private static void rebuildModel(IAnimatable<?> target) {
        ModelController controller = target.getModelController();
        ModelInstance previous = controller.getModel();
        ResourceLocation texture = previous == null ? null : previous.getTextureLocation();

        ModelInstance rebuilt = new ModelInstance(target, target.getDefaultModelIndex());
        if (texture != null) {
            rebuilt.setTextureLocation(texture);
        }
        controller.setModel(rebuilt);
    }

    // ==========================================
    // 诊断
    // ==========================================

    /**
     * 四台姿态机当前追踪的活跃动画实例总数。
     * <p>
     * 阶段验收要看的「{@link MechaAnimGroups#LOCOMOTION} 层实例数恒为 1」就是它的期望值：
     * 四台机器共用一层且互斥，同一时刻只有一台机器持有一个实例。它是只读诊断量，不参与决策。
     */
    public int activePostureInstanceCount() {
        int count = 0;
        for (MultiAnimStateMachine machine : postureMachines) {
            count += machine.getActiveAnimInstances().size();
        }
        return count;
    }

    /** 常驻层当前持有的实例数，用于确认槽位有没有起起来。 */
    public int persistentInstanceCount() {
        return persistentInstances.size();
    }

    /** 表现层是否已建立（常驻层与四台机器就位）。 */
    public boolean isStarted() {
        return started;
    }

    // ==========================================
    // 辅助
    // ==========================================

    /** 在目标自身的动画集里查动画；查不到返回 {@code null}，由调用方决定是跳过还是回退。 */
    private static @Nullable OAnimation findAnimation(IAnimatable<?> target, String name) {
        OAnimationSet set = target.getAnimController().getOriginAnimations();
        return set == null ? null : set.getAnimation(name);
    }

    /** 建立时观察到的资源对象引用。 */
    private static final class ResourceStamp {
        final OAnimationSet animations;
        final OModel model;

        ResourceStamp(OAnimationSet animations, OModel model) {
            this.animations = animations;
            this.model = model;
        }
    }
}
