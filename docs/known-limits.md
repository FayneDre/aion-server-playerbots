# Known limits, in order of how much they will bite

What this project does not control or has chosen to live with, reviewed with each patch. What is left to do is in [roadmap.md](roadmap.md); what the engine does to a bot that nothing warns about, in [traps/](traps/engine-traps.md).

1. **How many characters a client tolerates in one place**: measured once on 20 October, in Sanctum on the tester's machine. It held 60 fps with
   25 standing in sight, about 45 with 50, 35 with 75 and 30 with 97 ([living-capitals-plan.md](capitals/history/living-capitals-plan.md)). This is the ceiling
   this project does not control and the one a player feels.
2. **Memory**: about 800 awake bots fill a 2560 MB heap (90% old generation), which is why the two housing maps (Oriel, Pernon) are not populated.
   The machine has little left besides: a long background wait of the tooling was killed for low memory on 17 October.
3. **Only maps with a mesh are planned on**: 26 open-world maps and 45 dungeons (`tools/navmesh.ps1 <mapId>`, then `-Obelisks`); flight links on Reshanta
   alone (`NavmeshTool <mapId> flights`, server stopped). The Cloister of Kaisinel and Panesterra have no heightmap.
4. **The mesh is not the geometry**: props placed at run time (gatherables, doors) are not in it. Walls are, at a body's width since 18 October
   (erosion per surface, every open-world mesh generated again; a test server runs `tools/navmesh.ps1 -OpenWorld` once). What is left of the steps aside is the berth round gatherables, which the client does
   need: a body does not walk through one (checked in game, 18 October; [obstacle-steering-plan.md](navigation/history/obstacle-steering-plan.md)).
5. **Walkable ground comes in islands**, and a route between two does not exist: `NavmeshTool <mapId> components` says so. Fortress obelisks are the
   usual case ([navigation-stuck-plan.md](navigation/history/navigation-stuck-plan.md)); Reshanta's two pass the audit through the flight links.
6. **A crash loses at most 5 minutes** of what bots did. A clean stop takes seconds since 18 October (it took 510 s while a dead bot searched for a
   spot round Primum's obelisk); `stop-server.ps1` waits up to 900 s.
7. **Bots flee only from a lost fight** (`retreatIfLosing`), never in a group; otherwise they heal, drink and shield themselves, and wait out the soul
   sickness at a spot of their own after a death ([bot-careers.md](population/bot-careers.md)).

## Tests

- **397 tests on the game server**, run on every deploy. The combat half of `PlayerBotAI` is still untested and unsplit, so a mistake there is
  silent. Decisions written as pure functions of a situation record are what made the suites possible; keep writing them that way.
