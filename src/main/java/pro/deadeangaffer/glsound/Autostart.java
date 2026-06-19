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
    private static final Charset OEM = Charset.forName("IBM866");

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    private Autostart() {}

    public static Optional<Path> currentExecutable() {
        return ProcessHandle.current().info().command().map(Path::of);
    }

    public static boolean isInstalled() {
        return run(List.of("reg", "query", RUN_KEY, "/v", VALUE_NAME)).ok();
    }

    public static void install(Path exe) throws IOException {
        Result res = run(List.of(
                "reg", "add", RUN_KEY,
                "/v", VALUE_NAME,
                "/t", "REG_SZ",
                "/d", "\"%s\"".formatted(exe.toString()),
                "/f"
        ));
        if (res.ok()) return;
        throw new IOException("reg add exit=%d%s%s".formatted(
                res.exitCode(),
                streamSuffix("stderr", res.stderr()),
                streamSuffix("stdout", res.stdout())));
    }

    public static void uninstall() throws IOException {
        Result res = run(List.of("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f"));
        if (res.ok()) return;
        if (res.stderr() != null && res.stderr().toLowerCase().contains("unable to find")) return;
        throw new IOException("reg delete exit=%d%s".formatted(
                res.exitCode(),
                streamSuffix("stderr", res.stderr())));
    }

    private static String streamSuffix(String label, String stream) {
        return Optional.ofNullable(stream)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> "\n%s: %s".formatted(label, s))
                .orElse("");
    }

    private static Result run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();
            byte[] stdoutBytes = p.getInputStream().readAllBytes();
            byte[] stderrBytes = p.getErrorStream().readAllBytes();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "", "timeout");
            }
            return new Result(p.exitValue(), new String(stdoutBytes, OEM), new String(stderrBytes, OEM));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(-1, "", Optional.ofNullable(e.getMessage()).orElse(""));
        }
    }
}
