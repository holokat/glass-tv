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
  if (!matchMedia('(prefers-reduced-motion: reduce)').matches && 'IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => entries.forEach(entry => {
      if (entry.isIntersecting) { entry.target.classList.add('revealed'); entry.target.classList.remove('will-reveal'); observer.unobserve(entry.target); }
    }), {threshold:.08, rootMargin:'0px 0px 30px 0px'});
    document.querySelectorAll('[data-reveal]').forEach(element => {
      if (element.getBoundingClientRect().top > innerHeight) element.classList.add('will-reveal');
      observer.observe(element);
    });
  }
})();
