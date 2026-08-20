package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateSpringMvcToJakartaRestTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringMvcToJakartaRest())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-web", "spring-beans", "spring-tx", "jakarta.enterprise.cdi-api",
                                "jakarta.inject-api", "jakarta.ws.rs-api")
                        .dependsOn(
                                """
                                  package org.springframework.security.access.prepost;
                                  public @interface PreAuthorize {
                                      String value();
                                  }
                                  """,
                                """
                                  package org.springframework.security.web;
                                  public interface SecurityFilterChain {}
                                  """,
                                """
                                  package com.example.security;
                                  import org.springframework.security.access.prepost.PreAuthorize;
                                  @PreAuthorize("hasAuthority('order:read')")
                                  public @interface ReadOrders {}
                                  """,
                                """
                                  package com.example.contract;
                                  public interface ManagedEndpoint
                                          extends org.springframework.beans.factory.InitializingBean {}
                                  """,
                                """
                                  package lombok;
                                  public @interface AllArgsConstructor {}
                                  """,
                                """
                                package org.springframework.web.servlet.mvc.method.annotation;
                                  public interface StreamingResponseBody {
                                      void writeTo(java.io.OutputStream outputStream) throws java.io.IOException;
                                  }
                                  """,
                                """
                                  package org.springframework.web.servlet.config.annotation;
                                  public interface WebMvcConfigurer {}
                                  """,
                                """
                                  package com.example.web;
                                  public interface CustomMvcConfigurer
                                          extends org.springframework.web.servlet.config.annotation.WebMvcConfigurer {}
                                  """,
                                """
                                  package com.example.web;
                                  @org.springframework.web.bind.annotation.ControllerAdvice
                                  public @interface GlobalPolicy {}
                                  """,
                                """
                                  package jakarta.servlet;
                                  public interface Filter {}
                                  """))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void migratesSafeControllerBindingsAndConstantPaths() {
        rewriteRun(
          java(
            """
              package com.example.catalog.rest.v1;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.PathVariable;
              import org.springframework.web.bind.annotation.PostMapping;
              import org.springframework.web.bind.annotation.RequestBody;
              import org.springframework.web.bind.annotation.RequestHeader;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              class Routes {
                  static final String ROOT = "/v1/dataset-service";
                  static final String DATASET = "/datasets/{id}";
                  static final String USER = "X-Remote-User";
              }

              @RestController
              @RequestMapping(Routes.ROOT)
              public class DatasetRestService {
                  @GetMapping(Routes.DATASET)
                  public String dataset(
                          @PathVariable("id") String id,
                          @RequestParam(name = "version", required = false) String version,
                          @RequestParam(name = "limit", required = false) Integer limit,
                          @RequestHeader(name = Routes.USER, required = false) String user) {
                      return id + version + limit + user;
                  }

                  @PostMapping("/preview")
                  public String preview(@RequestBody(required = false) Object body) {
                      return String.valueOf(body);
                  }
              }
              """,
            """
              package com.example.catalog.rest.v1;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.*;

              class Routes {
                  static final String ROOT = "/v1/dataset-service";
                  static final String DATASET = "/datasets/{id}";
                  static final String USER = "X-Remote-User";
              }

              @ApplicationScoped
              @Path(Routes.ROOT)
              public class DatasetRestService {
                  @GET
                  @Path(Routes.DATASET)
                  public String dataset(
                          @PathParam("id") String id,
                          @QueryParam("version") String version,
                          @QueryParam("limit") Integer limit,
                          @HeaderParam(Routes.USER) String user) {
                      return id + version + limit + user;
                  }

                  @POST
                  @Path("/preview")
                  public String preview(Object body) {
                      return String.valueOf(body);
                  }
              }
              """
          )
        );
    }

    @Test
    void composesPortableLiteralClassAndTemplateMethodPaths() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.PathVariable;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping("/{orderId}")
                  public String get(@PathVariable("orderId") String orderId) {
                      return orderId;
                  }
              }
              """,
            """
              package com.example;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;
              import jakarta.ws.rs.PathParam;

              @ApplicationScoped
              @Path("/v1/orders")
              public class OrderEndpoint {
                  @GET
                  @Path("/{orderId}")
                  public String get(@PathParam("orderId") String orderId) {
                      return orderId;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesPropertyPlaceholderInClassPathAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("${orders.api-root}")
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller uses Spring-only or unverified path syntax; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("${orders.api-root}")
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesSpringOnlyPathSyntaxHiddenBehindACompileTimeConstant() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              class Routes {
                  static final String ROOT = "${orders.api-root}";
              }

              @RestController
              @RequestMapping(Routes.ROOT)
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              class Routes {
                  static final String ROOT = "${orders.api-root}";
              }

              /*~~(Manual migration: controller uses Spring-only or unverified path syntax; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping(Routes.ROOT)
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesAntWildcardAndPatternSegmentsInMethodPathsAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/files")
              public class FileEndpoint {
                  @GetMapping("/**")
                  public String all() {
                      return "all";
                  }

                  @GetMapping("/search/?")
                  public String search() {
                      return "search";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller uses Spring-only or unverified path syntax; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/files")
              public class FileEndpoint {
                  @GetMapping("/**")
                  public String all() {
                      return "all";
                  }

                  @GetMapping("/search/?")
                  public String search() {
                      return "search";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesUnverifiedRegexTemplateSyntaxAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.PathVariable;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping("/{orderId:[0-9]+}")
                  public String get(@PathVariable("orderId") String orderId) {
                      return orderId;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.PathVariable;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller uses Spring-only or unverified path syntax; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping("/{orderId:[0-9]+}")
                  public String get(@PathVariable("orderId") String orderId) {
                      return orderId;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesAllControllersWhenSeparateSourceUsesSpringSecurity() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: Spring Security is present in this migration scope; preserve global filters and endpoint authorization before converting Spring MVC annotations)~~>*/@RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  public String get() {
                      return "ok";
                  }
              }
              """
          ),
          java(
            """
              package com.example;

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
    void scopesSpringSecurityBlockingToTheOwningModule() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: Spring Security is present in this migration scope; preserve global filters and endpoint authorization before converting Spring MVC annotations)~~>*/@RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  public String get() {
                      return "ok";
                  }
              }
              """,
            source -> source.path("module-a/src/main/java/com/example/orders/OrderEndpoint.java")
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
              """,
            source -> source.path("module-a/src/main/java/com/example/security/SecurityConfiguration.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/catalog")
              public class CatalogEndpoint {
                  @GetMapping
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.catalog;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;

              @ApplicationScoped
              @Path("/v1/catalog")
              public class CatalogEndpoint {
                  @GET
                  public String get() {
                      return "ok";
                  }
              }
              """,
            source -> source.path("module-b/src/main/java/com/example/catalog/CatalogEndpoint.java")
          )
        );
    }

    @Test
    void resolvedMavenSecurityDependencyBlocksOnlyItsModule() {
        rewriteRun(
          pomXml(
            """
              <project xmlns="http://maven.apache.org/POM/4.0.0"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>secured-service</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework.security</groupId>
                          <artifactId>spring-security-core</artifactId>
                          <version>7.1.0</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            source -> source.path("module-a/pom.xml")
                    .afterRecipe(document -> assertThat(document.getMarkers()
                            .findFirst(MavenResolutionResult.class)).isPresent())
          ),
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class SecuredEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: Spring Security is present in this migration scope; preserve global filters and endpoint authorization before converting Spring MVC annotations)~~>*/@RestController
              public class SecuredEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            source -> source.path("module-a/src/main/java/com/example/orders/SecuredEndpoint.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class CatalogEndpoint {
                  @GetMapping("/catalog")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.catalog;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;

              @ApplicationScoped
              @Path("/")
              public class CatalogEndpoint {
                  @GET
                  @Path("/catalog")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            source -> source.path("module-b/src/main/java/com/example/catalog/CatalogEndpoint.java")
          )
        );
    }

    @Test
    void ignoresTestOnlyMavenSecurityDependency() {
        rewriteRun(
          pomXml(
            """
              <project xmlns="http://maven.apache.org/POM/4.0.0"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders-service</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework.security</groupId>
                          <artifactId>spring-security-core</artifactId>
                          <version>7.1.0</version>
                          <scope>test</scope>
                      </dependency>
                  </dependencies>
              </project>
              """,
            source -> source.path("orders/pom.xml")
                    .afterRecipe(document -> assertThat(document.getMarkers()
                            .findFirst(MavenResolutionResult.class)).isPresent())
          ),
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;

              @ApplicationScoped
              @Path("/")
              public class OrderEndpoint {
                  @GET
                  @Path("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderEndpoint.java")
          )
        );
    }

    @Test
    void ignoresSpringWebAndSecurityUsageInTestSources() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;

              @ApplicationScoped
              @Path("/")
              public class OrderEndpoint {
                  @GET
                  @Path("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            source -> source.path("orders/src/main/java/com/example/orders/OrderEndpoint.java")
          ),
          java(
            """
              package com.example.orders;

              import org.springframework.http.MediaType;
              import org.springframework.security.web.SecurityFilterChain;

              class OrderEndpointTest {
                  MediaType mediaType;
                  SecurityFilterChain testFilterChain;
              }
              """,
            source -> source.path("orders/src/test/java/com/example/orders/OrderEndpointTest.java")
          )
        );
    }

    @Test
    void scopesSpringWebInfrastructureBlockingToTheOwningModule() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: Spring Web or servlet runtime infrastructure is present in this migration scope; preserve filters, advice, exception, and request/response semantics before converting Spring MVC annotations)~~>*/@RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
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

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class CatalogEndpoint {
                  @GetMapping("/catalog")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.catalog;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;

              @ApplicationScoped
              @Path("/")
              public class CatalogEndpoint {
                  @GET
                  @Path("/catalog")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            source -> source.path("module-b/src/main/java/com/example/catalog/CatalogEndpoint.java")
          )
        );
    }

    @Test
    void blocksComposedAdviceAndCustomMvcConfigurerContracts() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: Spring Web or servlet runtime infrastructure is present in this migration scope; preserve filters, advice, exception, and request/response semantics before converting Spring MVC annotations)~~>*/@RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """
          ),
          java(
            """
              package com.example.web;

              @GlobalPolicy
              class ProjectAdvice {}

              class ProjectMvcConfiguration implements CustomMvcConfigurer {}
              """
          )
        );
    }

    @Test
    void blocksSpringWebTypesInFieldsBodiesAndServletContracts() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.orders;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: Spring Web or servlet runtime infrastructure is present in this migration scope; preserve filters, advice, exception, and request/response semantics before converting Spring MVC annotations)~~>*/@RestController
              public class OrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }
              """
          ),
          java(
            """
              package com.example.web;

              import jakarta.servlet.Filter;
              import org.springframework.http.HttpEntity;
              import org.springframework.http.HttpStatus;
              import org.springframework.web.server.ResponseStatusException;

              class RuntimeWebPolicy implements Filter {
                  HttpEntity<String> upstream;

                  RuntimeException rejected() {
                      return new ResponseStatusException(HttpStatus.BAD_REQUEST);
                  }
              }
              """
          )
        );
    }

    @Test
    void scopesIdenticallyNamedPathConstantsToTheirOwningModule() {
        InMemoryExecutionContext context = new InMemoryExecutionContext();
        J.CompilationUnit moduleARoutes = (J.CompilationUnit) JavaParser.fromJavaVersion().build().parse(context,
                "package com.example; public final class Routes { " +
                "public static final String ROOT = \"/v1/orders\"; }")
                .findFirst().orElseThrow();
        moduleARoutes = moduleARoutes.withSourcePath(
                Paths.get("module-a/src/main/java/com/example/Routes.java"));
        J.CompilationUnit moduleBRoutes = (J.CompilationUnit) JavaParser.fromJavaVersion().build().parse(context,
                "package com.example; public final class Routes { " +
                "public static final String ROOT = \"${orders.api-root}\"; }")
                .findFirst().orElseThrow();
        moduleBRoutes = moduleBRoutes.withSourcePath(
                Paths.get("module-b/src/main/java/com/example/Routes.java"));

        String useSource = "package com.example.orders; import com.example.Routes; " +
                           "class Use { String value = Routes.ROOT; }";
        J.CompilationUnit moduleAUse = (J.CompilationUnit) JavaParser.fromJavaVersion().build()
                .parse(context, useSource)
                .findFirst().orElseThrow();
        moduleAUse = moduleAUse.withSourcePath(
                Paths.get("module-a/src/main/java/com/example/orders/Use.java"));
        J.CompilationUnit moduleBUse = (J.CompilationUnit) JavaParser.fromJavaVersion().build()
                .parse(context, useSource)
                .findFirst().orElseThrow();
        moduleBUse = moduleBUse.withSourcePath(
                Paths.get("module-b/src/main/java/com/example/orders/Use.java"));

        StringConstantProjectIndex.State constants = StringConstantProjectIndex.newAccumulator();
        StringConstantProjectIndex.scanSource(moduleARoutes, constants);
        StringConstantProjectIndex.scanSource(moduleBRoutes, constants);

        assertThat(StringConstantProjectIndex.resolve(initializer(moduleAUse), constants,
                moduleAUse.getSourcePath(), moduleAUse)).isEqualTo("/v1/orders");
        assertThat(StringConstantProjectIndex.resolve(initializer(moduleBUse), constants,
                moduleBUse.getSourcePath(), moduleBUse)).isEqualTo("${orders.api-root}");
    }

    @Test
    void refusesSoleParameterizedControllerConstructorForPortableCdiProxyability() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  private final OrderService service;

                  OrderEndpoint(OrderService service) {
                      this.service = service;
                  }

                  @GetMapping
                  String get(@RequestParam(required = false) String view) {
                      return service.find(view);
                  }
              }

              class OrderService {
                  String find(String view) {
                      return view;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller has no public no-argument constructor required for a portable CDI ApplicationScoped proxy; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  private final OrderService service;

                  OrderEndpoint(OrderService service) {
                      this.service = service;
                  }

                  @GetMapping
                  String get(@RequestParam(required = false) String view) {
                      return service.find(view);
                  }
              }

              class OrderService {
                  String find(String view) {
                      return view;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesUnsafeSoleParameterizedControllerConstructor() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  private final String prefix;

                  private OrderEndpoint(String prefix) {
                      this.prefix = prefix;
                  }

                  @GetMapping
                  String get() {
                      return prefix;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller has no public no-argument constructor required for a portable CDI ApplicationScoped proxy; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  private final String prefix;

                  private OrderEndpoint(String prefix) {
                      this.prefix = prefix;
                  }

                  @GetMapping
                  String get() {
                      return prefix;
                  }
              }
              """
          )
        );
    }

    @Test
    void migratesPublicTopLevelControllerWithExplicitPublicNoArgConstructor() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  public OrderEndpoint() {
                  }

                  @GetMapping
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;

              @ApplicationScoped
              @Path("/v1/orders")
              public class OrderEndpoint {
                  public OrderEndpoint() {
                  }

                  @GET
                  public String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesUnannotatedMappedParameterAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  String get(String view) {
                      return view;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  String get(String view) {
                      return view;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesRequiredRequestParamAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping("/{id}")
                  String get(@RequestParam("view") String view) {
                      return view;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping("/{id}")
                  String get(@RequestParam("view") String view) {
                      return view;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesRequiredRequestHeaderAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestHeader;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping("/{id}")
                  String get(@RequestHeader("X-Tenant") String tenant) {
                      return tenant;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestHeader;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping("/{id}")
                  String get(@RequestHeader("X-Tenant") String tenant) {
                      return tenant;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesRequiredRequestBodyAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.PostMapping;
              import org.springframework.web.bind.annotation.RequestBody;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @PostMapping
                  String create(@RequestBody Object body) {
                      return String.valueOf(body);
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.PostMapping;
              import org.springframework.web.bind.annotation.RequestBody;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @PostMapping
                  String create(@RequestBody Object body) {
                      return String.valueOf(body);
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesSpringDefaultValueBecauseEmptyValueSemanticsDiffer() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  String get(@RequestParam(defaultValue = "all") String view) {
                      return view;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  String get(@RequestParam(defaultValue = "all") String view) {
                      return view;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesBindingsWhoseSpringAndJakartaConversionRulesMayDiffer() {
        rewriteRun(
          java(
            """
              package com.example;

              import java.time.Instant;
              import java.util.List;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestHeader;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  String get(
                          @RequestParam(required = false) List<String> views,
                          @RequestHeader(required = false) Instant requestedAt) {
                      return String.valueOf(views) + requestedAt;
                  }
              }
              """,
            """
              package com.example;

              import java.time.Instant;
              import java.util.List;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestHeader;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  String get(
                          @RequestParam(required = false) List<String> views,
                          @RequestHeader(required = false) Instant requestedAt) {
                      return String.valueOf(views) + requestedAt;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesMixedSupportedAndUnsupportedMethodsAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.PathVariable;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping("/{id}")
                  public String get(@PathVariable("id") String id) {
                      return id;
                  }

                  @GetMapping(path = "/export", produces = "application/octet-stream")
                  public byte[] export(@RequestParam(required = false) String format) {
                      return new byte[0];
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.PathVariable;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping("/{id}")
                  public String get(@PathVariable("id") String id) {
                      return id;
                  }

                  @GetMapping(path = "/export", produces = "application/octet-stream")
                  public byte[] export(@RequestParam(required = false) String format) {
                      return new byte[0];
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesUnsupportedSpringMvcClassAndMethodAnnotations() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.http.HttpStatus;
              import org.springframework.web.bind.annotation.CrossOrigin;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.ResponseStatus;
              import org.springframework.web.bind.annotation.RestController;

              @CrossOrigin("https://example.invalid")
              @RestController
              @RequestMapping("/v1/cors")
              class CorsEndpoint {
                  @GetMapping
                  String get() {
                      return "ok";
                  }
              }

              @RestController
              @RequestMapping("/v1/status")
              class StatusEndpoint {
                  @GetMapping
                  @ResponseStatus(HttpStatus.ACCEPTED)
                  String get() {
                      return "accepted";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.http.HttpStatus;
              import org.springframework.web.bind.annotation.CrossOrigin;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.ResponseStatus;
              import org.springframework.web.bind.annotation.RestController;

              @CrossOrigin("https://example.invalid")
              /*~~(Manual migration: controller retains Spring behavior annotations that would be inert after Jakarta REST conversion; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/cors")
              class CorsEndpoint {
                  @GetMapping
                  String get() {
                      return "ok";
                  }
              }

              /*~~(Manual migration: controller retains Spring behavior annotations that would be inert after Jakarta REST conversion; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/status")
              class StatusEndpoint {
                  @GetMapping
                  @ResponseStatus(HttpStatus.ACCEPTED)
                  String get() {
                      return "accepted";
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesTransactionalForTheDownstreamRecipeWhileMigratingMvc() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.transaction.annotation.Transactional;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  @Transactional
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;
              import org.springframework.transaction.annotation.Transactional;

              @ApplicationScoped
              @Path("/v1/orders")
              public class OrderEndpoint {
                  @GET
                  @Transactional
                  public String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesValueForTheDownstreamRecipeWhileMigratingMvc() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @Value("${orders.region}")
                  String region;

                  @GetMapping
                  public String get() {
                      return region;
                  }
              }
              """,
            """
              package com.example;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;
              import org.springframework.beans.factory.annotation.Value;

              @ApplicationScoped
              @Path("/v1/orders")
              public class OrderEndpoint {
                  @Value("${orders.region}")
                  String region;

                  @GET
                  public String get() {
                      return region;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesControllerInheritancePendingProxyAndInheritedEndpointReview() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              class BaseEndpoint {
              }

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint extends BaseEndpoint {
                  @GetMapping
                  String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              class BaseEndpoint {
              }

              /*~~(Manual migration: controller inheritance needs CDI proxyability and inherited endpoint review; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint extends BaseEndpoint {
                  @GetMapping
                  String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesInheritedSpringLifecycleContractAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import com.example.contract.ManagedEndpoint;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint implements ManagedEndpoint {
                  @Override
                  public void afterPropertiesSet() {
                  }

                  @GetMapping
                  String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import com.example.contract.ManagedEndpoint;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller implements or inherits a Spring framework contract whose lifecycle semantics need explicit migration; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint implements ManagedEndpoint {
                  @Override
                  public void afterPropertiesSet() {
                  }

                  @GetMapping
                  String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesLombokGeneratedConstructorWithoutProvenPublicNoArgShape() {
        rewriteRun(
          java(
            """
              package com.example;

              import lombok.AllArgsConstructor;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @AllArgsConstructor
              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  String region;

                  @GetMapping
                  String get() {
                      return region;
                  }
              }
              """,
            """
              package com.example;

              import lombok.AllArgsConstructor;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @AllArgsConstructor
              /*~~(Manual migration: controller has no public no-argument constructor required for a portable CDI ApplicationScoped proxy; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  String region;

                  @GetMapping
                  String get() {
                      return region;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesAsyncAndStreamingReturnTypesAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import java.util.concurrent.CompletionStage;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;
              import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

              @RestController
              @RequestMapping("/v1/async")
              class AsyncEndpoint {
                  @GetMapping
                  CompletionStage<String> get() {
                      return null;
                  }
              }

              @RestController
              @RequestMapping("/v1/stream")
              class StreamingEndpoint {
                  @GetMapping
                  StreamingResponseBody download() {
                      return null;
                  }
              }
              """,
            """
              package com.example;

              import java.util.concurrent.CompletionStage;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;
              import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

              /*~~(Manual migration: controller uses an async, reactive, or streaming return type that needs explicit Jakarta REST design; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/async")
              class AsyncEndpoint {
                  @GetMapping
                  CompletionStage<String> get() {
                      return null;
                  }
              }

              /*~~(Manual migration: controller uses an async, reactive, or streaming return type that needs explicit Jakarta REST design; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/stream")
              class StreamingEndpoint {
                  @GetMapping
                  StreamingResponseBody download() {
                      return null;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesFinalResourceMethodAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  final String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: final resource method is not proxyable on a CDI ApplicationScoped bean; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  final String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesNonPublicResourceMethodAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: Jakarta REST resource methods must be public and non-static; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              public class OrderEndpoint {
                  @GetMapping
                  String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesVoidAndBoxedVoidResourceReturnsAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/tasks")
              class TaskEndpoint {
                  @GetMapping
                  void run() {
                  }
              }

              @RestController
              @RequestMapping("/v1/jobs")
              class JobEndpoint {
                  @GetMapping
                  Void run() {
                      return null;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: Spring MVC and Jakarta REST empty-response semantics need explicit status review; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/tasks")
              class TaskEndpoint {
                  @GetMapping
                  void run() {
                  }
              }

              /*~~(Manual migration: Spring MVC and Jakarta REST empty-response semantics need explicit status review; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/jobs")
              class JobEndpoint {
                  @GetMapping
                  Void run() {
                      return null;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesResidualSpringResponseEntityAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping("/{id}")
                  ResponseEntity<String> get(@RequestParam(required = false) String view) {
                      return ResponseEntity.ok().header("X-Trace-Id", "example").body(view);
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller still uses Spring ResponseEntity; migrate response status and header semantics before converting Spring MVC annotations)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping("/{id}")
                  ResponseEntity<String> get(@RequestParam(required = false) String view) {
                      return ResponseEntity.ok().header("X-Trace-Id", "example").body(view);
                  }
              }
              """
          )
        );
    }

    @Test
    void remainsAtomicAfterResponseMigrationRefusesUnsupportedBuilder() {
        rewriteRun(
          spec -> spec.recipe(new ResponseThenMvc()),
          java(
            """
              package com.example;

              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/files")
              class FileEndpoint {
                  @GetMapping("/{id}")
                  ResponseEntity<byte[]> download(byte[] body) {
                      return ResponseEntity.ok().header("X-Checksum", "example").body(body);
                  }
              }
              """,
            """
              package com.example;

              /*~~(Manual migration: this file mixes ResponseEntity with unsupported APIs; no response types were changed)~~>*/import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller still uses Spring ResponseEntity; migrate response status and header semantics before converting Spring MVC annotations)~~>*/@RestController
              @RequestMapping("/v1/files")
              class FileEndpoint {
                  @GetMapping("/{id}")
                  ResponseEntity<byte[]> download(byte[] body) {
                      return ResponseEntity.ok().header("X-Checksum", "example").body(body);
                  }
              }
              """
          )
        );
    }

    @Test
    void defersSupportedResponseMigrationWhenMvcMustPreserveSecuredController() {
        rewriteRun(
          spec -> spec.recipe(new ResponseThenMvc()),
          java(
            """
              package com.example;

              import com.example.security.ReadOrders;
              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  @ReadOrders
                  ResponseEntity<String> get() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """,
            """
              package com.example;

              import com.example.security.ReadOrders;
              /*~~(Manual migration: ResponseEntity conversion was deferred because this file contains a Spring MVC controller that cannot migrate atomically)~~>*/import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller uses Spring Security annotations; preserve endpoint authorization before converting Spring MVC annotations)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  @ReadOrders
                  ResponseEntity<String> get() {
                      return ResponseEntity.ok("ok");
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesSpringSecurityAnnotationsAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.security.access.prepost.PreAuthorize;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping("/{id}")
                  @PreAuthorize("hasAuthority('order:read')")
                  String get(@RequestParam(required = false) String view) {
                      return view;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.security.access.prepost.PreAuthorize;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller uses Spring Security annotations; preserve endpoint authorization before converting Spring MVC annotations)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping("/{id}")
                  @PreAuthorize("hasAuthority('order:read')")
                  String get(@RequestParam(required = false) String view) {
                      return view;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesComposedSpringSecurityAnnotationsAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import com.example.security.ReadOrders;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  @ReadOrders
                  String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import com.example.security.ReadOrders;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller uses Spring Security annotations; preserve endpoint authorization before converting Spring MVC annotations)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  @ReadOrders
                  String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesFinalControllerThatCannotBeApplicationScoped() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              final class FinalOrderEndpoint {
                  @GetMapping("/{id}")
                  String get(@RequestParam(required = false) String view) {
                      return view;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: final Spring controller is not proxyable as a CDI ApplicationScoped bean; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              final class FinalOrderEndpoint {
                  @GetMapping("/{id}")
                  String get(@RequestParam(required = false) String view) {
                      return view;
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesSealedControllerThatCannotBeApplicationScoped() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              public sealed class OrderEndpoint permits SpecializedOrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }

              final class SpecializedOrderEndpoint extends OrderEndpoint {}
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: sealed Spring controller is not proxyable as a CDI ApplicationScoped bean; no Spring MVC annotations were changed)~~>*/@RestController
              public sealed class OrderEndpoint permits SpecializedOrderEndpoint {
                  @GetMapping("/orders")
                  public String get() {
                      return "ok";
                  }
              }

              final class SpecializedOrderEndpoint extends OrderEndpoint {}
              """
          )
        );
    }

    @Test
    void refusesNonPublicRootResourceClassAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  public String get() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: a portable Jakarta REST root resource must be a public top-level class; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping("/v1/orders")
              class OrderEndpoint {
                  @GetMapping
                  public String get() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesNestedControllerAsRootResourceAtomically() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              public class Endpoints {
                  @RestController
                  @RequestMapping("/v1/orders")
                  public static class OrderEndpoint {
                      @GetMapping
                      public String get() {
                          return "ok";
                      }
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              public class Endpoints {
                  /*~~(Manual migration: a portable Jakarta REST root resource must be a public top-level class; no Spring MVC annotations were changed)~~>*/@RestController
                  @RequestMapping("/v1/orders")
                  public static class OrderEndpoint {
                      @GetMapping
                      public String get() {
                          return "ok";
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesAControllerAtomicallyWhenItsClassMappingHasConditions() {
        rewriteRun(
          java(
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping(path = "/v1/orders", headers = "X-Tenant")
              class ConditionalEndpoint {
                  @GetMapping("/{id}")
                  String get(@RequestParam(required = false) String view) {
                      return view;
                  }
              }
              """,
            """
              package com.example;

              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RequestParam;
              import org.springframework.web.bind.annotation.RestController;

              /*~~(Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; no Spring MVC annotations were changed)~~>*/@RestController
              @RequestMapping(path = "/v1/orders", headers = "X-Tenant")
              class ConditionalEndpoint {
                  @GetMapping("/{id}")
                  String get(@RequestParam(required = false) String view) {
                      return view;
                  }
              }
              """
          )
        );
    }

    private static Expression initializer(J.CompilationUnit compilationUnit) {
        J.ClassDeclaration use = compilationUnit.getClasses().get(0);
        J.VariableDeclarations value = (J.VariableDeclarations) use.getBody().getStatements().get(0);
        return value.getVariables().get(0).getInitializer();
    }

    public static class ResponseThenMvc extends Recipe {
        @Override
        public String getDisplayName() {
            return "Test response and MVC migration ordering";
        }

        @Override
        public String getDescription() {
            return "Runs response conversion before MVC conversion.";
        }

        @Override
        public List<Recipe> getRecipeList() {
            return List.of(
                    new MigrateResponseEntityToJakartaResponse(),
                    new MigrateSpringMvcToJakartaRest());
        }
    }
}
