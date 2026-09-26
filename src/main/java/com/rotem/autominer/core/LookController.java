package com.rotem.autominer.core;

import com.rotem.autominer.util.Rand;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/**
 * The camera. This is the part that most obviously separates a hand on a mouse
 * from a script setting rotationYaw, so it is worth more than a lerp:
 *
 *  - velocity-based spring, so every turn accelerates out of rest and decelerates
 *    into the target instead of moving at a constant rate;
 *  - a per-turn speed ceiling drawn fresh each time, so no two turns take the
 *    same number of ticks;
 *  - a deliberate small overshoot on larger turns followed by a correcting
 *    micro-adjustment, which is what a real hand does and a lerp never does;
 *  - a continuous low-amplitude drift (two incommensurable sines) so the camera
 *    is never perfectly still even when it has arrived;
 *  - updated on the render tick with real elapsed time, so it is smooth at any
 *    framerate rather than stepping 20 times a second.
 */
public class LookController {

    private float targetYaw;
    private float targetPitch;

    private float yawVel;
    private float pitchVel;

    // The tremor is two out-of-step sines per axis. Its amplitudes, rates and
    // phases are re-rolled every 6-20 s, so the character of the drift keeps
    // changing instead of settling into one recognisable waveform.
    private double noiseT;
    private double phaseA = Rand.range(0, Math.PI * 2);
    private double phaseB = Rand.range(0, Math.PI * 2);
    private double freqA = 1.7D;
    private double freqB = 4.3D;
    private double ampYawA = 0.055D;
    private double ampYawB = 0.022D;
    private double ampPitchA = 0.040D;
    private double ampPitchB = 0.016D;
    private double tremorRerollAt = 0.0D;

    private long lastNano = 0L;

    /** Degrees per second ceiling for the current turn. */
    private float maxSpeed = 110f;

    /** Overshoot state: once a big turn is issued we aim slightly past it. */
    private boolean overshooting;
    private float overshootYaw;
    private float overshootPitch;

    private boolean initialised;

