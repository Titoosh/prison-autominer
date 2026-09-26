package com.rotem.autominer.util;

import java.util.Random;

/**
 * Small randomness helpers. Everything the bot does in time or space goes through
 * here so that no two actions ever land on the same interval twice -- fixed
 * periods are the single most machine-looking thing a macro can do.
 */
public final class Rand {

    private static final Random R = new Random();

    private Rand() {
    }

    public static double d() {
        return R.nextDouble();
    }

    public static int i(int boundExclusive) {
        return boundExclusive <= 0 ? 0 : R.nextInt(boundExclusive);
    }

    public static boolean chance(double p) {
        return R.nextDouble() < p;
    }

    public static double range(double lo, double hi) {
        return lo + R.nextDouble() * (hi - lo);
    }

    public static int rangeI(int lo, int hi) {
        if (hi <= lo) {
            return lo;
        }
        return lo + R.nextInt(hi - lo + 1);
    }

    /** Normal-ish draw, clamped so a long tail never produces something silly. */
    public static double gauss(double mean, double sd, double lo, double hi) {
        double v = mean + R.nextGaussian() * sd;
        return Math.max(lo, Math.min(hi, v));
    }

    /** Centre of a range with a bell shape rather than a flat one. */
    public static double bell(double lo, double hi) {
        double mean = (lo + hi) * 0.5D;
        return gauss(mean, (hi - lo) / 5.0D, lo, hi);
    }

    /** base millis +/- pct, e.g. jitter(30000, 0.18) -> ~24.6s..35.4s. */
    public static long jitter(long baseMillis, double pct) {
        double f = 1.0D + (R.nextDouble() * 2.0D - 1.0D) * pct;
        return Math.max(1L, (long) (baseMillis * f));
    }

    public static double sign() {
        return R.nextBoolean() ? 1.0D : -1.0D;
    }
}
