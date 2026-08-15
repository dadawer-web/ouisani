package com.ouisani.aios.core.skill;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.config.AiosPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * User-facing Skill capability catalog and controlled installer.
 *
 * <p>The loader remains the source of truth for parsing and activating skills. This
 * class only adds a governed read model and a managed copy operation. Installation
 * never executes a downloaded script and only accepts sources below an explicitly
 * approved local root.</p>
 */
public final class SkillCatalogManager {
    private static final Gson JSON = new Gson();
    private static final Type STATE_TYPE = new TypeToken<Map<String, ManagedState>>() {}.getType();
    private static final class Holder { static final SkillCatalogManager INSTANCE = new SkillCatalogManager(); }

    public static SkillCatalogManager instance() { return Holder.INSTANCE; }

    private final Map<String, ManagedState> managed = new ConcurrentHashMap<>();
    private final Path stateFile;

    private SkillCatalogManager() {
        stateFile = Path.of(AiosPaths.aiosHome(), "var", "skills", "managed.json");
        loadState();
    }

    public record CatalogEntry(
            String id,
            String name,
            String description,
            String category,
            List<String> tags,
            String source,
            String path,
            boolean installed,
            boolean enabled,
            boolean controlled,
            String version,
            String risk,
            List<String> allowedTools) {
        public CatalogEntry {
            tags = tags == null ? List.of() : List.copyOf(tags);
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        }
    }

    private record ManagedState(String source, String installedPath, String version, long installedAt) {}

    /** Re-scan all loader sources and return a safe, serializable catalog. */
    public synchronized List<CatalogEntry> catalog() {
        Map<String, SkillLoader.SkillDef> loaded = SkillLoader.loadAll(System.getProperty("user.dir", "."));
        LinkedHashMap<String, CatalogEntry> result = new LinkedHashMap<>();
        for (SkillLoader.SkillDef skill : loaded.values()) {
            String id = skill.name();
            ManagedState state = managed.get(id);
            boolean installed = state != null || skill.source() == SkillLoader.SkillSource.MANAGED;
            String source = state == null ? skill.source().name() : "MANAGED";
            String path = state == null || state.installedPath() == null
                    ? skill.path() == null ? "" : skill.path().toString()
                    : state.installedPath();
            result.put(id, new CatalogEntry(id, id, skill.description(), skill.category(), skill.tags(),
                    source, path, installed, SkillLoader.isActive(id), installed,
                    state == null ? "" : state.version(), riskFor(skill), skill.allowedTools()));
        }
        // Also surface controlled managed entries whose source is temporarily unavailable.
        for (Map.Entry<String, ManagedState> e : managed.entrySet()) {
            result.putIfAbsent(e.getKey(), new CatalogEntry(e.getKey(), e.getKey(), "Managed skill",
                    "", List.of(), "MANAGED", e.getValue().installedPath(), true,
                    SkillLoader.isActive(e.getKey()), true, e.getValue().version(), "CONTROLLED", List.of()));
        }
        return List.copyOf(result.values());
    }

