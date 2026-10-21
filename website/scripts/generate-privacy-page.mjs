#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const websiteDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const indexPath = resolve(websiteDirectory, 'index.html');
const privacyPath = resolve(websiteDirectory, 'privacy.html');

let index = readFileSync(indexPath, 'utf8');
const footerLinks = '<a href="https://github.com/holokat/glass-tv">GitHub</a>';
const legacyPrivacyLink = '<a href="./privacy">Privacy</a>';
const privacyLink = '<a class="footer-privacy" href="./privacy">Privacy</a>';

if (index.includes(legacyPrivacyLink)) index = index.replace(legacyPrivacyLink, '');

if (!index.includes(privacyLink)) {
  const footerNotice = '<p class="footer-notice">Glass TV is an independent open source project and is not affiliated with Apple Inc.</p>';
  if (!index.includes(footerLinks) || !index.includes(footerNotice)) throw new Error('Could not find the canonical footer structure.');
  index = index.replace(footerNotice, `<p class="footer-notice">Glass TV is an independent open source project and is not affiliated with Apple Inc. ${privacyLink}</p>`);
  writeFileSync(indexPath, index);
}

const header = index.match(/<header class="site-header">[\s\S]*?<\/header>/)?.[0];
const footer = index.match(/<footer class="site-footer">[\s\S]*?<\/footer>/)?.[0];
if (!header || !footer) throw new Error('Could not extract canonical header and footer from index.html.');

const homeRelative = (fragment) => fragment.replaceAll('href="#', 'href="./#');
const privacyFooter = homeRelative(footer).replace(
  privacyLink,
  '<a class="footer-privacy" href="./privacy" aria-current="page">Privacy</a>',
);

const page = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="description" content="Privacy policy for Glass TV, published by 21 Media LLC.">
  <title>Privacy policy · Glass TV</title>
  <link rel="canonical" href="https://glasstv.xyz/privacy">
  <link rel="icon" href="assets/images/glass-tv-mark.png">
  <link rel="stylesheet" href="styles.css">
  <link rel="stylesheet" href="privacy.css">
</head>
<body id="top">
  <a class="skip" href="#privacy-content">Skip to privacy policy</a>
  ${homeRelative(header)}
  <main class="privacy-page" id="privacy-content">
    <header class="privacy-intro">
      <h1>Privacy policy</h1>
      <p class="privacy-effective">Effective 21 October 2026</p>
      <p>Glass TV is published by 21 Media LLC, based in the United States. This policy covers the Glass TV Android TV app, the Mac companion, and this website.</p>
    </header>

    <section class="privacy-section">
      <h2>What Glass TV stores on your devices</h2>
      <p>The Android app stores the information it needs to run on your TV. This can include paired-TV information, playlist and launcher settings, selected inputs, weather locations and cached forecasts, radio favorites and recent stations, uploaded ambient photos or videos, and movie catalog and poster data.</p>
      <p>The Mac companion can store its settings, local library information, media previews, derived video files, and a reference to the folder you select for your library. Pairing credentials are stored in the macOS Keychain.</p>
      <p>Some stored Android data can remain after you remove items from a playlist. Clearing the app’s Storage in Android settings or uninstalling the app removes Android app data. Android documents that app-specific files are removed when an app is uninstalled. Device menus vary by manufacturer. See Android’s <a href="https://support.google.com/android/answer/15728899">storage guidance</a> and <a href="https://developer.android.com/training/data-storage/app-specific">app-specific storage documentation</a>.</p>
      <p>Mac companion files, the selected-library reference, and pairing credentials can remain on your Mac until removed.</p>
    </section>

    <section class="privacy-section">
      <h2>Local TV connection</h2>
      <p>Glass TV can connect to the Mac companion over your local network. The companion sends the TV information needed for the feature, such as selected media, media names and bytes, playlist settings, weather settings, app and layout choices, and movie-library details. Movie playback can stream the selected file from the paired Mac to the TV.</p>
      <p>The Android and Mac apps use TLS for this local connection. The Android receiver stores a hash of its bearer token instead of the token in plaintext. The Mac companion stores its pairing credential in the macOS Keychain.</p>
    </section>

    <section class="privacy-section">
      <h2>Optional internet features</h2>
      <p><strong>Weather.</strong> Searching for a city sends the search text to Open-Meteo. Loading a forecast sends the selected latitude, longitude, temperature unit, timezone setting, and forecast fields to Open-Meteo. Open-Meteo also receives the request’s IP address and connection details. Open-Meteo describes its own handling in its <a href="https://open-meteo.com/en/terms">Terms &amp; Privacy</a>.</p>
      <p><strong>Radio.</strong> Starting a station connects your device to that station’s stream host. Loading station artwork connects to the host named by the selected station. The Android app can also recheck a selected station through Radio Browser. The website can play radio from third-party stream hosts. Those providers receive the request’s IP address and connection details and handle requests under their own terms and privacy notices.</p>
      <p><strong>Mac movie metadata.</strong> If you choose metadata lookup, the Mac companion sends a movie title and optional year derived from the selected local file name to Wikidata. If a matching IMDb identifier is available, it can request movie metadata from Cinemeta and download artwork from the host returned for that movie. These services also receive the request’s IP address and connection details. The app does not send the file path, movie bytes, TV credential, or pairing data to those services.</p>
    </section>

    <section class="privacy-section">
      <h2>Website analytics and links</h2>
      <p>This website uses Cloudflare Web Analytics. Cloudflare describes it as real-user monitoring for page views and performance, including Core Web Vitals. Its published dimensions include page path, referrer, visitor country, device type, browser, operating system, and navigation type. Cloudflare receives connection information for website requests. Cloudflare says Web Analytics does not use cookies or localStorage for usage metrics and does not fingerprint individuals. Read Cloudflare’s <a href="https://developers.cloudflare.com/web-analytics/data-metrics/data-origin-and-collection/">Web Analytics documentation</a> and <a href="https://www.cloudflare.com/privacypolicy/">Privacy Policy</a>.</p>
      <p>The Glass TV Android app and Mac companion do not use an analytics or crash-reporting SDK. The website links to GitHub for source code and downloads. GitHub handles activity on its services under the <a href="https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement">GitHub General Privacy Statement</a>.</p>
    </section>

    <section class="privacy-section">
      <h2>Contact</h2>
      <p>For privacy questions or to request deletion of support correspondence, email <a href="mailto:admin@21media.to">admin@21media.to</a>. We keep the information in support messages only as long as needed to respond to or resolve the request.</p>
      <p>Glass TV code owned by 21 Media LLC is available under the <a href="https://github.com/holokat/glass-tv/blob/main/LICENSE">MIT License</a>. That license applies to source code only. See the <a href="https://github.com/holokat/glass-tv/blob/main/LICENSE-SCOPE.md">license scope</a> for third-party assets and their terms.</p>
    </section>
  </main>
  ${privacyFooter}
</body>
</html>
`;

writeFileSync(privacyPath, page);
