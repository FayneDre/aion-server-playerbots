# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

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

Older entries: [patch-notes-2026-10.md](patch-notes-2026-10.md).
