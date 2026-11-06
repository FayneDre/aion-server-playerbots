# Patch notes

Written in French, for the testers who read them; [current.en.md](current.en.md) holds the same entries in English, for the launcher's "What's
new" and the public releases. Newest first. Each entry is headed by its version tag, `## vX.Y.Z-beta.N` for a test patch (the `patch-YYYY-MM-DD`
tags below are the ones made before), names the revision `//bot version` shows, and has its English twin: `tools/tag-patch.ps1` refuses a tag
otherwise. When this file nears 200 lines, the oldest entries move to `<year>-<month>.md`, and their English ones to `<year>-<month>.en.md`.

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

## patch-2026-11-03 — 03/11/2026

Révision `c512832`, celle que `//bot version` affiche. Le retour du 03/11 sur l'hôtel des ventes. **Ce patch reprend aussi l'entrée du 01/11 ci-dessous, qui n'a jamais eu son
étiquette** : tout ce qui y est écrit est dans ce patch. **Aucun SQL, aucun maillage à régénérer, aucun réglage qui change.** Un redémarrage suffit
(`.\tools\deploy.ps1 -Restart`).

**Ce qui change pour les joueurs et les testeurs**
- **Matériaux de récolte : prix selon le niveau et la qualité.** Un blanc vaut 1 500 au niveau 10 et 10 000 au niveau 50 (la droite entre les deux, prolongée
  au-dessus), et chaque qualité vaut trois fois la précédente : vert 30 000, bleu 90 000, **doré de niveau 50 : 270 000**. Les matériaux « bonus » suivent la même règle.
- **Matériaux de butin : plus de plafond à 100 000** (certains valent plusieurs millions). Le minimum de 1 000 kinah reste.
- **Stigmas : 20 fois le prix des données** (c'était 100).
- **L'équipement d'événement n'est plus vendu** : le client le marque « Équipement d'événement » (nom client en `event_`, nom serveur en `Bootleg`, par exemple
  *Bootleg Custodian's Divine Breeches*). C'étaient les « deux pièces d'armure mythiques » du retour, vendues 330 à 880 millions.
- **Tout le mythique est en vente** : armes, armures et accessoires, environ 2 300 modèles, liés ou non (les données lient presque tout le mythique, et l'hôtel
  ne vendait que l'échangeable, d'où les deux pièces). Les prix du 01/11 restent : armes de 500 M à 1 Md, autres pièces de 200 à 400 M.
- **Les lots n'étaient pas retenus** : une annonce de plusieurs matériaux ou consommables était jugée trop bon marché (son prix, déjà unitaire, était divisé par la quantité)
  et retirée à chaque passe, puis remise. C'est corrigé ; les annonces au-dessus du prix actuel (les stigmas à 100 fois par exemple) sont retirées à la
  première passe et remplacées.

**Ce qui n'est pas réglé** (à ne pas signaler : c'est connu)
- **Un mythique lié acheté** : l'achat ne vérifie pas qu'il est échangeable ; à regarder en jeu qu'il arrive bien dans l'inventaire et qu'on peut l'équiper.
- **Les prix des mythiques liés** sont ceux du 01/11, non vérifiés en jeu.
- Les points « Ce qui n'est pas réglé » du 01/11 restent vrais, sauf le prix des matériaux de récolte, réglé ici.

**À tester**
- **L'hôtel** : plus aucune pièce « Bootleg » ; des armes, armures et accessoires mythiques en nombre ; un doré de niveau 50 autour de 270 000 ; les stigmas à
  20 fois ; des lots de matériaux qui restent en vente d'une passe à l'autre (une passe toutes les 15 minutes).
- Tout ce qui cloche : `//bot note <texte>` près du bot.

---

## patch-2026-11-01 — 01/11/2026 (jamais étiqueté, repris par patch-2026-11-03)

Révision `3a6a44d`, celle que `//bot version` affiche. Les retours du 01/11 : l'esprit du spiritualiste, un bot de niveau 28 chez les monstres de niveau 34,
les bots coincés contre un comptoir ou une barrière, un bot au repos avec les ailes dehors, et le retour d'un testeur sur l'hôtel des ventes. **Aucun SQL,
aucun maillage à régénérer, aucun réglage qui change.** Un redémarrage suffit (`.\tools\deploy.ps1 -Restart`).

**Ce qui change pour les joueurs et les testeurs**
- **L'hôtel des ventes, nettoyé et repris sur les prix.** Les annonces déjà en place qui ne respectent pas les règles ci-dessous sont retirées à la première
  passe (environ 220 par race), puis remplacées au bon prix.
  - **Ne sont plus vendus** : les objets de test (`ExtractionControlTest`, `Pvp_..._Test_A`, `Test-T-Shirt`, le mot « test » est maintenant lu aussi collé en
    majuscules ou entre tirets et soulignés), les « Random Drop », les « Supremo's Special », les objets de la boutique cash, tout objet au prix de remplissage
    de 5 kinah ou moins, les coffres et paquets qui s'ouvrent en d'autres objets, les objets de quête de collection, tous les livres de compétence (le 4.8 les
    apprend seul), les pierres d'enchantement, ce que les PNJ vendent sans limite (schémas de base, consommables de craft, suppléments de sertissage) et les
    parchemins qu'aucune recette ne fabrique. Les versions 30 min et 1 h des parchemins ne sont pas revenues : le courtier ne liste rien qui expire.
  - **Prix** : équipement au-dessus du niveau 60 **dix fois** plus cher ; **armes mythiques de 500 M à 1 Md**, autres pièces mythiques de 200 à 400 M ;
    stigmas **cent fois** plus chers ; matériaux de récolte et de butin **jamais sous 1 000 kinah ni sur 100 000** pièce ; potions **au moins deux fois les
    points qu'elles rendent** (vie et mana) quand aucun PNJ ne les vend ; nourriture à 50 000 dès le niveau 60 et 100 000 dès le niveau 70.
- **L'esprit du spiritualiste combat.** Il restait collé à son maître au lieu de frapper (il s'éloignait de 5 m, faisait demi-tour, recommençait) ; le combat
  passe maintenant avant le suivi, et sa marche ne saccade plus quand son maître court.
