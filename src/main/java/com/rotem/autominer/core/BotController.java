package com.rotem.autominer.core;

import com.rotem.autominer.config.MinerConfig;
import com.rotem.autominer.util.Area;
import com.rotem.autominer.util.KeyState;
import com.rotem.autominer.util.Rand;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;

/**
 * The state machine. Everything else is a component this drives.
 *
 * Phases: PET (activate the pet, once) -> MINING (wander + dig, with micro-pauses)
 * with excursions into RETURNING (pushed back into the ring) and BREAK, and any of
 * it interruptible by the confirm window or by the stop key.
 */
public class BotController {

    public enum Phase {STOPPED, PET, MINING, RETURNING, BREAK}

    private static BotController instance;

    private final LookController look = new LookController();
    private final MovementController move = new MovementController(look);
    private final MiningController mine = new MiningController(look);
    private final FailsafeHandler failsafe = new FailsafeHandler();
    private final SellHandler sell = new SellHandler();

    private boolean running;
    private Phase phase = Phase.STOPPED;
    private String lastStopReason = "";

    private long sessionEndsAt;
    private long startedAt;

    // Breaks
    private long nextBreakAt;
    private long breakEndsAt;

    // Pet activation
    private long petStageAt;
    private int petStage;

    // Humanising
    private long microPauseUntil;
    private long nextMicroPauseAt;
    private long sprintDropUntil;
    private long nextSprintDropAt;
    private long lastAimUpdate;
    private long nextWiggleAt;
    private long wiggleHoldUntil;

    public static BotController get() {
        if (instance == null) {
            instance = new BotController();
        }
        return instance;
    }

    public boolean isRunning() {
        return running;
    }

    public Phase getPhase() {
        return phase;
    }

    public LookController getLook() {
        return look;
    }

    public FailsafeHandler getFailsafe() {
        return failsafe;
    }

    public SellHandler getSell() {
        return sell;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long millisRemaining() {
        return running ? Math.max(0L, sessionEndsAt - System.currentTimeMillis()) : 0L;
    }

    public long millisUntilBreak() {
        MinerConfig cfg = MinerConfig.get();
        if (!running || !cfg.breaksEnabled || phase == Phase.BREAK) {
            return -1L;
        }
        return Math.max(0L, nextBreakAt - System.currentTimeMillis());
    }

    public long millisUntilBreakEnds() {
        return phase == Phase.BREAK ? Math.max(0L, breakEndsAt - System.currentTimeMillis()) : -1L;
    }

    // ------------------------------------------------------------------ start/stop

    public void toggle() {
        if (running) {
            stop("toggled off");
        } else {
            start();
        }
    }

    public void start() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        if (p == null || mc.theWorld == null) {
            return;
        }
        MinerConfig cfg = MinerConfig.get();
        cfg.sanitise();

        // Start gate is XZ only -- whatever depth you are already dug down to is
        // fine, the bot just carries on from there.
        if (!Area.insideMine(p.posX, p.posZ)) {
            chat(EnumChatFormatting.RED + "Outside the mine rectangle -- refusing to start. "
                    + Area.describe(p.posX, p.posY, p.posZ));
            return;
        }

        running = true;
        startedAt = System.currentTimeMillis();
        sessionEndsAt = startedAt + (long) cfg.runTimeMinutes * 60000L;
        lastStopReason = "";

        look.syncToPlayer();
        move.reset();
        mine.reset();
        failsafe.reset();
        sell.reset();

        scheduleNextBreak();
        scheduleMicroPause();
        scheduleSprintDrop();
        scheduleWiggle();

        if (cfg.petEnabled) {
            phase = Phase.PET;
            petStage = 0;
            petStageAt = System.currentTimeMillis() + (long) Rand.range(120.0D, 400.0D);
        } else {
            beginMining();
        }
        chat(EnumChatFormatting.GREEN + "Started " + EnumChatFormatting.GRAY
                + "(" + cfg.runTimeMinutes + " min"
                + (cfg.breaksEnabled ? ", breaks on" : "") + ")");
    }

    public void stop(String reason) {
        if (!running && phase == Phase.STOPPED) {
            return;
        }
        running = false;
        phase = Phase.STOPPED;
        lastStopReason = reason;
        move.clearUnstuck();
        move.stopWalking();
        KeyState.releaseAll();
        chat(EnumChatFormatting.RED + "Stopped " + EnumChatFormatting.GRAY + "(" + reason + ")");
    }

