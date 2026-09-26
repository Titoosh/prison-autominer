package com.rotem.autominer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;

/**
 * Everything the K menu edits, persisted to config/prisonautominer.json.
 * Ranges are stored as two ints and a concrete value is re-rolled each time it
 * is needed, so no two breaks are ever the same length.
 */
public class MinerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MinerConfig instance;

    // Field 1 + 2 -- /sell
    public boolean sellEnabled = true;
    public int sellIntervalSeconds = 120; // 1s is allowed

    // Field 3 -- start/stop key (LWJGL keycode)
    public int toggleKey = Keyboard.KEY_J;

    // Field 4 -- total session length, minutes
    public int runTimeMinutes = 120;

    // Field 7 + 5 + 6 -- breaks
    public boolean breaksEnabled = true;
    public int breakEveryMinMinutes = 45;
    public int breakEveryMaxMinutes = 80;
    public int breakLengthMinMinutes = 10;
    public int breakLengthMaxMinutes = 15;

    // Extras
    public boolean petEnabled = true;
    public int petSlot = 2;       // hotbar slot, 1-9
    public int pickaxeSlot = 1;   // hotbar slot to return to after the pet
    public boolean humanizeExtra = true; // idle micro-pauses, look-arounds, sprint dropouts

    public static MinerConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/prisonautominer.json");
    }

    private static MinerConfig load() {
        File f = file();
        if (f.isFile()) {
            Reader r = null;
            try {
                r = new FileReader(f);
                MinerConfig cfg = GSON.fromJson(r, MinerConfig.class);
                if (cfg != null) {
                    cfg.sanitise();
                    return cfg;
                }
            } catch (Exception ignored) {
                // corrupt or hand-edited file -- fall through to defaults
            } finally {
                closeQuietly(r);
            }
        }
        return new MinerConfig();
    }

    public void save() {
        sanitise();
        File f = file();
        Writer w = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            w = new FileWriter(f);
            GSON.toJson(this, w);
        } catch (Exception ignored) {
        } finally {
            closeQuietly(w);
        }
    }

    /** Keep the two ends of every range the right way round and in sane bounds. */
    public void sanitise() {
        sellIntervalSeconds = clamp(sellIntervalSeconds, 1, 1800);
        runTimeMinutes = clamp(runTimeMinutes, 1, 720);
        breakEveryMinMinutes = clamp(breakEveryMinMinutes, 5, 240);
        breakEveryMaxMinutes = clamp(breakEveryMaxMinutes, 5, 240);
        breakLengthMinMinutes = clamp(breakLengthMinMinutes, 1, 120);
        breakLengthMaxMinutes = clamp(breakLengthMaxMinutes, 1, 120);
        if (breakEveryMaxMinutes < breakEveryMinMinutes) {
            int t = breakEveryMinMinutes;
            breakEveryMinMinutes = breakEveryMaxMinutes;
            breakEveryMaxMinutes = t;
        }
        if (breakLengthMaxMinutes < breakLengthMinMinutes) {
            int t = breakLengthMinMinutes;
            breakLengthMinMinutes = breakLengthMaxMinutes;
            breakLengthMaxMinutes = t;
        }
        // A toggle key of 0 means "no key" and could never be detected; a config
        // file carrying one would leave the bot unstartable by keyboard.
        if (toggleKey <= 0) {
            toggleKey = Keyboard.KEY_J;
        }
        petSlot = clamp(petSlot, 1, 9);
        pickaxeSlot = clamp(pickaxeSlot, 1, 9);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
