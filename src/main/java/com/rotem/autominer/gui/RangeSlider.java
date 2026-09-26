package com.rotem.autominer.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * A slider with either one handle or two.
 *
 * The two-handle form is what the break fields need: "every 45-80 minutes" is a
 * range the bot draws from each time, not a single number.
 */
public class RangeSlider extends Gui {

    private static final int BAR_H = 8;
    private static final int HANDLE_W = 5;

    public final int x;
    public final int y;
    public final int width;
    public final boolean dual;
    public final int min;
    public final int max;
    private final String label;
    private final String suffix;

    public int low;
    public int high;

    private int dragging = -1;

    public RangeSlider(int x, int y, int width, String label, String suffix,
                       int min, int max, int low, int high, boolean dual) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.label = label;
        this.suffix = suffix == null ? "" : suffix;
        this.min = min;
        this.max = max;
        this.dual = dual;
        this.low = clamp(low);
        this.high = dual ? clamp(high) : this.low;
    }

    public static RangeSlider single(int x, int y, int width, String label, String suffix,
                                     int min, int max, int value) {
        return new RangeSlider(x, y, width, label, suffix, min, max, value, value, false);
    }

    public int value() {
        return low;
    }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    private int barTop() {
        return y + 11;
    }

    private int pixelFor(int value) {
        double f = (double) (value - min) / (double) Math.max(1, max - min);
        return x + (int) Math.round(f * width);
    }

    private int valueFor(int mouseX) {
        double f = (double) (mouseX - x) / (double) Math.max(1, width);
        f = Math.max(0.0D, Math.min(1.0D, f));
        return clamp(min + (int) Math.round(f * (max - min)));
    }

    public void draw(FontRenderer fr, int mouseX, int mouseY) {
        int top = barTop();

        String text = label + ": " + low + (dual ? " - " + high : "") + (suffix.isEmpty() ? "" : " " + suffix);
        fr.drawString(text, x, y, 0xFFCCCCCC);

        // Track
        drawRect(x, top, x + width, top + BAR_H, 0xFF101010);
        drawRect(x, top, x + width, top + 1, 0xFF3A3A3A);

        int pLow = pixelFor(low);
        int pHigh = dual ? pixelFor(high) : pLow;

        // Selected span
        if (dual) {
            drawRect(pLow, top + 1, pHigh, top + BAR_H - 1, 0xFF2E6E4E);
        } else {
            drawRect(x, top + 1, pLow, top + BAR_H - 1, 0xFF2E6E4E);
        }

        drawHandle(pLow, top, hovering(mouseX, mouseY, pLow, top));
        if (dual) {
            drawHandle(pHigh, top, hovering(mouseX, mouseY, pHigh, top));
        }
    }

    private void drawHandle(int px, int top, boolean hot) {
        int color = hot ? 0xFFFFFFFF : 0xFFBBBBBB;
        drawRect(px - HANDLE_W / 2, top - 2, px + HANDLE_W / 2 + 1, top + BAR_H + 2, 0xFF000000);
        drawRect(px - HANDLE_W / 2 + 1, top - 1, px + HANDLE_W / 2, top + BAR_H + 1, color);
    }

    private boolean hovering(int mouseX, int mouseY, int px, int top) {
        return mouseX >= px - 4 && mouseX <= px + 4 && mouseY >= top - 3 && mouseY <= top + BAR_H + 3;
    }

    /** @return true when this slider took the click. */
    public boolean mouseDown(int mouseX, int mouseY) {
        int top = barTop();
        if (mouseY < top - 4 || mouseY > top + BAR_H + 4) {
            return false;
        }
        if (mouseX < x - 6 || mouseX > x + width + 6) {
            return false;
        }
        if (dual) {
            // Grab whichever handle is nearer the cursor.
            int dl = Math.abs(mouseX - pixelFor(low));
            int dh = Math.abs(mouseX - pixelFor(high));
            dragging = dl <= dh ? 0 : 1;
        } else {
            dragging = 0;
        }
        mouseDrag(mouseX, mouseY);
        return true;
    }

    public void mouseDrag(int mouseX, int mouseY) {
        if (dragging < 0) {
            return;
        }
        int v = valueFor(mouseX);
        if (dragging == 0) {
            low = dual ? Math.min(v, high) : v;
        } else {
            high = Math.max(v, low);
        }
    }

    public void mouseUp() {
        dragging = -1;
    }

    public int bottom() {
        return barTop() + BAR_H;
    }
}
