# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-10-25 — 25/10/2026

Révision `d91cda5`, celle que `//bot version` affiche. La navigation des bots, mesurée toute la journée avec un joueur en jeu : moins de trajets
abandonnés, des bots qui ne se figent plus, moins de mémoire. **Aucun maillage à régénérer, aucun changement de base ni de configuration, le
serveur de chat ne change pas.** Mais **les liens de vol plané du monde ouvert sont à écrire une fois** (voir « À faire »).

**Action requise : les liens de vol plané du monde ouvert**

Les fichiers `.glide` des cartes du monde ouvert avaient été effacés par la régénération des maillages du 18 octobre, et ne sont pas dans le dépôt
(ils sont produits sur chaque serveur). Sans eux, les bots ne savent pas descendre d'une falaise ou d'une galerie en planant, et beaucoup de leurs
trajets échouent (capitales, Reshanta, Inggison, Enshar). Serveur arrêté, une seule fois, depuis le dépôt :
`.\tools\navmesh.ps1 110010000,120010000,120020000,120080000,210010000,210020000,210030000,210040000,210050000,210060000,210070000,210090000,220010000,220020000,220030000,220040000,220050000,220070000,220080000,220100000,400010000,600010000,600090000,600100000 -Glides`
Compter un moment (17 Mo de liens au total). Le maillage lui-même n'est pas touché.

**Amélioré**
- **Les bots ne se figent plus en pleine action.** Certaines vérifications (« ce point près du village est-il atteignable ? », « ce marchand
  est-il accessible ? ») tournaient sur les fils qui font agir les bots et prenaient jusqu'à 4 s, figeant une centaine de bots à chaque fois :
  environ 960 s par demi-heure, contre quelques secondes maintenant.
- **Moins de trajets abandonnés** : 345 à 148 par demi-heure sur l'ensemble des cartes, plans de journée abandonnés 621 à 475.
  - Dans **Sanctum et Pandaemonium**, la recherche ne prend plus la galerie ou le pont au-dessus du bot pour son étage (68 à 32 abandons).
  - À **Reshanta**, les recherches ne s'épuisent plus à comparer chaque atterrissage à 1 500 monstres (29 à 13).
  - Un bot ne retourne plus sans cesse vers un point de flânerie qui lui a été refusé, et un court trajet raté n'est plus recherché trois fois
    depuis le même endroit.
- **Les bots perdus sont ramenés** : un bot dont tous les trajets échouent depuis le même endroit (4 destinations différentes en 5 minutes)
  rentre chez lui. Jamais sous les yeux d'un joueur : il fait alors un pas de côté ou change d'activité.
- **Villages sans issue** : un village dont les habitants restent coincés à la porte (3 fois en 10 minutes) n'est plus peuplé, ses habitants
  sont relogés ailleurs sur la même carte. Il redevient habitable dès que le maillage ou les liens de la carte changent.
- **Un bot coincé à côté d'un joueur** (autour d'un obélisque, derrière une barrière) fait un pas jusqu'à 12 m au lieu de rester sur place.
- **Mémoire** : la recherche de chemin est plus légère (environ 200 Mo de moins mesurés juste après un démarrage, sans plus de recul).

**Corrigé**
- Le journal des avertissements ne liste plus chaque bot comme « joueur sans connexion » toutes les demi-heures.

**Connu, pas encore corrigé**
- À Reshanta, une dizaine de trajets par demi-heure vers des îlots plus hauts ou plus bas échouent encore : le vol direct est refusé au-delà de
  300 m ou sous un toit. Inggison est désormais la carte qui abandonne le plus de trajets.
- Un bot qui va vendre peut en principe se retrouver derrière un comptoir d'où il ne sait pas revenir (aucun cas vu) : signaler un bot téléporté
  juste après une vente.
- Les pas de côté (environ 900 par demi-heure) n'ont pas bougé.

**À tester**
- Se promener dans Sanctum et Pandaemonium : des bots figés, qui tournent en rond ou qui disparaissent sous vos yeux ?
- Reshanta : des bots qui volent d'un îlot à l'autre, ou au contraire qui restent plantés au bord ?
- Un bot qui vient de vendre et qui est téléporté chez lui juste après (`//bot note` sur place).

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull`.
2. Écrire les liens de vol plané avec la commande de « Action requise » ci-dessus (une seule fois).
3. `.\tools\deploy.ps1 -Restart`.
4. Vérifier qu'il y a bien un fichier `.glide` par carte de la liste dans `game-server/data/navmesh` du serveur (24 fichiers, 17 Mo en tout).

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

Older entries: [patch-notes-2026-10.md](patch-notes-2026-10.md).
