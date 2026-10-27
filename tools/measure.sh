#!/bin/bash
# Measures the bots' navigation over one stretch of the running server's log, without loading the whole log anywhere, and writes the day plans
# given up in that stretch as journeys NavmeshTool can plan again offline (the file next to the report, ending in -journeys.txt):
#   cd <server>/game-server && java -Xmx700m -cp "libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool replay <that file> [every]
# Usage: tools/measure.sh <from HH:MM> <to HH:MM or now> [day, default today]
# Run it in the foreground once the window is over: a background wait of half an hour was killed for low memory on this machine.
# Versioned on 27 October 2026 after two weeks in the server's log folder, where only this machine could run it. Reports go to
# <server>/game-server/log/bot-measures, not to the repository: they are a run's data. Every report has the same sections, so two can be compared.
from=$1; to=$2; day=${3:-$(date +%Y-%m-%d)}
SERVER=$(cygpath -u "${AION_SERVER_HOME:-D:/Jeux/Aion/server}")
MYSQL=${MYSQL_EXE:-"/c/Program Files/MySQL/MySQL Server 26.7/bin/mysql.exe"}
REPO=$(cd "$(dirname "$0")/.." && pwd)
M=$SERVER/game-server/log/bot-measures
F=$SERVER/game-server/log/server_console.log
mkdir -p $M
W=$(mktemp -d)
mkdir -p $W/game-server/log
if [ "$to" = now ]; then hi="9999"; else hi="${day}T$to"; fi
awk -v lo="${day}T$from" -v hi="$hi" '$0 >= lo && $0 < hi' $F > $W/window.log
grep 'com\.aionemu\.gameserver\.playerbot\.\S* - Bot ' $W/window.log > $W/game-server/log/server_console.log
OUT=$M/nav-${day}-${from/:/}.txt
cd "$REPO" && powershell -NoProfile -File tools/nav-stats.ps1 -Top 5 -ServerRoot "$(cygpath -w $W)" > "$OUT" 2>&1
{
echo "--- errors/warnings (debug service excluded)"
grep -E " ERROR | WARN " $W/window.log | grep -v "DEBUG SERVICE" | sed -E 's/^[^ ]+ +//; s/[0-9]+([.,][0-9]+)?/N/g' | cut -c1-200 | sort | uniq -c | sort -rn | head -12
echo "--- channel lines"; for c in region trade "find group"; do echo "$c $(grep -c "says in $c" $W/window.log)"; done
echo "--- spots that lead nowhere: lost (searches out of budget) $(grep -c "run out of budget for .* so it is the spot" $W/window.log), refused in a row $(grep -c "destinations in a row from .* so it is the spot" $W/window.log)"
echo "--- obelisk strandings $(grep -c 'no place to wait out its sickness' $W/window.log)"
echo "--- in sight loops"; grep "a player has it in sight" $W/window.log | grep -o "Bot [A-Za-z]*" | sort | uniq -c | sort -rn | head -5
echo "--- stuck spots"; grep "could not move" $W/window.log | sed -E 's/.*Bot (\S+) could not (move|walk)[^0-9]*([0-9]+) ([0-9]+).*/\3 \4 \1/' | sort
echo "--- searches over a second, by outcome"; grep "Path search on map" $W/window.log | grep -o "took [0-9]* ms ([a-z ]*[,)]" | tr -d ',)(' | awk '{k=$4" "$5; s[k]+=$2; c[k]++} END{for(k in c) printf "%s %d, avg %d ms, %d s in all\n", k, c[k], s[k]/c[k], s[k]/1000}'
echo "--- slow reach questions by asker and thread (count, wall s, processor s)"
grep "Path search on map" $W/window.log | sed -nE 's/.*\[([A-Za-z]+)[^]]*\].* took ([0-9]+) ms \([a-z ]+, ([0-9]+) ms of processor, asked by ([^)]+)\).*/\4 \1 \2 \3/p' \
  | awk '{k=$1" "$2; c[k]++; w[k]+=$3; p[k]+=$4} END{for(k in c) printf "%5d %6d %6d  %s\n", c[k], w[k]/1000, p[k]/1000, k}' | sort -rn | head -12
echo "--- slow journey searches by outcome and thread (count, wall s, processor s)"
grep "Journey search for" $W/window.log | sed 's/gave up/gave-up/; s/no way/no-way/' | sed -nE 's/.* took ([0-9]+) ms \(([a-z-]+), ([0-9]+) ms of processor, on ([A-Za-z]+).*/\2 \4 \1 \3/p' \
  | awk '{k=$1" "$2; c[k]++; w[k]+=$3; p[k]+=$4} END{for(k in c) printf "%5d %6d %6d  %s\n", c[k], w[k]/1000, p[k]/1000, k}' | sort -rn
echo "--- route queue waits over a second"; grep -o "waited [0-9]* ms for a route thread, [0-9]* more" $W/window.log \
  | awk '{c++; s+=$2; if($2>m)m=$2; q+=$8} END{if(c) printf "%d, avg %d ms, longest %d ms, avg %d queued\n", c, s/c, m, q/c; else print 0}'
echo "--- characters per map in the database (map:count), then villages retired per map"
G=$SERVER/game-server
P=$(grep -h "^database.password" $G/config/mygs.properties | cut -d= -f2 | tr -d ' \r')
"$MYSQL" -uaion -p"$P" aion_gs -N -e "select world_id, count(*) from players group by world_id order by 1" 2>/dev/null | tr '\t\n' ': ' ; echo
for s in $G/cache/playerbot/stranded-*.txt; do [ -f "$s" ] && printf "%s:%d " "$(basename $s .txt | cut -d- -f2)" $(( $(wc -l < "$s") - 1 )); done; echo
echo "--- flights not taken instead of a journey given up, by reason"; grep "does not fly to" $W/window.log | sed -E "s/.*instead: //; s/^[A-Z][a-z]+ //; s/[0-9]+/N/g" | cut -c1-90 | sort | uniq -c | sort -rn | head -8
echo "--- day plans given up, by activity and cause"; grep "BotDay - Bot .* cannot get to" $W/window.log | sed -nE 's/.*cannot get to (where ([A-Z_]+) would take it|its hunting ground)[^(]*\(map ([0-9]+) .*, ([^,)]+)\)$/\2\1 \4/p' | sed -E 's/where [A-Z_]+ would take it//; s/its hunting ground/FARMING/' | sort | uniq -c | sort -rn | head -14
echo "--- day plans given up, by map"; grep "BotDay - Bot .* cannot get to" $W/window.log | sed -nE 's/.*\(map ([0-9]+) .*/\1/p' | sort | uniq -c | sort -rn | head -10
echo "--- day plans given up, places refused most (map, destination rounded to 20 m, bots)"; grep "BotDay - Bot .* cannot get to" $W/window.log | sed -nE 's/.*Bot ([A-Za-z]+) cannot.*\(map ([0-9]+) from .* to (-?[0-9]+) (-?[0-9]+) (-?[0-9]+), ([^)]+)\)$/\2 \3 \4 \1 \6/p' | awk '{k=$1" "int($2/20)*20" "int($3/20)*20; c[k]++; b[k][$4]=1; why[k]=$5" "$6" "$7} END{for(k in c){n=0; for(x in b[k]) n++; printf "%4d %3d bots  %s  (%s)\n", c[k], n, k, why[k]}}' | sort -rn | head -12
echo "--- day plans given up, starts refused most (map, start rounded to 20 m)"; grep "BotDay - Bot .* cannot get to" $W/window.log | sed -nE 's/.*\(map ([0-9]+) from (-?[0-9]+) (-?[0-9]+) .*/\1 \2 \3/p' | awk '{print $1, int($2/20)*20, int($3/20)*20}' | sort | uniq -c | sort -rn | head -8
echo "--- deaths by bot"; grep " died at " $W/window.log | grep -o "Bot [A-Za-z]*" | sort | uniq -c | sort -rn | head -6
} >> "$OUT"
# the day plans given up, one journey a line for NavmeshTool replay; the cause in one word, as the replay counts them
grep "BotDay - Bot .* cannot get to" $W/window.log \
  | sed -nE 's/.*\(map ([0-9]+) from (-?[0-9]+) (-?[0-9]+) (-?[0-9]+) to (-?[0-9]+) (-?[0-9]+) (-?[0-9]+), ([^)]+)\)$/\1 \2 \3 \4 \5 \6 \7 \8/p' \
  | sed -E 's/ search gave up, long$/ long/; s/ search gave up again$/ again/; s/ no way$/ noway/; s/ goal cut off$/ cutoff/; s/ /-/8g' \
  > "${OUT%.txt}-journeys.txt"
rm -rf $W
echo "$OUT"
