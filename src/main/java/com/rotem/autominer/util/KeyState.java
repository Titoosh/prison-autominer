package com.rotem.autominer.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

/**
 * Drives vanilla keybinds by their internal pressed-state rather than by faking
 * OS input. Two consequences worth knowing:
 *
 *  - it keeps working while the Minecraft window is unfocused, which is what
 *    makes "run it in the background" possible at all;
 *  - it goes through the same code path vanilla uses, so mining, sprinting and
 *    walking behave exactly as they do under a real hand.
 */
public final class KeyState {

    private KeyState() {
    }

    private static GameSettings settings() {
        return Minecraft.getMinecraft().gameSettings;
    }

    public static void set(KeyBinding kb, boolean down) {
        if (kb == null) {
            return;
        }
        int code = kb.getKeyCode();
        if (code == 0) {
            return;
        }
        boolean was = kb.isKeyDown();
        if (down) {
            KeyBinding.setKeyBindState(code, true);
            if (!was) {
                // onTick() is what queues the "was just pressed" edge vanilla looks for.
                KeyBinding.onTick(code);
            }
        } else if (was) {
            KeyBinding.setKeyBindState(code, false);
        }
    }

    public static void attack(boolean down) {
        set(settings().keyBindAttack, down);
    }

    public static void use(boolean down) {
        set(settings().keyBindUseItem, down);
    }

    public static void forward(boolean down) {
        set(settings().keyBindForward, down);
    }

    public static void back(boolean down) {
        set(settings().keyBindBack, down);
    }

    public static void left(boolean down) {
        set(settings().keyBindLeft, down);
    }

    public static void right(boolean down) {
        set(settings().keyBindRight, down);
    }

    public static void sprint(boolean down) {
        set(settings().keyBindSprint, down);
    }

    public static void jump(boolean down) {
        set(settings().keyBindJump, down);
    }

    /** Let go of everything we might be holding. Always safe to call. */
    public static void releaseAll() {
        attack(false);
        use(false);
        forward(false);
        back(false);
        left(false);
        right(false);
        sprint(false);
        jump(false);
    }
}
