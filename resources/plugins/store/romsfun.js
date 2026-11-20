// Browse ROMSFUN using its own search and download pages.
// Preserve site-managed mirrors instead of guessing direct file URLs.
const romsfun = Plugin("ROMSFUN");
romsfun
    .loadSite("https://romsfun.com/browse-all-roms/#roms")
    .disableRedirectsToOtherWebsites(true)
    .redirectDownloads(true, Directories.ROMS);
