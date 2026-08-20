package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class SpringBoot4ToHelidonMpTest implements RewriteTest {
    private static final String RECIPE =
            "io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp";

    @Override
    public void defaults(RecipeSpec spec) {
        Environment environment = Environment.builder()
                .scanRuntimeClasspath("io.github.devanshops.rewrite.helidon")
                .build();
        spec.recipe(environment.activateRecipes(RECIPE))
                .parser(JavaParser.fromJavaVersion().classpath("spring-context", "spring-web"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void assessesMixedModuleWithoutCreatingAHybridRuntime() {
        rewriteRun(
          java(
            """
              package com.example.catalog;

              import org.springframework.stereotype.Service;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @Service
              class CatalogService {
              }

              @RestController
              class CatalogEndpoint {
                  @GetMapping("/catalog")
                  String get(@RequestParam(required = false) String query) {
                      return query;
                  }
              }
              """,
            """
              package com.example.catalog;

              /*~~(PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject)~~>*//*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/import org.springframework.stereotype.Service;
              /*~~(MANUAL: Spring MVC -> Jakarta REST annotations)~~>*//*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/import org.springframework.web.bind.annotation.GetMapping;
              /*~~(MANUAL: Spring MVC -> Jakarta REST annotations)~~>*//*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/import org.springframework.web.bind.annotation.RequestParam;
              /*~~(MANUAL: Spring MVC -> Jakarta REST annotations)~~>*//*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/import org.springframework.web.bind.annotation.RestController;

              @/*~~(PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject)~~>*/Service
              class CatalogService {
              }

              @/*~~(MANUAL: Spring MVC -> Jakarta REST annotations)~~>*/RestController
              class CatalogEndpoint {
                  @/*~~(MANUAL: Spring MVC -> Jakarta REST annotations)~~>*/GetMapping("/catalog")
                  String get(@/*~~(MANUAL: Spring MVC -> Jakarta REST annotations)~~>*/RequestParam(required = false) String query) {
                      return query;
                  }
              }
              """,
            source -> source.path("src/main/java/com/example/catalog/CatalogModule.java")
          )
        );
    }
}
