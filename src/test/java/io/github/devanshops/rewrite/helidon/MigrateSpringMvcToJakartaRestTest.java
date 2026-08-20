package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateSpringMvcToJakartaRestTest implements RewriteTest {

    private static final String MARKER =
            "Manual migration: v0.1 preserves this Spring MVC controller because routing, binding, validation, " +
            "and response semantics are not yet proven equivalent; no source code was changed";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringMvcToJakartaRest())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-web", "spring-core")
                        .dependsOn(
                                """
                                  package org.springframework.security.access.prepost;
                                  public @interface PreAuthorize {
                                      String value();
                                  }
                                  """,
                                """
                                  package org.springframework.security.web;
                                  public interface SecurityFilterChain {}
                                  """))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void preservesSimpleControllerAndMarksItForManualMigration() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(%s)~~>*/@RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """.formatted(MARKER)
          )
        );
    }

    @Test
    void preservesUnsafeBindingRolesWithoutPartialImportsOrAnnotationChanges() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.PostMapping;
              import org.springframework.web.bind.annotation.RequestBody;
              import org.springframework.web.bind.annotation.RequestHeader;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @PostMapping("/orders")
                  public Order create(
                          @RequestParam(required = false) String queryFormOrPart,
                          @RequestHeader(required = false) String traceId,
                          @RequestParam(required = false) Integer numericBinding,
                          @RequestBody Order firstEntity,
                          @RequestBody(required = false) Order secondEntity,
                          @RequestParam @RequestBody String conflictingRoles) {
                      return firstEntity;
                  }
              }

              class Order {}
              """,
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.PostMapping;
              import org.springframework.web.bind.annotation.RequestBody;
              import org.springframework.web.bind.annotation.RequestHeader;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(%s)~~>*/@RestController
              public class OrderEndpoint {
                  @PostMapping("/orders")
                  public Order create(
                          @RequestParam(required = false) String queryFormOrPart,
                          @RequestHeader(required = false) String traceId,
                          @RequestParam(required = false) Integer numericBinding,
                          @RequestBody Order firstEntity,
                          @RequestBody(required = false) Order secondEntity,
                          @RequestParam @RequestBody String conflictingRoles) {
                      return firstEntity;
                  }
              }

              class Order {}
              """.formatted(MARKER)
          )
        );
    }

    @Test
    void marksEveryControllerWhileLeavingSecurityAndOtherModulesUntouched() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              class FirstEndpoint {
                  @GetMapping("/first")
                  public String first() {
                      return "first";
                  }
              }

              @RestController
              class SecondEndpoint {
                  @GetMapping("/second")
                  public String second() {
                      return "second";
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(%s)~~>*/@RestController
              class FirstEndpoint {
                  @GetMapping("/first")
                  public String first() {
                      return "first";
                  }
              }

              /*~~(%s)~~>*/@RestController
              class SecondEndpoint {
                  @GetMapping("/second")
                  public String second() {
                      return "second";
                  }
              }
              """.formatted(MARKER, MARKER),
            source -> source.path("module-a/src/main/java/com/example/orders/Endpoints.java")
          ),
          java(
            """
              package com.example.security;

              import org.springframework.security.web.SecurityFilterChain;

              class SecurityConfiguration {
                  SecurityFilterChain filterChain() {
                      return null;
                  }
              }
              """,
            source -> source.path("module-a/src/main/java/com/example/security/SecurityConfiguration.java")
          ),
          java(
            """
              package com.example.catalog;

              class CatalogService {
                  String catalog() {
                      return "catalog";
                  }
              }
              """,
            source -> source.path("module-b/src/main/java/com/example/catalog/CatalogService.java")
          )
        );
    }
}
