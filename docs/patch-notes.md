# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-10-25-2 — 25/10/2026

Révision `42e6876`, celle que `//bot version` affiche. Les retours du 25/10 : les soins du clerc, les bots invités de loin, les pierres de
puissance, les ailes, un clerc qui semblait se téléporter, le repos dans la lave. **Aucun maillage à régénérer, aucun changement de base ni de
configuration, le serveur de chat ne change pas.** Si les liens de vol plané du patch précédent n'ont pas encore été écrits, le faire d'abord.

**Précision sur le chat (patch du 24/10)** : les deux réglages peuvent aussi bien se mettre dans `game-server/config/mygs.properties` que
directement dans `config/main/gameserver.properties` (`gameserver.chatserver.enable`) et `config/network/network.properties`
(`gameserver.network.chat.password`) : le serveur lit ces fichiers, puis `mygs.properties` par-dessus.

**Amélioré**
- **Clerc, seuils de soin corrigés d'après le retour du testeur** : le seuil le plus bas passe en premier. Tank sous 30 % : Éclair de guérison
  (canalisation, auparavant interdite) ; sous 50 % : L'éclair de la reprise ; sous 60 % : Cure radieuse (pas si un monstre frappe le clerc).
  Deux membres blessés : Splendeur curative sous 30 %, Splendeur de la récupération sous 50 %, Vent de guérison sous 60 %, avant le soin du tank
  tant qu'il garde 50 %. Un clerc sous 70 % se soigne par un soin de zone. Le salut en groupe avant 31 avec le mana sous 20 %. Seul, L'éclair de
  la reprise sur soi sous 30 %.
- **Lumière de rajeunissement** : posée sur le templier dès le début du combat et relancée à l'expiration. Elle ne l'était jamais : elle
  attendait que le tank passe sous 95 %.
- **Un bot qui accepte une invitation à plus de 50 m (ou sur une autre carte) est téléporté à côté du joueur**, et rentre chez lui quand il
  quitte le groupe. Pas pour une recrue, déjà amenée, ni en instance, ni pour un compagnon (il reste avec son propriétaire).
- **Pierres de puissance** : les bots achètent celles des marchands avec leurs kinahs (1000, ou 200 si la bourse est légère), les équipent
  (les deux emplacements avec une arme à deux mains ou deux armes) et activent le mode en combat. Les marchands s'arrêtent au niveau 50 : au-delà,
  c'est la Premium.
- **Ailes** : les bots portent à partir du niveau 30 les ailes que les marchands vendent contre des kinahs (niveaux 30, 40 et 50, de leur
  faction), jamais celles d'une quête, d'une instance, d'une récompense ou des Abysses. Données, pas achetées. Changées en montant de niveau.

**Corrigé**
- **Un clerc semblait se téléporter devant le monstre mort** : le client arrête un personnage qui lance un sort, et le serveur reprenait la
  marche ensuite sans le redire. Le mouvement est maintenant renvoyé après chaque sort. C'est l'explication qui colle au retour, pas une cause vue.
- **Un bot ne se repose plus dans la lave** (ou sur tout sol qui fait des dégâts) : il marche d'abord jusqu'au sol sain le plus proche.

**À tester**
- Clerc en groupe avec un templier : Lumière de rajeunissement dès le début, Éclair de guérison qui part bien au bout de la canalisation, plus de
  « saut » à la fin des combats.
- Inviter un bot à plus de 50 m : il doit apparaître à côté de vous, puis rentrer chez lui une fois sorti du groupe.
- Ailes visibles sur les bots de niveau 30 et plus ; arme qui brille et pierres qui diminuent en combat.
- Marais de soufre (Theobomos) : un bot qui combat les pierres de lave doit sortir de la lave avant de s'asseoir.

**À faire sur chaque serveur de test**
1. `.\tools\stop-server.ps1`, puis `git pull`.
2. `.\tools\deploy.ps1 -Restart`.
3. Au démarrage, quelques centaines de bots mettent leurs ailes (« wears new wings » dans le log) et beaucoup partent acheter leurs pierres :
   les trajets vers les marchands montent les dix premières minutes, puis redescendent.

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

Older entries: [patch-notes-2026-10.md](patch-notes-2026-10.md).
