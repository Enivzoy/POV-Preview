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

Once you leap for a cell, that step is **consumed** (marked done) so it won't repeat. Consumption resets when Goldor's section changes, when you leave the boss, or on floor enter.

---

## 5. Commands

Run these in Minecraft chat (prefix with `/`):

| Command | What it does |
|---|---|
| `/leaputils landmark <name>` | Captures your current block position as a named landmark (saved to `config/leaputils/landmarks.json`) |
| `/leaputils plan` | Opens the Savepoint planner GUI (edit your own matrix) |
| `/leaputils presets` | Checks GitHub for preset updates |
| `/leaputils reload` | Reloads presets without restarting |
| `/leaputils logs` | Opens the logs folder |

---

## 6. The planner GUI

`/leaputils plan` opens a screen where you can:

- Toggle phases on/off (Maxor, Storm, Goldor, Necron, Wither King)
- Set each cell's target per class (Berserk, Archer, Mage, Tank, Healer)
- Export your whole matrix as JSON (to share or import elsewhere)
- Import a JSON plan from clipboard
- Set landmarks per phase (Nearest-mode targets)

---

## 7. Files the mod creates

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
| `pov-cache/` | Cached room-geometry bundles (downloaded or built locally) |

---

## 8. What's NOT automated

Savepoint only decides **who to leap to**. It does **not**:
- Attack camp mobs
- Toggle Jerry
- Swap gear
- Complete terminals / devices
- Move players to pads
- Detect melody, boss HP, or device completion

Those leaps rely on you opening the menu when the guide says to. The mod waits for its condition (or `anyAfterMs` timeout) and then leaps.

---

## 9. Building from source

You need JDK 25.

```
.\gradlew build
```

Output: `build/libs/pov-preview-1.0.0.jar`

---

## 10. CI / GitHub Actions

- **build.yml**: Builds on every push to `LeapUtils` (and main). Creates a GitHub release every 2 commits on LeapUtils with the jar attached.
- **commit-notification.yml**: Posts commit info to a webhook on every push (all branches).

No action needed from you — both run automatically when you push.

---

## 11. Troubleshooting

| Problem | Likely cause / fix |
|---|---|
| No previews show in leap menu | Make sure Odin's **Leap Menu** module is ON. Make sure you're actually in the leap menu screen. |
| Previews show dark quadrants | Teammate isn't loaded / too far / dead. This is normal — it's the "no feed" color. |
| Savepoint never leaps | Check your class is detected (Odin's `currentDungeonPlayer`). Check the cell target isn't `None`. Check conditions aren't blocking (verbose logging shows `Waiting: ...`). |
| Leaps the wrong person | You may have multiple teammates of the same class in the menu — the mod refuses to pick automatically ("two Berserks are in the leap menu"). |
| Build fails | You need JDK 25 and Odin 0.4.0. The mod won't compile against older versions. |

Send `config/leaputils/savepoint.log` if something acts weird — it logs every decision, leap, and wait with timestamps.
