# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-10-31 — 31/10/2026

Révision `d840fec`, celle que `//bot version` affiche. **Un gros lot : 104 commits depuis le 28/10 (l'hôtel des ventes rempli par le serveur, le PvP des bots,
les planés pour le plaisir, beaucoup de navigation).** Aucun SQL à passer, aucun fichier de données qui change, **mais les maillages sont à régénérer si les vôtres datent d'avant le 31/10** (le générateur a changé : voir « À faire »). **Deux options
sont activées par défaut : le PvP des bots et l'hôtel des ventes** (voir « À faire » pour les couper).

**Ce qui change pour les joueurs et les testeurs**
- **L'hôtel des ventes n'est plus vide.** Deux personnages « maison » (`BotSellerElyos`, `BotSellerAsmo`, jamais connectés) y vendent 6 000 annonces par race,
  réparties par catégorie, qualité et niveau (surtout 20 à 55), à des prix autour du marché (± 15 %, jamais sous le plancher). **Plus d'objets de PNJ, de test,
  d'événement, de boutique ni de carte** : tout nom qui commence par un crochet, par « NPC » ou qui contient test, practice, dummy, event, etc. est écarté,
  et les annonces de ce genre déjà en place ont été retirées au redémarrage.
- **L'hôtel achète vos annonces** quand leur prix est dans la fourchette connue (au plus le haut de la fourchette + 5 %), **en 1 à 5 minutes**, sans jamais
  deviner le prix d'un objet : pas de prix connu, pas d'achat. Plafonds par jour : 200 M de kinah en tout, 30 M par vendeur, 50 M par type d'objet. L'objet
  acheté est détruit quand vous récupérez la vente. Vous touchez le kinah comme pour n'importe quelle vente.
