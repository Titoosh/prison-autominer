package com.rotem.autominer.util;

/**
 * The fixed geometry of the mine.
 *
 * Centre and corners are block coordinates; we add 0.5 to get the middle of the
 * block, which is what the player's posX/posZ actually measure against.
 *
 * Y is deliberately absent from every containment test -- the bot digs downward,
 * so its Y is meaningless for "am I in bounds". Y only matters for spotting the
 * mine reset, which is handled in MiningController.
 */
public final class Area {

    public static final double CENTER_X = 383576.5D;
    public static final double CENTER_Z = 225.5D;

    /** Surface level the server teleports us back to on a mine reset. */
    public static final double SURFACE_Y = 79.0D;

    /** Hard ring the bot must stay inside of. */
    public static final double INNER_R = 5.0D;
    public static final double OUTER_R = 30.0D;

    /** Where we aim for when we have to come back into the ring. */
    public static final double RETURN_R_MIN = 10.0D;
    public static final double RETURN_R_MAX = 25.0D;

    /** Comfortable band for ordinary wandering -- kept off both walls of the ring. */
    public static final double WANDER_R_MIN = 8.0D;
    public static final double WANDER_R_MAX = 27.0D;

    // The four corners: (383528,272) (383528,179) (383621,272) (383621,179).
    // Corner blocks are inclusive, so the walkable box runs to max+1.
    private static final double MINE_X_LO = 383528.0D;
    private static final double MINE_X_HI = 383622.0D;
    private static final double MINE_Z_LO = 179.0D;
    private static final double MINE_Z_HI = 273.0D;

    private Area() {
    }

    public static double distToCenter(double x, double z) {
        double dx = x - CENTER_X;
        double dz = z - CENTER_Z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** True while the bot is somewhere inside the mine's rectangle. */
    public static boolean insideMine(double x, double z) {
        return x >= MINE_X_LO && x <= MINE_X_HI && z >= MINE_Z_LO && z <= MINE_Z_HI;
    }

    /** True while the bot is in the legal 5..30 ring. */
    public static boolean insideRing(double x, double z) {
        double r = distToCenter(x, z);
        return r >= INNER_R && r <= OUTER_R;
    }

    /** Clamp a candidate point so a waypoint can never sit outside the rectangle. */
    public static double clampX(double x) {
        return Math.max(MINE_X_LO + 1.5D, Math.min(MINE_X_HI - 1.5D, x));
    }

    public static double clampZ(double z) {
        return Math.max(MINE_Z_LO + 1.5D, Math.min(MINE_Z_HI - 1.5D, z));
    }

    public static String describe(double x, double y, double z) {
        return String.format("x=%.1f y=%.1f z=%.1f r=%.1f", x, y, z, distToCenter(x, z));
    }
}