- **Les bots ne se coincent plus contre un comptoir ou une barrière puis disparaissent.** Un bot qui n'avance plus depuis 2 secondes saute par-dessus (3,5 m
  devant lui, deux fois au plus) avant d'abandonner comme avant.
- **Un bot de niveau 28 n'ira plus vendre à travers un pays de niveau 34.** Une boutique dont la ligne droite traverse un terrain de chasse de plus de quatre
  niveaux au-dessus du bot n'est plus visitée.
- **Un bot qui s'assoit avec les ailes dehors les replie** (une ligne de journal « sits with its wings out » dit dans quel état il était).

**Ce qui n'est pas réglé** (à ne pas signaler : c'est connu)
- **L'esprit qui semble vider la vie de son maître au repos** : pas expliqué, il faut relever les compétences qu'il lance.
- **Les ailes dehors** : la cause n'est pas démontrée, le correctif ne joue qu'à l'assise ; un bot debout, ailes dehors, n'est pas couvert.
- **Le saut** vise l'arrivée du trajet et non le prochain point : un bot qui contourne un obstacle peut être posé du mauvais côté d'un muret bas.
- **L'hôtel** : le prix des matériaux ne lit pas le niveau de compétence de la récolte, seulement la rareté ; les parchemins de 30 min et 1 h manquent.
- **La jetée de Pandaemonium** : les trois échecs de `check-golden-routes.ps1` sont attendus (la rampe à la main reste à poser).

**À tester**
- **L'hôtel** : reparcourir le courtier (armes mythiques, stigmas, potions, nourriture, onglets Craft) : plus d'objets de test ni de cash shop, des prix dans
  les fourchettes ci-dessus.
- **Un spiritualiste** : l'esprit attaque avec son animation et suit sans saccade.
- **Alsig** : le comptoir du vendeur et la barrière où deux notes ont été prises (2479 1799 et 2481 1781) ; chercher « hops over what holds it » dans le journal.
- **Morheim** : plus de bot de niveau 28 mort sur la route de la boutique de 2672 2501.
- Tout ce qui cloche : `//bot note <texte>` près du bot.

---

Older entries: [patch-notes-2026-10.md](2026-10.md).
