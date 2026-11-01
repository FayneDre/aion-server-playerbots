# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-11-01 — 01/11/2026

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

## patch-2026-10-31 — 31/10/2026

Révision `d840fec`, celle que `//bot version` affiche. **Un gros lot : 104 commits depuis le 28/10 (l'hôtel des ventes rempli par le serveur, le PvP des bots,
les planés pour le plaisir, beaucoup de navigation).** Aucun SQL à passer, aucun fichier de données qui change, **mais les maillages sont à régénérer si les vôtres datent d'avant le 31/10** (le générateur a changé : voir « À faire »). **Deux options
sont activées par défaut : le PvP des bots et l'hôtel des ventes** (voir « À faire » pour les couper).

**Ce qui change pour les joueurs et les testeurs**
- **L'hôtel des ventes n'est plus vide.** Deux personnages « maison » (`BotSellerElyos`, `BotSellerAsmo`, jamais connectés) y vendent 6 000 annonces par race,
  réparties par catégorie, qualité et niveau (surtout 20 à 55), à des prix autour du marché (± 15 %, jamais sous le plancher). **Plus d'objets de PNJ, de test,
  d'événement, de boutique ni de carte** : tout nom qui commence par un crochet, par « NPC » ou qui contient test, practice, dummy, event, etc. est écarté,
  et les annonces de ce genre déjà en place ont été retirées au redémarrage.
- **L'hôtel achète vos annonces** quand leur prix est dans la fourchette connue (au plus le haut de la fourchette + 5 %), **en 1 à 5 minutes**, sans jamais
  deviner le prix d'un objet : pas de prix connu, pas d'achat. Plafonds par jour : 200 M de kinah en tout, 30 M par vendeur, 50 M par type d'objet. L'objet
  acheté est détruit quand vous récupérez la vente. Vous touchez le kinah comme pour n'importe quelle vente.
- **PvP des bots.** Des bots de l'autre race peuvent vous attaquer (et attaquer vos bots) là où le jeu vous rend ennemis, par exemple sur Reshanta. Pas
  systématiquement : chaque bot a un tempérament fixe, **combattant (1 sur 4), opportuniste (1 sur 4, n'attaque qu'un joueur blessé ou de niveau
  inférieur) ou pacifique (1 sur 2, ne commence jamais un combat)**. Jamais plus de 3 niveaux au-dessus d'eux, jamais plus de 6 en dessous, et jamais plus de
  deux bots sur un joueur seul. Un bot attaqué par un joueur riposte, utilise une compétence défensive ou une potion, et **fuit sous 30 % de vie** (50 % à
  deux adversaires). Un bot de votre groupe vous aide quand un ennemi vous attaque. **Un joueur qui tue un bot reçoit les récompenses normales du jeu**
  (points d'abysse, primes, quêtes). Après un meurtre, le bot peut dire une phrase en Région.
- **Boutiques des bots** : de la nourriture, des parchemins de vitesse (5 minutes et 1 heure), des parchemins d'événement de 30 minutes (que le jeu
  n'autorise pas à l'échange, une exception pour les bots), des pierres 60 et 70 chez la plupart ; une page de 10 lignes ; **les biens rares (pierre
  toute-puissante, pierre d'évolution, sérum sacré) sont toujours dans une boutique de la carte**.
- **Le chat des bots** : des blagues et des échanges dans Région, des réactions (un bot meurt, passe un niveau, félicite), et **une option de langue, français
  ou anglais, pour tous les canaux** (`gameserver.playerbot.chat.language`, ou `//bot speech lang FR|EN` jusqu'au prochain démarrage).
- **Planés pour le plaisir** : un bot qui flâne près de vous, sur un rebord de 8 à 40 m, plane parfois vers le sol en dessous (une fois toutes les vingt
  minutes environ). Jamais en chasse, en groupe, sous 70 % de vie ni en ville.

**Ce qui change pour les bots**
- **Bots dans un mur ou sur un îlot** (Pandaemonium, la jetée) : un bot dont le chez-soi est un îlot de sol reçoit un autre chez-soi tout de suite ; un bot sur un
  sol que la marche ne joint pas à son chez-soi est replacé chez lui dès le deuxième refus, même sous les yeux d'un joueur.
- **Moins de morts inutiles** : les boutiques où trois bots sont morts en quinze minutes ne sont plus visitées (55 morts en une demi-heure vers une boutique de
  la forteresse de Morheim), et les lieux de mort sont évités par les routes pendant quinze minutes.
- **Longs trajets** : budgets de recherche doublés, trajets partiels marchés, terrains de chasse hors d'atteinte écartés.
- **La grotte de Morheim** marche (entrée, couloir, sortie) ; **un bot assis ne glisse plus** avant de courir.
- **Mesures de navigation (31/10), même fenêtre de 30 minutes, 13h25 puis 22h00** : plans abandonnés 317 → 201, routes refusées 551 → 306, morts 275 → 214,
  téléportations de secours 38 → 23. Ces chiffres varient d'une fenêtre à l'autre (jusqu'à 30 %) : à lire comme une tendance.
- **Nouvelles lignes de journal** : `Motion:` (une par minute : demi-tours des bots par cause), `PVP KILL` (une par meurtre qui touche un bot), « Broker house »
  (une par passe de l'hôtel et par achat).
- **Nouveaux outils** (pour qui lit le dépôt) : `NavmeshTool <carte> glidegain | ledges | steps | walkers`, les routes de référence (`tools/golden-routes.txt`,
  dont la jetée de Pandaemonium), le sol réellement marché (un point toutes les 0,5 s).

**Ce qui n'est pas réglé** (à ne pas signaler : c'est connu)
- **La jetée des dirigeables de Pandaemonium** : Toronil et Eriunik sont séparés du reste par une marche de 1 à 2 m (une rampe à la main reste à poser).
- **La grotte d'Altgard** et **l'entrée du temple du feu de Morheim** : même cause (des marches de 2 m), en attente de vos notes aux deux bouts du passage.
- **L'hôtel** : quelques anciens objets (« Rank 8 Asmodian Sword », des armes d'une version passée) peuvent encore y être ; **les plafonds de l'acheteur et les
  prix des matériaux sont des premières valeurs**, à corriger sur ce que vous voyez.
- **PvP** : pas de rotation de combat propre (étourdissement, immunité, fuite en kite) ; un bot en groupe ne fuit jamais un joueur ; ni terrains de chasse PvP ni
  invasions par les failles pour l'instant.

**À tester**
- **L'hôtel** : ouvrir le courtier dans une capitale, parcourir une catégorie (plus d'objets « NPC », de test ni de carte ?), acheter, et mettre un objet en vente
  dans la fourchette de prix pour voir la maison l'acheter en moins de 5 minutes.
- **Le PvP** : sur Reshanta, croiser des bots de l'autre race (certains attaquent, d'autres non), se faire attaquer et voir le bot fuir quand il est bas ;
  `//bot pvp <bot1> <bot2>` lance un combat entre deux bots de races différentes (même carte, moins de 60 m, en vue).
