package io.github.sweetzonzi.arms_core.common;

import cn.solarmoon.spark_core.event.PhysicsLevelTickEvent;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.MechaCharacter;
import io.github.sweetzonzi.arms_core.common.control.MechaControl;
import io.github.sweetzonzi.arms_core.network.payload.MechaCoreSyncPayload;
import net.minecraft.core.Rotations;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * {@link ArmsCore} 的服务端生命周期与两个相位的事件接线。
 * <p>
 * 两个相位的职责（计划 D8、D9、§3.9）：
 * <ul>
 *   <li>{@code PhysicsLevelTickEvent.Pre}（物理线程）：按 Level 扇出
 *       {@link ArmsCore#prePhysicsTick(float)}，每步恰好一次。与 {@code VehicleCore} 的
 *       {@code ObjectManager.onPrePhysicsTick} 同相位。</li>
 *   <li>{@code LevelTickEvent.Post}（主线程）：读逻辑状态快照与 KCC 位姿 → 写
 *       {@code syncedData} → {@code packDirty()} → 非空则广播。</li>
 * </ul>
 * <p>
 * 不在 {@code LevelTickEvent.Pre} 做同步：Spark-Core 自身在该相位（{@code HIGH}）调用
 * {@code physicsLevel.requestStep()}，避开与请求步进竞争。
 * <p>
 * 本类不标注 {@code Dist}，因此其订阅者只在存在服务端的 JVM 中生效；单人游戏的集成服务端
 * 也走这条路径，专用服务端同理。
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID)
public final class ArmsCoreServerEvents {

    private ArmsCoreServerEvents() {
    }

    // ==========================================
    // 物理线程：唯一的物理步驱动
    // ==========================================

    /**
     * 按 Level 扇出物理步。
     * <p>
     * 驱动源是 Level 级注册表而不是宿主：宿主形态各异、驱动时机不同，漏调一次就停摆。
     * <p>
     * {@code dt} 取 {@code 1f / tps}：单步 dt 是恒定值，负载自适应调整的是每 tick 的步数
     * （计划 §3.6）。
     */
    @SubscribeEvent
    public static void onPrePhysicsTick(PhysicsLevelTickEvent.Pre event) {
        Level level = event.getLevel().getMcLevel();
        if (level.isClientSide()) return;
        float dt = 1f / event.getLevel().getTps();
        for (ArmsCore core : MechaCoreRegistry.all(level)) {
            core.prePhysicsTick(dt);
        }
    }

    // ==========================================
    // 主线程：同步写包
    // ==========================================

