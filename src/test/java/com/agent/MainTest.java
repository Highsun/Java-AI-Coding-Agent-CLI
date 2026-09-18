package com.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@code .env} 加载逻辑的测试，不依赖真实 Key。 */
class MainTest {

    @Test
    void shouldReadKeyFromEnvFile(@TempDir Path tempDir) throws Exception {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "# 注释\nGLM_API_KEY=abc123\n", StandardCharsets.UTF_8);

        assertEquals("abc123", Main.loadApiKey(env));
    }

    @Test
    void shouldIgnoreBomAndTrimQuotes(@TempDir Path tempDir) throws Exception {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "\uFEFFGLM_API_KEY=\"quoted-key\"\n", StandardCharsets.UTF_8);

        assertEquals("quoted-key", Main.loadApiKey(env));
    }

    @Test
    void shouldReturnNullWhenEnvFileMissing(@TempDir Path tempDir) {
        assertNull(Main.loadApiKey(tempDir.resolve("no-such-env")));
    }
}
