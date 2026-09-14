package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedThreadConventionTest {

    @Test
    void productionCodeUsesExplicitLifecycleManagedExecutors() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path :
                    paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (source.contains("Executors.") || source.contains("new Thread(")) {
                    violations.add(sourceRoot.relativize(path).toString());
                }
            }
        }

        assertThat(violations).as("生产线程必须由显式 ThreadPoolExecutor 管理容量、拒绝与关闭").isEmpty();
    }
}
