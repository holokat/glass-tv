(() => {
  const gallery = document.querySelector('.weather-gallery');
  if (!gallery) return;
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const hoverAvailable = matchMedia('(any-hover: hover)');
  const scenic = () => !gallery.classList.contains('minimal') && !gallery.classList.contains('inline');

  const cards = [...gallery.querySelectorAll('.city-card')].map(card => {
    const video = card.querySelector('[data-city-video]');
    if (!video) return null;
    let hovered = false;
    let focused = false;
    let visible = false;
    let failed = false;
    let resetTimer;
    let request = 0;
    video.muted = true;
    const permitted = () => !failed && !reducedMotion.matches && scenic() && visible && !document.hidden;
    const wanted = () => permitted() && ((hovered && hoverAvailable.matches) || focused);
    const load = () => {
      if (!video.hasAttribute('src')) {
        video.preload = 'auto';
        video.src = video.dataset.src;
        video.load();
      }
    };
    const hide = (immediate = false) => {
      request += 1;
      clearTimeout(resetTimer);
      card.classList.remove('city-motion-ready');
      video.pause();
      const reset = () => { if (!wanted() && video.readyState) video.currentTime = 0; };
      if (immediate) reset();
      else resetTimer = setTimeout(reset, 460);
    };
    const update = () => {
      if (!wanted()) { hide(reducedMotion.matches || !visible || document.hidden); return; }
      clearTimeout(resetTimer);
      load();
      // Hold the last zoomed frame until exit, rather than jumping back in a loop.
      if (video.ended) { card.classList.add('city-motion-ready'); return; }
      const token = ++request;
      video.play().then(() => {
        if (token === request && wanted()) card.classList.add('city-motion-ready');
        else if (!wanted()) video.pause();
      }).catch(() => {
        // A quick leave or style change can cancel play while the file is loading.
        if (token === request && wanted()) hide();
      });
    };
    video.addEventListener('playing', () => {
      if (wanted()) card.classList.add('city-motion-ready');
      else hide();
    });
    video.addEventListener('error', () => { failed = true; hide(); });
    card.addEventListener('pointerenter', event => {
      if (event.pointerType !== 'touch') { hovered = true; update(); }
    });
    card.addEventListener('pointerleave', () => { hovered = false; update(); });
    card.addEventListener('focusin', () => { focused = true; update(); });
    card.addEventListener('focusout', event => {
      if (!card.contains(event.relatedTarget)) { focused = false; update(); }
    });
    return {card, update, hide, setVisible(value) {
      visible = value;
      // Warm only visible scenic cards on hover-capable devices; never play here.
      if (permitted() && hoverAvailable.matches && !navigator.connection?.saveData) load();
      update();
    }};
  }).filter(Boolean);

  const updateAll = () => cards.forEach(item => item.update());
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => cards.find(item => item.card === entry.target)?.setVisible(entry.isIntersecting));
    });
    cards.forEach(item => observer.observe(item.card));
  } else cards.forEach(item => item.setVisible(true));
  new MutationObserver(updateAll).observe(gallery, {attributes: true, attributeFilter: ['class']});
  reducedMotion.addEventListener('change', updateAll);
  hoverAvailable.addEventListener('change', updateAll);
  document.addEventListener('visibilitychange', updateAll);
  window.addEventListener('pagehide', () => cards.forEach(item => item.hide(true)));
})();
