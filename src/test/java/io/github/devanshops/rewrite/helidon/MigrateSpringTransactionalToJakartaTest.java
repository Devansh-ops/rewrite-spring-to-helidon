package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.MigrationAssessmentTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.xml.Assertions.xml;

class MigrateSpringTransactionalToJakartaTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringTransactionalToJakarta())
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-tx", "jakarta.enterprise.cdi-api", "jakarta.transaction-api",
                        "jakarta.annotation-api", "jakarta.inject-api", "spring-context",
                        "spring-beans", "spring-core"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @Test
    void refusesTheWholeClassWhenOneMethodUsesNestedPropagation() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
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
                  @Transactional(propagation = Propagation.NESTED)
                  void createOrder() {
                  }
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
                  /*~~(REFUSED [TX_NESTED_NO_EQUIVALENT]: Spring NESTED propagation has no Jakarta Transactions equivalent)~~>*/@Transactional(propagation = Propagation.NESTED)
                  void createOrder() {
                  }
              }
              """,
            source -> source.path("src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void migratesBareRequiredAndPreservesSpringErrorRollback() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactly(tuple("MIGRATED", "TX_MIGRATED_REQUIRED"))),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              class OrderService {
                  void createOrder() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class)
              class OrderService {
                  void createOrder() {
                  }
              }
              """,
            source -> source.path("src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void mapsTheFiveDirectPropagationModes() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional(propagation = Propagation.REQUIRED)
                  void required() {}

                  @Transactional(propagation = Propagation.REQUIRES_NEW)
                  void requiresNew() {}

                  @Transactional(propagation = Propagation.MANDATORY)
                  void mandatory() {}

                  @Transactional(propagation = Propagation.NOT_SUPPORTED)
                  void notSupported() {}

                  @Transactional(propagation = Propagation.NEVER)
                  void never() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class)
                  void required() {}

                  /*~~(MIGRATED [TX_MIGRATED_REQUIRES_NEW]: migrated Spring REQUIRES_NEW semantics to Jakarta Transactions)~~>*/@Transactional(value = Transactional.TxType.REQUIRES_NEW, rollbackOn = Error.class)
                  void requiresNew() {}

                  /*~~(MIGRATED [TX_MIGRATED_MANDATORY]: migrated Spring MANDATORY semantics to Jakarta Transactions)~~>*/@Transactional(value = Transactional.TxType.MANDATORY, rollbackOn = Error.class)
                  void mandatory() {}

                  /*~~(MIGRATED [TX_MIGRATED_NOT_SUPPORTED]: migrated Spring NOT_SUPPORTED semantics to Jakarta Transactions)~~>*/@Transactional(value = Transactional.TxType.NOT_SUPPORTED, rollbackOn = Error.class)
                  void notSupported() {}

                  /*~~(MIGRATED [TX_MIGRATED_NEVER]: migrated Spring NEVER semantics to Jakarta Transactions)~~>*/@Transactional(value = Transactional.TxType.NEVER, rollbackOn = Error.class)
                  void never() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void migratesFullyQualifiedAndStaticImportedPropagationWithoutSpringImports() {
        rewriteRun(
          java(
            """
              package com.example.fullyqualified;

              import jakarta.enterprise.context.ApplicationScoped;

              @ApplicationScoped
              class FullyQualifiedService {
                  @org.springframework.transaction.annotation.Transactional(
                          propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
                  public void work() {}
              }
              """,
            """
              package com.example.fullyqualified;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              class FullyQualifiedService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRES_NEW]: migrated Spring REQUIRES_NEW semantics to Jakarta Transactions)~~>*/@Transactional(value = Transactional.TxType.REQUIRES_NEW, rollbackOn = Error.class)
                  public void work() {}
              }
              """,
            source -> source.path(
                    "fully-qualified/src/main/java/com/example/fullyqualified/FullyQualifiedService.java")),
          java(
            """
              package com.example.staticimport;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

              @ApplicationScoped
              class StaticImportService {
                  @Transactional(propagation = REQUIRES_NEW)
                  public void work() {}
              }
              """,
            """
              package com.example.staticimport;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              class StaticImportService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRES_NEW]: migrated Spring REQUIRES_NEW semantics to Jakarta Transactions)~~>*/@Transactional(value = Transactional.TxType.REQUIRES_NEW, rollbackOn = Error.class)
                  public void work() {}
              }
              """,
            source -> source.path(
                    "static-import/src/main/java/com/example/staticimport/StaticImportService.java"))
        );
    }

    @Test
    void refusesNonDefaultSpringOnlyAttributesWithoutDroppingThem() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_NON_DEFAULT_ISOLATION"),
                                  tuple("REFUSED", "TX_TIMEOUT_POLICY"),
                                  tuple("REFUSED", "TX_READ_ONLY_POLICY"),
                                  tuple("REFUSED", "TX_LABEL_POLICY"),
                                  tuple("REFUSED", "TX_MANAGER_SELECTION"))),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Isolation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class IsolationService {
                  @Transactional(isolation = Isolation.SERIALIZABLE)
                  void work() {}
              }

              @ApplicationScoped
              class TimeoutService {
                  @Transactional(timeout = 10)
                  void work() {}
              }

              @ApplicationScoped
              class ReadOnlyService {
                  @Transactional(readOnly = true)
                  void work() {}
              }

              @ApplicationScoped
              class LabelService {
                  @Transactional(label = "orders")
                  void work() {}
              }

              @ApplicationScoped
              class RoutedService {
                  @Transactional(transactionManager = "orders")
                  void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Isolation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class IsolationService {
                  /*~~(REFUSED [TX_NON_DEFAULT_ISOLATION]: non-default Spring isolation has no Jakarta Transactions annotation member)~~>*/@Transactional(isolation = Isolation.SERIALIZABLE)
                  void work() {}
              }

              @ApplicationScoped
              class TimeoutService {
                  /*~~(REFUSED [TX_TIMEOUT_POLICY]: non-default Spring timeout has no Jakarta Transactions annotation member)~~>*/@Transactional(timeout = 10)
                  void work() {}
              }

              @ApplicationScoped
              class ReadOnlyService {
                  /*~~(REFUSED [TX_READ_ONLY_POLICY]: Spring read-only behavior requires an explicit target persistence policy)~~>*/@Transactional(readOnly = true)
                  void work() {}
              }

              @ApplicationScoped
              class LabelService {
                  /*~~(REFUSED [TX_LABEL_POLICY]: Spring transaction labels require an explicit target provider policy)~~>*/@Transactional(label = "orders")
                  void work() {}
              }

              @ApplicationScoped
              class RoutedService {
                  /*~~(REFUSED [TX_MANAGER_SELECTION]: Spring transaction-manager selection requires an explicit target routing policy)~~>*/@Transactional(transactionManager = "orders")
                  void work() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/TransactionPolicies.java"))
        );
    }

    @Test
    void exposesEveryUnresolvedAndPatternRollbackRefusalThroughThePublicRecipe() {
        rewriteRun(
          spec -> spec.typeValidationOptions(TypeValidation.none()),
          java(
            """
              package com.example.pattern;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class PatternService {
                  @Transactional(rollbackForClassName = "Exception")
                  public void work() {}
              }
              """,
            """
              package com.example.pattern;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class PatternService {
                  /*~~(REFUSED [TX_PATTERN_ROLLBACK_RULE]: Spring string-pattern rollback rules have no Jakarta Transactions equivalent)~~>*/@Transactional(rollbackForClassName = "Exception")
                  public void work() {}
              }
              """,
            source -> source.path("pattern/src/main/java/com/example/pattern/PatternService.java")),
          java(
            """
              package com.example.rollbacktype;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class RollbackTypeService {
                  @Transactional(rollbackFor = MissingException.class)
                  public void work() {}
              }
              """,
            """
              package com.example.rollbacktype;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class RollbackTypeService {
                  /*~~(REFUSED [TX_UNATTRIBUTED_ROLLBACK_TYPE]: rollback type rules require attributed class literals)~~>*/@Transactional(rollbackFor = MissingException.class)
                  public void work() {}
              }
              """,
            source -> source.path(
                    "rollback-type/src/main/java/com/example/rollbacktype/RollbackTypeService.java")),
          java(
            """
              package com.example.propagation;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class PropagationService {
                  @Transactional(propagation = MissingPropagation.VALUE)
                  public void work() {}
              }
              """,
            """
              package com.example.propagation;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class PropagationService {
                  /*~~(REFUSED [TX_UNRESOLVED_PROPAGATION]: the Spring propagation value is not a direct supported enum constant)~~>*/@Transactional(propagation = MissingPropagation.VALUE)
                  public void work() {}
              }
              """,
            source -> source.path(
                    "propagation/src/main/java/com/example/propagation/PropagationService.java")),
          java(
            """
              package com.example.attribute;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class AttributeService {
                  @Transactional(mystery = true)
                  public void work() {}
              }
              """,
            """
              package com.example.attribute;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class AttributeService {
                  /*~~(REFUSED [TX_UNRESOLVED_ATTRIBUTE]: an unresolved Spring transaction attribute cannot be discarded safely)~~>*/@Transactional(mystery = true)
                  public void work() {}
              }
              """,
            source -> source.path(
                    "attribute/src/main/java/com/example/attribute/AttributeService.java")),
          java(
            """
              package com.example.global;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(rollbackOn = MissingRollbackPolicy.VALUE)
              class TransactionPolicy {}

              @ApplicationScoped
              class GlobalPolicyService {
                  @Transactional
                  public void work() {}
              }
              """,
            """
              package com.example.global;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(rollbackOn = MissingRollbackPolicy.VALUE)
              class TransactionPolicy {}

              @ApplicationScoped
              class GlobalPolicyService {
                  /*~~(REFUSED [TX_GLOBAL_ROLLBACK_UNRESOLVED]: the effective Spring global rollback policy cannot be resolved)~~>*/@Transactional
                  public void work() {}
              }
              """,
            source -> source.path("global/src/main/java/com/example/global/GlobalPolicyService.java"))
        );
    }

    @Test
    void mapsOnlyRollbackTypeHierarchiesWhosePrecedenceIsPreserved() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("MIGRATED", "TX_MIGRATED_REQUIRES_NEW"),
                                  tuple("REFUSED", "TX_ROLLBACK_PRECEDENCE"))),
          java(
            """
              package com.example.orders;

              import java.io.IOException;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class SafeRollbackService {
                  @Transactional(
                          propagation = Propagation.REQUIRES_NEW,
                          rollbackFor = Exception.class,
                          noRollbackFor = IOException.class)
                  void work() {}
              }

              @ApplicationScoped
              class UnsafeRollbackService {
                  @Transactional(
                          rollbackFor = IOException.class,
                          noRollbackFor = Exception.class)
                  void work() {}
              }
              """,
            """
              package com.example.orders;

              import java.io.IOException;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class SafeRollbackService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRES_NEW]: migrated Spring REQUIRES_NEW semantics to Jakarta Transactions)~~>*/@jakarta.transaction.Transactional(value = jakarta.transaction.Transactional.TxType.REQUIRES_NEW, rollbackOn = {Error.class, Exception.class}, dontRollbackOn = IOException.class)
                  void work() {}
              }

              @ApplicationScoped
              class UnsafeRollbackService {
                  /*~~(REFUSED [TX_ROLLBACK_PRECEDENCE]: Jakarta dontRollbackOn precedence would change this Spring rollback rule hierarchy)~~>*/@Transactional(
                          rollbackFor = IOException.class,
                          noRollbackFor = Exception.class)
                  void work() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/RollbackServices.java"))
        );
    }

    @Test
    void baseRecipeRefusesSupportsWithItsPolicyCode() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactly(tuple("REFUSED", "TX_SUPPORTS_POLICY"))),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional(propagation = Propagation.SUPPORTS)
                  void findOrder() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_SUPPORTS_POLICY]: Spring SUPPORTS may create a resource-synchronization scope; activate the explicit opt-in recipe only after accepting that difference)~~>*/@Transactional(propagation = Propagation.SUPPORTS)
                  void findOrder() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void normalizesExplicitSpringDefaults() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Isolation;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional(
                          propagation = Propagation.REQUIRED,
                          isolation = Isolation.DEFAULT,
                          timeout = -1,
                          timeoutString = "",
                          readOnly = false,
                          label = {},
                          transactionManager = "")
                  void createOrder() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class)
                  void createOrder() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void treatsExplicitEmptyRollbackArraysAsSpringDefaults() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional(
                          rollbackFor = {},
                          noRollbackFor = {},
                          rollbackForClassName = {},
                          noRollbackForClassName = {})
                  public void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class)
                  public void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void refusesTransactionalTestSources() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              class OrderRepositoryTest {
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(REFUSED [TX_TEST_TRANSACTION]: Spring test-managed transactions are not Jakarta CDI business transactions)~~>*/@Transactional
              class OrderRepositoryTest {
              }
              """,
            source -> source.path("src/test/java/com/example/orders/OrderRepositoryTest.java"))
        );
    }

    @Test
    void refusesTransactionalCustomTestSourceSets() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              class OrderRepositoryIntegrationTest {
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(REFUSED [TX_TEST_TRANSACTION]: Spring test-managed transactions are not Jakarta CDI business transactions)~~>*/@Transactional
              class OrderRepositoryIntegrationTest {
              }
              """,
            source -> source.path(
                    "orders/src/integrationTest/java/com/example/orders/OrderRepositoryIntegrationTest.java"))
        );
    }

    @Test
    void refusesNonInterceptableTargetsAndUserTransactionCalls() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  "TX_NON_CDI_TARGET",
                                  "TX_NON_INTERCEPTABLE_TARGET",
                                  "TX_NON_INTERCEPTABLE_TARGET",
                                  "TX_LIFECYCLE_METHOD",
                                  "TX_USER_TRANSACTION",
                                  "TX_JAKARTA_COLLISION")),
          java(
            """
              package com.example.orders;

              import jakarta.annotation.PostConstruct;
              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.UserTransaction;
              import org.springframework.transaction.annotation.Transactional;

              class PlainService {
                  @Transactional void work() {}
              }

              @ApplicationScoped
              final class FinalService {
                  @Transactional void work() {}
              }

              @ApplicationScoped
              class PrivateService {
                  @Transactional private void work() {}
              }

              @ApplicationScoped
              class LifecycleService {
                  @PostConstruct
                  @Transactional void initialize() {}
              }

              @ApplicationScoped
              class UserTransactionService {
                  @Transactional void work(UserTransaction transaction) throws Exception {
                      transaction.begin();
                  }
              }

              @ApplicationScoped
              @jakarta.transaction.Transactional
              @Transactional
              class AlreadyJakartaService {
              }
              """,
            """
              package com.example.orders;

              import jakarta.annotation.PostConstruct;
              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.UserTransaction;
              import org.springframework.transaction.annotation.Transactional;

              class PlainService {
                  /*~~(REFUSED [TX_NON_CDI_TARGET]: the enclosing type is not an attributed CDI bean)~~>*/@Transactional void work() {}
              }

              @ApplicationScoped
              final class FinalService {
                  /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: the target class or method cannot be intercepted by CDI)~~>*/@Transactional void work() {}
              }

              @ApplicationScoped
              class PrivateService {
                  /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: the target class or method cannot be intercepted by CDI)~~>*/@Transactional private void work() {}
              }

              @ApplicationScoped
              class LifecycleService {
                  @PostConstruct
                  /*~~(REFUSED [TX_LIFECYCLE_METHOD]: Jakarta transaction interception does not apply to lifecycle callbacks)~~>*/@Transactional void initialize() {}
              }

              @ApplicationScoped
              class UserTransactionService {
                  /*~~(REFUSED [TX_USER_TRANSACTION]: this transaction scope directly uses Jakarta UserTransaction)~~>*/@Transactional void work(UserTransaction transaction) throws Exception {
                      transaction.begin();
                  }
              }

              @ApplicationScoped
              @jakarta.transaction.Transactional
              /*~~(REFUSED [TX_JAKARTA_COLLISION]: the same target already declares Jakarta @Transactional)~~>*/@Transactional
              class AlreadyJakartaService {
              }
              """,
            source -> source.path("src/main/java/com/example/orders/RefusedServices.java"))
        );
    }

    @Test
    void refusesUnsafeMembersGovernedByAClassLevelTransaction() {
        rewriteRun(
          java(
            """
              package org.reactivestreams;

              public interface Publisher<T> {
              }
              """,
            source -> source.path("contract/src/main/java/org/reactivestreams/Publisher.java")),
          java(
            """
              package com.example.orders;

              import jakarta.annotation.PostConstruct;
              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.UserTransaction;
              import org.reactivestreams.Publisher;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              class FinalMethodService {
                  public final void work() {}
              }

              @ApplicationScoped
              @Transactional
              class PrivateMethodService {
                  private void work() {}
              }

              @ApplicationScoped
              @Transactional
              class StaticMethodService {
                  public static void work() {}
              }

              @ApplicationScoped
              @Transactional
              class LifecycleService {
                  @PostConstruct
                  public void initialize() {}
              }

              @ApplicationScoped
              @Transactional
              class UserTransactionService {
                  public void work(UserTransaction transaction) throws Exception {
                      transaction.begin();
                  }
              }

              @ApplicationScoped
              @Transactional
              class ReactiveService {
                  public Publisher<String> work() { return null; }
              }

              @ApplicationScoped
              @Transactional
              abstract class AbstractService {
                  public abstract void work();
              }

              class OuterService {
                  @ApplicationScoped
                  @Transactional
                  class InnerService {
                      public void work() {}
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.annotation.PostConstruct;
              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.UserTransaction;
              import org.reactivestreams.Publisher;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a class-level transaction governs a member or type that cannot be intercepted safely by CDI)~~>*/@Transactional
              class FinalMethodService {
                  public final void work() {}
              }

              @ApplicationScoped
              /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a class-level transaction governs a member or type that cannot be intercepted safely by CDI)~~>*/@Transactional
              class PrivateMethodService {
                  private void work() {}
              }

              @ApplicationScoped
              /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a class-level transaction governs a member or type that cannot be intercepted safely by CDI)~~>*/@Transactional
              class StaticMethodService {
                  public static void work() {}
              }

              @ApplicationScoped
              /*~~(REFUSED [TX_LIFECYCLE_METHOD]: a class-level transaction governs a lifecycle callback)~~>*/@Transactional
              class LifecycleService {
                  @PostConstruct
                  public void initialize() {}
              }

              @ApplicationScoped
              /*~~(REFUSED [TX_USER_TRANSACTION]: a class-level transaction governs code that directly uses Jakarta UserTransaction)~~>*/@Transactional
              class UserTransactionService {
                  public void work(UserTransaction transaction) throws Exception {
                      transaction.begin();
                  }
              }

              @ApplicationScoped
              /*~~(REFUSED [TX_REACTIVE_RETURN]: Spring reactive transaction completion cannot be represented by Jakarta invocation-scoped interception)~~>*/@Transactional
              class ReactiveService {
                  public Publisher<String> work() { return null; }
              }

              @ApplicationScoped
              /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a class-level transaction governs a member or type that cannot be intercepted safely by CDI)~~>*/@Transactional
              abstract class AbstractService {
                  public abstract void work();
              }

              class OuterService {
                  @ApplicationScoped
                  /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a class-level transaction governs a member or type that cannot be intercepted safely by CDI)~~>*/@Transactional
                  class InnerService {
                      public void work() {}
                  }
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/UnsafeServices.java"))
        );
    }

    @Test
    void refusesDirectReactiveReturnsAndOnlyExceptionCoupledPropagationModes() {
        rewriteRun(
          java(
            """
              package org.reactivestreams;

              public interface Publisher<T> {
              }
              """,
            source -> source.path("contract/src/main/java/org/reactivestreams/Publisher.java")),
          java(
            """
              package com.example.reactive;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.reactivestreams.Publisher;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class ReactiveService {
                  @Transactional
                  public Publisher<String> work() { return null; }
              }
              """,
            """
              package com.example.reactive;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.reactivestreams.Publisher;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class ReactiveService {
                  /*~~(REFUSED [TX_REACTIVE_RETURN]: Spring reactive transaction completion cannot be represented by Jakarta invocation-scoped interception)~~>*/@Transactional
                  public Publisher<String> work() { return null; }
              }
              """,
            source -> source.path("reactive/src/main/java/com/example/reactive/ReactiveService.java")),
          java(
            """
              package com.example.coupled;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.IllegalTransactionStateException;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              class SpringStateContract {
                  boolean identifies(Throwable failure) {
                      return failure instanceof IllegalTransactionStateException;
                  }
              }

              @ApplicationScoped
              class MandatoryService {
                  @Transactional(propagation = Propagation.MANDATORY)
                  public void work() {}
              }

              @ApplicationScoped
              class NeverService {
                  @Transactional(propagation = Propagation.NEVER)
                  public void work() {}
              }

              @ApplicationScoped
              class RequiredService {
                  @Transactional
                  public void work() {}
              }
              """,
            """
              package com.example.coupled;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.IllegalTransactionStateException;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              class SpringStateContract {
                  boolean identifies(Throwable failure) {
                      return failure instanceof IllegalTransactionStateException;
                  }
              }

              @ApplicationScoped
              class MandatoryService {
                  /*~~(REFUSED [TX_SPRING_TRANSACTION_EXCEPTION_COUPLING]: source code depends on Spring transaction-state exceptions for this propagation mode)~~>*/@Transactional(propagation = Propagation.MANDATORY)
                  public void work() {}
              }

              @ApplicationScoped
              class NeverService {
                  /*~~(REFUSED [TX_SPRING_TRANSACTION_EXCEPTION_COUPLING]: source code depends on Spring transaction-state exceptions for this propagation mode)~~>*/@Transactional(propagation = Propagation.NEVER)
                  public void work() {}
              }

              @ApplicationScoped
              class RequiredService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@jakarta.transaction.Transactional(rollbackOn = Error.class)
                  public void work() {}
              }
              """,
            source -> source.path("coupled/src/main/java/com/example/coupled/Services.java"))
        );
    }

    @Test
    void acceptsAttributedCustomCdiStereotypeAsBeanDefiningAnnotation() {
        rewriteRun(
          java(
            """
              package com.example.stereotype;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.inject.Stereotype;
              import org.springframework.transaction.annotation.Transactional;

              @Stereotype
              @Retention(RetentionPolicy.RUNTIME)
              @Target(ElementType.TYPE)
              @interface ApplicationService {}

              @ApplicationService
              class OrderService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.stereotype;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.inject.Stereotype;
              import jakarta.transaction.Transactional;

              @Stereotype
              @Retention(RetentionPolicy.RUNTIME)
              @Target(ElementType.TYPE)
              @interface ApplicationService {}

              @ApplicationService
              class OrderService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class) public void work() {}
              }
              """,
            source -> source.path(
                    "stereotype/src/main/java/com/example/stereotype/OrderService.java"))
        );
    }

    @Test
    void acceptsAttributedCustomNormalScopeAndAppliesItsProxyPreflight() {
        rewriteRun(
          java(
            """
              package com.example.customscope;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.NormalScope;
              import org.springframework.transaction.annotation.Transactional;

              @NormalScope
              @Retention(RetentionPolicy.RUNTIME)
              @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
              @interface TenantScoped {}

              @TenantScoped
              class OrderService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.customscope;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.NormalScope;
              import jakarta.transaction.Transactional;

              @NormalScope
              @Retention(RetentionPolicy.RUNTIME)
              @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
              @interface TenantScoped {}

              @TenantScoped
              class OrderService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class) public void work() {}
              }
              """,
            source -> source.path(
                    "custom-scope/src/main/java/com/example/customscope/OrderService.java")),
          java(
            """
              package com.example.customscopeproxy;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.NormalScope;
              import jakarta.inject.Inject;
              import org.springframework.transaction.annotation.Transactional;

              @NormalScope
              @Retention(RetentionPolicy.RUNTIME)
              @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
              @interface TenantScoped {}

              @TenantScoped
              class OrderService {
                  @Inject
                  OrderService(String dependency) {}

                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.customscopeproxy;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.NormalScope;
              import jakarta.inject.Inject;
              import org.springframework.transaction.annotation.Transactional;

              @NormalScope
              @Retention(RetentionPolicy.RUNTIME)
              @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
              @interface TenantScoped {}

              @TenantScoped
              class OrderService {
                  @Inject
                  OrderService(String dependency) {}

                  /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a normal-scoped transactional bean has no non-private no-argument proxy constructor)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "custom-scope-proxy/src/main/java/com/example/customscopeproxy/OrderService.java"))
        );
    }

    @Test
    void distinguishesNormalScopeProxyConstructorsFromDependentAndSingletonBeans() {
        rewriteRun(
          java(
            """
              package com.example.normal;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Inject;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              class NormalScopedService {
                  @Inject
                  NormalScopedService(String dependency) {}

                  public void work() {}
              }
              """,
            """
              package com.example.normal;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Inject;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a normal-scoped transactional bean has no non-private no-argument proxy constructor)~~>*/@Transactional
              class NormalScopedService {
                  @Inject
                  NormalScopedService(String dependency) {}

                  public void work() {}
              }
              """,
            source -> source.path("normal/src/main/java/com/example/normal/NormalScopedService.java")),
          java(
            """
              package com.example.dependent;

              import jakarta.enterprise.context.Dependent;
              import jakarta.inject.Inject;
              import org.springframework.transaction.annotation.Transactional;

              @Dependent
              @Transactional
              class DependentService {
                  @Inject
                  DependentService(String dependency) {}

                  public void work() {}
              }
              """,
            """
              package com.example.dependent;

              import jakarta.enterprise.context.Dependent;
              import jakarta.inject.Inject;
              import jakarta.transaction.Transactional;

              @Dependent
              /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class)
              class DependentService {
                  @Inject
                  DependentService(String dependency) {}

                  public void work() {}
              }
              """,
            source -> source.path("dependent/src/main/java/com/example/dependent/DependentService.java")),
          java(
            """
              package com.example.singleton;

              import jakarta.inject.Inject;
              import jakarta.inject.Singleton;
              import org.springframework.transaction.annotation.Transactional;

              @Singleton
              @Transactional
              class SingletonService {
                  @Inject
                  SingletonService(String dependency) {}

                  public void work() {}
              }
              """,
            """
              package com.example.singleton;

              import jakarta.inject.Inject;
              import jakarta.inject.Singleton;
              import jakarta.transaction.Transactional;

              @Singleton
              /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class)
              class SingletonService {
                  @Inject
                  SingletonService(String dependency) {}

                  public void work() {}
              }
              """,
            source -> source.path("singleton/src/main/java/com/example/singleton/SingletonService.java")),
          java(
            """
              package com.example.sealed;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              sealed class SealedService permits ConcreteService {
                  public void work() {}
              }

              final class ConcreteService extends SealedService {
              }
              """,
            """
              package com.example.sealed;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a class-level transaction governs a member or type that cannot be intercepted safely by CDI)~~>*/@Transactional
              sealed class SealedService permits ConcreteService {
                  public void work() {}
              }

              final class ConcreteService extends SealedService {
              }
              """,
            source -> source.path("sealed/src/main/java/com/example/sealed/SealedService.java"))
        );
    }

    @Test
    void appliesCdiTypeAndProxyPreflightToMethodLevelTransactions() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_NON_INTERCEPTABLE_TARGET"),
                                  tuple("REFUSED", "TX_NON_INTERCEPTABLE_TARGET"),
                                  tuple("REFUSED", "TX_NON_INTERCEPTABLE_TARGET"),
                                  tuple("REFUSED", "TX_NON_INTERCEPTABLE_TARGET"),
                                  tuple("MIGRATED", "TX_MIGRATED_REQUIRED"),
                                  tuple("MIGRATED", "TX_MIGRATED_REQUIRED"))),
          java(
            """
              package com.example.normalmethod;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Inject;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class NormalMethodService {
                  @Inject
                  NormalMethodService(String dependency) {}

                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.normalmethod;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Inject;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class NormalMethodService {
                  @Inject
                  NormalMethodService(String dependency) {}

                  /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a normal-scoped transactional bean has no non-private no-argument proxy constructor)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "normal-method/src/main/java/com/example/normalmethod/NormalMethodService.java")),
          java(
            """
              package com.example.abstractmethod;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              abstract class AbstractMethodService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.abstractmethod;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              abstract class AbstractMethodService {
                  /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: the target class or method cannot be intercepted by CDI)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "abstract-method/src/main/java/com/example/abstractmethod/AbstractMethodService.java")),
          java(
            """
              package com.example.sealedmethod;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              sealed class SealedMethodService permits ConcreteMethodService {
                  @Transactional public void work() {}
              }

              final class ConcreteMethodService extends SealedMethodService {}
              """,
            """
              package com.example.sealedmethod;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              sealed class SealedMethodService permits ConcreteMethodService {
                  /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: the target class or method cannot be intercepted by CDI)~~>*/@Transactional public void work() {}
              }

              final class ConcreteMethodService extends SealedMethodService {}
              """,
            source -> source.path(
                    "sealed-method/src/main/java/com/example/sealedmethod/SealedMethodService.java")),
          java(
            """
              package com.example.innermethod;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              class OuterService {
                  @ApplicationScoped
                  class InnerMethodService {
                      @Transactional public void work() {}
                  }
              }
              """,
            """
              package com.example.innermethod;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              class OuterService {
                  @ApplicationScoped
                  class InnerMethodService {
                      /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: the target class or method cannot be intercepted by CDI)~~>*/@Transactional public void work() {}
                  }
              }
              """,
            source -> source.path(
                    "inner-method/src/main/java/com/example/innermethod/OuterService.java")),
          java(
            """
              package com.example.dependentmethod;

              import jakarta.enterprise.context.Dependent;
              import jakarta.inject.Inject;
              import org.springframework.transaction.annotation.Transactional;

              @Dependent
              class DependentMethodService {
                  @Inject
                  DependentMethodService(String dependency) {}

                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.dependentmethod;

              import jakarta.enterprise.context.Dependent;
              import jakarta.inject.Inject;
              import jakarta.transaction.Transactional;

              @Dependent
              class DependentMethodService {
                  @Inject
                  DependentMethodService(String dependency) {}

                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class) public void work() {}
              }
              """,
            source -> source.path(
                    "dependent-method/src/main/java/com/example/dependentmethod/DependentMethodService.java")),
          java(
            """
              package com.example.singletonmethod;

              import jakarta.inject.Inject;
              import jakarta.inject.Singleton;
              import org.springframework.transaction.annotation.Transactional;

              @Singleton
              class SingletonMethodService {
                  @Inject
                  SingletonMethodService(String dependency) {}

                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.singletonmethod;

              import jakarta.inject.Inject;
              import jakarta.inject.Singleton;
              import jakarta.transaction.Transactional;

              @Singleton
              class SingletonMethodService {
                  @Inject
                  SingletonMethodService(String dependency) {}

                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class) public void work() {}
              }
              """,
            source -> source.path(
                    "singleton-method/src/main/java/com/example/singletonmethod/SingletonMethodService.java"))
        );
    }

    @Test
    void materializesTheResolvedAllExceptionsGlobalDefault() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class TransactionPolicy {
              }

              @ApplicationScoped
              class OrderService {
                  @Transactional
                  void createOrder() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class TransactionPolicy {
              }

              @ApplicationScoped
              class OrderService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = {Error.class, Exception.class})
                  void createOrder() {}
              }
              """,
            source -> source.path("src/main/java/com/example/orders/Transactions.java"))
        );
    }

    @Test
    void validatesGlobalAllExceptionsAgainstLocalNegativeRollbackRules() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_ROLLBACK_PRECEDENCE"),
                                  tuple("MIGRATED", "TX_MIGRATED_REQUIRED"))),
          java(
            """
              package com.example.unsafe;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class TransactionPolicy {}

              @ApplicationScoped
              class UnsafeService {
                  @Transactional(noRollbackFor = Throwable.class)
                  public void work() {}
              }
              """,
            """
              package com.example.unsafe;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class TransactionPolicy {}

              @ApplicationScoped
              class UnsafeService {
                  /*~~(REFUSED [TX_ROLLBACK_PRECEDENCE]: Jakarta dontRollbackOn precedence would change this Spring rollback rule hierarchy)~~>*/@Transactional(noRollbackFor = Throwable.class)
                  public void work() {}
              }
              """,
            source -> source.path("unsafe/src/main/java/com/example/unsafe/UnsafeService.java")),
          java(
            """
              package com.example.safe;

              import java.io.IOException;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class TransactionPolicy {}

              @ApplicationScoped
              class SafeService {
                  @Transactional(noRollbackFor = IOException.class)
                  public void work() {}
              }
              """,
            """
              package com.example.safe;

              import java.io.IOException;
              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class TransactionPolicy {}

              @ApplicationScoped
              class SafeService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = {Error.class, Exception.class}, dontRollbackOn = IOException.class)
                  public void work() {}
              }
              """,
            source -> source.path("safe/src/main/java/com/example/safe/SafeService.java"))
        );
    }

    @Test
    void refusesConflictingGlobalRollbackAndAspectjMode() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(rollbackOn = RollbackOn.RUNTIME_EXCEPTIONS)
              class RuntimePolicy {}

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class AllPolicy {}

              @ApplicationScoped
              class OrderService {
                  @Transactional void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(rollbackOn = RollbackOn.RUNTIME_EXCEPTIONS)
              class RuntimePolicy {}

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class AllPolicy {}

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_GLOBAL_ROLLBACK_CONFLICT]: conflicting Spring global rollback policies were found in this source set)~~>*/@Transactional void work() {}
              }
              """
          )
        );

        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.AdviceMode;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(mode = AdviceMode.ASPECTJ)
              class TransactionPolicy {}

              @ApplicationScoped
              class OrderService {
                  @Transactional void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.AdviceMode;
              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.Transactional;

              @EnableTransactionManagement(mode = AdviceMode.ASPECTJ)
              class TransactionPolicy {}

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_ASPECTJ_MODE]: Spring AspectJ transaction weaving is not equivalent to CDI interception)~~>*/@Transactional void work() {}
              }
              """
          )
        );
    }

    @Test
    void refusesProgrammaticAndAmbiguousTransactionManagerPolicies() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
              import org.springframework.transaction.annotation.Transactional;

              class CustomTransactionAttributeSource extends AnnotationTransactionAttributeSource {
              }

              @ApplicationScoped
              class OrderService {
                  @Transactional void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
              import org.springframework.transaction.annotation.Transactional;

              class CustomTransactionAttributeSource extends AnnotationTransactionAttributeSource {
              }

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_CUSTOM_ROLLBACK_POLICY]: programmatic Spring default rollback rules require an explicit target policy)~~>*/@Transactional void work() {}
              }
              """
          )
        );

        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              @Configuration
              class TransactionPolicy {
                  @Bean
                  PlatformTransactionManager orders() { return null; }

                  @Bean
                  PlatformTransactionManager billing() { return null; }
              }

              @ApplicationScoped
              class OrderService {
                  @Transactional void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              @Configuration
              class TransactionPolicy {
                  @Bean
                  PlatformTransactionManager orders() { return null; }

                  @Bean
                  PlatformTransactionManager billing() { return null; }
              }

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_MULTIPLE_TRANSACTION_MANAGERS]: multiple Spring transaction managers require an explicit target routing policy)~~>*/@Transactional void work() {}
              }
              """
          )
        );

        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              class TransactionPolicy {
                  @Qualifier("orders")
                  PlatformTransactionManager manager;
              }

              @ApplicationScoped
              class OrderService {
                  @Transactional void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              class TransactionPolicy {
                  @Qualifier("orders")
                  PlatformTransactionManager manager;
              }

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_QUALIFIED_TRANSACTION_MANAGER]: qualified Spring transaction managers require an explicit target routing policy)~~>*/@Transactional void work() {}
              }
              """
          )
        );

        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.ReactiveTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              class TransactionPolicy {
                  ReactiveTransactionManager manager;
              }

              @ApplicationScoped
              class OrderService {
                  @Transactional void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.ReactiveTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              class TransactionPolicy {
                  ReactiveTransactionManager manager;
              }

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_REACTIVE_TRANSACTION_MANAGER]: reactive Spring transactions are not invocation-scoped Jakarta CDI transactions)~~>*/@Transactional void work() {}
              }
              """
          )
        );
    }

    @Test
    void refusesEveryProgrammaticTransactionPolicyThroughItsOwningModule() {
        rewriteRun(
          java(
            """
              package com.example.configurer;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.TransactionManagementConfigurer;
              import org.springframework.transaction.annotation.Transactional;

              abstract class TransactionPolicy implements TransactionManagementConfigurer {
              }

              @ApplicationScoped
              class ConfigurerService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.configurer;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.TransactionManagementConfigurer;
              import org.springframework.transaction.annotation.Transactional;

              abstract class TransactionPolicy implements TransactionManagementConfigurer {
              }

              @ApplicationScoped
              class ConfigurerService {
                  /*~~(REFUSED [TX_PROGRAMMATIC_TRANSACTION_POLICY]: programmatic Spring transaction policy requires an explicit Jakarta target contract)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "configurer/src/main/java/com/example/configurer/ConfigurerService.java")),
          java(
            """
              package com.example.attributesource;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.interceptor.TransactionAttributeSource;

              @Configuration
              class TransactionPolicy {
                  @Bean
                  TransactionAttributeSource transactionAttributeSource() { return null; }
              }

              @ApplicationScoped
              class AttributeSourceService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.attributesource;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.interceptor.TransactionAttributeSource;

              @Configuration
              class TransactionPolicy {
                  @Bean
                  TransactionAttributeSource transactionAttributeSource() { return null; }
              }

              @ApplicationScoped
              class AttributeSourceService {
                  /*~~(REFUSED [TX_PROGRAMMATIC_TRANSACTION_POLICY]: programmatic Spring transaction policy requires an explicit Jakarta target contract)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "attribute-source/src/main/java/com/example/attributesource/AttributeSourceService.java")),
          java(
            """
              package com.example.template;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.support.TransactionTemplate;

              class TransactionPolicy {
                  TransactionTemplate template;
              }

              @ApplicationScoped
              class TemplateService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.template;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.support.TransactionTemplate;

              class TransactionPolicy {
                  TransactionTemplate template;
              }

              @ApplicationScoped
              class TemplateService {
                  /*~~(REFUSED [TX_PROGRAMMATIC_TRANSACTION_POLICY]: programmatic Spring transaction policy requires an explicit Jakarta target contract)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path("template/src/main/java/com/example/template/TemplateService.java")),
          java(
            """
              package com.example.operator;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.reactive.TransactionalOperator;

              class TransactionPolicy {
                  TransactionalOperator operator;
              }

              @ApplicationScoped
              class OperatorService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.operator;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.reactive.TransactionalOperator;

              class TransactionPolicy {
                  TransactionalOperator operator;
              }

              @ApplicationScoped
              class OperatorService {
                  /*~~(REFUSED [TX_REACTIVE_TRANSACTION_API]: programmatic reactive Spring transactions have no invocation-scoped Jakarta mapping)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path("operator/src/main/java/com/example/operator/OperatorService.java")),
          java(
            """
              package com.example.managerops;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.TransactionStatus;
              import org.springframework.transaction.annotation.Transactional;

              class TransactionPolicy {
                  void complete(PlatformTransactionManager manager, TransactionStatus status) {
                      manager.commit(status);
                  }
              }

              @ApplicationScoped
              class ManagerOperationService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.managerops;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.TransactionStatus;
              import org.springframework.transaction.annotation.Transactional;

              class TransactionPolicy {
                  void complete(PlatformTransactionManager manager, TransactionStatus status) {
                      manager.commit(status);
                  }
              }

              @ApplicationScoped
              class ManagerOperationService {
                  /*~~(REFUSED [TX_PROGRAMMATIC_TRANSACTION_POLICY]: programmatic Spring transaction policy requires an explicit Jakarta target contract)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "manager-ops/src/main/java/com/example/managerops/ManagerOperationService.java")),
          xml(
            """
              <beans xmlns="http://www.springframework.org/schema/beans"
                     xmlns:tx="http://www.springframework.org/schema/tx">
                  <tx:annotation-driven/>
              </beans>
              """,
            source -> source.path("xml-policy/src/main/resources/transactions.xml")),
          java(
            """
              package com.example.xmlpolicy;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class XmlPolicyService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.xmlpolicy;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class XmlPolicyService {
                  /*~~(REFUSED [TX_XML_TRANSACTION_POLICY]: XML Spring transaction advice requires an explicit target policy)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "xml-policy/src/main/java/com/example/xmlpolicy/XmlPolicyService.java"))
        );
    }

    @Test
    void refusesPublicSpringTransactionContextInvocationSeams() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_PROGRAMMATIC_TRANSACTION_POLICY"),
                                  tuple("REFUSED", "TX_PROGRAMMATIC_TRANSACTION_POLICY"),
                                  tuple("REFUSED", "TX_PROGRAMMATIC_TRANSACTION_POLICY"),
                                  tuple("REFUSED", "TX_REACTIVE_TRANSACTION_API"))),
          java(
            """
              package com.example.aspect;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.interceptor.TransactionAspectSupport;

              class TransactionCoupling {
                  void markRollbackOnly() {
                      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                  }
              }

              @ApplicationScoped
              class AspectService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.aspect;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.interceptor.TransactionAspectSupport;

              class TransactionCoupling {
                  void markRollbackOnly() {
                      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                  }
              }

              @ApplicationScoped
              class AspectService {
                  /*~~(REFUSED [TX_PROGRAMMATIC_TRANSACTION_POLICY]: programmatic Spring transaction policy requires an explicit Jakarta target contract)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path("aspect/src/main/java/com/example/aspect/AspectService.java")),
          java(
            """
              package com.example.synchronization;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.support.TransactionSynchronizationManager;

              class TransactionCoupling {
                  boolean active() {
                      return TransactionSynchronizationManager.isActualTransactionActive();
                  }
              }

              @ApplicationScoped
              class SynchronizationService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.synchronization;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.support.TransactionSynchronizationManager;

              class TransactionCoupling {
                  boolean active() {
                      return TransactionSynchronizationManager.isActualTransactionActive();
                  }
              }

              @ApplicationScoped
              class SynchronizationService {
                  /*~~(REFUSED [TX_PROGRAMMATIC_TRANSACTION_POLICY]: programmatic Spring transaction policy requires an explicit Jakarta target contract)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "synchronization/src/main/java/com/example/synchronization/SynchronizationService.java")),
          java(
            """
              package com.example.templatechain;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.support.TransactionTemplate;

              class TransactionCoupling {
                  Object execute(PlatformTransactionManager manager) {
                      return new TransactionTemplate(manager).execute(status -> null);
                  }
              }

              @ApplicationScoped
              class TemplateChainService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.templatechain;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.support.TransactionTemplate;

              class TransactionCoupling {
                  Object execute(PlatformTransactionManager manager) {
                      return new TransactionTemplate(manager).execute(status -> null);
                  }
              }

              @ApplicationScoped
              class TemplateChainService {
                  /*~~(REFUSED [TX_PROGRAMMATIC_TRANSACTION_POLICY]: programmatic Spring transaction policy requires an explicit Jakarta target contract)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "template-chain/src/main/java/com/example/templatechain/TemplateChainService.java")),
          java(
            """
              package com.example.operatorchain;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.ReactiveTransactionManager;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.reactive.TransactionalOperator;

              class TransactionCoupling {
                  Object create() {
                      return TransactionalOperator.create((ReactiveTransactionManager) null);
                  }
              }

              @ApplicationScoped
              class OperatorChainService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.operatorchain;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.ReactiveTransactionManager;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.reactive.TransactionalOperator;

              class TransactionCoupling {
                  Object create() {
                      return TransactionalOperator.create((ReactiveTransactionManager) null);
                  }
              }

              @ApplicationScoped
              class OperatorChainService {
                  /*~~(REFUSED [TX_REACTIVE_TRANSACTION_API]: programmatic reactive Spring transactions have no invocation-scoped Jakarta mapping)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "operator-chain/src/main/java/com/example/operatorchain/OperatorChainService.java"))
        );
    }

    @Test
    void doesNotCountRepeatedManagerInjectionAsMultipleBeanDeclarations() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              class ManagerConsumers {
                  PlatformTransactionManager firstReference;
                  PlatformTransactionManager secondReference;
              }

              @ApplicationScoped
              class OrderService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;
              import org.springframework.transaction.PlatformTransactionManager;

              class ManagerConsumers {
                  PlatformTransactionManager firstReference;
                  PlatformTransactionManager secondReference;
              }

              @ApplicationScoped
              class OrderService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class) public void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void refusesTypeQualifiedBeansAndCountsStereotypeManagedManagerDeclarations() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_MANAGER_SELECTION"),
                                  tuple("MIGRATED", "TX_MIGRATED_REQUIRED"),
                                  tuple("REFUSED", "TX_MULTIPLE_TRANSACTION_MANAGERS"))),
          java(
            """
              package com.example.qualified;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Qualifier("orders")
              class QualifiedOrderService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.qualified;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Qualifier("orders")
              class QualifiedOrderService {
                  /*~~(REFUSED [TX_MANAGER_SELECTION]: Spring transaction-manager selection requires an explicit target routing policy)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "qualified/src/main/java/com/example/qualified/QualifiedOrderService.java")),
          java(
            """
              package com.example.qualified;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class DefaultOrderService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.qualified;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              class DefaultOrderService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class) public void work() {}
              }
              """,
            source -> source.path(
                    "qualified/src/main/java/com/example/qualified/DefaultOrderService.java")),
          java(
            """
              package com.example.managers;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.stereotype.Component;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.TransactionDefinition;
              import org.springframework.transaction.TransactionStatus;
              import org.springframework.transaction.annotation.Transactional;

              @Component
              class OrdersManager implements PlatformTransactionManager {
                  public TransactionStatus getTransaction(TransactionDefinition definition) { return null; }
                  public void commit(TransactionStatus status) {}
                  public void rollback(TransactionStatus status) {}
              }

              @Component
              class BillingManager implements PlatformTransactionManager {
                  public TransactionStatus getTransaction(TransactionDefinition definition) { return null; }
                  public void commit(TransactionStatus status) {}
                  public void rollback(TransactionStatus status) {}
              }

              @ApplicationScoped
              class ManagedService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.managers;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.stereotype.Component;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.TransactionDefinition;
              import org.springframework.transaction.TransactionStatus;
              import org.springframework.transaction.annotation.Transactional;

              @Component
              class OrdersManager implements PlatformTransactionManager {
                  public TransactionStatus getTransaction(TransactionDefinition definition) { return null; }
                  public void commit(TransactionStatus status) {}
                  public void rollback(TransactionStatus status) {}
              }

              @Component
              class BillingManager implements PlatformTransactionManager {
                  public TransactionStatus getTransaction(TransactionDefinition definition) { return null; }
                  public void commit(TransactionStatus status) {}
                  public void rollback(TransactionStatus status) {}
              }

              @ApplicationScoped
              class ManagedService {
                  /*~~(REFUSED [TX_MULTIPLE_TRANSACTION_MANAGERS]: multiple Spring transaction managers require an explicit target routing policy)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path("managers/src/main/java/com/example/managers/Managers.java"))
        );
    }

    @Test
    void discoversAttributedCustomQualifierAndStereotypeMetaAnnotations() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_MANAGER_SELECTION"),
                                  tuple("MIGRATED", "TX_MIGRATED_REQUIRED"),
                                  tuple("REFUSED", "TX_MULTIPLE_TRANSACTION_MANAGERS"))),
          java(
            """
              package com.example.metaqualified;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.transaction.annotation.Transactional;

              @Target(ElementType.TYPE)
              @Retention(RetentionPolicy.RUNTIME)
              @Qualifier
              @interface OrdersTransactionManager {}

              @ApplicationScoped
              @OrdersTransactionManager
              class MetaQualifiedService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.metaqualified;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.transaction.annotation.Transactional;

              @Target(ElementType.TYPE)
              @Retention(RetentionPolicy.RUNTIME)
              @Qualifier
              @interface OrdersTransactionManager {}

              @ApplicationScoped
              @OrdersTransactionManager
              class MetaQualifiedService {
                  /*~~(REFUSED [TX_MANAGER_SELECTION]: Spring transaction-manager selection requires an explicit target routing policy)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "meta-qualified/src/main/java/com/example/metaqualified/MetaQualifiedService.java")),
          java(
            """
              package com.example.metaqualified;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class MetaDefaultService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.metaqualified;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              class MetaDefaultService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class) public void work() {}
              }
              """,
            source -> source.path(
                    "meta-qualified/src/main/java/com/example/metaqualified/MetaDefaultService.java")),
          java(
            """
              package com.example.metamanagers;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.stereotype.Component;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.TransactionDefinition;
              import org.springframework.transaction.TransactionStatus;
              import org.springframework.transaction.annotation.Transactional;

              @Target(ElementType.TYPE)
              @Retention(RetentionPolicy.RUNTIME)
              @Component
              @interface ManagedTransactionManager {}

              @ManagedTransactionManager
              class OrdersManager implements PlatformTransactionManager {
                  public TransactionStatus getTransaction(TransactionDefinition definition) { return null; }
                  public void commit(TransactionStatus status) {}
                  public void rollback(TransactionStatus status) {}
              }

              @ManagedTransactionManager
              class BillingManager implements PlatformTransactionManager {
                  public TransactionStatus getTransaction(TransactionDefinition definition) { return null; }
                  public void commit(TransactionStatus status) {}
                  public void rollback(TransactionStatus status) {}
              }

              @ApplicationScoped
              class MetaManagedService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.metamanagers;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.stereotype.Component;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.TransactionDefinition;
              import org.springframework.transaction.TransactionStatus;
              import org.springframework.transaction.annotation.Transactional;

              @Target(ElementType.TYPE)
              @Retention(RetentionPolicy.RUNTIME)
              @Component
              @interface ManagedTransactionManager {}

              @ManagedTransactionManager
              class OrdersManager implements PlatformTransactionManager {
                  public TransactionStatus getTransaction(TransactionDefinition definition) { return null; }
                  public void commit(TransactionStatus status) {}
                  public void rollback(TransactionStatus status) {}
              }

              @ManagedTransactionManager
              class BillingManager implements PlatformTransactionManager {
                  public TransactionStatus getTransaction(TransactionDefinition definition) { return null; }
                  public void commit(TransactionStatus status) {}
                  public void rollback(TransactionStatus status) {}
              }

              @ApplicationScoped
              class MetaManagedService {
                  /*~~(REFUSED [TX_MULTIPLE_TRANSACTION_MANAGERS]: multiple Spring transaction managers require an explicit target routing policy)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "meta-managers/src/main/java/com/example/metamanagers/MetaManagers.java"))
        );
    }

    @Test
    void discoversAttributedCustomBeanMetaAnnotationsForManagerAndPolicyMethods() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_MULTIPLE_TRANSACTION_MANAGERS"),
                                  tuple("REFUSED", "TX_PROGRAMMATIC_TRANSACTION_POLICY"))),
          java(
            """
              package com.example.metabeanmanagers;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              @Target(ElementType.METHOD)
              @Retention(RetentionPolicy.RUNTIME)
              @Bean
              @interface TransactionManagerBean {}

              @Configuration
              class TransactionPolicy {
                  @TransactionManagerBean
                  PlatformTransactionManager orders() { return null; }

                  @TransactionManagerBean
                  PlatformTransactionManager billing() { return null; }
              }

              @ApplicationScoped
              class ManagedService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.metabeanmanagers;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              @Target(ElementType.METHOD)
              @Retention(RetentionPolicy.RUNTIME)
              @Bean
              @interface TransactionManagerBean {}

              @Configuration
              class TransactionPolicy {
                  @TransactionManagerBean
                  PlatformTransactionManager orders() { return null; }

                  @TransactionManagerBean
                  PlatformTransactionManager billing() { return null; }
              }

              @ApplicationScoped
              class ManagedService {
                  /*~~(REFUSED [TX_MULTIPLE_TRANSACTION_MANAGERS]: multiple Spring transaction managers require an explicit target routing policy)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "meta-bean-managers/src/main/java/com/example/metabeanmanagers/TransactionPolicy.java")),
          java(
            """
              package com.example.metabeanpolicy;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.support.TransactionTemplate;

              @Target(ElementType.METHOD)
              @Retention(RetentionPolicy.RUNTIME)
              @Bean
              @interface TransactionPolicyBean {}

              @Configuration
              class TransactionPolicy {
                  @TransactionPolicyBean
                  TransactionTemplate transactionTemplate() { return null; }
              }

              @ApplicationScoped
              class PolicyService {
                  @Transactional public void work() {}
              }
              """,
            """
              package com.example.metabeanpolicy;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.transaction.support.TransactionTemplate;

              @Target(ElementType.METHOD)
              @Retention(RetentionPolicy.RUNTIME)
              @Bean
              @interface TransactionPolicyBean {}

              @Configuration
              class TransactionPolicy {
                  @TransactionPolicyBean
                  TransactionTemplate transactionTemplate() { return null; }
              }

              @ApplicationScoped
              class PolicyService {
                  /*~~(REFUSED [TX_PROGRAMMATIC_TRANSACTION_POLICY]: programmatic Spring transaction policy requires an explicit Jakarta target contract)~~>*/@Transactional public void work() {}
              }
              """,
            source -> source.path(
                    "meta-bean-policy/src/main/java/com/example/metabeanpolicy/TransactionPolicy.java"))
        );
    }

    @Test
    void scopesXmlTransactionPolicyRefusalToItsModule() {
        rewriteRun(
          xml(
            """
              <beans xmlns="http://www.springframework.org/schema/beans"
                     xmlns:tx="http://www.springframework.org/schema/tx">
                  <tx:advice id="transactionAdvice"/>
              </beans>
              """,
            source -> source.path("orders/src/main/resources/transactions.xml")),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_XML_TRANSACTION_POLICY]: XML Spring transaction advice requires an explicit target policy)~~>*/@Transactional void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java")),
          java(
            """
              package com.example.billing;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class BillingService {
                  @Transactional void work() {}
              }
              """,
            """
              package com.example.billing;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.Transactional;

              @ApplicationScoped
              class BillingService {
                  /*~~(MIGRATED [TX_MIGRATED_REQUIRED]: migrated Spring REQUIRED semantics to Jakarta Transactions)~~>*/@Transactional(rollbackOn = Error.class) void work() {}
              }
              """,
            source -> source.path("billing/src/main/java/com/example/billing/BillingService.java"))
        );
    }

    @Test
    void refusesComposedTransactionDeclarationAndUsage() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("REFUSED", "TX_COMPOSED_TRANSACTION"),
                                  tuple("REFUSED", "TX_COMPOSED_TRANSACTION"))),
          java(
            """
              package com.example.orders;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @Target({ElementType.TYPE, ElementType.METHOD})
              @Retention(RetentionPolicy.RUNTIME)
              @Transactional
              @interface ReadOnlyTransaction {
              }

              @ApplicationScoped
              @ReadOnlyTransaction
              class OrderService {
              }
              """,
            """
              package com.example.orders;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @Target({ElementType.TYPE, ElementType.METHOD})
              @Retention(RetentionPolicy.RUNTIME)
              /*~~(REFUSED [TX_COMPOSED_TRANSACTION]: composed Spring transaction annotations require an explicit target annotation contract)~~>*/@Transactional
              @interface ReadOnlyTransaction {
              }

              @ApplicationScoped
              /*~~(REFUSED [TX_COMPOSED_TRANSACTION]: composed Spring transaction annotations require an explicit target annotation contract)~~>*/@ReadOnlyTransaction
              class OrderService {
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void refusesASourceVisibleHierarchyAsOneAtomicUnit() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              public class BaseOrderService {
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(REFUSED [TX_ATOMIC_SCOPE_REFUSED]: another Spring transaction annotation in this class cannot be migrated)~~>*/@Transactional
              public class BaseOrderService {
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/BaseOrderService.java")),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              public class SpecialOrderService extends BaseOrderService {
                  @Transactional(propagation = Propagation.NESTED)
                  public void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              public class SpecialOrderService extends BaseOrderService {
                  /*~~(REFUSED [TX_NESTED_NO_EQUIVALENT]: Spring NESTED propagation has no Jakarta Transactions equivalent)~~>*/@Transactional(propagation = Propagation.NESTED)
                  public void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/SpecialOrderService.java"))
        );
    }

    @Test
    void refusesInheritedClassTransactionsWhenASourceSubclassHasUnsafeMembers() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Transactional
              public class BaseOrderService {
                  public void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(REFUSED [TX_NON_INTERCEPTABLE_TARGET]: a class-level transaction governs a member or type that cannot be intercepted safely by CDI)~~>*/@Transactional
              public class BaseOrderService {
                  public void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/BaseOrderService.java")),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;

              @ApplicationScoped
              public class SpecializedOrderService extends BaseOrderService {
                  public final void unsafeOverride() {}
              }
              """,
            source -> source.path(
                    "orders/src/main/java/com/example/orders/SpecializedOrderService.java"))
        );
    }

    @Test
    void refusesHierarchyMembersOutsideTheSourceModule() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import java.util.ArrayList;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService extends ArrayList<String> {
                  @Transactional
                  public void work() {}
              }
              """,
            """
              package com.example.orders;

              import java.util.ArrayList;
              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService extends ArrayList<String> {
                  /*~~(REFUSED [TX_EXTERNAL_HIERARCHY]: the transactional type has a hierarchy member outside this source module)~~>*/@Transactional
                  public void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void refusesDeclaredHierarchyMembersWhenTheirTypeAttributionIsMissing() {
        rewriteRun(
          spec -> spec.typeValidationOptions(TypeValidation.none()),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService extends MissingBaseService {
                  @Transactional
                  public void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService extends MissingBaseService {
                  /*~~(REFUSED [TX_MISSING_ATTRIBUTION]: the transactional type declares a hierarchy member whose type is not attributed)~~>*/@Transactional
                  public void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void marksAnExactDirectAnnotationWhenTypeAttributionIsMissing() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().classpath(
                          "jakarta.enterprise.cdi-api", "jakarta.transaction-api"))
                  .typeValidationOptions(TypeValidation.none()),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;

              @ApplicationScoped
              class OrderService {
                  @org.springframework.transaction.annotation.Transactional
                  public void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_MISSING_ATTRIBUTION]: exact Spring @Transactional syntax is present but its type is not attributed)~~>*/@org.springframework.transaction.annotation.Transactional
                  public void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void onlyTreatsAnUnambiguousExactImportAsMissingSpringAttribution() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().classpath(
                          "jakarta.enterprise.cdi-api", "jakarta.transaction-api"))
                  .typeValidationOptions(TypeValidation.none()),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional
                  public void work() {}
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(REFUSED [TX_MISSING_ATTRIBUTION]: exact Spring @Transactional syntax is present but its type is not attributed)~~>*/@Transactional
                  public void work() {}
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java")),
          java(
            """
              package com.example.other;

              import jakarta.enterprise.context.ApplicationScoped;

              @interface Transactional {}

              @ApplicationScoped
              class OtherService {
                  @Transactional
                  public void work() {}
              }
              """,
            source -> source.path("other/src/main/java/com/example/other/OtherService.java"))
        );
    }
}
