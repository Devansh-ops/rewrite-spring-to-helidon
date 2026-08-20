package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateSpringTransactionalToJakartaTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringTransactionalToJakarta())
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-tx", "jakarta.enterprise.cdi-api", "jakarta.inject-api",
                        "jakarta.transaction-api", "spring-web")
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

    @DocumentExample
    @Test
    void preservesSpringErrorRollbackForDefaultTransactions() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional
                  void createOrder() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;

              @ApplicationScoped
              class OrderService {
                  @jakarta.transaction.Transactional(rollbackOn = Error.class)
                  void createOrder() {
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesCombinedRollbackAndNoRollbackRules() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional(
                          rollbackFor = java.io.IOException.class,
                          noRollbackFor = java.sql.SQLException.class)
                  void importOrders() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(Manual migration: combined rollbackFor and noRollbackFor rules require semantic review)~~>*/@Transactional(
                          rollbackFor = java.io.IOException.class,
                          noRollbackFor = java.sql.SQLException.class)
                  void importOrders() {
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesOneSidedRollbackRulesUntilErrorSemanticsCanBePreserved() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class ImportService {
                  @Transactional(rollbackFor = java.io.IOException.class)
                  void importOrders() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class ImportService {
                  /*~~(Manual migration: Spring rollback rules require semantic review to preserve Error behavior and rule precedence)~~>*/@Transactional(rollbackFor = java.io.IOException.class)
                  void importOrders() {
                  }
              }
              """
          ),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class RetryService {
                  @Transactional(noRollbackFor = java.io.IOException.class)
                  void retryOrders() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class RetryService {
                  /*~~(Manual migration: Spring rollback rules require semantic review to preserve Error behavior and rule precedence)~~>*/@Transactional(noRollbackFor = java.io.IOException.class)
                  void retryOrders() {
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesEveryTransactionAnnotationWhenOneClassOverrideIsUnsupported() {
        rewriteRun(
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

                  @Transactional(readOnly = true)
                  void summarizeOrders() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              /*~~(Manual migration: class contains unsupported Spring transaction semantics; no transaction annotations were changed)~~>*/@Transactional
              class OrderService {
                  void createOrder() {
                  }

                  /*~~(Manual migration: class contains unsupported Spring transaction semantics; no transaction annotations were changed)~~>*/@Transactional(readOnly = true)
                  void summarizeOrders() {
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesModuleWithNonDefaultGlobalRollbackPolicy() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.transaction.annotation.EnableTransactionManagement;
              import org.springframework.transaction.annotation.RollbackOn;

              @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
              class TransactionConfiguration {
              }
              """,
            source -> source.path(
                    "orders/src/main/java/com/example/orders/TransactionConfiguration.java")
          ),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional
                  void createOrder() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(Manual migration: transaction conversion was deferred because non-portable Spring transaction infrastructure is present in this migration scope)~~>*/@Transactional
                  void createOrder() {
                  }
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java")
          )
        );
    }

    @Test
    void refusesModuleWithAspectJTransactionMode() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.context.annotation.AdviceMode;
              import org.springframework.transaction.annotation.EnableTransactionManagement;

              @EnableTransactionManagement(mode = AdviceMode.ASPECTJ)
              class TransactionConfiguration {
              }
              """,
            source -> source.path(
                    "orders/src/main/java/com/example/orders/TransactionConfiguration.java")
          ),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional
                  void createOrder() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(Manual migration: transaction conversion was deferred because non-portable Spring transaction infrastructure is present in this migration scope)~~>*/@Transactional
                  void createOrder() {
                  }
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java")
          )
        );
    }

    @Test
    void refusesModuleWithReactiveTransactionManager() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.transaction.ReactiveTransactionManager;

              class TransactionConfiguration {
                  ReactiveTransactionManager transactionManager;
              }
              """,
            source -> source.path(
                    "orders/src/main/java/com/example/orders/TransactionConfiguration.java")
          ),
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional
                  void createOrder() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(Manual migration: transaction conversion was deferred because non-portable Spring transaction infrastructure is present in this migration scope)~~>*/@Transactional
                  void createOrder() {
                  }
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderService.java")
          )
        );
    }

    @Test
    void refusesTransactionScopeThatUsesUserTransaction() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.UserTransaction;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  UserTransaction userTransaction;

                  @Transactional
                  void createOrder() throws Exception {
                      userTransaction.begin();
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.transaction.UserTransaction;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  UserTransaction userTransaction;

                  /*~~(Manual migration: transaction conversion was deferred because non-portable Spring transaction infrastructure is present in this migration scope)~~>*/@Transactional
                  void createOrder() throws Exception {
                      userTransaction.begin();
                  }
              }
              """
          )
        );
    }

    @Test
    void marksSpringPropagationReadOnlyAndManagerSelection() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  @Transactional(
                          propagation = Propagation.REQUIRES_NEW,
                          readOnly = true,
                          transactionManager = "orders")
                  void summarizeOrders() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              class OrderService {
                  /*~~(Manual migration: Spring propagation, isolation, timeout, readOnly, labels, and transaction manager selection require semantic review)~~>*/@Transactional(
                          propagation = Propagation.REQUIRES_NEW,
                          readOnly = true,
                          transactionManager = "orders")
                  void summarizeOrders() {
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesSpringOnlyAndNonInterceptableTargets() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Singleton;
              import org.springframework.transaction.annotation.Transactional;

              class SpringOnlyService {
                  @Transactional
                  void createOrder() {
                  }
              }

              @Singleton
              class PseudoScopedService {
                  @Transactional
                  void createOrder() {
                  }
              }

              @ApplicationScoped
              final class FinalCdiService {
                  @Transactional
                  void createOrder() {
                  }
              }

              @ApplicationScoped
              class CdiService {
                  @Transactional
                  private void createOrder() {
                  }
              }

              @ApplicationScoped
              class ConstructorOnlyCdiService {
                  ConstructorOnlyCdiService(String dependency) {
                  }

                  @Transactional
                  void createOrder() {
                  }
              }

              class BaseService {
              }

              @ApplicationScoped
              class DerivedCdiService extends BaseService {
                  @Transactional
                  void createOrder() {
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Singleton;
              import org.springframework.transaction.annotation.Transactional;

              class SpringOnlyService {
                  /*~~(Manual migration: Jakarta @Transactional requires an enclosing CDI bean-defining annotation)~~>*/@Transactional
                  void createOrder() {
                  }
              }

              @Singleton
              class PseudoScopedService {
                  /*~~(Manual migration: Jakarta @Transactional requires an enclosing CDI bean-defining annotation)~~>*/@Transactional
                  void createOrder() {
                  }
              }

              @ApplicationScoped
              final class FinalCdiService {
                  /*~~(Manual migration: Jakarta @Transactional requires an interceptable CDI class and method)~~>*/@Transactional
                  void createOrder() {
                  }
              }

              @ApplicationScoped
              class CdiService {
                  /*~~(Manual migration: Jakarta @Transactional requires an interceptable CDI class and method)~~>*/@Transactional
                  private void createOrder() {
                  }
              }

              @ApplicationScoped
              class ConstructorOnlyCdiService {
                  ConstructorOnlyCdiService(String dependency) {
                  }

                  /*~~(Manual migration: Jakarta @Transactional requires an interceptable CDI class and method)~~>*/@Transactional
                  void createOrder() {
                  }
              }

              class BaseService {
              }

              @ApplicationScoped
              class DerivedCdiService extends BaseService {
                  /*~~(Manual migration: Jakarta @Transactional requires an interceptable CDI class and method)~~>*/@Transactional
                  void createOrder() {
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesTransactionMigrationInsideAnUnsafeSpringController() {
        rewriteRun(
          java(
            """
              package com.example.web;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class SearchController {
                  @GetMapping("/search")
                  @Transactional
                  public String search(String query) {
                      return query;
                  }
              }
              """,
            """
              package com.example.web;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class SearchController {
                  @GetMapping("/search")
                  /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@Transactional
                  public String search(String query) {
                      return query;
                  }
              }
              """
          )
        );
    }

    @Test
    void defersControllerTransactionsForModuleWideWebAndSecuritySemantics() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class OrderController {
                  @GetMapping("/orders")
                  @Transactional
                  public String orders() {
                      return "orders";
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class OrderController {
                  @GetMapping("/orders")
                  /*~~(Manual migration: transaction conversion was deferred because Spring Web or servlet runtime infrastructure is present in this migration scope)~~>*/@Transactional
                  public String orders() {
                      return "orders";
                  }
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderController.java")
          ),
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.ControllerAdvice;

              @ControllerAdvice
              class ErrorAdvice {}
              """,
            source -> source.path("orders/src/main/java/com/example/orders/ErrorAdvice.java")
          ),
          java(
            """
              package com.example.catalog;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class CatalogController {
                  @GetMapping("/catalog")
                  @Transactional
                  public String catalog() {
                      return "catalog";
                  }
              }
              """,
            """
              package com.example.catalog;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class CatalogController {
                  @GetMapping("/catalog")
                  /*~~(Manual migration: transaction conversion was deferred because Spring Security is present in this migration scope)~~>*/@Transactional
                  public String catalog() {
                      return "catalog";
                  }
              }
              """,
            source -> source.path("catalog/src/main/java/com/example/catalog/CatalogController.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.security.web.SecurityFilterChain;

              class SecurityConfiguration {
                  SecurityFilterChain filterChain;
              }
              """,
            source -> source.path("catalog/src/main/java/com/example/catalog/SecurityConfiguration.java")
          ),
          java(
            """
              package com.example.inventory;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class InventoryController {
                  @GetMapping("/inventory")
                  @Transactional
                  public String inventory() {
                      return "inventory";
                  }
              }
              """,
            """
              package com.example.inventory;

              import jakarta.enterprise.context.ApplicationScoped;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @ApplicationScoped
              @RestController
              public class InventoryController {
                  @GetMapping("/inventory")
                  @jakarta.transaction.Transactional(rollbackOn = Error.class)
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

              import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

              class TestWebConfiguration implements WebMvcConfigurer {}
              """,
            source -> source.path("inventory/src/test/java/com/example/inventory/TestWebConfiguration.java")
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
