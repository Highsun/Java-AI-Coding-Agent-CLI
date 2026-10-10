package com.agent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/** cwd .env > environment > per-user .env > defaults, on all launch paths. */
public final class ConfigurationStore {
    private final Path userFile;
    private final Map<String, String> environment;

    public ConfigurationStore(Path userFile, Map<String, String> environment) {
        this.userFile = userFile.toAbsolutePath().normalize();
        this.environment = Map.copyOf(environment);
    }

    public static ConfigurationStore system() {
        Map<String, String> env = System.getenv();
        String base = env.get("XDG_CONFIG_HOME");
        Path directory = base == null || base.isBlank()
                ? Path.of(env.getOrDefault("HOME", System.getProperty("user.home")), ".config") : Path.of(base);
        return new ConfigurationStore(directory.resolve("java-coding-agent/.env"), env);
    }

    public Path userFile() { return userFile; }

    public AgentConfiguration load(Path currentEnv) throws IOException {
        Map<String, String> local = readFile(currentEnv);
        Map<String, String> user = readFile(userFile);
        AgentConfiguration defaults = AgentConfiguration.defaults();
        return new AgentConfiguration(value("GLM_API_KEY", local, user, null),
                value("GLM_API_URL", local, user, defaults.apiUrl()),
                value("GLM_MODEL", local, user, defaults.model()));
    }

    private String value(String name, Map<String, String> local, Map<String, String> user, String fallback) {
        String value = local.get(name);
        if (value == null || value.isBlank()) value = environment.get(name);
        if (value == null || value.isBlank()) value = user.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public boolean hasOverrides(Path currentEnv) throws IOException {
        Map<String, String> local = readFile(currentEnv);
        return java.util.List.of("GLM_API_KEY", "GLM_API_URL", "GLM_MODEL").stream()
                .anyMatch(key -> local.containsKey(key) || (environment.containsKey(key) && !environment.get(key).isBlank()));
    }

    public static Map<String, String> readFile(Path path) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) return result;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            line = line.replace("\uFEFF", "").trim();
            int equals = line.indexOf('=');
            if (equals < 1) continue;
            String name = line.substring(0, equals);
            if (!java.util.Set.of("GLM_API_KEY", "GLM_API_URL", "GLM_MODEL").contains(name)) continue;
            String value = line.substring(equals + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            if (!value.isBlank()) result.putIfAbsent(name, value);
        }
        return result;
    }

    /** Store only user settings; no project .env is modified. Temp file is private from creation. */
    public void save(AgentConfiguration config) throws IOException {
        if (!config.hasApiKey() || !AgentConfiguration.validApiUrl(config.apiUrl())
                || !AgentConfiguration.validModel(config.model())) throw new IllegalArgumentException("配置内容无效。");
        Files.createDirectories(userFile.getParent());
        if (Files.isSymbolicLink(userFile)) throw new IOException("配置文件是符号链接，未覆盖。");
        boolean posix = Files.getFileStore(userFile.getParent()).supportsFileAttributeView("posix");
        Path temporary = posix
                ? Files.createTempFile(userFile.getParent(), ".config-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(userFile.getParent(), ".config-", ".tmp");
        try {
            Files.writeString(temporary, "# Java AI Coding Agent user configuration\n"
                    + "GLM_API_KEY=" + config.apiKey() + "\nGLM_API_URL=" + config.apiUrl()
                    + "\nGLM_MODEL=" + config.model() + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, userFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, userFile, StandardCopyOption.REPLACE_EXISTING);
            }
            if (posix) Files.setPosixFilePermissions(userFile, PosixFilePermissions.fromString("rw-------"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
