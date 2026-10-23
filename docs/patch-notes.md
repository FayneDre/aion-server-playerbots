# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

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

Older entries: [patch-notes-2026-10.md](patch-notes-2026-10.md).
