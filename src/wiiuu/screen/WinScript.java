package wiiuu.screen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs PowerShell on Windows the reliable way: from a script file, never inline. (Java doesn't
 * escape the double quotes inside a {@code -Command} argument on Windows, so a script containing
 * any, such as C# code, reaches PowerShell garbled and does nothing.)
 *
 * <p>C# helpers are compiled once into a DLL next to the script (named after the source's hash)
 * instead of on every call, which saves about a second each time.
 */
public final class WinScript {
    private static volatile Path dir = Path.of(System.getProperty("user.home"), ".wiiuu", "bin");

    private WinScript() {}

    public static void setDir(Path d) {
        dir = d;
    }

    /**
     * Runs {@code body} (PowerShell) with {@code args}. {@code csharp}, if given, is compiled once
     * into a DLL and loaded before {@code body} runs.
     */
    static List<String> run(String name, String csharp, String body, int timeoutSec, String... args) {
        try {
            Files.createDirectories(dir);
            StringBuilder script = new StringBuilder();
            String params = body.startsWith("param(") ? body.substring(0, body.indexOf('\n') + 1) : "";
            script.append(params);
            if (csharp != null) {
                String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(csharp.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
                Path src = dir.resolve(name + "-" + hash + ".cs"), dll = dir.resolve(name + "-" + hash + ".dll");
                if (!Files.exists(src)) Files.writeString(src, csharp);
                script.append("$dll = '").append(dll.toString().replace("'", "''")).append("'\n")
                        // built under a name of its own first: two helpers starting at once can't load half a DLL
                        .append("if (-not (Test-Path $dll)) { $tmp = \"$dll.$PID.tmp\"; Add-Type -Path '").append(src.toString().replace("'", "''"))
                        .append("' -OutputAssembly $tmp -OutputType Library; Move-Item -Force $tmp $dll -ErrorAction SilentlyContinue }\n")
                        .append("Add-Type -Path $dll\n");
            }
            script.append(params.isEmpty() ? body : body.substring(params.length()));
            Path file = dir.resolve(name + ".ps1");
            String text = script.toString();
            if (!Files.exists(file) || !Files.readString(file).equals(text)) Files.writeString(file, text);
            List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file.toString()));
            cmd.addAll(List.of(args));
            return exec(timeoutSec, cmd);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return List.of("error: " + e.getMessage());
        }
    }

    /** A script written as {@code Add-Type @' <C#> '@} followed by PowerShell: the C# goes into the DLL. */
    static List<String> runInline(String name, String script, int timeoutSec, String... args) {
        int a = script.indexOf("Add-Type @'"), b = script.indexOf("\n'@", a);
        if (a < 0 || b < 0) return run(name, null, script, timeoutSec, args);
        String csharp = script.substring(script.indexOf('\n', a) + 1, b);
        String body = script.substring(0, a) + script.substring(b + 3);
        return run(name, csharp, body.trim() + "\n", timeoutSec, args);
    }

    static List<String> exec(int timeoutSec, List<String> cmd) {
        List<String> lines = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) lines.add(l);
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (IOException e) {
            // no PowerShell
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lines;
    }
}
