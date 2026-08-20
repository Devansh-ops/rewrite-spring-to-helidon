package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class MigrateSpringValueToConfigPropertyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringValueToConfigProperty())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-beans", "spring-context", "jakarta.enterprise.cdi-api",
                                "jakarta.inject-api", "microprofile-config-api", "spring-web")
                        .dependsOn(
                                """
                                  package org.springframework.security.web;
                                  public interface SecurityFilterChain {}
                                  """,
                                """
                                  package org.springframework.web.servlet.config.annotation;
                                  public interface WebMvcConfigurer {}
                                  """))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @Test
    void preservesBareScalarValueBecauseItsConversionContractIsNotPortable() {
        rewriteRun(
          java(
            """
              package com.example.config;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;

              @ApplicationScoped
              class ServiceConfig {
                  @Value("${service.name:catalog}")
                  String serviceName;
              }
              """,
            """
              package com.example.config;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;

              @ApplicationScoped
              class ServiceConfig {
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${service.name:catalog}")
                  String serviceName;
              }
              """
          )
        );
    }

    @DocumentExample
    @Test
    void preservesAndMarksTypedConfigurationIncludingAnEmptyDefault() {
        rewriteRun(
          java(
            """
              package com.acme.config;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;

              @ApplicationScoped
              class ServiceConfig {
                  @Value("${service.http.connect-timeout}")
                  int connectTimeout;

                  @Value(value = "${service.security.hsts.enabled:true}")
                  boolean hstsEnabled;

                  @Value("${service.ui.url:}")
                  String dataStudioUrl;
              }
              """,
            """
              package com.acme.config;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;

              @ApplicationScoped
              class ServiceConfig {
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${service.http.connect-timeout}")
                  int connectTimeout;

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value(value = "${service.security.hsts.enabled:true}")
                  boolean hstsEnabled;

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${service.ui.url:}")
                  String dataStudioUrl;
              }
              """
          )
        );
    }

    @Test
    void preservesAndMarksEveryFieldAndParameterInjectionPoint() {
        rewriteRun(
          java(
            """
              package com.example;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.enterprise.context.Dependent;
              import jakarta.enterprise.inject.Produces;
              import jakarta.inject.Inject;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.context.annotation.Bean;

              @ApplicationScoped
              class InjectionPoints {
                  @Inject
                  @Value("${existing.field}")
                  String existing;

                  @Produces
                  Object client(@Value("${client.url:https://localhost}") String url) {
                      return new Object();
                  }

                  @Value("${immutable.value}")
                  final String immutable = "";

                  @Value("${global.value}")
                  static String global;

                  void configure(@Value("${runtime.value}") String value) {
                  }
              }

              @ApplicationScoped
              class ConstructorInjection {
                  ConstructorInjection(@Value("${service.url}") String serviceUrl) {
                  }
              }

              @Dependent
              class DependentConstructorInjection {
                  DependentConstructorInjection(@Value("${dependent.url}") String serviceUrl) {
                  }
              }

              @ApplicationScoped
              class ProxyableConstructorInjection {
                  ProxyableConstructorInjection() {
                  }

                  ProxyableConstructorInjection(@Value("${proxyable.url}") String serviceUrl) {
                  }
              }

              @ApplicationScoped
              class AmbiguousInjection {
                  @Inject
                  AmbiguousInjection() {
                  }

                  AmbiguousInjection(@Value("${ambiguous.value}") String value) {
                  }
              }

              @ApplicationScoped
              class LegacyProducer {
                  @Bean(name = "client")
                  Object client(@Value("${legacy.url}") String url) {
                      return new Object();
                  }
              }
              """,
            """
              package com.example;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.enterprise.context.Dependent;
              import jakarta.enterprise.inject.Produces;
              import jakarta.inject.Inject;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.context.annotation.Bean;

              @ApplicationScoped
              class InjectionPoints {
                  @Inject
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${existing.field}")
                  String existing;

                  @Produces
                  Object client(/*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${client.url:https://localhost}") String url) {
                      return new Object();
                  }

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${immutable.value}")
                  final String immutable = "";

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${global.value}")
                  static String global;

                  void configure(/*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${runtime.value}") String value) {
                  }
              }

              @ApplicationScoped
              class ConstructorInjection {
                  ConstructorInjection(/*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${service.url}") String serviceUrl) {
                  }
              }

              @Dependent
              class DependentConstructorInjection {
                  DependentConstructorInjection(/*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${dependent.url}") String serviceUrl) {
                  }
              }

              @ApplicationScoped
              class ProxyableConstructorInjection {
                  ProxyableConstructorInjection() {
                  }

                  ProxyableConstructorInjection(/*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${proxyable.url}") String serviceUrl) {
                  }
              }

              @ApplicationScoped
              class AmbiguousInjection {
                  @Inject
                  AmbiguousInjection() {
                  }

                  AmbiguousInjection(/*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${ambiguous.value}") String value) {
                  }
              }

              @ApplicationScoped
              class LegacyProducer {
                  @Bean(name = "client")
                  Object client(/*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${legacy.url}") String url) {
                      return new Object();
                  }
              }
              """
          )
        );
    }

    @Test
    void marksSpelNestedPlaceholdersAndNonLiteralValuesForReview() {
        rewriteRun(
          java(
            """
              package com.example;

              import jakarta.inject.Singleton;
              import org.springframework.beans.factory.annotation.Value;

              class UnsupportedConfiguration {
                  static final String PROPERTY = "${service.url}";

                  @Value("#{systemProperties['user.home']}")
                  String home;

                  @Value("${service.url:${fallback.url}}")
                  String nested;

                  @Value(PROPERTY)
                  String constant;
              }

              @Singleton
              class PseudoScopedConfiguration {
                  @Value("${service.name}")
                  String serviceName;
              }
              """,
            """
              package com.example;

              import jakarta.inject.Singleton;
              import org.springframework.beans.factory.annotation.Value;

              class UnsupportedConfiguration {
                  static final String PROPERTY = "${service.url}";

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("#{systemProperties['user.home']}")
                  String home;

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${service.url:${fallback.url}}")
                  String nested;

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value(PROPERTY)
                  String constant;
              }

              @Singleton
              class PseudoScopedConfiguration {
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${service.name}")
                  String serviceName;
              }
              """
          )
        );
    }

    @Test
    void preservesAndMarksEveryConversionDomain() {
        rewriteRun(
          java(
            """
              package com.example.config;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;

              import java.time.Duration;
              import java.util.List;

              @ApplicationScoped
              class RuntimeConfig {
                  @Value("${service.name:catalog}")
                  String serviceName;

                  @Value("${retry.count:3}")
                  int retryCount;

                  @Value("${request.timeout:30s}")
                  Duration requestTimeout;

                  @Value("${service.tags:catalog,api}")
                  List<String> tags;
              }
              """,
            """
              package com.example.config;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;

              import java.time.Duration;
              import java.util.List;

              @ApplicationScoped
              class RuntimeConfig {
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${service.name:catalog}")
                  String serviceName;

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${retry.count:3}")
                  int retryCount;

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${request.timeout:30s}")
                  Duration requestTimeout;

                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${service.tags:catalog,api}")
                  List<String> tags;
              }
              """
          )
        );
    }

    @Test
    void preservesValueInsideASpringController() {
        rewriteRun(
          java(
            """
              package com.example.web;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class SearchController {
                  @Value("${search.limit:10}")
                  int searchLimit;

                  @GetMapping("/search")
                  public String search(String query) {
                      return query;
                  }
              }
              """,
            """
              package com.example.web;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class SearchController {
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${search.limit:10}")
                  int searchLimit;

                  @GetMapping("/search")
                  public String search(String query) {
                      return query;
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesControllerValuesRegardlessOfOtherModuleSemantics() {
        rewriteRun(
          java(
            """
              package com.example.billing;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class BillingController {
                  @Value("${billing.limit:10}")
                  int limit;

                  @GetMapping("/billing")
                  public String billing() {
                      return "billing";
                  }
              }
              """,
            """
              package com.example.billing;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class BillingController {
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${billing.limit:10}")
                  int limit;

                  @GetMapping("/billing")
                  public String billing() {
                      return "billing";
                  }
              }
              """,
            source -> source.path("billing/src/main/java/com/example/billing/BillingController.java")
          ),
          java(
            """
              package com.example.billing;

              import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

              class BillingWebConfiguration implements WebMvcConfigurer {}
              """,
            source -> source.path("billing/src/main/java/com/example/billing/BillingWebConfiguration.java")
          ),
          java(
            """
              package com.example.profile;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class ProfileController {
                  @Value("${profile.limit:10}")
                  int limit;

                  @GetMapping("/profile")
                  public String profile() {
                      return "profile";
                  }
              }
              """,
            """
              package com.example.profile;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class ProfileController {
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${profile.limit:10}")
                  int limit;

                  @GetMapping("/profile")
                  public String profile() {
                      return "profile";
                  }
              }
              """,
            source -> source.path("profile/src/main/java/com/example/profile/ProfileController.java")
          ),
          text(
            """
              <project>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework.security</groupId>
                          <artifactId>spring-security-core</artifactId>
                      </dependency>
                  </dependencies>
              </project>
              """,
            source -> source.path("profile/pom.xml")
          ),
          java(
            """
              package com.example.inventory;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class InventoryController {
                  @Value("${inventory.limit:10}")
                  int limit;

                  @GetMapping("/inventory")
                  public String inventory() {
                      return "inventory";
                  }
              }
              """,
            """
              package com.example.inventory;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class InventoryController {
                  /*~~(Manual migration: bare Spring @Value injection has no behavior-preserving direct MicroProfile Config mapping)~~>*/@Value("${inventory.limit:10}")
                  int limit;

                  @GetMapping("/inventory")
                  public String inventory() {
                      return "inventory";
                  }
              }
              """,
            source -> source.path("inventory/src/main/java/com/example/inventory/InventoryController.java")
          ),
          java(
            """
              package com.example.inventory;

              import org.springframework.web.bind.annotation.ControllerAdvice;

              @ControllerAdvice
              class TestAdvice {}
              """,
            source -> source.path("inventory/src/test/java/com/example/inventory/TestAdvice.java")
          ),
          java(
            """
              package com.example.inventory;

              import org.springframework.security.web.SecurityFilterChain;

              class IntegrationSecurity {
                  SecurityFilterChain filterChain;
              }
              """,
            source -> source.path("inventory/src/it/java/com/example/inventory/IntegrationSecurity.java")
          )
        );
    }
}
