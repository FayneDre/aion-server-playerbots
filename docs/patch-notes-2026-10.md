# Patch notes, October 2026 (older entries)

Moved out of [patch-notes.md](patch-notes.md) as it neared 200 lines. Newest first.

---

## patch-2026-10-24 — 24/10/2026

Révision `89b459a`, celle que `//bot version` affiche. Les capitales vivantes : visiteurs et paroles, et une navigation plus fluide pour tous les
bots. **Aucun maillage à régénérer.** La base reçoit une colonne au démarrage (`playerbot_characters.visiting_from`), ajoutée seule.

**Nouveau**
- **Visiteurs en capitale :** quand un joueur entre à Sanctum ou Pandaemonium, 15 bots d'autres régions arrivent aux téléporteurs, surtout des
  niveaux qui manquent en ville (50+). Ils regardent autour d'eux un moment, se promènent et font leurs courses 20 à 40 minutes, puis repartent
  par le téléporteur. Ils restent 5 minutes après le départ du dernier joueur.
- **Paroles en capitale :** les marchands crient le nom de leur boutique, les résidents de niveau 20+ cherchent un groupe (et acceptent
  l'invitation). Un cri toutes les quelques minutes, seulement près d'un joueur. `//bot speech off` les fait taire jusqu'au redémarrage.
- **Ville plus animée :** les résidents passent moins de temps chez eux ou assis, plus en courses et en promenades.

**Corrigé**
- **Trajets en attente :** un bot qui attendait un long trajet le redemandait toutes les 5 secondes ; la file se bouchait et des bots restaient
  plantés des minutes. Avec les correctifs suivants, deux fois plus de trajets trouvés, et deux fois et demie moins de recherches lentes.
- **Ventes de butin :** plus de boutique visée à 2 ou 3 km (800 m au plus), et une boutique injoignable pour plusieurs bots est laissée de côté.
- **Courses en ville :** vers le devant du comptoir, pas les pieds du marchand ; place cherchée plus loin quand le marchand est entouré.
- **Promenades en ville :** vers l'étage du quartier qu'on peut atteindre, pas un toit ou un balcon.
- **Stands :** plus au bord des ponts ni dans l'enclos des statues de téléportation, et plus alignés en rangées.
- **Démarrage du serveur :** les bots commencent leur journée étalés sur 4 minutes au lieu de tous en même temps.

**Connu, pas encore corrigé**
- Les grilles et rambardes du décor n'existent pas pour le serveur : un bot qui passe peut encore en traverser une.
- Environ 15 bots secourus (téléportés chez eux) toutes les 10 minutes sur l'ensemble du monde, à mesurer plus longuement.
- Duels entre bots et familiers en attente ; les prix des pierres d'enchantement attendent les réponses au plan des marchandises.

**À tester**
- Entrer dans une capitale : les visiteurs arrivent-ils vite, se dispersent-ils, repartent-ils par le téléporteur ?
- Écouter les cris près des marchés, et inviter un bot qui cherche un groupe.

---

## patch-2026-10-23-2 — 23/10/2026

Révision `7e94c45`, celle que `//bot version` affiche. Les boutiques des bots, d'après les retours du 23/10. **Aucun maillage à régénérer.**

**Corrigé**
- **Boutiques de classe :** elles ne vendaient que de la plaque. Chaque classe a maintenant son armure : plaque pour gladiateur et templier,
  mailles pour clerc, aède et éthertech, cuir pour assassin, rôdeur et pistolero, robe pour sorcier, spiritualiste et barde.
- **40 boutiques par capitale, à toute heure :** les marchands tiennent boutique à tour de rôle, 44 en service à la fois. Avant, leur nombre
  dépendait du hasard et de la taille de la ville : 16 à Sanctum, 21 à Pandaemonium. Un marchand qui ne peut pas rejoindre les marchés laisse
  son tour au suivant.
- **Boutiques moins serrées :** 6 m entre deux stands au lieu de 3, des marchés plus larges, et un sixième marché à Sanctum autour de l'Elyos Square.

**Connu, pas encore corrigé**
- Après un redémarrage, il faut environ 15 minutes pour atteindre les 40 boutiques : les marchands des quartiers éloignés marchent ou prennent
  le bateau jusqu'au marché.
- Le message « fusion de serveurs » à la sélection des personnages (compte avec les deux factions) vient du client : le serveur ne peut pas le
  désactiver.
- Les prix des pierres d'enchantement, et la vente de pièces et de médailles, attendent les réponses au plan des marchandises.

**À tester**
- Une boutique de chaque classe : la bonne armure, sans bouclier hors templier et clerc.
- Compter les boutiques à Sanctum et à Pandaemonium, et leur espacement.

---

## patch-2026-10-23 — 23/10/2026

Révision `d0daa64`. Les retours du 19, 20 et 21/10, le gladiateur et le sorcier d'après leurs guides. **Aucun maillage à régénérer.**

**Corrigé**
- **Groupe :** un membre mort se repose avant de vous rejoindre au lieu de mourir en route ; le clerc ne se bloque plus à se défendre au lieu de soigner.
- **Plané :** les bots qui vous suivent en planant ont l'animation des ailes.
- **Vols et téléportations :** un bot de votre groupe attend pendant votre vol de transport ou un courant aérien, et vous rejoint après une téléportation.
- **Ligne de vue :** un bot à distance ne tire plus à travers les murs ; il se place pour voir sa cible.
- **Buffs de vol :** les sorts qui ne servent qu'en vol ne sont plus lancés au sol.
- **Bot dans les airs :** un bot coincé en l'air (vu à Pandaemonium) est reposé au sol au démarrage, et secouru en jeu s'il ne peut plus aller nulle part.
- **Boutiques de classe :** plus de casques en tissu ou en cuir ni de boucliers dans une boutique gladiateur ; les bots ne portent plus non plus de
  casque d'une autre armure ni de bouclier s'ils n'en utilisent pas (seuls templier et clerc).

**Nouveau**
- **Gladiateur et sorcier** jouent selon leurs guides : posture, survie, enchaînements, zones sans réveiller les voisins (toutes classes), sorts au
  sol, multicasts, contrôle d'un second ennemi ; le sorcier se repose jusqu'à sa mana pleine et cesse d'attaquer s'il garde l'aggro en groupe.
- **`//bot gate`** : un sorcier de votre groupe ouvre un portail.
- **Marchés de Pandaemonium :** Vifrost Bridge, Market Street, Pandaemonium Plaza et Temple of Gold, 10 boutiques chacun.

**Connu, pas encore corrigé**
- Les capitales ont peu de bots de niveau 50+ : des visiteurs de tous niveaux sont prévus plus tard.
- On ne sait pas encore comment le bot de Pandaemonium est monté sous la coupole.

**À tester**
- Un gladiateur et un sorcier en groupe et en solo ; `//bot gate`.
- Prendre un vol de transport et se téléporter avec un groupe de bots.
- Les quatre marchés de Pandaemonium et le contenu des boutiques de classe.

---

## patch-2026-10-20 — 20/10/2026

Révision `a9e9ad4`, celle que `//bot version` affiche. Les capitales vivantes : Sanctum entier, une journée de ville, des boutiques tenues par les bots.
**Maillages à régénérer** sur un autre serveur de test, serveur arrêté : `.\tools\navmesh.ps1 110010000,120010000,710010000`, puis, depuis le
dossier `game-server` du serveur, `java -cp "libs/*" com.aionemu.gameserver.playerbot.navmesh.NavmeshTool 110010000 rides` (le bateau de Sanctum).
Une fois le serveur relancé, tapez **`//bot populate 0`** en vous tenant à Sanctum (la commande remplit la carte où vous êtes) pour créer les habitants qui lui manquaient.

**Corrigé**
- **Sanctum entier :** la moitié est de la ville (Artisans Hall, Divine Road…) était coupée du reste pour les bots, ses trois ponts centraux étant
  pris pour des murs. Sanctum passe de 62 à 116 habitants.
- **Allers-retours inutiles :** un bot qui va acheter des potions ne va plus chez un marchand qui n'en vend pas (il y retournait toutes les 10 minutes).

**Nouveau**
- **Le bateau de l'Airship Dock :** les bots le prennent pour aller à l'Outer Dock (Dionysia Tavern) et en revenir, et le quartier sud est de
  nouveau habité. Un bot disparaît en montant à bord et réapparaît à l'arrivée trois minutes plus tard : le bateau n'existe que dans votre client.
- **Une journée de ville :** à Sanctum, Pandaemonium et leurs annexes, les habitants ne « chassent » plus. Par tranches de 5 minutes, ils restent
  chez eux, s'assoient, font une course (marchand, courtier, entrepôt, artisans, téléporteur, maître des stigmas, guérisseur d'âme) ou visitent un
  autre quartier. Chacun a ses habitudes : la même chose à la même heure chaque jour. Une quinzaine bougent à tout moment à Sanctum.
