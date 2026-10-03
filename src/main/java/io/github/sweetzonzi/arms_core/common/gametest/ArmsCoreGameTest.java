package io.github.sweetzonzi.arms_core.common.gametest;

import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * {@link ArmsCore} 在 GameTest 车道上的最小探针：它能不能被实例化出来。
 * <p>
 * <b>它验证的前提。</b> {@link ArmsCore} 的服务端构造会直接从所在 Level 取物理空间
 * （{@code SparkLevel.getPhysicsLevel(level).getWorld()}）并创建 KCC，这条链要求
 * {@code cn.solarmoon.spark_core.physics.level.PhysicsLevelApplier} 的 {@code LevelEvent.Load}
 * 已经跑过。构造能成功，就说明 GameTest 环境里物理空间已就绪、KCC 可建、能取到物理步长。
 * <p>
 * <b>本类到此为止。</b> 位移摄入、作用域栈、pin、宿主绑定与 KCC warp 的验证不在这条车道上：
 * GameTest 服务端把测试网格摆在 ±1.5e7 附近（{@code GameTestServer#startTests} 用
 * {@code ServerLevel#random} 取原点，没有可覆盖的属性），而 jme3 与 Bullet 是单精度——那里 float32
 * 的量化步长约 1 m，位移与速度通道量不出东西。这些路径的验证走实机与 JUnit。
 * <p>
 * <b>不注册进 {@code MechaCoreRegistry}</b>：本探针只覆盖「构造得出来」，不覆盖「注册与广播」。注册会向
 * 维度广播创建包，而 GameTest 里没有真实客户端连接。KCC 不入世也就没有需要清理的物理对象。
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
}
