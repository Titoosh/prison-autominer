package com.rotem.autominer.gui;

import com.rotem.autominer.config.MinerConfig;
import com.rotem.autominer.core.BotController;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The K menu. */
public class ConfigScreen extends GuiScreen {

    private static final int PANEL_W = 330;
    private static final int PANEL_H = 262;

    private static final int ID_SELL = 1;
    private static final int ID_BREAKS = 2;
    private static final int ID_PET = 3;
    private static final int ID_HUMAN = 4;
    private static final int ID_REBIND = 5;
    private static final int ID_TOGGLE = 6;
    private static final int ID_DONE = 7;

    private final MinerConfig cfg = MinerConfig.get();
    private final List<RangeSlider> sliders = new ArrayList<RangeSlider>();

    private RangeSlider sellInterval;
    private RangeSlider runTime;
    private RangeSlider breakEvery;
    private RangeSlider breakLength;
    private RangeSlider petSlot;
    private RangeSlider pickSlot;

    private GuiButton sellButton;
    private GuiButton breaksButton;
    private GuiButton petButton;
    private GuiButton humanButton;
    private GuiButton rebindButton;
    private GuiButton toggleButton;

    private boolean awaitingKey;
    private String rebindNote = "";

    private int left;
    private int top;

    @Override
    public void initGui() {
        buttonList.clear();
        sliders.clear();

        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        int colW = (PANEL_W - 30) / 2;
        int lx = left + 10;
        int rx = left + 20 + colW;

        int y = top + 22;
        sellButton = new GuiButton(ID_SELL, lx, y, colW, 20, "");
        breaksButton = new GuiButton(ID_BREAKS, rx, y, colW, 20, "");
        buttonList.add(sellButton);
        buttonList.add(breaksButton);

        y += 24;
        petButton = new GuiButton(ID_PET, lx, y, colW, 20, "");
        humanButton = new GuiButton(ID_HUMAN, rx, y, colW, 20, "");
        buttonList.add(petButton);
        buttonList.add(humanButton);

        int fullW = PANEL_W - 20;

        y += 26;
        sellInterval = RangeSlider.single(lx, y, fullW, "Sell interval", "sec",
                1, 900, cfg.sellIntervalSeconds);
        sliders.add(sellInterval);

        y += 26;
        runTime = RangeSlider.single(lx, y, fullW, "Run time", "min",
                1, 720, cfg.runTimeMinutes);
        sliders.add(runTime);

        y += 26;
        breakEvery = new RangeSlider(lx, y, fullW, "Break every", "min",
                5, 240, cfg.breakEveryMinMinutes, cfg.breakEveryMaxMinutes, true);
        sliders.add(breakEvery);

        y += 26;
        breakLength = new RangeSlider(lx, y, fullW, "Break length", "min",
                1, 120, cfg.breakLengthMinMinutes, cfg.breakLengthMaxMinutes, true);
        sliders.add(breakLength);

        y += 26;
        petSlot = RangeSlider.single(lx, y, colW, "Pet slot", "", 1, 9, cfg.petSlot);
        pickSlot = RangeSlider.single(rx, y, colW, "Pickaxe slot", "", 1, 9, cfg.pickaxeSlot);
        sliders.add(petSlot);
        sliders.add(pickSlot);

        y += 30;
        rebindButton = new GuiButton(ID_REBIND, lx, y, colW, 20, "");
        toggleButton = new GuiButton(ID_TOGGLE, rx, y, colW, 20, "");
        buttonList.add(rebindButton);
        buttonList.add(toggleButton);

        y += 24;
        buttonList.add(new GuiButton(ID_DONE, left + (PANEL_W - 100) / 2, y, 100, 20, "Save & Close"));

        refreshLabels();
    }

