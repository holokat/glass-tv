# Glass TV local protocol

TV package: `local.glasstv.launcher`. Mac bundle: `local.glasstv.companion`. Protocol version: `1`.

The TV's foreground receiver service listens on HTTPS port `8743` independently of the launcher activity. Boot and package replacement restart the service without opening the launcher; leaving Home or turning the screen off no longer stops the listener. Network availability during deep standby remains controlled by the TV. Uploaded media is stored in the app's persistent files directory. Photo and video playback continues without a running Mac. Optional weather uses an Internet provider independently of media sync.

Settings and media uploads persist while another TV app is foreground. On returning to Glass TV, the activity reloads the persisted playlist. Without an attached activity, status contains persisted configuration, media, storage and actual input information; live playback, radio and weather snapshots are omitted. Movie playback/control requires an awake, foreground Glass TV activity and returns an explicit `503` otherwise.

The Mac checks paired receivers while its window is active, using an 8-second status timeout and failed-check delays of 2, 4, 8 and then 10 seconds. Healthy polling uses 20 seconds. App activation, Mac wake and network restoration request guarded immediate checks. Explicit Disconnect remains respected. A routine probe retains verified connection state until a real failure; reconnect does not apply Layout drafts automatically.

## Pairing and transport

The TV generates an ECDSA P-256 identity in AndroidKeyStore and serves TLS 1.2 with ECDHE/ECDSA AES-GCM. The Mac first reads its certificate without sending credentials. The user compares its SHA-256 fingerprint with the TV, then submits the displayed six-digit code through the pinned connection. Every authenticated request pins the same certificate. The Mac stores the pin and token in Keychain; the TV stores a token hash.

A pairing code lasts two minutes, permits five attempts and is consumed after success. Pairing another Mac replaces the previous token. Neither application logs tokens or codes. The Mac rejects redirects from the TV receiver.

| Route | Request | Response |
| --- | --- | --- |
| `POST /pair` | JSON with `apiVersion`, `code`, `clientName` | `201`: `apiVersion`, `deviceId`, `token`, `tokenType` |
| `GET /status` | Bearer token | Device ID, revision, playlist, stored media, storage, limits, inputs, selected input, playback snapshot |
| `PUT /images/{sha256}.jpg` | Bearer token, raw JPEG, fixed Content-Length | `201` new or `200` identical existing media, with metadata |
| `PUT /videos/{sha256}.mp4` | Bearer token, raw MP4, fixed Content-Length | `201` new or `200` identical existing media, with metadata |
| `PUT /playlist` | Bearer token, complete playlist, `baseRevision` | New revision, or `409` for a stale revision/missing media |
| `POST /movies/play` | Bearer token, transient movie stream descriptor | `202`: accepted movie playback snapshot |
| `GET /movies/status` | Bearer token | Movie state and confirmed playback position |
| `POST /movies/control` | Bearer token, session ID and pause/resume/seek/stop action | Movie playback snapshot; `409` for a stale session |

Uploads are streamed to staging, hashed, validated and atomically moved. An identical upload is consumed and verified before returning success. Playlist writes use Android AtomicFile. Failed commits retain the previous playlist.

## Limits

| Item | Limit |
| --- | --- |
| Stored media, including inactive files | 60 items, 1 GiB total |
| Single JPEG | 8 MiB; 3,840 pixels per edge; 8,294,400 pixels total |
| Single video | 1 GiB; H.264 or HEVC MP4 supported by the TV decoder; up to 4K |
| TV free-space reserve | 256 MiB |
| Photo interval | 10–86,400 seconds |
| JSON body | 64 KiB |
| Request headers | 16 KiB total, bounded lines |
| Weather locations | 6 |

