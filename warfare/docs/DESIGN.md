# WARFARE - design record

Modern-day grand strategy on a real-world province map, built from the 84 answers given in the design interview.
Start date 1 November 2026. Real-time with pause. Sandbox (no fixed end).

## What the 84 answers decided

| Area | Decision |
|---|---|
| Platform / engine | Steam release is the goal. Java 21 (the user reports Age of History 3 used a custom Java engine; that engine is proprietary, so this project has its own). Simulation is renderer-independent (`core`), the Swing client (`desktop`) is replaceable by LWJGL/libGDX for Steam. |
| Setting | Modern day / near future, all ~190 countries playable (197 sovereign nations on the map). |
| Map | Provinces on a real-world map: 3,400+ land provinces built from real administrative regions (Natural Earth 10m admin-1; oversized regions split, tiny ones merged) and ~270 sea zones, with smooth vector borders (`tools`). Satellite imagery was unreachable offline, so terrain is a latitude/region approximation. |
| Time | Real-time with pause, 5 speeds (Space pause, keys 1-5). |
| Players | Single-player vs AI first. |
| Fog of war | Classic vision radius, plus: hides unit location, strength and type, province ownership and buildings, and secret alliances. Vision from units, provinces, recon/aircraft, radar, satellites (tech), agents, allied sharing. Old intel **fades over time**. Province-based flat shading. |
| Tactics | Five tactic cards, all unlocked from the start (Frontal Assault, Flanking, Ambush, Hold the Line, Defense in Depth). Soft rock-paper-scissors (each beats two, loses to two). Chosen before or at contact, or leave the army on **General (auto)**, which picks using only what your nation can see. Outcome depends on unit composition (plus terrain, counters, surprise). Battle result is a report. |
| Armies | Individual stacks; click army, click destination. Stats: attack, defense, HP. Units: infantry, mechanized, armor, artillery, air defense, anti-tank, special forces. No commanders. |
| Economy | Abstract: money, manpower, industry, oil. Province output needs buildings. Pay money + manpower, units arrive after a delay. Supply: none (cut-off armies suffer nothing). |
| Diplomacy | Declare war any time, negotiated peace, alliances/blocs, sanctions, aid, oil trade, espionage with agents stationed abroad. Conquest is one province at a time. |
| Politics | Stability plus coups and revolutions; national morale tied to stability; random events with choices. |
| Military extras | Air wings with missions that feed fog of war; simple navy (transports, blockade); special forces and insurgency; nukes only (no conventional missiles), launched from a silo, province devastated. |
| Research | Simple upgrade tracks (Land, Air, Intelligence & Space). |
| UI | Map + side panel, customizable layout, minimal overlays, cinematic orchestral audio (not yet built), flat colored provinces, army icon with strength number, map modes: political, terrain, intel, diplomatic. |
| Release | English only, one-time purchase, no DLC, Windows only, no modding for now, achievements + cloud saves, manual save slots. |

## Status

**Built and covered by tests** (`core`: 29, `desktop`: 12 incl. a real-window UI test under xvfb):

* Map: 3,476 land provinces from real administrative regions, 271 sea zones, 198 nations, smooth vector borders.
* Fog of war: vision quality levels, intel decay, hidden ownership/buildings/secret pacts, satellites, radar, recon,
  agents, allied sharing, **decoy forces** (look real from afar, exposed up close) and **special-forces sabotage raids**.
* Tactic cards with soft counters, composition/terrain/surprise effects, General (auto) mode that only uses visible intel.
* Economy (money, manpower, industry, oil), buildings, recruitment, research tracks, air wings, simple navy, nukes.
* **Economy panel** (left dock, key E): tax rate with efficiency, six budget sliders (development, infrastructure, education,
  welfare, security, military) with live effect text, policies (mobilization, industry focus, fuel rationing, printing money),
  daily ledger, presets and a build-economy tool. The AI uses the same controls (war stance vs peace stance).
* **Diplomacy realism:** an opinion matrix (-100..100) built from blocs, rivalries, friendships, borders and government type; alliances
  are accepted only when the score clears a bar and the refusal explains why. Aid, trade and good behaviour earn friends; war,
  sanctions, broken pacts and nukes cost them. AI peace offers have cooldowns, so refusals are not met with spam.
* **Governments and the democracy menu:** seven government types change war weariness, tax anger and coup risk. Four interest groups
  (military, business, workers, public) react to your budget; democracies and republics hold elections every four years.
* Province sizes are normalised across nations (tiny regions merged, huge ones split), 3,600+ land provinces.
* Capitals: real capital cities (Natural Earth) are marked with stars on the map; yours is gold, a lost capital turns red.
* Diplomacy (war, negotiated peace, alliances, sanctions, aid, oil deals), stability, coups, events, insurgency.
* AI nations that play under fog, with peacetime build-up capped relative to their starting force.
* UI: top bar, card-based panels, nation picker, flags, icons, tutorial, achievements (16) with toasts, settings,
  autosave, ironman mode, game-over screen, save slots.
* Procedural cinematic score (stems that follow war tension) and sound effects, generated at runtime.
* Packaging: `installDist` and `jpackage` app image; Steam depot templates and checklist in `docs/STEAM.md`.

**Not done / not verified**

* Windows build and the Steamworks bridge were never run against a real Windows machine or Steam client.
* No real satellite imagery (blocked offline): relief is procedural. Terrain classes are a latitude/region guess.
* Audio was verified by analysing the generated waveforms, not by ear.
* Supply/logistics intentionally absent per the design answers; naval layer is transports and blockade only.
* Ironman and autosave are simple (no cloud conflict handling); English only; no controller support.

## Running

```bash
cd warfare
./gradlew test                      # core tests
./gradlew :desktop:run              # play (needs a display)
./gradlew :desktop:run --args="--shot out.png RUS 40 UKR INTEL"   # headless screenshot
./gradlew :tools:run --args="countries.geojson assets/map 2900 260"   # regenerate the map
```

## Visuals

Provinces are drawn as antialiased vector shapes (traced, simplified and Chaikin-smoothed border arcs) over a procedural
satellite-style underlay (blended terrain colours, noise hillshade, coastal ocean depth). Fonts are Barlow / Barlow
Condensed / Bebas Neue (SIL OFL, `assets/fonts`); UI theme is FlatLaf (Apache 2.0). Real satellite imagery was not
reachable from the build environment, so relief is procedural.
