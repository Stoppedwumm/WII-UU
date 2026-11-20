const archive = Plugin("Build Archive");
archive
    .loadSite("https://wii-uu-archive.stoppedwumm.net")
    .disableRedirectsToOtherWebsites(true);
