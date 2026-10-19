# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-10-19 — 19/10/2026

Révision `666ce2b`, celle que `//bot version` affiche. Les donjons avec des bots. **Les maillages des donjons sont à régénérer** (voir plus bas).

**Corrigé**
- **Bot perdu en donjon :** quand vous passez un téléporteur, un levier, un ascenseur, un windstream ou une porte que votre clé ouvre, votre bot ne
  peut pas vous suivre à pied. Il réapparaît maintenant à côté de vous, quelle que soit la distance, environ 13 secondes plus tard. Avant, au-delà de
  200 m, il était renvoyé vers les coordonnées de sa maison, mais sur la carte du donjon.
- **Sols manquants :** des planchers et des ponts manquaient aux maillages de 20 donjons. Part des monstres que les bots peuvent atteindre à pied :
  Steel Rake 99 % (94 avant), Danuar Sanctuary et sa version occupée 94 et 95 % (74 et 76), Dredgion, Chantra et Terath 96, 97 et 99 % (91, 88 et
  89), Sauro 91 % (80). À Drakenspire, 84 des 88 emplacements de monstres sont désormais reliés à l'entrée, en marchant ou en sautant d'une plateforme.

**Connu, pas encore corrigé**
- **Theobomos Lab** : l'ascenseur vers l'aile haute n'est pas connu des bots, qui vous rejoignent par la réapparition décrite plus haut. Dans la salle
  de la reine Arachne, une paroi invisible coupe peut-être la salle en deux : dites-nous si vous la traversez librement.
- **Rentus** : nous ne savons pas comment on monte du sol de Kuhara jusqu'au pont de Vasharti. Dites-nous comment vous y montez.
- Les bots ne prennent pas eux-mêmes les windstreams, les ascenseurs et les téléporteurs : ils vous rejoignent par la réapparition.

**À tester**
- Dans un Dredgion, à Esoterrace (après un windstream) ou à Steel Rake : vos bots doivent réapparaître près de vous en 15 secondes environ.

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull` et `mvn -pl game-server -am package`.
2. `.\tools\navmesh.ps1 -Dungeons -Force` (environ 17 minutes).
3. `.\tools\deploy.ps1`, puis `.\tools\start-server.ps1`.

---

## patch-2026-10-18-5 — 18/10/2026

Révision `c7bbc59`, celle que `//bot version` affiche. Les trajets vers les boutiques. Pas de régénération des maillages. Mesuré sur les 23 premières
minutes après un redémarrage, comparé à la même fenêtre.

**Corrigé**
- **Le bot marchait vers le mauvais marchand :** il choisissait une boutique accessible, puis marchait vers le marchand le plus proche de lui, qu'il ne
  pouvait parfois pas atteindre, et abandonnait la boutique choisie. Il recommençait ainsi avec chaque boutique de la rue, toutes les 2 minutes. Il
  marche maintenant vers celle qu'il a choisie, et vend toujours au premier marchand à sa portée. Trajets abandonnés : 91 contre 269.
- **Il visait les pieds du marchand**, derrière le comptoir, hors du sol praticable : chaque recherche d'itinéraire tournait 1 à 2 secondes pour
  rien. Il vise maintenant le premier sol devant le marchand, à portée d'échange. Temps de calcul de ces recherches lentes : 599 s contre 978.
- **Fioles :** un bot qui venait d'acheter des fioles, sans en avoir assez, retournait en acheter chaque seconde.

**Connu, pas encore corrigé**
- Environ 50 téléportations de secours par demi-heure, réparties sur toutes les cartes, sans endroit qui revienne plus de 4 fois.
- Des recherches d'itinéraire longues (plus d'une seconde) prennent encore environ 600 secondes de calcul par demi-heure, sans effet visible en jeu.

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull` et `mvn -pl game-server -am package`.
2. Si le patch 2026-10-18-3 n'a pas été installé : `.\tools\navmesh.ps1 -OpenWorld` (environ 25 minutes).
3. `.\tools\deploy.ps1`, puis `.\tools\start-server.ps1`.

---

## patch-2026-10-18-4 — 18/10/2026

Révision `cfe42ff`, celle que `//bot version` affiche. Trois points restés ouverts au patch précédent. Pas de régénération des maillages.

**Corrigé**
- **Bots restés en donjon :** si vous vous déconnectez dans une instance, vos bots vous attendent 5 minutes (le temps de relancer un client), puis
  quittent le groupe et sortent de l'instance. Les bots recrutés par la recherche de groupe rentrent ensuite chez eux. Pareil pour un groupe dont il ne
  reste aucun joueur.
- **Mort en boucle dans les Abysses :** un bot élyséen ressuscitait à Primum, la base asmodienne, et ses gardes le tuaient toutes les 12 secondes.
  Quand aucun obélisque n'est atteignable à pied depuis chez lui, un bot prenait le plus proche, de n'importe quelle faction. Il ne s'attache plus
  qu'aux obélisques de sa faction ou neutres.
- **Habitants de lieux disparus :** la règle qui reloge les habitants d'un lieu supprimé existait depuis le 4 octobre mais n'était jamais appelée.
  98 bots ont été relogés au premier démarrage, dont 47 du creux d'Inggison ; un bot éveillé prend son nouveau domicile tout de suite.

**À vérifier en jeu**
- Déconnectez-vous dans un donjon avec vos bots : au bout de 5 minutes, ils doivent quitter le groupe et ne plus être dans l'instance.

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull` et `mvn -pl game-server -am package`.
2. Si le patch 2026-10-18-3 n'a pas été installé : `.\tools\navmesh.ps1 -OpenWorld` (environ 25 minutes).
3. `.\tools\deploy.ps1`, puis `.\tools\start-server.ps1`.

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

Older entries: [patch-notes-2026-10.md](patch-notes-2026-10.md).
