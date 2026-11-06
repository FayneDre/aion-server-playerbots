# One release process for the server and the launcher, with notes in French and English

Status: not started (written on 6 November, after a tester ran launcher v0.2.0 against a server older than it needs; decisions of the same day
folded in: pre-releases on both sides, no channel setting, independent version numbers).

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

**Two kinds of release, named and published the same way in both repositories.**

| | Server | Launcher |
|---|---|---|
| Test (testers) | GitHub **pre-release** `vX.Y.Z-beta.N` from `develop` | GitHub **pre-release** `vX.Y.Z-beta.N` from `develop` |
| Stable (everyone) | release `vX.Y.Z` on `main` | release `vX.Y.Z` on `main` |

Both use semantic versions: electron-builder and its updater read them, `-beta.N` sorts before the release it leads to, and a date-named tag
would mean nothing to them. The server's test patches therefore stop being `patch-YYYY-MM-DD` tags: the first one made under this plan is
`v0.1.0-beta.1`, and the date tags already pushed stay as they are, as history. The date of a patch is in its notes and on its GitHub page.

**Independent numbers.** The server and the launcher each have their own `major.minor.patch`, raised by their own rhythm: a minor for a new
feature, a patch for fixes, a major for a break of compatibility. A server 0.4 can go with a launcher 0.7. What ties a pair together is not the
number but the minimum the launcher declares (below).

**One rhythm per pair.** A test patch goes out as a pair whenever the launcher changed too: the server pre-release first, then the launcher
pre-release, whose notes name the server version it needs. A launcher change that needs no server change is a launcher pre-release alone. A
server pre-release with no launcher change keeps the last launcher pre-release, whose minimum still holds.

**No channel setting.** Testers install a pre-release by hand, both for the server and the launcher; the launcher gets no stable/test choice. To
be reconsidered only if automatic updates (launcher phase 6) make the hand install a burden.

**The launcher checks its server.** The admin channel answers a new command, `version`: the server version (`v0.1.0-beta.1`), the revision (as
`//bot version` shows it) and an integer, the **launcher API level**, a constant of `AdminChannel` raised whenever a command or a setting the
launcher relies on is added or changed. Each launcher release declares the lowest level it works with, and its notes name the first server
version that has it. Against an older server, the launcher says so in one line ("this server is older than this launcher: install server
v0.1.0-beta.1 or later") instead of empty lists, on the pages that depend on it. A server without `version` is level 0, which is the tester's
case of 6 November. While the game server is stopped, the launcher reads the settings files as today; a setting missing from them is already
handled.

## Notes in French and English

The decision of 5 November ("test patches stay in French") is changed: testers read French, but the notes now also serve the launcher's
"What's new" and the public repositories.

- **Two files with the same entries**: `docs/patch-notes/current.md` (French, as today) and `docs/patch-notes/current.en.md` (English). Same
  headings, one per version, in the same order, so a script can pair them. Archives move together (`2026-11.md` and `2026-11.en.md`).
- **`tag-patch.ps1` refuses** a version whose entry is missing from either file, as it refuses one missing from the French file today, checks
  that both entries name the same revision, and that the version is higher than the last one.
- **The launcher repository does the same** with `docs/release-notes/current.md` and `current.en.md`, and a script `tools/release.ps1` that
  refuses: a dirty tree, a failed `npm test`, a `package.json` version that differs from the tag, an entry missing in either language, or an
  entry that names no minimum server version.
- **GitHub release bodies, the same in both repositories**: the full English entry first, then the full French entry in a `<details>` block.
  For a stable release the rule of the public releases stands (English in full, a short French summary) unless both entries are short. The two
  entries are also attached as `notes.en.md` and `notes.fr.md`, which is what the launcher reads, so it never has to parse a release body.
- **Past entries** stay French only. The English file starts with the first version made under this plan.

Writing both is one more step per patch. It is made cheap by writing the English entry from the French one in the same pass, never later: the
check makes sure they never drift.

## Milestones

1. **The launcher's `develop`.** Branch, push, GitHub default stays `main`. Its `CLAUDE.md` gets the branch lines the server's has. The next
   launcher build goes out as `v0.3.0-beta.1`, a pre-release. Measured by: the tester installs a pre-release while `Latest` still points at v0.2.0.
2. **Server pre-releases, notes in both languages.** `tag-patch.ps1` takes a version (`-Version 0.1.0-beta.1`), tags it, and builds the
   pre-release (body and the two attached notes) for the user to publish with `gh release create --prerelease`; `current.en.md`, the check,
   `CLAUDE.md`, `docs/README.md` and the patch-notes heading format updated from dates to versions. Measured by: `tag-patch.ps1 -Check` refuses
   a French entry with no English one and a version not above the last, and accepts the pair.
3. **The launcher's release script and notes.** `tools/release.ps1`, `docs/release-notes/`, the release body built from the two entries the
   same way as the server's. Measured by: the script refuses each case above on a throwaway tag, then publishes `v0.3.0-beta.1` (the user's
   call, as every push).
4. **The compatibility check.** `version` on the admin channel with `API_LEVEL`, the launcher's minimum, its message on the Account, Bots and
   Settings pages. Measured by: an end-to-end test with a fake server at level 0 shows the message; a real server at the current level shows
   nothing.
5. **The first stable pair.** `develop` merged into `main` in both repositories once a pair is validated in game, each tagged with its own
   `vX.Y.Z`, the launcher's notes naming the server release. This is also where the server release plan (manifest, navmesh assets, embedded
   Java and MariaDB) starts; it is a separate plan.

Milestones 1 and 2 are independent and small; 4 is the one that would have prevented the report of 6 November.

## The steps of a test patch, once built

1. Server: commits on `develop`, `deploy.ps1`, checked in game.
2. When the user asks for a patch: the next `-beta.N` (or a new minor), one entry in each language for everything since the last version, then
   `tag-patch.ps1`; the push and the pre-release are the user's.
3. Launcher, if it changed: commits on `develop`, `npm test`, version raised to its own next `-beta.N`, one entry in each language naming the
   minimum server version, then `tools/release.ps1`; publishing is the user's.
4. Testers update both by hand; a launcher that is too new for their server says which server version to take.
