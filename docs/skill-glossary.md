# Skill glossary

The French names the guides and the testers use, and the skill group each one is in the data. The server's data carries only the English client names,
so a French name in a report has to be looked up somewhere, and until now that meant asking. `tools/skill-lookup.ps1 -Name` searches this table as well
as the data, and prints the groups it finds here.

A name a tester uses that is not the client's (a nickname, a misspelling) goes in too, saying so. To add a row: the French name as the player wrote it,
the client name, the group from `skill-lookup.ps1`, the class. Groups are stable across ranks, which is why they are the key.

| French | Client name | Group | Class |
|---|---|---|---|
| Accélération magique | Word of Quickness | `CH_ZEST` | Chanter |
| Bénédiction de la pierre | Blessing of Rock | `CH_BLESSPROTECT` | Chanter |
| Bénédiction du rocher | Blessing of Guardianship | `CL_BLESSOFROCK` | Chanter |
| Frappe de parade | Parrying Strike | `CH_PARRYINGSTRIKE` | Chanter |
| Inarrêtable | Unstoppable | `CH_FRONTALATTACK` | Chanter |
| L'aile du martinet | Swiftwing | `CH_CHANT_IMPROVEDFLY` | Chanter |
| Le mot du vent | Word of Wind | `CH_PROVIDENCE` | Chanter |
| Lumière de guérison | Healing Light | `CL_HEAL` | Chanter |
| Lumière de résurrection | Light of Resurrection | `CL_REVIVE` | Chanter |
| Magie de la récupération | Recovery Spell | `CH_STIGMA_RECOVERWORD` | Chanter |
| Parade parfaite | Perfect Parry | `CH_CROSSPARRY` | Chanter |
| Parole d'éveil | Ascension Spell | `CH_IMPROVEDPOWERATTACK` | Chanter |
| Prière de protection | Protectorate's Prayer | `CH_BLESSOFROCK` | Chanter |
| Promesse de la terre | Promise of Earth | `CH_IMBUEEARTH` | Chanter |
| Protection divine | Winter Circle | `CH_ABYSSGLORIA` | Chanter |
| Quartier plus sûr | Protective Ward | `CH_PROTECTSELF` | Chanter |
| Rayonnement de l'endurance | Energy Discharge | `CH_RENEWEDVITALITY` | Chanter |
| Rideau divin | Block Curtain | `CH_ANGELICWALL` | Chanter |
| Récupération de l'endurance | Stamina Restoration | `CH_CHAKRA` | Chanter |
| Sort de rage | Rage Spell | `CH_NIGHTWISH` | Chanter |
| Sort de renaissance | Word of Revival | `CH_REJUVENATION` | Chanter |
| Acquittement | Acquittal | `PR_HOLYGRAIL` | Cleric |
| Amplification | Amplification | `PR_PREPAREHOLYWAR` | Cleric |
| Bouclier béni | Blessed Shield | `PR_BLESSEDSHIELD` | Cleric |
| Bouclier de vide | Life Curtain | `PR_CLOAKOFREMEDY` | Cleric |
| Béguin | Smite | `CL_SMITE` | Cleric |
| Caisse claire Aero | Aero Snare | `PR_HARSHPAIN` | Cleric |
| Châtiment | Chastise | `PR_REPENTANCE` | Cleric |
| Colère de la terre | Earth's Wrath | `PR_PURGATORY` | Cleric |
| Contrainte | Root | `PR_ROOT` | Cleric |
| Cure radieuse | Healing Grace | `PR_GREATHEAL` | Cleric |
| Embrasement incandescent | Infernal Blaze | `CL_SONICERUPTION` | Cleric |
| Explosion de puissance | Land's Bargain | `PR_HOLYEXPLOSION` | Cleric |
| Flashbolt | Flashbolt | `PR_LIGHTNINGSHOCK` | Cleric |
| Grève sacrée | Hallowed Strike | `CL_HALLOWSWING` | Cleric |
| Invoquer le serviteur sacré | Summon Holy Servant | `PR_HOLYSERVENT` | Cleric |
| L'éclair de la reprise | Flash of Recovery | `PR_FIRSTAID` | Cleric |
| La lumière du rétablissement | Light of Recovery | `PR_EMERGENTHEAL` | Cleric |
| La sagesse des sages | Sage's Wisdom | `PR_SAGESWISDOM` | Cleric |
| La terre qui punit | Punishing Earth | `PR_POWERSMITE` | Cleric |
| Le salut | Salvation | `PR_SECRETPRECEPT` | Cleric |
| Le toucher divin | Divine Touch | `PR_DIVINETOUCH` | Cleric |
| Linceul immortel | Immortal Shroud | `PR_INVINSIBLEWALL` | Cleric |
| Loci de résurrection | Resurrection Loci | `PR_CALLRESURRECTION` | Cleric |
| Lumière de rajeunissement | Light of Rejuvenation | `PR_RAPIDREGENERATION` | Cleric |
| Main de la réincarnation | Hand of Reincarnation | `PR_REVIVEHAND` | Cleric |
| Mot de destruction | Word of Destruction | `PR_GODSVOICE` | Cleric |
| Nettoyer | Cleanse | `PR_PURIFY` | Cleric |
| Noble Grace | same | `PR_HEALINGGRACE` | Cleric |
| Prière de concentration | Prayer of Focus | `PR_FOCUSCASTING` | Cleric |
| Pénitence | Penance | `PR_MEDITATION` | Cleric |
| Reverse Condition | same | `PR_REVERSECONDITION` | Cleric |
| Saving Grace | same | `PR_RESTORELIFE` | Cleric |
| Splendeur curative | Healing Splendor | `PR_HOLYLIGHTWAVE` | Cleric |
| Splendeur de la purification | Splendor of Purification | `PR_MASSDISPEL` | Cleric |
| Splendeur de la récupération | Splendor of Recovery | `PR_MASSEMERGENTHEAL` | Cleric |
| Splendeur du vol | Splendor of Flight | `PR_CIRCLEOFWINGS` | Cleric |
| Stabilité | Stability | `PR_STIGMA_SUGGESTION` | Cleric |
| Sympathetic Heal | same | `PR_BINDINGHEAL` | Cleric |
| Un marteau étonnant | Slowing Hammer | `PR_RAISEREPENTANCE` | Cleric |
| Une protection brillante | Brilliant Protection | `PR_HOLYCIRCLE` | Cleric |
| Vent de guérison | Healing Wind | `PR_MASSHEAL` | Cleric |
| Vent violent | Slashing Wind | `PR_DIVINEBOLT` | Cleric |
| Éclair de guérison | Flash of Healing | `PR_HOLYLIGHT` | Cleric |
| Étincelle divine | Divine Spark | `PR_DIVINESPARK` | Cleric |
| Cacher | Stealth | `SC_HIDE` | Ranger |
| Chuchotement apaisant | Calming Whisper | `AS_BREEZEOFCALM` | Ranger |
| Dévotion | Devotion | `SC_INVOKEPOWER` | Ranger |
| Esquive | Dodging | `RA_HAWKEYE` | Ranger |
| Flèche de Manille | Shackle Arrow | `RA_ROOTARROW` | Ranger |
| Flèche de saisie | Seizure Arrow | `RA_APPROACHSHOT` | Ranger |
| Flèche de sommeil | Sleep Arrow | `RA_PARALYZEARROW` | Ranger |
| Fureur bestiale | Bestial Fury | `RA_STIGMA_TIGEREYE` | Ranger |
| Fusil de chasse Manaleech | Manaleech Shot | `RA_DRAINSHOT` | Ranger |
| Guérir les ailes | Heal Wings | `RA_RESTOREWING` | Ranger |
| Les doigts agiles | Nimble Fingers | `RA_QUICKSETTING` | Ranger |
| Renforcement de l'œil | Strong Shots | `RA_SNAKEEYE` | Ranger |
| Retraite tactique | Tactical Retreat | `SC_SPIRITOFGALE` | Ranger |
| Souffle de la nature | Breath of Nature | `RA_BREATHOFNATURE` | Ranger |
| Sprint instantané | Feint | `RA_INSTANTSPRINT` | Ranger |
| Taillage en retrait | Retreating Slash | `RA_BACKDASHSTAB` | Ranger |
| Tempête de flèches | Arrow Storm | `RA_ARROWSTORM` | Ranger |
| Tir d'enchevêtrement | Entangling Shot | `RA_ENTANGLESHOT` | Ranger |
| Tir silencieux | Silence Arrow | `RA_SILENTARROW` | Ranger |
| Transformation | Transformation: White Tiger / Krall / Mau (twice) | `RA_WHITETIGER`, `RA_KRALLSCOUT`, `RA_SABAGEROAR`, `RA_L_SABAGEROAR` | Ranger |
| Viser | Aiming | `RA_EAGLEEYE` | Ranger |
| Vitesse du vent | Speed of the Wind | `RA_PANTHERMOVE` | Ranger |
| Évasion ciblée | Focused Evasion | `SC_WHISPEROFWIND` | Ranger |
| Robe de flammes | Robe of Flame | `WI_FLAMESEAL` | Sorcerer |
| Robe de froid | Robe of Cold | `WI_FROZENSEAL` | Sorcerer |
| Armure empyréenne | Empyrean Armor | `KN_STONEBODY` | Templar |
| Capture | Aether Leash | `KN_STUNNINGSNACHER` | Templar |
| Dissipation de choc | Remove Shock I | `ALL_SHOCKREFLECT` | Templar |
| Défi (a tester's word for Provoquer) | Taunt | `WA_PROVOKE` | Templar |
| Dévotion inébranlable | Unwavering Devotion | `WA_STEADINESS` | Templar |
| Effet de l'esprit têtu | Stubborn Spirit | `KN_MOVINGSTANCE` | Templar |
| Garde du corps | Bodyguard | `KN_GRANDPROTECTION` | Templar |
| Incure wrath (a tester's spelling) | Incite Rage | `KN_HIGHPROVOKE` | Templar |
| Main de la guérison | Hand of Healing | `KN_DIVINEHAND` | Templar |
| Peau de fer | Iron Skin | `KN_IRONBODY` | Templar |
| Provoquer | Taunt | `WA_PROVOKE` | Templar |
| Rafraîchir l'esprit | Refresh Spirit | `KN_PROTECTPROUD` | Templar |
| Rugissement narquois | Provoking Roar | `KN_MASSIVEPROVOKE` | Templar |
| Saint Châtiment | Empyrean Chastisement | `KN_ABYSALJUDGEMENT` | Templar |

Still to confirm with the tester: "ricanement" in the feedback of 12 October, which may be Provoking Roar (`KN_MASSIVEPROVOKE`, "Rugissement narquois" in
the guide) or a skill of its own.
