# Tester feedback: what a useful report holds

A report is turned into a fix by finding the moment in the server log and the rule in the code. Each of the items below removes one round of questions;
the tooling behind them is in [test-harness-plan.md](../architecture/history/test-harness-plan.md).

## During the session

0. **Before the session, [patch-notes.md](../patch-notes/current.md)**: what the patch changed, so the session tests that, and what is known and not yet fixed.
1. **At the start, `//bot version`.** It names the revision the server runs, which a patch note names too (`tools/tag-patch.ps1`). A report against
   "the 20h patch" cannot be checked out; one against `patch-2026-10-16` or `a0ba0bd` can.
2. **`//bot debug combat` once, if the session is about combat.** Every cast and every fight given up is then in the log, until the server restarts.
3. **`//bot note <a few words>` the moment something looks wrong.** It writes the time, where you stand and the state of every bot of your group to the
   log. Three words are enough; the details go in the report. Its number (`Note #4`) is what the report refers to.

## After the session

- **On the tester's server:** `tools\collect-feedback.ps1` writes a zip of the minutes around every note, a few hundred kilobytes. Send it with the report.
  Without the script, the whole `game-server\log\server_console.log` will do, as long as it is from that session: it is replaced on each start.
- **Skill names:** French as in your client is fine. [the skill glossary](../classes/glossary/README.md) maps them to the data; a name that is not there gets added
  once it is settled, so it is never asked twice.

## The template

Copied into the report, one block per point:

```text
Point n — <titre court>
Note : #<n> (ou heure approximative)
Classe / bot : <classe, nom du bot si connu>
Lieu : <carte ou instance, boss ou pack>
Attendu : <ce qu'un joueur aurait fait>
Observé : <ce que le bot a fait>
Fréquence : <une fois / souvent / à chaque fois>
Vidéo : <lien, avec le moment>
```

"Attendu" is the line that matters most: it is the rule the fix will be written from. Where it is a choice ("Capture only on targets other than the
main one"), say so, since that is a decision rather than a bug.
