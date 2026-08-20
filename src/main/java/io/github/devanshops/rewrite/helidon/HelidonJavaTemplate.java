package io.github.devanshops.rewrite.helidon;

import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;

import java.nio.file.Path;
import java.util.List;

/** Creates templates with the target APIs available before a project's POM has been migrated. */
final class HelidonJavaTemplate {
    private static final List<Path> TARGET_CLASSPATH = JavaParser.dependenciesFromClasspath(
            "jakarta.enterprise.cdi-api",
            "jakarta.inject-api",
            "jakarta.ws.rs-api",
            "jakarta.transaction-api",
            "microprofile-config-api",
            "helidon");

    private HelidonJavaTemplate() {
    }

    static JavaTemplate.Builder builder(String source) {
        return JavaTemplate.builder(source)
                .javaParser(JavaParser.fromJavaVersion().classpath(TARGET_CLASSPATH));
    }
}
