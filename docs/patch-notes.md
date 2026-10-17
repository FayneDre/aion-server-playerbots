# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

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
