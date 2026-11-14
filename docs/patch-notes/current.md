# Patch notes

Written in French, for the testers who read them; [current.en.md](current.en.md) holds the same entries in English, for the launcher's "What's
new" and the public releases. Newest first. Each entry is headed by its version tag, `## vX.Y.Z-beta.N` for a test patch (the `patch-YYYY-MM-DD`
tags below are the ones made before), and has its English twin: `tools/tag-patch.ps1` refuses a tag otherwise. An entry names no revision:
the published notes get, as their first line, the tagged commit's, which is the one `//bot version` shows on a server installed from the
bundle (the entries up to v0.1.0-beta.4 named the server deployed here, which only it shows). When this file nears 200 lines, the oldest entries move to `<year>-<month>.md`, and their English ones to `<year>-<month>.en.md`.

---

## v0.1.0-beta.8 — 15/11/2026

Il va avec le launcher v0.3.0-beta.12. **Pas de SQL, pas de navmesh à reconstruire.** **Un fichier de réglages par défaut change**,
`game-server/config/main/playerbot.properties` (la nouvelle option ci-dessous) ; le launcher le remplace s'il n'a pas été modifié,
sinon il pose la nouvelle version à côté.

**Ce qui change pour les joueurs et les testeurs**
- **Les bots cessent d'arriver quand la RAM du PC est pleine** : en plus de sa propre mémoire, le serveur regarde la RAM de tout
  l'ordinateur, le jeu et les autres programmes compris (le chiffre du gestionnaire des tâches). Au-delà du seuil, 90 % par défaut, plus
  aucun bot n'arrive ; ceux qui sont là restent, et les arrivées reprennent 5 points en dessous. La page Bots du launcher v0.3.0-beta.12
  l'active ou non et règle le seuil (`gameserver.playerbot.guard.machine.enable` et `.hold`). `//bot pool` et le cadre « Population
  en ce moment » du launcher affichent la RAM du PC utilisée.

**À tester**
- Sur un PC où le jeu et le serveur prennent beaucoup de RAM : voir le cadre Population dire « en pause » au-delà du seuil, et les bots
  revenir en le montant ou en décochant la case.

---

## v0.1.0-beta.7 — 14/11/2026

Il va avec le launcher v0.3.0-beta.11. **Pas de SQL, pas de navmesh à reconstruire.** **Un fichier de réglages par défaut change**,
`game-server/config/administration/admin.properties` (la nouvelle option ci-dessous) ; le launcher le remplace s'il n'a pas été modifié,
sinon il pose la nouvelle version à côté.

**Ce qui change pour les joueurs et les testeurs**
- **Le compteur de combat marche avec un compte administrateur** : il disait « The server does not recognise this character », parce que le
  jeu lui donnait le nom avec son tag »Admin«.
- **Les modes de maître du jeu depuis le launcher** : la page Compte du launcher v0.3.0-beta.11 bascule, pour un compte administrateur
  dont le personnage est en jeu, Invulnérable, Invisible, Laissé tranquille, Voir les cachés, Sans temps de recharge et Voler partout, par
  les commandes de maître du jeu elles-mêmes.
- **Le tag »Admin« peut être retiré** : `gameserver.administration.customtags.enable = false` (ou la case du launcher) affiche les noms
  des administrateurs sans leur tag.

**À tester**
- Avec un compte administrateur : ouvrir le compteur de combat en jeu, puis basculer chaque mode depuis le launcher et le voir en jeu.

---

## v0.1.0-beta.6 — 14/11/2026

Il va avec le launcher v0.3.0-beta.8. **Pas de SQL, pas de navmesh à reconstruire, aucun fichier de réglages ne change.** Le serveur de jeu
est le même que celui de la beta.5 : seule la base de données qu'une installation à partir de rien télécharge change.

**Ce qui change pour les joueurs et les testeurs**
- **L'installation à partir de rien marche sur un Windows neuf** : la base de données MySQL ne démarrait pas sur un ordinateur qui n'avait
  jamais installé le « Visual C++ Redistributable » de Microsoft (« La base de données n'a pas pu être créée »). Elle embarque maintenant les
  quatre fichiers de Microsoft qu'il lui faut. Une installation existante n'est pas concernée.

**À tester**
- Avec le launcher v0.3.0-beta.8 dans Windows Sandbox (8 Go) : « Installer le serveur », se connecter en jeu et voir des bots, puis
  « Désinstaller le serveur » ; la sauvegarde des personnages doit rester dans `Documents\Echoes of Atreia\backups`.

---

## v0.1.0-beta.5 — 14/11/2026

Il va avec le launcher v0.3.0-beta.6, qui sait l'installer sur un ordinateur qui n'a que le client du jeu. **Pas de SQL, pas de navmesh à
reconstruire.** **Neuf fichiers de réglages par défaut changent** (dont `game-server/config/main/playerbot.properties` et `custom.properties`,
options ci-dessous) ; le launcher remplace chacun s'il n'a pas été modifié, sinon il pose la nouvelle version à côté.

**Ce qui change pour les joueurs et les testeurs**
- **Une installation à partir de rien** : la version porte son propre Java (55 Mo) et sa propre base de données MySQL (29 Mo), avec les
  scripts qui la créent. Rien n'est installé dans Windows : ni Java, ni service MySQL, ni droits d'administrateur. Une installation neuve peuple
  les 24 cartes mesurées (les deux races, les capitales, les terres disputées) au lieu du seul Poeta.
- **Le serveur tient dans moins de mémoire** : les cartes de navigation de toutes les cartes ouvertes partagent un budget (12 % de la mémoire,
  `gameserver.playerbot.navmesh.tile_budget`), et un garde retient le réveil des bots quand la mémoire ou le calcul des trajets manque
  (`gameserver.playerbot.guard.*`). Pendant qu'il retient, des bots des cartes où personne ne joue sont échangés contre des arrivées là où un
  joueur se trouve : il y a du monde autour de toi même sur une petite machine.
- **Les bots ont des potions** : à leur création et quand ils changent d'équipement, à partir du niveau 10, ils reçoivent des fioles de vie et
  de mana, les meilleures que vend un marchand.
- **L'émulateur mis à jour** (49 changements de l'équipe d'origine, jusqu'au 12 novembre) : la météo et le changement de canal comme sur le
  jeu officiel, l'identification qui échouait après le premier objet, le vol libre qui s'arrête en planant hors d'une zone de vol, le minuteur
  des duels, les chutes depuis les ascenseurs, les quêtes où un PNJ suit, le Défi éternel rééquilibré, les plumes de nouveau sélectionnables,
  des messages d'échange raté, et de nombreuses protections contre des paquets malformés. Une créature qui va mourir d'un coup en vol est
  désormais traitée comme morte dès la fin de l'attaque, comme sur le jeu officiel (`gameserver.combat.death_at_hittime`, `false` par défaut).

**À tester**
- Avec le launcher v0.3.0-beta.6 sur une machine sans serveur (ou Windows Sandbox) : « Installer le serveur » sur l'accueil, jusqu'à se
  connecter en jeu et voir des bots.
- Sur une installation existante : la mise à jour depuis beta.4, puis une heure de jeu ; les bots de niveau 10 et plus boivent leurs potions.

---

## v0.1.0-beta.4 — 08/11/2026

Révision `0d967aa`, celle que `//bot version` affiche. Il va avec les addons v0.1.0-beta.4 (le compteur de combat), qu'il sert. **Pas de SQL,
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