- **Les planés** : rester près d'un bot qui flâne au bord d'un relief ; il plane de lui-même, sans heurter un mur ni atterrir chez des monstres.
- **Pandaemonium** : plus de bot dans un mur autour de la jetée ; un bot assis se lève sans glisser.
- **Les boutiques** : nourriture, parchemins, pierres, les biens rares ; les lignes de chat dans Région, Commerce et Recherche de groupe, en français.
- Tout ce qui cloche : `//bot note <texte>` près du bot.

**À faire (mise à jour)**
- Rien de nouveau dans la base : le serveur crée lui-même les deux personnages maison (un par race, sur un compte réservé aux bots) au premier démarrage
  de l'hôtel, et enregistre leurs numéros dans `server_variables`. **Ne pas les supprimer** (leurs annonces disparaîtraient avec eux).
- `.\tools\deploy.ps1 -Restart`. Le fichier de réglages du dépôt a de nouvelles clés dans `playerbot.properties` ; un fichier installé qui ne les a pas utilise
  les valeurs par défaut du code (PvP et hôtel **activés**). Pour les couper, ajouter à la main dans le fichier installé :
  ```
  gameserver.playerbot.pvp.enable = false
  gameserver.playerbot.broker.enable = false
  ```
  Les autres clés (`gameserver.playerbot.broker.*`, `...ambient.glide`, `...chat.language`) sont décrites avec leurs valeurs dans le fichier du dépôt.
- La mémoire de l'hôtel : environ 300 octets par annonce (6 000 par race : 4 Mo), mesurés par `jcmd <pid> GC.class_histogram`. `broker.size` monte jusqu'à
  12 000 par race sans souci de mémoire ; le temps d'une recherche dans la fenêtre du courtier n'a pas été mesuré à cette taille.
- **Maillages de navigation : à régénérer si les vôtres datent d'avant le 31/10.** Ils ne sont pas dans le dépôt, chaque installation les génère. Le générateur a changé depuis
  le 28/10 : érosion qui épargne les passages et les décors, la grotte de Morheim (ouverture, sol, couloir), le sol réellement marché et les routes de patrouille.
  Un maillage plus ancien n'a pas la grotte de Morheim et garde des passages coupés. Serveur arrêté : `.\tools\navmesh.ps1 -OpenWorld` (une vingtaine de minutes,
  réécrit aussi les liens de plané et de vol), puis `.\tools\patrol-ground.ps1` (les routes de patrouille ; facultatif), puis `.\tools\check-golden-routes.ps1`
  (les routes marchées par les testeurs doivent toutes passer ; celles de la jetée de Pandaemonium échouent encore, voir « Ce qui n'est pas réglé »). Une installation
  déjà régénérée le 31/10 n'a rien à refaire. Après toute reconstruction d'une carte, ses liens sont à réécrire (`tools/links-rewrite.ps1`) ; `navmesh.ps1` les relit
  avant de construire.

---



Older entries: [patch-notes-2026-10.md](2026-10.md).
