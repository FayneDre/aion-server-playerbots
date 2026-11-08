package com.aionemu.gameserver.playerbot.addon;

/**
 * The combat meter's page: a table of the team's damage or healing it fetches again every second and a half, a member's skills when its row is
 * clicked, and a button that starts the count over.
 * <p>
 * The client's browser is a Chromium of 2011: the script keeps to what it knows (no arrow functions, no {@code let}, no {@code fetch}), and a
 * member's bar is placed in a block inside its cell, as that browser places nothing against a table cell (a bar against the cell covered the page).
 * It draws text at its own size, smaller than the game's interface around it: the page's text is large.
 */
final class CombatMeterPage {

	private static final String HTML = """
		<!doctype html>
		<html><head><meta charset="utf-8"><title>Combat Meter</title>
		<style>
		body{margin:0;padding:6px 8px;background:#10131c;color:#e8dcc0;font:21px Arial,sans-serif;overflow:hidden}
		.bar{margin-bottom:6px;overflow:hidden}
		button{background:#2a2f3d;color:#e8dcc0;border:1px solid #4a5063;padding:3px 10px;margin-right:6px;font:20px Arial,sans-serif;cursor:pointer}
		button.on{background:#6b5a2e;border-color:#b8964a}
		#reset{float:right;margin-right:0}
		table{width:100%;border-collapse:collapse}
		td{padding:3px 4px;white-space:nowrap}
		td.n{text-align:right}
		tr.row{cursor:pointer}
		tr.row:hover{background:#1e2330}
		.name{position:relative}
		.fill{position:absolute;left:0;top:1px;bottom:1px;background:#3a3420}
		.heal .fill{background:#203a2a}
		.label{position:relative}
		.head td{color:#9a917c;font-size:17px}
		#detail{margin-top:8px;border-top:1px solid #2a2f3d;padding-top:6px;overflow-y:auto;overflow-x:hidden}
		::-webkit-scrollbar{width:14px}
		::-webkit-scrollbar-track{background:#1a1e29}
		::-webkit-scrollbar-thumb{background:#4a5063;border:1px solid #6b7287}
		.muted{color:#9a917c}
		</style></head>
		<body>
		<div class="bar"><button id="damage" class="on">Damage</button><button id="healing">Healing</button><button id="reset">Reset</button></div>
		<table id="table"></table>
		<div id="detail"></div>
		<script>
		var mode = 'damage', selected = null, query = location.search;
		function el(id) { return document.getElementById(id); }
		function esc(t) { return String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
		function num(v) { return v >= 1e6 ? (v / 1e6).toFixed(2) + 'M' : v >= 1e4 ? (v / 1e3).toFixed(1) + 'k' : String(Math.round(v)); }
		function rate(v, s) { return s > 0 ? num(v / s) : '0'; }
		function get(path, done) {
			var x = new XMLHttpRequest();
			x.onreadystatechange = function () { if (x.readyState == 4 && x.status == 200) done(JSON.parse(x.responseText)); };
			x.open('GET', path + query, true);
			x.send(null);
		}
		function render(data) {
			var heal = mode == 'healing', rows = data.members.slice(0), total = 0, top = 0, i, m, v, html;
			rows.sort(function (a, b) { return (heal ? b.healing - a.healing : b.damage - a.damage); });
			for (i = 0; i < rows.length; i++) { v = heal ? rows[i].healing : rows[i].damage; total += v; if (v > top) top = v; }
			html = '<tr class="head"><td>' + (heal ? 'Healer' : 'Name') + '</td><td class="n">' + (heal ? 'Healing' : 'Damage') + '</td><td class="n">'
				+ (heal ? 'HPS' : 'DPS') + '</td><td class="n">%</td>' + (heal ? '<td class="n">Over</td>' : '') + '</tr>';
			for (i = 0; i < rows.length; i++) {
				m = rows[i]; v = heal ? m.healing : m.damage;
				html += '<tr class="row' + (heal ? ' heal' : '') + '" data-name="' + esc(m.name) + '"><td><div class="name"><div class="fill" style="width:'
					+ (top > 0 ? Math.round(100 * v / top) : 0) + '%"></div><span class="label">' + esc(m.name) + '</span></div></td><td class="n">' + num(v)
					+ '</td><td class="n">' + rate(v, m.seconds) + '</td><td class="n">' + (total > 0 ? Math.round(100 * v / total) : 0) + '%</td>'
					+ (heal ? '<td class="n">' + (m.healing + m.overhealing > 0 ? Math.round(100 * m.overhealing / (m.healing + m.overhealing)) : 0) + '%</td>' : '')
					+ '</tr>';
			}
			el('table').innerHTML = html;
			var trs = el('table').getElementsByTagName('tr');
			for (i = 0; i < trs.length; i++)
				if (trs[i].getAttribute('data-name'))
					trs[i].onclick = function () { selected = selected == this.getAttribute('data-name') ? null : this.getAttribute('data-name'); render(data); };
			var scroll = el('detail').scrollTop;
			renderDetail(data, heal);
			fit();
			el('detail').scrollTop = scroll;
		}
		// the browser view shows no scroll bar of its own: the skills scroll in the room the window leaves below the team
		function fit() {
			var d = el('detail'), room = (window.innerHeight || document.documentElement.clientHeight) - d.offsetTop - 6 - 7;
			d.style.height = Math.max(40, room) + 'px';
		}
		window.onresize = fit;
		function renderDetail(data, heal) {
			var m = null, i, s, html, rows = [];
			for (i = 0; i < data.members.length; i++) if (data.members[i].name == selected) m = data.members[i];
			if (!m) { el('detail').innerHTML = '<span class="muted">Click a name for its skills.</span>'; return; }
			for (i = 0; i < m.skills.length; i++) { s = m.skills[i]; if ((heal ? s.healing : s.damage) > 0) rows.push(s); }
			rows.sort(function (a, b) { return heal ? b.healing - a.healing : b.damage - a.damage; });
			html = '<table><tr class="head"><td>' + esc(m.name) + '</td><td class="n">' + (heal ? 'Healing' : 'Damage') + '</td><td class="n">Uses</td><td class="n">Avg</td></tr>';
			for (i = 0; i < rows.length; i++) {
				s = rows[i];
				html += '<tr><td>' + esc(s.name) + '</td><td class="n">' + num(heal ? s.healing : s.damage) + '</td><td class="n">' + s.actions
					+ '</td><td class="n">' + num((heal ? s.healing : s.damage) / s.actions) + '</td></tr>';
			}
			el('detail').innerHTML = html + '</table>';
		}
		var last = null;
		function refresh() { get('/addon/combat-meter/data', function (d) { last = d; render(d); }); }
		function setMode(m) { mode = m; el('damage').className = m == 'damage' ? 'on' : ''; el('healing').className = m == 'healing' ? 'on' : ''; if (last) render(last); }
		el('damage').onclick = function () { setMode('damage'); };
		el('healing').onclick = function () { setMode('healing'); };
		el('reset').onclick = function () { get('/addon/combat-meter/reset', function (d) { last = d; render(d); }); };
		refresh();
		setInterval(refresh, 1500);
		</script>
		</body></html>
		""";

	private CombatMeterPage() {
	}

	static String html() {
		return HTML;
	}
}
