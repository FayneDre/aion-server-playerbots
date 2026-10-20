# Patch notes, October 2026 (older entries)

Moved out of [patch-notes.md](patch-notes.md) as it neared 200 lines. Newest first.

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

## patch-2026-10-17-2 — 17/10/2026

Révision `1ce4571`, celle que `//bot version` affiche. Corrige le retour du 16/10 sur les compagnons et les anciens retours du 06/10, du 09/10, du
14/10 et du 15/10 encore ouverts. Vérifié avec une escouade de six bots de niveau 40 en Heiron, sans joueur : 71 combats en dix minutes, aucune peur ni
grande AoE lancée.

**Compagnons (bots qui appartiennent à un joueur)**
- **Ne touchent plus à leur sac.** Un compagnon vendait au marchand, sac plein, tout l'équipement que sa classe ne peut pas porter, orange et jaune compris : un aède de 65 a ainsi perdu 15 à 20 pièces de tissu et de plaque gardées pour des rerolls. Il ne vend plus, n'achète plus de potions, ne s'habille plus avec ce qu'il y a dans son sac, n'identifie plus et ne lie plus rien (« les compagnons continuent de changer d'équipement »). `//bot sell` le refuse. Il continue de ramasser son butin et de boire ses potions en combat.
- Les objets déjà vendus sont perdus : la liste de rachat du marchand disparaît à la déconnexion, il faut les recréer avec `//add`.

**En groupe et en donjon**
- **Plus aucune peur.** Un monstre effrayé s'enfuit et attire tout ce qu'il croise ; le Fear Shriek du spiritualiste n'est plus lancé en groupe ni en instance. Seul en monde ouvert, il reste autorisé.
- **Plus d'AoE de 20 m ou plus**, qu'elle soit centrée sur le bot ou sur sa cible (Divine Grasp du templier, sorts de zone du spiritualiste…). Une AoE plus petite autour de la cible n'est lancée que si elle ne touche aucun monstre que le groupe ne combat pas déjà. Le cri du templier (8 m) reste.

**Classes**
- **Pistolero :** deux pistolets au lieu du canon. Le canon venait d'une erreur : le pistolero apprend bien le maniement à deux armes au niveau 10. Les pistoleros déjà créés gardent leur canon jusqu'à un `//bot regear`.
- **Aède, gladiateur, templier, assassin :** après un premier sort à distance pour attirer la cible, ils vont au contact au lieu de rester à distance. Un sort à distance ne sert plus que si la cible fuit ou si la poursuite échoue.
- **Clerc :** ne tente plus de dissiper un débuff que le moteur ne retirera pas (rune d'assassin, mauvaise catégorie, effet très long). Un débuff encore là après un nettoyage réussi est laissé jusqu'à sa fin, au lieu d'être visé en boucle.
- **Rôdeur :** rien à corriger. Focused Shots renforce les 5 prochaines attaques, d'où sa relance fréquente en combat ; Viser et Esquive ne tournent plus en boucle depuis le 10/10.

**Niveaux des régions**
- **Théobomos et Brusthonin :** la bande de niveau 20 à 30 au centre de la carte recevait des bots de 45 à 49. Un lieu garde maintenant son propre niveau quand une part réelle des monstres de la région est à ce niveau. Les deux cartes ont été repeuplées : 23 et 21 bots de 20 à 30 dans ces zones.
- Pas encore de bots de niveau 50 pour les instances de Théobomos et la Poeta sombre.

**À vérifier en jeu**
- Le clerc face à un débuff qu'il ne peut pas retirer (rune d'assassin) : l'escouade de test n'a rencontré aucun débuff dissipable.
- La peur et les grandes AoE en donjon, avec un joueur dans le groupe.

---

## patch-2026-10-17 — 17/10/2026

