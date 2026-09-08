package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ProductionSourceBoundaryTest {
    @Test
    void productionSourcesDoNotContainTestHooksOrBenchmarkBypasses() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("surprising-aeron-core"))) {
            root = root.getParent();
            if (root == null) throw new AssertionError("repository root unavailable");
        }
        var forbidden = Pattern.compile(
                "ForTest|ForBenchmark|FaultInjector|BENCHMARK_|benchmark\\.skip|"
                        + "beforeActivationObserver|injected (?:mutable projection|.*commit) failure|"
                        + "\\bpublic static \\w+ simulate\\w*\\(");
        var violations = new ArrayList<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return switch (directory.getFileName().toString()) {
                    case ".git", ".codegraph", "target", "node_modules", "surprising-aeron-benchmarks" ->
                            FileVisitResult.SKIP_SUBTREE;
                    default -> FileVisitResult.CONTINUE;
                };
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String path = file.toString().replace('\\', '/');
                if (path.contains("/src/main/java/") && path.endsWith(".java")) {
                    String source = Files.readString(file);
                    if (forbidden.matcher(source).find()
                            || file.getFileName().toString().matches(".*(?:Test|Fixture|Benchmark|FaultAgent)\\.java")) {
                        violations.add(file.toString());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertThat(violations).as("test support must remain outside production sources").isEmpty();
    }
}
