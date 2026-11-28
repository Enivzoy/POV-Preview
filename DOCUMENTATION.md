# Leap Utils — beginner's guide

This mod runs inside Odin's Spirit Leap menu and draws each teammate's first-person view into their quarter of the screen, plus auto-leap helpers (Savepoint and Blood Rush).

Everything below assumes you have **Odin installed** and **Odin's Leap Menu module is turned on**, because that's what opens the menu the mod draws into.

---

## 1. What the mod does

### POV Previews

While Odin's leap menu is open ("Spirit Leap" or "Teleport to Player"), the mod renders each teammate's view into one of four quadrants:

```
+---------+---------+
|   1     |   2     |
| teammate| teammate|
|   A     |   B     |
+---------+---------+
|   3     |   4     |
| teammate| teammate|
|   C     |   D     |
+---------+---------+
```

- If a teammate is nearby and your game can see them, you see **their actual eyes** (position + look).
- If they're too far away / not loaded, you see **where the dungeon map puts them** (a guess).
- If nothing is known, the quadrant goes dark (`#141414`) — that's the "no feed" color.

This lets you pick a leap target by *looking at where they land*, not just by reading a name.

### Savepoint (auto leap)

Savepoint decides **who to leap to, when, and where** based on a role matrix you configure (the ADG preset ships seeded with common M7/F7 roles).

It opens **nothing itself** — it only clicks the leap target after *you* open the leap menu.

- If **Schizo Mode** is off, it waits a small delay after you open the menu, then leaps.
- If **Schizo Mode** is on, any keypress (WASD, clicks, etc.) in the menu triggers the leap immediately.

### Blood Rush

Leaps you to the teammate closest to the Wither door while the Blood key is still unpicked (archer/mage/healer roles only). Stops once the blood door opens, blood is done, or you're in the boss.

---

## 2. Installing