Révision `2f9cec8`, celle que `//bot version` affiche. Les bots qui rentrent dans le décor, glissent le long des murs, traversent une rambarde « en deux
pas de côté » ou se téléportent : mesuré par demi-heure sur 24 cartes et environ 800 bots, sans joueur connecté
([obstacle-steering-plan.md](obstacle-steering-plan.md)).

**Déplacements**
- **Le navmesh contrôle les petits déplacements :** un pas de côté ou les derniers mètres vers une cible ne traversent plus une rambarde, un muret ou le pied d'un objet du décor, que les anciens rayons ne voyaient pas.
- **Un obstacle sur le chemin :** le bot demande une route qui le contourne au lieu de faire un pas de côté à l'aveugle, et il attend sa route sur place au lieu de zigzaguer.
- **Pas de côté :** de 14 689 à 469 par demi-heure. Ceux qui restent contournent un objet apparu en jeu (une plante à récolter), que le navmesh ne connaît pas.
- **Une cible hors du sol praticable** (un marchand derrière son comptoir, un monstre sur un rocher) : le bot s'arrête au plus près au lieu de faire les cent pas, puis y renonce si ça ne suffit pas.
- **Boutiques :** un bot vend à 8 m du marchand (5 m avant), ce qui suffit aux comptoirs.
- **Buffs en marchant :** un buff que le moteur refuse n'arrête plus la marche du bot. Certains bots recevaient la même route chaque seconde sans jamais la suivre (1 442 fois en une demi-heure pour un seul).
- **« N'avance plus » :** de 708 à 15 par demi-heure. Une route replanifiée en chemin faisait croire au bot qu'il reculait.

**Téléportations de secours** (un bot coincé remis chez lui ou sur un sol relié)
- **De 403 à 79 par demi-heure.** Les trois quarts venaient d'un terrain de chasse inaccessible : le bot y renonce maintenant au lieu d'insister jusqu'au secours.
- **Plus de téléportation sous les yeux d'un joueur :** un bot coincé qu'un joueur voit change d'activité, et le secours attend que personne ne regarde.
- **Sauf dans le groupe d'un joueur :** un bot du groupe coincé est toujours téléporté, vu ou non, et posé à côté du chef s'il n'y a pas de sol relié tout près. Avant, il pouvait attendre indéfiniment. Il attend seulement si le chef est en vol.
- Un secours sur l'endroit même où se tient le bot ne téléporte plus rien.

**Serveur**
- Processeur : de 9,2 à 3,9 cœurs pour environ 800 bots.

**À vérifier en jeu**
- Un bot de votre groupe qui reste coincé doit réapparaître près de vous au bout d'environ 12 secondes.
- Un bot hors groupe que vous regardez ne doit plus disparaître sous vos yeux.

---

## patch-2026-10-16-3 — 16/10/2026