The receiver bounds concurrent clients and socket inactivity. The Mac uploads videos from files instead of loading them into memory. Video transfer allows up to 30 minutes, with a 60-second request timeout. Original source files are untouched. JPEG imports apply orientation, resize and strip source metadata. When an imported video does not meet the receiver profile, the Mac creates a cached H.264/AAC MP4 up to 4K at 6 Mbps and uploads that prepared file.

## Playlist

`images` is the legacy field name for the ordered mixed-media list. Each item contains `id` (SHA-256 hex), `name` and `kind` (`photo` or `video`; omitted means `photo`). Other fields are `intervalSeconds`, `shuffle`, `selectedInputId`, `videoPlayback` (`cycle` or `loop`), optional `loopVideoId`, and `muteVideos` (default true). `slideshowEnabled` defaults to true and pauses rotation/video when false. `fitMode` is `fill` (default) or `fit`; fit preserves the entire image or video with letterboxing. `showTVInterface` defaults to true. When false, the launcher overlays hide during active ambient playback; navigation reveals them temporarily. Omission of slideshowEnabled, fitMode or showTVInterface preserves the receiver’s current value.

`layout` is an optional playlist object. Omission preserves the receiver's current layout, including legacy playlists with no stored layout. A supplied layout has `version: 1`, optional finite numeric `safeAreaPadding` from `0` through `0.10` (default `0.02`), optional Boolean `wifiEnabled` (default `true`), optional Boolean `snapToEdges` (default `false`), optional Boolean `appsShowLabels` (default `true`, omitted fields remain omitted), optional finite numeric `clockScale` from `0.5` through `2` (default `1`), optional finite numeric `weatherIconScale` from `0.5` through `2` (default `1`), and optional `clock`, `settings`, `wifi`, `weather`, and `apps` positions. The receiver leaves absent `safeAreaPadding`, `clockScale` and `weatherIconScale` fields absent in stored and read-back layouts. It persists and validates `snapToEdges`; the companion editor computes any nearest-edge placement. Each position is an object with finite numeric `x` and `y` values from `0` through `1`, measured as the control's top-left fraction of the full 16:9 viewport. Missing positions use the launcher defaults. The receiver rejects unknown fields, other versions, an invalid safe-area padding, clock scale or weather icon scale, non-Boolean `wifiEnabled` or `snapToEdges`, incomplete positions, and non-numeric, non-finite, or out-of-range coordinates. At render time, every launcher control is clamped to the configured safe area, including default and supplied positions. The clock uses its scaled `0.28` width and `0.11` height bounding box, and scales its type with `clockScale`. The padding is a fraction of the display's short edge, so landscape `x` uses `padding × height / width` and `y` uses `padding`, producing equal pixel insets. A control that cannot fit between both safe edges is centered. The grouped app row uses its visible packed width with 0.09-wide shortcuts, a 0.11 stride, and a 0.167 height with all labels visible, or 0.12 height with labels and their gap removed when `appsShowLabels` is false. Focus never reveals labels in hidden mode. Weather uses 0.13 width and 0.06 height for Icon, 0.3523 width for Compact and 0.4069 otherwise; heights are 0.2210 Compact, 0.4069 Scenic, 0.4628 Minimal with daily forecast, and 0.3172 other Minimal. Icon width and height are multiplied by `weatherIconScale`; other weather styles ignore this retained scale. The Icon style has only the condition icon and temperature in a 240 × 62.4 nominal canvas. The Wi-Fi shortcut only opens Android's native Wi-Fi settings, with general system settings as a fallback; it does not expose custom network controls or request network/location permissions.

Cycle mode times photos and advances after each video ends. Loop mode repeats the chosen included video. Removing an item from rotation retains its stored file. There is no media deletion endpoint.

The authenticated `playback` snapshot reports current media, state, video position/duration, looping and decoded dimensions. MediaPlayer reads run on the main thread with a bounded wait; a busy main thread can return an idle snapshot.

## Weather

`weather` is an optional playlist object. Omission preserves the existing TV configuration. Its default is disabled with an empty location list.

