package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.MigrationAssessmentTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;

class MigrateSpringTransactionalToJakartaIncludingSupportsTest
        extends MigrateSpringTransactionalToJakartaTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringTransactionalToJakartaIncludingSupports())
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-tx", "jakarta.enterprise.cdi-api", "jakarta.transaction-api",
                        "jakarta.annotation-api", "jakarta.inject-api", "spring-context",
                        "spring-beans", "spring-core"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @Override
    void baseRecipeRefusesSupportsWithItsPolicyCode() {
        // SUPPORTS is the sole intentional delta in this opt-in contract.
    }

    @DocumentExample
    @Test
    void mapsSupportsInTheSameAtomicPlanAsRequired() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("MIGRATED", "TX_MIGRATED_REQUIRED"),
                                  tuple("MIGRATED", "TX_MIGRATED_SUPPORTS"))),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              class OrderService {
                  @Transactional(propagation = Propagation.SUPPORTS)
                  void findOrder() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class)
              class OrderService {
                  /*~~(MIGRATED [TX_MIGRATED_SUPPORTS]: migrated Spring SUPPORTS semantics to Jakarta Transactions)~~>*/@Transactional(value = Transactional.TxType.SUPPORTS, rollbackOn = Error.class)
                  void findOrder() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void refusesSupportsAtomicallyWhenAnotherHierarchyMemberCannotMigrate() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_ATOMIC_SCOPE_REFUSED"),
                                  tuple("REFUSED", "TX_ATOMIC_SCOPE_REFUSED"),
                                  tuple("REFUSED", "TX_NESTED_NO_EQUIVALENT"))),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              class OrderService {
                  @Transactional(propagation = Propagation.SUPPORTS)
                  public void findOrder() {}

                  @Transactional(propagation = Propagation.NESTED)
                  public void nestedWork() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(REFUSED [TX_ATOMIC_SCOPE_REFUSED]: another Spring transaction annotation in this class cannot be migrated)~~>*/@Transactional
              class OrderService {
                  /*~~(REFUSED [TX_ATOMIC_SCOPE_REFUSED]: another Spring transaction annotation in this class cannot be migrated)~~>*/@Transactional(propagation = Propagation.SUPPORTS)
                  public void findOrder() {}

                  /*~~(REFUSED [TX_NESTED_NO_EQUIVALENT]: Spring NESTED propagation has no Jakarta Transactions equivalent)~~>*/@Transactional(propagation = Propagation.NESTED)
                  public void nestedWork() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void mapsFullyQualifiedClassLevelSupportsAndRemovesEverySpringImport() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;

              @ApplicationScoped
              @org.springframework.transaction.annotation.Transactional(
                      propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS)
              class OrderService {
                  public void findOrder() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              /*~~(MIGRATED [TX_MIGRATED_SUPPORTS]: migrated Spring SUPPORTS semantics to Jakarta Transactions)~~>*/@Transactional(value = Transactional.TxType.SUPPORTS, rollbackOn = Error.class)
              class OrderService {
                  public void findOrder() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/OrderService.java"))
        );
    }
}
