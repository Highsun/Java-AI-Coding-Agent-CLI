package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Recursively lists regular files below a directory. */
public class ListFilesTool {
    /**
     * Returns sorted paths relative to {@code root}.
     *
     * @param root directory to traverse
     * @return relative paths for all regular files below {@code root}
     * @throws IOException if the directory cannot be traversed
     */
    public List<String> listFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }
}
