# Patch notes

Written in French, for the testers who read them; [current.en.md](current.en.md) holds the same entries in English, for the launcher's "What's
new" and the public releases. Newest first. Each entry is headed by its version tag, `## vX.Y.Z-beta.N` for a test patch (the `patch-YYYY-MM-DD`
tags below are the ones made before), names the revision `//bot version` shows, and has its English twin: `tools/tag-patch.ps1` refuses a tag
otherwise. When this file nears 200 lines, the oldest entries move to `<year>-<month>.md`, and their English ones to `<year>-<month>.en.md`.

---

## v0.1.0-beta.4 — 08/11/2026

Révision `a494079`, celle que `//bot version` affiche. Il va avec les addons v0.1.0-beta.4 (le compteur de combat), qu'il sert. **Pas de SQL,
pas de navmesh à reconstruire.** **Un fichier de réglages par défaut change**, `game-server/config/main/playerbot.properties` (la nouvelle
option ci-dessous) ; le launcher le remplace s'il n'a pas été modifié, sinon il pose la nouvelle version à côté.

**Ce qui change pour les joueurs et les testeurs**
- **Le compteur de combat** : le serveur compte les dégâts et les soins de chaque joueur, par compétence (surplus de soin compris, les chiffres
  d'une invocation vont à son maître), et ceux des bots quand ils sont dans le groupe d'un joueur. Le compte est celui du groupe depuis sa
  dernière remise à zéro ; le « par seconde » ne compte que le temps en combat.
- **Les pages des addons** : le serveur les sert sur le port 7790 (`gameserver.playerbot.addon.port`, 0 pour n'en servir aucune). Une page ne
  montre un personnage qu'à une demande venant de l'adresse de sa connexion de jeu.

**À tester**
- Installer les addons v0.1.0-beta.4, puis en jeu ouvrir Combat Meter (menu du jeu, Additional Functions, ou `/dps`) et se battre, seul puis
  en groupe avec un bot : les dégâts, les soins, le détail par compétence d'un clic sur un nom, la remise à zéro.

---

## v0.1.0-beta.3 — 07/11/2026

Révision `9164482`, celle que `//bot version` affiche. Il va avec le launcher v0.3.0-beta.5, qui règle les taux premium. **Pas de SQL, pas de
navmesh à reconstruire.** **Un fichier de réglages par défaut change**, `login-server/config/network/network.properties` (la nouvelle option
ci-dessous) ; le launcher le remplace s'il n'a pas été modifié, sinon il pose la nouvelle version à côté.

**Ce qui change pour les joueurs et les testeurs**
- **Tous les comptes des joueurs sont premium** (`loginserver.accounts.minimum_membership = 1`) : les nouveaux comptes sont créés premium,
  les anciens passent premium au démarrage du login server. Les taux premium sont donc ceux des joueurs, et les taux normaux ceux des bots,
  qui n'ont pas de compte.
- **Un bot dans le groupe d'un joueur gagne aux taux du joueur** (le plus haut des joueurs du groupe), et non plus aux taux normaux.
- **L'expérience des instances de groupe** reste celle du jeu officiel quels que soient les taux : elle est maintenant divisée par le taux
  premium au lieu du taux normal.

**À tester**
- Changer un taux premium dans la page Réglages du launcher, redémarrer, et vérifier le gain en jeu, seul puis en groupe avec un bot.

---

## v0.1.0-beta.2 — 06/11/2026

Révision `c979e97` pour le code (celle du serveur déployé ici) ; le serveur installé par le launcher affiche dans `//bot version` le commit du
tag, qui n'ajoute que ces notes et les scripts de publication. **Premier patch livré tout compilé** : il va avec le launcher v0.3.0-beta.3, qui
l'installe lui-même, sans Maven ni compilation. **Pas de SQL, pas de navmesh à reconstruire**, aucun fichier de réglages par défaut ne change.

**Ce qui change pour les testeurs**
- **Le serveur s'installe depuis le launcher** : la pre-release porte le serveur compilé (`aion-server-v0.1.0-beta.2.zip`, 149 Mo), son
  manifeste et les cartes du navmesh (1,8 Go la première fois ; ensuite, seules les cartes reconstruites). Le launcher télécharge et vérifie
  tout pendant que les serveurs tournent, te montre ce qu'il va faire, puis les arrête, installe, met à jour la base et les relance. «
  Revenir à… » remet la version précédente.
- **Tes fichiers restent à toi** : un fichier de réglages par défaut que tu as modifié n'est pas remplacé, la nouvelle version est posée à côté
  (`<fichier>.new-v0.1.0-beta.2`) ; `mygs.properties`, les journaux et ce que ton serveur a écrit dans le navmesh ne sont jamais touchés.
- **La version du serveur** : `//bot version` affiche la version, la révision et le niveau d'API du launcher, et le launcher la demande au
  serveur pour dire s'il est trop ancien.
- **Un JDK 25 est nécessaire** (le serveur compile ses handlers à chaque démarrage) ; Maven et Git ne le sont plus.

**Ce qui n'est pas réglé** (connu, inutile de le signaler)
- **L'installation par le launcher** n'a été vérifiée qu'en tests et sur un bundle d'essai, pas encore sur ton installation.
- **Une base jamais passée par `apply-sql.ps1`** : ses instructions SQL sont notées comme déjà faites, pas rejouées. Si tu n'as jamais
  appliqué `game-server/sql/update.sql` à ta base, dis-le avant d'installer.

**À tester**
- **Avec le launcher v0.3.0-beta.3** : « Mettre à jour le serveur », installer, jouer, puis « Revenir au serveur compilé depuis ses sources »,
  et vérifier que le serveur démarre et que les bots se connectent après les deux.
- Quelque chose ne va pas : `//bot note <texte>` près du bot, ou une capture du panneau « Version du serveur ».

---

## v0.1.0-beta.1 — 06/11/2026

Révision `eb82601`, celle que `//bot version` affiche. **Premier patch sous forme de version** : les patchs de test s'appellent maintenant
`vX.Y.Z-beta.N`, sont publiés comme pre-releases sur GitHub et ont leurs notes en français et en anglais. Ce patch reprend tout ce qui est
entré depuis patch-2026-11-03. Il va avec le launcher v0.3.0-beta.1. **Aucun SQL, aucun maillage à régénérer.** **Un fichier de réglages par
défaut change**, `game-server/config/main/playerbot.properties` (les nouvelles options ci-dessous) : `.\tools\sync-install.ps1 -From
patch-2026-11-03` dit ce qui sera remplacé, la même commande avec `-Apply` le fait ; tes propres réglages vont dans `config/mygs.properties`.
Puis `.\tools\deploy.ps1 -Restart`.

**Ce qui change pour les joueurs et les testeurs**
- **La densité du monde a trois modes** (`gameserver.playerbot.density.mode`, et `density.percent`, 30 par défaut) :
  - `AUTO` (par défaut) suit les joueurs : toute la campagne d'une carte où quelqu'un joue (et cinq minutes après), un cinquième ailleurs, un
    vingtième quand personne n'est connecté ;
  - `FIXED` garde le pourcentage partout, que quelqu'un regarde ou non ;
  - `FLOOR` fait comme `AUTO`, sans jamais descendre sous le pourcentage.
  Les villages restent toujours pleins. `//reload config` l'applique en 30 secondes.
- **Les réglages des bots** (ceux de la page Bots du launcher), appliqués par `//reload config` :
  - l'expérience d'un bot, en pourcentage du taux officiel, quels que soient les taux des joueurs : seul, en groupe et en PvP (1, 1 et 100 par
    défaut). Un bot dans le groupe d'un joueur suit le taux du joueur ;
  - le PvP : la part des bots qui attaquent à vue, de ceux qui n'attaquent qu'un joueur affaibli ou plus faible, et des pacifiques (25, 25 et
    50), et l'écart de niveau (3 au-dessus, 6 en dessous) ;
  - le chat des bots, activé ou non.
- **Le canal d'administration du launcher** : recharger la configuration, lister les destinations (cartes du monde ouvert seulement) et
  téléporter un personnage.
- **Les recherches d'itinéraire** : une seule recherche attend par bot, la plus récente remplace l'ancienne et garde sa place. La file
  montait au-delà d'une minute d'attente (25 000 recherches à 100 %).
- **Le journal** : une ligne du directeur à chaque revue (réveillés, voulus, endormis, densité, file d'itinéraire) et une ligne de la file
  toutes les 10 secondes, au lieu d'une ligne par recherche lente.

**Ce qui n'est pas réglé** (à ne pas signaler : c'est connu)
- **La file d'itinéraire à pleine densité** n'est pas encore mesurée dans sa nouvelle version.
- **Après une session à forte densité**, le démarrage peut dépasser 10 minutes (environ 1 900 bots restaurés avant que le serveur s'annonce).
- **Les réglages des bots et la téléportation** passent leurs tests mais n'ont pas été vérifiés en jeu.

**À tester**
- **Avec le launcher v0.3.0-beta.1** : la page Bots montre les réglages du serveur, la liste de téléportation est remplie.
- **La densité** : `FIXED` à 30, puis `//reload config` ; la campagne doit rester peuplée quand on change de carte.
- Tout ce qui cloche : `//bot note <texte>` près du bot.

---

Older entries: [2026-11.md](2026-11.md), [2026-10.md](2026-10.md).
