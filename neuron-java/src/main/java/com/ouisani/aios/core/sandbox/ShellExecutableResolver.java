package com.ouisani.aios.core.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Resolves shell executables without accidentally selecting the Windows WSL
 * launcher when a native POSIX shell (for example Git Bash) is available.
 *
 * <p>{@link ProcessBuilder} on Windows searches well-known system locations
 * before the process PATH.  That means a command such as {@code bash} can
 * resolve to {@code %WINDIR%\\System32\\bash.exe}, even when Git Bash was
 * deliberately put first in PATH for a test or a local sandbox.  The WSL
 * launcher then fails if no Linux distribution is installed.  Selecting an
 * explicit PATH entry keeps the backend deterministic while retaining the
 * original executable name as a fallback on other platforms.</p>
 */
public final class ShellExecutableResolver {

    private ShellExecutableResolver() {}

    /** Resolve a shell name such as {@code bash} or {@code sh}. */
    public static String resolve(String executable) {
        if (executable == null || executable.isBlank() || !isWindows()) {
            return executable;
        }
        if (executable.indexOf('\\') >= 0 || executable.indexOf('/') >= 0) {
            return executable;
        }

        String explicit = System.getenv("AIOS_" + executable.toUpperCase(Locale.ROOT) + "_PATH");
        if (isExecutable(explicit)) {
            return explicit;
        }

        String wslFallback = null;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (entry == null || entry.isBlank()) continue;
                Path directory;
                try {
                    directory = Paths.get(entry.trim());
                } catch (RuntimeException ignored) {
                    continue;
                }

                // Git for Windows commonly exposes only <git>/cmd on PATH;
                // discover its POSIX shell beside that launcher even when
                // <git>/usr/bin is not itself on PATH.
                Path gitBash = findGitShell(directory, executable);
                if (isExecutable(gitBash == null ? null : gitBash.toString())) {
                    return gitBash.toString();
                }

                Path candidate = directory.resolve(executable + ".exe");
                if (!isExecutable(candidate.toString())) {
                    candidate = directory.resolve(executable);
                }
                if (!isExecutable(candidate.toString())) continue;

                if (isWslLauncher(candidate)) {
                    wslFallback = candidate.toString();
                } else {
                    return candidate.toString();
                }
            }
        }
        return wslFallback == null ? executable : wslFallback;
    }

    /** Whether a resolved shell is Git Bash and therefore accepts POSIX paths. */
    public static boolean isGitBash(String executable) {
        if (executable == null) return false;
        String normalized = executable.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/git/") && normalized.endsWith("/bash.exe");
    }

    /** Whether a resolved executable belongs to the Git-for-Windows POSIX toolchain. */
    public static boolean isGitShell(String executable) {
        if (executable == null) return false;
        String normalized = executable.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/git/")
                && (normalized.endsWith("/bash.exe") || normalized.endsWith("/sh.exe"));
    }

    /**
     * Make Git Bash's companion commands (cat, sleep, seq, ...) visible when
     * the shell was selected by an explicit path but its usr/bin directory is
     * not present in the parent JVM's PATH.
     */
    public static void configureEnvironment(ProcessBuilder process, String executable) {
        if (process == null || !isGitShell(executable)) return;
        Path shell = Paths.get(executable).toAbsolutePath();
        Path gitRoot = findGitRoot(shell.getParent());
        if (gitRoot == null) return;

        String usrBin = gitRoot.resolve("usr").resolve("bin").toString();
        String gitBin = gitRoot.resolve("bin").toString();
        String existing = process.environment().get("PATH");
        String prefix = usrBin + java.io.File.pathSeparator + gitBin;
        process.environment().put("PATH",
                existing == null || existing.isBlank() ? prefix : prefix + java.io.File.pathSeparator + existing);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static boolean isExecutable(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return Files.isRegularFile(Paths.get(value));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isWslLauncher(Path candidate) {
        String normalized = candidate.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.endsWith("/windows/system32/bash.exe");
    }

    private static Path findGitShell(Path pathEntry, String executable) {
        Path gitRoot = findGitRoot(pathEntry);
        return gitRoot == null ? null : gitRoot.resolve("usr").resolve("bin").resolve(executable + ".exe");
    }

    private static Path findGitRoot(Path path) {
        Path cursor = path;
        for (int i = 0; cursor != null && i < 5; i++, cursor = cursor.getParent()) {
            Path name = cursor.getFileName();
            if (name != null && name.toString().equalsIgnoreCase("Git")) {
                return cursor;
            }
        }
        return null;
    }
}
