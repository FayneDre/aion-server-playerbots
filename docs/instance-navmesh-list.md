# Instance maps to build a navmesh for

Status: list only, no instance has a mesh yet (the 26 meshes in `data/navmesh` are open world, cities and the Abyss). Read from `world_maps.xml` (`instance="true"`), `data/geo` and the
spawn files on 2026-10-10. How to build one is in [navmesh-plan.md](navmesh-plan.md): `.\tools\navmesh.ps1 300040000,300110000`.

## What the data says

- **110 instance maps**, of which **105 have a geometry file** and 5 have none (tests). Only 25 of them carry a terrain heightmap; the rest
  are made of placed meshes alone, the way Sanctum and Reshanta are, and the generator takes their size from `world_size` for that reason.
- **86 distinct geometries.** Several instances reuse one map under other ids (a hard mode, a training copy, a legion barracks): the `geo` and `png` files are byte
  for byte the same. A mesh built once serves them all, if it may be copied under the other ids (to be checked in `NavmeshService`: is the id only the file name?).
- **Most are interiors on several floors**, which is the case [navigation-stuck-plan.md](navigation-stuck-plan.md) says the rough route handles badly (it has no height). Build
  one small dungeon first and look at it before the batch.
- Nothing here is populated: bots live in the open world, and no code puts one in an instance yet. The meshes come before that work, not with it.

Columns: map id, name in the client, internal name, side in metres (`world_size`), geometry in KB, and what shares its geometry.

## PvE dungeons and raids (77)

