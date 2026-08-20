package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class MigrateSpringBootMainTest implements RewriteTest {
    private static final String APPLICATION_REVIEW =
            "Manual migration: preserve @SpringBootApplication until Helidon bootstrap " +
            "dependencies, configuration, and lifecycle semantics are reviewed";
    private static final String RUN_REVIEW =
            "Manual migration: preserve SpringApplication.run until Helidon bootstrap " +
            "dependencies, configuration, and lifecycle semantics are reviewed";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringBootMain())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-boot", "spring-boot-autoconfigure"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void preservesAndMarksAPlainLauncher() {
        rewriteRun(
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              public class CatalogApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(%s)~~>*/@SpringBootApplication
              public class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(%s)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """.formatted(APPLICATION_REVIEW, RUN_REVIEW)
          )
        );
    }

    @Test
    void preservesOptionsAndAdditionalBootstrapCode() {
        rewriteRun(
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication(
                      excludeName = "com.example.catalog.LegacyAutoConfiguration")
              class CatalogApplication {
                  public static void main(String[] args) {
                      System.setProperty("service.mode", "server");
                      SpringApplication.run(CatalogApplication.class, args);
                      System.out.println("started");
                  }
              }
              """,
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(%s)~~>*/@SpringBootApplication(
                      excludeName = "com.example.catalog.LegacyAutoConfiguration")
              class CatalogApplication {
                  public static void main(String[] args) {
                      System.setProperty("service.mode", "server");
                      /*~~(%s)~~>*/SpringApplication.run(CatalogApplication.class, args);
                      System.out.println("started");
                  }
              }
              """.formatted(APPLICATION_REVIEW, RUN_REVIEW)
          )
        );
    }

    @Test
    void marksEveryFullyQualifiedBootstrapUse() {
        rewriteRun(
          java(
            """
              package com.example.catalog;

              @org.springframework.boot.autoconfigure.SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      org.springframework.boot.SpringApplication.run(CatalogApplication.class, args);
                      org.springframework.boot.SpringApplication.run(OtherConfiguration.class, args);
                  }
              }

              class OtherConfiguration {
              }
              """,
            """
              package com.example.catalog;

              /*~~(%s)~~>*/@org.springframework.boot.autoconfigure.SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(%s)~~>*/org.springframework.boot.SpringApplication.run(CatalogApplication.class, args);
                      /*~~(%s)~~>*/org.springframework.boot.SpringApplication.run(OtherConfiguration.class, args);
                  }
              }

              class OtherConfiguration {
              }
              """.formatted(APPLICATION_REVIEW, RUN_REVIEW, RUN_REVIEW)
          )
        );
    }

    @Test
    void preservesAndMarksLauncherWhenBuildAndResourcesNeedReview() {
        rewriteRun(
          text(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>spring-boot-starter-security</artifactId>
                      </dependency>
                  </dependencies>
              </project>
              """,
            spec -> spec.path("pom.xml")
          ),
          text(
            """
              spring:
                main:
                  lazy-initialization: true
              """,
            spec -> spec.path("src/main/resources/application.yml")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(%s)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(%s)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """.formatted(APPLICATION_REVIEW, RUN_REVIEW),
            spec -> spec.path("src/main/java/com/example/catalog/CatalogApplication.java")
          )
        );
    }

    @Test
    void marksRunCallsOutsideSpringBootApplicationClasses() {
        rewriteRun(
          java(
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;

              class AlternateLauncher {
                  Object start(String[] args) {
                      return SpringApplication.run(AlternateLauncher.class, args);
                  }
              }
              """,
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;

              class AlternateLauncher {
                  Object start(String[] args) {
                      return /*~~(%s)~~>*/SpringApplication.run(AlternateLauncher.class, args);
                  }
              }
              """.formatted(RUN_REVIEW)
          )
        );
    }
}
