package io.github.sweetzonzi.arms_core.tools;

import net.minecraft.SharedConstants;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成 GameTest 场地模板 {@code data/arms_core/structure/empty_platform.nbt}。
 * <p>
 * <b>为什么需要它。</b> GameTest 框架在 {@code GameTestRunner.createStructuresForBatch}
 * （{@code net/minecraft/gametest/framework/GameTestRunner.java:90}）里读结构模板，这个时点<b>早于</b>
 * 同一批次的 {@code @BeforeBatch} 函数（同文件 {@code :93}）。因此模板不能靠批次函数现搭——必须事先存在于
 * 资源里，否则服务端在批处理开始时抛 {@code IllegalStateException: Missing test structure}。
 * <p>
 * <b>为什么不手搓 NBT。</b> 模板的持久化格式由 {@code StructureTemplate#save} 定义，手工拼二进制既容易写错、
 * 也会随版本漂移。本类走游戏自己的序列化路径——先用方块状态拼出 {@code StructureTemplate#save} 的形态，
 * 再交给 {@link NbtIo} 写出——得到的文件与结构方块保存出来的同构。它依赖方块注册表，因此必须在
 * Minecraft/NeoForge 的测试运行时里执行，不能放进纯逻辑测试。
 * <p>
 * <b>怎么跑。</b> {@code gradlew generateGameTestStructure}。产物写入
 * {@code src/main/resources/data/arms_core/structure/empty_platform.nbt} 并随资源打包。改了场地几何后
 * 重新执行一次即可；日常的 {@code gradlew test} 只会校验已有模板可被解析，不重写它。
 * <p>
 * <b>场地形状。</b> 5×3×5、底面一层石砖、其余为空。与兄弟仓库 BallisticsFramework 的 {@code empty_arena}
 * 同规格，用途只是给用例一个落脚面。
 *
 * @author Sweetzonzi
 */
class GenerateGameTestStructure {

    /** 模板在资源里的相对路径，与 {@code ArmsCoreGameTest.PLATFORM} 的命名空间 + 名字一致。 */
    private static final String TEMPLATE_RESOURCE = "data/arms_core/structure/empty_platform.nbt";

    /** 场地尺寸：5 宽 × 3 高 × 5 深。 */
    private static final Vec3i SIZE = new Vec3i(5, 3, 5);

    /** 重新生成模板时的开关；缺省只校验，避免每次 {@code gradlew test} 都写源目录。 */
    private static final String REGENERATE_PROPERTY = "arms_core.regenerateGameTestStructure";

    @Test
    void templateExistsAndParses() throws Exception {
        Path inResources = Path.of("src", "main", "resources", TEMPLATE_RESOURCE);
        Path inBuild = Path.of("build", "resources", "main", TEMPLATE_RESOURCE);

        if (Boolean.getBoolean(REGENERATE_PROPERTY) || !Files.exists(inResources)) {
            byte[] generated = generateTemplateNbt();
            Files.createDirectories(inResources.getParent());
            Files.write(inResources, generated);
            assertTrue(Files.size(inResources) > 0, "生成的模板不应为空");
        }

        Path toCheck = Files.exists(inResources) ? inResources : inBuild;
        assertTrue(Files.exists(toCheck),
                "缺少 GameTest 场地模板 " + TEMPLATE_RESOURCE
                        + "；执行 gradlew generateGameTestStructure 生成它");

        StructureTemplate template = new StructureTemplate();
        try (InputStream in = Files.newInputStream(toCheck)) {
            template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                    NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
        }

        assertEquals(SIZE.getX(), template.getSize().getX(), "模板宽度应与场地一致");
        assertEquals(SIZE.getY(), template.getSize().getY(), "模板高度应与场地一致");
        assertEquals(SIZE.getZ(), template.getSize().getZ(), "模板深度应与场地一致");
    }

    /**
     * 用游戏自己的序列化路径产出模板 NBT 字节。
     * <p>
     * 形态与 {@code StructureTemplate#save} 一致：{@code size} 三元组、{@code blocks} 列表（每项含
     * {@code pos} 与 {@code state}）、以及一个只含石砖的 {@code palette}。
     */
    private static byte[] generateTemplateNbt() throws Exception {
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
        CompoundTag paletteEntry = new CompoundTag();
        paletteEntry.putString("Name", "minecraft:stone_bricks");

        ListTag palette = new ListTag();
        palette.add(paletteEntry);

        ListTag blocks = new ListTag();
        for (int x = 0; x < SIZE.getX(); x++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                CompoundTag block = new CompoundTag();
                block.put("pos", newListTag(x, 0, z));
                block.put("state", NbtUtils.writeBlockState(floor));
                blocks.add(block);
            }
        }

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("size", newListTag(SIZE.getX(), SIZE.getY(), SIZE.getZ()));
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(buffer)) {
            NbtIo.writeCompressed(root, data);
        }
        return buffer.toByteArray();
    }

    private static ListTag newListTag(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }
}
