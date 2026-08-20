package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateSpringNamedBeansToCdiTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringNamedBeansToCdi())
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-beans", "spring-context", "spring-tx", "spring-web",
                        "jakarta.enterprise.cdi-api", "jakarta.inject-api"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void preservesExplicitNamesAndUsesSingletonProducerScope() {
        rewriteRun(
          java(
            """
              package com.example.clients;

              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.stereotype.Component;

              @Configuration(proxyBeanMethods = false)
              class ClientConfiguration {
                  @Bean(name = "primaryClient", destroyMethod = "")
                  Object client() {
                      return new Object();
                  }

                  @Bean(name = "region", destroyMethod = "")
                  String region() {
                      return "example";
                  }
              }

              @Component("auditService")
              class AuditService {
              }

              class ClientConsumer {
                  @Autowired
                  @Qualifier("primaryClient")
                  Object client;
              }
              """,
            """
              package com.example.clients;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.enterprise.inject.Produces;
              import jakarta.inject.Named;
              import jakarta.inject.Singleton;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.context.annotation.Configuration;

              @ApplicationScoped
              @Configuration(proxyBeanMethods = false)
              class ClientConfiguration {
                  @Named("primaryClient")
                  @Produces
                  @Singleton
                  Object client() {
                      return new Object();
                  }

                  @Named("region")
                  @Produces
                  @Singleton
                  String region() {
                      return "example";
                  }
              }

              @ApplicationScoped
              @Named("auditService")
              class AuditService {
              }

              class ClientConsumer {
                  @Autowired
                  @Qualifier("primaryClient")
                  Object client;
              }
              """
          )
        );
    }

    @Test
    void requiresLiteralDestroyInferenceOptOutForNamedProducers() {
        rewriteRun(
          java(
            """
              package com.example.lifecycle;

              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              class LifecycleNames {
                  static final String NONE = "";
              }

              @Configuration(proxyBeanMethods = false)
              class DefaultLifecycleConfiguration {
                  @Bean(name = "client")
                  Object client() {
                      return new Object();
                  }
              }

              @Configuration(proxyBeanMethods = false)
              class ConstantOptOutConfiguration {
                  @Bean(name = "client", destroyMethod = LifecycleNames.NONE)
                  Object client() {
                      return new Object();
                  }
              }

              @Configuration(proxyBeanMethods = false)
              class ExplicitOptOutConfiguration {
                  @Bean(name = "primaryClient", destroyMethod = "")
                  Object client() {
                      return new Object();
                  }
              }
              """,
            """
              package com.example.lifecycle;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.enterprise.inject.Produces;
              import jakarta.inject.Named;
              import jakarta.inject.Singleton;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              class LifecycleNames {
                  static final String NONE = "";
              }

              /*~~(Manual migration: unsupported @Bean methods require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              class DefaultLifecycleConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean(name = "client")
                  Object client() {
                      return new Object();
                  }
              }

              /*~~(Manual migration: unsupported @Bean methods require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              class ConstantOptOutConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean(name = "client", destroyMethod = LifecycleNames.NONE)
                  Object client() {
                      return new Object();
                  }
              }

              @ApplicationScoped
              @Configuration(proxyBeanMethods = false)
              class ExplicitOptOutConfiguration {
                  @Named("primaryClient")
                  @Produces
                  @Singleton
                  Object client() {
                      return new Object();
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesNamedBeansInsideProxiedConfigurationAtomically() {
        rewriteRun(
          java(
            """
              package com.example.config;

              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              @Configuration
              class ClientConfiguration {
                  @Bean(name = "primaryClient")
                  Object client() {
                      return new Object();
                  }
              }
              """,
            """
              package com.example.config;

              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              /*~~(Manual migration: proxied @Configuration semantics require CDI redesign)~~>*/@Configuration
              class ClientConfiguration {
                  /*~~(Manual migration: @Bean belongs to a proxied @Configuration and must migrate atomically)~~>*/@Bean(name = "primaryClient")
                  Object client() {
                      return new Object();
                  }
              }
              """
          )
        );
    }

    @Test
    void marksAliasesLifecycleAttributesAndNonLiteralNames() {
        rewriteRun(
          java(
            """
              package com.example.names;

              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.context.annotation.Bean;
              import org.springframework.stereotype.Component;

              class Names {
                  static final String CLIENT = "client";
              }

              @Component(Names.CLIENT)
              class Client {
              }

              class ClientConfiguration {
                  @Bean(name = {"primaryClient", "clientAlias"}, destroyMethod = "close")
                  Object client() {
                      return new Object();
                  }
              }

              class Consumer {
                  @Qualifier(Names.CLIENT)
                  Client client;
              }
              """,
            """
              package com.example.names;

              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.context.annotation.Bean;
              import org.springframework.stereotype.Component;

              class Names {
                  static final String CLIENT = "client";
              }

              /*~~(Manual migration: named stereotype is not one literal CDI name)~~>*/@Component(Names.CLIENT)
              class Client {
              }

              class ClientConfiguration {
                  /*~~(Manual migration: bean aliases or lifecycle attributes need CDI producer review)~~>*/@Bean(name = {"primaryClient", "clientAlias"}, destroyMethod = "close")
                  Object client() {
                      return new Object();
                  }
              }

              class Consumer {
                  @Qualifier(Names.CLIENT)
                  Client client;
              }
              """
          )
        );
    }

    @Test
    void preservesNamedBeanForUnsafeInjectionConstructorAndLifecycleSemantics() {
        rewriteRun(
          java(
            """
              package com.example.safety;

              import java.time.Duration;
              import java.util.List;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.context.annotation.Lazy;
              import org.springframework.stereotype.Component;

              @Component("handlerRegistry")
              class HandlerRegistry {
                  HandlerRegistry(List<Object> handlers) {
                  }
              }

              @Component("worker")
              class Worker {
                  @Autowired
                  final Object dependency = null;
              }

              @Component("timedWorker")
              class TimedWorker {
                  @Value("${timeout}")
                  Duration timeout;
              }

              @Component("configuredWorker")
              class ConfiguredWorker {
                  @Value("${attempts:3}")
                  int attempts;
              }

              @Component("lazyWorker")
              @Lazy
              class LazyWorker {
              }

              @Configuration(proxyBeanMethods = false)
              class ResourceConfiguration {
                  @Bean(name = "resource")
                  AutoCloseable resource() {
                      return () -> { };
                  }
              }
              """,
            """
              package com.example.safety;

              import java.time.Duration;
              import java.util.List;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.context.annotation.Lazy;
              import org.springframework.stereotype.Component;

              /*~~(Manual migration: this named Spring stereotype cannot become a CDI bean with safe constructor injection)~~>*/@Component("handlerRegistry")
              class HandlerRegistry {
                  HandlerRegistry(List<Object> handlers) {
                  }
              }

              /*~~(Manual migration: unsupported @Autowired members require atomic CDI bean redesign)~~>*/@Component("worker")
              class Worker {
                  @Autowired
                  final Object dependency = null;
              }

              /*~~(Manual migration: unsupported @Value members require atomic CDI bean redesign)~~>*/@Component("timedWorker")
              class TimedWorker {
                  @Value("${timeout}")
                  Duration timeout;
              }

              /*~~(Manual migration: unsupported @Value members require atomic CDI bean redesign)~~>*/@Component("configuredWorker")
              class ConfiguredWorker {
                  @Value("${attempts:3}")
                  int attempts;
              }

              /*~~(Manual migration: Spring scope, lifecycle, or conditional annotations require atomic CDI bean redesign)~~>*/@Component("lazyWorker")
              @Lazy
              class LazyWorker {
              }

              /*~~(Manual migration: unsupported @Bean methods require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              class ResourceConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean(name = "resource")
                  AutoCloseable resource() {
                      return () -> { };
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesSealedNamedSpringStereotypesAtomically() {
        rewriteRun(
          java(
            """
              package com.example.proxyability;

              import org.springframework.stereotype.Component;

              @Component("auditService")
              sealed class AuditService permits SpecializedAuditService {
              }

              final class SpecializedAuditService extends AuditService {
              }
              """,
            """
              package com.example.proxyability;

              import org.springframework.stereotype.Component;

              /*~~(Manual migration: this named Spring stereotype cannot become a CDI bean with safe constructor injection)~~>*/@Component("auditService")
              sealed class AuditService permits SpecializedAuditService {
              }

              final class SpecializedAuditService extends AuditService {
              }
              """
          )
        );
    }

    @Test
    void leavesQualifierOutsideAnExactlyEligibleBeanClassUntouched() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(0),
          java(
            """
              package com.example.boundaries;

              import org.springframework.beans.factory.annotation.Qualifier;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              class ApiController {
                  @Qualifier("service")
                  Object service;
              }

              class OrdinaryHelper {
                  @Qualifier("dependency")
                  Object dependency;
              }
              """
          )
        );
    }

    @Test
    void refusesNamedBeanParametersAndUnqualifiedInjectionAtomically() {
        rewriteRun(
          java(
            """
              package com.example.boundaries;

              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.stereotype.Component;

              @Configuration(proxyBeanMethods = false)
              class ClientConfiguration {
                  @Bean(name = "primaryClient")
                  Object client(Object dependency) {
                      return dependency;
                  }
              }

              @Component("consumer")
              class Consumer {
                  @Autowired
                  Object client;
              }
              """,
            """
              package com.example.boundaries;

              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.stereotype.Component;

              /*~~(Manual migration: unsupported @Bean methods require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              class ClientConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean(name = "primaryClient")
                  Object client(Object dependency) {
                      return dependency;
                  }
              }

              /*~~(Manual migration: unqualified @Autowired may rely on Spring name fallback)~~>*/@Component("consumer")
              class Consumer {
                  @Autowired
                  Object client;
              }
              """
          )
        );
    }

    @Test
    void refusesSpringWebAndRepositorySemanticsOnNamedStereotypes() {
        rewriteRun(
          java(
            """
              package com.example.boundaries;

              import org.springframework.stereotype.Component;
              import org.springframework.stereotype.Repository;
              import org.springframework.web.bind.annotation.GetMapping;

              @Component("endpoint")
              class Endpoint {
                  @GetMapping("/status")
                  String status() {
                      return "ok";
                  }
              }

              @Repository("orders")
              class OrderRepository {
              }
              """,
            """
              package com.example.boundaries;

              import org.springframework.stereotype.Component;
              import org.springframework.stereotype.Repository;
              import org.springframework.web.bind.annotation.GetMapping;

              /*~~(Manual migration: Spring scope, lifecycle, or conditional annotations require atomic CDI bean redesign)~~>*/@Component("endpoint")
              class Endpoint {
                  @GetMapping("/status")
                  String status() {
                      return "ok";
                  }
              }

              /*~~(Manual migration: Spring @Repository exception translation requires manual CDI persistence review)~~>*/@Repository("orders")
              class OrderRepository {
              }
              """
          )
        );
    }
}
