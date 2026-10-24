# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-10-24-2 — 24/10/2026

Révision `425eb6f`, celle que `//bot version` affiche (game server et serveur de chat). Les marchandises des boutiques d'après le guide, et les
bots qui parlent dans les canaux. **Aucun maillage à régénérer, aucun changement de base.** **Le serveur de chat change aussi** : il faut
déployer son jar (`deploy.ps1` le fait seul quand il a changé) et le redémarrer.

**Action requise pour voir les canaux**

Le chat est désactivé d'origine. Sans ces réglages, aucun canal (Commerce, Recherche de groupe, Région) ne fonctionne, même entre joueurs :
- dans `game-server/config/mygs.properties` : `gameserver.chatserver.enable = true`, et `gameserver.network.chat.password = <le mot de passe>`,
  le même que `chatserver.network.gameserver.password` dans `chat-server/config/network/network.properties` (sinon le serveur de chat refuse
  le game server : « tried to register with an invalid password ») ;
- redémarrer le game server, puis vérifier dans son log « Connected to chat server » ;
- un joueur hors du réseau local du serveur : régler `chatserver.network.client.connect_address` (adresse publique) dans
  `chat-server/config/mycs.properties`, sinon il n'a pas les canaux ;
- les canaux s'ouvrent à partir du niveau 10.

**Nouveau**
- **Bots dans les canaux**, une ligne toutes les 20 à 40 s par canal, seulement si un joueur de la faction est là pour la lire. Jamais vu de
  l'autre faction. Un bot qui a parlé se tait 20 minutes. Chaque ligne est vraie pour celui qui la dit :
  - **Commerce** (capitales) : un marchand annonce un objet de son étal au prix affiché à ce moment-là, ou sa boutique. Lien d'objet
    cliquable, affiché dans la langue du client.
  - **Recherche de groupe** : un bot libre de niveau 20+ annonce sa classe, son niveau, son rôle et un donjon de groupe de son niveau.
  - **Région** : salutation selon l'heure, ce qu'il combat et où, sa progression vers le niveau suivant, une question sur un lieu à laquelle
    un bot qui s'y trouve répond quelques secondes plus tard, ou une petite phrase.
- **`//bot chat <region|trade|group> [texte]`** : fait parler le bot ciblé (ou le plus proche) dans un canal, pour tester. `//bot speech off`
  fait aussi taire les canaux.
- **Pierres d'enchantement du guide** : seules les L1 à L5 (166000191 à 195), la All-Powerful (166020000), la pierre d'évolution (166500002)
  et le Holy Upgrade Serum (166030005) sont vendus. Les anciennes pierres L1 à L190 encore dans les sacs des marchands sont supprimées.
- **Boutique « Pièces et médailles »** : pièces de bronze à céramium, médailles d'argent et d'or, selon le niveau du marchand. Ni pièces de
  fer, ni médailles de platine, mithril ou céramium.
- **Prix de courtier** : fourchettes du guide, le caractère du marchand le place dedans ; un invendu baisse de 10 % par heure jusqu'au bas de
  la fourchette. Pierres de mana au prix de leur stat (et palier pour les composites), divisé par deux tous les 10 niveaux sous le 60.
- **Pierres de mana anciennes** pour les marchands de niveau 60+. Plus de pierres de mana blanches.
- **Équipement et bijoux bleus au minimum**, éternels à partir du niveau 60.
- **Deux sortes de marchands** : les spécialisés, sur un large choix de niveaux (« Bijoux 20-45 », « Pierres 10-50 »), et un tiers de
  « Bric-à-brac », un peu de tout autour de leur niveau.

**Corrigé**
- **Spiritualistes marchands** : ils n'ont plus leur esprit invoqué à côté de leur boutique.

**Connu, pas encore corrigé**
- Noms des donjons et des monstres en anglais dans les canaux (les objets, eux, sont des liens traduits par le client) ; le reste des phrases est
  en français.
- Un bot chuchoté ne répond pas, par décision. Inviter un bot qui cherche un groupe ne le fait pas venir s'il est loin.
- Les marchands devenus « Bric-à-brac » gardent dans leur sac les marchandises de leur ancien thème.
- La All-Powerful est à prix fixe (75M) : elle ne baisse pas.

**À tester**
- Les trois canaux pendant une heure : variété, rythme, une phrase fausse ou absurde (une capture ou `//bot note` suffit).
- Une boutique « Pierres » de niveau 60+ et un « Bric-à-brac » : prix et contenu.

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull`.
2. Faire les réglages de « Action requise » ci-dessus (une seule fois : ils restent, les déploiements ne touchent pas à la configuration).
3. `.\tools\deploy.ps1 -Restart` : il compile le game server et le serveur de chat, remplace le jar du serveur de chat et redémarre le tout.
4. En jeu, avec un personnage de niveau 10+ : `//bot chat region` en ciblant un bot ; la ligne doit apparaître dans l'onglet Région.

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

Older entries: [patch-notes-2026-10.md](patch-notes-2026-10.md).
