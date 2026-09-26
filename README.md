# Prison AutoMiner — Forge 1.8.9 client mod

Singleplayer prison-mine automation for your own modded world. Client-side only.

## Building

The 1.8.9 toolchain cannot run on JDK 25, so the build is pinned to your JDK 17
install in `gradle.properties`:

```
org.gradle.java.home=C:/Program Files/Java/jdk-17
```

Then:

```
./gradlew build
```

The jar lands in `build/libs/PrisonAutoMiner-1.0.1.jar`. Drop it into
`.minecraft/mods` next to Forge 1.8.9 (11.15.1.2318). Minecraft 1.8.9 itself
still needs a **Java 8** runtime — that is separate from the JDK that builds it.

To run the game from the workspace instead: `./gradlew runClient`.

If you open the folder in IntelliJ, import it as a **Gradle** project (the old
`untitled.iml` is left over from the blank project and can be deleted).

## Controls

| Key | What it does |
| --- | --- |
| `K` | Open the config menu (rebindable in vanilla Controls) |
| `J` | Hard stop. Only ever stops, never starts. Works while the confirm window or inventory is open |
| configurable | Start/Stop toggle — set it in the menu, defaults to `J` |

Keys are polled raw, so the mod decides for itself when a press is aimed at the
game. Two gates:

- **stopping** works under any screen except chat and this mod's own menu, so the
  confirm window can never trap a running bot;
- **starting** works only with no screen open at all, so a key typed into chat
  cannot kick the bot off.

If a key reports no usable scancode when you try to bind it — which happens on
some non-Latin keyboard layouts — the menu says so and keeps the previous
binding, rather than storing a key that could never fire.

## The menu (`K`)

| Field | Control |
| --- | --- |
| 1 | `/sell` on/off |
| 2 | Sell interval, 1–900 s |
| 3 | Start/Stop key — click, then press the key you want |
| 4 | Run time, 1–720 min |
| 5 | Break every — **two-handle** slider, e.g. 45–80 min |
| 6 | Break length — **two-handle** slider, e.g. 10–15 min |
| 7 | Breaks on/off |
| extra | Pet on/off, pet slot, pickaxe slot, humanising on/off |

Both break fields are ranges, and a fresh value is drawn from the range every
time — plus a few seconds of jitter, so a break is never a round number of
minutes. Settings persist to `.minecraft/config/prisonautominer.json`.

`Start bot` in the menu starts it immediately and closes the menu. Status shows
in the top-left HUD while it runs.

## Area

Hard-coded in `util/Area.java`:

- centre `383576, 225` (block centre `383576.5 / 225.5`), surface `y=79`
- corners `383528/383621` × `179/272`
- legal ring: 5–30 blocks from centre; wanders in 8–27; when pushed out, returns
  to the 10–25 band
- **Y is ignored** for every containment test, because the bot digs downward. Y is
  read in exactly two places: the reset jump back to ~79, and the block under the
  feet for the sponge/beacon floor check.

## Behaviour

- **Wander** — waypoint-based: pick a point in the ring 18–34 blocks away, sprint
  to it, pick another. Long hops mean it commits to a direction instead of
  re-deciding constantly, so paths stay fairly straight.
- **Turns** — candidates are scored against a turn budget: 82% of the time that
  budget is a 5–40° course correction, 18% of the time a 55–130° turn. Turns
  still vary, but they no longer average out near a 90° pivot. The preference is
  switched off when walking back into the ring, where being in bounds wins.
- **Look** — a velocity spring, not a lerp: accelerates out of rest, decelerates
  in, with a per-turn speed ceiling drawn fresh each time, a small overshoot on
  bigger turns followed by a correcting micro-adjustment, and a continuous
  low-amplitude tremor so it is never perfectly still. Runs on the render tick
  with real elapsed time, so it is smooth at any framerate instead of stepping 20
  times a second.
- **Mining** — holds attack, pitch 35–60° down, re-rolled every 12–40 s.
- **Obstacles** — beacon/sponge/bedrock/barrier in the crosshair: coin-flip
  between digging down under it and turning away. If the floor is blocked too,
  turning is the only option left.
