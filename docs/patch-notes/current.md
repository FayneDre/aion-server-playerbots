# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-10-28 — 28/10/2026

Révision `ed3400a`, celle que `//bot version` affiche. **Une mise à jour à ne pas faire à la légère : elle fusionne 69 corrections du dépôt d'origine
(beyond-aion), qui change les bibliothèques, les serveurs de connexion et de discussion, des fichiers de données et de configuration, et la base.**
Le comportement des bots ne change pas : leur code a été réorganisé en cinq étapes, mesurées une à une, et les chiffres de navigation restent dans la
fourchette d'avant. **Aucun maillage à régénérer, mais une procédure à suivre dans l'ordre (voir « À faire »).**

**Ce qui change pour les joueurs et les testeurs** (le dépôt d'origine)
- **Les serveurs de connexion et de discussion passent à une autre bibliothèque réseau (Netty 4.2)** et s'accordent mieux au client 4.8. Un client
  qui n'envoie rien ou ne s'authentifie pas en 60 s est déconnecté ; la limite de paquets en attente par client est réglable (`network.properties`).
- **Deux personnages au plus par ordinateur** (`gameserver.security.multi_client.max_clients_per_computer = 2`). Les bots n'ont pas de connexion :
  ils ne comptent pas.
- **Chutes alignées sur le jeu officiel** : dégâts de chute, hauteurs mortelles, mort en plein vol ; une chute mortelle ramène tout de suite au point
  de résurrection. Les niveaux de mort propres à chaque carte n'existent plus (les bots gardent les leurs, pour leur vol).
- **Niveaux de compétence** : les compétences apprises ont toutes le niveau 1 en base (le rang n'est plus pris pour un niveau). Quelques coûts changent
  (Miroir divin : 166 PM au lieu de 178). La base est migrée par le script, pour les joueurs comme pour les bots.
- Des dizaines de failles corrigées (familiers, maisons, courants d'air, emblèmes de légion, butin, courrier, courtier, boutiques, échanges,
  enchantement), des quêtes réparées, la déconnexion et l'invisibilité alignées sur le jeu officiel.
- **Les journaux tournent par taille** : 100 Mo par fichier, 5 fichiers (`logging.properties`). `server_console.log` repart de zéro quand il est plein ;
  les mesures de `tools/measure.sh` se font sur une fenêtre qui tient dans le fichier courant.

**Ce qui change pour les bots**
- Rien de visible. Le module est découpé (oublier un bot qui part du monde, les rôles de classe qui décident sur ce qu'on leur donne, la navigation
  comme couche du bas, `PlayerBotAI` en cinq morceaux). Mesuré après chaque étape et après la fusion, avec un joueur en jeu : 13 compteurs sur 17
  dans la fourchette, les autres sans lien avec le code touché.
- Une ligne de journal en plus : un plan de journée abandonné dit sa carte, son départ, son arrivée et pourquoi le trajet a été refusé.

**À tester**
- Se connecter (le serveur de connexion a changé), créer ou charger un personnage.
- Voler, atterrir ; suivre un bot en vol ; descendre d'une falaise sans mourir de la chute.
- Acheter et vendre chez un marchand ; ouvrir le courrier et le courtier.
- Un groupe avec un bot et quelques lignes de discussion dans les canaux (région, commerce, recherche de groupe).
- Les bots sont environ 950 au démarrage ; rien d'anormal dans `server_errors.log`.

**À faire sur chaque serveur de test, dans cet ordre**
0. Avoir `mysql.exe` et `mysqldump.exe` (dans le PATH, ou sous `C:\Program Files\MySQL`) et un accès Internet pour Maven : les nouvelles
   bibliothèques se téléchargent à la première construction.
1. `.\tools\stop-server.ps1`, puis `git pull`.
2. `.\tools\sync-install.ps1 -From <le tag du dernier patch déployé>` (par exemple `patch-2026-10-25-2`). Il ne change rien tant qu'on n'ajoute pas
   `-Apply` ; il liste ce qu'il ferait : les fichiers de données et de configuration que le dépôt d'origine a changés (remplacés seulement si vous ne les
   aviez pas modifiés), les bibliothèques (Netty 4.2, jakarta, logback, MySQL, Hikari : à ajouter ; les anciennes : à retirer) et les instructions SQL
   à passer. **Lire la liste. Les lignes `LEFT` sont des fichiers que vous avez modifiés : le script n'y touche pas, à fusionner à la main**
   (`git diff <tag> HEAD -- <fichier>`). `mygs.properties` n'est jamais touché.
3. Relancer la même commande avec `-Apply`. Elle remplace les fichiers, met les bibliothèques à jour et passe le SQL : **sauvegarde de la base d'abord**
   (`<serveur>\backups\aion_gs-<date>.sql`), puis les instructions, une fois chacune (un registre `schema_applied_sql` est créé dans la base). Si une
   instruction échoue, le script s'arrête avec l'erreur, et on relance après correction.
4. `.\tools\deploy.ps1 -Restart` : construit les trois serveurs, passe les tests, copie les jars et relance connexion, discussion et jeu. La première
   fois, compter quelques minutes de plus (bibliothèques).
5. Au démarrage, attendre la ligne « Connected to login server » puis « Connected to chat server » dans `server_console.log` ; `//bot version` en jeu
   doit afficher `ed3400a`.

**En cas de problème** : revenir en arrière demande le code, les bibliothèques et la base ensemble. La base d'avant est dans `<serveur>\backups`
(à restaurer avec `mysql`, serveur arrêté) ; le code, avec `git checkout <tag>` puis `.\tools\sync-install.ps1 -LibsOnly -Apply`. Dans le doute, ne pas
relancer le SQL à la main : le registre sait ce qui est passé. Une base dont le SQL a déjà été passé à la main se déclare une fois avec
`.\tools\apply-sql.ps1 -MarkApplied -Apply`.

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

Older entries: [patch-notes-2026-10.md](2026-10.md).
