// Injeta sidebar + topbar comuns. Uso: <body data-nav="overview" data-crumbs="Visão geral|Operations Dashboard" data-actions='<span class="pill">...</span>'>
(function () {
  const I = {
    home: '<path d="M3 10.5 12 3l9 7.5V21H3z"/><path d="M9 21v-6h6v6"/>',
    ops: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
    govern: '<path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z"/>',
    drift: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/>',
    automate: '<path d="M4 17 10 11 13 14 20 7"/><path d="M14 7h6v6"/>',
    code: '<path d="m8 8-4 4 4 4"/><path d="m16 8 4 4-4 4"/>',
    admin: '<circle cx="9" cy="8" r="3"/><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6"/><path d="M17 11h4M19 9v4"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2"/>',
    help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 2-2.5 2-2.5 4"/><path d="M12 17h.01"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 8 3 8H3s3-1 3-8"/><path d="M10 20a2 2 0 0 0 4 0"/>'
  };
  const svg = (n) => `<svg viewBox="0 0 24 24">${I[n]}</svg>`;
  const b = document.body;
  const nav = b.dataset.nav;
  const items = [['overview', 'home'], ['operations', 'ops'], ['govern', 'govern'], ['drift', 'drift'], ['automate', 'automate'], 'sep', ['developer', 'code'], ['admin', 'admin']];
  const side = `<nav class="sidebar"><div class="logo">LM</div>${items.map((it) => it === 'sep' ? '<div class="sep"></div>' : `<a class="${it[0] === nav ? 'active' : ''}">${svg(it[1])}</a>`).join('')}<div class="bottom"><a>${svg('settings')}</a><a>${svg('help')}</a></div></nav>`;
  const crumbs = b.dataset.crumbs.split('|').map((c, i, a) => i === a.length - 1 ? `<b>${c}</b>` : `<span>${c}</span><span class="sep">›</span>`).join('');
  const top = `<div class="topbar"><div class="crumbs">${crumbs}</div><div class="spacer"></div>${b.dataset.actions || ''}<span class="bell">${svg('bell')}</span><div class="user"><div class="avatar">AS</div>Ana Souza</div></div>`;
  const content = b.innerHTML;
  b.innerHTML = `<div class="app">${side}<main class="main">${top}${content}</main></div>`;
  document.querySelectorAll('.bell svg').forEach((s) => { s.style.width = '16px'; s.style.stroke = 'currentColor'; s.style.fill = 'none'; s.style.strokeWidth = 2; });
})();
