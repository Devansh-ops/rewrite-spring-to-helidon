package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

public class MigrateResponseEntityToJakartaResponseTest implements RewriteTest {

    private static final String MARKER = MigrateResponseEntityToJakartaResponse.MANUAL_MIGRATION;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateResponseEntityToJakartaResponse())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-web", "spring-core", "jakarta.ws.rs-api"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void preservesSimpleResponseEntityAndMarksItsSourceFile() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public ResponseEntity<String> get() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """,
            """
              package com.example.orders;

              /*~~({{marker}})~~>*/import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public /*~~({{marker}})~~>*/ResponseEntity<String> get() {
                      return /*~~({{marker}})~~>*/ResponseEntity.ok("ok");
                  }
              }
              """.replace("{{marker}}", MARKER)
          )
        );
    }

    @Test
    void preservesUnsafeStatusesLocationsGenericEntitiesAndNullBranches() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import java.net.URI;
              import java.util.List;
              import jakarta.ws.rs.Path;
              import org.springframework.http.ResponseEntity;

              @Path("/orders")
              public class OrderEndpoint {
                  public ResponseEntity<String> dynamicStatus(int status) {
                      return ResponseEntity.status(status).body("dynamic");
                  }

                  public ResponseEntity<String> outOfRangeStatus() {
                      return ResponseEntity.status(700).body("extension");
                  }

                  public ResponseEntity<String> created(URI location) {
                      return ResponseEntity.created(location).body("created");
                  }

                  public ResponseEntity<List<Order>> genericEntity(List<Order> orders) {
                      return ResponseEntity.ok(orders);
                  }

                  public ResponseEntity<String> literalNullBranch(String value) {
                      return value == null ? ResponseEntity.ok(null) : ResponseEntity.ok(value);
                  }
              }

              class Order {}
              """,
            """
              package com.example.orders;

              import java.net.URI;
              import java.util.List;
              import jakarta.ws.rs.Path;
              /*~~({{marker}})~~>*/import org.springframework.http.ResponseEntity;

              @Path("/orders")
              public class OrderEndpoint {
                  public /*~~({{marker}})~~>*/ResponseEntity<String> dynamicStatus(int status) {
                      return /*~~({{marker}})~~>*/ResponseEntity.status(status).body("dynamic");
                  }

                  public /*~~({{marker}})~~>*/ResponseEntity<String> outOfRangeStatus() {
                      return /*~~({{marker}})~~>*/ResponseEntity.status(700).body("extension");
                  }

                  public /*~~({{marker}})~~>*/ResponseEntity<String> created(URI location) {
                      return /*~~({{marker}})~~>*/ResponseEntity.created(location).body("created");
                  }

                  public /*~~({{marker}})~~>*/ResponseEntity<List<Order>> genericEntity(List<Order> orders) {
                      return /*~~({{marker}})~~>*/ResponseEntity.ok(orders);
                  }

                  public /*~~({{marker}})~~>*/ResponseEntity<String> literalNullBranch(String value) {
                      return value == null ? /*~~({{marker}})~~>*/ResponseEntity.ok(null) : /*~~({{marker}})~~>*/ResponseEntity.ok(value);
                  }
              }

              class Order {}
              """.replace("{{marker}}", MARKER)
          )
        );
    }

    @Test
    void preservesResponseContractsAcrossInterfaceImplementationCallerAndHelper() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.http.ResponseEntity;

              public interface OrderContract {
                  ResponseEntity<String> get();
              }
              """,
            """
              package com.example.orders;

              /*~~({{marker}})~~>*/import org.springframework.http.ResponseEntity;

              public interface OrderContract {
                  /*~~({{marker}})~~>*/ResponseEntity<String> get();
              }
              """.replace("{{marker}}", MARKER),
            source -> source.path("api/src/main/java/com/example/orders/OrderContract.java")
          ),
          java(
            """
              package com.example.orders;

              import org.springframework.http.ResponseEntity;

              public class OrderEndpoint implements OrderContract {
                  @Override
                  public ResponseEntity<String> get() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """,
            """
              package com.example.orders;

              /*~~({{marker}})~~>*/import org.springframework.http.ResponseEntity;

              public class OrderEndpoint implements OrderContract {
                  @Override
                  public /*~~({{marker}})~~>*/ResponseEntity<String> get() {
                      return /*~~({{marker}})~~>*/ResponseEntity.ok("ok");
                  }
              }
              """.replace("{{marker}}", MARKER),
            source -> source.path("service/src/main/java/com/example/orders/OrderEndpoint.java")
          ),
          java(
            """
              package com.example.orders;

              import org.springframework.http.ResponseEntity;

              class OrderCaller {
                  ResponseEntity<String> call(OrderContract endpoint) {
                      return endpoint.get();
                  }
              }
              """,
            """
              package com.example.orders;

              /*~~({{marker}})~~>*/import org.springframework.http.ResponseEntity;

              class OrderCaller {
                  /*~~({{marker}})~~>*/ResponseEntity<String> call(OrderContract endpoint) {
                      return endpoint.get();
                  }
              }
              """.replace("{{marker}}", MARKER),
            source -> source.path("service/src/main/java/com/example/orders/OrderCaller.java")
          ),
          java(
            """
              package com.example.orders;

              import org.springframework.http.ResponseEntity;

              class OrderResponseHelper {
                  ResponseEntity<String> build() {
                      return ResponseEntity.ok("helper-value");
                  }
              }
              """,
            """
              package com.example.orders;

              /*~~({{marker}})~~>*/import org.springframework.http.ResponseEntity;

              class OrderResponseHelper {
                  /*~~({{marker}})~~>*/ResponseEntity<String> build() {
                      return /*~~({{marker}})~~>*/ResponseEntity.ok("helper-value");
                  }
              }
              """.replace("{{marker}}", MARKER),
            source -> source.path("service/src/main/java/com/example/orders/OrderResponseHelper.java")
          )
        );
    }

    @Test
    void marksFullyQualifiedResponseEntityUseWithoutChangingIt() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              class OrderClient {
                  org.springframework.http.ResponseEntity<String> fetch() {
                      return org.springframework.http.ResponseEntity.ok("ok");
                  }
              }
              """,
            """
              package com.example.orders;

              class OrderClient {
                  org.springframework.http./*~~({{marker}})~~>*/ResponseEntity<String> fetch() {
                      return org.springframework.http./*~~({{marker}})~~>*/ResponseEntity.ok("ok");
                  }
              }
              """.replace("{{marker}}", MARKER)
          )
        );
    }
}
