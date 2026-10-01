package io.github.sweetzonzi.arms_core.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.sweetzonzi.arms_core.ARMS;
import io.github.sweetzonzi.arms_core.common.control.MechaEvent;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 离散事件的客户端按键来源。
 * <p>
 * 客户端只有两条上行内容：连续量（WASD / 视角 / 按键按住）与离散边沿（{@code MechaEvent}）。
 * 连续量由 {@link ARMSClient} 每 tick 采集；离散边沿需要一个「产生事件」的时刻，
 * 而原版按键里没有与 {@code DODGE} / {@code TOGGLE_PRONE} 等价的输入，因此这里注册按键。
 * <p>
 * 「按下」被翻译成一次 {@link ARMSClient#queueEvent}：客户端只表达意图，服务端校验控制权与
 * 当前状态后决定是否生效。按键不直接改任何本地状态——客户端不跑状态机。
 * <p>
 * 键位在「控制 → 按键绑定 → 游戏玩法」分类下，默认不占用原版已用键：
 * <ul>
 *   <li>闪避：左 Alt</li>
 *   <li>卧倒切换：B</li>
 * </ul>
 *
 * @author Sweetzonzi
 */
@EventBusSubscriber(modid = ARMS.MOD_ID, value = Dist.CLIENT)
public final class ARMSKeyMappings {

    private ARMSKeyMappings() {
    }

    /** 按键绑定分类的翻译键 */
    private static final String CATEGORY = "key.categories." + ARMS.MOD_ID + ".gameplay";

    /** 闪避 */
    public static final KeyMapping DODGE = new KeyMapping(
            "key." + ARMS.MOD_ID + ".dodge",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            CATEGORY);

    /** 卧倒切换 */
    public static final KeyMapping TOGGLE_PRONE = new KeyMapping(
            "key." + ARMS.MOD_ID + ".toggle_prone",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY);

    /** 按下时是否曾经按过，用于只认「边沿」而不是「按住」 */
    private static boolean dodgeWasDown;
    private static boolean proneWasDown;

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(DODGE);
        event.register(TOGGLE_PRONE);
    }

    /** 每客户端 tick 读一次边沿，转成待上行事件。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        boolean dodgeDown = DODGE.isDown();
        if (dodgeDown && !dodgeWasDown) {
            ARMSClient.queueEvent(MechaEvent.DODGE, false);
        }
        dodgeWasDown = dodgeDown;

        boolean proneDown = TOGGLE_PRONE.isDown();
        if (proneDown && !proneWasDown) {
            ARMSClient.queueEvent(MechaEvent.TOGGLE_PRONE, false);
        }
        proneWasDown = proneDown;
    }
}
