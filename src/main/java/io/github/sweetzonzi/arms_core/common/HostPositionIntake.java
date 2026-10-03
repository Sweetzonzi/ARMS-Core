package io.github.sweetzonzi.arms_core.common;

import cn.solarmoon.spark_core.api.SparkLevel;
import cn.solarmoon.spark_core.util.PPhase;
import com.jme3.math.Vector3f;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 外部位移的摄入 —— {@code docs/宿主位置权威与位移摄入设计.md} §5、§6 的落地。
 * <p>
 * <b>它回答两个问题</b>：这一次 {@code net.minecraft.world.entity.Entity#setPos} 是不是外部位移，
 * 以及采纳之后要把 KCC 搬到哪、在它落地之前 {@code DATA_POS} 取什么。
 * <p>
 * <b>分类靠「作用域栈 + 锚点」两个量。</b> 位置写入有四类已知来源，其中三类是镜像：实体自身运动
 * （{@code Entity#move}）、客户端上报与回声（{@code ServerGamePacketListenerImpl#handleMovePlayer}）、
 * 以及本模组自己的运行时回写（服务端 {@code ArmsCoreServerEvents#applyPoseToHost}、客户端
 * {@code client/ClientHostPoseEvents.java#applySyncedPose}）。三者各被一个作用域包住，栈非空就不摄入；
 * 栈空（栈顶类别读作 {@link #SCOPE_EXTERNAL}）才是外部位移候选，再与锚点比较——目标等于锚点表示这次
 * 写入只是「重断言当前位置」（连接相位的基准复位、起床与骑乘那类
 * {@code connection.teleport(当前位置)}），同样不摄入。
 * <p>
 * <b>判定只发生在 {@code Entity#setPos} 的注入点上；作用域的退出只弹栈。</b> 两个
 * {@code @WrapMethod} 包装体的 {@code finally} 调 {@link #exitScope(int)}，与
 * {@code ArmsCoreServerEvents#applyPoseToHost} 的收尾同形。把摄入判定挂在作用域退出上会让
 * 「实体自身运动」与「客户端上报回声」这两类镜像每 tick 各被采纳一次。
 * <p>
 * 用栈而不是布尔，是因为两个作用域会嵌套（{@code handleMovePlayer} 内先调 {@code Entity#move}、
 * 后写 {@code absMoveTo}），布尔会被内层的退出提前清零。栈顶的类别码既用于日志，也决定这次写入要不要
 * 推进锚点：第 2 类与第 4 类写的是「被本模组认可的当前值」，推进；第 1 类是 tick 内的实体自走，随后会
 * 被连接相位的基准复位抹掉，因此不推进。
 * <p>
 * <b>锚点回答「这次写入有没有改变位置」。</b> 基准与 {@code Entity#setPos} 的入参一致（包围盒底面），
 * 值＝实体当前被合法持有的位置。推进规则：作用域内的第 2/4 类写入按该次目标推进（
 * {@link #advanceAnchor(double, double, double)}，由注入点按栈顶类别码调用）；栈空且判定为外部位移的
 * 写入，在采纳生效之后由 {@link #intake} 自己推进。锚点因此让「重断言当前位置」那一族写入与锚点相等，
 * 被 {@link #isAtAnchor(double, double, double)} 拦下。
 * <p>
 * 唯一被<b>跨相位</b>读取的值仍是 {@link #getPin() pin 的目标} —— 它要被
 * {@code ArmsCoreServerEvents#syncToClients} 在同一 tick 稍后（乃至下一个物理步）读到，那时
 * {@code setPos} 的栈帧早已退出。
 * <p>
 * <b>为什么需要 pin。</b> 服务端 tick 内命令相位早于 level 相位（§1.2）：`/tp` 在命令相位把实体写到目标，
 * 摄入在此发生；而 {@code syncToClients} 在 level 相位才采样 KCC，此刻 KCC 还停在旧位置。没有 pin，
 * 这一拍的 {@code DATA_POS} 就是旧值 —— 客户端把自己放回旧处、上报旧值、服务端采纳，传送被整条回路吃掉
 * （§3.3）。有了它，客户端被钉在目标上，直到 KCC 的 warp 真正落地。
 * <p>
 * <b>不承担生命周期。</b> 跨维度的搬迁由装配体重建承担（{@code docs/宿主接入与伤害管线设计.md} §3.1.3：
 * 「换维度（非重生）」判为重建），因此本类不做维度守卫，也不保存任何等级引用。
 * <p>
 * <b>线程模型</b>：作用域栈、锚点、pin 的目标与剩余 tick 都是主线程的普通字段（位置写入、包处理、
 * 同步写包都在主线程）。跨线程的只有两项：待投递的落点（{@link #intake} 在主线程写，
 * {@link #runWarpTask} 在物理线程读）与 pin 的落地标志（物理线程写、主线程读）。落点走「先写字段、再投递只读字段的任务」，与既有的
 * {@code common/MechaCoreRegistry.java#enterPhysicsSpace} 同形。
 * <p>
 * <b>为什么单独成类。</b> 作用域栈、锚点与 pin 都是「一个装配体一份」的状态，与 {@code ArmsCore} 的生命周期
 * 完全一致：本类由 {@link ArmsCore} 在自己的构造末尾持有，作用域与 pin 都跟着装配体生灭。独立成类只是
 * 为了让这份状态有自己的文件与 Javadoc，不引入任何额外的抽象层。验证走 GameTest 车道
 * （{@code common/gametest/ArmsCoreGameTest.java}）：那里能拿到真的 {@link ArmsCore}，比测试替身更接近
 * 实际调用路径。
 *
 * @author Sweetzonzi
 */
public final class HostPositionIntake {

    // ═══════════════════════════════════════════════
    // 作用域类别码（取设计文档 §5.1 的行号，只用于日志）
    // ═══════════════════════════════════════════════

    /** 第 1 类「自身运动」：{@code Entity#move} 的两条分支，tick 内发生、随即被基准复位抹掉 */
    public static final byte SCOPE_SELF_MOTION = 1;

    /** 第 2 类「客户端上报采纳」：{@code ServerGamePacketListenerImpl#handleMovePlayer} 的目标写入与回声 */
    public static final byte SCOPE_CLIENT_REPORT = 2;

    /** 第 3 类「外部位移」：栈空时的候选，见 {@link #isInScope()} 与 {@link #intake} */
    public static final byte SCOPE_EXTERNAL = 3;

    /**
     * 第 4 类「本模组运行时回写」：服务端 {@code ArmsCoreServerEvents#applyPoseToHost} 与
     * 客户端 {@code client/ClientHostPoseEvents.java#applySyncedPose}。
     * <p>
     * 两端各有一笔：服务端那一笔把 KCC 位姿写进宿主实体，客户端那一笔按 {@code DATA_POS} 摆放本机玩家。
     * 两者都是「本模组自己写的」，因此按镜像处理，不摄入。
     */
    public static final byte SCOPE_RUNTIME_WRITEBACK = 4;

    // ═══════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════

    /** 作用域栈容量：最大嵌套是 2（{@code handleMovePlayer} → {@code Entity#move}），8 层留足余量 */
    private static final int SCOPE_CAPACITY = 8;

    /**
     * pin 的 tick 预算。
     * <p>
     * 兜底用：物理线程未运行、维度正在卸载等情形下，KCC 侧的 warp 永远不会把落地标志翻起来，若不设上限
     * 就会把 {@code DATA_POS} 钉在一个永不生效的目标上。取 40 tick（2 s）——正常路径下 warp 在下一物理步
     * 就执行，这个预算只用来兜底。
     */
    private static final int PIN_TICK_BUDGET = 40;

    /** 摄入日志的打印上限，超出后只累计不打印，避免每 tick 刷屏 */
    private static final int INTAKE_LOG_LIMIT = 8;

    /** 位置比较容差 (m)：只用于 pin 的幂等判据，为 float32 往返留余量 */
    private static final float POSITION_EPSILON = 1.0e-4f;

    // ═══════════════════════════════════════════════
    // 身份
    // ═══════════════════════════════════════════════

    /**
     * 本份分类状态所属的装配体。
     * <p>
     * 判据用到的只有 {@code authoritative}、{@code assemblyId} 与 {@code getKcc()}，而「一个装配体一份
     * 分类状态」这条不变量本来就要求它跟着装配体走。
     */
    private final ArmsCore core;

    // ═══════════════════════════════════════════════
    // 作用域栈
    // ═══════════════════════════════════════════════

    /** 各层作用域的类别码，{@code reasons[i]} 是第 {@code i + 1} 层（最内层是 {@code depth - 1}） */
    private final byte[] reasons = new byte[SCOPE_CAPACITY];

    /** 当前作用域深度；{@code 0} 表示栈空、这次写入是外部位移候选 */
    private int depth;

    // ═══════════════════════════════════════════════
    // 锚点：实体当前被合法持有的位置
    // ═══════════════════════════════════════════════

    /**
     * 锚点是否已建立。
     * <p>
     * {@code false} 表示本份状态还没有见过任何一次「被认可的位置写入」（作用域内的第 2/4 类写入或一次
     * 已采纳的摄入），此时 {@link #isAtAnchor(double, double, double)} 一律返回 {@code false}——
     * 先按外部位移候选处理，与首次绑定的保守方向一致。
     */
    private boolean anchorValid;

    /** 锚点 X（包围盒底面基准，与 {@code Entity#setPos} 的入参同基准） */
    private double anchorX;
    /** 锚点 Y */
    private double anchorY;
    /** 锚点 Z */
    private double anchorZ;

    // ═══════════════════════════════════════════════
    // pin：warp 落地前 DATA_POS 暂取的值
    // ═══════════════════════════════════════════════

    /** 是否有生效的 pin；读取入口是 {@link #isPinActive()} 与 {@link #getPin()}。 */
    private boolean pinActive;

    /** pin 的目标（胶囊中心），仅在 {@link #pinActive} 为真时有效 */
    private final Vector3f pinTarget = new Vector3f();

    /** pin 的剩余 tick 预算 */
    private int pinTicksLeft;

    /** 物理线程已执行 KCC 侧 warp；由 {@link #markPinLanded()} 写、{@link #getPin()} 读 */
    private volatile boolean pinLanded;

    // ═══════════════════════════════════════════════
    // 待投递的 warp 落点
    // ═══════════════════════════════════════════════

    /** 是否有待执行的 warp */
    private volatile boolean warpPending;

    /**
     * 待执行的 warp 落点（胶囊中心，世界坐标）。
     * <p>
     * 落点先写字段、再投递一个只读字段的任务，与 {@code MechaCoreRegistry#enterPhysicsSpace} 同形。
     * 同一 tick 内的多次摄入按<b>最后写入者</b>生效：先投递的任务取到的是最终值，后投递的任务读到
     * {@link #warpPending} 已为假而直接返回。
     */
    private volatile float warpX;
    private volatile float warpY;
    private volatile float warpZ;

    // ═══════════════════════════════════════════════
    // 统计
    // ═══════════════════════════════════════════════

    /** 已采纳的摄入次数（诊断） */
    private int intakeCount;
    /** 已打印的日志行数 */
    private int loggedCount;

    /**
     * 由 {@link ArmsCore} 在自己的构造末尾创建。
     * <p>
     * 注入体构造不到本类：{@code mixin/EntityPositionWriteMixin} 与
     * {@code mixin/ServerGamePacketListenerMixin} 不实例化它，只经 {@link ArmsCore#getPositionIntake()}
     * 取到 {@code ArmsCore} 持有的那一份。构造器保持包私有，与「一个装配体一份分类状态」这条不变量同形。
     *
     * @param core 本份状态所属的装配体
     */
    HostPositionIntake(ArmsCore core) {
        this.core = core;
    }

    // ═══════════════════════════════════════════════
    // 作用域栈
    // ═══════════════════════════════════════════════

    /**
     * 进入一个已知镜像作用域，返回进入前的深度。
     * <p>
     * 调用方必须把返回值交给 {@link #exitScope(int)}。作用域只回答「这次写入是谁发的」：栈非空就不摄入，
     * 栈顶类别码决定它要不要推进锚点（第 2/4 类推进，第 1 类不推进）。摄入判定不在这里，而在
     * {@code Entity#setPos} 的注入点上。作用域的平衡由 {@code @WrapMethod} 包装体与两端运行时回写
     * （{@code ArmsCoreServerEvents#applyPoseToHost}、{@code client/ClientHostPoseEvents.java#applySyncedPose}）
     * 的 {@code try/finally} 保证，本类不设「按 tick 清深度」的兜底 ——
     * 那会把状态错误藏起来，且清理时机晚于同 tick 的命令相位。
     *
     * @param reason 类别码，取本类的 {@code SCOPE_*} 常量
     * @return 进入前的深度
     */
    public int enterScope(byte reason) {
        if (depth < SCOPE_CAPACITY) {
            reasons[depth] = reason;
        }
        // 溢出时只继续计数、不再记类别：判定只看深度非零，因此不受影响
        return depth++;
    }

    /**
     * 退出一个作用域。
     * <p>
     * 只弹栈，不做摄入判定：三个已知镜像作用域（自身运动、客户端上报、本模组运行时回写）退出时的位置
     * 都不是外部位移。
     *
     * @param outerDepth {@link #enterScope(byte)} 返回的深度
     */
    public void exitScope(int outerDepth) {
        // 只读断言式的下溢处理：写入发生在实体 tick 的调用链里，抛异常会把异常带进原版流程。
        // 真正的不平衡由 ArmsCoreServerEvents 的每 tick 深度断言暴露
        depth = Math.max(0, Math.min(outerDepth, depth));
    }

    /** 当前是否在某个已知镜像作用域内；{@code false} 表示这次写入是外部位移候选。 */
    public boolean isInScope() {
        return depth > 0;
    }

    // ═══════════════════════════════════════════════
    // 锚点
    // ═══════════════════════════════════════════════

    /**
     * 把锚点写成一次「被认可的位置写入」的目标。
     * <p>
     * 由 {@code mixin/EntityPositionWriteMixin} 的 {@code Entity#setPos} 注入点在作用域内按栈顶类别码调用
     * （第 2/4 类），以及由 {@link #intake} 在自己采纳一次外部位移之后调用。第 1 类自身运动的写入不推进
     * 锚点：那是 tick 内实体自己走出来的位移，随后会被
     * {@code net.minecraft.server.network.ServerGamePacketListenerImpl#tick} 的基准复位抹掉，
     * 若把它记成锚点，紧接着的基准复位就会被判成「位置变了」。
     * <p>
     * 只允许在主线程调用（位置写入与包处理都在主线程）。
     *
     * @param x 目标 X（包围盒底面基准）
     * @param y 目标 Y
     * @param z 目标 Z
     */
    public void advanceAnchor(double x, double y, double z) {
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        anchorValid = true;
    }

    /**
     * 目标是否与锚点相同 —— 「这次写入有没有改变位置」的判据。
     * <p>
     * 相同表示这次写入只是把实体重断言回它当前被合法持有的位置（连接相位的基准复位、起床与骑乘那类
     * {@code connection.teleport(当前位置)}），不构成位移。容差为 float32 往返留余量；
     * 锚点尚未建立时一律返回 {@code false}。
     *
     * @param x 目标 X（包围盒底面基准）
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 与锚点相同则返回 {@code true}
     */
    public boolean isAtAnchor(double x, double y, double z) {
        return anchorValid && samePosition(anchorX, anchorY, anchorZ, x, y, z);
    }

    /**
     * 清空全部分类状态：解绑时调用，让下一次绑定从干净的栈开始。
     * <p>
     * 作用域栈、锚点与 pin 都属于<b>这一次</b>绑定：留着一个未解除的 pin，下一个宿主的第一拍
     * {@code DATA_POS} 就会取到上一个宿主的目标值；留着一个旧锚点，新宿主的第一拍就会把真实的
     * 位移判成「无变化」。
     */
    public void reset() {
        depth = 0;
        anchorValid = false;
        warpPending = false;
        clearPin();
    }

    /** 当前作用域深度；{@code 0} 表示栈空。 */
    public int getScopeDepth() {
        return depth;
    }

    /**
     * 栈顶类别码 —— 这次写入属于哪一类已知镜像。
     *
     * @return 类别码；栈空时返回 {@link #SCOPE_EXTERNAL}
     */
    public byte topScopeReason() {
        if (depth <= 0) return SCOPE_EXTERNAL;
        return reasons[Math.min(depth, SCOPE_CAPACITY) - 1];
    }

    /**
     * 把一个类别码转成可读标签，供日志使用。
     *
     * @param reason 类别码
     * @return 中文标签
     */
    public static String scopeName(byte reason) {
        return switch (reason) {
            case SCOPE_SELF_MOTION -> "1/自身运动";
            case SCOPE_CLIENT_REPORT -> "2/客户端上报采纳";
            case SCOPE_RUNTIME_WRITEBACK -> "4/本模组运行时回写";
            default -> "3/外部位移";
        };
    }

    // ═══════════════════════════════════════════════
    // 动作层：摄入
    // ═══════════════════════════════════════════════

    /**
     * 一次摄入判定的结果。
     *
     * @param target 目标（胶囊中心，世界坐标）
     * @param report 判定路径的文字说明，供逐次日志使用
     */
    public record Decision(Vector3f target, String report) {
    }

    /**
     * 摄入一次外部位移：把宿主实体的位置写入转成对 KCC 的位置写入，并在 warp 落地前钉住对外位姿。
     * <p>
     * 调用方必须已经确认「这次写入不在任何已知镜像作用域内」（{@link #isInScope()} 为假），
     * 并把<b>这次写入的目标</b>（{@code Entity#setPos} 的三个入参）传进来：
     * {@code mixin/EntityPositionWriteMixin} 在 {@code HEAD} 处拿到的正是它们。
     * <p>
     * 三条守卫：
     * <ol>
     *   <li><b>绑定</b> —— 装配体的宿主引用为空则不摄入（{@code ArmsCore#host}）。发起这次写入的实体与
     *       它由绑定关系维持一致（{@code common/IArmsHost.java#setControlledArmsCore} 同时写两个方向），
     *       因此这里只读装配体那一侧，不需要调用方把宿主再传一遍；</li>
     *   <li><b>无变化</b> —— 目标等于锚点则不摄入（{@link #isAtAnchor(double, double, double)}）。
     *       连接相位把实体重断言回基准值的那一笔、以及起床与骑乘那类
     *       {@code connection.teleport(当前位置)}，目标都取自锚点本身，在这里被吞掉；</li>
     *   <li><b>幂等</b> —— 目标与当前 pin 的落点相同则不重复采纳（实体正在追平一个已在途的目标）。</li>
     * </ol>
     * 通过则按 §6.2 钉住 {@code DATA_POS}、推进锚点，并把落点交给 {@code SparkLevel#submitImmediateTask}
     * 投递（{@code PPhase.ALL}）。投递与判据同处一个调用栈：落点先写进 {@link #warpPending} 与三个落点
     * 字段，任务只取它们，因此同一 tick 内的多次摄入按最后写入者生效。
     *
     * @param feetX 这次写入的目标 X（包围盒底面基准）
     * @param feetY 这次写入的目标 Y
     * @param feetZ 这次写入的目标 Z
     * @return 已采纳时返回判定结果；未采纳时为 {@code null}
     */
    public @Nullable Decision intake(double feetX, double feetY, double feetZ) {
        // 绑定守卫：装配体没有宿主（或正在解绑）时不存在「外部位移」这回事
        if (core.getHost() == null) return null;

        // 无变化：目标就是实体当前被合法持有的位置，不是位移
        if (isAtAnchor(feetX, feetY, feetZ)) return null;

        float targetX = (float) feetX;
        float targetY = (float) (feetY + MechaBodyPreset.HALF_TOTAL);
        float targetZ = (float) feetZ;

        // 幂等：目标与当前 pin 的落点相同。此时实体正在追平一个已在途的目标，不重复投递
        if (pinActive && samePosition(pinTarget.x, pinTarget.y, pinTarget.z,
                targetX, targetY, targetZ)) {
            return null;
        }

        // 采纳生效：锚点跟着走到新位置，同一目标的重复写入此后由「无变化」守卫拦下
        advanceAnchor(feetX, feetY, feetZ);

        pinActive = true;
        pinTarget.set(targetX, targetY, targetZ);
        pinTicksLeft = PIN_TICK_BUDGET;
        // 先清落地标志再武装：上一次的残留会让新 pin 在第一次 syncToClients 就被误判为已落地
        pinLanded = false;

        warpX = targetX;
        warpY = targetY;
        warpZ = targetZ;
        warpPending = true;
        SparkLevel.submitImmediateTask(core.getLevel(), PPhase.ALL, this::runWarpTask);

        intakeCount++;
        String report = "栈空、目标 ≠ 锚点（不在自身运动 / 客户端上报 / 本模组运行时回写三类作用域内）";
        logIntake(targetX, targetY, targetZ, report);
        return new Decision(new Vector3f(targetX, targetY, targetZ), report);
    }

    /**
     * 位置是否等同（三个分量逐个比较，为 float32 往返留一个容差）。
     * <p>
     * 入参取 {@code double}：锚点保留 {@code Entity#setPos} 入参的精度，pin 的目标是 float32 的胶囊中心，
     * 两者共用同一个比较。
     */
    private static boolean samePosition(double ax, double ay, double az,
                                        double bx, double by, double bz) {
        return Math.abs(ax - bx) < POSITION_EPSILON
                && Math.abs(ay - by) < POSITION_EPSILON
                && Math.abs(az - bz) < POSITION_EPSILON;
    }

    // ═══════════════════════════════════════════════
    // 动作层：pin
    // ═══════════════════════════════════════════════

    /**
     * pin 的只读视图。
     *
     * @param x         目标胶囊中心 X
     * @param y         目标胶囊中心 Y
     * @param z         目标胶囊中心 Z
     * @param landed    物理线程是否已执行 KCC 侧 warp
     * @param ticksLeft 剩余 tick 预算
     */
    public record PinView(float x, float y, float z, boolean landed, int ticksLeft) {
    }

    /**
     * 当前 pin；没有生效的 pin 时返回 {@code null}。
     * <p>
     * {@code syncToClients} 用它的目标填 {@code DATA_POS}，并在 {@code landed} 为真时解除；物理线程完成
     * warp 后经 {@link #markPinLanded()} 把 {@code landed} 翻起来。
     *
     * @return pin 的只读视图；无 pin 时为 {@code null}
     */
    public @Nullable PinView getPin() {
        if (!pinActive) return null;
        return new PinView(pinTarget.x, pinTarget.y, pinTarget.z, pinLanded, pinTicksLeft);
    }

    /**
     * 每个服务端 tick 调一次，递减 pin 的 tick 预算。
     * <p>
     * 兜底：物理线程未运行、维度正在卸载等情形下落地标志永远不会翻起来，超过 {@link #PIN_TICK_BUDGET}
     * 就直接解除，避免钉在一个永不生效的目标上。
     */
    public void tickPinBudget() {
        if (!pinActive) return;
        if (--pinTicksLeft <= 0) {
            ARMS.LOGGER.warn("[ARMS-Core] {} 的位移 pin 超过 {} tick 未落地，按兜底解除；目标=({}, {}, {})",
                    core.getAssemblyId(), PIN_TICK_BUDGET,
                    fmt(pinTarget.x), fmt(pinTarget.y), fmt(pinTarget.z));
            clearPin();
        }
    }

    /** 物理线程：KCC 侧 warp 已执行，主线程下一次 {@code syncToClients} 可解除 pin。 */
    public void markPinLanded() {
        if (pinActive) {
            pinLanded = true;
        }
    }

    /** 解除 pin（落地、超预算、解绑）。 */
    public void clearPin() {
        pinActive = false;
        pinLanded = false;
        pinTicksLeft = 0;
    }

    /** 是否有生效的 pin。 */
    public boolean isPinActive() {
        return pinActive;
    }

    // ═══════════════════════════════════════════════
    // 动作层：执行 warp
    // ═══════════════════════════════════════════════

    /**
     * 物理线程（或主线程）执行 KCC 侧 warp。
     * <p>
     * {@code PPhase.ALL} 的任务在执行点有两处（物理线程的 {@code prePhysicsTick} / {@code physicsTick} 与
     * 主线程的 {@code LevelTickEvent.Pre/Post}），落在主线程时与物理步并发，写的是幽灵体变换，属既定的
     * 良性竞态（`docs/宿主位置权威与位移摄入设计.md` §6.1 第 1 行）。
     * <p>
     * 落点是<b>这次摄入的目标</b>，而移动一个已入世的 KCC 需要的不只是换位置：速度的垂直分量与本步遗留的
     * 施力状态都属于「上一步的落点上下文」，由 {@code common/control/MechaCharacter.java#warp} 一并复位
     * （它重写了 {@code com.jme3.bullet.objects.PhysicsCharacter#warp}）。
     * <p>
     * 只对权威实例生效：客户端实例不持有 KCC、没有物理空间。非权威实例仍然武装 pin，因为客户端那一侧的
     * {@code DATA_POS} 由同一条写入产生。
     */
    private void runWarpTask() {
        if (!warpPending) return;
        warpPending = false;
        if (!core.isAuthoritative()) return;
        // 落点用新向量而不是复用缓冲：本方法可能落在物理线程或主线程，而摄入是罕见事件，
        // 一次小额分配换掉「两个线程同时用同一个缓冲」这个不需要承担的风险
        core.getKcc().warp(new Vector3f(warpX, warpY, warpZ));
        markPinLanded();
    }

    // ═══════════════════════════════════════════════
    // 诊断
    // ═══════════════════════════════════════════════

    /** 已采纳的摄入次数。 */
    public int getIntakeCount() {
        return intakeCount;
    }

    private void logIntake(float x, float y, float z, String report) {
        if (loggedCount >= INTAKE_LOG_LIMIT) return;
        loggedCount++;
        ARMS.LOGGER.info("[ARMS-Core] {} 摄入一次外部位移：目标胶囊中心=({}, {}, {})，判定={}",
                core.getAssemblyId(), fmt(x), fmt(y), fmt(z), report);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    @Override
    public String toString() {
        return "HostPositionIntake[depth=" + depth
                + ", anchor=" + (anchorValid
                        ? "(" + fmt(anchorX) + ", " + fmt(anchorY) + ", " + fmt(anchorZ) + ")"
                        : "未建立")
                + ", pin=" + (pinActive
                        ? "(" + fmt(pinTarget.x) + ", " + fmt(pinTarget.y) + ", " + fmt(pinTarget.z) + ")"
                                + (pinLanded ? "/已落地" : "/在途")
                        : "无")
                + ", intake=" + intakeCount + "]";
    }
}
