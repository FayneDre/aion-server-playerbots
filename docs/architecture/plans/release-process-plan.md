# One release process for the server and the launcher, with notes in French and English

Status: not started (written on 6 November, after a tester ran launcher v0.2.0 against a server older than it needs).

## Why

The server and the launcher are released by two processes that do not know each other:

- **Server**: `develop` holds the daily work; a test patch is a tag `patch-YYYY-MM-DD` made by `tools/tag-patch.ps1`, with a French entry in
  `docs/patch-notes/current.md`; `main` and the `vX.Y.Z` releases move once a version is validated in game (none yet).
- **Launcher**: one branch, `main`; releases `v0.1.0` and `v0.2.0` published from it on GitHub, notes in English with a short French summary.

On 6 November a tester on `patch-2026-11-03` ran launcher v0.2.0. The Bots page said the server had none of its settings, and the teleport list was
empty: both need server commits (`ca5aba72b` the admin channel, `3087a1560` the bot settings) that were in no patch tag. Nothing told them apart
from a bug. The tester asked the right question: since both testers run the server's `develop`, where is the launcher's?

Three things are missing: the launcher's own test channel, a statement of which server a launcher needs, checked by the launcher itself, and
notes both audiences can read, since the launcher will show them to players in their own language (launcher roadmap, phase 6).

## The target

**Same branches in both repositories.** `main` is stable and the GitHub default, `develop` is the daily work, and `main` moves only by merging
`develop` once a version has been validated in game. The launcher gets its `develop` branch, cut from its `main` as it stands.

**Two kinds of release, the same in both repositories.**

| | Server | Launcher |
|---|---|---|
| Test (testers) | tag `patch-YYYY-MM-DD` on `develop` | GitHub **pre-release** `vX.Y.Z-beta.N` from `develop` |
| Stable (everyone) | tag and release `vX.Y.Z` on `main` | release `vX.Y.Z` on `main` |

The launcher keeps semantic versions, because electron-builder and its updater read them, and `-beta.N` sorts before the release it leads to.
A date-named tag would mean nothing to them.

**One rhythm.** A test patch goes out as a pair whenever the launcher changed too: the server tag first, then the launcher pre-release, whose
notes name the patch it needs. A launcher change that needs no server change is a pre-release alone. A server patch with no launcher change keeps
the last launcher pre-release, whose minimum still holds.

**The launcher checks its server.** The admin channel answers a new command, `version`: the revision (as `//bot version` shows it) and an
integer, the **launcher API level**, a constant of `AdminChannel` raised whenever a command or a setting the launcher relies on is added or
changed. Each launcher release declares the lowest level it works with. Against an older server, the launcher says so in one line ("this server
is older than this launcher: deploy patch X or later") instead of empty lists, on the pages that depend on it. A server without `version` is
level 0, which is the tester's case of 6 November. While the game server is stopped, the launcher reads the settings files as today; a setting
missing from them is already handled.

## Notes in French and English

The decision of 5 November ("test patches stay in French") is changed: testers read French, but the notes now also serve the launcher's
"What's new" and the public repositories.

- **Two files with the same entries**: `docs/patch-notes/current.md` (French, as today) and `docs/patch-notes/current.en.md` (English). Same
  headings, one per tag, in the same order, so a script can pair them. Archives move together (`2026-11.md` and `2026-11.en.md`).
- **`tag-patch.ps1` refuses** a tag whose entry is missing from either file, as it refuses one missing from the French file today, and checks
  that both entries name the same revision.
- **The launcher repository does the same** with `docs/release-notes/current.md` and `current.en.md`, and a script `tools/release.ps1` that
  refuses: a dirty tree, a failed `npm test`, a `package.json` version that differs from the tag, an entry missing in either language, or an
  entry that names no minimum server patch.
- **GitHub release bodies**: the full English entry first, then the full French entry in a `<details>` block. For a stable release the rule of the
  public releases stands (English in full, a short French summary) unless both entries are short. The two entries are also attached as `notes.en.md` and
  `notes.fr.md`, which is what the launcher reads, so it never has to parse a release body.
- **Past entries** stay French only. The English file starts with the first patch made under this plan.

Writing both is one more step per patch. It is made cheap by writing the English entry from the French one in the same pass, never later: the
check makes sure they never drift.

## Milestones

1. **The launcher's `develop`.** Branch, push, GitHub default stays `main`. Its `CLAUDE.md` gets the branch lines the server's has. The next
   launcher build goes out as `v0.3.0-beta.1`, a pre-release. Measured by: the tester installs a pre-release while `Latest` still points at v0.2.0.
2. **Notes in both languages, server side.** `current.en.md`, the `tag-patch.ps1` check, `docs/README.md` and `CLAUDE.md` updated. Measured by:
   `tag-patch.ps1 -Check` refuses a French entry with no English one, and accepts the pair.
3. **The launcher's release script and notes.** `tools/release.ps1`, `docs/release-notes/`, the release body built from the two entries.
   Measured by: the script refuses each case above on a throwaway tag, then publishes `v0.3.0-beta.1` (the user's call, as every push).
4. **The compatibility check.** `version` on the admin channel with `API_LEVEL`, the launcher's minimum, its message on the Account, Bots and
   Settings pages. Measured by: an end-to-end test with a fake server at level 0 shows the message; a real server at the current level shows
   nothing.
5. **Pre-releases for server patches.** Each patch tag gets a GitHub pre-release carrying both notes, so the launcher can show the server's
   "What's new" too. Needed by the launcher's phase 6, not before.
6. **The first stable pair.** `develop` merged into `main` in both repositories once a pair is validated in game, tags `vX.Y.Z` on both, the
   launcher's notes naming the server release. This is also where the server release plan (manifest, navmesh assets, embedded Java and MariaDB)
   starts; it is a separate plan.

Milestones 1 and 2 are independent and small; 4 is the one that would have prevented the report of 6 November.

## The steps of a test patch, once built

1. Server: commits on `develop`, `deploy.ps1`, checked in game.
2. When the user asks for a patch: one entry in each language for everything since the last tag, then `tag-patch.ps1`; the push is the user's.
3. Launcher, if it changed: commits on `develop`, `npm test`, version raised to the next `-beta.N`, one entry in each language naming the server
   patch, then `tools/release.ps1`; publishing is the user's.
4. Testers update both; a launcher that is too new for their server says which patch to take.

## Open questions

- Do the testers need a launcher setting to choose the channel (stable or test) once updates are automatic (phase 6)? Until then they install a
  pre-release by hand.
- Should the server's `vX.Y.Z` and the launcher's share a major and minor number, so a pair reads as one (server 1.2.x with launcher 1.2.x)? It
  is simpler to read, but ties two release rhythms that may differ.
