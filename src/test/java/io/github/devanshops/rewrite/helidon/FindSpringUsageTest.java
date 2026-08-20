package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.SpringUsageTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.Recipe;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;

class FindSpringUsageTest implements RewriteTest {

    private static final class RepeatingFindSpringUsage extends Recipe {
        @Override
        public String getDisplayName() {
            return "Repeat the Spring usage inventory for idempotence testing";
        }

        @Override
        public String getDescription() {
            return "Forces a follow-up cycle around the Spring usage inventory.";
        }

        @Override
        public List<Recipe> getRecipeList() {
            return List.of(new FindSpringUsage());
        }

        @Override
        public boolean causesAnotherCycle() {
            return true;
        }
    }

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindSpringUsage())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-beans", "spring-context", "spring-web", "spring-tx", "spring-boot")
                        .dependsOn(
                                """
                                  package org.springframework.data.repository;
                                  public interface Repository<T, ID> {}
                                  """,
                                """
                                  package org.springframework.security.config.annotation.web.configuration;
                                  public @interface EnableWebSecurity {}
                                  """,
                                """
                                  package org.springframework.kafka.core;
                                  public class KafkaTemplate<K, V> {}
                                  """,
                                """
                                  package org.springframework.boot.actuate.health;
                                  public interface HealthIndicator {}
                                  """,
                                """
                                  package org.springframework.boot.autoconfigure;
                                  public @interface AutoConfiguration {}
                                  """,
                                """
                                  package org.springframework.web.reactive.function.client;
                                  public final class WebClient {}
                                  """,
                                """
                                  package org.springframework.cloud.client.discovery;
                                  public @interface EnableDiscoveryClient {}
                                  """,
                                """
                                  package org.springframework.batch.core;
                                  public interface Job {}
                                  """,
                                """
                                  package org.springframework.integration.annotation;
                                  public @interface IntegrationComponentScan {}
                                  """,
                                """
                                  package org.springframework.aop;
                                  public interface Advisor {}
                                  """));
    }

    @DocumentExample
    @Test
    void classifiesBoundedAndManualTypesAndDeduplicatesRows() {
        rewriteRun(
          spec -> spec.dataTable(SpringUsageTable.Row.class, rows -> {
              assertThat(rows).hasSize(4);
              assertThat(rows).allMatch(row ->
                      "src/main/java/com/acme/OrderEndpoint.java".equals(row.getSourcePath()));
              assertThat(rows)
                      .extracting(SpringUsageTable.Row::getSpringType,
                              SpringUsageTable.Row::getFeature,
                              SpringUsageTable.Row::getSupportLevel,
                              SpringUsageTable.Row::getSuggestedReplacement)
                      .containsExactlyInAnyOrder(
                              tuple("org.springframework.beans.factory.annotation.Autowired",
                                      "Dependency injection", "PARTIAL", "Jakarta CDI and jakarta.inject"),
                              tuple("org.springframework.beans.factory.annotation.Value",
                                      "Externalized configuration", "MANUAL",
                                      "Explicit MicroProfile Config contract or compatibility adapter"),
                              tuple("org.springframework.transaction.annotation.Transactional",
                                      "Transactions", "PARTIAL", "jakarta.transaction.Transactional"),
                              tuple("org.springframework.web.bind.annotation.GetMapping",
                                      "Spring MVC", "MANUAL", "Jakarta REST annotations"));
          }),
          java(
            """
              package com.acme;

              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;

              class OrderEndpoint {
                  @Autowired
                  Object firstDependency;

                  @Autowired
                  Object secondDependency;

                  @Value("${orders.region}")
                  String region;

                  @GetMapping("/orders")
                  @Transactional
                  String orders() {
                      return region;
                  }
              }
              """,
            """
              package com.acme;

              /*~~(PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject)~~>*/import org.springframework.beans.factory.annotation.Autowired;
              /*~~(MANUAL: Externalized configuration -> Explicit MicroProfile Config contract or compatibility adapter)~~>*/import org.springframework.beans.factory.annotation.Value;
              /*~~(PARTIAL: Transactions -> jakarta.transaction.Transactional)~~>*/import org.springframework.transaction.annotation.Transactional;
              /*~~(MANUAL: Spring MVC -> Jakarta REST annotations)~~>*/import org.springframework.web.bind.annotation.GetMapping;

              class OrderEndpoint {
                  @/*~~(PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject)~~>*/Autowired
                  Object firstDependency;

                  @/*~~(PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject)~~>*/Autowired
                  Object secondDependency;

                  @/*~~(MANUAL: Externalized configuration -> Explicit MicroProfile Config contract or compatibility adapter)~~>*/Value("${orders.region}")
                  String region;

                  @/*~~(MANUAL: Spring MVC -> Jakarta REST annotations)~~>*/GetMapping("/orders")
                  @/*~~(PARTIAL: Transactions -> jakarta.transaction.Transactional)~~>*/Transactional
                  String orders() {
                      return region;
                  }
              }
              """,
            source -> source.path("src/main/java/com/acme/OrderEndpoint.java"))
        );
    }

    @Test
    void classifiesPartialAndManualBoundaries() {
        rewriteRun(
          spec -> spec.dataTable(SpringUsageTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(SpringUsageTable.Row::getSpringType,
                                  SpringUsageTable.Row::getFeature,
                                  SpringUsageTable.Row::getSupportLevel)
                          .containsExactlyInAnyOrder(
                                  tuple("org.springframework.boot.actuate.health.HealthIndicator",
                                          "Spring Boot Actuator", "MANUAL"),
                                  tuple("org.springframework.cache.annotation.Cacheable",
                                          "Caching", "MANUAL"),
                                  tuple("org.springframework.data.repository.Repository",
                                          "Spring Data", "MANUAL"),
                                  tuple("org.springframework.http.ResponseEntity",
                                          "Spring MVC response type", "MANUAL"),
                                  tuple("org.springframework.scheduling.annotation.Scheduled",
                                          "Scheduling", "MANUAL"),
                                  tuple("org.springframework.aop.Advisor",
                                          "Spring AOP", "MANUAL"),
                                  tuple("org.springframework.batch.core.Job",
                                          "Spring Batch", "MANUAL"),
                                  tuple("org.springframework.boot.autoconfigure.AutoConfiguration",
                                          "Spring Boot auto-configuration", "MANUAL"),
                                  tuple("org.springframework.cloud.client.discovery.EnableDiscoveryClient",
                                          "Spring Cloud", "MANUAL"),
                                  tuple("org.springframework.context.ApplicationContext",
                                          "Spring ApplicationContext", "MANUAL"),
                                  tuple("org.springframework.integration.annotation.IntegrationComponentScan",
                                          "Spring Integration", "MANUAL"),
                                  tuple("org.springframework.kafka.core.KafkaTemplate",
                                          "Spring Kafka", "MANUAL"),
                                  tuple("org.springframework.security.config.annotation.web.configuration.EnableWebSecurity",
                                          "Spring Security", "MANUAL"),
                                  tuple("org.springframework.stereotype.Controller",
                                          "Spring MVC view controller", "MANUAL"),
                                  tuple("org.springframework.web.reactive.function.client.WebClient",
                                          "Spring WebFlux", "MANUAL"))),
          java(
            """
              package com.acme;

              import org.springframework.aop.Advisor;
              import org.springframework.batch.core.Job;
              import org.springframework.boot.actuate.health.HealthIndicator;
              import org.springframework.boot.autoconfigure.AutoConfiguration;
              import org.springframework.cache.annotation.Cacheable;
              import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
              import org.springframework.context.ApplicationContext;
              import org.springframework.data.repository.Repository;
              import org.springframework.http.ResponseEntity;
              import org.springframework.integration.annotation.IntegrationComponentScan;
              import org.springframework.kafka.core.KafkaTemplate;
              import org.springframework.scheduling.annotation.Scheduled;
              import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
              import org.springframework.stereotype.Controller;
              import org.springframework.web.reactive.function.client.WebClient;

              class MigrationBoundary {}
              """,
            """
              package com.acme;

              /*~~(MANUAL: Spring AOP -> CDI interceptors or decorators)~~>*/import org.springframework.aop.Advisor;
              /*~~(MANUAL: Spring Batch -> Jakarta Batch or an application-specific batch runtime)~~>*/import org.springframework.batch.core.Job;
              /*~~(MANUAL: Spring Boot Actuator -> MicroProfile Health and Metrics)~~>*/import org.springframework.boot.actuate.health.HealthIndicator;
              /*~~(MANUAL: Spring Boot auto-configuration -> Explicit CDI producers or a CDI portable extension)~~>*/import org.springframework.boot.autoconfigure.AutoConfiguration;
              /*~~(MANUAL: Caching -> Application-specific cache with CDI integration)~~>*/import org.springframework.cache.annotation.Cacheable;
              /*~~(MANUAL: Spring Cloud -> Component-specific MicroProfile or Helidon replacement)~~>*/import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
              /*~~(MANUAL: Spring ApplicationContext -> CDI Instance, BeanManager, or injection)~~>*/import org.springframework.context.ApplicationContext;
              /*~~(MANUAL: Spring Data -> Jakarta Persistence repository or DAO)~~>*/import org.springframework.data.repository.Repository;
              /*~~(MANUAL: Spring MVC response type -> jakarta.ws.rs.core.Response)~~>*/import org.springframework.http.ResponseEntity;
              /*~~(MANUAL: Spring Integration -> Redesign integration flows for Helidon-compatible messaging)~~>*/import org.springframework.integration.annotation.IntegrationComponentScan;
              /*~~(MANUAL: Spring Kafka -> Helidon-compatible Kafka client or messaging integration)~~>*/import org.springframework.kafka.core.KafkaTemplate;
              /*~~(MANUAL: Scheduling -> Helidon scheduling or Jakarta Concurrency)~~>*/import org.springframework.scheduling.annotation.Scheduled;
              /*~~(MANUAL: Spring Security -> Helidon Security or Jakarta Security)~~>*/import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
              /*~~(MANUAL: Spring MVC view controller -> Redesign as Jakarta REST or retain a dedicated view layer)~~>*/import org.springframework.stereotype.Controller;
              /*~~(MANUAL: Spring WebFlux -> Redesign for Jakarta REST or Helidon SE)~~>*/import org.springframework.web.reactive.function.client.WebClient;

              class MigrationBoundary {}
              """,
            source -> source.path("src/main/java/com/acme/MigrationBoundary.java"))
        );
    }

    @Test
    void classifiesRepositoryStereotypeAsManual() {
        rewriteRun(
          spec -> spec.dataTable(SpringUsageTable.Row.class, rows -> {
              assertThat(rows).hasSize(1);
              assertThat(rows.get(0).getSpringType())
                      .isEqualTo("org.springframework.stereotype.Repository");
              assertThat(rows.get(0).getFeature()).isEqualTo("Spring repository stereotype");
              assertThat(rows.get(0).getSupportLevel()).isEqualTo("MANUAL");
          }),
          java(
            """
              package com.acme;

              import org.springframework.stereotype.Repository;

              @Repository
              class CatalogStore {}
              """,
            """
              package com.acme;

              /*~~(MANUAL: Spring repository stereotype -> Explicit CDI bean with reviewed persistence exception mapping)~~>*/import org.springframework.stereotype.Repository;

              @/*~~(MANUAL: Spring repository stereotype -> Explicit CDI bean with reviewed persistence exception mapping)~~>*/Repository
              class CatalogStore {}
              """,
            source -> source.path("src/main/java/com/acme/CatalogStore.java"))
        );
    }

    @Test
    void findsFullyQualifiedUsageWithoutAnImport() {
        rewriteRun(
          spec -> spec.dataTable(SpringUsageTable.Row.class, rows -> {
              assertThat(rows).hasSize(1);
              assertThat(rows.get(0).getSpringType())
                      .isEqualTo("org.springframework.context.ApplicationContext");
              assertThat(rows.get(0).getSupportLevel()).isEqualTo("MANUAL");
          }),
          java(
            """
              package com.acme;

              class DirectSpringUsage {
                  private org.springframework.context.ApplicationContext context;
              }
              """,
            """
              package com.acme;

              class DirectSpringUsage {
                  private /*~~(MANUAL: Spring ApplicationContext -> CDI Instance, BeanManager, or injection)~~>*/org.springframework.context.ApplicationContext /*~~(MANUAL: Spring ApplicationContext -> CDI Instance, BeanManager, or injection)~~>*/context;
              }
              """,
            source -> source.path("src/main/java/com/acme/DirectSpringUsage.java"))
        );
    }

    @Test
    void doesNotRecreateEquivalentSearchMarkersOnAFollowUpCycle() {
        rewriteRun(
          spec -> spec.recipe(new RepeatingFindSpringUsage())
                  .cycles(2)
                  .expectedCyclesThatMakeChanges(1),
          java(
            """
              package com.acme;

              import org.springframework.context.ApplicationContext;

              class ContextHolder {
                  ApplicationContext context;
              }
              """,
            """
              package com.acme;

              /*~~(MANUAL: Spring ApplicationContext -> CDI Instance, BeanManager, or injection)~~>*/import org.springframework.context.ApplicationContext;

              class ContextHolder {
                  /*~~(MANUAL: Spring ApplicationContext -> CDI Instance, BeanManager, or injection)~~>*/ApplicationContext /*~~(MANUAL: Spring ApplicationContext -> CDI Instance, BeanManager, or injection)~~>*/context;
              }
              """
          )
        );
    }
}
