# Patch notes

Written in French, for the testers who read them; the rest of the docs stay in English. Newest first. Each entry names its git tag
(`tools/tag-patch.ps1`), which is what `//bot version` on the server should match. When this file nears 200 lines, the oldest entries move to
`patch-notes-<year>-<month>.md`.

---

## patch-2026-10-16 — 16/10/2026

Révision `b83ad00` (le serveur de test tournait sur `f9a5518`, identique côté serveur). Corrige les retours du 12/10 et du 14/10.

**Toutes les classes**
- **Sorcier :** ne relance plus ses robes en boucle. Robe de flammes et Robe de froid s'annulaient l'une l'autre et vidaient sa mana ; il garde Robe de flammes.
- **AoE centrées sur le lanceur (gladiateur et autres) :** ne partent que si la cible est dans la zone, hauteur comprise. En groupe, pas d'AoE qui toucherait un pack pas encore engagé.
- **Résurrection en groupe :** un bot mort attend d'être ressuscité tant que le groupe combat, puis 1 minute (10 minutes au plus). Il part à l'obélisque tout de suite si personne du groupe n'est en vie. En solo, rien ne change.
- **Arme :** en groupe en instance, plus de rengainage entre les packs (sauf pour s'asseoir) ; en groupe dehors, rengainage après 60 s sans combat. Pas de changement d'équipement en instance.
- **Buffs entre coéquipiers :** un bot ne « riposte » plus contre un coéquipier qui vient de le buffer (l'aède surtout), ce qui coupait son déplacement.
- **Provocation :** en groupe, seul le tank provoque. Le gladiateur lançait Provocation et prenait l'aggro du templier.
- **Après une mort, en groupe :** le bot récupère avec son groupe au lieu de partir vers un coin calme près de chez lui.

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
- Le rôdeur abandonne des cibles qu'il ne parvient pas à atteindre : 13 abandons sur 13 relevés en escouade.

---

## patch-2026-10-12 — 12/10/2026, 20 h

Révision `f3b73a8`. Le patch sur lequel portent les retours du 12/10 et du 14/10.

- Les classes avec un guide (templier, aède, rôdeur, clerc) lancent le rang le plus élevé de leurs compétences, et non plus le rang 1.
- Planeur : les bots planent en descendant en cercle au lieu de tomber le long de la falaise, ailes ouvertes, et un bot tué en planant ne revient plus en volant.
- Un sort commencé en marchant n'est plus annulé par l'arrêt qui le précède.
- Liaisons de planeur dans le monde ouvert (8 obélisques isolés sur 12 rejoints), longs trajets planifiés par étapes, les bots ne choisissent plus de cible hors de leur camp.
- Accès aux donjons : entrées avec dialogue, téléporteurs danuar.
