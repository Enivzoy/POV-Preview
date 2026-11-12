# Leap Utils for Odin

Utilities for Odin's Spirit Leap menu - renders each teammate's first-person view into their quarter of the Leap menu, plus auto leap modules for blood rush and the M7 boss phases.

![nothing to see here](https://img.shields.io/badge/Minecraft-26.3-blue) ![Odin](https://img.shields.io/badge/Odin-0.4.0-9cf)

## What it does
Render each teammate's view into the four quadrants of the Odin Spirit Leap menu.

Each view is drawn from that teammate's own shared position and look, over your own block meshes - so you pick a leap by looking at where it lands rather than by reading a name. Teammates you cannot see are still drawn from the last pose your game had of them, or from where the dungeon map puts them.

On top of that theres two auto leap modules, Savepoint and Blood Rush, both explained below.

## Requirements
- Not a requirement but any motion blur mod is reccomended since it will hide the imperfections i reccomend [athen](https://github.com/skies-starred/Athen).
- Minecraft 26.3
- Fabric Loader 0.19.5+
- Fabric API
- Odin 0.4.0 (`LeapMenu` must be enabled)
- Fabric Language Kotlin

## POV Previews settings

| Setting | Default | Meaning |
| --- | --- | --- |
| Prerender | on | Builds the whole dungeon and the boss arena in the background while you're in a dungeon, so the leap menu only has to draw. Room geometry is kept across runs either way. |
| Use shared POVs | on | Draws teammates who share from their own view (exact and smooth). Off: only from what your game sees of them, and the map's guess out of range. |
| Sharing FPS | 60 | How many times a second your look goes to teammates (whether or not this module is on). Higher is smoother for them. At the lowest it reads **off** and nothing of yours is shared. |
| Only In Blood Rush | off | Shows the views on blood rush: from the dungeon starting until the blood door opens. Adds to the other Only In place; neither ticked is everywhere. |
| Only In Terms | off | Shows the views in terms: F7/M7 phase 3 (the terminals, devices and levers, and the Goldor split after them until you drop to Necron). Adds to the other Only In place; neither ticked is everywhere. |
| HUDs Over Views | Mod HUDs | None: just the views. Mod HUDs: every mod's HUD (HUD-editor timers, other mods' HUDs, and titles such as alerts) but not Odin's Dungeon Map or vanilla's HUD. All: everything, maps and vanilla's HUD too. The leap menu's own boxes and names stay on top either way. |
| POV resolution | 100% | How sharp the views are drawn. Lower is faster on weaker cards; they're scaled up to fill their quarter of the screen. |
| View distance | 96 blocks | How far the views draw, fading into the sky over the last stretch. Lower is faster. |
| Debug info | off | Over the views: the relay and TURN connections, each teammate's route and one-way delays both ways, frames a second, losses and buffering. |

### Sharing your view with your party

Your position, look and held item are sent to the other games in your run so they can draw you. Frames go peer to peer over Cloudflare TURN when that is up, and through the relay otherwise.

The relay and the room-geometry bundles are hosted by default. To run your own, put a `pov-server.properties` in `config/leaputils/`:

```properties
relay=wss://your-relay.example.com/pose/api/
mesh=https://your-cdn.example.com/adr/mesh/
```

`relay` is the WebSocket endpoint (the path is the room prefix); `mesh` is where the room geometry bundles are fetched from - the mod builds them itself when they are not there, so it works without a mesh host at all. The same two values can be set with the `leaputils.pov.relay` and `leaputils.pov.mesh` system properties.

## Savepoint

Leap helper for m7/f7 according to m7g roles/adg roles/custom roles  
every phase and every step of the boss fight has a certain person your supposed to leap to, and instead of memorising it or fumbling thru the menu you configure it once and the mod shows you who you have to leap to next.  
Disabling Schizo Mode is unreccomended.  
Important part - **it never opens the leap menu itself**, it only acts when you already opened it.

Schizo Mode lets you press any keybind e.g w a s d RMB, LMB, 2 4 5 and it will leap to the next person


### The rule matrix

The core of it is a matrix: for every stage, every step in that stage and every class there is one cell saying who to leap to when your that class. Stages are:

| Stage | Phase | What it covers |
| --- | --- | --- |
| P1 | Maxor | two crystal checkpoint waves, then tank and archer leap to mage to finish Maxor |
| P2.GY / P2.OLDPY / P2.NEWPY | Storm | ordered route-specific setup, lightning, crush and transition rows |
| P3.S1 - P3.S4 | Goldor | role-specific terminal transitions; S4 supports entry, post-terminal and recore leaps |
| P4 | Necron | role-specific drop, 70m and relic handoff leaps; instamid remains a movement route |
| P5 | Wither King | optional landmark or per-class rules |

Only stages you toggled on and that match the current phase are used, and by default you only see the rows for your own class in the gui ("Show all classes" shows everything).

The goldor section number is tracked from chat alone (boss start line, terminal/lever/device messages, gate destroyed, core entrance) so no reflection hacks, and it resets properlly on world load, floor change and section change. Your consumed steps reset when they should too - leaving the boss, new goldor section, floor change or the skip key - so a leap you already did *doesnt* get repeated.

### Conditions

Any cell can have a condition that must be true before the mod is allowed to click:

- `target` or `you` must be `at` (2 blocks) / `near` / `in` (8 blocks) of a landmark
- a hold time in ms - the condition has to stay true that long so it doesnt click on someone sprinting past the spot
- what to do if it never becomes true: keep waiting, skip the step entierly, or after X ms just leap to anyone

### Landmarks

A landmark is just a named position. Stand where you want it and run `/leaputils landmark <name>`, or use the Capture here button in the settings. `necron_spot` ships seeded at the common P4 spot so nearest mode works out the box.
[A M7 map download](https://cdn.discordapp.com/attachments/1508852179128291358/1564007004203258046/mk3bkxh.rar?ex=6ae05b47&is=6adf09c7&hm=e7c95e5e56c41fafd98b3c36357ccd4b26cedfce39dbd9d90cb4e2d427fa4125&)

### Presets

ADG and MM7G guide presets come bundled as a starting point, load them from the settings (press twice within 5 sec to confirm since it overwrites the whole matrix). Entries marked TODO_USER are left blank on purpose since those depend on your party setup. Conditions you write are saved to `config/leaputils/conds.json` so they survive loading another preset.

The ADG preset follows the supplied current-role POV: Maxor crystal checkpoints, split Storm predev, green-yellow crush, then the updated Goldor terminal roles. Its notes also document camp timing, movement, gear swaps and conditional melody branches. Savepoint only automates leap targets; it does not attack camp mobs, toggle Jerry, swap gear, complete terminals/devices, or move players to pads. Conditional melody decisions remain manual.

### Misc

- Old flat settings get migrated into the matrix automaticly on first tick, the old settings stay hidden so your saved config doesnt break
- Pause and Skip keybinds
- Dry run mode - logs every decision in chat without actually clicking
- Floor filter (F7 / M7 / both)
- HUD showing what the next leap is or why its waiting
- Schizo mode support, any keypress in the menu triggers the leap instead of waiting the delay
- Everything gets logged to `config/leaputils/savepoint.log` (leaps, decisions, chat tracking, positions) so if something acts weird just send me that file

## Blood Rush

Leaps you to the teammate standing closest to the wither door while the blood key is still unpicked. Only runs for archer, mage and healer since tank and berserk dont blood rush anyway.

It stops leaping once the blood door is opened (odin detection or the chat message), once blood is done, or once your in the boss. All of that state resets on floor enter so it works on every run, not just the first one after you load the world.

Delay and schizo mode work the same way as in Savepoint.

## Build

Open the project in IntelliJ IDEA with JDK 25 and run the Gradle `build`.

```
.\gradlew build
```

Tested on Odin 0.4.0 on Minecraft 26.3.