Révision `18789a0`, celle que `//bot version` affiche. Corrige les retours du 12/10 et du 14/10, et ce qu'une escouade de six bots sans joueur a révélé
en jouant (chiffres mesurés sur le même camp d'Eltnen). Les étiquettes intermédiaires du jour, `patch-2026-10-16` et `patch-2026-10-16-2`, sont
comprises dans celle-ci.

**Toutes les classes**
- **Sorcier :** ne relance plus ses robes en boucle. Robe de flammes et Robe de froid s'annulaient l'une l'autre et vidaient sa mana ; il garde Robe de flammes.
- **AoE centrées sur le lanceur (gladiateur et autres) :** ne partent que si la cible est dans la zone, hauteur comprise. En groupe, pas d'AoE qui toucherait un pack pas encore engagé.
- **Sorts interrompus :** un bot ne fait plus aucun pas pendant qu'il incante (un seul pas faisait échouer le sort à la fin, sans rien dire), et ne commence plus un sort à incantation sur une cible de groupe qui va mourir avant la fin. Un sort qui échoue quatre fois de suite est mis de côté 30 s. Sorts perdus par l'escouade : de 17 % à 3 %.
- **Résurrection en groupe :** un bot mort attend d'être ressuscité tant que le groupe combat, puis 1 minute (10 minutes au plus). Il part à l'obélisque tout de suite si personne du groupe n'est en vie. En solo, rien ne change.
- **Après une mort, en groupe :** le bot récupère avec son groupe au lieu de partir vers un coin calme près de chez lui.
- **Arme :** en groupe en instance, plus de rengainage entre les packs (sauf pour s'asseoir) ; en groupe dehors, rengainage après 60 s sans combat. Pas de changement d'équipement en instance.
- **Bots coincés près du groupe :** un bot coincé dans une poche du terrain près de son chef n'était jamais secouru ; il est maintenant posé sur le sol relié à celui du chef. Abandons de cible : de 21 en dix minutes à 0.
- **Buffs entre coéquipiers :** un bot ne « riposte » plus contre un coéquipier qui vient de le buffer (l'aède surtout), ce qui coupait son déplacement.
- **Provocation :** en groupe, seul le tank provoque. Le gladiateur lançait Provocation et prenait l'aggro du templier.

**Templier**
- Capture n'est plus utilisée sur la cible principale ni sur la cible au crâne : seulement pour le peel des autres monstres déjà en combat.
- Plus de Capture sur un monstre immobilisé ; un monstre endormi, étourdi ou paralysé n'est plus provoqué du tout.
- **Défi (Provocation)** dans la rotation : sur la cible principale dès qu'elle frappe quelqu'un d'autre, et à chaque recharge sur un boss.

**Positionnement**
- `//bot position auto|standard|spread`. Par défaut `auto` : placement complet contre les boss (héroïques et légendaires) seulement ; contre le reste, corps-à-corps droit au contact, distances à portée. `standard` : jamais. `spread` : toujours. `//bot positioning on|off` marche toujours.
- Mesuré sur un camp ordinaire : 23 packs par minute sans placement, 14 avec.
- ⚠️ Dans `playerbot.properties`, `gameserver.playerbot.positioning = true` doit devenir `AUTO` (en majuscules), sinon le serveur ne démarre pas.

**Pour les séances de test** (détail dans [tester-feedback.md](tester-feedback.md))
- `//bot version` : la révision qui tourne, à citer dans les retours.
- `//bot note <texte>` : marque le moment dans le journal, avec l'état de chaque bot du groupe.
- `//bot debug combat` : tous les sorts des bots dans le journal, jusqu'au prochain redémarrage.
- `tools\collect-feedback.ps1` : un zip des minutes autour de chaque note, à joindre au retour.
- Les noms de compétences en français sont acceptés ([skill-glossary.md](skill-glossary.md)).

**Connu, pas encore corrigé**
- Le templier qui « lâche l'affaire » : il faut le journal de la séance (ou l'heure et le nom du bot).
- Le placement contre un boss n'a pas pu être vérifié : le seul boss accessible du bon niveau à Eltnen se tient sur un terrain hors maillage.
- La résurrection en groupe n'a pas encore été vue en jeu : aucun bot n'est mort pendant les essais.

---

## patch-2026-10-12 — 12/10/2026, 20 h

Révision `f3b73a8`. Le patch sur lequel portent les retours du 12/10 et du 14/10.

- Les classes avec un guide (templier, aède, rôdeur, clerc) lancent le rang le plus élevé de leurs compétences, et non plus le rang 1.
- Planeur : les bots planent en descendant en cercle au lieu de tomber le long de la falaise, ailes ouvertes, et un bot tué en planant ne revient plus en volant.
- Un sort commencé en marchant n'est plus annulé par l'arrêt qui le précède.
- Liaisons de planeur dans le monde ouvert (8 obélisques isolés sur 12 rejoints), longs trajets planifiés par étapes, les bots ne choisissent plus de cible hors de leur camp.
- Accès aux donjons : entrées avec dialogue, téléporteurs danuar.