| Field | Values |
| --- | --- |
| `enabled` | Boolean |
| `locations` | Ordered location objects, maximum six |
| `selectedLocationId` | Enabled location ID or null |
| `style` | `detailed`, `compact`, `minimal`, `scenic`, `icon` |
| `unit` | `celsius`, `fahrenheit` |
| `showTemperature`, `showHourly`, `showDaily` | Booleans |
| `contentScale` | Finite number, clamped to `1.0`–`1.6`; missing/non-finite values default to `1.25` |
| `cycleSeconds` | `0`, `30`, `60`, `300` |

Content scale enlarges card text and weather icons inside the existing card bounds. It applies to landscape and portrait Minimal, Compact and Scenic cards. The inline Icon style retains its independent layout resize setting. Artwork, card placement and outer dimensions do not change.

Each location contains `id`, `name`, `country`, `latitude`, `longitude` and `enabled`, plus optional integer `artworkVariant` from 0 to 4 (legacy default 0). The shared destination catalog maps fixed scene slots; missing artwork does not renumber later slots. IDs are unique, at most 80 characters, and use letters, digits, hyphens or underscores. Coordinates must be finite and valid. Names and countries are limited to 100 characters. The server normalizes an unavailable selection to the first enabled location.

City search uses Open-Meteo's geocoding endpoint. Forecast requests use Open-Meteo HTTPS with normal certificate validation. These requests never carry the local TV token. Locations are selected manually; neither app requests device location. Forecasts are cached and refreshed while the launcher is visible. Missing or unavailable values must remain visibly unavailable.

