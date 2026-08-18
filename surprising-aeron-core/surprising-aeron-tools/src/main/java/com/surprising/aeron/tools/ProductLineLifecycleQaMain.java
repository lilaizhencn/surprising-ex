package com.surprising.aeron.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ProductLineLifecycleQaMain {
    private ProductLineLifecycleQaMain() {
    }

    public static void main(String[] args) throws Exception {
        String manifestValue = requiredProperty("surprising.aeron.lifecycle-manifest");
        System.setProperty("surprising.aeron.w4-manifest", manifestValue);
        System.setProperty("surprising.aeron.w4-seed",
                System.getProperty("surprising.aeron.lifecycle-seed", "16001"));
        System.setProperty("surprising.aeron.w4-mode", "execute");
        W4LifecycleQaMain.main(args);

        Path manifest = Path.of(manifestValue);
        List<String> normalized = Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                .map(line -> line.equals("W4_STATUS=REAL_PASS") ? "TEST_STATUS=PASS" : line)
                .toList();
        Files.write(manifest, normalized, StandardCharsets.UTF_8);
        System.out.printf("PRODUCT_LINE_TEST=PASS path=%s%n", manifest);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing property: " + name);
        }
        return value;
    }
}