    private void beginMining() {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        mine.rollBasePitch();

        // Only the XZ radius decides where mining can begin; depth is ignored. If we
        // are starting off the ring, walk in first rather than mining for a tick and
        // being corrected by the ring guard.
        if (p != null && !Area.insideRing(p.posX, p.posZ)) {
            double r = Area.distToCenter(p.posX, p.posZ);
            phase = Phase.RETURNING;
            move.pickReturnWaypoint();
            aimAtWaypoint(true);
            chat(EnumChatFormatting.YELLOW
                    + String.format("Starting off the ring (r=%.1f) -- walking in first.", r));
            return;
        }
        phase = Phase.MINING;
        move.pickWaypoint(Area.WANDER_R_MIN, Area.WANDER_R_MAX);
        aimAtWaypoint(true);
    }

    // ------------------------------------------------------------------ main loop

    /** Client tick. */
    public void onTick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;

        if (!running) {
            return;
        }
        if (p == null || mc.theWorld == null) {
            stop("no player");
            return;
        }

        // The confirm window outranks everything except the stop key.
        failsafe.tick();
        if (failsafe.isBusy()) {
            return;
        }

        // Hard bound: outside the mine rectangle is an immediate abort.
        if (!Area.insideMine(p.posX, p.posZ)) {
            stop("left the mine area at " + Area.describe(p.posX, p.posY, p.posZ));
            return;
        }

        if (System.currentTimeMillis() >= sessionEndsAt) {
            stop("run time finished");
            return;
        }

        if (phase == Phase.PET) {
            tickPet();
            return;
        }

        MinerConfig cfg = MinerConfig.get();

        if (phase == Phase.BREAK) {
            tickBreak();
            return;
        }
        if (cfg.breaksEnabled && System.currentTimeMillis() >= nextBreakAt) {
            beginBreak();
            return;
        }

        // A mine reset teleports us to the surface and wipes the camera angle.
        if (mine.detectMineReset()) {
            move.pickWaypoint(Area.WANDER_R_MIN, Area.WANDER_R_MAX);
        }
        if (mine.isRecoveringFromReset()) {
            move.stopWalking();
            mine.tickResetRecovery();
            return;
        }

        sell.tick();
        if (sell.isBusy()) {
            move.stopWalking();
            return;
        }