- **Boutiques privées des bots :** la moitié des habitants de niveau 20 et plus tiennent une boutique, 6 à 10 heures par jour, à toute heure : une
  quinzaine ouvertes en permanence à Sanctum, au plus 40 par carte. À Sanctum, elles sont dans la rue de l'Exalted Path à l'Elyos Square, devant
  le Hall of Prosperity et dedans. Trois sortes : équipement d'une classe, bijoux, pierres (mana et enchantement), au niveau du marchand.
- **Prix :** jamais sous ce qu'un PNJ rachète plus un quart ; vert, bleu et or plus chers ; chaque marchand un peu plus cher ou moins cher qu'un
  autre ; un objet invendu baisse de 10 % par heure. Chaque boutique se renouvelle toutes les heures environ : les invendus de plus de deux heures
  partent, du stock neuf arrive.
- Un marchand en boutique refuse les duels et les échanges, et ferme boutique s'il accepte une invitation de groupe.

**Connu, pas encore corrigé**
- La terrasse du Protectors Hall reste vide : son sol manque dans le maillage.
- Pandaemonium n'a pas encore ses zones de marché : ses boutiques sont autour des courtiers et des entrepôts, et quelques marchands n'y arrivent pas.
- Le prix d'un objet ne monte pas quand il part vite.