    /** 主线程每 tick 采样一次位姿与逻辑状态，按脏数据发包。 */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onPostTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        for (ArmsCore core : MechaCoreRegistry.all(level)) {
            syncToClients(core);
        }
    }

    /**
     * 把一个装配体的当前状态写进同步容器并在有脏数据时广播。
     * <p>
     * 位姿、速度、朝向三项在主线程直接读 KCC —— KCC 是幽灵体，其世界变换由 {@code playerStep}
     * 末尾一次性写入，并发读取拿到的是某一次完整步进的结果；这与 Machine-Max
     * {@code DestroyableRigidObject.postTick()} 已接受的竞态同类（计划 §3.5、R4）。
     * <p>
     * 回写宿主实体与写包同相位，使同步出去的 {@code DATA_POS} 与宿主实体位置取自同一次采样。
     * <b>宿主为 {@code null} 时只跳过回写两步，写包照常执行</b>：客户端可视锚点依赖
     * {@code DATA_POS}，连写包一起跳过会让未绑定宿主的装配体在客户端停在上一次采样上
     * （`docs/宿主接入与伤害管线设计.md` §3.1.2 的空值规则表）。
     * <p>
     * <b>为什么服务端回写不能放在 {@code PlayerTickEvent.Post}</b>：服务端玩家不在 {@code Entity#tick}
     * 路径上，{@code ServerGamePacketListenerImpl#tick} 在调用 {@code ServerPlayer#doTick} 之后紧接着
     * 把玩家位置重置为本次连接 tick 开始时记录的基准坐标，在该相位写的位置会被直接抹掉。
     * 本相位在关卡循环内触发，位于那次基准记录之前，写入的坐标会被原样保留。
     */
    public static void syncToClients(ArmsCore core) {
        MechaControl control = core.getMechaControl();
        if (control == null) return;
        MechaCharacter kcc = control.getKcc();

        // 位姿与速度：每次读都取新对象，不能把复用缓冲直接交给 set
        // （DataItem.setValue 直接存引用，复用会让已入队的旧值被改写）
        Vector3f kccPosition = kcc.getPhysicsLocation(null);
        Vector3f velocity = kcc.getLinearVelocity(null);

        // pin：外部位移被采纳之后、KCC 的 warp 真正落地之前，DATA_POS 取 pin 的目标而不是 KCC 位置。
        // 没有这一笔，客户端会在 warp 生效前收到旧位置并把自己放回旧处（§3.1）；这也是 DATA_POS 的第二
        // 个取值来源（`docs/ArmsCore双端权威与位移摄入设计.md` §6.2）。
        HostPositionIntake.PinView pin = core.getPositionIntake().getPin();
        Vector3f position = kccPosition;
        if (pin != null) {
            position = new Vector3f(pin.x(), pin.y(), pin.z());
        }
        core.getSyncedData().set(ArmsCore.DATA_POS, toJoml(position));
        core.getSyncedData().set(ArmsCore.DATA_VEL, toJoml(velocity));

        // 朝向：控制器侧绝对 Y 朝向，单位度。currentYaw 已由 MechaCharacter.setViewYaw 归约到
        // [−π, π)，Rotations 的构造器再对分量取 % 360 覆盖线上格式。
        core.getSyncedData().set(ArmsCore.DATA_YAW,
                new Rotations(0f, (float) Math.toDegrees(kcc.getCurrentYaw()), 0f));

        applyPoseToHost(core, kcc, kccPosition, velocity);

        // 逻辑层五项：主线程不得读 StateVariableContainer，只读物理线程发布的不可变快照
        MechaControl.LogicStateSnapshot state = core.getLogicState();
        if (state != null) {
            core.getSyncedData().set(ArmsCore.DATA_POSTURE, state.posture().molangName());
            core.getSyncedData().set(ArmsCore.DATA_GAIT, state.gait().molangName());
            core.getSyncedData().set(ArmsCore.DATA_VERTICAL, state.vertical().molangName());
            core.getSyncedData().set(ArmsCore.DATA_ENERGY, state.energy());
            core.getSyncedData().set(ArmsCore.DATA_JUMP_CHARGING, state.jumpCharging());
        }

        List<SynchedEntityData.DataValue<?>> dirty = core.getSyncedData().packDirty();
        if (dirty != null && !dirty.isEmpty()) {
            PacketDistributor.sendToPlayersInDimension((ServerLevel) core.getLevel(),
                    new MechaCoreSyncPayload(core.getAssemblyId(), dirty));
        }

        // pin 的生命周期收尾：warp 已落地（或超预算）就解除，下一 tick 起 DATA_POS 回到 KCC 位置
        releaseLandedPin(core, pin);

        // 只读断言：作用域深度必须归零。非零说明某个注入点的压栈没配对，不修改任何状态
        assertScopeBalanced(core);
    }

    /**
     * 解除已落地的 pin。
     * <p>
     * {@code landed} 由物理线程在 warp 执行后置起；主线程在本次采样里观察到它就解除，因此 pin 的实际
     * 存活窗口是「采纳的那一 tick」加「warp 执行所在的物理步」，正常路径下不超过两个 tick。
     * <p>
     * 解除后 {@code DATA_POS} 回到 KCC 位置，两者此时是同一个值（warp 已把 KCC 放到 pin 的目标上），
     * 因此不会产生「目标 → 回退 → 目标」的抖动。
     */
    private static void releaseLandedPin(ArmsCore core, HostPositionIntake.@Nullable PinView pin) {
        HostPositionIntake intake = core.getPositionIntake();
        intake.tickPinBudget();
        if (pin != null && pin.landed()) {
            intake.clearPin();
        }
    }

    /**
     * 每 tick 的只读断言：作用域深度必须归零。
     * <p>
     * 作用域的平衡本来由两个 {@code @WrapMethod} 包装体与 {@code applyPoseToHost} 的 {@code try/finally}
     * 保证（§5.2），因此这里只报告、不清状态。清理会把「某个注入点没配对」这个状态错误藏起来，而它的
     * 后果是此后<b>每一次</b>位置写入都被当作已知镜像、外部位移永远不再被摄入。
     */
    private static void assertScopeBalanced(ArmsCore core) {
        HostPositionIntake intake = core.getPositionIntake();
        int depth = intake.getScopeDepth();
        if (depth != 0) {
            ARMS.LOGGER.warn("[ARMS-Core] {} 的作用域栈在本 tick 结束时深度为 {}（栈顶类别={}）；"
                            + "说明某个位置写入的作用域压栈没配对，此后的外部位移将不再被摄入",
                    core.getAssemblyId(), depth,
                    HostPositionIntake.scopeName(intake.topScopeReason()));
        }
    }

    private static org.joml.Vector3f toJoml(Vector3f source) {
        return new org.joml.Vector3f(source.x, source.y, source.z);
    }

    /**
     * 把 KCC 的位姿与速度写进宿主实体，并按固定间隔记录 KCC 与宿主实体的偏移。
     * <p>
     * 位置按 {@code 宿主位置 = 胶囊中心 − MechaBodyPreset.HALF_TOTAL} 换算到包围盒底面，换算由
     * 宿主实现负责（{@code IArmsHost#applyPose} 的入参就是胶囊中心，见
     * `docs/IArmsHost宿主接口设计.md` §2.1）。直接把胶囊中心写进实体位置会把宿主整体抬高
     * {@code HALF_TOTAL}，当前素体取值下为 {@code 1.2 m}。
     * <p>
     * 速度也写：{@code noPhysics} 为真时 {@code Entity#move} 不参与碰撞求解，写进去的
     * {@code deltaMovement} 不产生实际位移，作用只是让外部查询（动画、其它模组、调试）看到 KCC 的真实
     * 速度，因此它与位置回写不构成两个运动权威。
     * <p>
     * <b>物理步长必须传下去。</b> KCC 的速度在三个轴上语义不同（水平是每物理步位移、垂直是 m/s），而
     * {@code deltaMovement} 的三个分量统一是每 tick 位移，换算由宿主实现完成、因子取自
     * {@link ArmsCore#physicsStepSeconds()}——服务端 100 Hz 与客户端 60 Hz 的因子不同，不能在宿主里写死。
     * <p>
     * 宿主引用为 {@code null}（尚未绑定宿主、或客户端实例）时整个回写是 no-op。
     */
    private static void applyPoseToHost(ArmsCore core, MechaCharacter kcc,
                                        Vector3f position, Vector3f velocity) {
        IArmsHost host = core.getHost();
        if (host == null) return;
        Vec3 entityBefore = host.getHostEntity().position();
        float yRot = (float) Math.toDegrees(kcc.getCurrentYaw());
        // 第 4 类作用域（服务端运行时回写）：本模组自己写的这一笔不能被判成外部位移，否则每 tick 一次误判。
        // 用 try/finally 而不是「每个返回点各注入一次」——异常路径也要把栈弹干净
        // （`docs/宿主位置权威与位移摄入设计.md` §5.2）。
        // 类别码只影响日志标注：判定只看栈空不空，见 common/HostPositionIntake.java#isInScope
        int outerDepth = core.getPositionIntake()
                .enterScope(HostPositionIntake.SCOPE_SERVER_WRITEBACK);
        try {
            host.applyPose(new Vec3(position.x, position.y, position.z), yRot, yRot);
        } finally {
            core.getPositionIntake().exitScope(outerDepth);
        }
        host.applyVelocity(new Vec3(velocity.x, velocity.y, velocity.z), core.physicsStepSeconds());
        logHostDrift(core, host, entityBefore, position, velocity);
    }

    /** 宿主偏移日志的采样间隔（tick） */
    private static final int HOST_DRIFT_LOG_INTERVAL = 40;

    /** 上述日志的全局节流计数 */
    private static long hostDriftTicks;

    /**
     * 记录宿主实体回写前后的位置与 KCC 位置，用于定位「KCC 与宿主不同步」的成因。
     * <p>
     * 三个数各自回答一个问题：
     * <ul>
     *   <li><b>实体自走量</b>（回写前后位置之差）：本 tick 期间实体自己走了多远。应当接近 0；
     *       明显非零说明实体仍有独立的运动来源，例如上一步写进去的 {@code deltaMovement}。</li>
     *   <li><b>KCC-to-实体 before</b>：回写前实体与 KCC 的偏移。它的<b>水平分量</b>持续增大说明 KCC 没有
     *       跟着实体走（回写没生效，或 KCC 被别的写入拉回）；<b>垂直分量</b>持续增大说明 KCC 在持续
     *       下坠（地面检测没接住它）。</li>
     *   <li><b>KCC 速度</b>：判断 KCC 是在自己走，还是停在原地被拖。</li>
     * </ul>
     * 水平与垂直分量回答「KCC 是没跟着宿主走，还是在持续下坠」：水平分量增长说明回写没生效或 KCC
     * 被别的写入拉回，垂直分量增长说明地面检测没接住 KCC。
     */
    private static void logHostDrift(ArmsCore core, IArmsHost host,
                                     Vec3 entityBefore, Vector3f kcc, Vector3f velocity) {
        if (++hostDriftTicks % HOST_DRIFT_LOG_INTERVAL != 0) return;
        Vec3 entityAfter = host.getHostEntity().position();
        double dx = kcc.x - entityBefore.x;
        double dy = kcc.y - entityBefore.y;
        double dz = kcc.z - entityBefore.z;
        ARMS.LOGGER.info(
                "[ARMS-Core] 宿主偏移 {}：水平={} 垂直={} 分量=({}, {}, {})；实体自走量=({}, {}, {})；"
                        + "KCC 速度=({}, {}, {})",
                core.getAssemblyId(),
                fmt(Math.sqrt(dx * dx + dz * dz)), fmt(Math.abs(dy)),
                fmt(dx), fmt(dy), fmt(dz),
                fmt(entityAfter.x - entityBefore.x),
                fmt(entityAfter.y - entityBefore.y),
                fmt(entityAfter.z - entityBefore.z),
                fmt(velocity.x), fmt(velocity.y), fmt(velocity.z));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    // ==========================================
    // 登录 / 换维度 / 重生补发
    // ==========================================

    /**
     * 玩家登录或换维度后把所在维度的装配体逐个补发。
     * <p>
     * 用 {@link EntityJoinLevelEvent} 而不是 {@code PlayerLoggedInEvent}：前者在玩家实体真正
     * 加入维度后触发，此时 {@code player.level()} 已是目标维度，正是创建包需要校验的维度。
     * 换维度路径同时由 {@link PlayerEvent.PlayerChangedDimensionEvent} 覆盖（重生也走该事件）。
     */
    @SubscribeEvent
    public static void onPlayerJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MechaCoreRegistry.sendAllTo(player);
    }

    /** 换维度 / 重生：清掉该玩家的绑定，再补发新维度。 */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        releaseCoresControlledBy(event.getEntity());
        if (event.getEntity() instanceof ServerPlayer player) {
            MechaCoreRegistry.sendAllTo(player);
        }
    }

    /**
     * 断线：该玩家控制的装配体必须回到空快照，否则状态机卡在最后一帧（R11）。
     * <p>
     * 同时解绑：玩家实体即将退场，绑定字段留在它上面没有意义，而 {@code ArmsCore} 侧的宿主引用
     * 若继续指向这个实体，位姿回写会写到一个已经不在世界里的对象上（静默无效）。
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        releaseCoresControlledBy(event.getEntity());
    }

    /**
     * 释放某个玩家承载的装配体。
     * <p>
     * 判据取自绑定字段本身（{@link IArmsHost#getControlledArmsCore}），因此不需要按玩家 UUID
     * 反查「他控制着哪些装配体」——那个方向的信息就是绑定字段。
     */
    private static void releaseCoresControlledBy(Entity entity) {
        if (!(entity instanceof IArmsHost host)) return;
        ArmsCore core = host.getControlledArmsCore();
        if (core != null) {
            host.setControlledArmsCore(null);
        }
    }

    // ==========================================
    // 维度卸载
    // ==========================================

    /**
     * 维度卸载时清空注册表。
     * <p>
     * 服务端卸载与客户端退出共用同一入口：客户端换维度时同样会卸载旧的 {@code ClientLevel}，
     * 此时清空本地表，随后由服务端补发的创建包重新填充。
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof Level level)) return;
        MechaCoreRegistry.onLevelUnload(level);
    }
}
