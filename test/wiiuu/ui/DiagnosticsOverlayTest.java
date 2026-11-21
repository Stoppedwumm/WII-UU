package wiiuu.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import wiiuu.core.Config;
import wiiuu.core.Diagnostics;
import wiiuu.input.PadButton;

/** Exercises the secret entry screen, explicit refusal, consent and gamepad navigation. */
public final class DiagnosticsOverlayTest {
    public static void main(String[] args) throws Exception {
        Config config=new Config(Files.createTempDirectory("wiiuu-diag-ui-"));
        config.set("diagnostics.url","http://127.0.0.1:9"); // no external upload in CI
        config.save();
        DiagnosticsOverlay ui=new DiagnosticsOverlay(config);
        check(!ui.active(),"disabled until ten logo clicks");
        ui.open();
        check(ui.wantsKeys(),"PIN accepts keyboard digits");
        for(char c:"1234".toCharArray())ui.keyTyped(c);
        ui.activate(); // last digit -> consent
        check(ui.stage()==DiagnosticsOverlay.Stage.CONSENT,"disclaimer after four-digit PIN");
        check(!Diagnostics.enabled(config),"not uploaded until accepted");
        ui.activate(); // Decline is selected by default.
        check(!ui.active()&&!Diagnostics.enabled(config),"declining does not enable logging");
        ui.open();
        for(char c:"1234".toCharArray())ui.keyTyped(c);
        ui.activate();
        ui.navigate(1,0); // explicit agree
        ui.activate();
        check(Diagnostics.enabled(config),"acceptance enables telemetry");
        check(Diagnostics.status(config).contains("1234"),"four-digit PIN kept as identifier");
        check(ui.stage()==DiagnosticsOverlay.Stage.COMPLETE,"completion screen after consent");
        BufferedImage canvas=new BufferedImage(1280,720,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=canvas.createGraphics();ui.paint(g,1280,720);g.dispose();
        check(ui.pressed(PadButton.B,true),"controller can dismiss overlay");
        check(!ui.active(),"controller B closes dialog");
        Diagnostics.disable(config);
        check(!Diagnostics.enabled(config),"Settings-style opt-out stops sharing");
        System.out.println("DiagnosticsOverlayTest passed");
    }
    private static void check(boolean condition,String why){if(!condition)throw new AssertionError(why);}
}
