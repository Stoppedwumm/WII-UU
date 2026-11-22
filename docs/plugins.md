# JavaScript plugins

Open **Settings > System > Plugin store**. Install the shared plugin runtime once, then activate an app.
Activated apps appear directly in the bottom bar alongside Settings, GamePad, Refresh and Power.
Open them with the mouse or controller A. Deactivation removes the icon and closes its window;
website login data stays in your WII-UU data folder. For larger catalogs, use Left/Right or scroll
on the dock to reach the next group of icons.

The runtime installer needs **Node.js 22.12 or newer with npm on PATH**, internet access and space
for Electron/Chromium. WII-UU itself still builds and runs with Java 17 and has no npm dependency.
Installation output is in `logs/plugin-runtime.log` in your WII-UU data folder. Website apps open
in their own browser windows on Windows, macOS and Linux; native apps render inside the WII-UU menu.
Close apps before repairing/updating the runtime. Native apps reuse the installed runtime in Node
mode, so they do not open a browser window. Existing installations need no new runtime download.

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

### ROMSFUN native plugin

The bundled **ROMSFUN** app uses WII-UU's native controller-friendly panels
rather than Electron's browser window. Browse the catalog, use pagination or
the provided quick-search terms, select a game, see its available versions and
confirm a download. Game thumbnails and titles come from the site's HTML;
the site may change its markup without notice. Files are downloaded using the
fresh, site-provided HTTPS link, never a fabricated mirror URL.

The plugin includes a native **Search games** text field supporting arbitrary
search terms. Select it with mouse or controller A, type a title, and submit;
the plugin requests the corresponding `?q=` result page. Quick searches remain
available as shortcuts.
Downloads go to the configured ROM base folder, are not extracted or run,
and need to be moved into console folders before refreshing the library.
Only download content you have the right to use. This plugin does not bypass
site authentication, rate limits or access checks.

## Compiled native desktop apps

The built-in **Xbox Cloud Gaming** entry (type `external`) launches a native
OpenXCloud Rust/WebRTC executable with GPU-backed playback and no JavaScript
runtime or web view. WII-UU can download the official checksum-verified native
release into its own data directory, or launch an existing manual installation. This is intentionally *not* a general-purpose
native binary plugin API: the bundled catalog only allows the reviewed
`xcloud` external identifier, to avoid executing arbitrary commands. See the
[setup guide](xbox-cloud.md) for installation, executable selection, controller
limitations and platform requirements.

## Native apps

Add `"type": "native"` to the catalog entry. Native definitions use `NPlugin` instead of `Plugin`;
activation and bottom-bar launching work the same way. **Native Demo** is bundled as a working,
offline example. Panels use WII-UU's Java renderer and support keyboard, mouse and GamePad input.

```javascript
const nativePlugin = NPlugin("XYZ");
const view = nativePlugin.newView(ViewType.PANELS);
view.title("Browse Games");
view.closeButton(true);

const response = await fetch("https://myxyz.com/api/games");
if (!response.ok) throw new Error(`Games API returned HTTP ${response.status}`);
const games = await response.json();

for (const game of games) {
    view.addEntry(game.gameTitle, game.imageURL, [
        NButton("Download", async () => {
            await downloadManager.download(Directories.ROMS, game.downloadURL);
        })
    ]);
}
```

The API URL above is a placeholder for your own server. Use `for…of` with an array: `for…in`
iterates its index strings. Each file supports top-level `await` and defines one `NPlugin`.

