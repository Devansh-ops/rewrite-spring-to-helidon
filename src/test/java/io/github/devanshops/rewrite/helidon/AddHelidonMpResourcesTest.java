package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Paths;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class AddHelidonMpResourcesTest implements RewriteTest {
    private static final String BEANS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                                       https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
                   version="4.0"
                   bean-discovery-mode="annotated">
            </beans>
            """;

    private static final String MICROPROFILE_CONFIG = """
            # Helidon MP / MicroProfile Config
            # No defaults are generated: migrate each Spring property only after semantic review.
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddHelidonMpResources())
                .parser(JavaParser.fromJavaVersion().classpath("spring-boot"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void createsResourcesForAnExecutableRootModule() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.boot.SpringApplication;

              class RootApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(RootApplication.class, args);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/RootApplication.java")
          ),
          text(null, BEANS_XML,
            spec -> spec.path("src/main/resources/META-INF/beans.xml")),
          text(null, MICROPROFILE_CONFIG,
            spec -> spec.path("src/main/resources/META-INF/microprofile-config.properties"))
        );
    }

    @Test
    void createsOnlyMissingResourcesInEachNestedExecutableModule() {
        String existingBeans = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
                       version="4.0"
                       bean-discovery-mode="all">
                </beans>
                """;
        String existingConfig = """
                server.port=9090
                app.name=catalog
                """;

        rewriteRun(
          java(
            """
              package org.springframework.boot.autoconfigure;

              public @interface SpringBootApplication {
              }
              """,
            spec -> spec.path(
              "test-support/src/main/java/org/springframework/boot/autoconfigure/SpringBootApplication.java")
          ),
          java(
            """
              package com.example.orders;

              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class OrdersApplication {
              }
              """,
            spec -> spec.path(
              "services/orders/src/main/java/com/example/orders/OrdersApplication.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;

              class CatalogApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            spec -> spec.path(
              "services/catalog/src/main/java/com/example/catalog/CatalogApplication.java")
          ),
          text(existingBeans,
            spec -> spec.path("services/orders/src/main/resources/META-INF/beans.xml")),
          text(null, MICROPROFILE_CONFIG,
            spec -> spec.path(
              "services/orders/src/main/resources/META-INF/microprofile-config.properties")),
          text(null, BEANS_XML,
            spec -> spec.path("services/catalog/src/main/resources/META-INF/beans.xml")),
          text(existingConfig,
            spec -> spec.path(
              "services/catalog/src/main/resources/META-INF/microprofile-config.properties"))
        );
    }

    @Test
    void ignoresModulesWithoutASpringBootApplication() {
        rewriteRun(
          spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
          java(
            """
              package com.example.library;

              class LibraryComponent {
              }
              """,
            spec -> spec.path(
              "services/library/src/main/java/com/example/library/LibraryComponent.java")
          )
        );
    }

    @Test
    void accumulatorIsSafeForParallelSourceScanning() {
        AddHelidonMpResources.Accumulator accumulator =
                new AddHelidonMpResources.Accumulator();

        IntStream.range(0, 10_000).parallel().forEach(index -> {
            accumulator.recordExecutableModuleRoot(Paths.get("module-" + index));
            accumulator.recordExistingSourcePath(Paths.get("source-" + index));
        });

        assertEquals(10_000, accumulator.executableModuleRootCount());
        assertEquals(10_000, accumulator.existingSourcePathCount());
    }
}
