package wiiuu.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Consent is off by default, persisted, reversible, and never treats the PIN as a password. */
public final class DiagnosticsTest {
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("wiiuu-diagnostic-test");
        Config cfg=new Config(home);
        check(!Diagnostics.enabled(cfg), "diagnostics disabled by default");
        check(Diagnostics.status(cfg).equals("Off"), "no background sharing without consent");
        for(String wrong : new String[]{"12","abcd","12345","12 3","１２３４"}) {
            check(!Diagnostics.validPin(wrong), "invalid PIN rejected");
        }
        check(Diagnostics.validPin("0042"), "leading zeros allowed");
        Diagnostics.enable(cfg,"0042");
        check(Diagnostics.enabled(cfg), "consent persists");
        check(Diagnostics.status(new Config(home)).contains("0042"), "same PIN on reload");
        Properties data=new Properties();
        try(var in=Files.newInputStream(home.resolve("diagnostics.properties"))) {data.load(in);}
        check("true".equals(data.getProperty("consent")) && "0042".equals(data.getProperty("pin")),
                "only user consent enables telemetry");
        check(!data.containsKey("token"), "PIN does not create authentication token");
        String redacted=Diagnostics.redact("token=abcdef password=sekret "
                +"https://a.example/path?a=1 192.168.1.12 /Users/Someone/Documents");
        check(!redacted.contains("abcdef") && !redacted.contains("sekret")
                && !redacted.contains("192.168.1.12") && !redacted.contains("Someone")
                && !redacted.contains("a.example"), "sensitive values removed");
        check(Diagnostics.jsonEscape("a\"b\\c\n").equals("a\\\"b\\\\c\\n"), "JSON escaping");
        Diagnostics.disable(cfg);
        check(!Diagnostics.enabled(cfg), "revocation disables uploads synchronously");
        check(Diagnostics.status(cfg).equals("Off"), "disabled UI status");
        System.out.println("DiagnosticsTest passed");
    }
    private static void check(boolean ok,String message) {
        if(!ok)throw new AssertionError(message);
    }
}
