const mainScreen = Plugin("WII-UU Website");
mainScreen
    .loadSite("https://wiiuu.stoppedwumm.net")
    .disableRedirectsToOtherWebsites(true);
