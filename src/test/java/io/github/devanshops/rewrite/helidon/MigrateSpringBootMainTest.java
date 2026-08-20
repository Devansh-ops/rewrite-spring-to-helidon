package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class MigrateSpringBootMainTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringBootMain())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-boot", "spring-context", "helidon")
                        .dependsOn(
                                """
                                  package org.springframework.boot.autoconfigure;
                                  public @interface SpringBootApplication {
                                      Class<?>[] exclude() default {};
                                      String[] excludeName() default {};
                                  }
                                  """,
                                """
                                  package com.example.meta;

                                  import org.springframework.context.annotation.ComponentScan;

                                  import java.lang.annotation.ElementType;
                                  import java.lang.annotation.Retention;
                                  import java.lang.annotation.RetentionPolicy;
                                  import java.lang.annotation.Target;

                                  @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
                                  @Retention(RetentionPolicy.RUNTIME)
                                  @ComponentScan("com.example.components")
                                  public @interface EnableCatalogComponents {
                                  }
                                  """,
                                """
                                  package com.example.meta;

                                  import java.lang.annotation.ElementType;
                                  import java.lang.annotation.Retention;
                                  import java.lang.annotation.RetentionPolicy;
                                  import java.lang.annotation.Target;

                                  @Target(ElementType.TYPE)
                                  @Retention(RetentionPolicy.RUNTIME)
                                  @EnableCatalogComponents
                                  public @interface CatalogApplicationConfiguration {
                                  }
                                  """,
                                """
                                  package org.springframework.security.web;

                                  public interface SecurityFilterChain {
                                  }
                                  """,
                                """
                                  package org.springframework.security.config.annotation.web.configuration;

                                  public @interface EnableWebSecurity {
                                  }
                                  """,
                                """
                                  package org.springframework.data.repository;

                                  public interface Repository<T, ID> {
                                  }
                                  """))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void delegatesAnOptionFreeBootApplicationToHelidon() {
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

              public class CatalogApplication {
                  public static void main(String[] args) {
                      io.helidon.Main.main(args);
                  }
              }
              """
          )
        );
    }

    @Test
    void marksBootExclusionsAndCapturedApplicationLifecycleForReview() {
        rewriteRun(
          java(
            """
              package com.acme.platform;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;
              import org.springframework.context.ConfigurableApplicationContext;

              @SpringBootApplication(
                      excludeName = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
              class ConfiguredApplication {
                  static ConfigurableApplicationContext start(String[] args) {
                      return SpringApplication.run(ConfiguredApplication.class, args);
                  }
              }
              """,
            """
              package com.acme.platform;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;
              import org.springframework.context.ConfigurableApplicationContext;

              /*~~(Manual migration: Spring Boot application options require Helidon dependency/config review)~~>*/@SpringBootApplication(
                      excludeName = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
              class ConfiguredApplication {
                  static ConfigurableApplicationContext start(String[] args) {
                      return /*~~(Manual migration: Spring Boot application options require Helidon dependency/config review)~~>*/SpringApplication.run(ConfiguredApplication.class, args);
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesTheWholeLauncherWhenRunShapeIsUnsupported() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;
              import org.springframework.context.ConfigurableApplicationContext;

              @SpringBootApplication
              class CapturedApplication {
                  static ConfigurableApplicationContext start(String[] args) {
                      return SpringApplication.run(CapturedApplication.class, args);
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;
              import org.springframework.context.ConfigurableApplicationContext;

              /*~~(Manual migration: SpringApplication.run must be a standalone call in public static void main with a String[] argument)~~>*/@SpringBootApplication
              class CapturedApplication {
                  static ConfigurableApplicationContext start(String[] args) {
                      return /*~~(Manual migration: SpringApplication.run must be a standalone call in public static void main with a String[] argument)~~>*/SpringApplication.run(CapturedApplication.class, args);
                  }
              }
              """
          ),
          java(
            """
              package com.example;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class LiteralArgumentsApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(LiteralArgumentsApplication.class, "--profile=test");
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(Manual migration: SpringApplication.run must be a standalone call in public static void main with a String[] argument)~~>*/@SpringBootApplication
              class LiteralArgumentsApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: SpringApplication.run must be a standalone call in public static void main with a String[] argument)~~>*/SpringApplication.run(LiteralArgumentsApplication.class, "--profile=test");
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesServletInitializerLauncherAtomically() {
        rewriteRun(
          java(
            """
              package com.acme.platform;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;
              import org.springframework.boot.builder.SpringApplicationBuilder;
              import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

              @SpringBootApplication
              class ServletApplication extends SpringBootServletInitializer {
                  @Override
                  protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
                      return builder.sources(ServletApplication.class);
                  }

                  public static void main(String[] args) {
                      SpringApplication.run(ServletApplication.class, args);
                  }
              }
              """,
            """
              package com.acme.platform;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;
              import org.springframework.boot.builder.SpringApplicationBuilder;
              import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

              /*~~(Manual migration: SpringApplicationBuilder or servlet initializer lifecycle is in use)~~>*/@SpringBootApplication
              class ServletApplication extends SpringBootServletInitializer {
                  @Override
                  protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
                      return builder.sources(ServletApplication.class);
                  }

                  public static void main(String[] args) {
                      /*~~(Manual migration: SpringApplicationBuilder or servlet initializer lifecycle is in use)~~>*/SpringApplication.run(ServletApplication.class, args);
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesAPrimarySourceOtherThanTheEnclosingBootApplication() {
        rewriteRun(
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(CatalogConfiguration.class, args);
                  }
              }

              class CatalogConfiguration {
              }
              """,
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(Manual migration: SpringApplication.run must use the enclosing @SpringBootApplication class as its primary source)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: SpringApplication.run must use the enclosing @SpringBootApplication class as its primary source)~~>*/SpringApplication.run(CatalogConfiguration.class, args);
                  }
              }

              class CatalogConfiguration {
              }
              """
          )
        );
    }

    @Test
    void refusesPrimarySourceArraysAndMultipleRunCallsAtomically() {
        rewriteRun(
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class ArrayApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(new Class<?>[]{ArrayApplication.class}, args);
                  }
              }
              """,
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(Manual migration: SpringApplication.run must use the enclosing @SpringBootApplication class as its primary source)~~>*/@SpringBootApplication
              class ArrayApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: SpringApplication.run must use the enclosing @SpringBootApplication class as its primary source)~~>*/SpringApplication.run(new Class<?>[]{ArrayApplication.class}, args);
                  }
              }
              """
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class MultipleApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(MultipleApplication.class, args);
                      SpringApplication.run(MultipleApplication.class, args);
                  }
              }
              """,
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(Manual migration: exactly one SpringApplication.run bootstrap is required)~~>*/@SpringBootApplication
              class MultipleApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: exactly one SpringApplication.run bootstrap is required)~~>*/SpringApplication.run(MultipleApplication.class, args);
                      /*~~(Manual migration: exactly one SpringApplication.run bootstrap is required)~~>*/SpringApplication.run(MultipleApplication.class, args);
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesLaunchersWithAdditionalLifecycleOrConfigurationSemantics() {
        rewriteRun(
          java(
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class SetupApplication {
                  public static void main(String[] args) {
                      System.setProperty("service.mode", "server");
                      SpringApplication.run(SetupApplication.class, args);
                  }
              }
              """,
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(Manual migration: SpringApplication.run must be the only statement in public static void main)~~>*/@SpringBootApplication
              class SetupApplication {
                  public static void main(String[] args) {
                      System.setProperty("service.mode", "server");
                      /*~~(Manual migration: SpringApplication.run must be the only statement in public static void main)~~>*/SpringApplication.run(SetupApplication.class, args);
                  }
              }
              """
          ),
          java(
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class HierarchyApplication extends BaseLauncher implements LauncherContract {
                  public static void main(String[] args) {
                      SpringApplication.run(HierarchyApplication.class, args);
                  }
              }

              class BaseLauncher {
              }

              interface LauncherContract {
              }
              """,
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(Manual migration: application superclass or interfaces require lifecycle review)~~>*/@SpringBootApplication
              class HierarchyApplication extends BaseLauncher implements LauncherContract {
                  public static void main(String[] args) {
                      /*~~(Manual migration: application superclass or interfaces require lifecycle review)~~>*/SpringApplication.run(HierarchyApplication.class, args);
                  }
              }

              class BaseLauncher {
              }

              interface LauncherContract {
              }
              """
          ),
          java(
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;
              import org.springframework.context.annotation.ComponentScan;

              @ComponentScan("com.example.components")
              @SpringBootApplication
              class ScanningApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(ScanningApplication.class, args);
                  }
              }
              """,
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;
              import org.springframework.context.annotation.ComponentScan;

              @ComponentScan("com.example.components")
              /*~~(Manual migration: additional Spring annotations on the application launcher require review)~~>*/@SpringBootApplication
              class ScanningApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: additional Spring annotations on the application launcher require review)~~>*/SpringApplication.run(ScanningApplication.class, args);
                  }
              }
              """
          ),
          java(
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @SpringBootApplication
              class StatefulApplication {
                  static final String MODE = "server";

                  public static void main(String[] args) {
                      SpringApplication.run(StatefulApplication.class, args);
                  }
              }
              """,
            """
              package com.example.bootstrap;

              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              /*~~(Manual migration: application launcher must contain only one plain main method)~~>*/@SpringBootApplication
              class StatefulApplication {
                  static final String MODE = "server";

                  public static void main(String[] args) {
                      /*~~(Manual migration: application launcher must contain only one plain main method)~~>*/SpringApplication.run(StatefulApplication.class, args);
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesNestedComposedSpringAnnotationsAtomically() {
        rewriteRun(
          java(
            """
              package com.example.catalog;

              import com.example.meta.CatalogApplicationConfiguration;
              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @CatalogApplicationConfiguration
              @SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            """
              package com.example.catalog;

              import com.example.meta.CatalogApplicationConfiguration;
              import org.springframework.boot.SpringApplication;
              import org.springframework.boot.autoconfigure.SpringBootApplication;

              @CatalogApplicationConfiguration
              /*~~(Manual migration: additional Spring annotations on the application launcher require review)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: additional Spring annotations on the application launcher require review)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesLauncherWhenAnotherSourceDefinesGlobalSpringSecurity() {
        rewriteRun(
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

              /*~~(Manual migration: Spring Security is present in this migration scope; preserve global filter and authorization semantics before changing runtimes)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: Spring Security is present in this migration scope; preserve global filter and authorization semantics before changing runtimes)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/catalog/CatalogApplication.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
              import org.springframework.security.web.SecurityFilterChain;

              @Configuration
              @EnableWebSecurity
              class SecurityConfiguration {
                  @Bean
                  SecurityFilterChain securityFilterChain() {
                      return null;
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/catalog/SecurityConfiguration.java")
          )
        );
    }

    @Test
    void refusesLauncherWhenTheBuildDeclaresSpringSecurity() {
        rewriteRun(
          text(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>catalog-service</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>spring-boot-starter-security</artifactId>
                          <version>4.1.0</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            spec -> spec.path("pom.xml")
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

              /*~~(Manual migration: Spring Security is present in this migration scope; preserve global filter and authorization semantics before changing runtimes)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: Spring Security is present in this migration scope; preserve global filter and authorization semantics before changing runtimes)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/catalog/CatalogApplication.java")
          )
        );
    }

    @Test
    void scopesSpringSecurityToTheLaunchersModule() {
        rewriteRun(
          java(
            """
              package com.example.identity;

              import org.springframework.security.web.SecurityFilterChain;

              class IdentitySecurity {
                  SecurityFilterChain chain;
              }
              """,
            spec -> spec.path("identity-service/src/main/java/com/example/identity/IdentitySecurity.java")
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

              class CatalogApplication {
                  public static void main(String[] args) {
                      io.helidon.Main.main(args);
                  }
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogApplication.java")
          )
        );
    }

    @Test
    void refusesLauncherWhenProductionSpringRuntimeUsageRemainsInTheSameModule() {
        rewriteRun(
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

              /*~~(Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior before changing runtimes)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior before changing runtimes)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogApplication.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.data.repository.Repository;

              interface CatalogRepository extends Repository<String, Long> {
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogRepository.java")
          )
        );
    }

    @Test
    void followsComposedSpringRuntimeAnnotationsInTheSameModule() {
        rewriteRun(
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

              /*~~(Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior before changing runtimes)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior before changing runtimes)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogApplication.java")
          ),
          java(
            """
              package com.example.catalog;

              import com.example.meta.CatalogApplicationConfiguration;

              @CatalogApplicationConfiguration
              class CatalogRuntimeConfiguration {
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogRuntimeConfiguration.java")
          )
        );
    }

    @Test
    void scopesRuntimeResidueToProductionSourcesInTheLaunchersModule() {
        rewriteRun(
          java(
            """
              package com.example.catalog;

              import org.springframework.data.repository.Repository;

              interface LegacyRepository extends Repository<String, Long> {
              }
              """,
            spec -> spec.path("legacy-service/src/main/java/com/example/catalog/LegacyRepository.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.context.ApplicationContext;

              class CatalogApplicationTest {
                  ApplicationContext context;
              }
              """,
            spec -> spec.path("catalog-service/src/test/java/com/example/catalog/CatalogApplicationTest.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;

              class CatalogApplicationIT {
                  Class<?> bootType = SpringApplication.class;
              }
              """,
            spec -> spec.path("catalog-service/src/it/java/com/example/catalog/CatalogApplicationIT.java")
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

              class CatalogApplication {
                  public static void main(String[] args) {
                      io.helidon.Main.main(args);
                  }
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogApplication.java")
          )
        );
    }

    @Test
    void refusesFullyQualifiedSpringRuntimeNamesInProductionSource() {
        rewriteRun(
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

              /*~~(Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior before changing runtimes)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior before changing runtimes)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogApplication.java")
          ),
          java(
            """
              package com.example.catalog;

              class RuntimeLookup {
                  static final String TYPE = "org.springframework.context.ApplicationContext";
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/RuntimeLookup.java")
          )
        );
    }

    @Test
    void refusesLauncherWhenAnotherProductionSourceUsesSpringBootRuntimePrimitives() {
        rewriteRun(
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

              /*~~(Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior before changing runtimes)~~>*/@SpringBootApplication
              class CatalogApplication {
                  public static void main(String[] args) {
                      /*~~(Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior before changing runtimes)~~>*/SpringApplication.run(CatalogApplication.class, args);
                  }
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogApplication.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;

              class SecondaryBootstrap {
                  static void start(String[] args) {
                      SpringApplication.run(SecondaryBootstrap.class, args);
                  }
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/SecondaryBootstrap.java")
          )
        );
    }

    @Test
    void allowsBootPrimitiveUsageInTestsAndOtherModules() {
        rewriteRun(
          java(
            """
              package com.example.identity;

              import org.springframework.boot.SpringApplication;

              class IdentityBootstrap {
                  static void start(String[] args) {
                      SpringApplication.run(IdentityBootstrap.class, args);
                  }
              }
              """,
            spec -> spec.path("identity-service/src/main/java/com/example/identity/IdentityBootstrap.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.boot.SpringApplication;

              class CatalogApplicationTest {
                  Class<?> bootType = SpringApplication.class;
              }
              """,
            spec -> spec.path("catalog-service/src/test/java/com/example/catalog/CatalogApplicationTest.java")
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

              class CatalogApplication {
                  public static void main(String[] args) {
                      io.helidon.Main.main(args);
                  }
              }
              """,
            spec -> spec.path("catalog-service/src/main/java/com/example/catalog/CatalogApplication.java")
          )
        );
    }
}
