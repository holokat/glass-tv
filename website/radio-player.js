(() => {
  // Verified direct 320 kbps streams; evidence is recorded in radio-sources.json.
  const STATIONS = [
  {
    "id": "edm",
    "genre": "EDM",
    "name": "Extreme Deep House Radio",
    "homepage": "https://www.extremedeephouse.com/",
    "streamUrl": "https://stream1.rcast.net/72921",
    "artwork": "assets/images/radio-edm.webp",
    "accent": "#59d9ed",
    "accentSoft": "#b5f5ff"
  },
  {
    "id": "classical",
    "genre": "Classical",
    "name": "MR3 - Bartók Rádió",
    "homepage": "https://mediaklikk.hu/bartok/",
    "streamUrl": "https://mr-stream.connectmedia.hu/4741/mr3.mp3",
    "artwork": "assets/images/radio-classical.webp",
    "accent": "#edbc6a",
    "accentSoft": "#ffe0a3"
  },
  {
    "id": "hits",
    "genre": "Top hits",
    "name": "Radio Vacanța Fresh",
    "homepage": "https://www.radiovacanta.ro/live-radio-vacanta-fresh",
    "streamUrl": "https://stream4.srr.ro:8443/fresh_rv",
    "artwork": "assets/images/radio-hits.webp",
    "accent": "#ff7567",
    "accentSoft": "#ffafa5"
  }
];

  class RadioPlayerController {
    constructor({stations, audio, onStateChange = () => {}, timeoutMs = 20000, setTimeoutFn = (callback, delay) => setTimeout(callback, delay), clearTimeoutFn = handle => clearTimeout(handle)}) {
      this.stations = stations;
      this.audio = audio;
      this.onStateChange = onStateChange;
      this.timeoutMs = timeoutMs;
      this.setTimeout = setTimeoutFn;
      this.clearTimeout = clearTimeoutFn;
      this.activeStation = null;
      this.state = 'idle';
      this.request = 0;
      this.timeoutId = null;
      this.desiredPlayback = false;
      this.destroyed = false;
      this.onPlaying = () => this.handlePlaybackEvent('playing');
      this.onPause = () => this.handlePlaybackEvent('pause');
      this.onWaiting = () => this.handlePlaybackEvent('loading');
      this.onStalled = () => this.handlePlaybackEvent('loading');
      this.onError = () => this.handlePlaybackEvent('error');
      this.onEnded = () => this.handlePlaybackEvent('ended');
      this.listeners = [['playing', this.onPlaying], ['pause', this.onPause], ['waiting', this.onWaiting], ['stalled', this.onStalled], ['error', this.onError], ['ended', this.onEnded]];
      this.listeners.forEach(([event, handler]) => this.audio.addEventListener(event, handler));
      this.updateMediaSessionActions();
      this.emit();
    }

    snapshot() { return {state: this.state, station: this.activeStation}; }
    emit() { this.onStateChange(this.snapshot()); }

    setState(state) {
      if (this.destroyed) return;
      this.state = state;
      this.updateMediaSessionPlaybackState();
      this.emit();
    }

    stationFor(id) { return this.stations.find(station => station.id === id) || null; }

    clearPlaybackTimeout() {
      if (this.timeoutId !== null) this.clearTimeout(this.timeoutId);
      this.timeoutId = null;
    }

    startPlaybackTimeout(token, station) {
      this.clearPlaybackTimeout();
      this.timeoutId = this.setTimeout(() => {
        if (this.destroyed || !this.desiredPlayback || token !== this.request || this.activeStation?.id !== station.id || this.state !== 'loading') return;
        this.desiredPlayback = false;
        this.request += 1;
        this.audio.pause();
        this.setState('error');
      }, this.timeoutMs);
    }

    matchesActiveSource() {
      if (!this.activeStation?.streamUrl) return false;
      const current = this.audio.currentSrc || this.audio.src;
      if (!current) return true;
      try {
        if (typeof document === 'undefined') return current === this.activeStation.streamUrl;
        return new URL(current, document.baseURI).href === new URL(this.activeStation.streamUrl, document.baseURI).href;
      } catch {
        return current === this.activeStation.streamUrl;
      }
    }

    async startPlayback(station, isSwitching) {
      const token = ++this.request;
      if (isSwitching) {
        this.desiredPlayback = false;
        this.resetAudio();
      }
      this.desiredPlayback = true;
      this.activeStation = station;
      this.audio.src = station.streamUrl;
      this.audio.load();
      this.setState('loading');
      this.startPlaybackTimeout(token, station);
      this.updateMediaSessionMetadata(station);

      try {
        await this.audio.play();
        if (this.destroyed || !this.desiredPlayback || token !== this.request || this.activeStation?.id !== station.id || !this.matchesActiveSource()) return false;
        this.clearPlaybackTimeout();
        this.setState('playing');
        return true;
      } catch {
        if (this.destroyed || !this.desiredPlayback || token !== this.request || this.activeStation?.id !== station.id) return false;
        this.desiredPlayback = false;
        this.clearPlaybackTimeout();
        this.setState('error');
        return false;
      }
    }

    playStation(id) {
      const station = this.stationFor(id);
      if (!station || this.destroyed) return Promise.resolve(false);
      if (!station.streamUrl) {
        this.request += 1;
        this.desiredPlayback = false;
        this.clearPlaybackTimeout();
        if (this.activeStation?.id !== station.id) this.resetAudio();
        this.activeStation = station;
        this.setState('unconfigured');
        return Promise.resolve(false);
      }
      if (this.activeStation?.id === station.id && ['playing', 'loading'].includes(this.state)) {
        this.pause();
        return Promise.resolve(false);
      }
      return this.startPlayback(station, this.activeStation?.id !== station.id);
    }

    resume() {
      if (!this.activeStation || this.destroyed || !this.activeStation.streamUrl) return Promise.resolve(false);
      if (['playing', 'loading'].includes(this.state)) return Promise.resolve(true);
      return this.startPlayback(this.activeStation, false);
    }

    pause() {
      if (!this.activeStation || this.destroyed) return;
      this.desiredPlayback = false;
      this.request += 1;
      this.clearPlaybackTimeout();
      this.audio.pause();
      this.setState('paused');
    }

    retry() { return this.resume(); }

    resetAudio() {
      this.clearPlaybackTimeout();
      this.audio.pause();
      this.audio.removeAttribute('src');
      this.audio.load();
    }

    handlePlaybackEvent(nextState) {
      if (!this.activeStation || this.destroyed) return;
      if (nextState === 'playing') {
        if (!this.desiredPlayback || !this.matchesActiveSource()) {
          this.audio.pause();
          return;
        }
        this.clearPlaybackTimeout();
        this.setState('playing');
        return;
      }
      if (nextState === 'pause') {
        if (this.desiredPlayback && this.state === 'playing') {
          this.desiredPlayback = false;
          this.clearPlaybackTimeout();
          this.setState('paused');
        }
        return;
      }
      if (nextState === 'loading' && this.desiredPlayback && this.matchesActiveSource()) {
        this.setState('loading');
        this.startPlaybackTimeout(this.request, this.activeStation);
        return;
      }
      if (nextState === 'error' && this.desiredPlayback && this.matchesActiveSource()) {
        this.desiredPlayback = false;
        this.clearPlaybackTimeout();
        this.setState('error');
        return;
      }
      if (nextState === 'ended' && this.matchesActiveSource()) {
        this.desiredPlayback = false;
        this.clearPlaybackTimeout();
        this.setState('paused');
      }
    }

    updateMediaSessionActions() {
      if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
      try {
        navigator.mediaSession.setActionHandler('play', () => this.resume());
        navigator.mediaSession.setActionHandler('pause', () => this.pause());
      } catch {}
    }

    updateMediaSessionPlaybackState() {
      if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
      try { navigator.mediaSession.playbackState = this.state === 'playing' ? 'playing' : this.activeStation ? 'paused' : 'none'; } catch {}
    }

    updateMediaSessionMetadata(station) {
      if (!station.name || typeof navigator === 'undefined' || !('mediaSession' in navigator) || typeof MediaMetadata === 'undefined') return;
      navigator.mediaSession.metadata = new MediaMetadata({title: station.name, artist: station.genre, album: 'Glass TV'});
    }

    clearMediaSession() {
      if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
      try {
        navigator.mediaSession.metadata = null;
        navigator.mediaSession.playbackState = 'none';
        navigator.mediaSession.setActionHandler('play', null);
        navigator.mediaSession.setActionHandler('pause', null);
      } catch {}
    }

    destroy() {
      if (this.destroyed) return;
      this.desiredPlayback = false;
      this.destroyed = true;
      this.request += 1;
      this.resetAudio();
      this.listeners.forEach(([event, handler]) => this.audio.removeEventListener(event, handler));
      this.clearMediaSession();
    }
  }

  const describeStation = station => station.name ? `${station.genre}, ${station.name}` : `${station.genre} station`;

  const mountRadioPlayer = root => {
    const list = root.querySelector('[data-radio-stations]');
    const status = root.querySelector('[data-radio-status]');
    if (!list || !status) return null;
    const audio = new Audio();
    audio.preload = 'none';
    // Deliberately no crossOrigin setting or proxy: streams must work directly in the browser.
    const buttons = new Map();
    let controller;

    const update = ({state, station}) => {
      root.dataset.state = state;
      if (station) {
        root.style.setProperty('--radio-accent', station.accent);
        root.style.setProperty('--radio-accent-soft', station.accentSoft);
      }
      buttons.forEach((button, id) => {
        const isCurrent = station?.id === id;
        const isPlaying = isCurrent && state === 'playing';
        const isLoading = isCurrent && state === 'loading';
        const item = STATIONS.find(candidate => candidate.id === id);
        button.classList.toggle('is-playing', isPlaying);
        button.classList.toggle('is-loading', isLoading);
        button.setAttribute('aria-pressed', String(isPlaying));
        const action = isPlaying || isLoading ? 'Pause' : state === 'unconfigured' && isCurrent ? 'Station configuration required for' : 'Play';
        button.setAttribute('aria-label', `${action} ${describeStation(item)}`);
        button.querySelector('.radio-control-icon').src = isPlaying || isLoading ? 'assets/ui-icons/pause.png' : 'assets/ui-icons/play.png';
      });
      if (!station) status.textContent = 'Live radio · 320 kbps';
      else if (state === 'unconfigured') status.textContent = `${station.genre} station is not configured.`;
      else if (state === 'loading') status.textContent = `Loading ${describeStation(station)}.`;
      else if (state === 'playing') status.textContent = `Playing ${describeStation(station)}.`;
      else if (state === 'paused') status.textContent = `Paused ${describeStation(station)}.`;
      else if (state === 'error') status.textContent = `Unable to play ${describeStation(station)}. Try again.`;
    };

    controller = new RadioPlayerController({stations: STATIONS, audio, onStateChange: update});
    STATIONS.forEach(station => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'radio-station';
      button.style.setProperty('--station-accent', station.accent);
      button.style.setProperty('--station-accent-soft', station.accentSoft);
      const circle = document.createElement('span');
      circle.className = 'radio-station-circle';
      const artwork = document.createElement('img');
      artwork.className = 'radio-artwork';
      artwork.src = station.artwork;
      artwork.alt = '';
      artwork.addEventListener('error', () => { artwork.hidden = true; }, {once: true});
      const control = document.createElement('span');
      control.className = 'radio-control';
      const icon = document.createElement('img');
      icon.className = 'icon radio-control-icon';
      icon.src = 'assets/ui-icons/play.png';
      icon.alt = '';
      control.append(icon);
      circle.append(artwork, control);
      const genre = document.createElement('span');
      genre.className = 'radio-genre';
      genre.textContent = station.genre;
      const identity = document.createElement('a');
      identity.className = 'radio-station-name';
      identity.textContent = station.name;
      identity.href = station.homepage;
      identity.target = '_blank';
      identity.rel = 'noopener noreferrer';
      identity.hidden = !station.name;
      button.append(circle, genre);
      button.addEventListener('click', () => controller.playStation(station.id));
      const card = document.createElement('div');
      card.className = 'radio-station-card';
      card.append(button, identity);
      list.append(card);
      buttons.set(station.id, button);
    });
    update(controller.snapshot());
    window.addEventListener('pagehide', event => {
      if (event.persisted) controller.pause();
      else controller.destroy();
    });
    return controller;
  };

  const api = {STATIONS, RadioPlayerController, mountRadioPlayer};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') {
    window.GlassTVRadioPlayer = api;
    const root = document.querySelector('[data-radio-player]');
    if (root) mountRadioPlayer(root);
  }
})();
