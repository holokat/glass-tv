(() => {
  const screen = document.querySelector('[data-screen]');
  const row = screen.querySelector('.app-row');
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
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
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
