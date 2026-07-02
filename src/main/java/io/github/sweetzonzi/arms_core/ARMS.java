package io.github.sweetzonzi.arms_core;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

/**
 * ARMS-Core 模组入口。
 * <p>
 * Machine-Max 的附属模组，提供机娘角色控制器等扩展功能。
 *
 * @author Sweetzonzi
 */
@Mod(ARMS.MOD_ID)
public class ARMS {

    /** 模组 ID */
    public static final String MOD_ID = "arms_core";

    /** 日志器 */
    public static final Logger LOGGER = LogUtils.getLogger();

    public ARMS(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // 注册事件总线（服务端事件如 ServerStarting）
        NeoForge.EVENT_BUS.register(this);

        // 注册配置
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[ARMS-Core] 通用初始化完成");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[ARMS-Core] 服务端启动中");
    }
}