Requirements:
- Minecraft **26.3**
- Fabric Loader **0.19.5+**
- Fabric API
- **Odin 0.4.0** (`LeapMenu` must be enabled in Odin's settings)
- Fabric Language Kotlin

Drop the `pov-preview-1.0.0.jar` into your `mods/` folder and launch.

---

## 3. POV Previews settings

Open Odin's ClickGUI while in a dungeon / on Hypixel SkyBlock, find the **Leap Utils** category, and open **POV Previews**:

| Setting | Default | What it does |
|---|---|---|
| **Prerender** | on | Builds the whole dungeon + boss arena in the background while you're in a dungeon, so the leap menu only has to draw. Room geometry is kept across runs. |
| **Use shared POVs** | on | Draws teammates who share their own view (exact and smooth). Off: only from what your game sees of them, plus the map's guess out of range. |
| **Sharing FPS** | 60 | How many times a second your look goes to teammates. At the lowest it reads **off** and nothing of yours is shared. |
| **Only In Blood Rush** | off | Shows views only on blood rush (dungeon start → blood door opens). |
| **Only In Terms** | off | Shows views only in Goldor (F7/M7 phase 3: terminals, devices, levers, Goldor split). |
| **HUDs Over Views** | Mod HUDs | **None**: just the views. **Mod HUDs**: every mod's HUD over the views, but not Odin's Dungeon Map or vanilla HUD. **All**: everything including maps and vanilla HUD. Leap boxes and names always stay on top. |
| **POV resolution** | 100% | How sharp the views are. Lower is faster on weak cards; views scale up to fill their quarter. |
| **View distance** | 96 blocks | How far the views draw before fading into sky. |
| **Debug info** | off | Over the views: relay/TURN connections, routes, delays, FPS, losses, buffering. |

### Sharing your view

Your position, look, and held item are sent to other games in your run so they can draw you. Frames go peer-to-peer over **Cloudflare TURN** when available, otherwise through a relay.

The relay and room-geometry bundles are hosted by default. To run your own, make `config/leaputils/pov-server.properties`:

```
relay=wss://your-relay.example.com/pose/api/
mesh=https://your-cdn.example.com/adr/mesh/
```

Or set system properties `-Dleaputils.pov.relay=... -Dleaputils.pov.mesh=...`.

---

## 4. Savepoint — "who do I leap to?"

Savepoint is a **decision engine**, not a bot. It doesn't move you or click anything unless you open the leap menu. It answers one question: *"If I leap right now, who should it be?"*

### The matrix

The core is a grid: **stage / step / class → target**.

Example cell: `P3.S2.2 / MAGE → HEALER`
Meaning: "If you're a Mage in Goldor Section 2, step 2, leap to the Healer."

The mod reads your class from Odin, figures out which stage/step the fight is in, looks up your cell, and tells you (or clicks) the target.

### Where stage/step comes from

The mod watches Odin's dungeon map and chat to track the fight:

| Phase | What it means |
|---|---|
| **P1** | Maxor boss fight (crystal checkpoints) |
| **P2.GY / P2.OLDPY / P2.NEWPY** | Storm route (green-yellow, old purple→yellow, new purple→yellow) |
| **P3.S1–S4** | Goldor sections 1–4 (terminals, devices, levers, gate) |
| **P4** | Necron phase |
| **P5** | Wither King phase |

**Section** (the number after the dot) is Goldor's progress tracker:
- `0` = before the boss starts
- `1` = first set of terminals/levers/device
- `2` = second set
- `3` = third set
- `4` = fourth set (recore / early Necron)
- `5` = core entrance / after gate

The mod reads this from chat lines like *"[BOSS] Goldor: Who dares trespass into my domain?"* and *"X activated a terminal! (4/4)"*.

### What `P3.S2.2` means (decoded)

`P3.S2.2` = **Goldor, Section 2, Step 2**

- `P3` = Goldor phase (F7/M7 phase 3)
- `.S2` = section 2 of Goldor
- `.2` = step 2 within that section (e.g. "after the second terminal")

So `P3.S2.2 / MAGE → HEALER` means: *"In Goldor section 2, step 2, if you're Mage, leap to Healer."*

### What `s1`, `s2`, `s3` mean (steps)

Steps are **ordered leap slots inside one section** — not sub-sections, and not the same thing as Goldor's S1/S2/S3. `s1` is used first, then `s2`, then `s3`.

- Each row fires **once** per run: after a successful leap it is consumed, so it won't repeat.
- The ladder is driven by **your leaps**, not a timer. Savepoint does not know when you finish a terminal — the first unconsumed row where your class has a target is what fires when you open the menu and leap. The next menu open evaluates the next row.
- A class with `none` in a row skips that slot and moves to its next armed row. Conditions can gate a row (landmark/hold/objective/`anyAfter`).
- Consumption for Goldor stages resets when the section advances 1→2→3→4, and on floor enter or when you leave the boss.

`P3.S4` is the clearest example — it has three slots, in this order:

| Row | Intended moment | ADG default |
|---|---|---|
| `P3.S4/1` (s1) | S4 entry, right after the S3 gate | Archer → Tank/Mage, then run to terminal 1 |
| `P3.S4/2` (s2) | after your S4 terminal / levers | Archer/Healer/Tank → Mage |
| `P3.S4/3` (s3) | **recore**: after i4/levers, when the core opens | Berserk/Healer → Mage |

If all three rows target Mage, your **first three S4 leaps** go to Mage — one row per leap — not one leap. If you only want the recore, set `s3` and leave `s1/s2` as none; the first S4 menu open will then use `s3`, so in practice only open the menu at the core (or give `s3` a condition that gates it).

The recore row stays armed after the core opens: the server flips the tracker to section 5 before the leap menu is usable, so Savepoint keeps `P3.S4/3` available at section 5.

### Targets

| Target | Meaning |
|---|---|
| **Berserk / Archer / Mage / Tank / Healer** | Leap to that class |
| **Nearest** | Leap to whoever is closest to a landmark (you set the landmark) |
| **First** | Leap to the first teammate in the menu |
| **None** | Don't leap (this cell is disabled) |

### Conditions (the "if")

A cell can have a **condition** — the leap only fires if it's true:

| Cond part | Meaning |
|---|---|
| `subject: TARGET` | Check where the **target** (person you'd leap to) is |
| `subject: SELF` | Check where **you** are |
| `mode: NEAR / AT / IN` | Distance check (NEAR/IN = 8m default, AT = 2m default) |
| `landmark: name` | A named spot you captured with `/leaputils landmark <name>` |
| `x / y / z` | Exact coordinates instead of a landmark name |
| `radius` | Error margin in blocks (customizable per cond) |
| `holdMs` | How long the condition must stay true before leaping |
| `anyAfterMs` | If the condition is never met, leap anyway after this many ms |
| `onFail: WAIT / SKIP / ANY_AFTER` | What to do if condition never becomes true |

**Example:** `P2.GY.1` has cond `subject: TARGET, landmark: storm_entry, radius: 12`. This means: *"Wait until the Berserk (the target) is near the storm_entry spot, then leap."* If they never get there, it leaps anyway after 20 seconds (`anyAfterMs`).

**Example:** `subject: SELF, x: 100, y: 64, z: -200, radius: 5` means: *"Only leap once you (the initiator) are standing at (100, 64, -200) within 5 blocks."*

### Subject TARGET vs SELF — practical difference

You never leap to yourself.

- `subject: TARGET` → checks if **the person you're leaping to** is in position. Use this for handoffs: "wait until Berserk is at the levers, then I (Mage) leap to them."
- `subject: SELF` → checks if **you** are in position. Use this for "I need to be here first": "wait until I'm at the ee spot, then I leap to Healer."

### Consumed steps

Once you leap for a cell, that step is **consumed** (marked done) so it won't repeat. Consumption resets when Goldor's section advances 1→2→3→4, when you leave the boss, or on floor enter. (It does *not* reset going 4→5, so the recore step is not repeated after the core opens.)

---

## 5. Fast Leap - click to leap with rules

Fast Leap is the click-driven cousin of Savepoint. You hold a Spirit Leap / Infinite Spirit Leap
item and left click: the mod evaluates an ordered rule list against the current dungeon state and,
if one rule matches, picks a teammate, clicks them in the leap menu, and (by default) closes the
menu about 1 ms after the click is dispatched. It never waits for the teleport to land.
Savepoint still exists for the "open the menu yourself and follow the guide" workflow.

### How a rule is evaluated

1. Rules are ordered by `priority` descending; equal priorities keep their order in the list.
2. Each rule first passes its gates: trigger, location (boss/clear), floor, phase/section/stage,
   self-region and excluded-region checks. A failed gate falls through to the next rule.
3. The first rule that picks a valid target **and** passes its conditions wins. Failed rules never
   block later ones.
4. With `Collect all and pick best` ON, every matching rule is evaluated and the one with the
   lowest measured distance wins (ties keep list order). It is OFF by default, so the default is
   still first-match-wins.
5. If nothing matched and `Fallback` is set, the fallback runs: nearest teammate to a landmark,
   or the last teammate you leaped to (`LAST_LEAPED`). `LAST_LEAPED` is an explicit request to
   repeat that target, so it still works when `Skip last leaped` is ON.

Target selection never guesses a position. A rule with a landmark condition fails with
`missing landmark <name>` until you capture it.

### The rule JSON and the config override

On first run the built-in pack is used. To customise, create
`config/leaputils/fastleap.json`. It fully replaces the built-in pack when it parses and has at
least one rule:

```json
{
  "disabled": ["s4"],
  "rules": [
    {
      "id": "mage-at-acacia",
      "name": "Mage at acacia",
      "enabled": true,
      "priority": 500,
      "inBoss": true,
      "targetType": "CLASS",
      "targetClass": "MAGE",
      "conditions": [
        { "subject": "TARGET", "landmark": "acacia", "errorMinDistance": 3.0, "errorMaxDistance": 15.0 }
      ]
    }
  ]
}
```

Fields: `id`, `name`, `enabled`, `priority`, `phase` (chat quoi phase 1-5), `phaseAnyOf`,
`phaseAt` (position-derived phase), `stage`, `section` (Goldor 1-5), `inBoss`, `triggers`
(`CLICK`, `MENU_OPEN`, `KEYBIND`, `COMMAND`), `targetType`
(`NAME`, `CLASS`, `NEAREST_LANDMARK`, `FIRST_IN_MENU`, `DOOR_OPENER`), `targetClass`, `targetName`,
`landmark`, `radius`, `selfRegion`, `excludeRegions`, `conditions`. Regions can be a built-in id
(`yellow_pad`, `purple_pad`, `between_pads`, `middle_platform`, `crystal_a`, `crystal_b`, `i4_spot`)
or an inline AABB/point object.

`disabled` is the list `/leaputils fastleap toggle <id>` edits. `/leaputils fastleap list` prints
`on`/`off` per rule.

### Settings (grouped, with defaults)

**Triggers**
- Left click **ON**, only while holding leap **ON**, require sneak **OFF**, cancel on no-match **OFF**
- On menu open **OFF**, open delay **0 ms**
- Trigger debounce **250 ms**, max leaps per second **2**
- Keybind **unbound**, `Leap now` action runs the same evaluation

**Allowed contexts**
- Floors **F7 + M7**
- Locations **Boss + Clear**
- Ignore phase gates **OFF**
- Pause when GUI open **ON**, when dead **ON**, when solo or 1 teammate **ON**
- Only while holding leap **ON**, auto swap **ON**, swap back **OFF**, respect cooldown **ON**
- Door opener leap **OFF**, disable after Blood open **ON**
- Ignore self **ON**

**Target and position**
- Leap mode **By class**, case-insensitive names **ON**
- Default radius **6.0** (raise it to 8.0 to restore the pre-setting fallback), horizontal only **OFF** (3D is the default; per-condition overrides win)
- Skip dead **ON**, skip not-in-menu **ON**, skip last leaped **OFF**
- Blacklist / whitelist **empty** (comma-separated names, applied before target selection)
- Collect all and pick best **OFF**
- Fallback **NOTHING**, fallback landmark **empty**
- Unknown position always **fails**; there is no "guess" mode
- Map fallback is **not implemented** (no verified XZ source in Odin on this MC version)

**Feedback**
- Feedback **errors only**, dry run **OFF**, profile timings **OFF**
- HUD target line **OFF**, sound **OFF**
- Repeated warnings are throttled to once per rule/reason per 5 s
- Close GUI after teammate click dispatch **ON**, GUI close delay **1 ms**

### Experimental (all off by default, and inert)

These settings exist so the diagnostics can be turned on, but **packet prefire is not
implemented**. The audited blocker is the click state ID: `ServerboundContainerClickPacket` needs a
menu revision ID that Minecraft 26.3 only advances when the server sends container contents, and
`OdinAccess.autoLeapTo` needs an open screen. There is no verified way to know either value before
the menu arrives, and a guessed container id, state id or slot could leap to the wrong teammate, so
the speculative click is deliberately absent. With `Experimental leap prefire` ON the module logs
the aborted prediction and runs the normal menu path; it never sends a packet.

| Setting | Default | What it really does |
|---|---:|---|
| `Save leap ordering log` | OFF | Writes the observed `uuid -> slot` order to `leaporder.log` |
| `Leap ordering diagnostics` | OFF | One `savepoint.log` line per menu with uuid, class and slot |
| `Use local container id predictor` | OFF | Shadow counter from real menu ids for diagnostics only |
| `Save container id log` | OFF | Appends every observed server container id to `containerid.log` |
| `QueueX / 0PingX` | OFF | Flushes the queued click when the contents packet is applied |
| `QueueX: Require learned UUID order` | ON | The early flush also needs the validated uuid to slot mapping |
| `QueueX: Flush on open packet` | ON | The safe early flush; can be turned off to keep only the normal path |
| `QueueX: Prefire before client open` | OFF | Inert; no state id exists before the open packet |
| `QueueX: Debug log` | OFF | One `queuex` line per flush or skip reason |
| `Experimental leap prefire` | OFF | Gates the state machine; the click is never sent |
| `Prefire delay (client ticks)` | 1 | Delay the state machine would use before a click |
| `Require verified slot order` | ON | Refuses prediction without two agreeing menu observations |
| `Prefire fallback to normal menu` | ON | Falls back instead of ever retrying a guessed click |
| `Prefire diagnostics` | OFF | Logs prefire state transitions and latencies |

**Slot order is learned, not assumed.** `leaporder.log` records the real order seen in each menu:
`{"run-000001": {"players": {"<uuid>": ["[0]", "TANK"]}, "slotByUuid": {"<uuid>": 11}}}`. The
`[n]` value is the ordinal among the observed teammate heads and `slotByUuid` is the menu-wide slot
index (never the per-container slot, which repeats between the menu and the player inventory), kept
separate so an ordinal is never mistaken for a slot. The file is local only,
atomic (temp file then replace), rotated to numbered `leaporder.log.N.old` side archives at 1 MB,
keeping the five newest archives (an
unreadable log is moved to `leaporder.log.N.corrupt` instead of being overwritten), preserves
earlier runs,
and is never uploaded. One observation never authorises a prediction: two menus in the same run
must agree before `LeapOrderCache.validated()` is true. A new world, a new dungeon run or a changed
party fingerprint invalidates the slot mapping, so `run-000002` never inherits `run-000001`'s
slots. The uuid is read from the item's real profile component, never guessed from a name or skin.

Diagnostics are bounded: one observation per menu, not one per tick. Contents that arrive a tick
after the screen are retried quietly until the heads are present.

`Profile timings` writes both paths in the same shape, so a normal attempt and a prefire attempt
can be compared in `savepoint.log`: `path=`, `useItemToClick`, `menuResponse`, `clickLead`,
`dispatchToClose`, `result` and `total`, all measured with `System.nanoTime()`. A negative
`clickLead` means the real menu arrived before the speculative click would have been sent. A
prefire attempt is never marked successful from a local send; only an observed result counts.
Contents that arrive before the click is handed out abort the prefire with the fallback flag, so a
late click is never sent and a menu-first request is never mistaken for a confirmed one.

Hidden-GUI rendering (`Hide Spirit Leap GUI Rendering`) is **not implemented**: suppressing the
draw event is explicitly out of scope, so the menu stays visible. The first-leap-cold /
later-leaps-prefired policy is also **not implemented**, because it depends on the same unverified
click. `Require verified slot order` and `Prefire fallback to normal menu` stay ON (they are hard
gates inside a path that is itself off), so they cannot weaken anything while prefire is disabled.

### Container id prediction and QueueX / 0PingX

The server owns the container counter. In the target version the private
`ServerPlayer.nextContainerCounter()` is `containerCounter = containerCounter % 100 + 1`, so active
ids are `1..100` and wrap `100 -> 1`; id `0` is the client-side player inventory and never comes from
that counter. `ContainerIdPredictor` is a pure shadow: it never allocates an id and never sends a
packet. Every real open is authoritative and overwrites the guess, and a connection, world or player
change resets the seed.

| Confidence | Meaning |
|---|---|
| `UNKNOWN` | No real open seen since the session started |
| `SEEDED` | One real open seen, next id can be predicted |
| `PREDICTED` | One tentative next id is pending for a request |
| `CONFIRMED` | The pending prediction matched the real open packet |
| `INVALID` | A mismatch, timeout, reconnect or bad open invalidated prediction |

Observation happens on the inbound `ClientboundOpenScreenPacket`, at the head of
`ClientPacketListener.handleOpenScreen`, before any screen or render work. The click cannot be
pre-dispatched before the client open: the container id, state id and slot table do not exist
client-side until the packets arrive, so `QueueX: Prefire before client open` is deliberately inert.
The safe flush runs at the return of `ClientPacketListener.handleContainerContent`, after
`initializeContents` applied the real state id and slots, and still before the frame is rendered.
It only ever allows one click, and only when confidence is `CONFIRMED` for the same request, the
learned uuid order is valid, the container matches, the target is alive and the screen is a leap
menu. A mismatch, timeout, decline, disconnect, world change or party change cancels the pending
prediction and the normal path continues; no guessed click is ever retried.

`containerid.log` is a separate append-only JSONL file next to `leaporder.log` (that file is a
rewritten JSON document, so appending to it would corrupt it). Each line has `t`, `gen`, `id`,
`kind`, `title`, `predicted`, `match`, `inventory` and `confidence`; id `0` is recorded with
`inventory: true` and never seeds. With `Save container id log` OFF the file is never opened. At
1 MB the file is rotated to numbered `containerid.log.N.old` side archives, keeping the five newest;
rotation runs on a dedicated writer thread so the packet and render threads never block on disk.

`Profile timings` also records QueueX points with `System.nanoTime()`: `q0` item use, `q1` open
packet, `q2` contents applied, `q3` click attempt, `q4` click sent and `q5` prediction reconcile.
The server's processing time is **not observable** and is reported as `server=unobserved`; the log
therefore compares client send and inbound arrival only.

### Distance conditions

A condition with a landmark or point and `errorMinDistance`/`errorMaxDistance` uses the inclusive
`min..max` interval. The module defaults come from Savepoint's **Error / Min Distance (3.0)** and
**Error / Max Distance (15.0)**. A condition without the interval keeps the legacy
`AT`/`NEAR`/`IN` radius behaviour, so old presets load exactly as before.

### Worked examples

**Mage at acacia** (the shipped template, disabled until you capture the landmark):

1. Stand where the Mage should be and run `/leaputils landmark acacia`.
2. `/leaputils fastleap list` shows `off mage-at-acacia`; run `/leaputils fastleap toggle mage-at-acacia`.
3. Now a left click leaps to the Mage whenever they are 3.0-15.0 blocks from `acacia`.
   Without the landmark the rule fails with `missing landmark acacia (capture it with ...)`.

**Nearest to `necron_spot`:**

```json
{ "id": "necron-near", "name": "Nearest to necron spot", "priority": 400,
  "targetType": "NEAREST_LANDMARK", "landmark": "necron_spot", "radius": 8.0 }
```

`necron_spot` ships as a default landmark, so this works immediately. The nearest eligible
teammate within 8 blocks wins.

**"If I'm at yellow pad, leap the Healer":**

```json
{ "id": "yellow-healer", "name": "Yellow pad healer", "priority": 700, "phase": 2,
  "selfRegion": "yellow_pad", "targetType": "CLASS", "targetClass": "HEALER" }
```

The self-region gate is checked before target selection, so the rule only fires while you stand
in the yellow pad AABB.

### Testing a rule

`/leaputils fastleap test <ruleId>` evaluates without leaping and prints:

```
[FastLeap] distance=7.4; allowed=3.0..15.0; axes=3D; target=Mage (mage); rule=mage-at-acacia; source=teammate
```

or `no match: <ruleId>: <reason>`. Use it after capturing a landmark to confirm the interval.

### Importing a quoi config

The Savepoint planner has a **quoi import** button. Importing a `quoi.json` fills every planner
stage/step, writes the min/max error fields, and stores imported landmarks with the `quoicnfg-`
prefix so they never collide with your own captures. Importing the same file twice does not
duplicate anything.

### Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `no match: missing landmark acacia` | Capture it: `/leaputils landmark acacia`, or fix the `landmark` name in the rule |
| `position unknown` | The target entity is not loaded (too far / unloaded). Move closer |
| Nothing happens on click | Check the item is a Spirit Leap / Infinite Spirit Leap, `Left click` is ON and you have a party |
| `paused: solo or only N teammate` | Solo or one-teammate parties are paused; this is on purpose |
| Leap does not fire after the menu closes | The close is scheduled 1 ms after the click is dispatched, but it is best-effort on the client thread; measure with `Profile timings` |
| `max leaps per second reached` | Lower your click rate or raise the setting |
| Menu stays open on a miss | Intentional: a failed or refused click never closes the menu |

---

## 6. Commands

Run these in Minecraft chat (prefix with `/`):

| Command | What it does |
|---|---|
| `/leaputils landmark <name>` | Captures your current block position as a named landmark (saved to `config/leaputils/landmarks.json`) |
| `/leaputils plan` | Opens the Savepoint planner GUI (edit your own matrix) |
| `/leaputils presets` | Checks GitHub for preset updates |
| `/leaputils reload` | Reloads presets without restarting |
| `/leaputils logs` | Opens the logs folder |
| `/leaputils fastleap list` | Prints every Fast Leap rule with `on`/`off` |
| `/leaputils fastleap toggle <ruleId>` | Enables or disables one rule |
| `/leaputils fastleap go [ruleId]` | Evaluates now and leaps if something matches |
| `/leaputils fastleap test <ruleId>` | Evaluates and prints the decision without leaping |

---

## 7. The planner GUI

`/leaputils plan` opens a screen where you can:

- Toggle phases on/off (Maxor, Storm, Goldor, Necron, Wither King)
- Set each cell's target per class (Berserk, Archer, Mage, Tank, Healer)
- Export your whole matrix as JSON (to share or import elsewhere)
- Import a JSON plan from clipboard
- Set landmarks per phase (Nearest-mode targets)

---

## 8. Files the mod creates

All under `config/leaputils/`:

| File | What it is |
|---|---|
| `savepoint.log` | Savepoint decisions, leaps, waits, skips (rotate at 2 MB) |
| `maskhelper.log` | EarlyEnter mask-swap logs |
| `landmarks.json` | Your captured landmark positions |
| `conds.json` | Conditions loaded from presets |
| `steplabels.json` | Step names/descriptions from presets |
| `presets/adg.json` | The bundled ADG preset |
| `presets/mm7g.json` | The bundled MM7G preset |
| `fastleap.json` | Optional Fast Leap rule pack override over the built-in pack |
| `leaporder.log` | Observed Spirit Leap slot order per run, only if you enable the ordering log |
| `containerid.log` | Append-only JSONL of observed server container ids, only if you enable it |
| `pov-cache/` | Cached room-geometry bundles (downloaded or built locally) |

---

## 9. What's NOT automated

Savepoint only decides **who to leap to**. It does **not**:
- Attack camp mobs
- Toggle Jerry
- Swap gear
- Complete terminals / devices
- Move players to pads
- Detect melody, boss HP, or device completion

Those leaps rely on you opening the menu when the guide says to. The mod waits for its condition (or `anyAfterMs` timeout) and then leaps.

---

## 10. Building from source

You need JDK 25.

```
.\gradlew build
```

Output: `build/libs/pov-preview-1.0.0.jar`

---

## 11. CI / GitHub Actions

- **build.yml**: Builds on every push to `LeapUtils` (and main). Creates a GitHub release every 2 commits on LeapUtils with the jar attached.
- **commit-notification.yml**: Posts commit info to a webhook on every push (all branches).

No action needed from you — both run automatically when you push.

---

## 12. Troubleshooting

| Problem | Likely cause / fix |
|---|---|
| No previews show in leap menu | Make sure Odin's **Leap Menu** module is ON. Make sure you're actually in the leap menu screen. |
| Previews show dark quadrants | Teammate isn't loaded / too far / dead. This is normal — it's the "no feed" color. |
| Savepoint never leaps | Check your class is detected (Odin's `currentDungeonPlayer`). Check the cell target isn't `None`. Check conditions aren't blocking (verbose logging shows `Waiting: ...`). |
| Leaps the wrong person | You may have multiple teammates of the same class in the menu — the mod refuses to pick automatically ("two Berserks are in the leap menu"). |
| Build fails | You need JDK 25 and Odin 0.4.0. The mod won't compile against older versions. |

Send `config/leaputils/savepoint.log` if something acts weird — it logs every decision, leap, and wait with timestamps.