    private void refreshLabels() {
        sellButton.displayString = "/sell: " + onOff(cfg.sellEnabled);
        breaksButton.displayString = "Breaks: " + onOff(cfg.breaksEnabled);
        petButton.displayString = "Pet: " + onOff(cfg.petEnabled);
        humanButton.displayString = "Humanising: " + onOff(cfg.humanizeExtra);
        rebindButton.displayString = awaitingKey
                ? "Press a key..."
                : "Start/Stop: " + keyName(cfg.toggleKey);
        toggleButton.displayString = BotController.get().isRunning() ? "Stop bot" : "Start bot";
    }

    private static String onOff(boolean b) {
        return b ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
    }

    private static String keyName(int code) {
        String n = Keyboard.getKeyName(code);
        return n == null ? "?" : n;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_SELL:
                cfg.sellEnabled = !cfg.sellEnabled;
                break;
            case ID_BREAKS:
                cfg.breaksEnabled = !cfg.breaksEnabled;
                break;
            case ID_PET:
                cfg.petEnabled = !cfg.petEnabled;
                break;
            case ID_HUMAN:
                cfg.humanizeExtra = !cfg.humanizeExtra;
                break;
            case ID_REBIND:
                awaitingKey = true;
                break;
            case ID_TOGGLE:
                applyAndSave();
                BotController.get().toggle();
                mc.displayGuiScreen(null);
                return;
            case ID_DONE:
                applyAndSave();
                mc.displayGuiScreen(null);
                return;
            default:
                break;
        }
        refreshLabels();
    }

    private void applyAndSave() {
        cfg.sellIntervalSeconds = sellInterval.value();
        cfg.runTimeMinutes = runTime.value();
        cfg.breakEveryMinMinutes = breakEvery.low;
        cfg.breakEveryMaxMinutes = breakEvery.high;
        cfg.breakLengthMinMinutes = breakLength.low;
        cfg.breakLengthMaxMinutes = breakLength.high;
        cfg.petSlot = petSlot.value();
        cfg.pickaxeSlot = pickSlot.value();
        cfg.save();
    }

    @Override
    public void onGuiClosed() {
        applyAndSave();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (awaitingKey) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                rebindNote = "";
            } else if (keyCode <= 0 || Keyboard.getKeyName(keyCode) == null) {
                // Some layouts report no usable scancode for a key (0 is "no key").
                // Binding that would silently never fire, so keep the old one.
                rebindNote = EnumChatFormatting.RED + "That key reports no scancode -- binding unchanged.";
            } else {
                cfg.toggleKey = keyCode;
                cfg.save();
                rebindNote = EnumChatFormatting.GRAY + "Bound to " + Keyboard.getKeyName(keyCode) + ".";
            }
            awaitingKey = false;
            refreshLabels();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            applyAndSave();
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        for (RangeSlider s : sliders) {
            if (s.mouseDown(mouseX, mouseY)) {
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        for (RangeSlider s : sliders) {
            s.mouseDrag(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        for (RangeSlider s : sliders) {
            s.mouseUp();
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawRect(left, top, left + PANEL_W, top + PANEL_H, 0xE0101014);
        drawRect(left, top, left + PANEL_W, top + 1, 0xFF3A6E8A);
        drawRect(left, top + PANEL_H - 1, left + PANEL_W, top + PANEL_H, 0xFF3A6E8A);
        drawRect(left, top, left + 1, top + PANEL_H, 0xFF3A6E8A);
        drawRect(left + PANEL_W - 1, top, left + PANEL_W, top + PANEL_H, 0xFF3A6E8A);

        drawCenteredString(fontRendererObj,
                EnumChatFormatting.AQUA + "Prison AutoMiner",
                left + PANEL_W / 2, top + 7, 0xFFFFFFFF);

        for (RangeSlider s : sliders) {
            s.draw(fontRendererObj, mouseX, mouseY);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        BotController bot = BotController.get();
        String status = (bot.isRunning() ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                + bot.statusLine();
        drawCenteredString(fontRendererObj, status, left + PANEL_W / 2, top + PANEL_H - 14, 0xFFAAAAAA);

        if (!rebindNote.isEmpty()) {
            drawCenteredString(fontRendererObj, rebindNote, left + PANEL_W / 2, top + PANEL_H - 26, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }
}
