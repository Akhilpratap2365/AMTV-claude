# Apple Music for Google TV

Android WebView app that runs the Apple Music web player on TV with a D-pad cursor.

## Build the APK on GitHub
Push this folder to a repo -> **Actions** tab -> *Build Apple Music TV APK* -> download the
`AppleMusic-TV-APK` artifact (`app-release.apk` or `app-debug.apk`).

## Controls
- Arrows: move cursor. OK/Enter: click (hold OK + arrows = drag, e.g. seek bar).
- Tap an arrow, press the same arrow again and HOLD: scroll that way (up/down/left/right).
- Page Up/Down or Channel Up/Down: scroll vertically.
- Back: browser back; press twice at the start page to exit.

## Tuning (MainActivity.java)
- `CAP_RENDER_RESOLUTION` (default true): render at 1080p and let the GPU upscale. Set false for native 4K (sharper, heavier).
- Page layout width is always 1600 CSS px (viewport meta injected by `am_inject.js`).
- To keep frosted-glass blur, delete the last `* { backdrop-filter: none ... }` rule in `app/src/main/assets/am_inject.js`.
