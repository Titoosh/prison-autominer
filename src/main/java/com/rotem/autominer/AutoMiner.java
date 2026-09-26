package com.rotem.autominer;

import com.rotem.autominer.config.MinerConfig;
import com.rotem.autominer.core.BotController;
import com.rotem.autominer.gui.ConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = AutoMiner.MODID, name = "Prison AutoMiner", version = AutoMiner.VERSION, clientSideOnly = true)
public class AutoMiner {

    public static final String MODID = "prisonautominer";
    public static final String VERSION = "1.0.1";

    /** Hard-wired panic key, live even while the confirm window is up. */
    private static final int PANIC_KEY = Keyboard.KEY_J;

    private KeyBinding menuKey;

    private boolean panicWasDown;
    private boolean toggleWasDown;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        menuKey = new KeyBinding("Open AutoMiner menu", Keyboard.KEY_K, "Prison AutoMiner");
        ClientRegistry.registerKeyBinding(menuKey);

        // In 1.8.9 tick events live on the FML bus; overlay events on the Forge bus.
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);

        MinerConfig.get();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        handleKeys(mc);
        BotController.get().onTick();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            BotController.get().onRenderTick();
        }
    }

    /**
     * Keys are polled raw rather than through keybind.isPressed(), because keybinds
     * are only serviced when no GUI is open -- and the moment the stop key matters
     * most is while the confirm window covers the screen.
     *
     * Raw polling means we have to decide for ourselves when a keypress is aimed at
     * the game and when it is just typing, so there are two different gates:
     *
     *  - stopping is allowed under any screen except chat and this mod's own menu,
     *    so the confirm window and the inventory never trap a running bot;
     *  - starting is allowed only with no screen open at all, so a key typed into
     *    chat can never kick the bot off.
     */
    private void handleKeys(Minecraft mc) {
        BotController bot = BotController.get();
        GuiScreen screen = mc.currentScreen;

        // Chat is typing; the config screen is either typing or rebinding. Neither
        // may drive the bot.
        boolean typing = screen instanceof GuiChat || screen instanceof ConfigScreen;
        boolean stopAllowed = !typing;
        boolean startAllowed = screen == null;

        int toggleKey = MinerConfig.get().toggleKey;

        // Edge state is tracked from the raw poll whatever the gates say. Otherwise a
        // key held down while chat closes would look like a fresh press afterwards.
        boolean panicDown = Keyboard.isKeyDown(PANIC_KEY);
        boolean panicEdge = panicDown && !panicWasDown;
        panicWasDown = panicDown;

        boolean toggleDown = toggleKey > 0 && Keyboard.isKeyDown(toggleKey);
        boolean toggleEdge = toggleDown && !toggleWasDown;
        toggleWasDown = toggleDown;

        // The hard-wired stop key only ever stops, and is live whenever the bot runs.
        if (panicEdge && stopAllowed && bot.isRunning()) {
            bot.stop("stop key");
            return;
        }

        if (toggleEdge) {
            if (bot.isRunning()) {
                if (stopAllowed) {
                    bot.stop("toggle key");
                }
            } else if (startAllowed) {
                bot.start();
            }
        }

        if (screen == null && menuKey != null && menuKey.isPressed()) {
            mc.displayGuiScreen(new ConfigScreen());
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        BotController bot = BotController.get();
        if (!bot.isRunning()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String line = EnumChatFormatting.AQUA + "AutoMiner " + EnumChatFormatting.WHITE + bot.statusLine();
        mc.fontRendererObj.drawStringWithShadow(line, 4, 4, 0xFFFFFF);

        if (bot.getFailsafe().getClickCount() > 0) {
            mc.fontRendererObj.drawStringWithShadow(
                    EnumChatFormatting.GRAY + "confirms handled: " + bot.getFailsafe().getClickCount()
                            + "   /sell sent: " + bot.getSell().getSentCount(),
                    4, 14, 0xAAAAAA);
        }
    }
}