- **PvP des bots.** Des bots de l'autre race peuvent vous attaquer (et attaquer vos bots) là où le jeu vous rend ennemis, par exemple sur Reshanta. Pas
  systématiquement : chaque bot a un tempérament fixe, **combattant (1 sur 4), opportuniste (1 sur 4, n'attaque qu'un joueur blessé ou de niveau
  inférieur) ou pacifique (1 sur 2, ne commence jamais un combat)**. Jamais plus de 3 niveaux au-dessus d'eux, jamais plus de 6 en dessous, et jamais plus de
  deux bots sur un joueur seul. Un bot attaqué par un joueur riposte, utilise une compétence défensive ou une potion, et **fuit sous 30 % de vie** (50 % à
  deux adversaires). Un bot de votre groupe vous aide quand un ennemi vous attaque. **Un joueur qui tue un bot reçoit les récompenses normales du jeu**
  (points d'abysse, primes, quêtes). Après un meurtre, le bot peut dire une phrase en Région.
- **Boutiques des bots** : de la nourriture, des parchemins de vitesse (5 minutes et 1 heure), des parchemins d'événement de 30 minutes (que le jeu
  n'autorise pas à l'échange, une exception pour les bots), des pierres 60 et 70 chez la plupart ; une page de 10 lignes ; **les biens rares (pierre
  toute-puissante, pierre d'évolution, sérum sacré) sont toujours dans une boutique de la carte**.
- **Le chat des bots** : des blagues et des échanges dans Région, des réactions (un bot meurt, passe un niveau, félicite), et **une option de langue, français
  ou anglais, pour tous les canaux** (`gameserver.playerbot.chat.language`, ou `//bot speech lang FR|EN` jusqu'au prochain démarrage).
- **Planés pour le plaisir** : un bot qui flâne près de vous, sur un rebord de 8 à 40 m, plane parfois vers le sol en dessous (une fois toutes les vingt
  minutes environ). Jamais en chasse, en groupe, sous 70 % de vie ni en ville.

**Ce qui change pour les bots**
- **Bots dans un mur ou sur un îlot** (Pandaemonium, la jetée) : un bot dont le chez-soi est un îlot de sol reçoit un autre chez-soi tout de suite ; un bot sur un
  sol que la marche ne joint pas à son chez-soi est replacé chez lui dès le deuxième refus, même sous les yeux d'un joueur.
- **Moins de morts inutiles** : les boutiques où trois bots sont morts en quinze minutes ne sont plus visitées (55 morts en une demi-heure vers une boutique de
  la forteresse de Morheim), et les lieux de mort sont évités par les routes pendant quinze minutes.
- **Longs trajets** : budgets de recherche doublés, trajets partiels marchés, terrains de chasse hors d'atteinte écartés.
- **La grotte de Morheim** marche (entrée, couloir, sortie) ; **un bot assis ne glisse plus** avant de courir.
- **Mesures de navigation (31/10), même fenêtre de 30 minutes, 13h25 puis 22h00** : plans abandonnés 317 → 201, routes refusées 551 → 306, morts 275 → 214,
  téléportations de secours 38 → 23. Ces chiffres varient d'une fenêtre à l'autre (jusqu'à 30 %) : à lire comme une tendance.
- **Nouvelles lignes de journal** : `Motion:` (une par minute : demi-tours des bots par cause), `PVP KILL` (une par meurtre qui touche un bot), « Broker house »
  (une par passe de l'hôtel et par achat).
- **Nouveaux outils** (pour qui lit le dépôt) : `NavmeshTool <carte> glidegain | ledges | steps | walkers`, les routes de référence (`tools/golden-routes.txt`,
  dont la jetée de Pandaemonium), le sol réellement marché (un point toutes les 0,5 s).

**Ce qui n'est pas réglé** (à ne pas signaler : c'est connu)
- **La jetée des dirigeables de Pandaemonium** : Toronil et Eriunik sont séparés du reste par une marche de 1 à 2 m (une rampe à la main reste à poser).
- **La grotte d'Altgard** et **l'entrée du temple du feu de Morheim** : même cause (des marches de 2 m), en attente de vos notes aux deux bouts du passage.
- **L'hôtel** : quelques anciens objets (« Rank 8 Asmodian Sword », des armes d'une version passée) peuvent encore y être ; **les plafonds de l'acheteur et les
  prix des matériaux sont des premières valeurs**, à corriger sur ce que vous voyez.
- **PvP** : pas de rotation de combat propre (étourdissement, immunité, fuite en kite) ; un bot en groupe ne fuit jamais un joueur ; ni terrains de chasse PvP ni
  invasions par les failles pour l'instant.

**À tester**
- **L'hôtel** : ouvrir le courtier dans une capitale, parcourir une catégorie (plus d'objets « NPC », de test ni de carte ?), acheter, et mettre un objet en vente
  dans la fourchette de prix pour voir la maison l'acheter en moins de 5 minutes.
- **Le PvP** : sur Reshanta, croiser des bots de l'autre race (certains attaquent, d'autres non), se faire attaquer et voir le bot fuir quand il est bas ;
  `//bot pvp <bot1> <bot2>` lance un combat entre deux bots de races différentes (même carte, moins de 60 m, en vue).
- **Les planés** : rester près d'un bot qui flâne au bord d'un relief ; il plane de lui-même, sans heurter un mur ni atterrir chez des monstres.
- **Pandaemonium** : plus de bot dans un mur autour de la jetée ; un bot assis se lève sans glisser.
- **Les boutiques** : nourriture, parchemins, pierres, les biens rares ; les lignes de chat dans Région, Commerce et Recherche de groupe, en français.
- Tout ce qui cloche : `//bot note <texte>` près du bot.

**À faire (mise à jour)**
- Rien de nouveau dans la base : le serveur crée lui-même les deux personnages maison (un par race, sur un compte réservé aux bots) au premier démarrage
  de l'hôtel, et enregistre leurs numéros dans `server_variables`. **Ne pas les supprimer** (leurs annonces disparaîtraient avec eux).
- `.\tools\deploy.ps1 -Restart`. Le fichier de réglages du dépôt a de nouvelles clés dans `playerbot.properties` ; un fichier installé qui ne les a pas utilise
  les valeurs par défaut du code (PvP et hôtel **activés**). Pour les couper, ajouter à la main dans le fichier installé :
  ```
  gameserver.playerbot.pvp.enable = false
  gameserver.playerbot.broker.enable = false
  ```
  Les autres clés (`gameserver.playerbot.broker.*`, `...ambient.glide`, `...chat.language`) sont décrites avec leurs valeurs dans le fichier du dépôt.
