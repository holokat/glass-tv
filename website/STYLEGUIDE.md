# Glass TV website

## Direction

The product is the hero. Use a centered Glass TV title, a concise Android TV launcher description, and a large television directly underneath. Everything in the marketing narrative is centered. Keep the page black, spacious and led by vivid imagery. The Mac companion appears later as supporting product context.

The owner requested Apple's product-page aesthetic. [Apple Studio Display](https://www.apple.com/studio-display/) informs the centered title, full-scale display imagery and deliberate vertical rhythm. [Apple TV 4K](https://www.apple.com/apple-tv-4k/) informs the large launcher demonstration and product-feature sequence. Supahero now redirects to [ScreensDesign](https://screensdesign.com/), which was consulted; it no longer offers the previous public hero gallery. [Dark.design](https://www.dark.design/) was consulted for dark composition, and [60fps](https://60fps.design/) for restrained state transitions. [Footer.design large type](https://www.footer.design/styles/large-type) informs the selected footer: a black field with an oversized one-line, reef-textured Glass TV wordmark and quiet anchored navigation. Retain native system typography from the product, as requested by the Apple aesthetic; do not add a webfont dependency.

Follow the owner's web-design-rules: no eyebrow text, decorative filler, all-cap titles, shadows, or bordered marketing-card grids. Color comes from content. No purple interface accent. Shader Effects was consulted: retain the reef video with a still-image fallback, DOM UI and CSS motion. The owner additionally requested a glass bubble on hover; keep that localized refraction beneath the launcher UI.

## Tokens

- Background: `#050505`. Surface: `#151516`. Primary text: `#f5f5f7`. Secondary text: `#96969c`. Link blue: `#b7d9ff`.
- Type: `-apple-system, BlinkMacSystemFont, Helvetica Neue, sans-serif`. Use platform-provided fonts with no redistributed font files. Main title 76–138 px; section headings 40–72 px; support copy 17–28 px. Heading tracking -0.045 to -0.065 em, line-height 1.06–1.08. Body line-height 1.5–1.55.
- Main width: 1280 px, hero TV 1280 px (1420 px on large displays). Section top spacing 145 px desktop, 90 px mobile. Page inset 24–32 px desktop, 12 px for the mobile hero and 22 px for mobile sections.
- Breakpoints: 900 px and 600 px. Feature explanations form three columns on desktop and one on mobile. Weather cards remain side by side. TV preserves 16:9 proportions and its shortcuts scroll inside the screen.
- Borders are limited to the physical TV bezel, selected shortcuts, focus states and selected wallpaper swatches. Group marketing content with whitespace. No box, text or drop shadows.
- Central outlined icons, except the user-requested filled radio playback controls. `assets/ui-icons/` contains licensed deployment-only PNG exports and stays ignored by Git. Recreate from owned exports using `scripts/prepare-licensed-icons.sh`.

## Components and behavior

`index.html` owns the single header, hero, feature sections and footer. `styles.css` owns tokens and responsive components; `app.js` owns the launcher/style preview controls and one shared reveal observer.

