package com.rotem.autominer.core;

import com.rotem.autominer.util.KeyState;
import com.rotem.autominer.util.Rand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

/**
 * The confirm window.
 *
 * A container full of "do not click" panes with one real item somewhere in it.
 * Sequence: notice it, let go of everything, pause about a second, click the one
 * item that is not a pane, pause about two seconds, carry on.
 *
 * The pause either side is not only in the spec -- it is also what makes the
 * click look like a person reading the window rather than a handler firing on the
 * open packet.
 */
public class FailsafeHandler {

    private enum State {IDLE, WAITING, COOLDOWN}

    private State state = State.IDLE;
    private long actAt;
    private long resumeAt;
    private int lastWindowId = -1;
    private int clicksThisSession;

    public void reset() {
        state = State.IDLE;
        lastWindowId = -1;
    }

    public int getClickCount() {
        return clicksThisSession;
    }

    /** True while the handler owns the bot -- mining must stay paused. */
    public boolean isBusy() {
        return state != State.IDLE;
    }

    public boolean isWindowOpen() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.currentScreen instanceof GuiChest;
    }

    /**
     * Run every client tick, including while the window is up (ClientTickEvent
     * still fires with a GUI open, which is what lets this work at all).
     */
    public void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.currentTimeMillis();

        switch (state) {
            case IDLE:
                if (isWindowOpen() && mc.thePlayer != null) {
                    int wid = mc.thePlayer.openContainer != null ? mc.thePlayer.openContainer.windowId : -1;
                    if (wid == lastWindowId) {
                        return; // already dealt with this one
                    }
                    lastWindowId = wid;
                    // Drop everything: no swinging or walking while the window is up.
                    KeyState.releaseAll();
                    actAt = now + (long) Rand.range(850.0D, 1500.0D);
                    state = State.WAITING;
                }
                break;

            case WAITING:
                if (!isWindowOpen()) {
                    // Closed itself before we got there.
                    state = State.COOLDOWN;
                    resumeAt = now + (long) Rand.range(1700.0D, 2600.0D);
                    return;
                }
                if (now >= actAt) {
                    clickTheRealItem();
                    clicksThisSession++;
                    state = State.COOLDOWN;
                    resumeAt = now + (long) Rand.range(1800.0D, 2700.0D);
                }
                break;

            case COOLDOWN:
                if (now >= resumeAt) {
                    if (isWindowOpen() && mc.thePlayer != null) {
                        // Server left it open; close it ourselves so mining can resume.
                        mc.thePlayer.closeScreen();
                    }
                    state = State.IDLE;
                }
                break;

            default:
                break;
        }
    }

    /**
     * Scan the container's own slots from index 0 and click the first stack that is
     * neither empty nor a pane. A slot whose name actually says "confirm" wins
     * outright if one is present, which guards against a future layout where some
     * other decoration sits before the real item.
     */
    private void clickTheRealItem() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.playerController == null) {
            return;
        }
        Container c = mc.thePlayer.openContainer;
        if (c == null) {
            return;
        }

        // The trailing 36 slots are always the player's own inventory.
        int chestSlots = Math.max(0, c.inventorySlots.size() - 36);

        int fallback = -1;
        int confirmSlot = -1;

        for (int i = 0; i < chestSlots; i++) {
            Slot slot = c.getSlot(i);
            if (slot == null) {
                continue;
            }
            ItemStack stack = slot.getStack();
            if (stack == null || stack.getItem() == null) {
                continue;
            }
            if (isPane(stack)) {
                continue;
            }
            if (fallback == -1) {
                fallback = i;
            }
            if (confirmSlot == -1 && nameSays(stack, "confirm")) {
                confirmSlot = i;
            }
        }

        int target = confirmSlot != -1 ? confirmSlot : fallback;
        if (target == -1) {
            return;
        }
        // mouseButton 0, mode 0 == an ordinary left click on the slot.
        mc.playerController.windowClick(c.windowId, target, 0, 0, mc.thePlayer);
    }

    private static boolean isPane(ItemStack stack) {
        Item item = stack.getItem();
        return item == Item.getItemFromBlock(Blocks.stained_glass_pane)
                || item == Item.getItemFromBlock(Blocks.glass_pane)
                || item == Item.getItemFromBlock(Blocks.stained_glass)
                || item == Item.getItemFromBlock(Blocks.glass);
    }

    private static boolean nameSays(ItemStack stack, String needle) {
        try {
            String name = stack.getDisplayName();
            if (name == null) {
                return false;
            }
            return EnumChatFormatting.getTextWithoutFormattingCodes(name)
                    .toLowerCase()
                    .contains(needle);
        } catch (Exception ignored) {
            return false;
        }
    }
}