| Id | Name | Internal | m | KB | Same geometry as |
|---|---|---|---|---|---|
| 300030000 | Nochsana Training Camp | IDAb1_MiniCastle | 1024 | 18 |  |
| 300040000 | Dark Poeta | IDLF1 | 1536 | 561 |  |
| 300050000 | Asteria Chamber | IDAbRe_Up_Asteria | 1024 | 63 |  |
| 300060000 | Sulfur Tree Nest | IDAbRe_Low_Divine | 1024 | 62 |  |
| 300070000 | Chamber of Roah | IDAbRe_Up_Rhoo | 1024 | 46 |  |
| 300080000 | Left Wing Chamber | IDAbRe_Low_Wciel | 1024 | 44 |  |
| 300090000 | Right Wing Chamber | IDAbRe_Low_Eciel | 512 | 12 |  |
| 300100000 | Steel Rake | IDshulackShip | 1024 | 430 |  |
| 300110000 | Dredgion | IDAb1_Dreadgion | 1024 | 67 |  |
| 300120000 | Kysis Chamber | IDAbRe_Up3_Dkisas | 1024 | 116 |  |
| 300130000 | Miren Chamber | IDAbRe_Up3_Lamiren | 1024 | 124 |  |
| 300140000 | Krotan Chamber | IDAbRe_Up3_Crotan | 1024 | 118 |  |
| 300150000 | Udas Temple | IDTemple_Up | 1024 | 187 |  |
| 300160000 | Lower Udas Temple | IDTemple_Low | 2048 | 346 |  |
| 300170000 | Beshmundir Temple | IDCatacombs | 2048 | 415 |  |
| 300190000 | Taloc's Hollow | IDElim | 2048 | 147 |  |
| 300200000 | Haramel | IDNovice | 512 | 222 |  |
| 300210000 | Chantra Dredgion | IDDreadgion_02 | 1024 | 111 |  |
| 300220000 | Abyssal Splinter | IDAbRe_Core | 1024 | 102 |  |
| 300230000 | Kromede's Trial | IDCromede | 1024 | 257 |  |
| 300240000 | Aturam Sky Fortress | IDStation | 1024 | 181 |  |
| 300250000 | Esoterrace | IDF4Re_Drana | 1536 | 348 |  |
| 300280000 | Rentus Base | IDYun | 1024 | 259 | builds 300620000 |
| 300440000 | Terath Dredgion | IDDreadgion_03 | 1024 | 109 |  |
| 300460000 | Steel Rake Cabin | IDshulackShip_Solo | 1024 | 428 |  |
| 300480000 | Sealed Danuar Mysticarium | IDLDF5RE_Solo | 768 | 284 |  |
| 300510000 | Tiamat Stronghold | IDTiamat_1 | 2048 | 372 |  |
| 300520000 | Dragon Lord's Refuge | IDTiamat_2 | 1024 | 25 | builds 300630000 |
| 300540000 | The Eternal Bastion | IDLDF5b_TD | 1024 | 250 |  |
| 300590000 | Ophidan Bridge | IDLDF5_Under_01 | 1024 | 89 |  |
| 300600000 | Unstable Abyssal Splinter | IDAbRe_Core_02 | 1024 | 101 |  |
| 300610000 | Raksang Ruins | IDRaksha_solo | 1536 | 345 |  |
| 300620000 | Occupied Rentus Base | IDYun_Hard | 1024 | 259 | 300280000 |
| 300630000 | Anguished Dragon Lord's Refuge | IDTiamat_2_Hard | 1024 | 25 | 300520000 |
| 300700000 | The Hexway | IDUnderpassRe | 1024 | 45 |  |
| 301110000 | Danuar Reliquary | IDLDF5_Under_Rune | 512 | 79 | builds 301330000, 301360000 |
| 301130000 | Sauro Supply Base | IDVritra_Base | 1024 | 412 |  |
| 301140000 | Seized Danuar Sanctuary | IDLDF5_Under_02 | 1536 | 884 | builds 301380000 |
| 301230000 | Illuminary Obelisk | IDLDF5_Under_03 | 512 | 68 | builds 301370000 |
| 301240000 | Legion's Kysis Barracks | IDAbRe_Up3_Dkisas_02 | 1024 | 115 | builds 301280000 |
| 301250000 | Legion's Miren Barracks | IDAbRe_Up3_Lamiren_02 | 1024 | 124 | builds 301290000 |
| 301260000 | Legion's Krotan Barracks | IDAbRe_Up3_Crotan_02 | 1024 | 118 | builds 301300000 |
| 301270000 | Linkgate Foundry | IDLDF4Re_01 | 512 | 179 |  |
| 301280000 | Kysis Barracks | IDAbRe_Up3_Dkisas_02_N | 1024 | 115 | 301240000 |
| 301290000 | Miren Barracks | IDAbRe_Up3_Lamiren_02_N | 1024 | 124 | 301250000 |
| 301300000 | Krotan Barracks | IDAbRe_Up3_Crotan_02_N | 1024 | 118 | 301260000 |
| 301310000 | Idgel Dome | IDLDF5_Fortress_Re | 512 | 78 |  |
| 301320000 | Lucky Ophidan Bridge | IDLDF5_Under_01_PC | 1024 | 89 |  |
| 301330000 | Lucky Danuar Reliquary | IDLDF5_Under_Rune_PC | 512 | 79 | 301110000 |
| 301340000 | Linkgate Foundry | IDLDF4Re_01_Q | 512 | 84 |  |
| 301360000 | Infernal Danuar Reliquary | IDLDF5_Under_Rune_H | 512 | 79 | 301110000 |
| 301370000 | Infernal Illuminary Obelisk | IDLDF5_Under_03_H | 512 | 68 | 301230000 |
| 301380000 | Danuar Sanctuary | IDLDF5_Under_02_E | 1536 | 884 | 301140000 |
| 301390000 | Drakenspire Depths | IDSeal | 1024 | 239 |  |
| 310010000 | Karamatis | IDAbProL1 | 1024 | 26 | builds 310020000, 320010000, 320020000, 320140000 |
| 310020000 | Karamatis | IDAbProL2 | 1024 | 26 | 310010000 |
| 310030000 | Aerdina | IDAbGateL1 | 1024 | 11 |  |
| 310040000 | Geranaia | IDAbGateL2 | 1024 | 11 |  |
| 310050000 | Aetherogenetics Lab | IDLF3Lp | 512 | 91 |  |
| 310060000 | Sliver of Darkness | IDLF1B | 3072 | 163 | builds 320060000 |
| 310070000 | Sliver of Darkness | IDLF1B_Stigma | 512 | 62 |  |
| 310090000 | Indratu Fortress | IDLF3_Castle_indratoo | 1024 | 41 |  |
| 310100000 | Azoturan Fortress | IDLF3_Castle_Lehpar | 1024 | 34 |  |
| 310110000 | Theobomos Lab | IDLF2a_Lab | 1024 | 136 |  |
| 310120000 | Karamatis | IDAbProL3 | 1024 | 26 |  |
| 320010000 | Ataxiar | IDAbProD1 | 1024 | 26 | 310010000 |
| 320020000 | Ataxiar | IDAbProD2 | 1024 | 26 | 310010000 |
| 320030000 | Bregirun | IDAbGateD1 | 1024 | 11 |  |
| 320040000 | Nidalber | IDAbGateD2 | 1024 | 11 |  |
| 320050000 | Sky Temple Interior | IDDF2Flying | 512 | 153 |  |
| 320060000 | Space of Oblivion | IDDF1B | 3072 | 163 | 310060000 |
| 320080000 | Draupnir Cave | IDDF3_Dragon | 1024 | 161 |  |
| 320100000 | Fire Temple | IDDF2_Dflame | 512 | 244 |  |
| 320110000 | Alquimia Research Center | IDDF3_LP | 1024 | 111 |  |
| 320130000 | Adma Stronghold | IDDf2a_Adma | 1024 | 166 |  |
| 320140000 | Ataxiar | IDAbProD3 | 1024 | 26 | 310010000 |
| 320150000 | Padmarashka's Cave | IDDramata_01 | 1024 | 34 |  |

