// Native ROMSFUN catalog. Treat remote HTML as data; never execute it.
const plugin = NPlugin("ROMSFUN");
const browse = plugin.newView(ViewType.PANELS).title("ROMSFUN · Catalog");
const detail = plugin.newView(ViewType.PANELS).title("ROMSFUN · Details");
const root = "https://romsfun.com";
let search = "", page = 1, selectedGame = "";
const gamePattern = /^\/roms\/[^/]+\/[^/]+\.html\/?$/;
const downloadPattern = /^\/download\/[a-z0-9-]+(?:\/\d+)?\/?$/i;
function plain(s) {
    return String(s || "").replace(/<[^>]*>/g, " ").replace(/&amp;|&#38;/gi, "&")
        .replace(/&quot;/gi, '"').replace(/&#39;|&#x27;/gi, "'")
        .replace(/&nbsp;/gi, " ").replace(/\s+/g, " ").trim();
}
function attribute(s, key) {
    const match = new RegExp("\\b" + key + "\\s*=\\s*['\"]([^'\"]*)['\"]", "i").exec(s);
    return match ? match[1].replace(/&amp;/g, "&") : "";
}
function siteUrl(raw, pattern) {
    try {
        const url = new URL(raw, root);
        return url.protocol === "https:" && url.hostname === "romsfun.com"
            && !url.username && !url.password && pattern.test(url.pathname) ? url.href : null;
    } catch (_) { return null; }
}
function picture(raw) {
    try {
        const url = new URL(raw, root);
        return url.protocol === "https:" && !url.username && !url.password
            && (url.hostname === "romsfun.com" || url.hostname.endsWith(".romsfun.com")) ? url.href : "";
    } catch (_) { return ""; }
}
function links(html, pattern) {
    const matches = [], seen = new Set();
    for (const m of html.matchAll(/<a\b([^>]*\bhref\s*=\s*["'][^"']+["'][^>]*)>([\s\S]*?)<\/a>/gi)) {
        const url = siteUrl(attribute(m[1], "href"), pattern);
        if (url && !seen.has(url)) { seen.add(url); matches.push({ url, name: plain(m[2]) }); }
    }
    return matches;
}
async function get(url) {
    logger.info("ROMSFUN request", new URL(url).pathname);
    const response = await fetch(url, { headers: { Accept: "text/html" } });
    if (!response.ok) {
        logger.warn("ROMSFUN returned HTTP", response.status, new URL(url).pathname);
        throw new Error("HTTP " + response.status + (response.status === 403
            ? " — access denied by ROMSFUN; open in a browser to check site access" : ""));
    }
    if (!(response.headers.get("content-type") || "").includes("html"))
        throw new Error("Expected HTML, not a downloadable file");
    return response.text();
}
async function loadCatalog(query = search, number = 1) {
    search = query; page = number;
    browse.show().clear().title("ROMSFUN · " + (search || "All games") + " · " + page).status("Loading...");
    browse.addTextField("Search games", "Enter a game title...", search,
        value => loadCatalog(value.trim(), 1));
    const path = page > 1 ? "/browse-all-roms/page/" + page + "/" : "/browse-all-roms/";
    try {
        const html = await get(root + path + (search ? "?q=" + encodeURIComponent(search) : ""));
        const cards = html.split(/<div\b[^>]*class=["'][^"']*\brf-li\s+[^"']*["'][^>]*>/i).slice(1);
        let count = 0;
        for (const card of cards) {
            const part = card.slice(0, 4000);
            const game = links(part, gamePattern)[0];
            if (!game) continue;
            const img = /<img\b[^>]*>/i.exec(part)?.[0] || "";
            browse.addEntry((game.name || attribute(img, "alt") || "Game").slice(0, 160),
                picture(attribute(img, "src") || attribute(img, "data-src")), [
                    NButton("Details", () => loadGame(game.url))
                ]);
            if (++count >= 60) break;
        }
        if (!count) browse.addEntry("No matching games", "", []);
        const next = html.includes("/browse-all-roms/page/" + (page + 1) + "/");
        browse.addEntry("Catalog navigation", "", [
            ...(page > 1 ? [NButton("Previous", () => loadCatalog(search, page - 1))] : []),
            ...(next ? [NButton("Next", () => loadCatalog(search, page + 1))] : []),
            NButton("All games", () => loadCatalog("", 1)),
            NButton("Mario", () => loadCatalog("mario", 1)),
            NButton("Sonic", () => loadCatalog("sonic", 1)),
            NButton("Zelda", () => loadCatalog("zelda", 1)),
            NButton("Pokemon", () => loadCatalog("pokemon", 1))
        ]);
        browse.status(count + " games · Page " + page + (search ? " · " + search : ""));
    } catch (error) {
        browse.status("Catalog failed: " + String(error.message).slice(0, 280));
        browse.addEntry("Retry loading", "", [NButton("Retry", () => loadCatalog(search, page))]);
    }
}
async function loadGame(url) {
    selectedGame = url;
    detail.show().clear().title("ROMSFUN · Game").status("Loading game...");
    detail.addEntry("Navigation", "", [NButton("Back to catalog", () => browse.show())]);
    try {
        const html = await get(url);
        const title = plain(/<h1\b[^>]*>([\s\S]*?)<\/h1>/i.exec(html)?.[1]) || "Game";
        const image = /<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']/i.exec(html)?.[1] || "";
        const choices = links(html, downloadPattern).filter(item => !/\/\d+\/?$/.test(new URL(item.url).pathname));
        detail.addEntry(title.slice(0, 160), picture(image), choices.slice(0, 4).map(choice =>
            NButton("Versions", () => loadVersions(choice.url, title))));
        detail.status(choices.length ? "Select Versions to choose a download" : "No versions listed");
    } catch (error) { detail.status("Game failed: " + String(error.message).slice(0, 280)); }
}
async function loadVersions(url, title) {
    detail.show().clear().title("Versions · " + title.slice(0, 30)).status("Loading versions...");
    detail.addEntry("Navigation", "", [
        NButton("Back", () => loadGame(selectedGame)),
        NButton("Catalog", () => browse.show())
    ]);
    try {
        const html = await get(url);
        const versions = links(html, downloadPattern)
            .filter(item => /\/\d+\/?$/.test(new URL(item.url).pathname));
        for (const item of versions.slice(0, 75))
            detail.addEntry((item.name || "Version").slice(0, 160), "", [
                NButton("Select", () => loadDownload(item.url, item.name || title))
            ]);
        if (!versions.length) detail.addEntry("No versions available", "", []);
        detail.status(versions.length + " download variants");
    } catch (error) { detail.status("Versions failed: " + String(error.message).slice(0, 280)); }
}
function signedLink(html) {
    const tag = /<a\b(?=[^>]*\bid=["']download-link["'])[^>]*>/i.exec(html)?.[0] || "";
    const raw = attribute(tag, "href");
    const url = new URL(raw);
    if (url.protocol !== "https:" || url.username || url.password)
        throw new Error("No valid download URL");
    return url.href;
}
async function loadDownload(url, title) {
    detail.show().clear().title("Download · " + title.slice(0, 30)).status("Checking link...");
    detail.addEntry("Navigation", "", [
        NButton("Back to catalog", () => browse.show())
    ]);
    try {
        signedLink(await get(url));
        detail.addEntry(title.slice(0, 160), "", [
            NButton("Download file", async () => {
                // Signed links expire; retrieve the current URL only on explicit click.
                const destination = signedLink(await get(url));
                await downloadManager.download(Directories.ROMS, destination);
            })
        ]);
        detail.status("Select Download file · Saved in ROMs folder, not installed or extracted");
    } catch (error) { detail.status("Link unavailable: " + String(error.message).slice(0, 280)); }
}
browse.addEntry("Loading ROMSFUN...", "", []);
await loadCatalog();
