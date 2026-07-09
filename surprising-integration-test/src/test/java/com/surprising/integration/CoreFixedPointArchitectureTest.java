package com.surprising.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CoreFixedPointArchitectureTest {

    private static final List<String> CORE_MODULES = List.of(
            "surprising-trading",
            "surprising-account",
            "surprising-risk",
            "surprising-margin-ops"
    );
    private static final Pattern DECIMAL_CORE_TYPE = Pattern.compile("\\b(BigDecimal|Double|Float|double|float)\\b");

    @Test
    void tradingAndSettlementCoreDoNotUseDecimalTypes() throws IOException {
        Path root = projectRoot();
        List<String> violations = new ArrayList<>();

        for (String module : CORE_MODULES) {
            Path moduleRoot = root.resolve(module);
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(moduleRoot)) {
                files.filter(CoreFixedPointArchitectureTest::isMainJavaSource)
                        .forEach(file -> collectDecimalTypeViolations(root, file, violations));
            }
        }

        assertThat(violations)
                .as("core execution paths must use exchange-core-compatible long units, not decimal types")
                .isEmpty();
    }

    private static boolean isMainJavaSource(Path file) {
        String normalized = file.toString().replace('\\', '/');
        return normalized.endsWith(".java") && normalized.contains("/src/main/java/");
    }

    private static void collectDecimalTypeViolations(Path root, Path file, List<String> violations) {
        try {
            String source = withoutComments(Files.readString(file));
            if (DECIMAL_CORE_TYPE.matcher(source).find()) {
                violations.add(root.relativize(file).toString());
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + file, e);
        }
    }

    private static String withoutComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        boolean blockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                } else if (current == '\n') {
                    code.append('\n');
                }
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (current == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                if (i < source.length()) {
                    code.append('\n');
                }
                continue;
            }
            code.append(current);
        }
        return code.toString();
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (Files.isDirectory(current.resolve("surprising-trading"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("surprising-trading"))) {
            return parent;
        }
        throw new IllegalStateException("cannot locate surprising-ex project root from " + current);
    }
}
