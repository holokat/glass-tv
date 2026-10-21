# Signed Android TV device check

21 October 2026. Package xyz.glasstv.launcher, version 1.0.1 (2), tested on Sony BRAVIA 4K VH21 with Android API 31.

- Updated the existing signed public 1.0.0 package in place using install -r. The development package remained separate and retained the HOME role.
- The exact published-candidate APK renders the shortcut, settings and Wi-Fi icons. This fixes the missing dynamic drawables seen in signed 1.0.0.
- Remote navigation opened Radio. Classic Vinyl HD reached the active-player state; the bottom-right heart saved it and the Favorites section appeared. No card favorite overlay or volume slider was present.
- The receiver Start/Stop control changed persisted state. Stopped state survived a process stop and relaunch. With the development receiver stopped, the listener closed (connection refused). Start restored the foreground receiver and listener; stopping it closed the listener again.
- The public app was stopped after testing. The development launcher was restored, the HOME role remained local.glasstv.launcher, and the TV was returned to its initial sleeping state.
- Four unedited 1920×1080 screenshots were captured from this signed package for store review. No pairing code, private library, network address or account information is shown.

Final rebuild check: APK SHA-256 `ae4c5a359640b854a851f7c6b9b6e6ffeb2c16590d7d46e2acea954856f94c87` installed successfully. Stop removed foreground state; Start restored both foreground state and the started service lifetime. The privacy action dispatched without crashing; this Sony has no web browser, so Android displayed its no-handler message. The privacy page itself was verified over HTTPS in a desktop browser.

Not tested here: Android 14 or later, clean install of 1.0.1, physical reboot, pairing this separate public package to the Mac, full media/movie streaming, and cross-channel signing updates. Existing development-app tests do not substitute for these release checks. Google Play foreground-service review remains unresolved.