Provider references: [forecast API](https://open-meteo.com/en/docs), [geocoding API](https://open-meteo.com/en/docs/geocoding-api), [usage terms](https://open-meteo.com/en/terms).

## TV integration and recovery

The Switch shortcut opens `com.sony.dtv.tvinput.external/.ExternalTvInputService/HW4`, the verified connected Sony HDMI input. It uses ACTION_VIEW with `TvContract.buildChannelUriForPassthroughInput` and MIME type `vnd.android.cursor.item/channel`, handed to Sony's TV app. Netflix opens its installed native application.

On the configured Sony TV, Google TV Home is removed for user 0 with data retained, and its setup/recovery package is disabled to prevent Home interception. Glass TV holds the HOME role and resolves as the default after reboot. `restore-google-home.sh` reinstalls the original launcher for user 0, restores both packages to their default enabled state and resets Home. TV system settings, HDMI/CEC, connectivity, accessories and DRM remain owned by their system services. Video playback keeps the screen on only while the launcher is playing it; photos use the existing TV sleep policy.

## Movies

Movies are independent of the ambient playlist. The Mac reads files from a user-selected folder and serves the selected movie over a temporary local HTTPS connection. Movie files are streamed in bounded byte ranges rather than uploaded into the TV's stored-media library. The Mac must remain open and reachable during playback.

`POST /movies/play` contains `apiVersion: 1`, a UUID `sessionId`, stable `movieId`, actual `title`, HTTPS source `url`, temporary stream `token`, Mac certificate SHA-256 `certificatePin`, and nonnegative `resumePositionMs`. The receiver accepts only a numeric IPv4 source host matching the authenticated request's peer address. URLs cannot contain credentials, a query, or a fragment. A successful response means preparation was accepted; the status endpoint confirms whether playback actually starts.

Movie snapshots include `supported`, `sessionId`, `movieId`, `title`, `state`, `positionMs`, `durationMs`, decoded `width`/`height`, and an optional plain-language `error`. States are `idle`, `preparing`, `playing`, `paused`, `completed`, and `error`. Source URLs, tokens and filesystem paths are omitted.

Movie controls contain `apiVersion: 1`, `sessionId` and `action` (`pause`, `resume`, `seek`, `stop`). Seek also contains `positionMs`. A stale session returns `409`. Stopping or leaving the player restores ambient playback without committing a playlist change.

The Mac generates an in-memory EC P-256 TLS identity for streaming. Its pin and temporary token reach the TV through the existing authenticated, pinned receiver connection. The reverse stream accepts only the paired TV's peer IP and bearer token. The TV uses per-source TLS pinning with a positional `MediaDataSource`; neither platform changes global certificate trust or enables cleartext traffic. GET/HEAD streaming supports single byte ranges, `206`/`416`, `Content-Range`, and fixed `Content-Length` with bounded buffers. Starting another movie or stopping revokes the previous stream.

Movie identity matching uses [Wikidata](https://www.wikidata.org/wiki/Wikidata:Data_access), followed by the documented [Cinemeta metadata endpoint](https://stremio.github.io/stremio-addon-sdk/advanced.html#getting-metadata-from-cinemeta) for available film details and artwork. Only a cleaned movie title is searched. Files and local paths are not sent to metadata providers. An unavailable or ambiguous match retains the actual filename and local artwork. No API key is required.

The shared compatibility layer accepts local video formats readable by the installed ffmpeg/ffprobe tools, including MKV, AVI, WebM, MOV and transport streams. Unsupported native codecs and oversize dimensions are converted to H.264/AAC MP4 at up to 3840 × 2160, 30 fps and 6 Mbps. PQ/HLG HDR inputs use a BT.2020-to-BT.709 SDR tone-map LUT. Originals remain unchanged; Media and Movies reuse the same profile-versioned cache. The ambient receiver retains its 1 GiB limit; Movies does not impose that upload limit.


## Shared movie catalog

`moviesEnabled` is an optional strict Boolean in Apps settings, defaulting to false for existing profiles. The launcher shortcut opens a native movie browser with All, Favorites, Watched and title search. All excludes watched titles; Favorites includes watched favorites. The Mac remains the source of truth for catalog organization.

All catalog endpoints require the existing bearer authorization and certificate-pinned connection:

| Route | Payload and limits | Result |
| --- | --- | --- |
| `PUT /movies/library` | `apiVersion`, UUID `publisherId`, SHA-256 `libraryId`, and up to 1,000 items; 512 KiB maximum | Atomically replaces metadata. No movie starts. |
| `PUT /movies/library/posters/{sha256}.jpg` | JPEG up to 256 KiB and 512 pixels per edge; hash must match | Adds artwork to the dedicated 128 MiB bounded cache before catalog publication. |
| `POST /movies/library/poll` | Publisher/library IDs and optional request acknowledgement; normal 64 KiB JSON limit | Renews a 15-second monotonic lease and returns a pending play request, or `409` for a catalog mismatch. |

Items contain an ID, title, optional year and duration, favorite/watched/playable flags and optional poster ID. Local paths and stream credentials are never part of the catalog. The Mac creates bounded thumbnails from actual movie artwork without modifying originals. The 512 KiB exception applies only to catalog replacement; other existing JSON routes remain limited to 64 KiB.

The Mac polls every two seconds while connected and Movies is enabled. It publishes only the current library generation, renews no previous catalog lease when the current library exceeds limits, and rotates publisher identity on a new authenticated connection session. Selecting a film on TV queues a UUID request. Requests are handled once; stale acknowledgements cannot replace newer requests or undo cancellation. Queued requests expire after 15 seconds. Preparation may take longer while the publisher lease remains live. Closing the browser, disabling Movies or leaving the launcher cancels pending preparation.

The TV retains catalog metadata across restarts but always starts with an expired publisher lease. Offline browsing displays a connection message and disables playback. Posters decode off the UI thread into a bounded cache. Status updates preserve grid focus. Accepted playback uses the existing authenticated streaming player; stopping a movie opened from the TV browser returns to that browser. Mac-initiated playback retains its existing behavior.

### Expanded launcher shortcuts and appearance

`apps.enabledServices` is an optional array of catalog IDs (default empty); the receiver rejects unknown IDs and non-string entries. `apps.shortcutStyle` accepts `minimal` (default) or `cards`. Existing Boolean shortcut fields retain their existing defaults. `shared/app-shortcuts.json` is bundled on both platforms and names provider packages and assets. Enabled shortcuts remain visible regardless of installation; selecting one opens the installed Leanback app or its official Google Play install/enable flow.

At the default icon size, the carousel viewport uses up to 8 Minimal items (width .09, stride .11, icon height .12), or 5 Cards (width .17, stride .18, icon height .17). It retains its capped width on the final partial page. Labels add .047 height and are fully omitted when hidden. More/previous arrows appear only at valid boundaries and clamp to the safe area. D-pad navigation moves focus across pages; interrupted animations cannot hide the newly active page. Apps style changes auto-sync; Layout context-menu changes are saved as a local draft and commit alongside layout positions using Update TV.


### Shortcut ordering and icon size

`apps.shortcutOrder` is an optional array of shortcut IDs, including local actions. Its default sequence is Netflix, Radio, Prime Video, Spotify, Twitch, Hulu, Movies, HDMI, followed by the shared service catalog. Supplied ordering removes unknown IDs and duplicates, then appends missing known IDs in default order. Disabled shortcuts retain their place in this sequence but do not render. Omission remains absent in readback. Both Mac and TV filter the same ordered sequence for display. Reordering in Apps follows its existing automatic sync policy.

`layout.appIconScale` is optional, finite, and ranges from `0.5` to `1.5`, defaulting to `1`; omission remains absent. It scales the app tiles, marks, labels, and spacing together. The viewport budget is the smaller of `.86` (Minimal) or `.89` (Cards) and the safe-area width. Capacity is `floor((budget + scaledGap) / scaledStride)`, at least one. Row width is the smaller of enabled count and capacity times the scaled stride, minus the scaled gap. The final partial page retains that row width. Layout size edits preserve edge anchoring and follow Update TV.

### Optional navigation sound preferences

`apps.navigationSound` accepts `off`, `soft`, `glass`, `wood`, `bubble`, or `chime`. `apps.navigationSoundVolume` accepts a finite JSON number from 0 through 1. Omitted fields remain absent in receiver readback; their effective defaults are `off` and `0.25`. Unknown IDs, non-number volume values and out-of-range volumes are rejected by the receiver. Preferences use the existing Apps update/revision path. Sound assets are bundled and are not fetched from a URL in the payload.

### Optional receiver identity

Authenticated `GET /status` may include `deviceName`, `manufacturer` and `model`. `deviceName` uses the TV's configured device name when present, otherwise its manufacturer/model. Existing clients may ignore these fields; clients talking to older receivers fall back to the saved address or a user-supplied name. Pairing and unauthenticated discovery responses do not expose these additional fields. `deviceId` remains the stable receiver identity; display names are not authentication identifiers.

### Standalone Apps and Weather editing

TV settings use the same persisted `apps` and `weather` documents as the companion. Local mutations serialize with remote playlist writes, validate before saving, increment the playlist revision once, and notify the launcher after the atomic file commit. Unrelated media, playback and input fields remain unchanged.

The Mac adopts authenticated receiver Apps and Weather values unless that section has a pending local edit. Pending flags are persisted per TV profile. A pending Mac edit wins for its entire section, preserving the other receiver sections; this is section-level conflict resolution, not a merge of individual fields within Apps or Weather. Exact readback acknowledges the edit and clears its pending flag. Without a pending edit, reconnecting does not publish stale companion preferences.

Apps and Weather updates read the latest revision before writing and retry one revision conflict with a fresh snapshot. Failed or unsupported Weather delivery remains pending without an immediate retry loop. Layout drafts remain staged until Update TV. No new unauthenticated configuration route is introduced.
