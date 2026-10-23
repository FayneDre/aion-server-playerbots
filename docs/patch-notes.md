# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

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

Older entries: [patch-notes-2026-10.md](patch-notes-2026-10.md).