- La mémoire de l'hôtel : environ 300 octets par annonce (6 000 par race : 4 Mo), mesurés par `jcmd <pid> GC.class_histogram`. `broker.size` monte jusqu'à
  12 000 par race sans souci de mémoire ; le temps d'une recherche dans la fenêtre du courtier n'a pas été mesuré à cette taille.
- **Maillages de navigation : à régénérer si les vôtres datent d'avant le 31/10.** Ils ne sont pas dans le dépôt, chaque installation les génère. Le générateur a changé depuis
  le 28/10 : érosion qui épargne les passages et les décors, la grotte de Morheim (ouverture, sol, couloir), le sol réellement marché et les routes de patrouille.
  Un maillage plus ancien n'a pas la grotte de Morheim et garde des passages coupés. Serveur arrêté : `.\tools\navmesh.ps1 -OpenWorld` (une vingtaine de minutes,
  réécrit aussi les liens de plané et de vol), puis `.\tools\patrol-ground.ps1` (les routes de patrouille ; facultatif), puis `.\tools\check-golden-routes.ps1`
  (les routes marchées par les testeurs doivent toutes passer ; celles de la jetée de Pandaemonium échouent encore, voir « Ce qui n'est pas réglé »). Une installation
  déjà régénérée le 31/10 n'a rien à refaire. Après toute reconstruction d'une carte, ses liens sont à réécrire (`tools/links-rewrite.ps1`) ; `navmesh.ps1` les relit
  avant de construire.

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

**À faire sur chaque serveur de test, dans cet ordre** (une seule fois, de bout en bout ; compter une demi-heure la première fois)

Tout se lance dans PowerShell, depuis le dossier du dépôt (celui qui contient `tools`). `<tag>` est le tag du dernier patch que vous avez déployé
(par exemple `patch-2026-10-25-2`) : c'est lui qui dit au script quels fichiers de l'installation sont encore ceux du dépôt.

0. **Avant de commencer** : `mysql.exe` et `mysqldump.exe` doivent être trouvables (dans le PATH, ou sous `C:\Program Files\MySQL`) et le serveur
   MySQL doit tourner. Il faut Java 25, Maven et un accès Internet : les nouvelles bibliothèques se téléchargent la première fois.
1. **Arrêter le serveur de jeu** : `.\tools\stop-server.ps1` (il sauvegarde les joueurs ; ne jamais fermer la fenêtre à la main). Puis `git pull`.
   Le dépôt doit contenir le correctif du 29/10 : sans lui, `sync-install.ps1` s'arrête sur « Could not list the libraries of game-server » (il ne
   trouvait pas `commons` dans un dépôt jamais construit).
2. **Regarder ce qui va changer, sans rien toucher** : `.\tools\sync-install.ps1 -From <tag>`. La sortie se lit ainsi :
   - `REPLACE` / `REMOVE` / `ADD` : fichiers de données ou de configuration que vous n'aviez pas modifiés ; ils seront mis à jour.
   - `LEFT` : fichiers que **vous** avez modifiés et que le dépôt d'origine a changés aussi. Le script n'y touche pas : à fusionner à la main après
     (`git diff <tag> HEAD -- <fichier>`). Les réglages réseau, `admin.properties` et `logback.xml` y figurent le plus souvent.
   - `Libraries of ...: N to add, N to remove` : bibliothèques à ajouter (Netty 4.2, jakarta, logback, MySQL, Hikari) et anciennes à retirer.
   - `SQL: N statement(s)` : instructions de base de données à passer.
   - La dernière ligne, « Nothing changed: -Apply does it. », confirme que rien n'a été modifié. Si une ligne rouge s'affiche à la place, s'arrêter
     et envoyer la sortie complète (elle contient les dernières lignes de Maven, préfixées `mvn:`).
