# JavaScript plugins

Open **Settings > System > Plugin store**. Install the browser runtime once, then activate an app.
Activated apps appear directly in the bottom bar alongside Settings, GamePad, Refresh and Power.
Open them with the mouse or controller A. Deactivation removes the icon and closes its window;
website login data stays in your WII-UU data folder. For larger catalogs, use Left/Right or scroll
on the dock to reach the next group of icons.

The runtime installer needs **Node.js 22.12 or newer with npm on PATH**, internet access and space
for Electron/Chromium. WII-UU itself still builds and runs with Java 17 and has no npm dependency.
Installation output is in `logs/plugin-runtime.log` in your WII-UU data folder. Apps open in their
own browser windows on Windows, macOS and Linux. Close them before repairing/updating the runtime.

## Add an app to the bundled store

1. Add a JavaScript file under `resources/plugins/store/`, for example `xyz.js`:

   ```javascript
   const pluginMainScreen = Plugin("XYZ");
   pluginMainScreen
       .loadSite("https://xyz.com")
       .disableRedirectsToOtherWebsites(true)
       .redirectDownloads(true, Directories.ROMS);
   ```

2. Add its entry to `resources/plugins/catalog.json`:

   ```json
   {"id":"xyz","name":"XYZ","description":"Your app description and download behavior.","script":"xyz.js"}
   ```

3. Rebuild with `./build.sh`. Resources are bundled into the app JAR. The store is local and uses
   these reviewed source files; it does not download or execute a remote plugin catalog.

Each file declares exactly one `Plugin(name)` and calls `loadSite(url)`. Definitions execute as
JavaScript in the Electron host when the app opens. Fluent methods return the same object:

| Method | Behavior |
| --- | --- |
| `loadSite(url)` | Initial HTTP(S) site and the Home menu destination. |
| `disableRedirectsToOtherWebsites(true)` | Default: block top-level navigation, HTTP redirects and popups outside the initial origin (scheme, host and port). Allowed popups open in the same window. |
| `disableRedirectsToOtherWebsites(false)` | Allow navigation to other HTTP(S) websites in the window. External protocols remain blocked. |
| `redirectDownloads(true, Directories.ROMS)` | Save downloaded files into the configured ROM base directory. Put files in their system subfolder afterward, then Refresh the game library. |
| `redirectDownloads(true, Directories.DOWNLOADS)` | Save files into `downloads/` inside the WII-UU data folder. |
| `redirectDownloads(false)` | Default: use the ordinary browser Save dialog. |

Navigation restrictions apply to the main document, not images, CDNs or embedded frames. Download
URLs may redirect to a different origin (for example a file CDN). Automatic downloads sanitize
filenames, reserve a new path without overwriting an existing file and remove failed partial files.
Downloads are never executed or extracted. The window menu provides Back, Home, Reload, Fullscreen
and Close; Escape exits fullscreen. Downloads finish independently of refreshing the library.

## Runtime boundaries

Remote pages have no Node.js integration, Java API, preload bridge or direct filesystem access.
The host enables context isolation and Chromium sandboxing, blocks new webviews, denies website
permission requests and leaves TLS validation enabled. Each app has separate persistent site data
under `plugin-data/<id>/`. Deleting that folder while the app is closed resets its login/storage.

Bundled plugin definitions are trusted, reviewed code. The evaluator's one-second limit helps catch
accidental loops; Node's `vm` is not a security boundary for untrusted third-party plugins. There is
no arbitrary user-script import in this version. The browser host and pinned npm lockfile live in
`resources/plugins/host/`; update the dependency and lockfile together when updating Electron.

Run API/policy tests with `node --test test/plugins/*.test.cjs`. The real-browser integration test
uses a local HTTP fixture; see `test/plugins/browser-smoke.cjs` for its invocation.
