package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.maven.Assertions.pomXml;

class PrepareMavenBuildForHelidonMpTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new PrepareMavenBuildForHelidonMp())
                .parser(JavaParser.fromJavaVersion().classpath("spring-boot"));
    }

    @DocumentExample
    @Test
    void addsHelidonAlongsideSpringAndPreservesParent() {
        rewriteRun(
          mavenProject("platform",
            pomXml(
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme.platform</groupId>
                    <artifactId>service-parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>orders</module>
                    </modules>
                </project>
                """,
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme.platform</groupId>
                    <artifactId>service-parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>orders</module>
                    </modules>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>io.helidon</groupId>
                                <artifactId>helidon-dependencies</artifactId>
                                <version>4.5.3</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """),
            mavenProject("orders",
              pomXml(
                """
                  <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                          <groupId>com.acme.platform</groupId>
                          <artifactId>service-parent</artifactId>
                          <version>1.0.0</version>
                      </parent>
                      <artifactId>orders</artifactId>
                      <dependencies>
                          <dependency>
                              <groupId>org.springframework.boot</groupId>
                              <artifactId>spring-boot</artifactId>
                              <version>3.5.0</version>
                          </dependency>
                      </dependencies>
                  </project>
                  """,
                """
                  <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                          <groupId>com.acme.platform</groupId>
                          <artifactId>service-parent</artifactId>
                          <version>1.0.0</version>
                      </parent>
                      <artifactId>orders</artifactId>
                      <dependencyManagement>
                          <dependencies>
                              <dependency>
                                  <groupId>io.helidon</groupId>
                                  <artifactId>helidon-dependencies</artifactId>
                                  <version>4.5.3</version>
                                  <type>pom</type>
                                  <scope>import</scope>
                              </dependency>
                          </dependencies>
                      </dependencyManagement>
                      <dependencies>
                          <dependency>
                              <groupId>io.helidon.microprofile.bundles</groupId>
                              <artifactId>helidon-microprofile-core</artifactId>
                          </dependency>
                          <dependency>
                              <groupId>org.springframework.boot</groupId>
                              <artifactId>spring-boot</artifactId>
                              <version>3.5.0</version>
                          </dependency>
                      </dependencies>
                  </project>
                  """),
              srcMainJava(java(
                """
                  package com.acme.orders;

                  import org.springframework.boot.SpringApplication;

                  class OrdersApplication {
                      void start(String[] args) {
                          SpringApplication.run(OrdersApplication.class, args);
                      }
                  }
                  """))))
        );
    }

    @Test
    void doesNotTouchAnUnrelatedMavenProject() {
        rewriteRun(
          mavenProject("library",
            pomXml(
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>library</artifactId>
                    <version>1.0.0</version>
                </project>
                """),
            srcMainJava(java(
              """
                package com.acme;

                class Library {}
                """)))
        );
    }

    @Test
    void importsManagementIntoADirectBootParentModule() {
        rewriteRun(
          mavenProject("dataset-service",
            pomXml(
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>4.1.0</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.acme.platform</groupId>
                    <artifactId>dataset-service</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>4.1.0</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.acme.platform</groupId>
                    <artifactId>dataset-service</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>io.helidon</groupId>
                                <artifactId>helidon-dependencies</artifactId>
                                <version>4.5.3</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.helidon.microprofile.bundles</groupId>
                            <artifactId>helidon-microprofile-core</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """),
            srcMainJava(java(
              """
                package com.acme.platform;

                import org.springframework.boot.SpringApplication;

                class DatasetServiceApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(DatasetServiceApplication.class, args);
                    }
                }
                """)))
        );
    }
}
