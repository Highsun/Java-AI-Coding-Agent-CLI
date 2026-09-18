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
    void shouldReturnFallbackWhenEnvFileMissing(@TempDir Path tempDir) {
        // 使用一个不可能出现在真实环境中的变量名，保证测试与开发机环境无关
        assertNull(Main.loadSetting(tempDir.resolve("no-such-env"), "AGENT_CLI_TEST_MISSING_KEY", null));
        assertEquals("default", Main.loadSetting(
                tempDir.resolve("no-such-env"), "AGENT_CLI_TEST_MISSING_KEY", "default"));
    }

    @Test
    void shouldPreferEnvFileOverEverythingElse(@TempDir Path tempDir) throws Exception {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "GLM_API_URL=http://127.0.0.1:9/v4/chat/completions\n",
                StandardCharsets.UTF_8);

        assertEquals("http://127.0.0.1:9/v4/chat/completions",
                Main.loadSetting(env, "GLM_API_URL", "https://open.bigmodel.cn/api/paas/v4/chat/completions"));
    }
}