## Special and event instances (6)

| Id | Name | Internal | m | KB | Same geometry as |
|---|---|---|---|---|---|
| 300560000 | Shugo Imperial Tomb | IDDF2Flying_Event01 | 512 | 53 |  |
| 300800000 | Infinity Shard | IDRuneweapon | 256 | 65 |  |
| 301160000 | Nightmare Circus | IDAsteria_IU_Party | 1024 | 37 | builds 301200000 |
| 301200000 | The Nightmare Circus | IDAsteria_IU_World | 1024 | 37 | 301160000 |
| 301400000 | The Shugo Emperor's Vault | IDSweep | 1024 | 148 |  |
| 301500000 | Stonespear Reach | IDLegion | 512 | 18 |  |

## Arenas and training grounds (PvP) (13)

| Id | Name | Internal | m | KB | Same geometry as |
|---|---|---|---|---|---|
| 300300000 | Empyrean Crucible | IDArena | 2048 | 90 |  |
| 300320000 | Crucible Challenge | IDArena_Solo | 2048 | 131 |  |
| 300350000 | Arena of Chaos | IDArena_pvp01 | 2048 | 165 | builds 300420000 |
| 300360000 | Arena of Discipline | IDArena_pvp02 | 2048 | 121 | builds 300430000 |
| 300420000 | Chaos Training Grounds | IDArena_pvp01_T | 2048 | 165 | 300350000 |
| 300430000 | Discipline Training Grounds | IDArena_pvp02_T | 2048 | 121 | 300360000 |
| 300450000 | Arena of Harmony | IDArena_Team01 | 2048 | 340 | builds 300570000, 301100000 |
| 300550000 | Arena of Glory | IDArena_Glory | 2048 | 161 |  |
| 300570000 | Harmony Training Grounds | IDArena_Team01_T | 2048 | 340 | 300450000 |
| 301100000 | Unity Training Grounds | IDArena_Team01_T2 | 2048 | 340 | 300450000 |
| 310080000 | Sanctum Underground Arena | IDLC1_arena | 512 | 5 |  |
| 320090000 | Triniel Underground Arena | IDDC1_arena | 512 | 5 |  |
| 320120000 | Shadow Court Dungeon | IDDC1_Arena_3F | 1024 | 18 |  |

## Warfronts and battlefields (PvP) (3)

| Id | Name | Internal | m | KB | Same geometry as |
|---|---|---|---|---|---|
| 301120000 | Kamar Battlefield | IDKamar | 3072 | 227 |  |
| 301210000 | Engulfed Ophidan Bridge | IDLDF5_Under_01_War | 1024 | 261 |  |
| 301220000 | Iron Wall Warfront | IDF5_TD_War | 1024 | 286 |  |

## Personal housing instances (2)

| Id | Name | Internal | m | KB | Same geometry as |
|---|---|---|---|---|---|
| 720010000 | Oriel | housing_idlf_personal | 512 | 42 |  |
| 730010000 | Pernon | housing_iddf_personal | 512 | 33 |  |

## Test maps, with little or no geometry (9)

| Id | Name | Internal | m | KB | Same geometry as |
|---|---|---|---|---|---|
| 300020000 | IDTest_Dungeon | IDTest_Dungeon | 256 | 0 |  |
| 300290000 | - | Test_MRT_IDZone | 512 | 5 |  |
| 320070000 | Space of Destiny | IDSpace | 512 | 0 |  |
| 900120000 | - | Test_IDArena | 0 | 0 |  |
| 900130000 | - | IDLDF5RE_test | 0 | 0 |  |
| 900140000 | Instanced Dungeon Test | Test_Kgw | 0 | 0 |  |
| 900150000 | Instanced Dungeon Test2 | Test_Basic_Mj | 0 | 0 |  |
| 900190000 | Tag Match Test Level | Test_TagMatch | 256 | 1 |  |
| 900200000 | TestTimeAttackInstancedDungeon_02 | test_timeattack | 256 | 0 |  |

## Suggested order

1. **PvE dungeons and raids**, the project's first scope. Start with a small one on one floor to see what the generator does with an interior, then the large ones. Build only the
   id marked "builds", and copy the file for the ids that share it.
2. The special instances, then the arenas, which are flat and open and so the easiest, then the warfronts, which are the open ground the roadmap leaves to the RvR phase.
3. Housing and tests last, or never: a house is a room, and the tests have nothing to walk on.

## Open questions

1. Which of the categories is wanted at all, and are the hard modes (Occupied Rentus Base, Anguished Dragon Lord's Refuge) to be served by the same mesh as their normal mode? The data says they can.
2. Several instances state `world_size` of 3072 for a space that is plainly smaller (Sliver of Darkness, Space of Oblivion). The grid is taken from it, so those cost the memory of a full open-world map for a
   few hundred metres of floor. Worth measuring before the batch.