| Native API | Behavior |
| --- | --- |
| `NPlugin(name).newView(ViewType.PANELS)` | Create a native panel view. The first view opens automatically. |
| `view.title(text)` | Change the title; returns the view for chaining. |
| `view.closeButton(boolean)` | Show/hide the Close button. B/Escape always returns to the menu. |
| `view.addTextField(label, placeholder, initialValue, onSubmit)` | Add a focusable native text field. Clicking or pressing controller A opens the text editor. Submitting up to 256 characters invokes `onSubmit(value)` in JavaScript. |
| `browserAuth.open(url)` | Open a dedicated sandboxed Electron browser window for interactive site sign-in. HTTPS only; no cookie or page data is exported to JavaScript. |
| `browserAuth.close()` | Close the browser authentication window. |
| `browserAuth.isAuthenticated()` | Returns whether the user selected **Return to WII-UU (I finished signing in)**. This is a user acknowledgement, **not** an HTTP authentication or Cloudflare clearance guarantee. |
| `logger.debug/info/warn/error(...values)` | Write bounded timestamped messages into `logs/native-plugin-<id>.log` in the WII-UU data folder, with a rotated `.log.1` backup. Avoid logging tokens, cookies or personal data. |
| `view.addEntry(title, imageURL, buttons)` | Append a panel with an optional HTTP(S) image and an array of `NButton` objects. Empty image strings use a title initial. Returns the view. |
| `NButton(label, callback)` | Run a sync or async JavaScript callback when selected; show a busy state until its work/downloads finish. |
| `view.status(text)` | Update the status/progress line. Returns the view. |
| `view.clear()` | Remove the view's entries before repopulating it. Returns the view. |
| `view.show()` | Switch to this view; useful for detail pages and Back buttons. Returns the view. |
| `view.close()` | Close the app and return to the menu. |
| `fetch(url, options)` | HTTP(S) requests with `response.ok`, `response.json()`, `response.text()` and streaming bodies. Default timeout: 30 seconds including the body. |
| `downloadManager.download(directory, url, filename?)` | Stream a download and return a Promise with the saved path. Uses `Directories.ROMS` or `Directories.DOWNLOADS`; optional filename is sanitized. |

Native plugins can use `browserAuth.open("https://example.com/login")` for an
interactive sign-in step when a website denies headless access. The browser
stores its own session separately at `plugin-data/<id>/browser-auth/` and
blocks out-of-origin top-level navigation. The plugin cannot read authentication
cookies or transfer them into `fetch`. On HTTP 403, ROMSFUN offers an
**Open in browser** action. The user can continue browsing in that window,
but the native catalog may still be blocked until ROMSFUN provides a
supported integration or API.

Native `fetch` sends ordinary Chrome-compatible User-Agent, Accept and language headers, and logs response codes. This does not guarantee that sites will allow requests: HTTP 403 is reported rather than bypassed. Native downloads follow HTTP(S) redirects, show progress, avoid overwriting existing files and
remove failed/cancelled partial files. Closing the app aborts its requests and downloads. ROMS
means the configured ROM base folder; move games into their system subfolder and Refresh afterward.
Image loading happens off the UI thread, with bounded image sizes and timeouts. Views paginate
automatically; Left/Right selects buttons, Up/Down moves between panels, and L/R or mouse-wheel
changes pages. Limits are 500 entries and eight buttons per panel, with 16 views per app.

Register the file in the bundled catalog:

```json
{"id":"xyz-native","name":"XYZ","description":"Browse games with native panels.","script":"xyz-native.js","type":"native"}
```

## Runtime boundaries

Remote pages have no Node.js integration, Java API, preload bridge or direct filesystem access.
The host enables context isolation and Chromium sandboxing, blocks new webviews, denies website
permission requests and leaves TLS validation enabled. Each app has separate persistent site data
under `plugin-data/<id>/`. Deleting that folder while the app is closed resets its login/storage.

Bundled plugin definitions are trusted, reviewed code. The evaluator's one-second limit helps catch
accidental loops; Node's `vm` is not a security boundary for untrusted third-party plugins. There is
no arbitrary user-script import in this version. Native definitions are also trusted source:
they run in a separate JS process, communicate with Java through bounded view/action messages,
and never evaluate API responses as code. Synchronous callback loops have a one-second limit;
closing the native app terminates an unresponsive host. The browser host and pinned npm lockfile live in
`resources/plugins/host/`; update the dependency and lockfile together when updating Electron.

Run API/policy tests with `node --test test/plugins/*.test.cjs`. The real-browser integration test
uses a local HTTP fixture; see `test/plugins/browser-smoke.cjs` for its invocation. CI also tests
the real Java/native-JS bridge, native UI navigation/rendering and streamed download cancellation.