    public synchronized Optional<CatalogEntry> install(String name, String source, String version) {
        String id = safeId(name);
        if (id == null) return Optional.empty();
        // Do not let a managed copy silently shadow a bundled/project skill. A
        // same-name update is allowed only after the name is already controlled.
        SkillLoader.SkillDef existing = SkillLoader.loadAll(System.getProperty("user.dir", ".")).get(id);
        if (existing != null && existing.source() != SkillLoader.SkillSource.MANAGED) {
            audit("SKILL_INSTALL_DENIED", id, "name already provided by " + existing.source());
            return Optional.empty();
        }
        Path sourcePath = resolveApprovedSource(id, source);
        if (sourcePath == null || !Files.exists(sourcePath)) {
            audit("SKILL_INSTALL_DENIED", id, "source is outside approved skill roots or missing");
            return Optional.empty();
        }
        try {
            Path targetRoot = Path.of(AiosPaths.aiosHome(), "var", "skills", "managed", id)
                    .toAbsolutePath().normalize();
            Files.createDirectories(targetRoot.getParent());
            if (Files.exists(targetRoot)) deleteTree(targetRoot);
            Files.createDirectories(targetRoot);
            copyControlled(sourcePath, targetRoot);
            String resolvedVersion = version == null || version.isBlank() ? "local" : version.trim();
            managed.put(id, new ManagedState(sourcePath.toString(), targetRoot.toString(), resolvedVersion,
                    System.currentTimeMillis()));
            saveState();
            SkillLoader.loadAll(System.getProperty("user.dir", "."));
            SkillLoader.activate(id);
            audit("SKILL_INSTALLED", id, "source=" + sourcePath);
            return catalog().stream().filter(c -> c.id().equals(id)).findFirst();
        } catch (Exception e) {
            audit("SKILL_INSTALL_DENIED", id, e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized boolean setEnabled(String name, boolean enabled) {
        String id = safeId(name);
        if (id == null || !SkillLoader.getCached().containsKey(id)) return false;
        boolean changed = enabled ? SkillLoader.activate(id) : SkillLoader.deactivate(id);
        audit(enabled ? "SKILL_ENABLED" : "SKILL_DISABLED", id, changed ? "changed" : "unchanged");
        return changed || SkillLoader.isActive(id) == enabled;
    }

    public Optional<CatalogEntry> get(String id) {
        return catalog().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    private String riskFor(SkillLoader.SkillDef skill) {
        if (skill.allowedTools().stream().anyMatch(t -> t.contains("bash") || t.contains("write") || t.contains("exec"))) {
            return "REVIEW";
        }
        return skill.source() == SkillLoader.SkillSource.BUNDLED ? "LOW" : "CONTROLLED";
    }

    private Path resolveApprovedSource(String id, String requested) {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(AiosPaths.skillsDir()));
        roots.add(Path.of(System.getProperty("user.dir", "."), "aios_skills"));
        roots.add(Path.of(System.getProperty("user.dir", "."), ".aios", "skills"));
        // Development/runtime bundles are read-only approved roots as well. They are
        // never executed in-place; installation still copies them into managed/.
        roots.add(Path.of(System.getProperty("user.dir", "."), "src", "main", "resources", "skills"));
        roots.add(Path.of(System.getProperty("user.dir", "."), "neuron-java", "src", "main", "resources", "skills"));
        roots.add(Path.of(System.getProperty("user.home", "."), ".aios", "skills"));
        if (requested != null && !requested.isBlank()) {
            Path candidate = Path.of(requested).toAbsolutePath().normalize();
            for (Path root : roots) {
                Path approved = root.toAbsolutePath().normalize();
                if (candidate.startsWith(approved) && isApprovedRealPath(candidate, approved)
                        && isSkillPackage(candidate)) return candidate;
            }
            return null;
        }
        for (Path root : roots) {
            Path dir = root.toAbsolutePath().normalize().resolve(id).normalize();
            if (dir.startsWith(root.toAbsolutePath().normalize()) && Files.exists(dir)
                    && isApprovedRealPath(dir, root) && isSkillPackage(dir)) return dir;
            Path md = root.toAbsolutePath().normalize().resolve(id + ".md").normalize();
            if (md.startsWith(root.toAbsolutePath().normalize()) && Files.exists(md)
                    && isApprovedRealPath(md, root) && isSkillPackage(md)) return md;
        }
        return null;
    }

    private boolean isSkillPackage(Path source) {
        if (Files.isDirectory(source)) return Files.isRegularFile(source.resolve("SKILL.md"));
        return Files.isRegularFile(source)
                && "SKILL.md".equalsIgnoreCase(source.getFileName().toString());
    }

    private boolean isApprovedRealPath(Path candidate, Path root) {
        try {
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            return realCandidate.startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private void copyControlled(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)) throw new IOException("symlink sources are not allowed");
        if (Files.isDirectory(source)) {
            try (Stream<Path> paths = Files.walk(source)) {
                for (Path p : paths.toList()) {
                    if (Files.isSymbolicLink(p)) throw new IOException("symlink entry is not allowed");
                    Path relative = source.relativize(p);
                    Path dest = target.resolve(relative).normalize();
                    if (!dest.startsWith(target)) throw new IOException("path traversal");
                    if (Files.isDirectory(p)) Files.createDirectories(dest);
                    else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } else {
            Path dest = target.resolve("SKILL.md").normalize();
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String safeId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim();
        if (!id.matches("[A-Za-z0-9._-]{1,96}")) return null;
        return id;
    }

    private void loadState() {
        try {
            if (!Files.exists(stateFile)) return;
            Map<String, ManagedState> loaded = JSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), STATE_TYPE);
            if (loaded != null) managed.putAll(loaded);
        } catch (Exception ignored) { }
    }

    private void saveState() throws IOException {
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, JSON.toJson(managed), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void audit(String decision, String target, String reason) {
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_SKILL, decision, decision, null, target, reason,
                UnifiedAuditLog.AuditContext.current()));
    }
}
