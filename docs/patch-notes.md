# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-10-18-3 — 18/10/2026

Révision `fdc459b`, celle que `//bot version` affiche. Corrige ce que la régénération des maillages du patch précédent avait cassé, et rouvre la
maison de la citadelle de Verteron. Les maillages ont encore été régénérés. Mesuré sur 36 minutes, ramené à 30, comparé au patch précédent.

**Corrigé**
- **Des zones entières sans habitants :** en ne laissant plus traverser les murs, les nouveaux maillages ont aussi coupé des liaisons qui passaient en
  fait à travers une porte de siège fermée (Gelkmaros). Or une carte ne gardait qu'un seul « continent » : le reste était vidé. Gelkmaros avait perdu
  149 lieux sur 229. Désormais, toute zone d'au moins 5 lieux, avec un village et un obélisque, est habitée : Gelkmaros n'en perd plus que 35 (comme
  avant les nouveaux maillages), Altgard 2, et Cygnea et Enshar gagnent des zones jamais peuplées jusqu'ici. Les bots de Gelkmaros combattent de
  nouveau autant qu'avant (1451 attaques contre 618).
- **Téléportations après une vente :** devant certains marchands, la place la plus proche était une marche dont on ne remonte pas ; le bot y vendait,
  puis restait bloqué et était téléporté. Il se place maintenant là d'où il peut repartir. Téléportations de secours : 87 contre 184, itinéraires
  refusés : 793 contre 1873.
- **Inggison :** les bots logés dans le creux sous le plateau ne pouvaient en sortir ni à pied ni en vol (il est couvert). Ces lieux ne sont plus
  habités. Décollages à Inggison : 0 contre 142.
- **La maison de la citadelle de Verteron** (1649, 1455) a bien une porte, en biais, que le maillage fermait. Elle est rouverte, et le PNJ de quête
  Nadaelo est de nouveau atteignable.
- **Serveur :** un lieu où aucun bot ne trouvait de coin où se reposer relançait de longues recherches toutes les quelques secondes.

**Vérifié**
- **Points de récolte :** ils bloquent bien le passage dans le client. La marge de 1,5 m que les bots gardent autour est donc justifiée, et les
  petits écarts qu'on voit autour d'eux sont normaux.

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull` et `mvn -pl game-server -am package`.
2. `.\tools\navmesh.ps1 -OpenWorld` (environ 25 minutes) : à refaire même si vous l'aviez fait pour le patch précédent.
3. `.\tools\deploy.ps1`, puis `.\tools\start-server.ps1`. Premier démarrage d'environ 7 minutes.
4. Si ce n'est pas déjà fait : `gameserver.instance_group.form_anywhere = true` dans `game-server\config\mygs.properties`.

**Connu, pas encore corrigé**
- Quelques bots habitent encore le creux d'Inggison et y sont ramenés après un blocage, le temps que le directeur les reloge.
- **Si vous vous déconnectez en donjon**, vos bots y restent tant que le groupe vous compte encore comme membre.

---

## patch-2026-10-18-2 — 18/10/2026

Révision `79b3863`, celle que `//bot version` affiche. Les bots vous suivent en donjon et répondent à vos annonces de groupe. Les bots ne traversent
plus les murs, et tous les maillages du monde ouvert ont été régénérés pour ça.

