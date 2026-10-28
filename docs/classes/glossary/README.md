# Skill glossary

Status: reference.

The French names the guides and the testers use, and the skill group each one is in the data, one file per class: [templar](templar.md),
[chanter](chanter.md), [cleric](cleric.md), [ranger](ranger.md), [gladiator](gladiator.md), [sorcerer](sorcerer.md). The server's data carries only
the English client names, so a French name in a report has to be looked up somewhere, and until then that meant asking.
`tools/skill-lookup.ps1 -Name` searches every file of this folder as well as the data, and prints the groups it finds here.

A name a tester uses that is not the client's (a nickname, a misspelling) goes in too, saying so. To add a row: the French name as the player wrote it,
the client name, the group from `skill-lookup.ps1`, the class; a new class gets a file of its own. Groups are stable across ranks, which is why they
are the key.

The French names from the gladiator on are the French client's own, read from `strings/client_strings_skill.xml` in the French client's `Data.pak`
(a plain zip, UTF-16), by the template's `nameId`: the guides' English names are the guide's translations of those, which is why no English list has
them.
