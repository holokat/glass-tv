(() => {
  const root = document.querySelector('[data-movie-demo]');
  if (!root) return;
  const films = [
    {id:'spring', title:'Spring', year:2019, type:'Fantasy', url:'https://studio.blender.org/projects/spring/'},
    {id:'sintel', title:'Sintel', year:2010, type:'Fantasy', url:'https://durian.blender.org/'},
    {id:'tears-of-steel', title:'Tears of Steel', year:2012, type:'Science fiction', ext:'png', url:'https://mango.blender.org/'},
    {id:'cosmos-laundromat', title:'Cosmos Laundromat', year:2015, type:'Animation', url:'https://gooseberry.blender.org/'},
    {id:'big-buck-bunny', title:'Big Buck Bunny', year:2008, type:'Comedy', url:'https://peach.blender.org/'},
    {id:'elephants-dream', title:'Elephants Dream', year:2006, type:'Animation', url:'https://orange.blender.org/'}
  ];
  const favorites = new Set(), watched = new Set();
  let collection = 'all', selected = null, opener = null;
  const grid = root.querySelector('[data-movie-grid]');
  const search = root.querySelector('[data-movie-search]');
  const sort = root.querySelector('[data-movie-sort]');
  const dialog = root.querySelector('[data-movie-dialog]');
  const feedback = root.querySelector('[data-movie-feedback]');
  const poster = film => `assets/movie-posters/${film.id}.${film.ext || 'jpg'}`;
  const icon = name => `<img class="icon" src="assets/ui-icons/${name}.png" alt="">`;
  const action = (film, kind) => {
    const active = (kind === 'favorite' ? favorites : watched).has(film.id);
    const label = kind === 'favorite' ? (active ? 'Remove from favorites' : 'Add to favorites') : (active ? 'Mark unwatched' : 'Mark watched');
    return `<button type="button" data-movie-action="${kind}" data-id="${film.id}" aria-pressed="${active}" aria-label="${label}: ${film.title}" title="${label}">${icon(kind === 'favorite' ? 'favorite' : 'check')}</button>`;
  };
  const nodes = new Map(films.map(film => {
    const card = document.createElement('article');
    card.className = 'movie-card';
    card.innerHTML = `<div class="movie-poster-wrap"><button type="button" class="movie-poster" data-movie-open="${film.id}" aria-label="Explore ${film.title}"><img src="${poster(film)}" alt="${film.title} poster" loading="lazy" width="500" height="707"><span class="movie-poster-prompt">Explore film</span></button><div class="movie-card-actions">${action(film,'favorite')}${action(film,'watched')}</div></div><button class="movie-card-title" type="button" data-movie-open="${film.id}">${film.title}</button><p>${film.year} · ${film.type}</p>`;
    grid.append(card);
    return [film.id, card];
  }));
  function render() {
    const focused = document.activeElement;
    const query = search.value.trim().toLocaleLowerCase();
    const ordered = [...films];
    if (sort.value === 'title') ordered.sort((a,b) => a.title.localeCompare(b.title));
    if (sort.value === 'year') ordered.sort((a,b) => b.year-a.year);
    let count = 0;
    for (const film of ordered) {
      const card = nodes.get(film.id);
      const show = `${film.title} ${film.year} ${film.type}`.toLocaleLowerCase().includes(query) && (collection === 'all' || (collection === 'favorites' ? favorites : watched).has(film.id));
      card.hidden = !show;
      if(show) count++;
      grid.append(card);
      for (const kind of ['favorite','watched']) {
        const button = card.querySelector(`[data-movie-action="${kind}"]`);
        const active = (kind === 'favorite' ? favorites : watched).has(film.id);
        button.setAttribute('aria-pressed', String(active));
        const label = kind === 'favorite' ? (active ? 'Remove from favorites' : 'Add to favorites') : (active ? 'Mark unwatched' : 'Mark watched');
        button.setAttribute('aria-label', `${label}: ${film.title}`);
        button.title = label;
      }
    }
    root.querySelector('[data-movie-count]').textContent = `${count} ${count === 1 ? 'movie' : 'movies'}`;
    root.querySelector('[data-movie-empty]').hidden = count !== 0;
    root.querySelector('[data-movie-empty-text]').textContent = query ? 'Try another title or clear your search.' : collection === 'favorites' ? 'Tap a heart to save a movie here.' : 'Mark a movie watched to find it here.';
    root.querySelectorAll('[data-collection]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.collection === collection)));
    if (focused && root.contains(focused) && !focused.closest('[hidden]')) focused.focus({preventScroll:true});
  }
  function showDetails() {
    dialog.classList.remove('is-credits');
    dialog.setAttribute('aria-labelledby','movie-detail-title');
    root.querySelector('[data-movie-detail]').innerHTML = `<img class="movie-detail-poster" src="${poster(selected)}" alt="${selected.title} poster"><div class="movie-detail-copy"><p class="movie-detail-meta">${selected.year} · ${selected.type}</p><h3 id="movie-detail-title">${selected.title}</h3><p>Blender open film</p><div class="movie-detail-actions">${action(selected,'favorite')}${action(selected,'watched')}</div><a class="movie-film-link" href="${selected.url}" target="_blank" rel="noopener noreferrer">View film on Blender ${icon('arrow_right')}</a><small>Explore this sample library. Your own movies stay on your computer.</small></div>`;
  }
  root.addEventListener('click', event => {
    const open = event.target.closest('[data-movie-open]');
    if(open) { selected=films.find(f=>f.id===open.dataset.movieOpen);opener=open;showDetails();dialog.showModal();return; }
    const toggle = event.target.closest('[data-movie-action]');
    if(toggle) {
      const film = films.find(f=>f.id===toggle.dataset.id), kind=toggle.dataset.movieAction;
      const set = kind === 'favorite' ? favorites : watched;
      set.has(film.id) ? set.delete(film.id) : set.add(film.id);
      feedback.textContent = `${film.title} ${kind === 'favorite' ? (set.has(film.id) ? 'added to favorites.' : 'removed from favorites.') : (set.has(film.id) ? 'marked watched.' : 'marked unwatched.')}`;
      render();
      if(dialog.open) {showDetails();dialog.querySelector(`[data-movie-action="${kind}"]`).focus();}
      else if(nodes.get(film.id).hidden) root.querySelector(`[data-collection="${collection}"]`).focus();
      return;
    }
    const tab=event.target.closest('[data-collection]');
    if(tab) {collection=tab.dataset.collection;render();}
    if(event.target.closest('[data-movie-reset]')) {favorites.clear();watched.clear();search.value='';sort.value='curated';collection='all';render();feedback.textContent='Demo reset. Select a poster to explore.';}
    if(event.target.closest('[data-movie-show-all]')) {search.value='';collection='all';render();search.focus();}
    if(event.target.closest('[data-movie-close]')) dialog.close();
  });
  dialog.addEventListener('click', event => {if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)dialog.close();}});
  dialog.addEventListener('close',()=>{if(opener && !opener.closest('[hidden]'))opener.focus();else root.querySelector(`[data-collection="${collection}"]`).focus();});
  document.querySelector('[data-movie-credits]').addEventListener('click', event => {
    opener=event.currentTarget;dialog.classList.add('is-credits');dialog.setAttribute('aria-labelledby','movie-credits-title');
    root.querySelector('[data-movie-detail]').innerHTML="<div class=\"movie-credits-list\"><h3 id=\"movie-credits-title\">Artwork credits</h3><p>Real open-film posters, resized for this demo. Films are not bundled with Glass TV.</p><article><strong>Big Buck Bunny</strong><p>(c) copyright Blender Foundation | www.bigbuckbunny.org</p><a href=\"https://commons.wikimedia.org/wiki/File:Big_buck_bunny_poster_big.jpg\" target=\"_blank\" rel=\"noopener noreferrer\">Original poster</a> \u00b7 <a href=\"https://creativecommons.org/licenses/by/3.0\" target=\"_blank\" rel=\"noopener noreferrer\">CC BY 3.0</a></article><article><strong>Elephants Dream</strong><p>Bassam Kurdali, Andy Goralczyk, and the Blender Foundation</p><a href=\"https://commons.wikimedia.org/wiki/File:ElephantsDreamPoster.jpg\" target=\"_blank\" rel=\"noopener noreferrer\">Original poster</a> \u00b7 <a href=\"https://creativecommons.org/licenses/by/4.0\" target=\"_blank\" rel=\"noopener noreferrer\">CC BY 4.0</a></article><article><strong>Sintel</strong><p>\u00a9 copyright Blender Foundation \u2013 durian.blender.org</p><a href=\"https://commons.wikimedia.org/wiki/File:Sintel_poster.jpg\" target=\"_blank\" rel=\"noopener noreferrer\">Original poster</a> \u00b7 <a href=\"https://creativecommons.org/licenses/by/3.0\" target=\"_blank\" rel=\"noopener noreferrer\">CC BY 3.0</a></article><article><strong>Spring</strong><p>Blender Cloud</p><a href=\"https://commons.wikimedia.org/wiki/File:Spring2019PillarPosterBlender.jpg\" target=\"_blank\" rel=\"noopener noreferrer\">Original poster</a> \u00b7 <a href=\"https://creativecommons.org/licenses/by/4.0\" target=\"_blank\" rel=\"noopener noreferrer\">CC BY 4.0</a></article><article><strong>Tears Of Steel</strong><p>(CC) Blender Foundation | Project Mango</p><a href=\"https://commons.wikimedia.org/wiki/File:Tos-poster.png\" target=\"_blank\" rel=\"noopener noreferrer\">Original poster</a> \u00b7 <a href=\"https://creativecommons.org/licenses/by/3.0\" target=\"_blank\" rel=\"noopener noreferrer\">CC BY 3.0</a></article><article><strong>Cosmos Laundromat</strong><p>CC-BY 2015, Blender Foundation, cloud.blender.org</p><a href=\"https://commons.wikimedia.org/wiki/File:CosmosLaundromatPoster.jpg\" target=\"_blank\" rel=\"noopener noreferrer\">Original poster</a> \u00b7 <a href=\"https://creativecommons.org/licenses/by/4.0\" target=\"_blank\" rel=\"noopener noreferrer\">CC BY 4.0</a></article></div>";dialog.showModal();
  });
  search.addEventListener('input',render);sort.addEventListener('change',render);
  render();
})();
