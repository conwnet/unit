(() => {
  'use strict';
  const name = document.getElementById('name');
  const note = document.getElementById('private-note');
  const sites = document.getElementById('sites');
  const empty = document.getElementById('empty');
  const all = document.getElementById('view-all');

  function update(data) {
    for (const [key, value] of Object.entries(data.colors)) {
      document.documentElement.style.setProperty(`--${key}`, value);
    }
    name.textContent = data.incognito ? 'Incognito' : 'Unit';
    document.title = data.incognito ? 'Incognito' : 'Home';
    note.hidden = !data.incognito;
    all.hidden = data.count === 0;
    empty.hidden = data.bookmarks.length !== 0;
    sites.textContent = '';
    for (const bookmark of data.bookmarks) {
      const link = document.createElement('a');
      link.className = 'site';
      link.href = bookmark.url;
      link.title = bookmark.title;
      const icon = document.createElement('span');
      icon.className = 'monogram';
      icon.setAttribute('aria-hidden', 'true');
      icon.textContent = bookmark.host.replace(/^www\./, '').charAt(0).toUpperCase();
      const label = document.createElement('span');
      label.className = 'site-name';
      label.textContent = bookmark.title || bookmark.host;
      link.append(icon, label);
      sites.append(link);
    }
  }

  window.UnitHome = { update };
  update(JSON.parse(document.getElementById('home-data').textContent));
})();
