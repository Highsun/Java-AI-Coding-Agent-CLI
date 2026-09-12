package com.agent.tool;

import java.nio.file.Path;

/** Demonstrates the three independent file tools from the command line. */
public class FileToolsDemo {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(".");
        var lister = new ListFilesTool();
        var reader = new ReadFileTool();
        var searcher = new SearchCodeTool();

        System.out.println("== list_files ==");
        lister.listFiles(root).stream().limit(20).forEach(System.out::println);

        if (args.length >= 1) {
            System.out.println("== read_file " + args[0] + " ==");
            System.out.println(reader.readFile(Path.of(args[0])));
        }

        String keyword = args.length >= 2 ? args[1] : "TODO";
        System.out.println("== search_code " + keyword + " ==");
        searcher.searchCode(keyword, root).forEach(match ->
                System.out.println(match.file() + ":" + match.line() + ": " + match.content()));
    }
}
