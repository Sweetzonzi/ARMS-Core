package io.github.sweetzonzi.arms_core.common.gametest;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.HostPositionIntake;
import io.github.sweetzonzi.arms_core.common.IArmsHost;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * {@link ArmsCore} 在 GameTest 车道上的两条探针：它能不能被实例化出来，以及
 * {@link HostPositionIntake} 的写入分类对不对。
 * <p>
 * <b>第一条验证的前提。</b> {@link ArmsCore} 的服务端构造会直接从所在 Level 取物理空间
 * （{@code SparkLevel.getPhysicsLevel(level).getWorld()}）并创建 KCC，这条链要求
 * {@code cn.solarmoon.spark_core.physics.level.PhysicsLevelApplier} 的 {@code LevelEvent.Load}
 * 已经跑过。构造能成功，就说明 GameTest 环境里物理空间已就绪、KCC 可建、能取到物理步长。
 * <p>
 * <b>第二条为什么能在这条车道上跑。</b> 分类判据是纯逻辑：作用域栈的深度、锚点的三个 double、
 * 已采纳的次数，都与坐标精度无关——断言用整数级坐标（{@code 0.5}、{@code 40.0}），不量位移。做法是
 * 绑一个 {@code GameTestHelper#makeMockPlayer} 到装配体，然后直接在作用域内外调
 * {@code Entity#setPos}，走的正是 {@code mixin/EntityPositionWriteMixin} 的注入点。
 * <p>
 * <b>这条车道量不了的东西。</b> 位移与速度：测试网格落在随机原点（±1.5e7）上，那里 float32 的量化
 * 步长约 1 m（{@code docs/GameTest车道指南.md}）。因此 KCC warp 的落地位置、行走/跳跃的力学、pin 在
 * 真实上行往返中的解除时机，都走 JUnit 车道（{@code src/test/java/}）与实机验收。
 * <p>
 * <b>不注册进 {@code MechaCoreRegistry}</b>：本探针只覆盖「构造得出来」与「分类判得对」，不覆盖
 * 「注册与广播」。注册会向维度广播创建包，而 GameTest 里没有真实客户端连接。KCC 不入世也就没有需要
 * 清理的物理对象。
 * <p>
 * <b>场地。</b> GameTest 要求每个用例有一份已保存的结构模板，且模板必须事先存在于资源里——框架在
 * {@code GameTestRunner.createStructuresForBatch} 里读它，那个时点早于同批次的批次函数
 * （{@code net/minecraft/gametest/framework/GameTestRunner.java:90} 与 {@code :93}）。模板由
 * {@code io.github.sweetzonzi.arms_core.tools.GenerateGameTestStructure} 生成到
 * {@code data/arms_core/structure/empty_platform.nbt}（{@code gradlew generateGameTestStructure}），
 * 与 {@link #PLATFORM} 一一对应。
 * <p>
 * <b>运行方式</b>：{@code gradlew runGameTestServer}。GameTest 不属于 {@code gradlew test} 的 JUnit 车道。
 *
 * @author Sweetzonzi
 */
@GameTestHolder(ARMS.MOD_ID)
@PrefixGameTestTemplate(false)
public class ArmsCoreGameTest {

    /** 场地结构模板的 id，对应 {@code src/main/resources/data/arms_core/structure/empty_platform.nbt}。 */
    private static final ResourceLocation PLATFORM =
            ResourceLocation.fromNamespaceAndPath(ARMS.MOD_ID, "empty_platform");

    /**
     * 本类用例所属的批次名。
     * <p>
     * 必须自成一档：每个批次至多一个 {@code @BeforeBatch} 方法，而同一开发环境里的兄弟仓库
     * BallisticsFramework 占用 {@code defaultBatch}，两处的批次函数撞在同一个批次上会让服务端在注册阶段
     * 直接失败：{@code RuntimeException: Hey, there should only be one interface BeforeBatch method per batch}。
     */
    private static final String BATCH = "armsCore";

    /**
     * 物理空间就绪，且 {@link ArmsCore} 能构造出来。
     * <p>
     * 断言四件事：
     * <ol>
     *   <li>构造不抛异常——即 {@code SparkLevel.getPhysicsLevel(level)} 拿到了物理空间
     *       （{@link ArmsCore#physicsStepSeconds()} 在物理空间缺失时会抛 {@code IllegalStateException}）；</li>
     *   <li>服务端路径拿到权威实例与 KCC；</li>
     *   <li>装配体持有位移摄入的分类状态；</li>
     *   <li>物理步长是有限正数——它是 {@code 1 / tps}，能算出值说明物理空间已经 {@code start()} 过。</li>
     * </ol>
     */
    @GameTest(timeoutTicks = 100, template = "empty_platform", batch = BATCH)
    public static void physicsSpaceIsReadyAndCoreConstructs(GameTestHelper helper) {
        ArmsCore core = new ArmsCore(helper.getLevel(), UUID.randomUUID());

        helper.assertTrue(core.isAuthoritative(), "服务端构造必须给出权威实例");
        helper.assertTrue(core.getKcc() != null, "权威实例必须持有 KCC");
        helper.assertTrue(core.getPositionIntake() != null, "装配体必须持有位移摄入的分类状态");
        helper.assertTrue(core.getLevel() == helper.getLevel(), "装配体所属 level 应当是构造时传入的那个");

        float step = core.physicsStepSeconds();
        helper.assertTrue(step > 0f && Float.isFinite(step),
                "物理步长应当是有限正数，实际为 " + step);

        helper.succeed();
    }

    /**
     * 写入分类：三类已知镜像不摄入（其中两类推进锚点），栈空且目标不等于锚点才采纳一次。
     * <p>
     * 六条断言对应 {@code docs/宿主位置权威与位移摄入设计.md} §5.1 的四类写入与 §6.3 的三条守卫：
     * <ol>
     *   <li>第 4 类（本模组运行时回写，两端同源）在作用域内不摄入，但把锚点推到目标；</li>
     *   <li>栈空、目标 == 锚点（连接相位把实体重断言回基准值那一类）不摄入；</li>
     *   <li>第 1 类（自身运动）在作用域内不摄入，且<b>不</b>推进锚点——它写的是 tick 内实体自走的位置，
     *       随后会被基准复位抹掉；推进它会让紧随其后的基准复位被判成位移；</li>
     *   <li>第 2 类（客户端上报与回声）在作用域内不摄入，推进锚点；</li>
     *   <li>栈空且目标 ≠ 锚点：采纳一次，锚点随采纳推进，pin 被武装；</li>
     *   <li>同一目标的重复写入由「无变化」守卫拦下，不产生第二次采纳。</li>
     * </ol>
     */
    @GameTest(timeoutTicks = 100, template = "empty_platform", batch = BATCH)
    public static void intakeClassifiesMirrorsAndAdoptsOnlyRealDisplacement(GameTestHelper helper) {
        ArmsCore core = new ArmsCore(helper.getLevel(), UUID.randomUUID());
        HostPositionIntake intake = core.getPositionIntake();
        Player host = helper.makeMockPlayer(GameType.CREATIVE);
        ((IArmsHost) host).setControlledArmsCore(core);

        // ① 第 4 类：本模组运行时回写。作用域内不摄入，锚点推到目标
        int depth = intake.enterScope(HostPositionIntake.SCOPE_RUNTIME_WRITEBACK);
        try {
            host.setPos(0.5, 64.0, 0.5);
        } finally {
            intake.exitScope(depth);
        }
        helper.assertTrue(intake.getIntakeCount() == 0,
                "第 4 类运行时回写不应被摄入，实际采纳 " + intake.getIntakeCount() + " 次");
        helper.assertTrue(intake.isAtAnchor(0.5, 64.0, 0.5),
                "第 4 类运行时回写应把锚点推到目标");

        // ② 栈空、目标 == 锚点：连接相位的基准复位那一类，不摄入
        host.setPos(0.5, 64.0, 0.5);
        helper.assertTrue(intake.getIntakeCount() == 0,
                "目标等于锚点（重断言当前位置）不应被摄入，实际采纳 " + intake.getIntakeCount() + " 次");

        // ③ 第 1 类：自身运动。不摄入，且不推进锚点
        depth = intake.enterScope(HostPositionIntake.SCOPE_SELF_MOTION);
        try {
            host.setPos(3.0, 64.0, 3.0);
        } finally {
            intake.exitScope(depth);
        }
        helper.assertTrue(intake.getIntakeCount() == 0,
                "第 1 类自身运动不应被摄入，实际采纳 " + intake.getIntakeCount() + " 次");
        helper.assertFalse(intake.isAtAnchor(3.0, 64.0, 3.0),
                "第 1 类自身运动不得推进锚点：那个位置下一步会被基准复位抹掉");

        // ④ 第 2 类：客户端上报与回声。不摄入，推进锚点
        depth = intake.enterScope(HostPositionIntake.SCOPE_CLIENT_REPORT);
        try {
            host.setPos(1.5, 64.0, 1.5);
        } finally {
            intake.exitScope(depth);
        }
        helper.assertTrue(intake.getIntakeCount() == 0,
                "第 2 类客户端上报不应被摄入，实际采纳 " + intake.getIntakeCount() + " 次");
        helper.assertTrue(intake.isAtAnchor(1.5, 64.0, 1.5),
                "第 2 类客户端上报应把锚点推到目标");

        // ⑤ 栈空、目标 ≠ 锚点：采纳一次
        host.setPos(40.0, 70.0, 40.0);
        helper.assertTrue(intake.getIntakeCount() == 1,
                "栈空且目标不等于锚点应恰好采纳一次，实际采纳 " + intake.getIntakeCount() + " 次");
        helper.assertTrue(intake.isAtAnchor(40.0, 70.0, 40.0),
                "采纳之后锚点应停在新位置，供随后的重复写入与回声使用");
        helper.assertTrue(intake.isPinActive(), "采纳应武装 pin，直到 KCC 侧 warp 落地");

        // ⑥ 同一目标的重复写入由「无变化」守卫拦下
        host.setPos(40.0, 70.0, 40.0);
        helper.assertTrue(intake.getIntakeCount() == 1,
                "同一目标的重复写入不应再次采纳，实际采纳 " + intake.getIntakeCount() + " 次");

        ((IArmsHost) host).setControlledArmsCore(null);
        helper.succeed();
    }
}
