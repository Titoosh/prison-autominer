package com.rotem.autominer.core;

import com.rotem.autominer.util.Area;
import com.rotem.autominer.util.KeyState;
import com.rotem.autominer.util.Rand;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;

/**
 * Mining itself: the downward pitch, holding attack, reacting to unbreakable
 * blocks, and recovering from a mine reset.
 */
public class MiningController {

    /** The band the bot mines at: looking down between 35 and 60 degrees. */
    private static final float PITCH_MIN = 35f;
    private static final float PITCH_MAX = 60f;

    /** Steeper pitch used when it decides to dig straight down past an obstacle. */
    private static final float DIG_DOWN_MIN = 78f;
    private static final float DIG_DOWN_MAX = 88f;

    private final LookController look;

    private float basePitch;
    private long rerollPitchAt;

    private long digDownUntil;
    private long obstacleCooldownUntil;

    // Mine reset detection
    private double lastY = Double.NaN;
    private long resetRecoverAt = 0L;
    private boolean awaitingResetRecover;

    public MiningController(LookController look) {
        this.look = look;
    }

    public void reset() {
        rollBasePitch();
        digDownUntil = 0L;
        obstacleCooldownUntil = 0L;
        awaitingResetRecover = false;
        resetRecoverAt = 0L;
        lastY = Double.NaN;
    }

    /** Fresh mining pitch in the 35..60 band, re-rolled every so often. */
    public void rollBasePitch() {
        basePitch = (float) Rand.range(PITCH_MIN, PITCH_MAX);
        rerollPitchAt = System.currentTimeMillis() + (long) Rand.range(12000.0D, 40000.0D);
    }

    public float currentPitch() {
        if (System.currentTimeMillis() < digDownUntil) {
            return (float) Rand.range(DIG_DOWN_MIN, DIG_DOWN_MAX);
        }
        return basePitch;
    }

    public boolean isDiggingDown() {
        return System.currentTimeMillis() < digDownUntil;
    }

    public void maybeRerollPitch() {
        if (System.currentTimeMillis() > rerollPitchAt) {
            rollBasePitch();
        }
    }

    public void holdMining(boolean down) {
        KeyState.attack(down);
    }

    /**
     * Did the server just reset the mine? The tell is being lifted back to the
     * surface: a large upward jump in Y ending at about y=79.
     */
    public boolean detectMineReset() {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            return false;
        }
        double y = p.posY;
        if (Double.isNaN(lastY)) {
            lastY = y;
            return false;
        }
        double jump = y - lastY;
        lastY = y;

        boolean liftedToSurface = jump > 2.0D && Math.abs(y - Area.SURFACE_Y) < 1.5D;
        if (liftedToSurface && !awaitingResetRecover) {
            awaitingResetRecover = true;
            // Well inside the 2s budget -- fast reaction, still not a fixed timer.
            resetRecoverAt = System.currentTimeMillis() + (long) Rand.range(110.0D, 380.0D);
            return true;
        }
        return false;
    }

    public boolean isRecoveringFromReset() {
        return awaitingResetRecover;
    }

    /**
     * After a reset the camera angle is wiped, so re-establish a random downward
     * angle once the (randomised, sub-2s) reaction delay has elapsed.
     */
    public void tickResetRecovery() {
        if (!awaitingResetRecover) {
            return;
        }
        if (System.currentTimeMillis() < resetRecoverAt) {
            // Let go while "noticing" the teleport -- a hand does not keep swinging.
            holdMining(false);
            return;
        }
        awaitingResetRecover = false;
        digDownUntil = 0L;
        rollBasePitch();
        look.setTargetQuick(look.getTargetYaw() + (float) Rand.range(-14.0D, 14.0D), basePitch);
    }

    /**
     * A beacon or sponge in front of the bot means it cannot mine forward. Decide
     * at random between digging down past it and turning away; if it is already
     * digging down and the floor is blocked too, turning is the only option.
     *
     * @return true when the caller should pick a new waypoint.
     */
    public boolean handleObstacles() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        if (p == null || mc.theWorld == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < obstacleCooldownUntil) {
            return false;
        }

        boolean lookingAtBlocker = isBlocker(lookedAtBlock());
        boolean floorBlocked = isBlocker(blockBelow());

        if (!lookingAtBlocker && !floorBlocked) {
            return false;
        }
        obstacleCooldownUntil = now + (long) Rand.range(900.0D, 2200.0D);

        if (lookingAtBlocker && !floorBlocked && Rand.chance(0.5D)) {
            // Option A: go under it.
            digDownUntil = now + (long) Rand.range(1800.0D, 4200.0D);
            look.setTarget(look.getTargetYaw() + (float) Rand.range(-6.0D, 6.0D), currentPitch());
            return false;
        }

        // Option B (and the only option when the floor is blocked too): turn away.
        digDownUntil = 0L;
        return true;
    }

    private Block lookedAtBlock() {
        Minecraft mc = Minecraft.getMinecraft();
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        BlockPos pos = mop.getBlockPos();
        if (pos == null) {
            return null;
        }
        return mc.theWorld.getBlockState(pos).getBlock();
    }

    private Block blockBelow() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        if (p == null) {
            return null;
        }
        BlockPos pos = new BlockPos(p.posX, p.posY - 0.6D, p.posZ);
        return mc.theWorld.getBlockState(pos).getBlock();
    }

    private static boolean isBlocker(Block b) {
        return b == Blocks.beacon || b == Blocks.sponge || b == Blocks.bedrock || b == Blocks.barrier;
    }
}
