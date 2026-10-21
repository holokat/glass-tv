(() => {
  const screen = document.querySelector('[data-screen]');
  const row = screen.querySelector('.app-row');
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const video = screen.querySelector('[data-hero-video]');
  const motionButton = document.querySelector('[data-hero-motion]');
  let reefSelected = true;
  let motionPaused = false;
  let motionFailed = false;
  let heroVisible = screen.getBoundingClientRect().top < innerHeight;
  const canAnimate = () => reefSelected && !reducedMotion.matches && !motionFailed;
  const shouldPlay = () => canAnimate() && !motionPaused && heroVisible && !document.hidden;
  const updateMotion = () => {
    motionButton.hidden = !canAnimate();
    const label = motionPaused ? 'Play background animation' : 'Pause background animation';
    motionButton.setAttribute('aria-label', label);
    motionButton.title = label;
    motionButton.querySelector('img').src = motionPaused ? 'assets/ui-icons/play.png' : 'assets/ui-icons/pause.png';
    if (!canAnimate()) screen.classList.remove('motion-ready');
    if (!shouldPlay()) { video.pause(); return; }
    if (!video.hasAttribute('src')) video.src = video.dataset.src;
    video.play().catch(() => {
      // An interrupted play request is expected when scrolling away or switching wallpaper.
      if (shouldPlay()) {
        motionPaused = true;
        updateMotion();
      }
    });
  };
  video.addEventListener('playing', () => {
    if (shouldPlay()) screen.classList.add('motion-ready');
    else video.pause();
  });
  video.addEventListener('error', () => { motionFailed = true; updateMotion(); });
  motionButton.addEventListener('click', () => { motionPaused = !motionPaused; updateMotion(); });
  document.addEventListener('visibilitychange', updateMotion);
  reducedMotion.addEventListener('change', updateMotion);
  if ('IntersectionObserver' in window) {
    const videoObserver = new IntersectionObserver(entries => {
      heroVisible = entries[0].isIntersecting;
      updateMotion();
    });
    videoObserver.observe(screen);
  }
  updateMotion();
  const setPressed = (buttons, current) => buttons.forEach(button => {
    button.classList.toggle('active', button === current);
    button.setAttribute('aria-pressed', String(button === current));
  });
  const styles = [...document.querySelectorAll('[data-style]')];
  styles.forEach(button => button.addEventListener('click', () => {
    const cards = button.dataset.style === 'cards';
    screen.classList.toggle('cards', cards);
    row.querySelectorAll('img').forEach(image => { image.src = cards ? image.dataset.color : image.dataset.minimal; });
    row.scrollTo({left:0,behavior:'instant'});
    setPressed(styles, button);
  }));
  const wallpapers = [...document.querySelectorAll('[data-wallpaper]')];
  wallpapers.forEach(button => button.addEventListener('click', () => {
    const reef = button.dataset.wallpaper === 'reef';
    reefSelected = reef;
    updateMotion();
    const image = screen.querySelector('.wallpaper');
    image.src = reef ? 'assets/images/oled-reef.png' : 'assets/images/coastal-sunset.png';
    image.alt = reef ? 'Vivid teal anemones and orange coral in deep blue water' : 'Coastal mountains at sunset';
    setPressed(wallpapers, button);
  }));
  const apps = [...row.querySelectorAll('.app-item')];
  const select = (button) => apps.forEach(app => {
    app.classList.toggle('selected', app === button);
    app.setAttribute('aria-pressed', String(app === button));
  });
  apps.forEach((button, index) => {
    button.addEventListener('click', () => select(button));
    button.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? apps.length - 1 : Math.max(0, Math.min(apps.length - 1, index + (event.key === 'ArrowRight' ? 1 : -1)));
      apps[next].focus({preventScroll:true});
      select(apps[next]);
      apps[next].scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});
    });
  });
  const weatherButtons = [...document.querySelectorAll('[data-weather]')];
  weatherButtons.forEach(button => button.addEventListener('click', () => {
    const gallery = document.querySelector('.weather-gallery');
    gallery.classList.toggle('minimal', button.dataset.weather === 'minimal');
    gallery.classList.toggle('inline', button.dataset.weather === 'inline');
    setPressed(weatherButtons, button);
  }));
  // Cover every section's content, including controls and captions, without
  // hiding an entire tall section before its lower content reaches the screen.
  const revealTargets = [...document.querySelectorAll('main > section > *, footer')];
  let observer;
  const reveal = element => {
    element.classList.remove('will-reveal');
    element.classList.add('revealed');
    observer?.unobserve(element);
  };
  const revealAll = () => {
    observer?.disconnect();
    revealTargets.forEach(reveal);
  };
  if (!reducedMotion.matches && 'IntersectionObserver' in window) {
    try {
      observer = new IntersectionObserver(entries => {
        entries.forEach(entry => { if (entry.isIntersecting) reveal(entry.target); });
      }, {threshold: 0, rootMargin: '0px 0px -24px 0px'});
      revealTargets.forEach(element => {
        element.setAttribute('data-reveal', '');
        element.classList.add('will-reveal');
      });
      // Establish the hidden frame before observing, including on initial load.
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (reducedMotion.matches) return revealAll();
        revealTargets.forEach(element => observer.observe(element));
      }));
      document.addEventListener('focusin', event => {
        const target = event.target.closest('[data-reveal]');
        if (target) reveal(target);
      });
      reducedMotion.addEventListener('change', event => {
        if (event.matches) revealAll();
      });
    } catch {
      revealAll();
    }
  }
})();

// Move only the image clipped inside the shared footer wordmark.
(() => {
  const footer = document.querySelector('.site-footer');
  const wordmark = footer?.querySelector('.footer-wordmark');
  if (!wordmark) return;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const hover = matchMedia('(any-hover: hover)');
  let frame = 0;
  let pointer = null;
  const reset = () => {
    cancelAnimationFrame(frame);
    frame = 0;
    pointer = null;
    footer.classList.remove('is-tracking');
    wordmark.style.removeProperty('--reef-x');
    wordmark.style.removeProperty('--reef-y');
  };
  footer.addEventListener('pointermove', event => {
    if (event.pointerType === 'touch' || reduced.matches || !hover.matches || document.hidden) return;
    pointer = {x: event.clientX, y: event.clientY};
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      if (!pointer) return;
      const rect = footer.getBoundingClientRect();
      const clamp = value => Math.max(-1, Math.min(1, value));
      const x = clamp((pointer.x - rect.left) / rect.width * 2 - 1);
      const y = clamp((pointer.y - rect.top) / rect.height * 2 - 1);
      footer.classList.add('is-tracking');
      wordmark.style.setProperty('--reef-x', `${(x * 32).toFixed(2)}px`);
      wordmark.style.setProperty('--reef-y', `${(y * 22).toFixed(2)}px`);
    });
  });
  footer.addEventListener('pointerleave', reset);
  footer.addEventListener('pointercancel', reset);
  reduced.addEventListener('change', reset);
  hover.addEventListener('change', reset);
  document.addEventListener('visibilitychange', () => { if (document.hidden) reset(); });
  window.addEventListener('pagehide', reset);
})();
