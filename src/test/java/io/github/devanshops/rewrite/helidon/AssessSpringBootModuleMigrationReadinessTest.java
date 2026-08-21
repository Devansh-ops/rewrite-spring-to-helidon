package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.ModuleMigrationReadinessTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.internal.EncodingDetectingInputStream;
import org.openrewrite.java.JavaParser;
import org.openrewrite.marker.Markers;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpec;
import org.openrewrite.test.SourceSpecs;
import org.openrewrite.test.TypeValidation;
import org.openrewrite.text.PlainText;
import org.openrewrite.tree.ParseError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.groovy.Assertions.groovy;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.test.SourceSpecs.other;
import static org.openrewrite.test.SourceSpecs.text;
import static org.openrewrite.xml.Assertions.xml;
import static org.openrewrite.yaml.Assertions.yaml;

class AssessSpringBootModuleMigrationReadinessTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AssessSpringBootModuleMigrationReadiness())
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-context", "spring-beans", "spring-core", "spring-tx",
                        "spring-boot", "spring-boot-autoconfigure"))
                .cycles(2);
    }

    @Test
    void reportsACleanMavenModuleEligibleForTheConservativeProfile() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row -> {
                      assertThat(row.getModulePath()).isEqualTo("orders");
                      assertThat(row.getBuildSystem()).isEqualTo("MAVEN");
                      assertThat(row.getProfile()).isEqualTo("HELIDON_MP_CONSERVATIVE");
                      assertThat(row.getOutcome()).isEqualTo("ELIGIBLE_FOR_PROFILE");
                      assertThat(row.getReasonCode()).isEqualTo("MODULE_ELIGIBLE_FOR_PROFILE");
                  })),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          java(
            """
              package com.example.orders;

              class OrderService {
                  String find() { return "order"; }
              }
              """,
            source -> source.path(
                    "orders/src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @DocumentExample
    @Test
    void refusesAttributedSpringJavaResidueWithoutChangingModuleSemantics() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(1)
                  .dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row -> {
                      assertThat(row.getModulePath()).isEqualTo("orders");
                      assertThat(row.getOutcome()).isEqualTo("REFUSED");
                      assertThat(row.getSourcePath()).isEqualTo(
                              "orders/src/main/java/com/example/orders/OrderService.java");
                      assertThat(row.getSourceKind()).isEqualTo("JAVA_MAIN");
                      assertThat(row.getConstruct()).isEqualTo(
                              "org.springframework.context.ApplicationContext");
                      assertThat(row.getReasonCode()).isEqualTo("MODULE_SPRING_JAVA_RESIDUE");
                  })),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          java(
            """
              package com.example.orders;

              import org.springframework.context.ApplicationContext;

              class OrderService {
                  ApplicationContext context;
              }
              """,
            source -> source.path(
                    "orders/src/main/java/com/example/orders/OrderService.java"))
        );
    }

    @Test
    void reportsEveryAttributedJavaBlockerInDeterministicSourceOrder() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getSourcePath,
                                  ModuleMigrationReadinessTable.Row::getSourceKind,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("orders/src/integrationTest/java/com/example/orders/OrderFlowTest.java",
                                          "JAVA_TEST",
                                          "org.springframework.context.ApplicationContext",
                                          "MODULE_SPRING_JAVA_RESIDUE"),
                                  tuple("orders/src/main/java/com/example/orders/OrderService.java",
                                          "JAVA_MAIN",
                                          "org.springframework.context.ApplicationContext",
                                          "MODULE_SPRING_JAVA_RESIDUE"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          java(
            """
              package com.example.orders;

              import org.springframework.context.ApplicationContext;

              class OrderService {
                  ApplicationContext context;
              }
              """,
            source -> source.path(
                    "orders/src/main/java/com/example/orders/OrderService.java")),
          java(
            """
              package com.example.orders;

              class OrderFlowTest {
                  org.springframework.context.ApplicationContext context;
              }
              """,
            source -> source.path(
                    "orders/src/integrationTest/java/com/example/orders/OrderFlowTest.java"))
        );
    }

    @Test
    void refusesSpringMavenBuildResidueAtTheModuleAnchorAndExactCoordinate() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row -> {
                      assertThat(row.getModulePath()).isEqualTo("orders");
                      assertThat(row.getBuildSystem()).isEqualTo("MAVEN");
                      assertThat(row.getOutcome()).isEqualTo("REFUSED");
                      assertThat(row.getSourcePath()).isEqualTo("orders/pom.xml");
                      assertThat(row.getSourceKind()).isEqualTo("MAVEN");
                      assertThat(row.getConstruct()).isEqualTo(
                              "org.springframework:spring-context");
                      assertThat(row.getReasonCode()).isEqualTo("MODULE_SPRING_BUILD_RESIDUE");
                  })),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework</groupId>
                          <artifactId>spring-context</artifactId>
                          <version>7.0.8</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework</groupId>
                          <artifactId>spring-context</artifactId>
                          <version>7.0.8</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            source -> source.path("orders/pom.xml"))
        );
    }

    @Test
    void reportsSensitiveResourceBlockersWithoutMarkingOrLeakingTheirValues() {
        String secret = "SPRING_SENTINEL_SECRET_18f4";
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows -> {
              assertThat(rows)
                      .extracting(ModuleMigrationReadinessTable.Row::getSourcePath,
                              ModuleMigrationReadinessTable.Row::getSourceKind,
                              ModuleMigrationReadinessTable.Row::getConstruct,
                              ModuleMigrationReadinessTable.Row::getReasonCode)
                      .containsExactly(
                              tuple("orders/src/main/resources/META-INF/spring.factories",
                                      "SPRING_FACTORIES", "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                                      "MODULE_SPRING_METADATA"),
                              tuple("orders/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                                      "SPRING_AUTOCONFIG_IMPORTS", "AutoConfiguration.imports entry",
                                      "MODULE_SPRING_METADATA"),
                              tuple("orders/src/main/resources/application.properties",
                                      "SPRING_PROPERTIES", "spring.datasource.password",
                                      "MODULE_SPRING_CONFIGURATION"),
                              tuple("orders/src/main/resources/application.yml",
                                      "SPRING_YAML", "spring.datasource.password",
                                      "MODULE_SPRING_CONFIGURATION"),
                              tuple("orders/src/main/resources/spring-context.xml",
                                      "SPRING_XML", "Spring XML namespace",
                                      "MODULE_SPRING_XML"));
              assertThat(rows).allSatisfy(row ->
                      assertThat(String.join("|", row.getSourcePath(), row.getSourceKind(),
                              row.getFeature(), row.getConstruct(), row.getReasonCode(),
                              row.getReason(), row.getSuggestedDirection()))
                              .doesNotContain(secret));
          }),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 5 blockers; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          properties(
            """
              spring.datasource.password=SPRING_SENTINEL_SECRET_18f4
              """,
            source -> source.path("orders/src/main/resources/application.properties")),
          yaml(
            """
              spring:
                datasource:
                  password: SPRING_SENTINEL_SECRET_18f4
              """,
            source -> source.path("orders/src/main/resources/application.yml")),
          xml(
            """
              <beans xmlns="http://www.springframework.org/schema/beans">
                  <property name="password" value="SPRING_SENTINEL_SECRET_18f4"/>
              </beans>
              """,
            source -> source.path("orders/src/main/resources/spring-context.xml")),
          properties(
            """
              org.springframework.boot.autoconfigure.EnableAutoConfiguration=com.example.SPRING_SENTINEL_SECRET_18f4
              """,
            source -> source.path("orders/src/main/resources/META-INF/spring.factories")),
          text(
            """
              com.example.SPRING_SENTINEL_SECRET_18f4
              """,
            source -> source.path("orders/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
        );
    }

    @Test
    void refusesEveryNonEmptyApplicationConfigurationKeyWithoutReportingValues() {
        String secret = "APPLICATION_SENTINEL_91ac";
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows -> {
              assertThat(rows)
                      .extracting(ModuleMigrationReadinessTable.Row::getSourcePath,
                              ModuleMigrationReadinessTable.Row::getConstruct,
                              ModuleMigrationReadinessTable.Row::getReasonCode)
                      .containsExactly(
                              tuple("orders/src/main/resources/application-prod.properties",
                                      "custom.api-token", "MODULE_APPLICATION_CONFIGURATION"),
                              tuple("orders/src/main/resources/application-prod.properties",
                                      "management.endpoints.web.exposure.include",
                                      "MODULE_APPLICATION_CONFIGURATION"),
                              tuple("orders/src/main/resources/application-prod.properties",
                                      "server.port", "MODULE_APPLICATION_CONFIGURATION"),
                              tuple("orders/src/main/resources/application.yml",
                                      "custom.token", "MODULE_APPLICATION_CONFIGURATION"));
              assertThat(rows).allSatisfy(row ->
                      assertThat(String.join("|", row.getConstruct(), row.getReason(),
                              row.getSuggestedDirection())).doesNotContain(secret));
          }),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 4 blockers; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          properties(
            """
              server.port=8080
              management.endpoints.web.exposure.include=health
              custom.api-token=APPLICATION_SENTINEL_91ac
              """,
            source -> source.path(
                    "orders/src/main/resources/application-prod.properties")),
          yaml(
            """
              custom:
                token: APPLICATION_SENTINEL_91ac
              """,
            source -> source.path("orders/src/main/resources/application.yml"))
        );
    }

    @Test
    void refusesSpringNamespaceDeclaredBelowANeutralXmlRootWithoutMarkingXml() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row -> {
                      assertThat(row.getSourceKind()).isEqualTo("SPRING_XML");
                      assertThat(row.getConstruct()).isEqualTo("Spring XML namespace");
                      assertThat(row.getReasonCode()).isEqualTo("MODULE_SPRING_XML");
                  })),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          xml(
            """
              <configuration>
                  <wiring xmlns:beans="http://www.springframework.org/schema/beans">
                      <beans:bean id="secret" class="com.example.PrivateValue"/>
                  </wiring>
              </configuration>
              """,
            source -> source.path("orders/src/main/resources/wiring.xml"))
        );
    }

    @Test
    void assignsEvidenceToTheDeepestMavenRootWithoutRefusingParentOrSibling() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getOutcome,
                                  ModuleMigrationReadinessTable.Row::getSourcePath,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple(".", "ELIGIBLE_FOR_PROFILE", "pom.xml",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("services/billing", "ELIGIBLE_FOR_PROFILE",
                                          "services/billing/pom.xml",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("services/orders", "REFUSED",
                                          "services/orders/src/main/java/com/example/Orders.java",
                                          "MODULE_SPRING_JAVA_RESIDUE"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>reactor</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("pom.xml")),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>billing</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("services/billing/pom.xml")),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("services/orders/pom.xml")),
          java(
            """
              package com.example;

              import org.springframework.context.ApplicationContext;

              class Orders {
                  ApplicationContext context;
              }
              """,
            source -> source.path("services/orders/src/main/java/com/example/Orders.java"))
        );
    }

    @Test
    void reportsACleanGroovyGradleModuleEligibleForTheConservativeProfile() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row -> {
                      assertThat(row.getModulePath()).isEqualTo("orders");
                      assertThat(row.getBuildSystem()).isEqualTo("GRADLE_GROOVY");
                      assertThat(row.getOutcome()).isEqualTo("ELIGIBLE_FOR_PROFILE");
                      assertThat(row.getSourcePath()).isEqualTo("orders/build.gradle");
                  })),
          buildGradle(
            """
              plugins {
                  id 'java'
              }
              """,
            source -> source.path("orders/build.gradle"))
        );
    }

    @Test
    void refusesMavenAndGradleDescriptorsAtTheSameModuleRoot() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row -> {
                      assertThat(row.getModulePath()).isEqualTo("orders");
                      assertThat(row.getBuildSystem()).isEqualTo("AMBIGUOUS");
                      assertThat(row.getOutcome()).isEqualTo("REFUSED");
                      assertThat(row.getSourcePath()).isEqualTo("orders/pom.xml");
                      assertThat(row.getSourceKind()).isEqualTo("MODULE_TOPOLOGY");
                      assertThat(row.getConstruct()).isEqualTo("MAVEN+GRADLE_GROOVY");
                      assertThat(row.getReasonCode()).isEqualTo(
                              "MODULE_BUILD_ROOT_AMBIGUOUS");
                  })),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          buildGradle(
            """
              plugins {
                  id 'java'
              }
              """,
            source -> source.path("orders/build.gradle"))
        );
    }

    @Test
    void refusesBuildlessSpringArtifactsInsteadOfDroppingTheirEvidence() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getBuildSystem,
                                  ModuleMigrationReadinessTable.Row::getSourcePath,
                                  ModuleMigrationReadinessTable.Row::getSourceKind,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("orders", "UNRESOLVED",
                                          "orders/src/main/java/com/example/Orders.java",
                                          "MODULE_TOPOLOGY", "MODULE_BUILD_ROOT_MISSING"),
                                  tuple("orders", "UNRESOLVED",
                                          "orders/src/main/java/com/example/Orders.java",
                                          "JAVA_MAIN", "MODULE_SPRING_JAVA_RESIDUE"))),
          java(
            """
              package com.example;

              import org.springframework.context.ApplicationContext;

              class Orders {
                  ApplicationContext context;
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/package com.example;

              import org.springframework.context.ApplicationContext;

              class Orders {
                  ApplicationContext context;
              }
              """,
            source -> source.path("orders/src/main/java/com/example/Orders.java"))
        );
    }

    @Test
    void assignsEvidenceToADeepestKotlinGradleChildOfAMavenRoot() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getBuildSystem,
                                  ModuleMigrationReadinessTable.Row::getOutcome,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple(".", "MAVEN", "ELIGIBLE_FOR_PROFILE",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("orders", "GRADLE_KOTLIN", "REFUSED",
                                          "MODULE_SPRING_JAVA_RESIDUE"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>reactor</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("pom.xml")),
          buildGradleKts(
            """
              plugins {
                  java
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/plugins {
                  java
              }
              """,
            source -> source.path("orders/build.gradle.kts")),
          java(
            """
              package com.example;

              import org.springframework.context.ApplicationContext;

              class Orders {
                  ApplicationContext context;
              }
              """,
            source -> source.path("orders/src/main/java/com/example/Orders.java"))
        );
    }

    @Test
    void refusesEveryLiteralSpringGradleBuildResidue() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getBuildSystem,
                                  ModuleMigrationReadinessTable.Row::getSourceKind,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("GRADLE_GROOVY", "GRADLE_GROOVY",
                                          "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("GRADLE_GROOVY", "GRADLE_GROOVY",
                                          "org.springframework:spring-context",
                                          "MODULE_SPRING_BUILD_RESIDUE"))),
          buildGradle(
            """
              plugins {
                  id 'com.example.safe' version '1.0.0'
                  id 'org.springframework.boot' version '4.0.0'
              }

              dependencies {
                  implementation 'org.springframework:spring-context:7.0.8'
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/plugins {
                  id 'com.example.safe' version '1.0.0'
                  id 'org.springframework.boot' version '4.0.0'
              }

              dependencies {
                  implementation 'org.springframework:spring-context:7.0.8'
              }
              """,
            source -> source.path("orders/build.gradle"))
        );
    }

    @Test
    void refusesUnresolvedMavenAndGradleDeclarationsThatCanHideSpringBuildResidue() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("catalog", "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("orders", "Unresolved Maven coordinate",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"))),
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              def springCoordinate = providers.gradleProperty('springCoordinate')

              dependencies {
                  implementation springCoordinate
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/plugins {
                  id 'java'
              }

              def springCoordinate = providers.gradleProperty('springCoordinate')

              dependencies {
                  implementation springCoordinate
              }
              """,
            source -> source.path("catalog/build.gradle")),
          xml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>${spring.group}</groupId>
                          <artifactId>spring-context</artifactId>
                          <version>7.0.8</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>${spring.group}</groupId>
                          <artifactId>spring-context</artifactId>
                          <version>7.0.8</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            source -> source.path("orders/pom.xml"))
        );
    }

    @Test
    void refusesExactSpringSyntaxWhenTypeAttributionIsMissing() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion())
                  .typeValidationOptions(TypeValidation.none())
                  .dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                          assertThat(rows)
                                  .extracting(ModuleMigrationReadinessTable.Row::getSourcePath,
                                          ModuleMigrationReadinessTable.Row::getConstruct,
                                          ModuleMigrationReadinessTable.Row::getReasonCode)
                                  .containsExactly(
                                          tuple("orders/src/main/java/com/example/Imported.java",
                                                  "org.springframework.missing.Unavailable",
                                                  "MODULE_MISSING_ATTRIBUTION"),
                                          tuple("orders/src/main/java/com/example/Qualified.java",
                                                  "org.springframework.missing.Unavailable",
                                                  "MODULE_MISSING_ATTRIBUTION"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          java(
            """
              package com.example;

              import org.springframework.missing.Unavailable;

              class Imported {
                  Unavailable value;
              }
              """,
            source -> source.path("orders/src/main/java/com/example/Imported.java")),
          java(
            """
              package com.example;

              class Qualified {
                  org.springframework.missing.Unavailable value;
              }
              """,
            source -> source.path("orders/src/main/java/com/example/Qualified.java"))
        );
    }

    @Test
    void preservesIdenticalConfigurationOccurrencesAsSeparateRows() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("spring.duplicate", "MODULE_SPRING_CONFIGURATION"),
                                  tuple("spring.duplicate", "MODULE_SPRING_CONFIGURATION"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          properties(
            """
              spring.duplicate=first
              spring.duplicate=second
              """,
            source -> source.path("orders/src/main/resources/application.properties"))
        );
    }

    @Test
    void refusesUnsupportedKotlinAndGroovyApplicationSources() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getSourceKind,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("GROOVY_SOURCE", "MODULE_UNSUPPORTED_SOURCE_LANGUAGE"),
                                  tuple("KOTLIN_SOURCE", "MODULE_UNSUPPORTED_SOURCE_LANGUAGE"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          groovy(
            """
              package com.example

              class GroovyOrderService {
              }
              """,
            source -> source.path(
                    "orders/src/main/groovy/com/example/GroovyOrderService.groovy")),
          kotlin(
            """
              package com.example

              class KotlinOrderService
              """,
            source -> source.path(
                    "orders/src/main/kotlin/com/example/KotlinOrderService.kt"))
        );
    }

    @Test
    void refusesPlainUnparsedMavenAndGradleBuildDescriptors() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getBuildSystem,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("catalog", "GRADLE_GROOVY",
                                          "MODULE_BUILD_DESCRIPTOR_UNPARSED"),
                                  tuple("orders", "MAVEN",
                                          "MODULE_BUILD_DESCRIPTOR_UNPARSED"))),
          text(
            "not a Gradle AST",
            "~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>not a Gradle AST",
            source -> source.path("catalog/build.gradle")),
          text(
            "not a Maven AST",
            "~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>not a Maven AST",
            source -> source.path("orders/pom.xml"))
        );
    }

    @Test
    void refusesLiteralAndUnresolvedSpringGradleFormsAcrossDslShapes() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("catalog", "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("catalog", "org.springframework:spring-context",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("orders", "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("orders", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("orders", "org.springframework.boot:spring-boot-devtools",
                                          "MODULE_SPRING_BUILD_RESIDUE"))),
          buildGradleKts(
            """
              plugins {
                  java
                  alias(libs.plugins.spring.boot)
              }

              dependencies {
                  customConfiguration("org.springframework:spring-context:7.0.8")
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/plugins {
                  java
                  alias(libs.plugins.spring.boot)
              }

              dependencies {
                  customConfiguration("org.springframework:spring-context:7.0.8")
              }
              """,
            source -> source.path("catalog/build.gradle.kts")),
          buildGradle(
            """
              apply plugin: 'org.springframework.boot'

              plugins {
                  alias libs.plugins.spring.boot
              }

              dependencies {
                  developmentOnly 'org.springframework.boot:spring-boot-devtools:4.0.0'
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 3 blockers; no module changes were applied)~~>*/apply plugin: 'org.springframework.boot'

              plugins {
                  alias libs.plugins.spring.boot
              }

              dependencies {
                  developmentOnly 'org.springframework.boot:spring-boot-devtools:4.0.0'
              }
              """,
            source -> source.path("orders/build.gradle"))
        );
    }

    @Test
    void refusesMissingMavenAndGradleReactorChildren() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getSourceKind,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("gradle-parent", "GRADLE", ":child",
                                          "MODULE_INCOMPLETE_REACTOR"),
                                  tuple("maven-parent", "MAVEN", "child",
                                          "MODULE_INCOMPLETE_REACTOR"))),
          buildGradle(
            """
              plugins {
                  id 'java'
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/plugins {
                  id 'java'
              }
              """,
            source -> source.path("gradle-parent/build.gradle")),
          groovy(
            """
              include ':child'
              """,
            source -> source.path("gradle-parent/settings.gradle")),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                      <module>child</module>
                  </modules>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                      <module>child</module>
                  </modules>
              </project>
              """,
            source -> source.path("maven-parent/pom.xml"))
        );
    }

    @Test
    void acceptsCompleteSuppliedMavenAndGradleReactorChildren() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getOutcome)
                          .containsExactly(
                                  tuple("gradle-parent", "ELIGIBLE_FOR_PROFILE"),
                                  tuple("gradle-parent/child", "ELIGIBLE_FOR_PROFILE"),
                                  tuple("maven-parent", "ELIGIBLE_FOR_PROFILE"),
                                  tuple("maven-parent/child", "ELIGIBLE_FOR_PROFILE"))),
          buildGradle("plugins { id 'java' }",
                  source -> source.path("gradle-parent/build.gradle")),
          groovy("include ':child'",
                  source -> source.path("gradle-parent/settings.gradle")),
          buildGradle("plugins { id 'java' }",
                  source -> source.path("gradle-parent/child/build.gradle")),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>child</module></modules>
              </project>
              """,
            source -> source.path("maven-parent/pom.xml")),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("maven-parent/child/pom.xml"))
        );
    }

    @Test
    void refusesMavenMarkerAndSuppliedPathDisagreement() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row ->
                          assertThat(row.getReasonCode()).isEqualTo(
                                  "MODULE_OWNERSHIP_DISAGREEMENT"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml").mapBeforeRecipe(document -> {
                MavenResolutionResult resolution = document.getMarkers()
                        .findFirst(MavenResolutionResult.class).orElseThrow();
                MavenResolutionResult changed = resolution.withPom(
                        resolution.getPom().withRequested(
                                resolution.getPom().getRequested().withSourcePath(
                                        Paths.get("elsewhere/pom.xml"))));
                return document.withMarkers(document.getMarkers().setByType(changed));
            }))
        );
    }

    @Test
    void refusesRecognizedEvidenceArtifactsWhenTheyWereNotParsed() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getSourcePath,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("orders/src/main/java/com/example/Broken.java",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"),
                                  tuple("orders/src/main/resources/META-INF/spring.factories",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"),
                                  tuple("orders/src/main/resources/application.properties",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"),
                                  tuple("orders/src/main/resources/application.yml",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"),
                                  tuple("orders/src/main/resources/broken-context.xml",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"),
                                  tuple("orders/src/main/resources/spring-context.xml",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 6 blockers; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          text("class Broken {",
                  source -> source.path(
                          "orders/src/main/java/com/example/Broken.java")),
          text("broken=factory",
                  source -> source.path(
                          "orders/src/main/resources/META-INF/spring.factories")),
          text("server.port=8080",
                  source -> source.path(
                          "orders/src/main/resources/application.properties")),
          text("spring: [",
                  source -> source.path(
                          "orders/src/main/resources/application.yml")),
          parseError("<beans>",
                  source -> source.path(
                                  "orders/src/main/resources/broken-context.xml")
                          .beforeRecipeParseError(error -> { })),
          text("<beans",
                  source -> source.path(
                          "orders/src/main/resources/spring-context.xml"))
        );
    }

    @Test
    void refusesUnparsedGradleSettingsAndAutoConfigurationMetadataModels() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getSourcePath,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("groovy-settings", "groovy-settings/settings.gradle",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"),
                                  tuple("kotlin-settings", "kotlin-settings/settings.gradle.kts",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"),
                                  tuple("metadata",
                                          "metadata/src/main/resources/META-INF/spring/" +
                                                  "org.springframework.boot.autoconfigure." +
                                                  "AutoConfiguration.imports",
                                          "MODULE_EVIDENCE_ARTIFACT_UNPARSED"))),
          buildGradle(
            "plugins { id 'java' }",
            "/*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/plugins { id 'java' }",
            source -> source.path("groovy-settings/build.gradle")),
          other("include ':hidden'",
                  source -> source.path("groovy-settings/settings.gradle")),
          buildGradleKts(
            "plugins { java }",
            "/*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/plugins { java }",
            source -> source.path("kotlin-settings/build.gradle.kts")),
          parseError("include(\":hidden\")",
                  source -> source.path("kotlin-settings/settings.gradle.kts")
                          .beforeRecipeParseError(error -> { })),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>metadata</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>metadata</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("metadata/pom.xml")),
          other("com.example.HiddenConfiguration",
                  source -> source.path(
                          "metadata/src/main/resources/META-INF/spring/" +
                                  "org.springframework.boot.autoconfigure." +
                                  "AutoConfiguration.imports"))
        );
    }

    @Test
    void refusesDeclaredChildrenOwnedByAnIncompatibleBuildSystem() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getOutcome,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("gradle-parent", "REFUSED",
                                          "MODULE_REACTOR_BUILD_MISMATCH"),
                                  tuple("gradle-parent/child", "ELIGIBLE_FOR_PROFILE",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("maven-parent", "REFUSED",
                                          "MODULE_REACTOR_BUILD_MISMATCH"),
                                  tuple("maven-parent/child", "ELIGIBLE_FOR_PROFILE",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"))),
          buildGradle(
            "plugins { id 'java' }",
            "/*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/plugins { id 'java' }",
            source -> source.path("gradle-parent/build.gradle")),
          groovy("include ':child'",
                  source -> source.path("gradle-parent/settings.gradle")),
          xml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>gradle-child</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("gradle-parent/child/pom.xml")),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>maven-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>child</module></modules>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>maven-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>child</module></modules>
              </project>
              """,
            source -> source.path("maven-parent/pom.xml")),
          buildGradle("plugins { id 'java' }",
                  source -> source.path("maven-parent/child/build.gradle"))
        );
    }

    @Test
    void refusesGenericUnresolvedMavenAndGradleDependencyOrPluginDeclarations() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("catalog", "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("catalog", "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("catalog", "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("orders", "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("orders", "MODULE_BUILD_EVIDENCE_INCOMPLETE"))),
          buildGradle(
            """
              plugins {
                  alias libs.plugins.framework
              }

              def frameworkCoordinate = providers.gradleProperty('frameworkCoordinate')
              dependencies {
                  customConfiguration frameworkCoordinate
                  implementation libs.framework
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 3 blockers; no module changes were applied)~~>*/plugins {
                  alias libs.plugins.framework
              }

              def frameworkCoordinate = providers.gradleProperty('frameworkCoordinate')
              dependencies {
                  customConfiguration frameworkCoordinate
                  implementation libs.framework
              }
              """,
            source -> source.path("catalog/build.gradle")),
          xml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>${framework.group}</groupId>
                          <artifactId>${framework.artifact}</artifactId>
                          <version>${framework.version}</version>
                      </dependency>
                  </dependencies>
                  <build><plugins><plugin>
                      <groupId>${plugin.group}</groupId>
                      <artifactId>${plugin.artifact}</artifactId>
                      <version>${plugin.version}</version>
                  </plugin></plugins></build>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>${framework.group}</groupId>
                          <artifactId>${framework.artifact}</artifactId>
                          <version>${framework.version}</version>
                      </dependency>
                  </dependencies>
                  <build><plugins><plugin>
                      <groupId>${plugin.group}</groupId>
                      <artifactId>${plugin.artifact}</artifactId>
                      <version>${plugin.version}</version>
                  </plugin></plugins></build>
              </project>
              """,
            source -> source.path("orders/pom.xml"))
        );
    }

    @Test
    void acceptsDynamicMavenCoordinatesResolvedAuthoritativelyAsNonSpring() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row ->
                          assertThat(row.getOutcome()).isEqualTo(
                                  "ELIGIBLE_FOR_PROFILE"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
                  <properties>
                      <framework.group>org.junit.jupiter</framework.group>
                      <framework.artifact>junit-jupiter-api</framework.artifact>
                      <framework.version>5.10.2</framework.version>
                  </properties>
                  <dependencies><dependency>
                      <groupId>${framework.group}</groupId>
                      <artifactId>${framework.artifact}</artifactId>
                      <version>${framework.version}</version>
                  </dependency></dependencies>
              </project>
              """,
            source -> source.path("orders/pom.xml"))
        );
    }

    @Test
    void refusesDynamicGradleReactorDeclarationsButNotOrdinaryProjectDependencies() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getOutcome,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("dynamic", "REFUSED",
                                          "MODULE_REACTOR_DECLARATION_UNRESOLVED"),
                                  tuple("dynamic", "REFUSED",
                                          "MODULE_REACTOR_DECLARATION_UNRESOLVED"),
                                  tuple("dynamic", "REFUSED",
                                          "MODULE_REACTOR_DECLARATION_UNRESOLVED"),
                                  tuple("orders", "ELIGIBLE_FOR_PROFILE",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"))),
          buildGradle(
            "plugins { id 'java' }",
            "/*~~(REFUSED [MODULE_REFUSED]: 3 blockers; no module changes were applied)~~>*/plugins { id 'java' }",
            source -> source.path("dynamic/build.gradle")),
          groovy(
            """
              def moduleName = providers.gradleProperty('moduleName')
              include moduleName
              includeFlat moduleName
              includeBuild moduleName
              """,
            source -> source.path("dynamic/settings.gradle")),
          buildGradle(
            """
              plugins { id 'java' }
              dependencies {
                  implementation project(':shared')
              }
              """,
            source -> source.path("orders/build.gradle"))
        );
    }

    @Test
    void refusesExternalScriptsAndUnknownPluginAccessorsButNotOrdinaryGradleCalls() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("groovy", "Unresolved Gradle external script declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("groovy", "Unresolved Gradle plugin declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("kotlin", "Unresolved Gradle external script declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("kotlin", "Unresolved Gradle plugin declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("safe", "safe", "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("safe-kotlin", "safe-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"))),
          buildGradle(
            """
              def externalScript = providers.gradleProperty('externalScript')
              apply from: externalScript
              plugins {
                  mysteryFramework
              }
              ordinaryMethod(externalScript)
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/def externalScript = providers.gradleProperty('externalScript')
              apply from: externalScript
              plugins {
                  mysteryFramework
              }
              ordinaryMethod(externalScript)
              """,
            source -> source.path("groovy/build.gradle")),
          buildGradleKts(
            """
              val externalScript = providers.gradleProperty("externalScript")
              apply(from = externalScript)
              plugins {
                  mysteryFramework
              }
              ordinaryMethod(externalScript)
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/val externalScript = providers.gradleProperty("externalScript")
              apply(from = externalScript)
              plugins {
                  mysteryFramework
              }
              ordinaryMethod(externalScript)
              """,
            source -> source.path("kotlin/build.gradle.kts")),
          buildGradle(
            """
              plugins { id 'java' }
              def dynamicArgument = providers.gradleProperty('dynamicArgument')
              ordinaryMethod(dynamicArgument)
              """,
            source -> source.path("safe/build.gradle")),
          buildGradleKts(
            """
              plugins { `java-library` }
              val dynamicArgument = providers.gradleProperty("dynamicArgument")
              ordinaryMethod(dynamicArgument)
              """,
            source -> source.path("safe-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void findsSpringInPositionalGradleDependenciesAndReceiverPluginApplyCalls() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getOutcome,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("safe-groovy", "ELIGIBLE_FOR_PROFILE", "safe-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("safe-kotlin", "ELIGIBLE_FOR_PROFILE", "safe-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("spring-groovy", "REFUSED", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("spring-groovy", "REFUSED",
                                          "org.springframework:spring-context",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("spring-kotlin", "REFUSED", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("spring-kotlin", "REFUSED",
                                          "org.springframework:spring-context",
                                          "MODULE_SPRING_BUILD_RESIDUE"))),
          buildGradle(
            """
              dependencies {
                  add('implementation', 'org.springframework:spring-context:7.0.8')
              }
              pluginManager.apply('org.springframework.boot')
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/dependencies {
                  add('implementation', 'org.springframework:spring-context:7.0.8')
              }
              pluginManager.apply('org.springframework.boot')
              """,
            source -> source.path("spring-groovy/build.gradle")),
          buildGradleKts(
            """
              dependencies {
                  add("implementation", "org.springframework:spring-context:7.0.8")
              }
              plugins.apply("org.springframework.boot")
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/dependencies {
                  add("implementation", "org.springframework:spring-context:7.0.8")
              }
              plugins.apply("org.springframework.boot")
              """,
            source -> source.path("spring-kotlin/build.gradle.kts")),
          buildGradle(
            """
              dependencies {
                  add('implementation', 'org.junit.jupiter:junit-jupiter-api:5.10.2')
              }
              plugins.apply('java')
              """,
            source -> source.path("safe-groovy/build.gradle")),
          buildGradleKts(
            """
              dependencies {
                  add("implementation", "org.junit.jupiter:junit-jupiter-api:5.10.2")
              }
              pluginManager.apply("java")
              """,
            source -> source.path("safe-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void refusesDynamicPositionalGradleDependenciesAndReceiverPluginApplyCalls() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("dynamic-groovy",
                                          "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("dynamic-groovy",
                                          "Unresolved Gradle plugin declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("dynamic-kotlin",
                                          "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("dynamic-kotlin",
                                          "Unresolved Gradle plugin declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"))),
          buildGradle(
            """
              def notation = providers.gradleProperty('notation')
              def pluginId = providers.gradleProperty('pluginId')
              dependencies {
                  add('implementation', notation)
              }
              plugins.apply(pluginId)
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/def notation = providers.gradleProperty('notation')
              def pluginId = providers.gradleProperty('pluginId')
              dependencies {
                  add('implementation', notation)
              }
              plugins.apply(pluginId)
              """,
            source -> source.path("dynamic-groovy/build.gradle")),
          buildGradleKts(
            """
              val notation = providers.gradleProperty("notation")
              val pluginId = providers.gradleProperty("pluginId")
              dependencies {
                  add("implementation", notation)
              }
              pluginManager.apply(pluginId)
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/val notation = providers.gradleProperty("notation")
              val pluginId = providers.gradleProperty("pluginId")
              dependencies {
                  add("implementation", notation)
              }
              pluginManager.apply(pluginId)
              """,
            source -> source.path("dynamic-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void findsSpringDependenciesThroughDependencyHandlerReceiversOnly() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getOutcome,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("ordinary-groovy", "ELIGIBLE_FOR_PROFILE",
                                          "ordinary-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("ordinary-kotlin", "ELIGIBLE_FOR_PROFILE",
                                          "ordinary-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("receiver-groovy", "REFUSED",
                                          "org.springframework:spring-beans",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("receiver-groovy", "REFUSED",
                                          "org.springframework:spring-context",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("receiver-kotlin", "REFUSED",
                                          "org.springframework:spring-core",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("receiver-kotlin", "REFUSED",
                                          "org.springframework:spring-tx",
                                          "MODULE_SPRING_BUILD_RESIDUE"))),
          buildGradle(
            """
              dependencies.add('implementation',
                      'org.springframework:spring-context:7.0.8')
              project.dependencies.add('runtimeOnly',
                      'org.springframework:spring-beans:7.0.8')
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/dependencies.add('implementation',
                      'org.springframework:spring-context:7.0.8')
              project.dependencies.add('runtimeOnly',
                      'org.springframework:spring-beans:7.0.8')
              """,
            source -> source.path("receiver-groovy/build.gradle")),
          buildGradleKts(
            """
              dependencies.add("implementation",
                      "org.springframework:spring-core:7.0.8")
              project.dependencies.add("runtimeOnly",
                      "org.springframework:spring-tx:7.0.8")
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/dependencies.add("implementation",
                      "org.springframework:spring-core:7.0.8")
              project.dependencies.add("runtimeOnly",
                      "org.springframework:spring-tx:7.0.8")
              """,
            source -> source.path("receiver-kotlin/build.gradle.kts")),
          buildGradle(
            """
              def helper = new Expando()
              helper.add('implementation',
                      'org.springframework:spring-context:7.0.8')
              """,
            source -> source.path("ordinary-groovy/build.gradle")),
          buildGradleKts(
            """
              fun add(configuration: String, notation: String) = Unit
              add("implementation", "org.springframework:spring-context:7.0.8")
              """,
            source -> source.path("ordinary-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void refusesProviderDependencyHandlerOverloadsButNotOrdinarySameNameCalls() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("ordinary-groovy", "ordinary-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("ordinary-kotlin", "ordinary-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("provider-groovy",
                                          "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("provider-kotlin",
                                          "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"))),
          buildGradle(
            """
              def notation = providers.provider {
                  'org.junit.jupiter:junit-jupiter-api:5.10.2'
              }
              dependencies.addProvider('implementation', notation)
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/def notation = providers.provider {
                  'org.junit.jupiter:junit-jupiter-api:5.10.2'
              }
              dependencies.addProvider('implementation', notation)
              """,
            source -> source.path("provider-groovy/build.gradle")),
          buildGradleKts(
            """
              val notation = providers.provider {
                  "org.junit.jupiter:junit-jupiter-api:5.10.2"
              }
              project.dependencies.addProviderConvertible("implementation", notation)
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/val notation = providers.provider {
                  "org.junit.jupiter:junit-jupiter-api:5.10.2"
              }
              project.dependencies.addProviderConvertible("implementation", notation)
              """,
            source -> source.path("provider-kotlin/build.gradle.kts")),
          buildGradle(
            """
              def helper = new Expando()
              def notation = providers.provider { 'safe' }
              helper.addProvider('implementation', notation)
              """,
            source -> source.path("ordinary-groovy/build.gradle")),
          buildGradleKts(
            """
              fun addProviderConvertible(configuration: String, notation: Any) = Unit
              val notation = providers.provider { "safe" }
              addProviderConvertible("implementation", notation)
              """,
            source -> source.path("ordinary-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void classifiesDependencyHandlerGetterCallsWithoutCapturingOrdinaryGetters() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("dynamic-kotlin",
                                          "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("ordinary-groovy", "ordinary-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("safe-kotlin", "safe-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("spring-groovy",
                                          "org.springframework:spring-context",
                                          "MODULE_SPRING_BUILD_RESIDUE"))),
          buildGradle(
            """
              getDependencies().add('implementation',
                      'org.springframework:spring-context:7.0.8')
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/getDependencies().add('implementation',
                      'org.springframework:spring-context:7.0.8')
              """,
            source -> source.path("spring-groovy/build.gradle")),
          buildGradleKts(
            """
              val notation = providers.gradleProperty("notation")
              project.getDependencies().add("implementation", notation)
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/val notation = providers.gradleProperty("notation")
              project.getDependencies().add("implementation", notation)
              """,
            source -> source.path("dynamic-kotlin/build.gradle.kts")),
          buildGradleKts(
            """
              getDependencies().add("implementation",
                      "org.junit.jupiter:junit-jupiter-api:5.10.2")
              """,
            source -> source.path("safe-kotlin/build.gradle.kts")),
          buildGradle(
            """
              def helper = new Expando()
              helper.getDependencies().add('implementation',
                      'org.springframework:spring-context:7.0.8')
              """,
            source -> source.path("ordinary-groovy/build.gradle"))
        );
    }

    @Test
    void acceptsOnlyExactStaticProjectDependencyNotations() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("project-safe-groovy", "project-safe-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("project-safe-kotlin", "project-safe-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("project-unsafe-groovy",
                                          "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("project-unsafe-groovy",
                                          "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("project-unsafe-kotlin",
                                          "Unresolved Gradle dependency declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"))),
          buildGradle(
            """
              dependencies.add('implementation', project(':shared'))
              dependencies {
                  runtimeOnly project(':shared')
              }
              """,
            source -> source.path("project-safe-groovy/build.gradle")),
          buildGradleKts(
            """
              project.dependencies.add("implementation", project(":shared"))
              dependencies {
                  runtimeOnly(project(":shared"))
              }
              """,
            source -> source.path("project-safe-kotlin/build.gradle.kts")),
          buildGradle(
            """
              def modulePath = ':shared'
              def helper = new Expando()
              dependencies.add('implementation', project(modulePath))
              dependencies.add('runtimeOnly', helper.project(':shared'))
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/def modulePath = ':shared'
              def helper = new Expando()
              dependencies.add('implementation', project(modulePath))
              dependencies.add('runtimeOnly', helper.project(':shared'))
              """,
            source -> source.path("project-unsafe-groovy/build.gradle")),
          buildGradleKts(
            """
              project.dependencies.add("implementation", project(":shared", "extra"))
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/project.dependencies.add("implementation", project(":shared", "extra"))
              """,
            source -> source.path("project-unsafe-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void classifiesOwnedPluginBlocksAndPluginHandlerReceiversOnly() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("plugin-dynamic-groovy",
                                          "Unresolved Gradle plugin declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("plugin-dynamic-kotlin",
                                          "Unresolved Gradle plugin declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("plugin-ordinary-groovy", "plugin-ordinary-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("plugin-ordinary-kotlin", "plugin-ordinary-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("plugin-safe-groovy", "plugin-safe-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("plugin-safe-kotlin", "plugin-safe-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("plugin-spring-groovy", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("plugin-spring-kotlin", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"))),
          buildGradle(
            """
              getPluginManager().apply('org.springframework.boot')
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/getPluginManager().apply('org.springframework.boot')
              """,
            source -> source.path("plugin-spring-groovy/build.gradle")),
          buildGradleKts(
            """
              project.getPlugins().apply("org.springframework.boot")
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/project.getPlugins().apply("org.springframework.boot")
              """,
            source -> source.path("plugin-spring-kotlin/build.gradle.kts")),
          buildGradle(
            """
              def pluginId = providers.gradleProperty('pluginId')
              plugins {
                  apply(pluginId)
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/def pluginId = providers.gradleProperty('pluginId')
              plugins {
                  apply(pluginId)
              }
              """,
            source -> source.path("plugin-dynamic-groovy/build.gradle")),
          buildGradleKts(
            """
              val pluginId = providers.gradleProperty("pluginId")
              project.getPluginManager().apply(pluginId)
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/val pluginId = providers.gradleProperty("pluginId")
              project.getPluginManager().apply(pluginId)
              """,
            source -> source.path("plugin-dynamic-kotlin/build.gradle.kts")),
          buildGradle(
            """
              getPlugins().apply('java')
              """,
            source -> source.path("plugin-safe-groovy/build.gradle")),
          buildGradleKts(
            """
              project.pluginManager.apply("java")
              """,
            source -> source.path("plugin-safe-kotlin/build.gradle.kts")),
          buildGradle(
            """
              def helper = new Expando()
              helper.apply('org.springframework.boot')
              helper.id('org.springframework.boot')
              helper.getPluginManager().apply('org.springframework.boot')
              """,
            source -> source.path("plugin-ordinary-groovy/build.gradle")),
          buildGradleKts(
            """
              fun id(value: String) = Unit
              fun apply(value: String) = Unit
              id("org.springframework.boot")
              apply("org.springframework.boot")
              """,
            source -> source.path("plugin-ordinary-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void classifiesLegacyNamedApplyOnlyForGradleProjectOwnership() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("ordinary-groovy", "ordinary-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("ordinary-kotlin", "ordinary-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("project-groovy", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("project-kotlin", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"))),
          buildGradle(
            """
              def helper = new Expando()
              def externalScript = providers.gradleProperty('externalScript')
              helper.apply plugin: 'org.springframework.boot'
              helper.apply from: externalScript
              """,
            source -> source.path("ordinary-groovy/build.gradle")),
          buildGradleKts(
            """
              class Helper {
                  fun apply(plugin: String) = Unit
              }
              val helper = Helper()
              helper.apply(plugin = "org.springframework.boot")
              """,
            source -> source.path("ordinary-kotlin/build.gradle.kts")),
          buildGradle(
            """
              project.apply plugin: 'org.springframework.boot'
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/project.apply plugin: 'org.springframework.boot'
              """,
            source -> source.path("project-groovy/build.gradle")),
          buildGradleKts(
            """
              apply(plugin = "org.springframework.boot")
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/apply(plugin = "org.springframework.boot")
              """,
            source -> source.path("project-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void acceptsLiteralBooleanPluginApplyChainsAndRefusesDynamicValues() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getConstruct,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("dynamic-groovy",
                                          "Unresolved Gradle plugin declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("dynamic-groovy", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("dynamic-kotlin",
                                          "Unresolved Gradle plugin declaration",
                                          "MODULE_BUILD_EVIDENCE_INCOMPLETE"),
                                  tuple("safe-groovy", "safe-groovy",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("safe-kotlin", "safe-kotlin",
                                          "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("spring-groovy", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"),
                                  tuple("spring-kotlin", "org.springframework.boot",
                                          "MODULE_SPRING_BUILD_RESIDUE"))),
          buildGradle(
            """
              plugins {
                  id 'org.springframework.boot' version '4.0.0' apply false
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/plugins {
                  id 'org.springframework.boot' version '4.0.0' apply false
              }
              """,
            source -> source.path("spring-groovy/build.gradle")),
          buildGradleKts(
            """
              plugins {
                  id("org.springframework.boot").apply(false)
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/plugins {
                  id("org.springframework.boot").apply(false)
              }
              """,
            source -> source.path("spring-kotlin/build.gradle.kts")),
          buildGradle(
            """
              plugins {
                  id 'com.example.safe' apply false
              }
              """,
            source -> source.path("safe-groovy/build.gradle")),
          buildGradleKts(
            """
              plugins {
                  id("com.example.safe").apply(false)
              }
              """,
            source -> source.path("safe-kotlin/build.gradle.kts")),
          buildGradle(
            """
              def shouldApply = providers.gradleProperty('shouldApply')
              plugins {
                  id 'org.springframework.boot' apply shouldApply
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 2 blockers; no module changes were applied)~~>*/def shouldApply = providers.gradleProperty('shouldApply')
              plugins {
                  id 'org.springframework.boot' apply shouldApply
              }
              """,
            source -> source.path("dynamic-groovy/build.gradle")),
          buildGradleKts(
            """
              val shouldApply = providers.gradleProperty("shouldApply")
              plugins {
                  id("com.example.safe").apply(shouldApply)
              }
              """,
            """
              /*~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>*/val shouldApply = providers.gradleProperty("shouldApply")
              plugins {
                  id("com.example.safe").apply(shouldApply)
              }
              """,
            source -> source.path("dynamic-kotlin/build.gradle.kts"))
        );
    }

    @Test
    void doesNotUseHandlerGetterSyntaxAfterIncompatibleAttribution() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  tuple("typed-groovy", "MODULE_ELIGIBLE_FOR_PROFILE"),
                                  tuple("typed-kotlin", "MODULE_ELIGIBLE_FOR_PROFILE"))),
          buildGradle(
            """
              class LocalDependencies {
                  void add(String configuration, String notation) { }
              }
              class LocalPlugins {
                  void apply(String pluginId) { }
              }
              class LocalProjectLike {
                  LocalDependencies getDependencies() { new LocalDependencies() }
                  LocalPlugins getPluginManager() { new LocalPlugins() }
                  LocalPlugins getPlugins() { new LocalPlugins() }
                  void inspect() {
                      getDependencies().add('implementation',
                              'org.springframework:spring-context:7.0.8')
                      getPluginManager().apply('org.springframework.boot')
                      getPlugins().apply('org.springframework.boot')
                  }
              }
              new LocalProjectLike().inspect()
              """,
            source -> source.path("typed-groovy/build.gradle")),
          buildGradleKts(
            """
              class LocalDependencies {
                  fun add(configuration: String, notation: String) = Unit
              }
              class LocalPlugins {
                  fun apply(pluginId: String) = Unit
              }
              class LocalProjectLike {
                  fun getDependencies() = LocalDependencies()
                  fun getPluginManager() = LocalPlugins()
                  fun getPlugins() = LocalPlugins()
                  fun inspect() {
                      getDependencies().add("implementation",
                              "org.springframework:spring-context:7.0.8")
                      getPluginManager().apply("org.springframework.boot")
                      getPlugins().apply("org.springframework.boot")
                  }
              }
              LocalProjectLike().inspect()
              """,
            source -> source.path("typed-kotlin/build.gradle.kts"))
        );
    }

    private static SourceSpecs parseError(
            String before, Consumer<SourceSpec<ParseError>> customize) {
        Parser.Builder builder = new Parser.Builder(ParseError.class) {
            @Override
            public Parser build() {
                return new Parser() {
                    @Override
                    public Stream<SourceFile> parseInputs(
                            Iterable<Input> sources, Path relativeTo,
                            ExecutionContext ctx) {
                        return StreamSupport.stream(sources.spliterator(), false)
                                .map(input -> parseError(input, relativeTo, ctx));
                    }

                    private SourceFile parseError(
                            Input input, Path relativeTo, ExecutionContext ctx) {
                        try (EncodingDetectingInputStream source = input.getSource(ctx)) {
                            String text = source.readFully();
                            Path sourcePath = input.getRelativePath(relativeTo);
                            PlainText erroneous = PlainText.builder()
                                    .sourcePath(sourcePath)
                                    .text(text)
                                    .build();
                            return new ParseError(Tree.randomId(), Markers.EMPTY,
                                    sourcePath,
                                    input.getFileAttributes(), source.getCharset().name(),
                                    source.isCharsetBomMarked(), null, text, erroneous);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    }

                    @Override
                    public boolean accept(Path path) {
                        return true;
                    }

                    @Override
                    public Path sourcePathFromSourceText(Path prefix, String sourceCode) {
                        return prefix.resolve("parse-error.xml");
                    }
                };
            }

            @Override
            public String getDslName() {
                return "parse-error";
            }
        };
        SourceSpec<ParseError> source = new SourceSpec<ParseError>(
                ParseError.class, null, builder, before, null);
        customize.accept(source);
        return source;
    }
}