        tickRingGuard(p);
        tickMining(p);
    }

    /** Render tick -- camera only, so it moves at framerate rather than in 20 steps. */
    public void onRenderTick() {
        if (!running || failsafe.isBusy()) {
            return;
        }
        look.update();
    }

    // ------------------------------------------------------------------ phases

    private void tickPet() {
        MinerConfig cfg = MinerConfig.get();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < petStageAt) {
            return;
        }

        switch (petStage) {
            case 0: // select the pet slot
                selectHotbar(cfg.petSlot - 1);
                petStage = 1;
                petStageAt = now + (long) Rand.range(180.0D, 420.0D);
                break;
            case 1: // right click it
                KeyState.use(true);
                petStage = 2;
                petStageAt = now + (long) Rand.range(90.0D, 180.0D);
                break;
            case 2: // release
                KeyState.use(false);
                petStage = 3;
                petStageAt = now + (long) Rand.range(350.0D, 900.0D);
                break;
            case 3: // back to the pickaxe and go
                selectHotbar(cfg.pickaxeSlot - 1);
                petStage = 4;
                petStageAt = now + (long) Rand.range(200.0D, 500.0D);
                break;
            default:
                beginMining();
                break;
        }
    }

    private void tickBreak() {
        move.stopWalking();
        mine.holdMining(false);

        if (System.currentTimeMillis() >= breakEndsAt) {
            scheduleNextBreak();
            chat(EnumChatFormatting.GREEN + "Break over, back to it.");
            beginMining();
            return;
        }
        // Idle, but not frozen: an occasional slow look-around.
        if (Rand.chance(0.0025D)) {
            look.nudge((float) Rand.range(-40.0D, 40.0D), (float) Rand.range(-15.0D, 10.0D));
        }
    }

    private void beginBreak() {
        MinerConfig cfg = MinerConfig.get();
        int minutes = Rand.rangeI(cfg.breakLengthMinMinutes, cfg.breakLengthMaxMinutes);
        // Jitter the seconds too, so a break is never a whole number of minutes.
        long ms = (long) minutes * 60000L + (long) Rand.range(-25000.0D, 25000.0D);
        breakEndsAt = System.currentTimeMillis() + Math.max(30000L, ms);
        phase = Phase.BREAK;
        move.stopWalking();
        KeyState.releaseAll();
        chat(EnumChatFormatting.YELLOW + "Taking a break for ~" + minutes + " min.");
    }

    private void scheduleNextBreak() {
        MinerConfig cfg = MinerConfig.get();
        int minutes = Rand.rangeI(cfg.breakEveryMinMinutes, cfg.breakEveryMaxMinutes);
        long ms = (long) minutes * 60000L + (long) Rand.range(-40000.0D, 40000.0D);
        nextBreakAt = System.currentTimeMillis() + Math.max(60000L, ms);
    }

    /**
     * Still inside the mine but outside the 5..30 ring: walk (mining through
     * anything in the way -- attack stays held) back to the 10..25 band.
     */
    private void tickRingGuard(EntityPlayer p) {
        double r = Area.distToCenter(p.posX, p.posZ);
        boolean outOfRing = r > Area.OUTER_R || r < Area.INNER_R;

        if (outOfRing && phase != Phase.RETURNING) {
            phase = Phase.RETURNING;
            move.pickReturnWaypoint();
            aimAtWaypoint(true);
            chat(EnumChatFormatting.YELLOW + String.format("Out of the ring (r=%.1f) -- heading back.", r));
        } else if (phase == Phase.RETURNING && r >= Area.RETURN_R_MIN && r <= Area.RETURN_R_MAX) {
            phase = Phase.MINING;
            move.pickWaypoint(Area.WANDER_R_MIN, Area.WANDER_R_MAX);
            aimAtWaypoint(true);
        }
    }

    private void tickMining(EntityPlayer p) {
        MinerConfig cfg = MinerConfig.get();
        long now = System.currentTimeMillis();

        // Micro-pause: a second or two of nothing, like a glance away from the screen.
        if (cfg.humanizeExtra) {
            if (now < microPauseUntil) {
                move.stopWalking();
                mine.holdMining(false);
                if (Rand.chance(0.02D)) {
                    look.nudge((float) Rand.range(-9.0D, 9.0D), (float) Rand.range(-5.0D, 5.0D));
                }
                return;
            }
            if (now >= nextMicroPauseAt) {
                microPauseUntil = now + (long) Rand.range(450.0D, 1500.0D);
                scheduleMicroPause();
                return;
            }
        }

        if (!move.hasWaypoint()) {
            move.pickWaypoint(Area.WANDER_R_MIN, Area.WANDER_R_MAX);
            aimAtWaypoint(true);
        }

        // Unbreakable block in the way: dig under it or turn away.
        if (mine.handleObstacles()) {
            move.pickWaypoint(Area.WANDER_R_MIN, Area.WANDER_R_MAX);
            aimAtWaypoint(true);
        }

        // Three seconds on identical coordinates: change direction.
        if (move.checkStuck()) {
            move.pickWaypoint(Area.WANDER_R_MIN, Area.WANDER_R_MAX);
            aimAtWaypoint(true);
        }

        mine.maybeRerollPitch();
        updateAim();

        boolean allowSprint = true;
        if (cfg.humanizeExtra) {
            if (now < sprintDropUntil) {
                allowSprint = false;
            } else if (now >= nextSprintDropAt) {
                sprintDropUntil = now + (long) Rand.range(350.0D, 1100.0D);
                scheduleSprintDrop();
                allowSprint = false;
            }
        }

        boolean arrived = move.tickWalking(allowSprint);
        mine.holdMining(true);

        if (arrived) {
            move.pickWaypoint(Area.WANDER_R_MIN, Area.WANDER_R_MAX);
            aimAtWaypoint(false);
        }
    }

    // ------------------------------------------------------------------ aiming

    private void aimAtWaypoint(boolean immediate) {
        look.setTarget(move.headingToWaypoint(), mine.currentPitch());
        lastAimUpdate = System.currentTimeMillis()
                + (immediate ? 0L : (long) Rand.range(0.0D, 400.0D));
    }

    /**
     * Keep the camera pointed roughly along the walk, correcting only when the
     * heading has drifted or enough time has passed. Re-targeting every tick would
     * produce exactly the constant twitching that reads as automated.
     */
    private void updateAim() {
        long now = System.currentTimeMillis();
        float desired = move.headingToWaypoint();
        float diff = Math.abs(MathHelper.wrapAngleTo180_float(desired - look.getTargetYaw()));
        float pitch = mine.currentPitch();
        boolean pitchOff = Math.abs(pitch - look.getTargetPitch()) > 3.0f;

        boolean holding = now < wiggleHoldUntil;

        // While a wiggle is being held, only a genuinely large error gets corrected.
        // Once the hold lapses the ordinary correction pulls the view back to the
        // walk, which is what makes the wiggle read as a glance and a return rather
        // than as a twitch that gets yanked straight back.
        if (diff > (holding ? 48.0f : 9.0f) || (pitchOff && !holding)) {
            look.setTarget(desired, pitch);
            lastAimUpdate = now;
            return;
        }
        if (!holding && now >= nextWiggleAt) {
            wiggle();
            lastAimUpdate = now;
        }
    }

    /**
     * A mouse wiggle, drawn from several shapes so the movement is not one
     * recognisable gesture repeated. Each shape sets its own hold time, during which
     * updateAim leaves the view alone.
     */
    private void wiggle() {
        long now = System.currentTimeMillis();
        double roll = MinerConfig.get().humanizeExtra ? Rand.d() : Rand.range(0.0D, 0.34D);
        double hold;

        if (roll < 0.34D) {
            // Small yaw tick -- the commonest, barely visible.
            look.nudge((float) (Rand.sign() * Rand.range(1.0D, 3.2D)),
                    (float) Rand.range(-0.7D, 0.7D));
            hold = Rand.range(250.0D, 650.0D);
        } else if (roll < 0.58D) {
            // Pitch bob: shifts how far down it is looking.
            look.nudge((float) Rand.range(-0.9D, 0.9D),
                    (float) (Rand.sign() * Rand.range(1.5D, 4.5D)));
            hold = Rand.range(300.0D, 850.0D);
        } else if (roll < 0.78D) {
            // Diagonal drift -- the two axes move together, as a real hand does.
            double dir = Rand.sign();
            look.nudge((float) (dir * Rand.range(2.0D, 6.0D)),
                    (float) (-dir * Rand.range(1.0D, 3.5D)));
            hold = Rand.range(400.0D, 1200.0D);
        } else if (roll < 0.93D) {
            // A wider, slower sweep to one side.
            look.nudge((float) (Rand.sign() * Rand.range(7.0D, 17.0D)),
                    (float) Rand.range(-3.0D, 3.0D));
            hold = Rand.range(700.0D, 1900.0D);
        } else {
            // Rare: a proper glance off the rock, part way back up.
            look.nudge((float) (Rand.sign() * Rand.range(18.0D, 36.0D)),
                    (float) Rand.range(-15.0D, -4.0D));
            hold = Rand.range(500.0D, 1400.0D);
        }

        wiggleHoldUntil = now + (long) hold;
        scheduleWiggle();
    }

    private void scheduleWiggle() {
        nextWiggleAt = System.currentTimeMillis() + (long) Rand.range(900.0D, 3200.0D);
    }

    private void scheduleMicroPause() {
        nextMicroPauseAt = System.currentTimeMillis() + (long) Rand.range(50000.0D, 180000.0D);
    }

    private void scheduleSprintDrop() {
        nextSprintDropAt = System.currentTimeMillis() + (long) Rand.range(45000.0D, 160000.0D);
    }

    // ------------------------------------------------------------------ helpers

    private void selectHotbar(int index) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        if (p == null) {
            return;
        }
        index = Math.max(0, Math.min(8, index));
        p.inventory.currentItem = index;
        if (mc.thePlayer.sendQueue != null) {
            // Setting the field alone does not tell the server; this does.
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(index));
        }
    }

    public void chat(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.DARK_AQUA + "[AutoMiner] " + EnumChatFormatting.RESET + msg));
        }
    }

    public String statusLine() {
        if (!running) {
            return "Stopped" + (lastStopReason.isEmpty() ? "" : " -- " + lastStopReason);
        }
        StringBuilder sb = new StringBuilder(phase.name());
        sb.append("  ").append(formatDuration(millisRemaining())).append(" left");
        long br = millisUntilBreak();
        if (phase == Phase.BREAK) {
            sb.append("  break ends in ").append(formatDuration(millisUntilBreakEnds()));
        } else if (br >= 0L) {
            sb.append("  break in ").append(formatDuration(br));
        }
        return sb.toString();
    }

    public static String formatDuration(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long m = total / 60L;
        long s = total % 60L;
        return m + "m" + (s < 10 ? "0" : "") + s + "s";
    }
}