**Donjons et recherche de groupe**
- **Recrutement :** postez une annonce dans « Find Group » > « Instance Groups » (sans être en groupe, ou en étant chef), et des bots la
  remplissent jusqu'au nombre demandé. D'abord un tank, un soigneur et un support, puis des DPS. Votre classe compte : un clerc reçoit un tank, pas un
  second soigneur. Niveau : le vôtre à ±2 (±4 si personne n'est assez proche). Les bots arrivent près de vous et rejoignent le groupe tout seuls.
  Quand ils quittent le groupe, ils rentrent chez eux.
- **Suivi en instance :** quand vous entrez dans un donjon, vos bots y entrent avec vous ; quand vous en sortez, ils vous suivent dehors. Un bot mort
  dans le donjon revient à l'entrée, pas à l'obélisque.
- **Réglage serveur :** `gameserver.instance_group.form_anywhere = true` dans `mygs.properties`, sans quoi le bouton « Post Offer » reste grisé (les statues
  de ce client n'ouvrent jamais la page de recrutement).

**Suivre le chef**
- **Plus d'arrêts ni d'allers-retours :** un bot continue sur son trajet pendant qu'il calcule le suivant, et reprend là où il est. Dans Nochsana, un bot
  faisait des allers-retours sur 5 m pendant 16 s, alors que son chef avançait de 50 m.
- **Planer :** quand vous planez, les bots qui sont encore en haut marchent jusqu'à votre point de départ, puis planent par le même chemin.

**Murs et navigation**
- **Les bots ne traversent plus les murs :** le sol collé à un mur n'était jamais réduit de la largeur d'un corps, alors un mur fin en biais se
  traversait. Ça concernait toutes les cartes. Les trajets relevés dans vos notes (maisons de la citadelle de Verteron, château de Teminon) ne
  traversent plus rien. Sans perte d'accès : depuis l'obélisque de Teminon, 99 PNJ sur 101 sont atteignables, contre 97 avant ; dans la citadelle de
  Verteron, 89 sur 108, comme avant.
- **Marchand derrière un comptoir :** un bot ne passe plus derrière le comptoir pour vendre, et un bot coincé dans un recoin en est sorti, même sous vos
  yeux. Avant, il s'asseyait et se relevait en boucle.
- **Pas de vol à l'intérieur d'un bâtiment :** un bot ne décolle plus vers un endroit couvert d'un toit.

**Comportement**
- **Malus de résurrection :** le bot attend la fin du malus au calme, au lieu de retourner sur son terrain de chasse.
- **Buffs :** les bots ne se buffent plus en se promenant. Seuls, ils se buffent quand ils ont choisi leur cible ; en groupe, quand le groupe a une cible
  ou que vous visez un ennemi. Les soins et résurrections entre les combats ne changent pas.

**Serveur**
- **`start-server.ps1` attend que le serveur de jeu soit inscrit auprès du serveur de connexion** avant d'annoncer qu'on peut se connecter. Se
  connecter plus tôt obligeait à relancer le client.
- **Démarrage :** environ 3 minutes.

**À faire sur chaque serveur de test** (les maillages ne sont pas dans le dépôt : chaque serveur génère les siens)
1. Arrêter le serveur : `.\tools\stop-server.ps1`.
2. Récupérer le code et construire le jar : `git pull`, puis `mvn -pl game-server -am package`.
3. Régénérer les maillages du monde ouvert, avec leurs liens de glissade et de vol : `.\tools\navmesh.ps1 -OpenWorld`. Environ 25 minutes. Sans
   cette étape, les bots continuent de traverser les murs.
4. Ajouter `gameserver.instance_group.form_anywhere = true` dans `game-server\config\mygs.properties`.
5. Déployer puis démarrer : `.\tools\deploy.ps1`, puis `.\tools\start-server.ps1`. Le premier démarrage recalcule les lieux des bots et prend
   plusieurs minutes.

**Connu, pas encore corrigé**
- **Écarts sans raison visible :** les bots gardent 1,5 m autour des points de récolte. On ne sait pas si le client l'exige.
- **Une maison de la citadelle de Verteron**, en (1649, 1455), n'est plus accessible : on n'y entrait qu'à travers un mur. Si elle a une vraie porte,
  dites-le.
- **Si vous vous déconnectez en donjon**, vos bots y restent tant que le groupe vous compte encore comme membre.
- **Theobomos :** beaucoup de vols de bots (140 décollages en 30 min).

---

## patch-2026-10-18 — 18/10/2026

Révision `3652dfc`, celle que `//bot version` affiche. Les bots traversent les Abysses en volant d'île en île, et Reshanta est peuplée sur ses deux
couches. Mesuré par demi-heure, sans joueur, avec une escouade de six bots sur la couche haute ([abyss-crossing-plan.md](abyss-crossing-plan.md)).

**Voler dans les Abysses**
- **Traversées par les airs :** un bot qui doit aller sur une autre île décolle au bord de la sienne, monte, traverse en palier et se pose, puis reprend son chemin à pied. Il attend au bord s'il manque de points de vol. Environ 20 000 liaisons entre îles sur Reshanta, jusqu'à 180 m d'écart et 200 m de dénivelé.
- **Choix du chemin :** le bot choisit l'enchaînement d'îles le plus court en distance, et non celui qui a le moins de sauts. Un trajet de 400 m sur la couche haute est trouvé en un quart de seconde, un trajet de 1 km en environ une seconde.
- **Jamais dans une forteresse ni chez l'adversaire :** aucune traversée ne se pose dans la zone d'une forteresse, ni sur une île où ne vivent que des gens de l'autre faction.
- **Jamais à côté d'un monstre trop fort :** aucune traversée ne se pose à moins de 40 m d'un monstre agressif bien au-dessus du niveau du bot. Une escouade de niveau 40 s'était posée au milieu de gardes balaurs de niveau 65 et y était restée. À pied, en revanche, les Abysses restent dangereuses.
- **Aucune mort dans le vide :** aucune chute, et aucune traversée refusée en vol, sur toutes les mesures.

**Reshanta habitée**
- **Les deux couches :** 120 lieux habitables sur 134, contre 18 avant, tous sur une seule île. La base de chaque faction, la couche basse et la couche haute ont leurs résidents.
- **Teminon et Primum :** leurs escaliers étaient modélisés comme des rampes trop raides, et les bots ne pouvaient pas passer d'un étage à l'autre. Trajets réussis entre les PNJ de Teminon : de 29 sur 34 à 34 sur 34 ; Primum : 34 sur 34. Les bots vont vendre au marchand à sa hauteur, et non plus un étage au-dessus.
- **Résidents :** créés 3 niveaux au-dessus de leur lieu, parce que les monstres des Abysses frappent plus fort. Personne n'habite à moins de 50 m d'une élite hostile, ni dans une forteresse.
- **Factions équilibrées :** les bots réveillés le sont selon les terrains de chaque faction. Avant, il y avait 26 Élyséens éveillés pour 3 Asmodiens ; maintenant, 17 pour 13. Un bot ne déménage plus jamais chez l'autre faction : neuf Élyséens avaient été installés à Primum.
- **Après une mort :** un bot de la couche haute ressuscite à l'obélisque de sa base, attend d'être guéri, puis est ramené chez lui, comme par le téléporteur. Il ne cherche plus de route vers une couche qu'il ne peut pas rejoindre.
- **Trajets refusés sur Reshanta :** de 336 à 41 par demi-heure.

**Pour toutes les cartes**
- **Un monstre qui attaque le bot n'est jamais « hors de son camp » :** le bot ripostait, puis lâchait un monstre qui le frappait de loin pendant une promenade. Ces abandons sont passés de 1 706 à 28.
- **En groupe, un bot ne fuit plus un combat.** Le templier chef d'une escouade quittait le combat à 2 attaquants en pleine santé et laissait ses membres sans tank.
- **Les distances qui choisissent une promenade, un terrain de chasse ou un marchand se mesurent en 3D :** un terrain situé 1 300 m plus haut n'est plus « à côté ».

**Serveur**
- **Démarrage :** de 259 à 132 s. **Arrêt :** de 8 min 30 à 3 s. Des recherches de route sans issue autour des bases bloquaient les bots morts plusieurs minutes.

**Connu, pas encore corrigé**
- **Morts dans les Abysses :** les spiritualistes, éthertechs, bardes et pistoleros meurent beaucoup sur la couche haute (36 des 40 morts d'une demi-heure). Ces classes n'ont pas encore de guide de combat.
- **Escouades :** une escouade dans les Abysses peut croiser à pied des gardes balaurs de niveau 65 et y rester.

---

Older entries: [patch-notes-2026-10.md](patch-notes-2026-10.md).
