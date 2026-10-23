# Skill glossary

The French names the guides and the testers use, and the skill group each one is in the data. The server's data carries only the English client names,
so a French name in a report has to be looked up somewhere, and until now that meant asking. `tools/skill-lookup.ps1 -Name` searches this table as well
as the data, and prints the groups it finds here.

A name a tester uses that is not the client's (a nickname, a misspelling) goes in too, saying so. To add a row: the French name as the player wrote it,
the client name, the group from `skill-lookup.ps1`, the class. Groups are stable across ranks, which is why they are the key.

| French | Client name | Group | Class |
|---|---|---|---|
| Absorption de la fureur | Absorbing Fury | `FI_SEISMICDRAIN` | Gladiator |
| Accélération magique | Word of Quickness | `CH_ZEST` | Chanter |
| Acquittement | Acquittal | `PR_HOLYGRAIL` | Cleric |
| Amplification | Amplification | `PR_PREPAREHOLYWAR` | Cleric |
| Armure d'Attrition | Armor of Attrition | `FI_FORTITUDE` | Gladiator |
| Armure empyréenne | Empyrean Armor | `KN_STONEBODY` | Templar |
| Béguin | Smite | `CL_SMITE` | Cleric |
| Bénédiction de la pierre | Blessing of Rock | `CH_BLESSPROTECT` | Chanter |
| Bénédiction du rocher | Blessing of Guardianship | `CL_BLESSOFROCK` | Chanter |
| Bouclier béni | Blessed Shield | `PR_BLESSEDSHIELD` | Cleric |
| Bouclier de vide | Life Curtain | `PR_CLOAKOFREMEDY` | Cleric |
| Cacher | Stealth | `SC_HIDE` | Ranger |
| Caisse claire Aero | Aero Snare | `PR_HARSHPAIN` | Cleric |
| Capture | Aether Leash | `KN_STUNNINGSNACHER` | Templar |
| Charge | Charge | `WA_DASH` | Gladiator |
| Châtiment | Chastise | `PR_REPENTANCE` | Cleric |
| Chuchotement apaisant | Calming Whisper | `AS_BREEZEOFCALM` | Ranger |
| Clivage | Cleave | `FI_WINGBLADE` | Gladiator |
| Colère | Ferocity | `FI_FIGHTERRAGE` | Gladiator |
| Colère de la terre | Earth's Wrath | `PR_PURGATORY` | Cleric |
| Contrainte | Root | `PR_ROOT` | Cleric |
| Contre sangsue | Counter Leech | `FI_COUNTERDRAIN` | Gladiator |
| Coup d'affaiblissement | Severe Weakening Blow | `FI_ENFEEBLEHIT` | Gladiator |
| Coup d'éclat | Crashing Blow | `FI_DROPIMPACT` | Gladiator |
| Coup d'écrasement | Crushing Blow | `FI_DESTRUCTBLOW` | Gladiator |
| Coup de poing | Body Slice | `FI_BODYSMASH` | Gladiator |
| Coupe de précision sévère | Severe Precision Cut | `FI_CHARGINGSHOCK` | Gladiator |
| Coupure paralysante | Crippling Cut | `FI_CRIPPLINGCUT` | Gladiator |
| Cure radieuse | Healing Grace | `PR_GREATHEAL` | Cleric |
| Défi (a tester's word for Provoquer) | Taunt | `WA_PROVOKE` | Templar |
| Dévotion | Devotion | `SC_INVOKEPOWER` | Ranger |
| Dévotion inébranlable | Unwavering Devotion | `WA_STEADINESS` | Templar |
| Dissipation de choc | Remove Shock I | `ALL_SHOCKREFLECT` | Templar |
| Éclair de guérison | Flash of Healing | `PR_HOLYLIGHT` | Cleric |
| Effet de l'esprit têtu | Stubborn Spirit | `KN_MOVINGSTANCE` | Templar |
| Effet sismique | Seismic Billow | `FI_SEISMICBILLOW` | Gladiator |
| Embrasement incandescent | Infernal Blaze | `CL_SONICERUPTION` | Cleric |
| Épée drainante | Draining Sword | `FI_DRAINSWORD` | Gladiator |
| Esquive | Dodging | `RA_HAWKEYE` | Ranger |
| Étincelle divine | Divine Spark | `PR_DIVINESPARK` | Cleric |
| Évasion ciblée | Focused Evasion | `SC_WHISPEROFWIND` | Ranger |
| Explosion de colère | Wrathful Explosion | `FI_RAGEBURST` | Gladiator |
| Explosion de puissance | Land's Bargain | `PR_HOLYEXPLOSION` | Cleric |
| Explosion de rage | Explosion of Rage | `FI_ABYSALRAGE` | Gladiator |
| Explosion énergétique | Energy Impact | `FI_CONSECUTIVEHIT` | Gladiator |
| Flashbolt | Flashbolt | `PR_LIGHTNINGSHOCK` | Cleric |
| Flèche de Manille | Shackle Arrow | `RA_ROOTARROW` | Ranger |
| Flèche de saisie | Seizure Arrow | `RA_APPROACHSHOT` | Ranger |
| Flèche de sommeil | Sleep Arrow | `RA_PARALYZEARROW` | Ranger |
| Flèche vertueuse | Righteous Cleave | `FI_STORMBLADE` | Gladiator |
| Frappe courroucée | Wrathful Strike | `FI_ROBUSTCRASH` | Gladiator |
| Frappe de parade | Parrying Strike | `CH_PARRYINGSTRIKE` | Chanter |
| Frappe féroce | Ferocious Strike | `WA_ROBUSTHIT` | Gladiator |
| Frappe répétée sur le corps | Body Combo | `FI_TRIPLESLASH` | Gladiator |
| Fureur bestiale | Bestial Fury | `RA_STIGMA_TIGEREYE` | Ranger |
| Fureur daevique | Daevic Fury | `FI_RAMPAGEMODE` | Gladiator |
| Fusil de chasse Manaleech | Manaleech Shot | `RA_DRAINSHOT` | Ranger |
| Garde du corps | Bodyguard | `KN_GRANDPROTECTION` | Templar |
| Grand clivage | Great Cleave | `FI_GUSTBLADE` | Gladiator |
| Grève sacrée | Hallowed Strike | `CL_HALLOWSWING` | Cleric |
| Guérir les ailes | Heal Wings | `RA_RESTOREWING` | Ranger |
| Inarrêtable | Unstoppable | `CH_FRONTALATTACK` | Chanter |
| Incure wrath (a tester's spelling) | Incite Rage | `KN_HIGHPROVOKE` | Templar |
| Invoquer le serviteur sacré | Summon Holy Servant | `PR_HOLYSERVENT` | Cleric |
| L'aile du martinet | Swiftwing | `CH_CHANT_IMPROVEDFLY` | Chanter |
| L'attaque finale | Final Strike | `FI_CRUELBLOW` | Gladiator |
| L'éclair de la reprise | Flash of Recovery | `PR_FIRSTAID` | Cleric |
| La grève de l'assurance | Sure Strike | `FI_BURSERKLANCE` | Gladiator |
| La grève imprudente | Reckless Strike | `FI_ROBUSTIMPULSE` | Gladiator |
| La lumière du rétablissement | Light of Recovery | `PR_EMERGENTHEAL` | Cleric |
| La menace de Zikel | Empyrean Blessing | `FI_CRUSADE` | Gladiator |
| La rage | Rage | `WA_RAGE` | Gladiator |
| La rage de l'ombre | Berserking | `FI_BERSERKSTANCE` | Gladiator |
| La sagesse des sages | Sage's Wisdom | `PR_SAGESWISDOM` | Cleric |
| La terre qui punit | Punishing Earth | `PR_POWERSMITE` | Cleric |
| Lame d'incitation | Mocking Blast | `FI_SLASHIGFORCE` | Gladiator |
| Le choc des corps | Body Smash | `WA_POWERSLASH` | Gladiator |
| Le mot du vent | Word of Wind | `CH_PROVIDENCE` | Chanter |
| Le salut | Salvation | `PR_SECRETPRECEPT` | Cleric |
| Le toucher divin | Divine Touch | `PR_DIVINETOUCH` | Cleric |
| Les doigts agiles | Nimble Fingers | `RA_QUICKSETTING` | Ranger |
| Linceul immortel | Immortal Shroud | `PR_INVINSIBLEWALL` | Cleric |
| Loci de résurrection | Resurrection Loci | `PR_CALLRESURRECTION` | Cleric |
| Lumière de guérison | Healing Light | `CL_HEAL` | Chanter |
| Lumière de rajeunissement | Light of Rejuvenation | `PR_RAPIDREGENERATION` | Cleric |
| Lumière de résurrection | Light of Resurrection | `CL_REVIVE` | Chanter |
| Magie de la récupération | Recovery Spell | `CH_STIGMA_RECOVERWORD` | Chanter |
| Main de la guérison | Hand of Healing | `KN_DIVINEHAND` | Templar |
| Main de la réincarnation | Hand of Reincarnation | `PR_REVIVEHAND` | Cleric |
| Mot de destruction | Word of Destruction | `PR_GODSVOICE` | Cleric |
| Mur d'acier | Wall of Steel | `FI_PARRYFOCUS` | Gladiator |
| Nettoyer | Cleanse | `PR_PURIFY` | Cleric |
| Noble Grace | same | `PR_HEALINGGRACE` | Cleric |
| Onde d'absorption | Roiling Hack | `FI_DRAINWAVE` | Gladiator |
| Onde de pression | Pressure Wave | `FI_SEISMICIMPACT` | Gladiator |
| Parade parfaite | Perfect Parry | `CH_CROSSPARRY` | Chanter |
| Parole d'éveil | Ascension Spell | `CH_IMPROVEDPOWERATTACK` | Chanter |
| Peau de fer | Iron Skin | `KN_IRONBODY` | Templar |
| Pénitence | Penance | `PR_MEDITATION` | Cleric |
| Perçage Rupture | Piercing Rupture | `FI_STIGMA_SHOCKBURST` | Gladiator |
| Préparation au combat | Slaughter | `FI_BLADEMODE` | Gladiator |
| Préparation de la défense | Defense Preparation | `FI_DEFENSEMODE` | Gladiator |
| Prière de concentration | Prayer of Focus | `PR_FOCUSCASTING` | Cleric |
| Prière de protection | Protectorate's Prayer | `CH_BLESSOFROCK` | Chanter |
| Promesse de la terre | Promise of Earth | `CH_IMBUEEARTH` | Chanter |
| Protection divine | Winter Circle | `CH_ABYSSGLORIA` | Chanter |
| Provoquer | Taunt | `WA_PROVOKE` | Templar |
| Quartier plus sûr | Protective Ward | `CH_PROTECTSELF` | Chanter |
| Rafraîchir l'esprit | Refresh Spirit | `KN_PROTECTPROUD` | Templar |
| Raging Blow (coup de fouet) | Ferocious Chop | `FI_FLYINGSLASH` | Gladiator |
| Rayonnement de l'endurance | Energy Discharge | `CH_RENEWEDVITALITY` | Chanter |
| Récupération de l'endurance | Stamina Restoration | `CH_CHAKRA` | Chanter |
| Renforcement de l'œil | Strong Shots | `RA_SNAKEEYE` | Ranger |
| Renforcer les ailes | Strengthen Wings | `FI_ENERGYWING` | Gladiator |
| Retraite tactique | Tactical Retreat | `SC_SPIRITOFGALE` | Ranger |
| Reverse Condition | same | `PR_REVERSECONDITION` | Cleric |
| Rideau divin | Block Curtain | `CH_ANGELICWALL` | Chanter |
| Robe de flammes | Robe of Flame | `WI_FLAMESEAL` | Sorcerer |
| Robe de froid | Robe of Cold | `WI_FROZENSEAL` | Sorcerer |
| Rugissement narquois | Provoking Roar | `KN_MASSIVEPROVOKE` | Templar |
| Rupture | Rupture | `FI_ROBUSTSMASH` | Gladiator |
| Saint Châtiment | Empyrean Chastisement | `KN_ABYSALJUDGEMENT` | Templar |
| Saving Grace | same | `PR_RESTORELIFE` | Cleric |
| Second souffle | Second Wind | `FI_SURVIVORSTANCE` | Gladiator |
| Sort de rage | Rage Spell | `CH_NIGHTWISH` | Chanter |
| Sort de renaissance | Word of Revival | `CH_REJUVENATION` | Chanter |
| Souffle de drainage | Draining Blow | `FI_STIGMA_DRAINCUT` | Gladiator |
| Souffle de la nature | Breath of Nature | `RA_BREATHOFNATURE` | Ranger |
| Souffle robuste | Robust Blow | `WA_ROBUSTBLOW` | Gladiator |
| Splendeur curative | Healing Splendor | `PR_HOLYLIGHTWAVE` | Cleric |
| Splendeur de la purification | Splendor of Purification | `PR_MASSDISPEL` | Cleric |
| Splendeur de la récupération | Splendor of Recovery | `PR_MASSEMERGENTHEAL` | Cleric |
| Splendeur du vol | Splendor of Flight | `PR_CIRCLEOFWINGS` | Cleric |
| Sprint instantané | Feint | `RA_INSTANTSPRINT` | Ranger |
| Stabilité | Stability | `PR_STIGMA_SUGGESTION` | Cleric |
| Sympathetic Heal | same | `PR_BINDINGHEAL` | Cleric |
| Taillage en retrait | Retreating Slash | `RA_BACKDASHSTAB` | Ranger |
| Tempête de flèches | Arrow Storm | `RA_ARROWSTORM` | Ranger |
| Tir d'enchevêtrement | Entangling Shot | `RA_ENTANGLESHOT` | Ranger |
| Tir silencieux | Silence Arrow | `RA_SILENTARROW` | Ranger |
| Tranche de printemps | Springing Slice | `FI_JUMPINGCUT` | Gladiator |
| Transformation | Transformation: White Tiger / Krall / Mau (twice) | `RA_WHITETIGER`, `RA_KRALLSCOUT`, `RA_SABAGEROAR`, `RA_L_SABAGEROAR` | Ranger |
| Un marteau étonnant | Slowing Hammer | `PR_RAISEREPENTANCE` | Cleric |
| Une protection brillante | Brilliant Protection | `PR_HOLYCIRCLE` | Cleric |
| Vague de colère | Wrathful Wave | `FI_WHIRLWIND` | Gladiator |
| Vague de tremblement de terre | Earthquake Wave | `FI_HEAVYIMPACT` | Gladiator |
| Variation de la coupe du dragon | Whirling Advance | `FI_DRAGONSLASH` | Gladiator |
| Vent de guérison | Healing Wind | `PR_MASSHEAL` | Cleric |
| Vent violent | Slashing Wind | `PR_DIVINEBOLT` | Cleric |
| Verrouillage aérien | Aerial Lockdown | `FI_RAISINGIMPACT` | Gladiator |
| Viser | Aiming | `RA_EAGLEEYE` | Ranger |
| Vitesse du vent | Speed of the Wind | `RA_PANTHERMOVE` | Ranger |

Still to confirm with the tester: "ricanement" in the feedback of 12 October, which may be Provoking Roar (`KN_MASSIVEPROVOKE`, "Rugissement narquois" in
the guide) or a skill of its own.