    public void syncToPlayer() {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            return;
        }
        targetYaw = p.rotationYaw;
        targetPitch = p.rotationPitch;
        yawVel = 0f;
        pitchVel = 0f;
        overshooting = false;
        initialised = true;
        lastNano = 0L;
    }

    public float getTargetYaw() {
        return targetYaw;
    }

    public float getTargetPitch() {
        return targetPitch;
    }

    /**
     * Point somewhere new. Large moves get an overshoot; small ones are treated as
     * micro-corrections and go straight in.
     */
    public void setTarget(float yaw, float pitch) {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            return;
        }
        if (!initialised) {
            syncToPlayer();
        }
        pitch = clampPitch(pitch);

        float dYaw = MathHelper.wrapAngleTo180_float(yaw - p.rotationYaw);
        float dPitch = pitch - p.rotationPitch;
        float magnitude = Math.abs(dYaw) + Math.abs(dPitch);

        // A fresh speed ceiling per turn: bigger sweeps get a faster hand.
        this.maxSpeed = (float) Rand.gauss(95.0D + magnitude * 2.0D, 25.0D, 55.0D, 320.0D);

        this.targetYaw = yaw;
        this.targetPitch = pitch;

        if (magnitude > 14.0D && Rand.chance(0.72D)) {
            double factor = Rand.range(0.04D, 0.13D);
            this.overshootYaw = yaw + (float) (dYaw * factor);
            this.overshootPitch = clampPitch(pitch + (float) (dPitch * factor));
            this.overshooting = true;
        } else {
            this.overshooting = false;
        }
    }

    /**
     * Snap to an angle deliberately and quickly, with no overshoot. Used after a
     * mine reset, where the point is to be back on the rock fast rather than to
     * imitate a lazy drifting hand.
     */
    public void setTargetQuick(float yaw, float pitch) {
        setTarget(yaw, pitch);
        this.overshooting = false;
        this.maxSpeed = (float) Rand.gauss(240.0D, 45.0D, 160.0D, 380.0D);
    }

    /** Small relative adjustment -- the "shifting the mouse a little" behaviour. */
    public void nudge(float dYaw, float dPitch) {
        setTarget(targetYaw + dYaw, clampPitch(targetPitch + dPitch));
    }

    public boolean isSettled() {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            return true;
        }
        return !overshooting
                && Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - p.rotationYaw)) < 1.5f
                && Math.abs(targetPitch - p.rotationPitch) < 1.5f;
    }

    /**
     * Advance the camera. Call this from the render tick so it runs at framerate;
     * calling it from the client tick as well is harmless (dt just comes out small).
     */
    public void update() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        if (p == null) {
            return;
        }
        if (!initialised) {
            syncToPlayer();
            return;
        }

        long now = System.nanoTime();
        if (lastNano == 0L) {
            lastNano = now;
            return;
        }
        double dt = (now - lastNano) / 1_000_000_000.0D;
        lastNano = now;
        // Guard against alt-tab stalls and debugger pauses producing a huge jump.
        if (dt <= 0.0D) {
            return;
        }
        dt = Math.min(dt, 0.05D);

        float aimYaw = overshooting ? overshootYaw : targetYaw;
        float aimPitch = overshooting ? overshootPitch : targetPitch;

        float dYaw = MathHelper.wrapAngleTo180_float(aimYaw - p.rotationYaw);
        float dPitch = aimPitch - p.rotationPitch;

        if (overshooting && Math.abs(dYaw) < 1.2f && Math.abs(dPitch) < 1.2f) {
            // Reached the overshoot point; now drift back to the real target.
            overshooting = false;
            maxSpeed = (float) Rand.gauss(45.0D, 12.0D, 20.0D, 80.0D);
            return;
        }

        final double stiffness = 9.0D;
        final double damping = 5.2D;

        yawVel += (float) ((dYaw * stiffness - yawVel * damping) * dt);
        pitchVel += (float) ((dPitch * stiffness - pitchVel * damping) * dt);

        yawVel = clamp(yawVel, -maxSpeed, maxSpeed);
        pitchVel = clamp(pitchVel, -maxSpeed, maxSpeed);

        // Hand tremor: never exactly still, never enough to miss a block.
        noiseT += dt;
        if (noiseT >= tremorRerollAt) {
            rerollTremor();
        }
        double tremorYaw = Math.sin(noiseT * freqA + phaseA) * ampYawA
                + Math.sin(noiseT * freqB + phaseB) * ampYawB;
        double tremorPitch = Math.sin(noiseT * freqA * 0.76D + phaseB) * ampPitchA
                + Math.sin(noiseT * freqB * 0.72D + phaseA) * ampPitchB;

        p.rotationYaw += (float) (yawVel * dt + tremorYaw);
        p.rotationPitch = clampPitch(p.rotationPitch + (float) (pitchVel * dt + tremorPitch));
    }

    /** Fresh tremor shape: new rates, new amplitudes, new phases. */
    private void rerollTremor() {
        phaseA = Rand.range(0, Math.PI * 2);
        phaseB = Rand.range(0, Math.PI * 2);
        freqA = Rand.range(1.1D, 2.6D);
        freqB = Rand.range(2.9D, 5.8D);
        // Occasionally the hand is simply steadier for a while.
        double scale = Rand.chance(0.25D) ? Rand.range(0.35D, 0.7D) : Rand.range(0.9D, 1.9D);
        ampYawA = Rand.range(0.035D, 0.085D) * scale;
        ampYawB = Rand.range(0.012D, 0.032D) * scale;
        ampPitchA = Rand.range(0.024D, 0.062D) * scale;
        ampPitchB = Rand.range(0.008D, 0.024D) * scale;
        tremorRerollAt = noiseT + Rand.range(6.0D, 20.0D);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampPitch(float pitch) {
        return clamp(pitch, -89.5f, 89.5f);
    }
}
