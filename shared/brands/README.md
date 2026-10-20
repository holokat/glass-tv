# Brand marks

- `netflix-original.svg`: Netflix N geometry from [Wikimedia Commons’ Netflix 2016 N logo](https://commons.wikimedia.org/wiki/File:Netflix_2016_N_logo.svg), whose source cites Netflix’s brand site. The official Netflix brand page at https://brand.netflix.com/en/assets/logos is retained in `work/glass-launcher/netflix-logo-page.json`.
- `netflix-white.svg`: a white-only derivative of that same N geometry, as requested for this private launcher.
- `switch-original.svg`: downloaded from Nintendo’s public common asset at https://www.nintendo.co.jp/common/v2/img/ncommon/_common/logo/switch.svg.
- `switch-white.svg`: a transparent crop of the original Nintendo Switch controller symbol, retaining its path geometry and white fill.

The PNG exports are rasterizations of the white SVG derivatives for Android and macOS.

## Additional streaming services

Retrieved 20 October 2026. Original SVG geometry is retained without redrawing.

- `spotify-original.svg` and `spotify-white.svg`: official white primary symbol, `Primary_Logo_White_RGB.svg`, from [Spotify's developer logo package](https://developer.spotify.com/images/guidelines/design/2024-spotify-logo-icon.zip), linked by [Spotify design guidelines](https://developer.spotify.com/documentation/design).
- `twitch-original.svg` and `twitch-white.svg`: official white Glitch symbol, `Twitch Brand/Twitch Logos/02. Glitch/04. White/glitch_flat_white.svg`, from [Twitch's brand package](https://brand.twitch.com/uploads/Twitch-Brand.zip), linked by [Twitch Brand Assets](https://brand.twitch.com/).
- `hulu-original.svg`: official black digital wordmark from [Hulu's logo package](https://www.thisishulu.com/app/uploads/2023/11/Hulu-Logos.zip), linked by [Hulu's brand guidelines](https://thisishulu.com/). `hulu-white.svg` changes the fill to white and trims the surrounding SVG canvas, preserving the path geometry.
- `prime_video-original.svg`: [Prime Video's 2024 wordmark on Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Prime_Video_logo_(2024).svg), attributed to Amazon.com, Inc., with amazon.com identified as its source. `prime_video-white.svg` changes only its fill to white.

Native PNG exports in `mac/Resources/icons/` and `android/res/drawable/` are 512 pixels on the longest edge, rendered directly from the SVG masters with preserved aspect ratio and transparency. They use the existing native image pipeline and provide ample resolution for Retina rows and TV shortcuts. Service marks are the user-requested exception to Central interface glyphs; native control styling is unchanged.

## Expanded shortcut catalog

The catalog in `../app-shortcuts.json` uses official Google Play package identifiers checked against the live listing. Availability is determined by Google Play for the user’s region and TV. No APK sideloading is used. Full-color 512-pixel marks are the provider-published images from those listings. Minimal versions preserve source geometry and use white with transparency.

- **youtube**: [official listing](https://play.google.com/store/apps/details?id=com.google.android.youtube.tv&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/OzCC2CEwKggMTTeFt_uAZ4Hp9W6b5_cGy29Pk36V-gAgh_w2ldR_2dVzo4B-7nClDUxo_mvkMG6LJ9o--ccvIw=s0-br30).
- **disney_plus**: [official listing](https://play.google.com/store/apps/details?id=com.disney.disneyplus&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/AEX_3Bk5_SEZfR4fUcJsWiyqW_RRVcLED9AM2HMUVHAPVTZhZHfP2q3M35mRe4LtCXlb712DtkKwkZa_nJ-0=s0-br30).
- **tubi**: [official listing](https://play.google.com/store/apps/details?id=com.tubitv&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/JcI8o5IHBMbNZ3tNE88Xnw8nwZYMZghNRTJ8JaiU_jXA4J2ShN1kybxOColfQW5UeRyykv3AFL6TcrmHGTRL9g=s0-br30).
- **hbo_max**: [official listing](https://play.google.com/store/apps/details?id=com.wbd.stream&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/wKJ5hN5CmSBWXPBVYYuRENzcEVb7FB6NyibjKqPT4SB0eoZ7Fww2jq-OhUDCxEPGALrSekQaazrHLSBwVb6T4A=s0-br30).
- **paramount_plus**: [official listing](https://play.google.com/store/apps/details?id=com.cbs.ott&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/O5QHZxy2pkxwHrjU3Omd_1jdIYk_pZQexy2VVEDBDhaXgNhvZV7wjhfN_0kLUrQfCKFsaGbQbVm8usyrc-yBGhI=s0-br30).
- **peacock**: [official listing](https://play.google.com/store/apps/details?id=com.peacocktv.peacockandroid&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/FdlQY6tEE0p3FsJT9EbxB0BzKdXB1U84nNzAUl_YCC9HxRO1J82i8-XWuN_BD1zReblXht3-cvUreuogHrPk-A=s0-br30).
- **pluto_tv**: [official listing](https://play.google.com/store/apps/details?id=tv.pluto.android&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/O6MtMEvYNmr-I_cntqWT2Lq8L5Y5bG01V5udGLT8-8LcpNzeeOCDjgaX-mQEushfwrLjT74nVJMlmSgeIxtP=s0-br30).
- **apple_tv**: [official listing](https://play.google.com/store/apps/details?id=com.apple.atve.androidtv.appletv&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/T5tmhzEpX3GPMVcQpOsSLuOz3WRjOdmvJaZ5Rs-6jDZKBGv5qGWdqfTK3Pvz9YZXUmLpBbhUzt2XBvBrnekuDw=s0-br30).
- **espn**: [official listing](https://play.google.com/store/apps/details?id=com.espn.score_center&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/XIEJt2LPHOV3O2cSEw7vbPGY0phcSY9CNeBj9GI8LvvlQoATs6iC6t7bU5tMk9nBduJXaNIp-J39j0D51fJw=s0-br30).
- **crunchyroll**: [official listing](https://play.google.com/store/apps/details?id=com.crunchyroll.crunchyroid&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/FUEOotGzxEZqItvGGov0YBiOhZBCACxCM6kF37OtpWrCG9H6EyxSeY2G8PDXQGueLxlxtL3Xr0fbkvXaQ67NLg=s0-br30).
- **discovery_plus**: [official listing](https://play.google.com/store/apps/details?id=com.discovery.discoveryplus.mobile&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/t_Lanic5BWmy8cg0z4lQW0t2LZAHl8v69DIdYR48hzI4p6YwvLn6Z_rHt02tCPccyFc-GGniuGIWiwWzDdwG=s0-br30).
- **dazn**: [official listing](https://play.google.com/store/apps/details?id=com.dazn&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/2m6bTc0_zIgzimPNQo_ZfZkhGHEjBXJIPbIs3_wliCXm4VTNkLW31yMMUqmB7Vpn51Fed62sUv91YtC1kRcZAQ=s0-br30).
- **starz**: [official listing](https://play.google.com/store/apps/details?id=com.bydeluxe.d3.android.program.starz&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/TQRIRBXk3GFDHxa8FTNKbxFT_Qn7Xa89HkeJRCQugyuoBJLKyP4e_3UUl9SbLnJicB69Jpxaj_n286GMp7Vu=s0-br30).
- **mgm_plus**: [official listing](https://play.google.com/store/apps/details?id=com.epix.epix.now&hl=en&gl=US); [provider image](https://play-lh.googleusercontent.com/u4AJINfBjncI0pKHo8SddiBTLtN1FhWB4iBU4LfQ9hJDWpqrtzgMXNh4O8d1H7VmsKY4FQaQjzUsWpD5hZVJ=s0-br30).

Minimal SVG geometry for YouTube, Tubi, Paramount+, Apple TV, Crunchyroll, DAZN and STARZ comes from [Simple Icons](https://github.com/simple-icons/simple-icons/tree/develop/icons). Disney+ and Peacock use [selfh.st’s SVG collection](https://github.com/selfhst/icons/tree/main/svg). Pluto TV uses the SVG embedded in [its official website](https://pluto.tv). HBO Max, ESPN, discovery+ and MGM+ use monochrome exports of their provider-published store marks. The HBO Max store mark is used instead of the older Max-only SVG. Existing Netflix, Prime Video, Spotify, Twitch and Hulu colored exports reuse the source geometry documented above. Brand marks are identification assets; UI glyphs remain Central.

YouTube Premium uses the normal YouTube app ([Google support](https://support.google.com/youtube/answer/6308244?hl=en)). The retired standalone Google Play Movies & TV app is not offered; Google TV Home is not restored or made the default ([Google announcement](https://support.google.com/googleplay/thread/248000772/upcoming-changes-to-google-play-movies-tv?hl=en)).