**À tester**
- Acheter dans une boutique de bot, puis revenir une heure plus tard pour voir l'étalage renouvelé.
- Passer à Sanctum la nuit : il doit rester des boutiques ouvertes.
- Regarder un bot embarquer sur le bateau : il doit disparaître au départ.

---

## patch-2026-10-19-3 — 19/10/2026

Révision `228691f`, celle que `//bot version` affiche. Le vol avec un bot, d'après les retours du 19/10 au soir. **Aucun maillage à régénérer.**
Une fois le serveur relancé, tapez **`//bot regear all`** en jeu pour donner leurs parchemins aux bots déjà créés.

**Corrigé**
- **Atterrissage :** quand vous vous posez, un bot de votre groupe encore en vol vole jusqu'à vous et se pose à côté de vous, au lieu de se poser
  là où il est (loin derrière, au milieu des monstres, ou au-dessus du vide).
- **Le vide des Abysses tue :** un bot sans sol sous lui cherche l'île la plus proche que ses points de vol lui permettent d'atteindre ; s'il n'y en
  a aucune, il tombe et meurt, comme un joueur. Avant, il restait suspendu en l'air puis était téléporté sur vous.
- **Combat en vol :** un bot de votre groupe qui vous suit en vol attaque la cible du groupe, ou ce qui l'attaque, depuis les airs. Un soigneur
  reste près de vous et soigne.
- **PvP en vol :** un bot ennemi seul ne lâche plus le combat quand vous décollez. Il vous tire dessus si un sort porte jusqu'à vous, sinon il
  décolle et vous poursuit ; si vous vous posez, il se pose à côté de vous et le combat continue au sol.

