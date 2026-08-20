package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.Recipe;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static org.openrewrite.java.Assertions.java;

class MigrateResponseEntityToJakartaResponseTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateResponseEntityToJakartaResponse())
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-web", "spring-core", "jakarta.ws.rs-api")
                        .dependsOn(
                                """
                                  package org.springframework.security.web;
                                  public interface SecurityFilterChain {}
                                  """))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void migratesCommonResponseFactoriesStatusesAndGenericReturnTypes() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import java.net.URI;
              import jakarta.ws.rs.Path;
              import org.springframework.http.HttpStatus;
              import org.springframework.http.ResponseEntity;

              @Path("/orders")
              public class OrderEndpoint {
                  public ResponseEntity<String> ok(String body) {
                      return ResponseEntity.ok(body);
                  }

                  public ResponseEntity<String> created(String body) {
                      return ResponseEntity.status(HttpStatus.CREATED).body(body);
                  }

                  public ResponseEntity<String> rejected(String body) {
                      return ResponseEntity.badRequest().body(body);
                  }

                  public ResponseEntity<Void> missing() {
                      return ResponseEntity.notFound().build();
                  }

                  public ResponseEntity<String> accepted(String body) {
                      return ResponseEntity.accepted().body(body);
                  }

                  public ResponseEntity<String> located(URI location, String body) {
                      return ResponseEntity.created(location).body(body);
                  }
              }
              """,
            """
              package com.example.orders;

              import java.net.URI;
              import jakarta.ws.rs.Path;
              import jakarta.ws.rs.core.Response;

              @Path("/orders")
              public class OrderEndpoint {
                  public Response ok(String body) {
                      return Response.ok(body).build();
                  }

                  public Response created(String body) {
                      return Response.status(201).entity(body).build();
                  }

                  public Response rejected(String body) {
                      return Response.status(400).entity(body).build();
                  }

                  public Response missing() {
                      return Response.status(404).build();
                  }

                  public Response accepted(String body) {
                      return Response.status(202).entity(body).build();
                  }

                  public Response located(URI location, String body) {
                      return Response.created(location).entity(body).build();
                  }
              }
              """
          )
        );
    }

    @Test
    void defersSupportedResponseWhenItsSpringControllerCannotMigrateAtomically() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              class OrderEndpoint {
                  @GetMapping("/v1/orders")
                  ResponseEntity<String> get(String view) {
                      return ResponseEntity.ok(view);
                  }
              }
              """,
            """
              package com.example.orders;

              /*~~(Manual migration: ResponseEntity conversion was deferred because this file contains a Spring MVC controller that cannot migrate atomically)~~>*/import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              class OrderEndpoint {
                  @GetMapping("/v1/orders")
                  ResponseEntity<String> get(String view) {
                      return ResponseEntity.ok(view);
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesSupportedResponseEntityInANonControllerService() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.http.ResponseEntity;

              public class OrderClient {
                  public ResponseEntity<String> fetch() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """,
            """
              package com.example.orders;

              /*~~(Manual migration: ResponseEntity conversion is limited to a proven Spring MVC or Jakarta REST resource; no response types were changed)~~>*/import org.springframework.http.ResponseEntity;

              public class OrderClient {
                  public ResponseEntity<String> fetch() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """
          )
        );
    }

    @Test
    void defersResponseMigrationWhenSeparateSourceUsesSpringSecurity() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  public ResponseEntity<String> get() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """,
            """
              package com.example.orders;

              /*~~(Manual migration: ResponseEntity conversion was deferred because Spring Security is present in this migration scope)~~>*/import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  public ResponseEntity<String> get() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """
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
              """
          )
        );
    }

    @Test
    void scopesSpringWebInfrastructureBlockingToItsModule() {
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

              /*~~(Manual migration: ResponseEntity conversion was deferred because Spring Web or servlet runtime infrastructure is present in this migration scope)~~>*/import org.springframework.http.ResponseEntity;
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
            source -> source.path("module-a/src/main/java/com/example/orders/OrderEndpoint.java")
          ),
          java(
            """
              package com.example.web;

              import org.springframework.web.bind.annotation.ControllerAdvice;

              @ControllerAdvice
              class ErrorAdvice {}
              """,
            source -> source.path("module-a/src/main/java/com/example/web/ErrorAdvice.java")
          ),
          java(
            """
              package com.example.catalog;

              import jakarta.ws.rs.Path;
              import org.springframework.http.ResponseEntity;

              @Path("/catalog")
              public class CatalogEndpoint {
                  public ResponseEntity<String> get() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """,
            """
              package com.example.catalog;

              import jakarta.ws.rs.Path;
              import jakarta.ws.rs.core.Response;

              @Path("/catalog")
              public class CatalogEndpoint {
                  public Response get() {
                      return Response.ok("ok").build();
                  }
              }
              """,
            source -> source.path("module-b/src/main/java/com/example/catalog/CatalogEndpoint.java")
          )
        );
    }

    @Test
    void responseAndMvcRecipesShareModuleScopedPathConstants() {
        rewriteRun(
          spec -> spec.recipe(new ResponseThenMvc()),
          java(
            """
              package com.example.routes;

              public final class Routes {
                  public static final String ROOT = "/v1/orders";
                  public static final String ITEM = "/{id}";
              }
              """,
            source -> source.path("orders/src/main/java/com/example/routes/Routes.java")
          ),
          java(
            """
              package com.example.orders;

              import com.example.routes.Routes;
              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.PathVariable;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping(Routes.ROOT)
              public class OrderEndpoint {
                  @GetMapping(Routes.ITEM)
                  public ResponseEntity<String> get(@PathVariable("id") String id) {
                      return ResponseEntity.ok(id);
                  }
              }
              """,
            """
              package com.example.orders;

              import com.example.routes.Routes;
              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;
              import jakarta.ws.rs.PathParam;
              import jakarta.ws.rs.core.Response;

              @ApplicationScoped
              @Path(Routes.ROOT)
              public class OrderEndpoint {
                  @GET
                  @Path(Routes.ITEM)
                  public Response get(@PathParam("id") String id) {
                      return Response.ok(id).build();
                  }
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderEndpoint.java")
          )
        );
    }

    @Test
    void refusesTheWholeCompilationUnitWhenADynamicOrHeaderChainRemains() {
        rewriteRun(
          java(
            """
              package com.acme.router.rest.v1;

              import org.springframework.http.HttpHeaders;
              import org.springframework.http.HttpStatusCode;
              import org.springframework.http.ResponseEntity;

              class FileRouterRestService {
                  ResponseEntity<String> health() {
                      return ResponseEntity.ok("running");
                  }

                  ResponseEntity<byte[]> download(
                          HttpStatusCode status, HttpHeaders headers, byte[] body) {
                      return ResponseEntity.status(status).headers(headers).body(body);
                  }
              }
              """,
            """
              package com.acme.router.rest.v1;

              import org.springframework.http.HttpHeaders;
              import org.springframework.http.HttpStatusCode;
              /*~~(Manual migration: this file mixes ResponseEntity with unsupported APIs; no response types were changed)~~>*/import org.springframework.http.ResponseEntity;

              class FileRouterRestService {
                  ResponseEntity<String> health() {
                      return ResponseEntity.ok("running");
                  }

                  ResponseEntity<byte[]> download(
                          HttpStatusCode status, HttpHeaders headers, byte[] body) {
                      return ResponseEntity.status(status).headers(headers).body(body);
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesMemberReferencesSubclassesAndNonHttpStatusConstants() {
        rewriteRun(
          java(
            """
              package com.example.responses;

              import java.util.function.Function;
              import org.springframework.http.HttpStatusCode;
              import org.springframework.http.ResponseEntity;

              class ResponsePatterns {
                  static final HttpStatusCode NOT_FOUND = HttpStatusCode.valueOf(599);
                  Function<String, ResponseEntity<String>> factory = ResponseEntity::ok;

                  ResponseEntity<Void> customStatus() {
                      return ResponseEntity.status(NOT_FOUND).build();
                  }
              }

              class SpecializedResponse extends ResponseEntity<String> {
                  SpecializedResponse() {
                      super(HttpStatusCode.valueOf(200));
                  }
              }
              """,
            """
              package com.example.responses;

              import java.util.function.Function;
              import org.springframework.http.HttpStatusCode;
              /*~~(Manual migration: this file mixes ResponseEntity with unsupported APIs; no response types were changed)~~>*/import org.springframework.http.ResponseEntity;

              class ResponsePatterns {
                  static final HttpStatusCode NOT_FOUND = HttpStatusCode.valueOf(599);
                  Function<String, ResponseEntity<String>> factory = ResponseEntity::ok;

                  ResponseEntity<Void> customStatus() {
                      return ResponseEntity.status(NOT_FOUND).build();
                  }
              }

              class SpecializedResponse extends ResponseEntity<String> {
                  SpecializedResponse() {
                      super(HttpStatusCode.valueOf(200));
                  }
              }
              """
          )
        );
    }

    static class ResponseThenMvc extends Recipe {
        @Override
        public String getDisplayName() {
            return "Test response then MVC migration";
        }

        @Override
        public String getDescription() {
            return "Runs response conversion before MVC conversion.";
        }

        @Override
        public List<Recipe> getRecipeList() {
            return List.of(new MigrateResponseEntityToJakartaResponse(),
                    new MigrateSpringMvcToJakartaRest());
        }
    }
}
