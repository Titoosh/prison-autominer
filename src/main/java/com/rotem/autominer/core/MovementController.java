package com.rotem.autominer.core;

import com.rotem.autominer.util.Area;
import com.rotem.autominer.util.KeyState;
import com.rotem.autominer.util.Rand;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/**
 * Where the bot walks.
 *
 * Wandering is waypoint-based inside the 5..30 ring: pick a point, walk to it,
 * pick another. That gives movement which is random but purposeful, rather than
 * the twitchy direction-flipping you get from re-rolling a heading every tick.
 *
 * Y is never consulted for containment -- the bot is digging down, so only XZ
 * decides whether it is in the ring and in the mine.
 */
public class MovementController {

    /** How close counts as "arrived". */
    private static final double ARRIVE_DIST = 2.2D;

    private double wpX;
    private double wpZ;
    private boolean hasWaypoint;

    /** Small constant bias applied to the walking heading so paths curve. */
    private double headingBias;
    private long headingBiasUntil;

    // Stuck detection
    private double lastX, lastZ;
    private long stuckSince;
    private long unstuckUntil;
    private int unstuckMode;
    private long lastStuckFix;

    private final LookController look;

    public MovementController(LookController look) {
        this.look = look;
    }

    public void reset() {
        hasWaypoint = false;
        stuckSince = 0L;
        unstuckUntil = 0L;
        headingBiasUntil = 0L;
        lastStuckFix = 0L;
    }

    public boolean hasWaypoint() {
        return hasWaypoint;
    }

    public double getWaypointX() {
        return wpX;
    }

    public double getWaypointZ() {
        return wpZ;
    }

    /** Distance still to walk, or -1 when idle. */
    public double distanceToWaypoint() {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null || !hasWaypoint) {
            return -1.0D;
        }
        double dx = wpX - p.posX;
        double dz = wpZ - p.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Choose a new destination in the given radius band.
     *
     * Two things keep the path from looking synthetic. The hop is long (18-34
     * blocks), so the bot commits to a direction instead of re-deciding every few
     * steps -- which is both straighter and, in practice, faster across the mine.
     * And when preferForward is set, candidates are scored against a turn budget
     * that is usually gentle (5-40 degrees) and occasionally sharp, so turns vary
     * instead of landing on the same near-90 pivot every time.
     */
    public void pickWaypoint(double rMin, double rMax) {
        pickWaypoint(rMin, rMax, true);
    }

    public void pickWaypoint(double rMin, double rMax, boolean preferForward) {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            return;
        }
        // Mostly a gentle course correction; now and then a real turn.
        double turnBudget = Rand.chance(0.18D) ? Rand.range(55.0D, 130.0D) : Rand.range(5.0D, 40.0D);
        double wantTravel = Rand.range(18.0D, 34.0D);

        double bestX = Area.CENTER_X;
        double bestZ = Area.CENTER_Z;
        double bestScore = -Double.MAX_VALUE;
        boolean found = false;

        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = Rand.range(0.0D, Math.PI * 2.0D);
            double radius = Rand.bell(rMin, rMax);
            double x = Area.clampX(Area.CENTER_X + Math.cos(angle) * radius);
            double z = Area.clampZ(Area.CENTER_Z + Math.sin(angle) * radius);

            double r = Area.distToCenter(x, z);
            if (r < Area.INNER_R + 1.5D || r > Area.OUTER_R - 1.5D) {
                continue;
            }

            double dx = x - p.posX;
            double dz = z - p.posZ;
            double travel = Math.sqrt(dx * dx + dz * dz);
            if (travel < 10.0D) {
                continue;
            }

            double score = -Math.abs(travel - wantTravel) * 0.5D;

            if (preferForward) {
                float candidateYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                double turn = Math.abs(MathHelper.wrapAngleTo180_float(candidateYaw - p.rotationYaw));
                score -= Math.abs(turn - turnBudget) * 0.9D;
            }

            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestZ = z;
                found = true;
            }
        }

        if (!found) {
            // Nothing scored -- fall back to the centre of the comfortable band so
            // the bot always has somewhere legal to be walking.
            double angle = Rand.range(0.0D, Math.PI * 2.0D);
            double radius = (rMin + rMax) * 0.5D;
            bestX = Area.clampX(Area.CENTER_X + Math.cos(angle) * radius);
            bestZ = Area.clampZ(Area.CENTER_Z + Math.sin(angle) * radius);
        }

        wpX = bestX;
        wpZ = bestZ;
        hasWaypoint = true;
        rollHeadingBias();
    }

    /** Force a destination back inside the comfortable band, used by the guards. */
    public void pickReturnWaypoint() {
        // Getting back in the ring outranks keeping the turn small.
        pickWaypoint(Area.RETURN_R_MIN, Area.RETURN_R_MAX, false);
    }

    private void rollHeadingBias() {
        headingBias = Rand.range(-2.5D, 2.5D);
        headingBiasUntil = System.currentTimeMillis() + (long) Rand.range(2500.0D, 6000.0D);
    }

    /** Yaw that would face the current waypoint, plus the curvature bias. */
    public float headingToWaypoint() {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null || !hasWaypoint) {
            return 0f;
        }
        double dx = wpX - p.posX;
        double dz = wpZ - p.posZ;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

        long now = System.currentTimeMillis();
        if (now > headingBiasUntil) {
            rollHeadingBias();
        }
        return yaw + (float) headingBias;
    }

    /**
     * Hold the walk keys. Returns true if we arrived and the caller should pick a
     * new waypoint.
     */
    public boolean tickWalking(boolean allowSprint) {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            return false;
        }
        long now = System.currentTimeMillis();

        if (now < unstuckUntil) {
            tickUnstuck();
            return false;
        }
        KeyState.left(false);
        KeyState.right(false);
        KeyState.back(false);
        KeyState.jump(false);

        KeyState.forward(true);
        KeyState.sprint(allowSprint);

        double dist = distanceToWaypoint();
        return dist >= 0.0D && dist < ARRIVE_DIST;
    }

    public void stopWalking() {
        KeyState.forward(false);
        KeyState.back(false);
        KeyState.left(false);
        KeyState.right(false);
        KeyState.sprint(false);
    }

    /**
     * Same coordinates for more than three seconds means something is in the way.
     * Returns true when a new direction is wanted.
     */
    public boolean checkStuck() {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        double moved = Math.abs(p.posX - lastX) + Math.abs(p.posZ - lastZ);

        if (moved > 0.12D) {
            lastX = p.posX;
            lastZ = p.posZ;
            stuckSince = now;
            return false;
        }
        if (stuckSince == 0L) {
            stuckSince = now;
            return false;
        }
        if (now - stuckSince < 3000L) {
            return false;
        }
        if (now - lastStuckFix < 1200L) {
            return false;
        }

        // Stuck. Strafe-and-jump out, then hand back a request for a new heading.
        lastStuckFix = now;
        stuckSince = now;
        unstuckMode = Rand.i(3);
        unstuckUntil = now + (long) Rand.range(420.0D, 900.0D);
        return true;
    }

    private void tickUnstuck() {
        KeyState.forward(true);
        KeyState.sprint(false);
        switch (unstuckMode) {
            case 0:
                KeyState.left(true);
                KeyState.right(false);
                break;
            case 1:
                KeyState.right(true);
                KeyState.left(false);
                break;
            default:
                KeyState.left(false);
                KeyState.right(false);
                KeyState.jump(true);
                break;
        }
    }

    public void clearUnstuck() {
        KeyState.jump(false);
        KeyState.left(false);
        KeyState.right(false);
        unstuckUntil = 0L;
    }
}
