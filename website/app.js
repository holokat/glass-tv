(() => {
  const release = { sourceUrl: 'https://github.com/holokat/glass-tv', downloadUrl: 'https://github.com/holokat/glass-tv/releases/latest/download/Glass-TV.dmg' };
  const icon = (name, label = '') => { const image = document.createElement('img'); image.className = 'ui-icon'; image.src = `assets/ui-icons/${name}.png`; image.alt = label; return image; };
  const make = (tag, className, text) => { const element = document.createElement(tag); if (className) element.className = className; if (text) element.textContent = text; return element; };
  document.documentElement.classList.add('js');
  document.querySelectorAll('[data-source]').forEach((link) => { link.href = release.sourceUrl; });
  document.querySelectorAll('[data-download]').forEach((link) => { link.href = release.downloadUrl; });

  const iconNames = { media: 'image', movies: 'movies', apps: 'apps', layout: 'settings', tv: 'tv', weather: 'weather_sun' };
  const tabs = [...document.querySelectorAll('[role=tab][data-screen]')];
  const panels = [...document.querySelectorAll('[data-panel]')];
  const title = document.querySelector('[data-title]');
  tabs.forEach((tab) => {
    tab.prepend(icon(iconNames[tab.dataset.screen]));
    tab.id = `demo-tab-${tab.dataset.screen}`;
    tab.setAttribute('aria-controls', `demo-panel-${tab.dataset.screen}`);
  });
  panels.forEach((panel) => {
    panel.id = `demo-panel-${panel.dataset.panel}`;
    panel.setAttribute('role', 'tabpanel');
    panel.setAttribute('aria-labelledby', `demo-tab-${panel.dataset.panel}`);
    panel.tabIndex = 0;
  });
  const show = (name) => {
    tabs.forEach((tab) => { const selected = tab.dataset.screen === name; tab.setAttribute('aria-selected', String(selected)); tab.tabIndex = selected ? 0 : -1; });
    panels.forEach((panel) => { const selected = panel.dataset.panel === name; panel.classList.toggle('is-active', selected); panel.hidden = !selected; });
    title.textContent = name[0].toUpperCase() + name.slice(1);
  };
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => show(tab.dataset.screen));
    tab.addEventListener('keydown', (event) => {
      const key = event.key;
      if (!['ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft', 'Home', 'End'].includes(key)) return;
      event.preventDefault();
      const next = key === 'Home' ? 0 : key === 'End' ? tabs.length - 1 : (index + (key === 'ArrowDown' || key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      tabs[next].focus();
      show(tabs[next].dataset.screen);
    });
  });
  show('layout');

  const barAction = document.querySelector('.window-bar > span:last-child');
  barAction.replaceChildren(icon('settings', 'Settings'));
  barAction.classList.add('window-bar-action');
  const switcher = document.querySelector('.tv-switch');
  const device = make('span', 'tv-switch-copy');
  const deviceName = make('b', '', 'Sony BRAVIA');
  const deviceStatus = make('small', '', 'TV connected');
  const statusDot = make('i', 'status-dot');
  statusDot.setAttribute('aria-hidden', 'true');
  deviceStatus.prepend(statusDot);
  device.replaceChildren(deviceName, deviceStatus);
  switcher.replaceChildren(icon('tv'), device, icon('more_horizontal', 'More options'));
  switcher.setAttribute('aria-label', 'Sony BRAVIA, TV connected. More options');
  switcher.addEventListener('click', () => { show('tv'); document.getElementById('demo-tab-tv').focus(); });
  const tvActions = document.querySelector('.tv-actions');
  tvActions.replaceChildren(icon('wifi', 'Wi-Fi'), icon('settings', 'Settings'));

  const tv = document.querySelector('[data-tv-image]');
  const size = document.querySelector('[data-size-range]');
  const padding = document.querySelector('[data-padding-range]');
  const applySize = () => { tv.style.setProperty('--tile-size', `${size.value / 100}`); document.querySelector('[data-size]').textContent = `${(size.value / 100).toFixed(2)}×`; };
  const applyPadding = () => { tv.style.setProperty('--edge', `${padding.value}%`); document.querySelector('[data-padding]').textContent = `${padding.value}%`; };
  size.addEventListener('input', applySize);
  padding.addEventListener('input', applyPadding);
  document.querySelector('[data-wifi]').addEventListener('change', (event) => { document.querySelector('.tv-actions .ui-icon:first-child').hidden = !event.target.checked; });
  const snap = document.querySelector('[data-padding-range]').closest('.control-block').querySelector('.check input');
  snap.addEventListener('change', () => tv.classList.toggle('is-unsnapped', !snap.checked));
  document.querySelector('[data-reset]').addEventListener('click', () => { size.value = 100; padding.value = 4; snap.checked = true; tv.classList.remove('is-unsnapped'); applySize(); applyPadding(); });
  document.querySelector('[data-panel="layout"] select').disabled = true;
  document.querySelectorAll('[data-style]').forEach((button) => button.addEventListener('click', () => {
    const parent = button.closest('.controls');
    parent.querySelectorAll('[data-style]').forEach((node) => node.classList.toggle('is-active', node === button));
    const target = parent.classList.contains('apps-controls') ? '[data-app-preview]' : '[data-shortcuts]';
    document.querySelectorAll(target).forEach((node) => { node.dataset.style = button.dataset.style; });
  }));
  const update = document.querySelector('.update');
  update.addEventListener('click', () => { if (update.dataset.busy) return; update.dataset.busy = 'true'; update.textContent = 'Applied'; window.setTimeout(() => { update.textContent = 'Update TV'; delete update.dataset.busy; }, 1250); });

  const appPreview = [...document.querySelectorAll('[data-app-preview] img')];
  document.querySelectorAll('.apps-controls input').forEach((input, index) => input.addEventListener('change', () => {
    if (appPreview[index]) appPreview[index].hidden = !input.checked;
  }));

  const places = { london: { city: 'London', temp: '12°', condition: 'Cloudy', image: 'assets/images/london-01.jpg' }, tokyo: { city: 'Tokyo', temp: '18°', condition: 'Clear', image: 'assets/images/tokyo-03.jpg' } };
  const weatherPreview = document.querySelector('[data-weather-preview]');
  document.querySelectorAll('[data-weather]').forEach((button) => button.addEventListener('click', () => {
    const place = places[button.dataset.weather];
    document.querySelector('[data-city]').textContent = place.city;
    document.querySelector('[data-temp]').textContent = place.temp;
    document.querySelector('[data-condition]').textContent = place.condition;
    weatherPreview.style.backgroundImage = `linear-gradient(0deg, rgba(5,10,9,.76), transparent 72%), url("${place.image}")`;
    document.querySelectorAll('[data-weather]').forEach((node) => node.classList.toggle('is-selected', node === button));
  }));
  const weatherControls = document.querySelector('.weather-controls');
  const weatherSelect = weatherControls.querySelector('select');
  weatherSelect.addEventListener('change', () => weatherPreview.classList.toggle('is-minimal', weatherSelect.value === 'Minimal'));
  const weatherToggles = [...weatherControls.querySelectorAll('input')];
  weatherToggles[0].addEventListener('change', () => weatherPreview.classList.toggle('is-off', !weatherToggles[0].checked));
  weatherToggles[1].addEventListener('change', () => { weatherPreview.querySelector('small').hidden = !weatherToggles[1].checked; });
  document.querySelectorAll('select').forEach((select, index) => { select.name = `demo-select-${index + 1}`; });

  const layout = document.querySelector('.mini-layout');
  const moveDot = (dot, x, y) => { const bounds = layout.getBoundingClientRect(); const rect = dot.getBoundingClientRect(); const maxX = Math.max(0, bounds.width - rect.width); const maxY = Math.max(0, bounds.height - rect.height); dot.style.left = `${Math.min(maxX, Math.max(0, x))}px`; dot.style.top = `${Math.min(maxY, Math.max(0, y))}px`; dot.style.right = 'auto'; dot.style.bottom = 'auto'; };
  document.querySelectorAll('.mini-layout .dot').forEach((dot) => {
    dot.setAttribute('aria-label', `Move ${dot.textContent.replace(/\s+/g, ' ').trim()}`);
    dot.addEventListener('pointerdown', (event) => {
      const rect = dot.getBoundingClientRect(); const bounds = layout.getBoundingClientRect(); const offsetX = event.clientX - rect.left; const offsetY = event.clientY - rect.top;
      dot.setPointerCapture(event.pointerId); dot.classList.add('is-dragging');
      const move = (pointer) => moveDot(dot, pointer.clientX - bounds.left - offsetX, pointer.clientY - bounds.top - offsetY);
      const end = () => { dot.classList.remove('is-dragging'); dot.removeEventListener('pointermove', move); dot.removeEventListener('pointerup', end); dot.removeEventListener('pointercancel', end); };
      dot.addEventListener('pointermove', move); dot.addEventListener('pointerup', end); dot.addEventListener('pointercancel', end);
    });
    dot.addEventListener('keydown', (event) => {
      const amount = event.shiftKey ? 12 : 4; const rect = dot.getBoundingClientRect(); const bounds = layout.getBoundingClientRect();
      const delta = { ArrowLeft: [-amount, 0], ArrowRight: [amount, 0], ArrowUp: [0, -amount], ArrowDown: [0, amount] }[event.key];
      if (!delta) return; event.preventDefault(); moveDot(dot, rect.left - bounds.left + delta[0], rect.top - bounds.top + delta[1]);
    });
  });

  const observer = 'IntersectionObserver' in window ? new IntersectionObserver((entries, current) => entries.forEach((entry) => { if (entry.isIntersecting) { entry.target.classList.add('is-revealed'); current.unobserve(entry.target); } }), { threshold: .12 }) : null;
  document.querySelectorAll('[data-reveal]').forEach((element) => observer ? observer.observe(element) : element.classList.add('is-revealed'));
})();
