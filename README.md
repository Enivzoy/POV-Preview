# Leap Utils for Odin

Utilities for Odin's Spirit Leap menu - renders each teammate's first-person view into their quarter of the Leap menu.

![nothing to see here](https://img.shields.io/badge/Minecraft-26.2-blue) ![Odin](https://img.shields.io/badge/Odin-0.3.6-9cf)

## What it does
Render each teammate's view into the four quadrants of the Odin Spirit Leap menu.
Everything is done client-side hence no real and this provides no less information then a discord screenshare.

## Requirements
- Not a requirement but any motion blur mod is reccomended since it will hide the imperfections i reccomend [athen](https://github.com/skies-starred/Athen).
- Minecraft 26.2
- Fabric Loader 0.19.3+
- Fabric API
- Odin 0.3.6 (`LeapMenu` must be enabled)
- Fabric Language Kotlin

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| Previews Per Frame | 1 | How many of the four feeds are re-rendered each frame. 4 is expensive but smoother. |
| Resolution | 0.75 | Relative scale per quadrant |
| Opacity | 1.0 | Opacity.. |
| Head Smoothing | Vanilla | **Vanilla** lerps the head the way the game already does (smooth, ~3 ticks behind the server). **Raw** uses the newest pose the server sent (lowest latency, 20 Hz(1/20 s) steps). |
| Max Distance | 2000 | Upto how much distance should it start feeding. |
| Show Cost | on | Ms Loss for the previews added to the last frame and how many feeds are live. |
| Disable On Error | on | Turn the previews off for the session if one throws. |

## Build

Open the project in IntelliJ IDEA with JDK 25 and run the Gradle `build`.

```
.\gradlew build
```

Tested on Odin 0.3.6 (release commit `833e053`) against Minecraft 26.2.
---

# Disclaimer
> **THIS WAS SIMPLY A CHALLENGE ACCEPTED TO ascentm7 IN THE ADVANCED DUNGEONS GUIDE DISCORD, IN NO WAY I HAVE REFERENCED THEIR CODE. THIS CODE BELONGS TO ME AND ME ONLY AS IT IS A HANDMADE, EXCLUSIVE OF ANY USE OF GENERATIVE AI!**
> **ADM OR ANY DEVELOPERS OF ADM/ENGINEERCLIENT HAVE NO RIGHTS TO MY CODE.**
> **I WILL NOT TOLERATE ANY "FIXES" TO YOUR CLIENT BY USING M.Y CODE.**
> **NO AI HAS ANY RIGHT TO USE THIS CODE TO TRAIN THEIR MODELS.**

This project is proprietary software. Copyright (c) 2026 enivzoy. **All rights reserved.**

No licence is granted, expressly or by implication, to any person or entity - including but not limited to ADM, EngineerClient, and any of their developers, contributors, staff, affiliates or users - to copy, reproduce, modify, adapt, translate, merge, publish, distribute, sublicense, sell, rent, lease, lend, decompile, disassemble, or create derivative works from this code, in whole or in part, in source or binary form, by any means and for any purpose. Publication of this repository, distribution of a build, or access to the source does not grant a licence and must not be read as one. A licence exists only if the copyright holder grants it in a separate, signed, written agreement.

The following provisions of European and international copyright law are among those that protect this work. This list is illustrative, not exhaustive, and nothing below is legal advice; consult a qualified lawyer in your jurisdiction for a binding opinion.

- **Berne Convention for the Protection of Literary and Artistic Works, Article 5(2)** - protection is automatic and requires no registration, deposit or copyright notice.
- **Directive 2009/24/EC (Software Directive), Article 4** - the author holds the exclusive rights to reproduce, translate, adapt, arrange and otherwise alter the software, and to distribute or communicate it to the public, including rental and lending.
- **Directive 2009/24/EC, Articles 5 and 6** - the limited acts of loading, running, observing, studying, testing and decompilation for interoperability are permitted only where mandatory law allows them. None of them grants a right to reuse, publish, distribute or build a competing product from this code.
- **Directive 2001/29/EC (InfoSoc Directive), Articles 2, 3 and 6** - exclusive reproduction right, exclusive right of communication to the public / making available to the public, and protection against circumvention of technological measures.
- **Directive 2019/790 (CDSM Directive), Articles 3, 4 and 17** - the text and data mining exceptions are unavailable where the rightsholder has expressly reserved the use of the work, as is done here, and online content-sharing services remain liable for unauthorised communication to the public.
- **National moral rights** - the right to be identified as the author and to object to derogatory treatment of the work, e.g. France (Code de la propriete intellectuelle, Article L121-1), Germany (UrhG, Section 13), Italy (Legge 633/1941, Article 20), and the equivalent provisions in other EU/EEA states and the United Kingdom (Copyright, Designs and Patents Act 1988, Sections 77-80). In several jurisdictions moral rights cannot be waived at all.

Any use of this code outside the scope of a written licence is an infringement of the rights above and may expose the infringing party to injunctive relief, damages, account of profits, seizure and criminal liability where applicable. The author reserves the right to pursue every available remedy.

This project is provided "as is", without warranty of any kind, and the author accepts no liability for any use, misuse or consequence of running it.

`LICENSE` contains the full proprietary licence text. That file and this notice control in case of conflict with any other statement in this repository.
