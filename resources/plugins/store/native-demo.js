const nativePlugin = NPlugin("Native Demo");
const view = nativePlugin.newView(ViewType.PANELS)
    .title("Native plugin demo")
    .closeButton(true);
view.addEntry("Native WII-UU panels", "", [
    NButton("Try a button", () => view.status("This callback ran in JavaScript. The panels are drawn by WII-UU."))
]);
view.addEntry("Async callbacks", "", [
    NButton("Show details", async () => {
        const details = nativePlugin.newView(ViewType.PANELS).title("Details");
        details.addEntry("A second native view", "", [NButton("Back", () => view.show())]);
        details.show();
    })
]);
