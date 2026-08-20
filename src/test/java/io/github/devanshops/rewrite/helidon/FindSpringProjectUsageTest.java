package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.MigrationAssessmentTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.test.SourceSpecs.text;
import static org.openrewrite.yaml.Assertions.yaml;
import static org.openrewrite.xml.Assertions.xml;

class FindSpringProjectUsageTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindSpringProjectUsage())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-context", "spring-tx"))
                .cycles(2);
    }

    @DocumentExample
    @Test
    void inventoriesMainAndTestJavaByTheirFullProjectPaths() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(1)
                  .dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getSourcePath,
                                  MigrationAssessmentTable.Row::getSourceKind,
                                  MigrationAssessmentTable.Row::getConstruct,
                                  MigrationAssessmentTable.Row::getSupportLevel,
                                  MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("catalog/src/main/java/com/acme/CatalogService.java",
                                          "JAVA_MAIN", "org.springframework.transaction.annotation.Transactional",
                                          "PARTIAL", "BOUNDED_RECIPE_AVAILABLE",
                                          "SPRING_TRANSACTION_ANNOTATION"),
                                  tuple("orders/src/test/java/com/acme/OrderTest.java",
                                          "JAVA_TEST", "org.springframework.context.ApplicationContext",
                                          "MANUAL", "MANUAL_REVIEW_REQUIRED",
                                          "SPRING_JAVA_API"))),
          java(
            """
              package com.acme;

              import org.springframework.transaction.annotation.Transactional;

              class CatalogService {
                  @Transactional
                  void update() {}
              }
              """,
            """
              package com.acme;

              /*~~(PARTIAL: Transactions [SPRING_TRANSACTION_ANNOTATION] -> io.github.devanshops.rewrite.helidon.MigrateSpringTransactionalToJakarta)~~>*/import org.springframework.transaction.annotation.Transactional;

              class CatalogService {
                  @Transactional
                  void update() {}
              }
              """,
            source -> source.path("catalog/src/main/java/com/acme/CatalogService.java")),
          java(
            """
              package com.acme;

              import org.springframework.context.ApplicationContext;

              class OrderTest {
                  ApplicationContext context;
              }
              """,
            """
              package com.acme;

              /*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/import org.springframework.context.ApplicationContext;

              class OrderTest {
                  ApplicationContext context;
              }
              """,
            source -> source.path("orders/src/test/java/com/acme/OrderTest.java"))
        );
    }

    @Test
    void classifiesConventionalCustomJavaTestSourceSetsAsTests() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getSourcePath,
                                  MigrationAssessmentTable.Row::getSourceKind,
                                  MigrationAssessmentTable.Row::getConstruct)
                          .containsExactlyInAnyOrder(
                                  tuple("catalog/src/integrationTest/java/com/acme/CatalogIT.java",
                                          "JAVA_TEST",
                                          "org.springframework.context.ApplicationContext"),
                                  tuple("orders/src/functionalTest/java/com/acme/OrderFlowTest.java",
                                          "JAVA_TEST",
                                          "org.springframework.context.ApplicationContext"))),
          java(
            """
              package com.acme;

              import org.springframework.context.ApplicationContext;

              class CatalogIT {
                  ApplicationContext context;
              }
              """,
            """
              package com.acme;

              /*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/import org.springframework.context.ApplicationContext;

              class CatalogIT {
                  ApplicationContext context;
              }
              """,
            source -> source.path("catalog/src/integrationTest/java/com/acme/CatalogIT.java")),
          java(
            """
              package com.acme;

              import org.springframework.context.ApplicationContext;

              class OrderFlowTest {
                  ApplicationContext context;
              }
              """,
            """
              package com.acme;

              /*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/import org.springframework.context.ApplicationContext;

              class OrderFlowTest {
                  ApplicationContext context;
              }
              """,
            source -> source.path("orders/src/functionalTest/java/com/acme/OrderFlowTest.java"))
        );
    }

    @Test
    void offersTheBoundedRecipeOnlyForSpringTransactionalAnnotations() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getConstruct,
                                  MigrationAssessmentTable.Row::getSupportLevel,
                                  MigrationAssessmentTable.Row::getOutcome,
                                  MigrationAssessmentTable.Row::getReasonCode,
                                  MigrationAssessmentTable.Row::getSuggestedRecipeOrDirection)
                          .containsExactlyInAnyOrder(
                                  tuple("org.springframework.transaction.annotation.Transactional",
                                          "PARTIAL", "BOUNDED_RECIPE_AVAILABLE",
                                          "SPRING_TRANSACTION_ANNOTATION",
                                          "io.github.devanshops.rewrite.helidon.MigrateSpringTransactionalToJakarta"),
                                  tuple("org.springframework.transaction.PlatformTransactionManager",
                                          "MANUAL", "MANUAL_REVIEW_REQUIRED",
                                          "SPRING_TRANSACTION_INFRASTRUCTURE",
                                          "Design explicit Jakarta transaction infrastructure before migration"))),
          java(
            """
              package com.acme;

              import org.springframework.transaction.PlatformTransactionManager;
              import org.springframework.transaction.annotation.Transactional;

              class CatalogService {
                  PlatformTransactionManager manager;

                  @Transactional
                  void update() {}
              }
              """,
            """
              package com.acme;

              /*~~(MANUAL: Spring transaction infrastructure [SPRING_TRANSACTION_INFRASTRUCTURE] -> Design explicit Jakarta transaction infrastructure before migration)~~>*/import org.springframework.transaction.PlatformTransactionManager;
              /*~~(PARTIAL: Transactions [SPRING_TRANSACTION_ANNOTATION] -> io.github.devanshops.rewrite.helidon.MigrateSpringTransactionalToJakarta)~~>*/import org.springframework.transaction.annotation.Transactional;

              class CatalogService {
                  PlatformTransactionManager manager;

                  @Transactional
                  void update() {}
              }
              """,
            source -> source.path("catalog/src/main/java/com/acme/CatalogService.java"))
        );
    }

    @Test
    void inventoriesFullyQualifiedSpringJavaUsesWithoutImports() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getSourceKind,
                                  MigrationAssessmentTable.Row::getConstruct,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("JAVA_MAIN",
                                          "org.springframework.context.ApplicationContext",
                                          "SPRING_JAVA_API"),
                                  tuple("JAVA_MAIN",
                                          "org.springframework.transaction.annotation.Transactional",
                                          "SPRING_TRANSACTION_ANNOTATION"))),
          java(
            """
              package com.acme;

              class CatalogService {
                  org.springframework.context.ApplicationContext context;

                  @org.springframework.transaction.annotation.Transactional
                  void update() {}
              }
              """,
            """
              package com.acme;

              class CatalogService {
                  /*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/org.springframework.context.ApplicationContext context;

                  @/*~~(PARTIAL: Transactions [SPRING_TRANSACTION_ANNOTATION] -> io.github.devanshops.rewrite.helidon.MigrateSpringTransactionalToJakarta)~~>*/org.springframework.transaction.annotation.Transactional
                  void update() {}
              }
              """,
            source -> source.path("catalog/src/main/java/com/acme/CatalogService.java"))
        );
    }

    @Test
    void classifiesSpringTestContextInTheTestSourceSet() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().dependsOn(
                  """
                    package org.springframework.test.context;
                    public @interface ContextConfiguration {}
                    """))
                  .dataTable(MigrationAssessmentTable.Row.class, rows ->
                          assertThat(rows).singleElement().satisfies(row -> {
                              assertThat(row.getSourceKind()).isEqualTo("JAVA_TEST");
                              assertThat(row.getFeature()).isEqualTo("Spring test context");
                              assertThat(row.getConstruct()).isEqualTo(
                                      "org.springframework.test.context.ContextConfiguration");
                              assertThat(row.getReasonCode()).isEqualTo("SPRING_TEST_CONTEXT");
                          })),
          java(
            """
              package com.acme;

              import org.springframework.test.context.ContextConfiguration;

              @ContextConfiguration
              class CatalogTest {}
              """,
            """
              package com.acme;

              /*~~(MANUAL: Spring test context [SPRING_TEST_CONTEXT] -> Migrate to Helidon MP and CDI test support)~~>*/import org.springframework.test.context.ContextConfiguration;

              @ContextConfiguration
              class CatalogTest {}
              """,
            source -> source.path("catalog/src/test/java/com/acme/CatalogTest.java"))
        );
    }

    @Test
    void ignoresJavaWithoutSpringUsage() {
        rewriteRun(
          java(
            """
              package com.acme;

              class PlainJava {}
              """,
            source -> source.path("plain/src/main/java/com/acme/PlainJava.java"))
        );
    }

    @Test
    void inventoriesSpringMavenParentBomDependencyAndPlugin() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(4);
              assertThat(rows)
                      .extracting(MigrationAssessmentTable.Row::getSourcePath,
                              MigrationAssessmentTable.Row::getSourceKind,
                              MigrationAssessmentTable.Row::getConstruct,
                              MigrationAssessmentTable.Row::getSupportLevel,
                              MigrationAssessmentTable.Row::getOutcome,
                              MigrationAssessmentTable.Row::getReasonCode)
                      .containsExactlyInAnyOrder(
                              tuple("inventory/pom.xml", "MAVEN",
                                      "org.springframework.boot:spring-boot-starter-parent",
                                      "PARTIAL", "BOUNDED_RECIPE_AVAILABLE", "SPRING_MAVEN_PARENT"),
                              tuple("inventory/pom.xml", "MAVEN",
                                      "org.springframework.boot:spring-boot-dependencies",
                                      "PARTIAL", "BOUNDED_RECIPE_AVAILABLE", "SPRING_MAVEN_BOM"),
                              tuple("inventory/pom.xml", "MAVEN",
                                      "org.springframework:spring-context",
                                      "PARTIAL", "BOUNDED_RECIPE_AVAILABLE", "SPRING_MAVEN_DEPENDENCY"),
                              tuple("inventory/pom.xml", "MAVEN",
                                      "org.springframework.boot:spring-boot-maven-plugin",
                                      "PARTIAL", "BOUNDED_RECIPE_AVAILABLE", "SPRING_MAVEN_PLUGIN"));
              assertThat(rows).allMatch(row ->
                      "io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp"
                              .equals(row.getSuggestedRecipeOrDirection()));
          }),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-parent</artifactId>
                      <version>4.0.0</version>
                  </parent>
                  <groupId>com.acme</groupId>
                  <artifactId>inventory</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                      <dependencies>
                          <dependency>
                              <groupId>org.springframework.boot</groupId>
                              <artifactId>spring-boot-dependencies</artifactId>
                              <version>4.0.0</version>
                              <type>pom</type>
                              <scope>import</scope>
                          </dependency>
                      </dependencies>
                  </dependencyManagement>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework</groupId>
                          <artifactId>spring-context</artifactId>
                      </dependency>
                  </dependencies>
                  <build>
                      <plugins>
                          <plugin>
                              <groupId>org.springframework.boot</groupId>
                              <artifactId>spring-boot-maven-plugin</artifactId>
                          </plugin>
                      </plugins>
                  </build>
              </project>
              """,
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <!--~~(PARTIAL: Spring Maven parent [SPRING_MAVEN_PARENT] -> io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp)~~>--><parent>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-parent</artifactId>
                      <version>4.0.0</version>
                  </parent>
                  <groupId>com.acme</groupId>
                  <artifactId>inventory</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                      <dependencies>
                          <!--~~(PARTIAL: Spring Maven BOM [SPRING_MAVEN_BOM] -> io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp)~~>--><dependency>
                              <groupId>org.springframework.boot</groupId>
                              <artifactId>spring-boot-dependencies</artifactId>
                              <version>4.0.0</version>
                              <type>pom</type>
                              <scope>import</scope>
                          </dependency>
                      </dependencies>
                  </dependencyManagement>
                  <dependencies>
                      <!--~~(PARTIAL: Spring Maven dependency [SPRING_MAVEN_DEPENDENCY] -> io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp)~~>--><dependency>
                          <groupId>org.springframework</groupId>
                          <artifactId>spring-context</artifactId>
                      </dependency>
                  </dependencies>
                  <build>
                      <plugins>
                          <!--~~(PARTIAL: Spring Maven plugin [SPRING_MAVEN_PLUGIN] -> io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp)~~>--><plugin>
                              <groupId>org.springframework.boot</groupId>
                              <artifactId>spring-boot-maven-plugin</artifactId>
                          </plugin>
                      </plugins>
                  </build>
              </project>
              """,
            source -> source.path("inventory/pom.xml"))
        );
    }

    @Test
    void classifiesImportedSpringFrameworkAndCloudCoordinatesAsMavenBoms() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getConstruct,
                                  MigrationAssessmentTable.Row::getFeature,
                                  MigrationAssessmentTable.Row::getSupportLevel,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("org.springframework:spring-framework-bom",
                                          "Spring Maven BOM", "PARTIAL", "SPRING_MAVEN_BOM"),
                                  tuple("org.springframework.cloud:spring-cloud-dependencies",
                                          "Spring Maven BOM", "PARTIAL", "SPRING_MAVEN_BOM"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>catalog</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                      <dependencies>
                          <dependency>
                              <groupId>org.springframework</groupId>
                              <artifactId>spring-framework-bom</artifactId>
                              <version>7.0.8</version>
                              <type>pom</type>
                              <scope>import</scope>
                          </dependency>
                          <dependency>
                              <groupId>org.springframework.cloud</groupId>
                              <artifactId>spring-cloud-dependencies</artifactId>
                              <version>2025.1.2</version>
                              <type>pom</type>
                              <scope>import</scope>
                          </dependency>
                      </dependencies>
                  </dependencyManagement>
              </project>
              """,
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>catalog</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                      <dependencies>
                          <!--~~(PARTIAL: Spring Maven BOM [SPRING_MAVEN_BOM] -> io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp)~~>--><dependency>
                              <groupId>org.springframework</groupId>
                              <artifactId>spring-framework-bom</artifactId>
                              <version>7.0.8</version>
                              <type>pom</type>
                              <scope>import</scope>
                          </dependency>
                          <!--~~(PARTIAL: Spring Maven BOM [SPRING_MAVEN_BOM] -> io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp)~~>--><dependency>
                              <groupId>org.springframework.cloud</groupId>
                              <artifactId>spring-cloud-dependencies</artifactId>
                              <version>2025.1.2</version>
                              <type>pom</type>
                              <scope>import</scope>
                          </dependency>
                      </dependencies>
                  </dependencyManagement>
              </project>
              """,
            source -> source.path("catalog/pom.xml"))
        );
    }

    @Test
    void ignoresMavenBuildWithoutSpringCoordinates() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>plain</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>jakarta.ws.rs</groupId>
                          <artifactId>jakarta.ws.rs-api</artifactId>
                          <version>3.1.0</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            source -> source.path("plain/pom.xml"))
        );
    }

    @Test
    void inventoriesPropertyKeysWithoutExposingTheirValues() {
        String secret = "SPRING_SENTINEL_SECRET_7f3e";
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(5);
              assertThat(rows)
                      .extracting(MigrationAssessmentTable.Row::getSourcePath,
                              MigrationAssessmentTable.Row::getSourceKind,
                              MigrationAssessmentTable.Row::getConstruct,
                              MigrationAssessmentTable.Row::getReasonCode)
                      .containsExactlyInAnyOrder(
                              tuple("billing/src/main/resources/application-prod.properties",
                                      "SPRING_PROPERTIES_PROFILE", "spring.config.import",
                                      "SPRING_CONFIG_TREE_IMPORT"),
                              tuple("billing/src/main/resources/application-prod.properties",
                                      "SPRING_PROPERTIES_PROFILE", "spring.profiles.group.production",
                                      "SPRING_PROFILE_GROUP"),
                              tuple("billing/src/main/resources/application-prod.properties",
                                      "SPRING_PROPERTIES_PROFILE", "spring.profiles.include",
                                      "SPRING_PROFILE_INCLUDE"),
                              tuple("billing/src/main/resources/application-prod.properties",
                                      "SPRING_PROPERTIES_PROFILE", "spring.datasource.password",
                                      "SPRING_CONFIGURATION_KEY"),
                              tuple("billing/src/main/resources/application-prod.properties",
                                      "SPRING_PROPERTIES_PROFILE", "acme.orders.timeout",
                                      "APPLICATION_CONFIGURATION_KEY"));
              assertThat(rows).allSatisfy(row ->
                      assertThat(String.join("|",
                              row.getSourcePath(), row.getSourceKind(), row.getFeature(),
                              row.getConstruct(), row.getSupportLevel(), row.getOutcome(),
                              row.getReasonCode(), row.getReason(),
                              row.getSuggestedRecipeOrDirection())).doesNotContain(secret));
          }),
          properties(
            """
              spring.config.import=optional:configtree:/run/secrets/SPRING_SENTINEL_SECRET_7f3e
              spring.profiles.group.production=prod,cloud
              spring.profiles.include=local
              spring.datasource.password=SPRING_SENTINEL_SECRET_7f3e
              acme.orders.timeout=20s
              """,
            source -> source.path("billing/src/main/resources/application-prod.properties"))
        );
    }

    @Test
    void ignoresUnrelatedPropertiesFiles() {
        rewriteRun(
          properties(
            """
              acme.orders.timeout=20s
              """,
            source -> source.path("billing/src/main/resources/messages.properties"))
        );
    }

    @Test
    void inventoriesNestedYamlKeysWithoutExposingTheirValues() {
        String secret = "SPRING_SENTINEL_SECRET_9b1a";
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(4);
              assertThat(rows)
                      .extracting(MigrationAssessmentTable.Row::getSourceKind,
                              MigrationAssessmentTable.Row::getConstruct,
                              MigrationAssessmentTable.Row::getReasonCode)
                      .containsExactlyInAnyOrder(
                              tuple("SPRING_YAML_PROFILE", "spring.config.import",
                                      "SPRING_CONFIG_TREE_IMPORT"),
                              tuple("SPRING_YAML_PROFILE", "spring.config.additional-location",
                                      "SPRING_CONFIG_CUSTOM_LOCATION"),
                              tuple("SPRING_YAML_PROFILE", "spring.profiles.group.production",
                                      "SPRING_PROFILE_GROUP"),
                              tuple("SPRING_YAML_PROFILE", "application.token",
                                      "APPLICATION_CONFIGURATION_KEY"));
              assertThat(rows).allSatisfy(row ->
                      assertThat(String.join("|", row.getConstruct(), row.getReason(),
                              row.getSuggestedRecipeOrDirection())).doesNotContain(secret));
          }),
          yaml(
            """
              spring:
                config:
                  import: optional:configtree:/run/secrets/SPRING_SENTINEL_SECRET_9b1a
                  additional-location: file:/opt/acme/config
                profiles:
                  group:
                    production: prod,cloud
              application:
                token: SPRING_SENTINEL_SECRET_9b1a
              """,
            source -> source.path("shipping/src/main/resources/application-cloud.yml"))
        );
    }

    @Test
    void preservesRepeatedYamlKeysAcrossDocuments() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(2);
              assertThat(rows)
                      .allSatisfy(row -> {
                          assertThat(row.getConstruct()).isEqualTo("spring.datasource.url");
                          assertThat(row.getReasonCode()).isEqualTo("SPRING_CONFIGURATION_KEY");
                      });
          }),
          yaml(
            """
              spring:
                datasource:
                  url: jdbc:h2:mem:first
              ---
              spring:
                datasource:
                  url: jdbc:h2:mem:second
              """,
            source -> source.path("shipping/src/main/resources/application.yml"))
        );
    }

    @Test
    void ignoresUnrelatedYamlFiles() {
        rewriteRun(
          yaml(
            """
              greeting:
                message: hello
              """,
            source -> source.path("shipping/src/main/resources/messages.yml"))
        );
    }

    @Test
    void inventoriesSpringXmlNamespacesBeansComponentScansAndTransactionAdvice() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(4);
              assertThat(rows)
                      .extracting(MigrationAssessmentTable.Row::getSourceKind,
                              MigrationAssessmentTable.Row::getConstruct,
                              MigrationAssessmentTable.Row::getSupportLevel,
                              MigrationAssessmentTable.Row::getReasonCode)
                      .containsExactlyInAnyOrder(
                              tuple("SPRING_XML", "Spring XML namespace", "MANUAL",
                                      "SPRING_XML_NAMESPACE"),
                              tuple("SPRING_XML", "bean", "MANUAL", "SPRING_XML_BEAN"),
                              tuple("SPRING_XML", "context:component-scan", "MANUAL",
                                      "SPRING_XML_COMPONENT_SCAN"),
                              tuple("SPRING_XML", "tx:advice", "MANUAL",
                                      "SPRING_XML_TRANSACTION_ADVICE"));
          }),
          xml(
            """
              <beans xmlns="http://www.springframework.org/schema/beans"
                     xmlns:context="http://www.springframework.org/schema/context"
                     xmlns:tx="http://www.springframework.org/schema/tx">
                  <context:component-scan base-package="com.acme"/>
                  <bean id="catalog" class="com.acme.CatalogService"/>
                  <tx:advice transaction-manager="txManager"/>
              </beans>
              """,
            source -> source.path("legacy/src/main/resources/application-context.xml"))
        );
    }

    @Test
    void resolvesSpringXmlConstructsByNamespaceRatherThanPrefix() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getConstruct,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("Spring XML namespace", "SPRING_XML_NAMESPACE"),
                                  tuple("c:component-scan", "SPRING_XML_COMPONENT_SCAN"),
                                  tuple("transactions:advice", "SPRING_XML_TRANSACTION_ADVICE"))),
          xml(
            """
              <beans xmlns="http://www.springframework.org/schema/beans"
                     xmlns:c="http://www.springframework.org/schema/context"
                     xmlns:transactions="http://www.springframework.org/schema/tx">
                  <c:component-scan base-package="com.acme"/>
                  <transactions:advice transaction-manager="txManager"/>
              </beans>
              """,
            source -> source.path("legacy/src/main/resources/alternate-prefixes.xml"))
        );
    }

    @Test
    void ignoresXmlWithoutSpringConstructs() {
        rewriteRun(
          xml(
            """
              <catalog xmlns="urn:acme:catalog">
                  <bean id="one"/>
              </catalog>
              """,
            source -> source.path("legacy/src/main/resources/catalog.xml"))
        );
    }

    @Test
    void preservesEveryRepeatedSpringXmlOccurrence() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(3);
              assertThat(rows)
                      .filteredOn(row -> "SPRING_XML_BEAN".equals(row.getReasonCode()))
                      .hasSize(2);
          }),
          xml(
            """
              <beans xmlns="http://www.springframework.org/schema/beans">
                  <bean id="first" class="com.acme.Service"/>
                  <bean id="second" class="com.acme.Service"/>
              </beans>
              """,
            source -> source.path("repeated/src/main/resources/beans.xml"))
        );
    }

    @Test
    void keepsValueBearingSpringXmlTableOnly() {
        String secret = "SPRING_SENTINEL_SECRET_f4a9";
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(2);
              assertThat(rows).allSatisfy(row ->
                      assertThat(String.join("|", row.getConstruct(), row.getReason(),
                              row.getSuggestedRecipeOrDirection())).doesNotContain(secret));
          }),
          xml(
            """
              <beans xmlns="http://www.springframework.org/schema/beans"
                     xmlns:p="http://www.springframework.org/schema/p">
                  <bean id="credentials" class="com.acme.Credentials"
                        p:password="SPRING_SENTINEL_SECRET_f4a9"/>
              </beans>
              """,
            source -> source.path("secure/src/main/resources/credentials.xml"))
        );
    }

    @Test
    void keepsNonBeanSpringXmlValuesTableOnly() {
        String secret = "SPRING_SENTINEL_SECRET_13c7";
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).singleElement().satisfies(row -> {
                  assertThat(row.getReasonCode()).isEqualTo("SPRING_XML_NAMESPACE");
                  assertThat(String.join("|", row.getConstruct(), row.getReason(),
                          row.getSuggestedRecipeOrDirection())).doesNotContain(secret);
              });
          }),
          xml(
            """
              <beans xmlns="http://www.springframework.org/schema/beans"
                     xmlns:util="http://www.springframework.org/schema/util">
                  <util:properties id="credentials">
                      <prop key="password">SPRING_SENTINEL_SECRET_13c7</prop>
                  </util:properties>
              </beans>
              """,
            source -> source.path("secure/src/main/resources/secrets.xml"))
        );
    }

    @Test
    void inventoriesSpringFactoriesKeysWithoutRegistrationValues() {
        String secret = "SPRING_SENTINEL_SECRET_42d0";
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(2);
              assertThat(rows)
                      .extracting(MigrationAssessmentTable.Row::getSourceKind,
                              MigrationAssessmentTable.Row::getConstruct,
                              MigrationAssessmentTable.Row::getReasonCode)
                      .containsExactlyInAnyOrder(
                              tuple("SPRING_FACTORIES",
                                      "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                                      "SPRING_FACTORIES_ENTRY"),
                              tuple("SPRING_FACTORIES",
                                      "org.springframework.context.ApplicationListener",
                                      "SPRING_FACTORIES_ENTRY"));
              assertThat(rows).allSatisfy(row ->
                      assertThat(String.join("|", row.getConstruct(), row.getReason(),
                              row.getSuggestedRecipeOrDirection())).doesNotContain(secret));
          }),
          properties(
            """
              org.springframework.boot.autoconfigure.EnableAutoConfiguration=com.acme.SPRING_SENTINEL_SECRET_42d0
              org.springframework.context.ApplicationListener=com.acme.Listener
              """,
            source -> source.path("catalog/src/main/resources/META-INF/spring.factories"))
        );
    }

    @Test
    void ignoresCommentOnlySpringFactories() {
        rewriteRun(
          properties(
            """
              # No Spring factories are registered.
              """,
            source -> source.path("catalog/src/main/resources/META-INF/spring.factories"))
        );
    }

    @Test
    void inventoriesAutoConfigurationImportsWithoutRegisteredClassNames() {
        String secret = "SPRING_SENTINEL_SECRET_d921";
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).singleElement().satisfies(row -> {
                  assertThat(row.getSourceKind()).isEqualTo("SPRING_AUTOCONFIG_IMPORTS");
                  assertThat(row.getConstruct()).isEqualTo("AutoConfiguration.imports entry");
                  assertThat(row.getReasonCode()).isEqualTo("SPRING_AUTOCONFIG_IMPORTS");
                  assertThat(String.join("|", row.getConstruct(), row.getReason(),
                          row.getSuggestedRecipeOrDirection())).doesNotContain(secret);
              });
          }),
          text(
            """
              com.acme.CatalogAutoConfiguration
              com.acme.SPRING_SENTINEL_SECRET_d921
              """,
            source -> source.path("catalog/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
        );
    }

    @Test
    void ignoresCommentOnlyAutoConfigurationImports() {
        rewriteRun(
          text(
            """
              # No auto-configurations are registered.
              """,
            source -> source.path("catalog/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
        );
    }

    @Test
    void inventoriesParseableSpringGradlePluginBomAndDependencyDeclarations() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows -> {
              assertThat(rows).hasSize(3);
              assertThat(rows)
                      .extracting(MigrationAssessmentTable.Row::getSourcePath,
                              MigrationAssessmentTable.Row::getSourceKind,
                              MigrationAssessmentTable.Row::getConstruct,
                              MigrationAssessmentTable.Row::getSupportLevel,
                              MigrationAssessmentTable.Row::getReasonCode)
                      .containsExactlyInAnyOrder(
                              tuple("payments/build.gradle", "GRADLE_GROOVY",
                                      "org.springframework.boot", "MANUAL",
                                      "SPRING_GRADLE_PLUGIN"),
                              tuple("payments/build.gradle", "GRADLE_GROOVY",
                                      "org.springframework.boot:spring-boot-dependencies", "MANUAL",
                                      "SPRING_GRADLE_BOM"),
                              tuple("payments/build.gradle", "GRADLE_GROOVY",
                                      "org.springframework:spring-context", "MANUAL",
                                      "SPRING_GRADLE_DEPENDENCY"));
          }),
          buildGradle(
            """
              plugins {
                  id 'org.springframework.boot' version '4.0.0'
              }

              dependencies {
                  implementation platform('org.springframework.boot:spring-boot-dependencies:4.0.0')
                  implementation 'org.springframework:spring-context:7.0.0'
              }
              """,
            """
              plugins {
                  /*~~(MANUAL: Spring Gradle plugin [SPRING_GRADLE_PLUGIN] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/id 'org.springframework.boot' version '4.0.0'
              }

              dependencies {
                  implementation /*~~(MANUAL: Spring Gradle BOM [SPRING_GRADLE_BOM] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/platform('org.springframework.boot:spring-boot-dependencies:4.0.0')
                  /*~~(MANUAL: Spring Gradle dependency [SPRING_GRADLE_DEPENDENCY] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/implementation 'org.springframework:spring-context:7.0.0'
              }
              """,
            source -> source.path("payments/build.gradle"))
        );
    }

    @Test
    void inventoriesLiteralSpringDependencyManagementMavenBoms() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getSourceKind,
                                  MigrationAssessmentTable.Row::getConstruct,
                                  MigrationAssessmentTable.Row::getFeature,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("GRADLE_GROOVY",
                                          "org.springframework.cloud:spring-cloud-dependencies",
                                          "Spring Gradle BOM", "SPRING_GRADLE_BOM"),
                                  tuple("GRADLE_KOTLIN",
                                          "org.springframework:spring-framework-bom",
                                          "Spring Gradle BOM", "SPRING_GRADLE_BOM"))),
          buildGradle(
            """
              dependencyManagement {
                  imports {
                      mavenBom 'org.springframework.cloud:spring-cloud-dependencies:2025.1.2'
                  }
              }
              """,
            """
              dependencyManagement {
                  imports {
                      /*~~(MANUAL: Spring Gradle BOM [SPRING_GRADLE_BOM] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/mavenBom 'org.springframework.cloud:spring-cloud-dependencies:2025.1.2'
                  }
              }
              """,
            source -> source.path("catalog/build.gradle")),
          buildGradleKts(
            """
              dependencyManagement {
                  imports {
                      mavenBom("org.springframework:spring-framework-bom:7.0.8")
                  }
              }
              """,
            """
              dependencyManagement {
                  imports {
                      /*~~(MANUAL: Spring Gradle BOM [SPRING_GRADLE_BOM] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/mavenBom("org.springframework:spring-framework-bom:7.0.8")
                  }
              }
              """,
            source -> source.path("catalog/build.gradle.kts"))
        );
    }

    @Test
    void ignoresComputedSpringDependencyManagementMavenBomCoordinates() {
        rewriteRun(
          buildGradle(
            """
              def springBom = 'org.springframework.cloud:spring-cloud-dependencies:2025.1.2'

              dependencyManagement {
                  imports {
                      mavenBom springBom
                  }
              }
              """,
            source -> source.path("dynamic/build.gradle")),
          buildGradleKts(
            """
              val springBom = "org.springframework:spring-framework-bom:7.0.8"

              dependencyManagement {
                  imports {
                      mavenBom(springBom)
                  }
              }
              """,
            source -> source.path("dynamic/build.gradle.kts"))
        );
    }

    @Test
    void inventoriesCommonGroovyGradleMapDependencyNotation() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row -> {
                      assertThat(row.getSourceKind()).isEqualTo("GRADLE_GROOVY");
                      assertThat(row.getConstruct()).isEqualTo(
                              "org.springframework:spring-context");
                      assertThat(row.getReasonCode()).isEqualTo("SPRING_GRADLE_DEPENDENCY");
                  })),
          buildGradle(
            """
              dependencies {
                  implementation group: 'org.springframework', name: 'spring-context', version: '7.0.0'
              }
              """,
            """
              dependencies {
                  /*~~(MANUAL: Spring Gradle dependency [SPRING_GRADLE_DEPENDENCY] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/implementation group: 'org.springframework', name: 'spring-context', version: '7.0.0'
              }
              """,
            source -> source.path("payments/build.gradle"))
        );
    }

    @Test
    void inventoriesLiteralKotlinGradleDeclarations() {
        rewriteRun(
          spec -> spec.dataTable(MigrationAssessmentTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(MigrationAssessmentTable.Row::getSourceKind,
                                  MigrationAssessmentTable.Row::getConstruct,
                                  MigrationAssessmentTable.Row::getReasonCode)
                          .containsExactlyInAnyOrder(
                                  tuple("GRADLE_KOTLIN", "org.springframework.boot",
                                          "SPRING_GRADLE_PLUGIN"),
                                  tuple("GRADLE_KOTLIN",
                                          "org.springframework.boot:spring-boot-dependencies",
                                          "SPRING_GRADLE_BOM"),
                                  tuple("GRADLE_KOTLIN", "org.springframework:spring-context",
                                          "SPRING_GRADLE_DEPENDENCY"))),
          buildGradleKts(
            """
              plugins {
                  id("org.springframework.boot") version "4.0.0"
              }

              dependencies {
                  implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))
                  implementation("org.springframework:spring-context:7.0.0")
              }
              """,
            """
              plugins {
                  /*~~(MANUAL: Spring Gradle plugin [SPRING_GRADLE_PLUGIN] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/id("org.springframework.boot") version "4.0.0"
              }

              dependencies {
                  implementation(/*~~(MANUAL: Spring Gradle BOM [SPRING_GRADLE_BOM] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))
                  /*~~(MANUAL: Spring Gradle dependency [SPRING_GRADLE_DEPENDENCY] -> Migrate the Gradle build with an explicit Helidon module policy)~~>*/implementation("org.springframework:spring-context:7.0.0")
              }
              """,
            source -> source.path("payments/build.gradle.kts"))
        );
    }

    @Test
    void ignoresKotlinGradleBuildWithoutSpringDeclarations() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }

              dependencies {
                  implementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
              }
              """,
            source -> source.path("plain/build.gradle.kts"))
        );
    }

    @Test
    void ignoresParseableGradleBuildWithoutSpringDeclarations() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              dependencies {
                  implementation 'jakarta.ws.rs:jakarta.ws.rs-api:3.1.0'
              }
              """,
            source -> source.path("plain/build.gradle"))
        );
    }
}
