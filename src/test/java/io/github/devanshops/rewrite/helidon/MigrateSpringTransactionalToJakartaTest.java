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
                .parser(JavaParser.fromJavaVersion().classpath("spring-tx"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void preservesAndMarksBareTransactional() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.transaction.annotation.Transactional;

              class OrderService {
                  @Transactional
                  void createOrder() {
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.transaction.annotation.Transactional;

              class OrderService {
                  /*~~(Manual migration: Spring transaction semantics require explicit Jakarta Transactions review)~~>*/@Transactional
                  void createOrder() {
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesAndMarksSpringSpecificAttributes() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import java.io.IOException;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              class OrderService {
                  @Transactional(
                          propagation = Propagation.REQUIRES_NEW,
                          readOnly = true,
                          rollbackFor = IOException.class,
                          transactionManager = "orders")
                  void importOrders() {
                  }
              }
              """,
            """
              package com.example.orders;

              import java.io.IOException;
              import org.springframework.transaction.annotation.Propagation;
              import org.springframework.transaction.annotation.Transactional;

              class OrderService {
                  /*~~(Manual migration: Spring transaction semantics require explicit Jakarta Transactions review)~~>*/@Transactional(
                          propagation = Propagation.REQUIRES_NEW,
                          readOnly = true,
                          rollbackFor = IOException.class,
                          transactionManager = "orders")
                  void importOrders() {
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesAndMarksClassAndMethodTransactionsIndependently() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.transaction.annotation.Transactional;

              @Transactional
              class OrderService {
                  @Transactional(timeout = 10)
                  void createOrder() {
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.transaction.annotation.Transactional;

              /*~~(Manual migration: Spring transaction semantics require explicit Jakarta Transactions review)~~>*/@Transactional
              class OrderService {
                  /*~~(Manual migration: Spring transaction semantics require explicit Jakarta Transactions review)~~>*/@Transactional(timeout = 10)
                  void createOrder() {
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesAndMarksTransactionsInTestSources() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.transaction.annotation.Transactional;

              @Transactional
              class OrderRepositoryTest {
              }
              """,
            """
              package com.example.orders;

              import org.springframework.transaction.annotation.Transactional;

              /*~~(Manual migration: Spring transaction semantics require explicit Jakarta Transactions review)~~>*/@Transactional
              class OrderRepositoryTest {
              }
              """,
            source -> source.path("src/test/java/com/example/orders/OrderRepositoryTest.java")
          )
        );
    }

    @Test
    void preservesAndMarksFullyQualifiedAndComposedTransactions() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import org.springframework.transaction.annotation.Transactional;

              @Target({ElementType.TYPE, ElementType.METHOD})
              @Retention(RetentionPolicy.RUNTIME)
              @Transactional(readOnly = true)
              @interface ReadOnlyTransaction {
              }

              @ReadOnlyTransaction
              class QueryService {
                  @org.springframework.transaction.annotation.Transactional
                  void refresh() {
                  }
              }
              """,
            """
              package com.example.orders;

              import java.lang.annotation.ElementType;
              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;
              import org.springframework.transaction.annotation.Transactional;

              @Target({ElementType.TYPE, ElementType.METHOD})
              @Retention(RetentionPolicy.RUNTIME)
              /*~~(Manual migration: Spring transaction semantics require explicit Jakarta Transactions review)~~>*/@Transactional(readOnly = true)
              @interface ReadOnlyTransaction {
              }

              @ReadOnlyTransaction
              class QueryService {
                  /*~~(Manual migration: Spring transaction semantics require explicit Jakarta Transactions review)~~>*/@org.springframework.transaction.annotation.Transactional
                  void refresh() {
                  }
              }
              """
          )
        );
    }
}
