# Leap Utils for Odin

Utilities for Odin's Spirit Leap menu - renders each teammate's first-person view into their quarter of the Leap menu, plus auto leap modules for blood rush and the M7 boss phases.

![nothing to see here](https://img.shields.io/badge/Minecraft-26.2-blue) ![Odin](https://img.shields.io/badge/Odin-0.3.6-9cf)

## What it does
Render each teammate's view into the four quadrants of the Odin Spirit Leap menu.
Everything is done client-side hence no real and this provides no less information then a discord screenshare.

On top of that theres two auto leap modules, Savepoint and Blood Rush, both explained below.

## Requirements
- Not a requirement but any motion blur mod is reccomended since it will hide the imperfections i reccomend [athen](https://github.com/skies-starred/Athen).
- Minecraft 26.2
- Fabric Loader 0.19.3+
- Fabric API
- Odin 0.3.6 (`LeapMenu` must be enabled)
- Fabric Language Kotlin

## POV Previews settings

| Setting | Default | Meaning |
| --- | --- | --- |
| Previews Per [Frame | 1 | How many of the four feeds are re-rendered each frame. 4 is expensive but smoother. |
| Resolution | 0.75 | Relative scale per quadrant |
| Opacity | 1.0 | Opacity.. |
| Head Smoothing | Vanilla | **Vanilla** lerps the head the way the game already does (smooth, ~3 ticks behind the server). **Raw** uses the newest pose the server sent (lowest latency, 20 Hz(1/20 s) steps). |
| Max Distance | 2000 | Upto how much distance should it start feeding. |
| Show Cost | on | Ms Loss for the previews added to the last frame and how many feeds are live. |
| Disable On Error | on | Turn the previews off for the session if one throws. |
]()
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
| P1.L / P1.R | Maxor | left and right crystal routes, one leap each |
| P2.GY / P2.OLDPY / P2.NEWPY | Storm | drop off leap and purple spotter leap, seperate rows per route variant |
| P3.S1 - P3.S4 | Goldor | one leap per section (S3 has two, the second one is the core leap) |
| P4 | Necron | usually leap to whoever is closest to necron but useless now as imid exists in a competent enough party |
| P5 | Wither King | same idea as P4, whoever is closest to a certain landmark |

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

Tested on Odin 0.3.6 (release commit `833e053`) on Minecraft 26.2.
