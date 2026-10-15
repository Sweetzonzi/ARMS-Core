package io.github.sweetzonzi.arms_core.common.control.presentation;

import cn.solarmoon.spark_core.animation.IAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimInstance;
import cn.solarmoon.spark_core.animation.anim.origin.OAnimation;
import cn.solarmoon.spark_core.animation.state.MultiAnimStateMachine;
import cn.solarmoon.spark_core.state_machine.graph.StateAction;
import cn.solarmoon.spark_core.state_machine.graph.StateGraphController;
import com.mojang.serialization.MapCodec;

/**
 * 在广播式动画状态机上按名字播一个动画 —— {@link cn.solarmoon.spark_core.state_machine.graph.actions.PlayAnimAction}
 * 的 Java 可达版本。
 *
 * <h3>为什么需要这个孪生类</h3>
 * {@code PlayAnimAction} 的第四个构造参数是 Kotlin 的 inline value class
 * {@code JSMolangValue}。Kotlin 为它生成的那个公开构造器带一个
 * {@code DefaultConstructorMarker} 形参，Java 侧构造不出来（javap 可核对：该类只留下
 * {@code private (String, float, boolean, String)} 与两个带 marker 的公开合成构造器），
 * 而 bmpt 的四台姿态机全部是 Java 硬编码图。本类因此承担同一个动作。
 *
 * <h3>与上游的耦合</h3>
 * 本类逐步对齐 {@code PlayAnimAction.execute} 的 Multi 分支：三级回退查找动画 → 找不到就跳过 →
 * 建 {@link AnimInstance} → 设置过渡时长与写入层 → 进入播放 → 登记进状态机的追踪表。
 * <b>上游改动那一处时本类要跟着改</b>，尤其是「进入后登记进
 * {@code MultiAnimStateMachine#activeAnimInstances}」这一步：不在那里的实例不会被节点退出时的回收
 * 逻辑看到，会永久留在层里。
 *
 * @author Sweetzonzi
 */
public final class MechaPlayAnimAction implements StateAction {

    /** 动画名；查找走目标自身动画集，找不到时静默跳过 */
    private final String animationName;

    /** 进出场过渡时长 (s) */
    private final float blendTime;

    public MechaPlayAnimAction(String animationName, float blendTime) {
        this.animationName = animationName;
        this.blendTime = blendTime;
    }

    @Override
    public void execute(StateGraphController controller) {
        if (!(controller instanceof MultiAnimStateMachine machine)) {
            // 与上游一致：这个动作只在广播式载体上有效，其它控制器上告警并跳过
            io.github.sweetzonzi.arms_core.ARMS.LOGGER.warn(
                    "[ARMS-Core] MechaPlayAnimAction 只能在 MultiAnimStateMachine 上执行，当前控制器为 {}",
                    controller.getClass().getSimpleName());
            return;
        }

        for (IAnimatable<?> target : machine.getAnimTargets()) {
            OAnimation animation = machine.findAnimation(target, animationName);
            if (animation == null) continue;

            AnimInstance instance = new AnimInstance(target, animation);
            instance.setInTransitionTime(blendTime);
            instance.setOutTransitionTime(blendTime);
            instance.setGroup(machine.getAnimGroup());
            instance.enter();
            machine.getActiveAnimInstances().add(instance);
        }
    }

    @Override
    public MapCodec<? extends StateAction> getCodec() {
        return MapCodec.unit(this);
    }

    @Override
    public String toString() {
        return "MechaPlayAnim(" + animationName + ", blend=" + blendTime + ")";
    }
}