- **Stuck** — identical XZ for 3 s → strafe/jump out, then a new waypoint.
- **Ring guard** — outside radius 30 but still in the mine → walks back to the
  10–25 band, mining through whatever is in the way.
- **Outside the mine rectangle** → immediate stop.
- **Mine reset** — detected as a Y jump back up to ~79. Releases the mouse, waits
  0.11–0.38 s (not a fixed timer), then re-aims at a fresh 35–60° angle at
  160–380°/s with overshoot disabled. Full recovery is ~0.3–0.6 s against the 2 s
  budget.
- **Confirm window** — on any chest GUI: release everything, wait 0.85–1.5 s,
  click the first slot that is not empty and not a glass pane (a slot whose name
  actually says "confirm" wins outright), wait 1.8–2.7 s, close it if the server
  left it open, resume.
- **Pet** — on start, selects the pet slot, right-clicks, returns to the pickaxe
  slot, then begins.
- **Breaks and session** — break every *n* minutes from your range for *m*
  minutes from your range; whole session ends after the run time.
- **Starting** — the start gate is XZ only, so any depth you have already dug to
  is fine. Starting outside the 5–30 ring walks in before mining; starting
  outside the mine rectangle is refused, and chat prints your position and
  radius.

## Background operation

Keybinds are driven through `KeyBinding.setKeyBindState` rather than synthetic OS
input, which is why it keeps mining with the window unfocused — alt-tab away and
it carries on.

One real limitation: LWJGL only reports keyboard state for the focused window, so
`J` does **not** reach the mod while you are in another application. Click back
into Minecraft to stop it. Alternatively the run-time limit will stop it on its
own, which is the reason to set field 4 to something you are comfortable with
before you walk away.

## Humanising extras (the `Humanising` toggle)

Beyond the camera work above:

- **Micro-pauses** — every 50–180 s, 0.45–1.5 s of doing nothing with a small
  look drift, like glancing away from the screen.
- **Sprint dropouts** — every 45–160 s, sprint releases for 0.35–1.1 s.
- **Curved paths** — a heading bias of ±2.5° re-rolled every 2.5–6 s, so the bot
  never walks a mathematically straight line.
- **Stops swinging to type** — at intervals of 5 s or more, releases attack
  ~0.2–0.5 s before `/sell` goes out, as a hand would. Below 5 s the command
  fires without interrupting the swing, and jitter tightens to ±6%.
- **Idle look-arounds during breaks** — it stands still but does not freeze.
- **Mouse wiggle** — every 0.9–3.2 s, one of five shapes rather than a single
  repeated gesture: a small yaw tick (34%), a pitch bob that changes how far down
  it is looking (24%), a diagonal drift where both axes move together (20%), a
  wider slow sweep to one side (15%), and rarely (7%) a proper glance up off the
  rock. Each shape holds for its own duration — 0.25 s for a tick, up to 1.9 s for
  a sweep — during which the aim correction leaves the view alone, so it reads as
  a glance and a return rather than a twitch that snaps straight back.
- **Drifting tremor** — the continuous sub-degree tremor re-rolls its amplitudes,
  rates and phases every 6–20 s, and a quarter of the time settles into a steadier
  spell. The drift never has one recognisable waveform.

Everything timed goes through `Rand`, and nothing anywhere fires on a fixed
interval.

## Layout

```
AutoMiner.java              mod entry, key polling, HUD
config/MinerConfig.java     settings + JSON persistence
core/BotController.java     state machine: PET → MINING ⇄ RETURNING ⇄ BREAK
core/LookController.java    the camera spring
core/MovementController.java waypoints, stuck detection
core/MiningController.java  pitch, obstacles, mine-reset recovery
core/FailsafeHandler.java   confirm window
core/SellHandler.java       /sell
gui/ConfigScreen.java       the K menu
gui/RangeSlider.java        one- and two-handle sliders
util/Area.java              the mine geometry
util/KeyState.java          keybind state injection
util/Rand.java              all randomness
```
