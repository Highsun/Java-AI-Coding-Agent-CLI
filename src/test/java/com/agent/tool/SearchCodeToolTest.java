package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchCodeToolTest {
    @Test
    void shouldFindKeywordWithFileLineAndTrimmedContent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.java"), "class A {\n    // TODO: implement\n}");

        List<SearchCodeTool.Match> matches = new SearchCodeTool().searchCode("TODO", dir);

        assertEquals(1, matches.size());
        assertEquals("src" + java.io.File.separator + "A.java", matches.get(0).file());
        assertEquals(2, matches.get(0).line());
        assertEquals("// TODO: implement", matches.get(0).content());
    }

    @Test
    void shouldFindMatchesInNestedFiles(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("nested/deeper"));
        Files.writeString(dir.resolve("nested/deeper/B.java"), "TODO\nno match\nTODO again");

        assertEquals(2, new SearchCodeTool().searchCode("TODO", dir).size());
    }

    @Test
    void shouldSkipKnownBinaryExtensions(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("A.java"), "TODO");
        Files.write(dir.resolve("B.class"), "TODO".getBytes());
        Files.write(dir.resolve("C.png"), "TODO".getBytes());

        assertEquals(1, new SearchCodeTool().searchCode("TODO", dir).size());
    }
}
