package io.github.sweetzonzi.arms_core.common.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.ArmsCore;
import io.github.sweetzonzi.arms_core.common.MechaCoreRegistry;
import io.github.sweetzonzi.arms_core.common.control.MechaConditionSnapshot;
import io.github.sweetzonzi.arms_core.common.control.MechaEvent;
import io.github.sweetzonzi.arms_core.common.control.attr.MechaBodyPreset;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;
import java.util.UUID;

/**
 * {@code /arms} 调试命令 —— 阶段 1–3 唯一的权威端驱动入口。
 * <p>
 * 客户端不发任何输入包的阶段（阶段 1），以及不启动客户端时的专用服务端调试（阶段 2.7），
 * 都靠这里的子命令直接写 {@code conditionSnapshot} 与投递 {@link MechaEvent}。
 * 上行输入通道接通后（阶段 2），本命令保留为旁路调试源。
 * <p>
 * 子命令：
 * <ul>
 *   <li>{@code /arms spawn} —— 在命令执行者脚底创建并注册一个装配体</li>
 *   <li>{@code /arms list} —— 列出当前维度的装配体</li>
 *   <li>{@code /arms remove [uuid]} —— 注销装配体（省略 uuid 时注销全部）</li>
 *   <li>{@code /arms move <forward> <strafe> [yaw]} —— 写移动输入快照</li>
 *   <li>{@code /arms jump <held> <released>} —— 写跳跃按住状态；{@code released=true} 时另投递
 *       {@link MechaEvent#JUMP_RELEASE} 松开边沿</li>
 *   <li>{@code /arms stop} —— 恢复空快照</li>
 *   <li>{@code /arms event <name>} —— 投递一个离散事件</li>
 *   <li>{@code /arms control <uuid>} —— 把控制权交给命令执行者</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID)
public final class ArmsCoreDebugCommand {

    private ArmsCoreDebugCommand() {
    }

    private static final SimpleCommandExceptionType NO_CORE =
            new SimpleCommandExceptionType(Component.literal("当前维度没有装配体；先用 /arms spawn 创建"));
    private static final SimpleCommandExceptionType UNKNOWN_CORE =
            new SimpleCommandExceptionType(Component.literal("找不到该 UUID 的装配体"));
    private static final SimpleCommandExceptionType UNKNOWN_EVENT =
            new SimpleCommandExceptionType(Component.literal("未知事件名；可用 /arms event 查看补全列表"));
    private static final SimpleCommandExceptionType NOT_SERVER =
            new SimpleCommandExceptionType(Component.literal("本命令只能在服务端执行"));

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("arms")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn").executes(ArmsCoreDebugCommand::spawn))
                .then(Commands.literal("list").executes(ArmsCoreDebugCommand::list))
                .then(Commands.literal("remove")
                        .executes(ctx -> remove(ctx, null))
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "uuid")))))
                .then(Commands.literal("move")
                        .then(Commands.argument("forward", FloatArgumentType.floatArg(-1f, 1f))
                                .then(Commands.argument("strafe", FloatArgumentType.floatArg(-1f, 1f))
                                        .executes(ctx -> move(ctx, 0f))
                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                                .executes(ctx -> move(ctx, FloatArgumentType.getFloat(ctx, "yaw")))))))
                .then(Commands.literal("jump")
                        .then(Commands.argument("held", BoolArgumentType.bool())
                                .then(Commands.argument("released", BoolArgumentType.bool())
                                        .executes(ArmsCoreDebugCommand::jump))))
                .then(Commands.literal("stop").executes(ArmsCoreDebugCommand::stop))
                .then(Commands.literal("event")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (MechaEvent value : MechaEvent.values()) {
                                        builder.suggest(value.name().toLowerCase(Locale.ROOT));
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ArmsCoreDebugCommand::postEvent)))
                .then(Commands.literal("control")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .executes(ArmsCoreDebugCommand::control))));
    }

    // ==========================================
    // 生命周期子命令
    // ==========================================

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) throw NOT_SERVER.create();
        ServerLevel level = source.getLevel();

        UUID coreId = UUID.randomUUID();
        ArmsCore core = new ArmsCore(level, coreId);

        // 出生点：目标脚底位置 + HALF_TOTAL = 胶囊中心（计划 §3.14）
        float[] center = MechaBodyPreset.capsuleCenterFromFeet(
                (float) player.getX(), (float) player.getY(), (float) player.getZ());
        MechaCoreRegistry.enterPhysicsSpace(core,
                new com.jme3.math.Vector3f(center[0], center[1], center[2]));

        // 先绑定再注册：创建包携带真实 hostEntityId，客户端据此认出自己的机体。
        // 绑定同时使执行者成为控制者，这样 /arms move 与上行包都能驱动它。
        ArmsCore.bindHostOf(player, core);
        MechaCoreRegistry.addServer(core, core.getBoundHostEntityId());

        source.sendSuccess(() -> Component.literal("已创建装配体 " + coreId), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        var cores = MechaCoreRegistry.snapshot(level);
        if (cores.isEmpty()) {
            source.sendSuccess(() -> Component.literal("当前维度没有装配体"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("当前维度装配体：" + cores.size()), false);
        for (ArmsCore core : cores) {
            var data = core.getSyncedData();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "  %s pos=%s posture=%s gait=%s vertical=%s energy=%.1f",
                            core.getAssemblyId(),
                            data.get(ArmsCore.DATA_POS),
                            data.get(ArmsCore.DATA_POSTURE),
                            data.get(ArmsCore.DATA_GAIT),
                            data.get(ArmsCore.DATA_VERTICAL),
                            data.get(ArmsCore.DATA_ENERGY))), false);
        }
        return cores.size();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String uuidText) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        if (uuidText == null) {
            var cores = MechaCoreRegistry.snapshot(level);
            if (cores.isEmpty()) throw NO_CORE.create();
            int count = 0;
            for (ArmsCore core : cores) {
                if (MechaCoreRegistry.removeServer(core)) count++;
            }
            int removed = count;
            source.sendSuccess(() -> Component.literal("已注销 " + removed + " 个装配体"), true);
            return removed;
        }
        ArmsCore core = resolve(source, uuidText);
        MechaCoreRegistry.removeServer(core);
        source.sendSuccess(() -> Component.literal("已注销装配体 " + core.getAssemblyId()), true);
        return 1;
    }

    // ==========================================
    // 输入子命令
    // ==========================================

    private static int move(CommandContext<CommandSourceStack> ctx, float yaw) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        float forward = FloatArgumentType.getFloat(ctx, "forward");
        float strafe = FloatArgumentType.getFloat(ctx, "strafe");
        if (yaw == 0f) {
            ServerPlayer player = source.getPlayer();
            if (player != null) yaw = player.getYRot();
        }
        float viewYaw = yaw;
        var cores = MechaCoreRegistry.snapshot(source.getLevel());
        if (cores.isEmpty()) throw NO_CORE.create();
        for (ArmsCore core : cores) {
            writeSnapshot(core, forward, strafe, viewYaw, false);
        }
        source.sendSuccess(() -> Component.literal(
                String.format(Locale.ROOT, "输入已写入：forward=%.2f strafe=%.2f yaw=%.1f", forward, strafe, viewYaw)), false);
        return 1;
    }

    private static int jump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        boolean held = BoolArgumentType.getBool(ctx, "held");
        boolean released = BoolArgumentType.getBool(ctx, "released");
        var cores = MechaCoreRegistry.snapshot(source.getLevel());
        if (cores.isEmpty()) throw NO_CORE.create();
        for (ArmsCore core : cores) {
            MechaConditionSnapshot current = core.getMechaControl() == null
                    ? MechaConditionSnapshot.EMPTY
                    : core.getMechaControl().getConditionSnapshot();
            writeSnapshot(core, current.inputForward(), current.inputStrafe(),
                    current.viewYaw(), held);
            // 松开边沿与按键按住分开表达：按住写快照，松开投递一次性事件
            if (released) {
                core.postEvent(MechaEvent.JUMP_RELEASE);
            }
        }
        source.sendSuccess(() -> Component.literal("跳跃输入：held=" + held + " released=" + released), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var cores = MechaCoreRegistry.snapshot(source.getLevel());
        if (cores.isEmpty()) throw NO_CORE.create();
        for (ArmsCore core : cores) {
            core.writeConditionSnapshot(MechaConditionSnapshot.EMPTY);
        }
        source.sendSuccess(() -> Component.literal("输入已重置为空快照"), false);
        return 1;
    }

    private static int postEvent(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        MechaEvent event;
        try {
            event = MechaEvent.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw UNKNOWN_EVENT.create();
        }
        var cores = MechaCoreRegistry.snapshot(source.getLevel());
        if (cores.isEmpty()) throw NO_CORE.create();
        for (ArmsCore core : cores) {
            core.postEvent(event);
        }
        source.sendSuccess(() -> Component.literal("已投递事件 " + event), false);
        return 1;
    }

    /**
     * 把某个装配体交给命令执行者承载。
     * <p>
     * 「控制权」不是独立的一份状态：它由绑定关系派生（{@code common/MechaInputHandler.java#isController}
     * 读的就是宿主实体上的绑定字段），因此这里做的是重新绑定宿主。
     */
    private static int control(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) throw NOT_SERVER.create();
        ArmsCore core = resolve(source, StringArgumentType.getString(ctx, "uuid"));
        ArmsCore.bindHostOf(player, core);
        source.sendSuccess(() -> Component.literal("已把装配体交给 " + player.getGameProfile().getName()), true);
        return 1;
    }

    // ==========================================
    // 辅助
    // ==========================================

    /**
     * 取当前快照并在其基础上覆盖若干字段后重新发布。
     * <p>
     * {@link MechaConditionSnapshot} 是 record，没有 {@code with*} 方法，因此必须从现有快照
     * 读出全部字段再逐项填入 builder；直接用 {@code MechaConditionSnapshot.builder()} 会让
     * 调试命令把视角与环境字段重置为默认值。
     * <p>
     * 参数是「覆盖字段」的若干取值，而不是一个 builder 变换函数：Lombok 生成的 Builder
     * 未必是 public，把它的类型写进方法签名会让编译期去找一个不可见的类。
     *
     * @param forward   新的前后输入
     * @param strafe    新的左右输入
     * @param viewYaw   新的视角偏航（度）
     * @param jumpHeld  跳跃键是否按住
     */
    private static void writeSnapshot(ArmsCore core, float forward, float strafe, float viewYaw,
                                      boolean jumpHeld) {
        MechaConditionSnapshot current = core.getMechaControl() == null
                ? MechaConditionSnapshot.EMPTY
                : core.getMechaControl().getConditionSnapshot();
        core.writeConditionSnapshot(MechaConditionSnapshot.builder()
                .inputForward(forward)
                .inputStrafe(strafe)
                .jumpPressed(jumpHeld)
                .sprintPressed(current.sprintPressed())
                .walkKeyPressed(current.walkKeyPressed())
                .sneaking(current.sneaking())
                .viewYaw(viewYaw)
                .viewPitch(current.viewPitch())
                .inWater(current.inWater())
                .inLava(current.inLava())
                .isDead(current.isDead())
                .isSleeping(current.isSleeping())
                .isFallFlying(current.isFallFlying())
                .isInWall(current.isInWall())
                .isOnFire(current.isOnFire())
                .build());
    }

    private static ArmsCore resolve(CommandSourceStack source, String uuidText) throws CommandSyntaxException {
        UUID coreId;
        try {
            coreId = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            throw UNKNOWN_CORE.create();
        }
        ArmsCore core = MechaCoreRegistry.get(source.getLevel(), coreId);
        if (core == null) throw UNKNOWN_CORE.create();
        return core;
    }
}
