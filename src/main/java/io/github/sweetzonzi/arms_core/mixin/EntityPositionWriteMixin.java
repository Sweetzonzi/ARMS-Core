package io.github.sweetzonzi.arms_core.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.HostPositionIntake;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 宿主实体位置写入的判定点与作用域 —— {@code docs/宿主位置权威与位移摄入设计.md} §5 的检测层。
 * <p>
 * <b>本类做两件事</b>：
 * <ol>
 *   <li>{@code net.minecraft.world.entity.Entity#setPos(double, double, double)} 的 {@code HEAD} 注入是
 *       <b>判定点</b>：栈空的那一次写入就是外部位移候选，当场把它的三个入参交给摄入层 —— 目标就是这次
 *       调用写下去的坐标，不需要与任何历史值比较。栈非空时它只做另一件事：按栈顶类别码决定要不要推进
 *       锚点（{@code HostPositionIntake#advanceAnchor}）——第 2 类客户端上报与第 4 类本模组运行时回写
 *       推进，第 1 类自身运动不推进，理由见 {@link HostPositionIntake} 的类注释。</li>
 *   <li>{@code Entity#move(MoverType, Vec3)} 的 {@code @WrapMethod} 是<b>第 1 类作用域「自身运动」</b>：
 *       它的两条分支都以 {@code setPos} 落位（{@code noPhysics} 为真时写「当前 + delta」，为假时写碰撞
 *       求解后的位移），因此必须整方法压栈。只压 {@code noPhysics} 分支会漏掉 tick 内的 {@code travel}
 *       ——{@code Player#tick} 的首行是 {@code noPhysics = isSpectator()}，绑定期间 {@code PlayerHostEvents}
 *       要到该方法末行才把它重新置真，因此 tick 内走的是碰撞求解分支，漏掉的后果是每 tick 误判一次
 *       外部位移、KCC 被反复拖回。</li>
 * </ol>
 * <p>
 * <b>为什么用 {@code @WrapMethod} 而不是 {@code HEAD} / {@code RETURN} 两次 {@code @Inject}。</b>
 * 包装体的形态是「压栈 → {@code try { original.call(...) } finally { 弹栈 }}」，作用域的平衡由
 * {@code finally} 保证，而不是由「每个返回点都恰好被注入到」这条约定保证（{@code @Inject(at = RETURN)}
 * 覆盖正常返回与提前返回，但不覆盖异常）。异常照常向外抛出：包装体只做弹栈，不捕获、不吞。
 * <p>
 * <b>退出时只弹栈。</b> 包装体的 {@code finally} 调 {@code HostPositionIntake#exitScope(int)}，与
 * {@code ArmsCoreServerEvents#applyPoseToHost} 的收尾同形：第 1 类与第 2 类作用域里的写入都是已知镜像，
 * 退出本身不是位移事件，摄入判定统一留在上面那个 {@code HEAD} 注入点。
 * <p>
 * <b>成本。</b> {@code Entity#setPos} 与 {@code Entity#move} 是全实体最热的两个入口，注入体因此先做
 * 最便宜的判断：{@code instanceof IArmsHost} → 绑定引用非空。世界里的绝大多数实体在第一步就返回；
 * 未绑定装配体的玩家在第二步返回。
 * <p>
 * <b>跨类状态不落在本类上。</b> {@code io.github.sweetzonzi.arms_core.mixin.*} 是 Mixin 独占包，
 * Mixin 类之间不能互相引用，因此作用域栈、锚点与 pin 必须落在普通类 {@link HostPositionIntake} 上，
 * 本类只作转发。
 *
 * @author Sweetzonzi
 */
@Mixin(Entity.class)
public abstract class EntityPositionWriteMixin {

    /**
     * 判定点：栈空的那次写入即刻做摄入判定，并把这次调用的三个入参交给摄入层。
     * <p>
     * 注入点是 {@code HEAD}，因此看到的是原始入参；{@code Entity#setPos} 要等注入体返回后才把坐标按世界
     * 边界钳制后写 {@code position} 与 {@code setBoundingBox}，被钳掉的目标不会留下持久偏差，因为实体
     * 本身也停在边界点上。
     */
    @Inject(method = "setPos(DDD)V", at = @At("HEAD"))
    private void armsCore$intakeExternalPosition(double x, double y, double z, CallbackInfo ci) {
        if (!(((Object) this) instanceof IArmsHost host)) return;
        ArmsCore core = host.getControlledArmsCore();
        if (core == null) return;
        HostPositionIntake intake = core.getPositionIntake();
        if (intake.isInScope()) {
            // 作用域内的已知镜像：第 2 类（客户端上报与回声）与第 4 类（本模组运行时回写）写的是
            // 「被本模组认可的当前值」，推进锚点；第 1 类是 tick 内的实体自走，随后被连接相位的
            // 基准复位抹掉，因此不推进
            if (intake.topScopeReason() != HostPositionIntake.SCOPE_SELF_MOTION) {
                intake.advanceAnchor(x, y, z);
            }
            return;
        }
        core.applyExternalDisplacement(x, y, z);
    }

    /**
     * 第 1 类作用域「自身运动」：包住 {@code Entity#move} 的整个动态范围，退出时只弹栈（见类注释）。
     */
    @WrapMethod(method = "move")
    private void armsCore$wrapMove(MoverType type, Vec3 movement, Operation<Void> original) {
        if (!(((Object) this) instanceof IArmsHost host)) {
            original.call(type, movement);
            return;
        }
        ArmsCore core = host.getControlledArmsCore();
        if (core == null) {
            original.call(type, movement);
            return;
        }
        int outerDepth = core.getPositionIntake().enterScope(HostPositionIntake.SCOPE_SELF_MOTION);
        try {
            original.call(type, movement);
        } finally {
            core.getPositionIntake().exitScope(outerDepth);
        }
    }
}
