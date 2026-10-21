const assert = require('node:assert/strict');
const test = require('node:test');
const {RadioPlayerController} = require('./radio-player.js');

class FakeAudio extends EventTarget {
  constructor() {
    super();
    this.src = '';
    this.currentSrc = '';
    this.playCalls = 0;
    this.pauseCalls = 0;
    this.loadCalls = 0;
    this.pending = [];
  }

  play() {
    this.playCalls += 1;
    this.currentSrc = this.src;
    return new Promise((resolve, reject) => this.pending.push({resolve, reject}));
  }

  pause() { this.pauseCalls += 1; this.dispatchEvent(new Event('pause')); }
  load() { this.loadCalls += 1; }
  removeAttribute(name) { if (name === 'src') { this.src = ''; this.currentSrc = ''; } }
  resolveNext() { this.pending.shift().resolve(); }
  rejectNext() { this.pending.shift().reject(new Error('stream failure')); }
  event(name) { this.dispatchEvent(new Event(name)); }
}

const stations = [
  {id: 'edm', genre: 'EDM', name: 'Test EDM', streamUrl: 'https://example.test/edm'},
  {id: 'classical', genre: 'Classical', name: 'Test Classical', streamUrl: 'https://example.test/classical'}
];

test('rapid station switches ignore a stale play resolution and retain one audio element', async () => {
  const audio = new FakeAudio();
  const controller = new RadioPlayerController({stations, audio});
  const first = controller.playStation('edm');
  const second = controller.playStation('classical');
  audio.resolveNext();
  await first;
  assert.equal(controller.snapshot().station.id, 'classical');
  assert.equal(controller.snapshot().state, 'loading');
  audio.resolveNext();
  await second;
  assert.equal(controller.snapshot().state, 'playing');
  assert.equal(controller.audio, audio);
  assert.equal(audio.playCalls, 2);
  controller.destroy();
});

test('a buffering timeout reports an error and invalidates its pending start', async () => {
  const audio = new FakeAudio();
  let timer;
  const controller = new RadioPlayerController({
    stations,
    audio,
    timeoutMs: 20,
    setTimeoutFn: callback => { timer = callback; return 1; },
    clearTimeoutFn: () => {}
  });
  const start = controller.playStation('edm');
  timer();
  assert.equal(controller.snapshot().state, 'error');
  assert.equal(audio.pauseCalls > 0, true);
  audio.resolveNext();
  assert.equal(await start, false);
  assert.equal(controller.snapshot().state, 'error');
  controller.destroy();
});

test('an explicit pause blocks a delayed playing event and a mismatched source', async () => {
  const audio = new FakeAudio();
  const controller = new RadioPlayerController({stations, audio});
  const start = controller.playStation('edm');
  audio.resolveNext();
  await start;
  controller.pause();
  const pausesAfterUserAction = audio.pauseCalls;
  audio.event('playing');
  assert.equal(controller.snapshot().state, 'paused');
  assert.equal(audio.pauseCalls, pausesAfterUserAction + 1);

  const pending = controller.resume();
  audio.currentSrc = stations[1].streamUrl;
  audio.event('playing');
  assert.equal(controller.snapshot().state, 'loading');
  assert.equal(audio.pauseCalls, pausesAfterUserAction + 2);
  audio.resolveNext();
  assert.equal(await pending, false);
  controller.destroy();
});

test('retry resumes a selected station and Media Session play never toggles active playback', async () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const handlers = new Map();
  const mediaSession = {
    playbackState: 'none',
    setActionHandler(action, handler) { handlers.set(action, handler); }
  };
  Object.defineProperty(globalThis, 'navigator', {configurable: true, value: {mediaSession}});
  try {
    const audio = new FakeAudio();
    const controller = new RadioPlayerController({stations, audio});
    const start = controller.playStation('edm');
    audio.rejectNext();
    await start;
    assert.equal(controller.snapshot().state, 'error');
    const retry = controller.retry();
    audio.resolveNext();
    await retry;
    assert.equal(controller.snapshot().state, 'playing');
    assert.equal(mediaSession.playbackState, 'playing');
    const playsBeforeMediaAction = audio.playCalls;
    await handlers.get('play')();
    assert.equal(controller.snapshot().state, 'playing');
    assert.equal(audio.playCalls, playsBeforeMediaAction);
    controller.pause();
    assert.equal(mediaSession.playbackState, 'paused');
    controller.destroy();
  } finally {
    if (descriptor) Object.defineProperty(globalThis, 'navigator', descriptor);
    else delete globalThis.navigator;
  }
});
