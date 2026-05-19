package pro.deadeangaffer.glsound;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class Autostart {

    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "GL-Sound";

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    private Autostart() {}

    public static Optional<Path> currentExecutable() {
        return ProcessHandle.current().info().command().map(Path::of);
    }

    public static boolean isInstalled() {
        var res = run(List.of("reg", "query", RUN_KEY, "/v", VALUE_NAME));
        return res.ok();
    }

    public static void install(Path exe) throws IOException {
        var data = "\"" + exe.toString() + "\"";
        var res = run(List.of(
                "reg", "add", RUN_KEY,
                "/v", VALUE_NAME,
                "/t", "REG_SZ",
                "/d", data,
                "/f"
        ));
        if (!res.ok()) {
            throw new IOException("reg add exit=" + res.exitCode()
                    + (res.stderr().isBlank() ? "" : "\nstderr: " + res.stderr().trim())
                    + (res.stdout().isBlank() ? "" : "\nstdout: " + res.stdout().trim()));
        }
    }

    public static void uninstall() throws IOException {
        var res = run(List.of("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f"));
        if (!res.ok()) {
            if (res.stderr() != null && res.stderr().toLowerCase().contains("unable to find")) return;
            throw new IOException("reg delete exit=" + res.exitCode()
                    + (res.stderr().isBlank() ? "" : "\nstderr: " + res.stderr().trim()));
        }
    }

    private static Result run(List<String> cmd) {
        try {
            var p = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();
            Charset oem;
            try { oem = Charset.forName("cp866"); }
            catch (Exception e) { oem = Charset.defaultCharset(); }

            var stdoutBytes = p.getInputStream().readAllBytes();
            var stderrBytes = p.getErrorStream().readAllBytes();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "", "timeout");
            }
            return new Result(p.exitValue(),
                    new String(stdoutBytes, oem),
                    new String(stderrBytes, oem));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(-1, "", e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
