package com.agent.session;

import com.agent.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path root;

    @Test void savesUnicodeNameAndDoesNotCreateDirectoriesWhenLoadingMissing() throws Exception {
        var store = new SessionStore(root);
        assertThrows(IOException.class, () -> store.load("不存在"));
        assertFalse(Files.exists(root.resolve(".agent")));
        assertThrows(IOException.class, () -> store.save("bad", List.of(Message.system("old")), false));
        assertFalse(Files.exists(root.resolve(".agent")));
        Path path = store.save("实验_01", SessionCodecTest.history(), false);
        assertEquals(root.toRealPath().resolve(".agent/sessions/实验_01.json"), path);
        assertEquals(SessionCodecTest.history(), store.load("实验_01"));
        assertThrows(FileAlreadyExistsException.class, () -> store.save("实验_01", List.of(), false));
        store.save("实验_01", List.of(), true);
        assertEquals(List.of(), store.load("实验_01"));
    }

    @Test void validatesNamesAndFileKinds() throws Exception {
        var store = new SessionStore(root);
        for (String name : List.of("", "a b", "a.b", "../x", "/tmp/x", "a/b", "a\\b", "😀", "a".repeat(65)))
            assertThrows(IOException.class, () -> store.path(name), name);
        store.save("汉".repeat(64), List.of(), false);
        Files.createDirectory(store.path("目录"));
        assertThrows(IOException.class, () -> store.load("目录"));
        assertThrows(IOException.class, () -> store.save("目录", List.of(), true));
    }

    @Test void rejectsSymlinksAtEveryLevelIncludingInternalTargets() throws Exception {
        Path inside = Files.createDirectory(root.resolve("inside"));
        Path agent = root.resolve(".agent");
        Files.createSymbolicLink(agent, inside);
        assertThrows(IOException.class, () -> new SessionStore(root).save("s", List.of(), false));
        Files.delete(agent); Files.createDirectory(agent);
        Files.createSymbolicLink(agent.resolve("sessions"), inside);
        assertThrows(IOException.class, () -> new SessionStore(root).load("s"));
        Files.delete(agent.resolve("sessions")); Files.createDirectory(agent.resolve("sessions"));
        Path target = Files.writeString(inside.resolve("real.json"), "unchanged");
        Files.createSymbolicLink(agent.resolve("sessions/s.json"), target);
        assertThrows(IOException.class, () -> new SessionStore(root).save("s", List.of(), true));
        assertEquals("unchanged", Files.readString(target));
        Files.delete(agent.resolve("sessions/s.json"));
        Files.createSymbolicLink(agent.resolve("sessions/s.json"), root.resolveSibling("outside.json"));
        assertThrows(IOException.class, () -> new SessionStore(root).load("s"));
    }

    @Test void preservesOldBytesAndCleansTempsForWriteAndCommitFailures() throws Exception {
        var normal = new SessionStore(root);
        Path file = normal.save("s", SessionCodecTest.history(), false);
        byte[] original = Files.readAllBytes(file);
        for (int phase = 0; phase < 3; phase++) {
            final int fault = phase;
            var failing = new SessionStore(root, new SessionStore.FileIO() {
                @Override public void write(Path path, byte[] bytes) throws IOException {
                    if (fault == 0) { Files.writeString(path, "partial"); throw new AccessDeniedException(path.toString()); }
                    SessionStore.FileIO.super.write(path, bytes);
                }
                @Override public void commit(Path temp, Path path, boolean overwrite) throws IOException {
                    if (fault == 1) throw new AtomicMoveNotSupportedException(temp.toString(), path.toString(), "test");
                    throw new IOException("提交失败");
                }
            });
            assertThrows(IOException.class, () -> failing.save("s", List.of(), true));
            assertArrayEquals(original, Files.readAllBytes(file));
            try (var entries = Files.list(file.getParent())) { assertEquals(1, entries.count()); }
        }
    }

    @Test void doesNotOverwriteFileCreatedDuringCommit() throws Exception {
        var store = new SessionStore(root, new SessionStore.FileIO() {
            @Override public void commit(Path temp, Path file, boolean overwrite) throws IOException {
                Files.writeString(file, "other writer", StandardOpenOption.CREATE_NEW);
                SessionStore.FileIO.super.commit(temp, file, overwrite);
            }
        });
        assertThrows(FileAlreadyExistsException.class, () -> store.save("s", List.of(), false));
        assertEquals("other writer", Files.readString(store.path("s")));
        try (var entries = Files.list(store.path("s").getParent())) { assertEquals(1, entries.count()); }
    }

    @Test void reportsReadDenialAndLimitsActualStreamBytes() throws Exception {
        new SessionStore(root).save("s", List.of(), false);
        var denied = new SessionStore(root, new SessionStore.FileIO() {
            @Override public InputStream open(Path p) throws IOException { throw new AccessDeniedException(p.toString()); }
        });
        assertThrows(AccessDeniedException.class, () -> denied.load("s"));
        int[] count = {0};
        var oversized = new SessionStore(root, new SessionStore.FileIO() {
            @Override public InputStream open(Path p) {
                return new InputStream() { @Override public int read() { count[0]++; return ' '; } };
            }
        });
        assertThrows(IOException.class, () -> oversized.load("s"));
        assertEquals(SessionCodec.MAX_BYTES + 1, count[0]);
    }
}