- Brand: use `glass-tv-mark.png`, the transparent cutout of the existing glass screens, beside live Glass TV text. No black tile or rounded image mask. Mark size is 56 px desktop and 42 px mobile; preserve its aspect ratio. The download section reuses the same transparent mark without a background tile or rounded mask. The original app icon remains the favicon.
- Hero: centered heading and a clear custom, open source Android TV launcher description, then TV, style buttons, wallpaper swatches and links. Label it Interactive preview. The top system row keeps a white weather icon and sample `12°C`, then Wi-Fi and Settings; do not use a scenic weather card there. Service shortcuts use white marks on blurred pills; cards use original-color marks. Every colorful card uses the same 16:9 face. Position logos inside that face so intrinsic image dimensions cannot change card height. Only the selected shortcut has a white outline. Keep selection keyboard accessible with arrows, Home and End.
- Radio: use three large circular station controls in a centered grid, with generated station artwork, a borderless 60 px play or pause overlay with a 22 px solid white Central icon, genre and verified station identity beneath. EDM uses cyan (`#59d9ed`), Classical gold (`#edbc6a`) and US Top 40 coral (`#ff7567`). A synthetic waveform uses the selected accent and animates only while the audio element reports playback; it is not an audio analyzer. Keep one user-initiated audio element active at a time, with accessible loading, error, pause and resume states. Do not publish a station identity, stream URL or bitrate until it is verified.
- Weather: Scenic, Minimal and Icon & temperature previews. City titles use light weight matching temperature. Weather values are explicitly sample data, never fetched or claimed as live.
- Feature copy: grounded in the project STYLEGUIDE.md, PROTOCOL.md and Android RadioController/RadioDirectory. Movie streaming needs the Mac; the unreleased TV APK remains clearly identified near download. No invented station names, movie titles, testimonials, performance claims or compatibility promises.
- Mac preview: a working Movies library demo made from HTML controls. Clearly label Interactive demo and Demo TV; no real device connection or bundled film claim. Use the existing Mac sidebar with Movies selected.
- Footer: the canonical `site-footer` is a wide black closing field with a single-line `Glass TV` wordmark clipped to `oled-reef.png`. Keep its tracking at `-.055em`, keep the whole wordmark visible on mobile, and place Back to top, Download for Mac and GitHub in a no-wrap 40 px minimum-height navigation row at the bottom. Place the provided independent-project notice beneath that row, centered, with 12 px desktop type and 11 px mobile type. The reef image follows the pointer within the fixed wordmark, bounded to ±32 px horizontally and ±22 px vertically. Coalesce pointer updates with requestAnimationFrame, ease tracking over 220 ms, and return to the resting 46%/55% position over 600 ms on exit. Touch and reduced motion retain the static image. Text geometry never moves.
- Motion: 700 ms fade/18 px rise for every direct content block in every section and the footer, once as each enters the viewport; 240 ms hover and selection transitions. Press scale 0.96. Radio waveform animation runs only while the active audio element reports playback. Reduced motion removes movement and reveals immediately, including when changed while the page is open. Keyboard focus reveals its content immediately. Without JS, content remains visible.
- Buttons and anchors expose visible keyboard focus. Preview controls use aria-pressed. Public navigation and DMG download remain ordinary anchors.

## Assets and release

`oled-reef.png` is newly AI-generated marine demo artwork. It evokes an OLED demo reel; the site makes no literal 8K-resolution or hardware claim. Coastal, London and Tokyo artwork comes from the project's generated assets. Tokyo uses `tokyo-01.jpg` with the Tokyo Tower composition. Provider marks come from `shared/brands`; they identify shortcut apps, not partnerships. No actual private media or device state is published.

Public source and downloads: https://github.com/holokat/glass-tv. Production site: https://glasstv.pages.dev. Publish only HTML, CSS, JavaScript and referenced assets. Keep raw licensed icon collections, local files, credentials and build artifacts out of Git.

## Platform wording

Identify Glass TV as a custom, open source Android TV launcher in the hero. The Android build requires API 26 (Android 8.0) or later; state that requirement near the TV release note rather than promising compatibility with every device. Mark the Linux companion as Coming soon in the companion and download sections. Keep the current Mac download and requirements clear.

## Hero background video

The owner-approved Grok clip, generated through Cloudflare from `oled-reef.png`, is served as `assets/video/reef-motion.mp4`. It is a silent 6.04-second H.264 clip at 1264×720, compressed for the website without changing the motion. Keep the TV frame, launcher controls and weather separate above it. Loop only the reef selection, with a Central pause/play control beside the preview options. Load video only when visible and motion is allowed; pause offscreen or in a hidden tab. Reduced motion, load failure and JavaScript failure retain the still wallpaper. Coastal wallpaper remains static.

## Social preview