**Nouveau**
- **Anneaux d'accélération (Abysses) :** un bot qui vous suit passe dans l'anneau que vous venez de traverser, et reçoit le même bonus de vitesse.
- **Sérums de vent :** un bot en vol en boit un sous la moitié de ses points de vol. Les bots en achètent chez les marchands qui en vendent.
- **Parchemins :** chaque bot reçoit à sa création et au `//bot regear` 20 parchemins de chaque sorte (course, vol, vitesse d'attaque, vitesse
  d'incantation), les meilleurs que son niveau permet (dès le niveau 10). Il lit le parchemin de vol en décollant, celui de course quand il a plus de
  30 m de retard sur vous à pied, et celui d'attaque ou d'incantation quand un combat de groupe commence.

**Connu, pas encore corrigé**
- En vol, un bot ne lance pas ses buffs de groupe (ils attendent le sol) ; un soin avec temps d'incantation peut être interrompu s'il se déplace.
- Les bots qui voyagent seuls dans les Abysses ne passent pas par les anneaux.
- La chute dans le vide à l'entrée du Terminon Training Camp n'a pas été retrouvée dans le journal : si elle se reproduit, notez l'heure et le
  nom du bot.

**À tester**
- Prendre de l'avance en vol puis vous poser dans un camp : le bot doit se poser à côté de vous.
- Planer depuis un rebord au-dessus du vide avec un bot en groupe.
- Traverser les anneaux de Primum ou de Teminon avec un bot.
- Attaquer un monstre volant ou un ennemi en vol avec un bot dans le groupe.
- Seul, provoquer un bot ennemi puis décoller.
- Après `//bot regear all`, regarder un bot lire ses parchemins (icônes de buff).

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull` et `mvn -pl game-server -am package`.
2. `.\tools\deploy.ps1`, puis `.\tools\start-server.ps1`.
3. En jeu : `//bot regear all`.

---

## patch-2026-10-19-2 — 19/10/2026

Révision `9de29b7`, celle que `//bot version` affiche. Ce qu'un joueur fait avec un bot, et Theobomos Lab. **Un seul maillage est à régénérer**,
celui de Theobomos Lab (voir plus bas).

**Nouveau**
- **Duels :** un bot accepte seul un duel s'il ne combat pas, qu'il n'est pas en donjon et qu'il est à 10 niveaux de vous au plus, puis il attaque
  tout de suite. Il refuse un second duel du même joueur pendant 3 minutes.
- **Amis :** un bot accepte toute demande d'ami. Il apparaît en ligne quand il est actif, hors ligne quand il dort.
- **Échange avec votre compagnon** (seulement le vôtre ; tout autre bot refuse) : ouvrez l'échange, il accepte. Ce que vous lui **donnez**, il le
  porte dans les 2 minutes si c'est mieux que ce qu'il a. Pour **reprendre** quelque chose, tapez **avant de verrouiller**
  `//bot give <nom> all`, `//bot give <nom> kinah <montant>` ou `//bot give <nom>` suivi du lien de l'objet (Maj + clic sur l'objet). Il verrouille
  et valide juste après vous.

**Corrigé**
- **Theobomos Lab :** la salle de la reine Arachne, le premier boss, était coupée de l'entrée pour les bots par un panneau du décor qu'on traverse en
  jeu. Elle est maintenant accessible à pied.

**Connu, pas encore corrigé**
- Les ascenseurs de Rentus (à la mort de Kuhara) et de Theobomos (après les salles élémentaires) et les téléporteurs de Dragon Lord's Refuge ne sont
  pas pris par les bots : ils vous rejoignent environ 13 secondes plus tard.
- Les noms d'objets tapés en français ne sont pas reconnus par `//bot give` : utilisez le lien de l'objet.

**À tester**
- Défier un bot en duel ; en défier un en plein combat (il doit refuser).
- Ajouter un bot en ami et le voir en ligne.
- Donner une arme à votre compagnon ; lui reprendre deux objets et des kinahs avec `//bot give`.

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull` et `mvn -pl game-server -am package`.
2. Si le patch 2026-10-19 n'a pas été installé : `.\tools\navmesh.ps1 -Dungeons -Force` (environ 17 minutes) ; sinon
   `.\tools\navmesh.ps1 -Dungeons 310110000 -Force` (une minute).
3. `.\tools\deploy.ps1`, puis `.\tools\start-server.ps1`.

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
4. Si ce n'est pas déjà fait : `gameserver.instance_group.form_anywhere = true` dans `game-server\config\main\group.properties` (passer de `false` à `true`), ou dans `game-server\config\mygs.properties` si vous avez créé ce fichier facultatif, qui remplace les réglages de `main`.

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
- **Réglage serveur :** `gameserver.instance_group.form_anywhere = true` dans `config\main\group.properties` (ou `mygs.properties` s'il existe), sans quoi le bouton « Post Offer » reste grisé (les statues
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
4. Mettre `gameserver.instance_group.form_anywhere = true` dans `game-server\config\main\group.properties` (passer de `false` à `true`), ou dans `game-server\config\mygs.properties` si vous avez créé ce fichier facultatif, qui remplace les réglages de `main`.
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
couches. Mesuré par demi-heure, sans joueur, avec une escouade de six bots sur la couche haute ([abyss-crossing-plan.md](flight/abyss-crossing-plan.md)).

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
([obstacle-steering-plan.md](navigation/obstacle-steering-plan.md)).

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
- Les noms de compétences en français sont acceptés ([skill-glossary.md](classes/skill-glossary.md)).

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
