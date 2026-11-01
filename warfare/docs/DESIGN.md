# WARFARE - design record

Modern-day grand strategy on a real-world province map, built from the 84 answers given in the design interview.
Start date 1 November 2026. Real-time with pause. Sandbox (no fixed end).

## What the 84 answers decided

| Area | Decision |
|---|---|
| Platform / engine | Steam release is the goal. Java 21 (the user reports Age of History 3 used a custom Java engine; that engine is proprietary, so this project has its own). Simulation is renderer-independent (`core`), the Swing client (`desktop`) is replaceable by LWJGL/libGDX for Steam. |
| Setting | Modern day / near future, all ~190 countries playable (197 sovereign nations on the map). |
| Map | Provinces on a real-world map: 3,700+ land provinces and ~270 sea zones, generated from Natural Earth borders (`tools`). Satellite imagery was unreachable offline, so terrain is a latitude/region approximation. |
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

Implemented and covered by `core` tests: map generation, fog of war with decay, tactic cards and General mode, battles, occupation, economy, recruitment, buildings, research, diplomacy, nukes, events, AI (plays under fog), insurgency, air missions, agents, save/load.

Not yet built: Steamworks (achievements, cloud saves), audio/music, ironman option, richer naval layer, special-forces raids, trade beyond oil, tutorial, polished art. Supply/logistics intentionally left out per the answers. The desktop GUI was verified through headless screenshots only.

## Running

```bash
cd warfare
./gradlew test                      # core tests
./gradlew :desktop:run              # play (needs a display)
./gradlew :desktop:run --args="--shot out.png RUS 40 UKR INTEL"   # headless screenshot
./gradlew :tools:run --args="countries.geojson assets/map 2900 260"   # regenerate the map
```
