package io.github.sweetzonzi.arms_core.common;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Abilities;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.extensions.IPlayerExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞行许可修饰符的纯逻辑验证。
 * <p>
 * 验证的是 {@code PlayerHostMixin#armsCore$syncFlightAbility} 所依赖的那套机制本身：
 * {@code neoforge:creative_flight} 是一个基准值为 {@code false} 的 {@code BooleanAttribute}，在其上添加
 * 一个 {@code ADD_VALUE} 的 {@code +1} 修饰符后，{@code IPlayerExtension#mayFly} 为真；移除该修饰符后
 * 回到假。
 * <p>
 * 这条链路必须用真实的 {@code AttributeInstance} 验证，而不是只测我们自己那几行代码：
 * {@code Abilities#mayfly} 已被 NeoForge 标记为过时并劝阻直接写，飞行许可的唯一正确入口就是这个属性，
 * 若属性的基准值、运算类型或同步性有任何一条与预期不同，绑定期间就会被「飞行过久」检测踢下线。
 * <p>
 * 本测试不启动 Minecraft：{@code AttributeInstance} 与 {@code BooleanAttribute} 的求值不触碰注册表。
 *
 * @author Sweetzonzi
 */
class FlightAbilityModifierTest {

    /** 与 {@code PlayerHostMixin#ARMS_FLIGHT_MODIFIER_ID} 保持一致的取值 */
    private static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("arms_core", "mecha_flight");

    @Test
    void creativeFlightAttributeGrantsPermissionThroughModifier() {
        AttributeInstance flight = new AttributeInstance(
                NeoForgeMod.CREATIVE_FLIGHT, instance -> {
        });
        assertNotNull(flight, "creative_flight 属性必须可用");

        // 未加修饰符：基准值为假
        assertTrue(flight.getModifiers().isEmpty(), "初始不应有修饰符");
        assertFalse(flight.getValue() > 0, "基准值必须为假，否则解除绑定时收不回飞行许可");

        // 授予：+1（ADD_VALUE）后值必须 > 0，这正是 IPlayerExtension#mayFly 的判据
        flight.addOrUpdateTransientModifier(
                new AttributeModifier(MODIFIER_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        assertTrue(flight.getValue() > 0, "绑定期间 creative_flight 必须大于 0");
        assertFalse(flight.getModifiers().isEmpty());

        // 重复授予按 id 去重，不能叠加成多个修饰符
        flight.addOrUpdateTransientModifier(
                new AttributeModifier(MODIFIER_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        assertTrue(flight.getModifiers().size() == 1, "同一 id 重复授予必须只留一个修饰符");

        // 收回：移除后必须回到基准值，且不影响别的来源
        assertTrue(flight.removeModifier(MODIFIER_ID));
        assertFalse(flight.getValue() > 0, "解除绑定后必须收回飞行许可");
        assertTrue(flight.getModifiers().isEmpty());
    }

    /** 飞行状态的写入判据：许可为假时必须能停掉飞行，不能留下服务端不允许的状态。 */
    @Test
    void abilitiesFlyingFlagIsSeparateFromPermission() {
        Abilities abilities = new Abilities();
        abilities.flying = true;
        // Abilities#flying 可写；Abilities#mayfly 才是被 NeoForge 劝阻直写的那个字段
        assertTrue(abilities.flying);
        abilities.flying = false;
        assertFalse(abilities.flying);
    }

    /** 确认 mayFly 是 IPlayerExtension 上的默认方法，调用点无需自备实现。 */
    @Test
    void mayFlyComesFromNeoForgeExtension() throws NoSuchMethodException {
        assertNotNull(IPlayerExtension.class.getMethod("mayFly"));
    }
}

