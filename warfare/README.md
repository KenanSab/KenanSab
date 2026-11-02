# WARFARE

A modern-day grand strategy game with deep fog of war and tactic-card battles, built in Java 21.
Start date 1 November 2026, real time with pause, sandbox (no fixed end). See [docs/DESIGN.md](docs/DESIGN.md).



## Play

Needs Java 21 (JDK) and a display.

```bash
./gradlew :desktop:run            # Windows: gradlew.bat :desktop:run
```

| Input | Action |
|---|---|
| Space | pause / play |
| 1-5 | game speed |
| Left-click army, then left-click province | march |
| Right-click | inspect a province |
| Wheel, +/- | zoom |
| Drag, arrows, WASD | pan |
| M | cycle map mode (political, terrain, intel, diplomatic) |
| Esc | deselect army |

Pick a tactic card per army in the **Selection** tab, or leave it on **General (auto)**, which only uses what your
intelligence shows. A tutorial walks through the first minutes (Help > Restart tutorial).

## Layout

| Module | What it is |
|---|---|
| `core/` | engine-independent simulation (no dependencies): map data, fog of war (`Vision`, `Intel`), battles and tactic cards (`Combat`, `TacticAdvisor`), AI under fog (`Ai`), economy, diplomacy, nukes, events, achievements |
| `desktop/` | Swing client: vector map renderer, UI panels, procedural music and effects, tutorial, settings, Steam bridge |
| `tools/` | map generator (Natural Earth admin-1 regions -> provinces + smooth vector borders) |
| `assets/` | generated map, fonts (OFL), game-icons.net icons (CC BY 3.0), flag-icons (MIT) |
| `docs/` | `DESIGN.md` (decisions and status), `STEAM.md` (shipping checklist) |

## Develop

```bash
./gradlew :core:test                                  # simulation tests (17)
xvfb-run -a ./gradlew :desktop:test                   # audio + real-window UI tests (needs a display or xvfb)
./gradlew :desktop:installDist                        # runnable distribution in desktop/build/install/warfare
./gradlew :desktop:packageApp                         # native app image with bundled runtime (run on the target OS)
./gradlew :tools:run --args="admin0.geojson admin1.geojson assets/map 5200 60 260"   # regenerate the map
```

Handy flags: `--nation TAG` skips the picker, `--smoke [seconds]` plays the real window unattended and prints
timings, `--shot out.png TAG DAYS FOE MODE TAB` renders a headless screenshot, `--export-audio dir` writes the score as WAV.
