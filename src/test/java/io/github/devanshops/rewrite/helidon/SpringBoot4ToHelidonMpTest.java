package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class SpringBoot4ToHelidonMpTest implements RewriteTest {
    private static final String RECIPE =
            "io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp";
    private static final String BEANS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                                       https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
                   version="4.0"
                   bean-discovery-mode="annotated">
            </beans>
            """;
    private static final String MICROPROFILE_CONFIG = """
            # Helidon MP / MicroProfile Config
            # No defaults are generated: migrate each Spring property only after semantic review.
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        Environment environment = Environment.builder()
                .scanRuntimeClasspath("io.github.devanshops.rewrite.helidon")
                .build();
        spec.recipe(environment.activateRecipes(RECIPE))
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-beans", "spring-context", "spring-web", "spring-boot",
                        "spring-boot-autoconfigure",
                        "jakarta.enterprise.cdi-api", "jakarta.inject-api", "jakarta.ws.rs-api",
                        "microprofile-config-api", "helidon"))
                // Project scans defer runtime replacement until the supported source migrations
                // have completed. A final metadata-only cycle stabilizes the rewritten launcher.
                .cycles(4)
                .expectedCyclesThatMakeChanges(3);
    }

    @DocumentExample
    @Test
    void migratesACompleteSafeSliceAndIsIdempotent() {
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

              class CatalogApplication {
                  public static void main(String[] args) {
                      io.helidon.Main.main(args);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/catalog/CatalogApplication.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.stereotype.Service;

              @Service
              class CatalogService {
                  @Value("${catalog.region:us-east}")
                  String region;
              }
              """,
            """
              package com.example.catalog;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Inject;
              import jakarta.inject.Named;
              import org.eclipse.microprofile.config.inject.ConfigProperty;

              @ApplicationScoped
              @Named("catalogService")
              class CatalogService {
                  @Inject
                  @ConfigProperty(name = "catalog.region", defaultValue = "us-east")
                  String region;
              }
              """,
            spec -> spec.path("src/main/java/com/example/catalog/CatalogService.java")
          ),
          java(
            """
              package com.example.catalog;

              import org.springframework.http.ResponseEntity;
              import org.springframework.web.bind.annotation.GetMapping;
              import org.springframework.web.bind.annotation.PathVariable;
              import org.springframework.web.bind.annotation.RequestMapping;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              @RequestMapping("/catalog")
              public class CatalogEndpoint {
                  @GetMapping("/{id}")
                  public ResponseEntity<String> get(@PathVariable("id") String id) {
                      return ResponseEntity.ok(id);
                  }
              }
              """,
            """
              package com.example.catalog;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.ws.rs.GET;
              import jakarta.ws.rs.Path;
              import jakarta.ws.rs.PathParam;
              import jakarta.ws.rs.core.Response;

              @ApplicationScoped
              @Path("/catalog")
              public class CatalogEndpoint {
                  @GET
                  @Path("/{id}")
                  public Response get(@PathParam("id") String id) {
                      return Response.ok(id).build();
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/catalog/CatalogEndpoint.java")
          ),
          text(null, BEANS_XML,
            spec -> spec.path("src/main/resources/META-INF/beans.xml")),
          text(null, MICROPROFILE_CONFIG,
            spec -> spec.path("src/main/resources/META-INF/microprofile-config.properties"))
        );
    }
}