`assets/images/glass-tv-og-4.png` is the approved 1200×630 social image (option 04). Keep the black field, oversized Glass TV title at lower left and the close, angled reef television bleeding off the top and right. The exact supporting line is “Open source, customizable Android TV launcher”. Open Graph and Twitter metadata use the absolute production image URL and the same description. [OGimage.gallery](https://www.ogimage.gallery/) and its [Apple product example](https://www.ogimage.gallery/og-images/apple---iphone-duo) informed the minimal product-led composition. Source composition lives in `work/og-variants/index.html?variant=4` in the private workspace. The previous image remains archived; use the new filename to avoid stale social image caches.


## Homepage radio

Three circular 232 px genre artwork controls: EDM (cyan), Classical (gold), US Top 40 (coral). Generated images are genre artwork, not station logos; provenance is recorded in `assets/images/radio-artwork-provenance.json`. Provider names link directly to official station pages beneath each control. Public direct HTTPS MP3 streams are verified in `radio-sources.json`: EDM and Classical at 320 kbps, and 181.FM Power 181 (US Top 40) at its published 128 kbps. Do not show a blanket 320 kbps claim. No autoplay, proxy, or recorded radio excerpts. One audio element plays at a time; active station ring and waveform use its artwork accent. Waveform motion is decorative playback feedback, not a measured spectrum. Only actual playback animates it; loading, pause, and errors stop it. Respect reduced motion. Keep hover scale restrained, native buttons keyboard accessible, and errors retryable.


## Weather hover motion

Scenic London and Tokyo cards layer separate silent Grok videos over their existing photos, below the gradient and live weather typography. `weather-motion.js` plays a six-second subtle zoom on pointer hover or keyboard focus, fades video opacity over 450 ms, holds its final frame, then fades back and resets after exit. There are no native video controls or automatic loops. Use `object-fit: cover` with the same 60% London and 75% Tokyo positioning as the stills. Pause offscreen, in hidden tabs, and when switching to Minimal or Icon & temperature. Reduced motion keeps the static photos; coarse-pointer devices do not preload or play from touch hover. Only visible cards warm their video sources on hover-capable devices; the static background remains the load/error fallback. No new dependencies.

- Header GitHub link: use GitHub’s official Primer Octicons `mark-github-24` from https://github.com/primer/octicons/blob/main/icons/mark-github-24.svg. Keep the MIT license in `assets/brands/github-LICENSE.txt`. Render at 24 px inside a 40 px link, white at 65% opacity by default and full white on hover or keyboard focus, with a 200 ms opacity transition. Accessible label: “Glass TV on GitHub”. This requested brand mark is the exception to Central UI icons.

- Radio artwork circles use a neutral #111 fallback, avoiding a colored hairline beneath the image clipping. Play and pause use filled versions of the existing Central paths, exported at 128 px as deployment-only PNGs. Keep controls borderless, 60 px across, with 22 px icons. Preserve the active station and keyboard focus rings.

## Interactive Movies library

`movie-demo.css` and `movie-demo.js` own the companion section demo. The 1180 px dark window uses a 164 px sidebar, 30 px content inset, search, collection segments, sort menu, and a scrollable four-column poster grid. At 1000 px use three columns; below 600 px hide the decorative sidebar and use two columns. Posters preserve their original aspect ratio, with a 9 px corner radius, 13 px titles and muted 11 px metadata. Hover gently zooms the artwork and exposes Explore film, favorite and watched actions; keyboard and touch also expose actions.

Use the six credited Blender open-film posters in `assets/movie-posters/`. Sources, authors and license links are in `credits.json` and the Artwork credits dialog. No generated movie imagery. Metadata years are verified against Blender’s film listing and foundation history. Demo favorites and watched state start empty, remain in memory, and reset with Reset demo. Native dialog details trap focus, close with Escape, and restore focus. View film links open the original project site. Search, collection filters, count, sort, empty states and reset are functional. The demo cannot connect to a TV or stream the visitor’s files. No new dependencies or shader effects.

## Hero glass lens

`hero-glass.js` is a native WebGL enhancement, initialized only on a fine-pointer hover inside the TV. Shader Effects was consulted; this focused image/video refraction uses browser APIs without a new production dependency or bundle. The small transparent canvas samples the same object-fit-cover wallpaper or current reef-video frame, with convex refraction, a restrained reflective rim, and a soft edge. It sits above the video and beneath the shade, clock and shortcuts; pointer-events are disabled. Radius is 9.5% of screen width, clamped to 65–125 px; canvas DPR is capped at 1.5. Pointer easing is 65 ms and fade easing 95 ms. No trails, shadows or full-screen distortion.

Cap rendering and texture uploads at 30 fps while the video plays; static scenes stop rendering once the lens settles. Fade out on pointer exit. Stop when offscreen or hidden, and release resources on page teardown. Resize resets the effect. Touch/coarse pointers and reduced motion do not initialize it; changes to those preferences remove the canvas. Missing WebGL, shader failure and context loss leave the original DOM preview intact.
