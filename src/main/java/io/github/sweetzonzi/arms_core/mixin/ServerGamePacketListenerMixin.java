package io.github.sweetzonzi.arms_core.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.HostPositionIntake;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 第 2 类作用域「客户端上报采纳」—— {@code docs/宿主位置权威与位移摄入设计.md} §5.2 的第 2 行。
 * <p>
 * 包住 {@code net.minecraft.server.network.ServerGamePacketListenerImpl#handleMovePlayer} 的<b>整个方法</b>，
 * 而不是只标末尾那一次 {@code absMoveTo}：同一个方法内的写入有三处，只标一处会漏掉另外两处。
 * <ol>
 *   <li>乘客分支里的一次 {@code absMoveTo}（玩家位置由载具驱动，见 §七）；</li>
 *   <li>中段的 {@code player.move(...)}——它是 {@code Entity#move} 的第 1 类作用域，会自己压栈，但它的
 *       结果与本次上报同属「已知镜像」语义，落在本方法内也不需要另行摄入；</li>
 *   <li>方法末段把位置钉到上报绝对值的 {@code absMoveTo}，它是**回声**那一笔：本模组经 {@code DATA_POS}
 *       下发、客户端镜像后又原样回传的旧值。</li>
 * </ol>
 * <p>
 * <b>三处纠偏 {@code teleport} 也落在窗口内</b>（睡觉且位移超限、moved too quickly、moved wrongly）。
 * 它们的目标取自服务端当前位置，按同一条规则推进锚点——这一点是必须的：不推进的话，下一次回声写入就会
 * 与锚点相差一个上行往返而被误判成外部位移（§5.2 第三点）。
 * <p>
 * <b>代价（已登记的残留项）。</b> 作用域动态范围内的第三方写入会被当成镜像而不摄入，例如
 * {@code BlockBehaviour#entityInside} 里搬人的方块、以及本方法内嵌套的传送。收紧办法是把第 1 类的作用域
 * 从整个 {@code Entity#move} 收窄到两个已知调用点，见设计文档 §八 第 1 项。
 * <p>
 * <b>退出时只弹栈，且这一笔写入要走锚点。</b> 作用域本身只说明「这次写入是回声」，它不构成位移；
 * 方法体内那几处写入会让 {@code Entity#setPos} 的注入点按栈顶类别码（{@code SCOPE_CLIENT_REPORT}）
 * 推进锚点，因此紧随其后的「把实体重断言回基准值」那类写入与锚点相等，被无变化守卫吞掉。
 *
 * @author Sweetzonzi
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    /**
     * 第 2 类作用域：包住一次位置包处理的完整动态范围。
     * <p>
     * 退出时只弹栈（{@code HostPositionIntake#exitScope(int)}）：本方法内的 {@code player.move} 与末段的
     * {@code absMoveTo} 都是已知镜像，栈空之后的判定留给 {@code Entity#setPos} 注入点。
     */
    @WrapMethod(method = "handleMovePlayer")
    private void armsCore$wrapHandleMovePlayer(ServerboundMovePlayerPacket packet, Operation<Void> original) {
        if (!(((Object) this) instanceof IArmsHost host)) {
            original.call(packet);
            return;
        }
        ArmsCore core = host.getControlledArmsCore();
        if (core == null) {
            original.call(packet);
            return;
        }
        int outerDepth = core.getPositionIntake().enterScope(HostPositionIntake.SCOPE_CLIENT_REPORT);
        try {
            original.call(packet);
        } finally {
            core.getPositionIntake().exitScope(outerDepth);
        }
    }
}