3. **Appliquer** : la même commande avec `-Apply` (`.\tools\sync-install.ps1 -From <tag> -Apply`). Elle remplace les fichiers, met les bibliothèques
   à jour, sauvegarde la base dans `<serveur>\backups\aion_gs-<date>.sql`, puis passe chaque instruction SQL une seule fois (un registre
   `schema_applied_sql` est créé). Si une instruction échoue, elle s'arrête avec l'erreur : corriger, puis relancer la même commande.
4. **Reporter à la main ce qui a changé dans les fichiers `LEFT`** (si l'étape 2 n'en a listé aucun, passer à la 5). Ce sont des fichiers que vous
   avez modifiés : le script les a laissés tels quels, donc les nouveautés du dépôt n'y sont pas. **Aucun n'empêche le serveur de démarrer** :
   sans ces lignes il utilise les valeurs par défaut. À faire une fois, avec un éditeur de texte, sur le fichier de l'installation
   (`<serveur>\<chemin affiché par le script>`). Pour voir exactement ce que le dépôt a changé dans l'un d'eux :
   `git diff <tag> HEAD -- <chemin>` (les lignes qui commencent par `+` sont les ajouts). Ne rien retirer de vos propres réglages.
   Pour les fichiers du patch du 28/10, voici quoi faire :
   - `game-server/config/network/network.properties` : ajouter ces quatre lignes, **telles quelles, une par ligne, sans virgule ni point à la fin**
     (une virgule après `60` empêche le serveur de démarrer : « Error parsing "60," as int ») :

     ```
     gameserver.network.packet.processor.max_pending_per_connection = 500
     gameserver.network.client.max_pending_server_packets = 20000
     gameserver.network.client.max_send_stall_seconds = 60
     gameserver.network.client.auth_timeout_seconds = 60
     ```

     La ligne `gameserver.network.flood.connections` vaut `true` dans le dépôt (c'était `false`) : si la vôtre est à `false`, la passer à `true`,
     sauf choix de votre part.
   - `chat-server/config/network/network.properties` : ajouter la ligne `chatserver.network.client.auth_timeout_seconds = 60` (sans rien après le 60).
   - `game-server/config/logback.xml`, `login-server/config/logback.xml`, `chat-server/config/logback.xml` : fichiers techniques que personne ne règle.
     Le plus simple est de les remplacer par ceux du dépôt, depuis le dossier du dépôt :
     `Copy-Item game-server\config\logback.xml <serveur>\game-server\config\logback.xml -Force` (et pareil pour `login-server` et `chat-server`).
     Sans cela le serveur démarre, mais les journaux ne tournent pas (le fichier grossit sans limite).
   - `game-server/config/administration/admin.properties` : rien à faire. Le dépôt a supprimé le réglage `enchant_info` ; une ligne en trop est ignorée.
5. **Construire et relancer** : `.\tools\deploy.ps1 -Restart`. Il construit les trois serveurs, passe les tests, copie les jars (dont ceux de la
   connexion et de la discussion) et redémarre connexion, discussion puis jeu. La première fois, la construction est plus longue (bibliothèques).
6. **Vérifier** : dans `<serveur>\game-server\log\server_console.log`, attendre « Connected to login server » (le jeu ne l'écrit qu'à la fin, deux à
   cinq minutes après le démarrage, une fois les bots prêts : ne pas se connecter avant). `//bot version` en jeu doit afficher la révision de votre
   dépôt (`git rev-parse --short HEAD`).

**En cas de problème** : revenir en arrière demande le code, les bibliothèques et la base ensemble. La base d'avant est dans `<serveur>\backups`
(à restaurer avec `mysql`, serveur arrêté) ; le code, avec `git checkout <tag>` puis `.\tools\sync-install.ps1 -LibsOnly -Apply`. Dans le doute, ne pas
relancer le SQL à la main : le registre sait ce qui est passé. Une base dont le SQL a déjà été passé à la main se déclare une fois avec
`.\tools\apply-sql.ps1 -MarkApplied -Apply`.

---


Older entries: [patch-notes-2026-10.md](2026-10.md).
