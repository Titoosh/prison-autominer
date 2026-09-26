package com.rotem.autominer.core;

import com.rotem.autominer.config.MinerConfig;
import com.rotem.autominer.util.KeyState;
import com.rotem.autominer.util.Rand;
import net.minecraft.client.Minecraft;

/**
 * Repeats /sell on the configured interval.
 *
 * Two small touches: the interval is jittered by up to ~15% each time rather than
 * firing on the exact second, and mining is released for a beat before the command
 * goes out -- a person stops swinging to type.
 */
public class SellHandler {

    private long nextAt;
    private long sendAt;
    private boolean pendingSend;
    private int sentThisSession;

    public void reset() {
        schedule();
        pendingSend = false;
        sentThisSession = 0;
    }

    public int getSentCount() {
        return sentThisSession;
    }

    public long millisUntilNext() {
        return Math.max(0L, nextAt - System.currentTimeMillis());
    }

    private void schedule() {
        MinerConfig cfg = MinerConfig.get();
        long base = Math.max(1L, (long) cfg.sellIntervalSeconds) * 1000L;
        // Tight intervals get proportionally less jitter, otherwise a 1s setting
        // would wander far enough to be noticeable.
        double pct = base <= 3000L ? 0.06D : 0.15D;
        nextAt = System.currentTimeMillis() + Rand.jitter(base, pct);
    }

    /** True while it is holding mining off to "type" the command. */
    public boolean isBusy() {
        return pendingSend;
    }

    public void tick() {
        MinerConfig cfg = MinerConfig.get();
        if (!cfg.sellEnabled) {
            schedule();
            pendingSend = false;
            return;
        }
        long now = System.currentTimeMillis();

        if (pendingSend) {
            if (now >= sendAt) {
                send();
                pendingSend = false;
                schedule();
            }
            return;
        }
        if (now >= nextAt) {
            // At a short interval, releasing the mouse to "type" every cycle would
            // cost more mining time than it buys in realism -- just send it.
            if (cfg.sellIntervalSeconds < 5) {
                send();
                schedule();
                return;
            }
            KeyState.attack(false);
            sendAt = now + (long) Rand.range(180.0D, 520.0D);
            pendingSend = true;
        }
    }

    private void send() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        mc.thePlayer.sendChatMessage("/sell");
        sentThisSession++;
    }
}
