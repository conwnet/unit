(() => {
  'use strict';
  const name = document.getElementById('name');
  const note = document.getElementById('private-note');
  const sites = document.getElementById('sites');
  const empty = document.getElementById('empty');
  const all = document.getElementById('view-all');
  const input = document.querySelector('.search input');
  const submit = document.querySelector('.search button');

  function update(data) {
    for (const [key, value] of Object.entries(data.colors)) {
      document.documentElement.style.setProperty(`--${key}`, value);
    }
    const strings = data.strings;
    document.documentElement.lang = data.language;
    name.textContent = data.incognito ? strings.incognito : 'Unit';
    document.title = data.incognito ? strings.incognito : strings.home;
    note.textContent = strings.privateNote;
    input.placeholder = strings.addressHint;
    input.setAttribute('aria-label', strings.addressHint);
    submit.setAttribute('aria-label', strings.go);
    document.getElementById('bookmarks-heading').textContent = strings.bookmarks;
    all.textContent = strings.viewAll;
    empty.textContent = strings.bookmarksHint;
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
