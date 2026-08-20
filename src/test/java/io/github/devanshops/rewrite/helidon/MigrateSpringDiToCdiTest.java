package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateSpringDiToCdiTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSpringDiToCdi())
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-beans", "spring-context", "spring-tx", "spring-web",
                        "jakarta.enterprise.cdi-api", "jakarta.inject-api"))
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void migratesProxyableBeansAndUsesSingletonProducerScope() {
        rewriteRun(
          java(
            """
              package com.example.orders;

              import jakarta.inject.Named;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.stereotype.Component;
              import org.springframework.stereotype.Service;

              @Configuration(proxyBeanMethods = false)
              class OrderConfiguration {
                  @Bean(destroyMethod = "")
                  Object orderClock() {
                      return new Object();
                  }

                  @Bean(destroyMethod = "")
                  String region() {
                      return "example";
                  }
              }

              @Service
              class OrderService {
                  OrderService() {
                  }

                  @Autowired
                  OrderService(@Named("orderDependency") OrderDependency dependency) {
                  }
              }

              @Component
              class OrderDependency {
              }

              @Component
              class FieldInjectedClock {
                  @Autowired(required = true)
                  @Named("orderClock")
                  Object clock;
              }
              """,
            """
              package com.example.orders;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.enterprise.inject.Produces;
              import jakarta.inject.Inject;
              import jakarta.inject.Named;
              import jakarta.inject.Singleton;

              @ApplicationScoped
              @Named("orderConfiguration")
              class OrderConfiguration {
                  @Named("orderClock")
                  @Produces
                  @Singleton
                  Object orderClock() {
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
              @Named("orderService")
              class OrderService {
                  OrderService() {
                  }

                  @Inject
                  OrderService(@Named("orderDependency") OrderDependency dependency) {
                  }
              }

              @ApplicationScoped
              @Named("orderDependency")
              class OrderDependency {
              }

              @ApplicationScoped
              @Named("fieldInjectedClock")
              class FieldInjectedClock {
                  @Inject
                  @Named("orderClock")
                  Object clock;
              }
              """
          )
        );
    }

    @Test
    void requiresLiteralDestroyInferenceOptOutForUnnamedProducers() {
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
                  @Bean
                  Object client() {
                      return new Object();
                  }
              }

              @Configuration(proxyBeanMethods = false)
              class ConstantOptOutConfiguration {
                  @Bean(destroyMethod = LifecycleNames.NONE)
                  Object client() {
                      return new Object();
                  }
              }

              @Configuration(proxyBeanMethods = false)
              class ExplicitOptOutConfiguration {
                  @Bean(destroyMethod = "")
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
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean
                  Object client() {
                      return new Object();
                  }
              }

              /*~~(Manual migration: unsupported @Bean methods require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              class ConstantOptOutConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean(destroyMethod = LifecycleNames.NONE)
                  Object client() {
                      return new Object();
                  }
              }

              @ApplicationScoped
              @Named("explicitOptOutConfiguration")
              class ExplicitOptOutConfiguration {
                  @Named("client")
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
    void preservesDefaultAndExplicitlyProxiedConfigurationsAtomically() {
        rewriteRun(
          java(
            """
              package com.example.config;

              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              @Configuration
              class DefaultProxyConfiguration {
                  @Bean
                  Object client() {
                      return new Object();
                  }
              }

              @Configuration(proxyBeanMethods = true)
              class ExplicitProxyConfiguration {
                  @Bean(name = "auditedClient")
                  Object auditedClient() {
                      return new Object();
                  }
              }
              """,
            """
              package com.example.config;

              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              /*~~(Manual migration: proxied @Configuration semantics require CDI redesign)~~>*/@Configuration
              class DefaultProxyConfiguration {
                  /*~~(Manual migration: @Bean belongs to a proxied @Configuration and must migrate atomically)~~>*/@Bean
                  Object client() {
                      return new Object();
                  }
              }

              /*~~(Manual migration: proxied @Configuration semantics require CDI redesign)~~>*/@Configuration(proxyBeanMethods = true)
              class ExplicitProxyConfiguration {
                  /*~~(Manual migration: @Bean belongs to a proxied @Configuration and must migrate atomically)~~>*/@Bean(name = "auditedClient")
                  Object auditedClient() {
                      return new Object();
                  }
              }
              """
          )
        );
    }

    @Test
    void preservesWholeBeanForUnsupportedInjectionOrUnproxyableConstructors() {
        rewriteRun(
          java(
            """
              package com.example.safety;

              import java.util.List;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.context.ApplicationContext;
              import org.springframework.stereotype.Component;
              import org.springframework.stereotype.Repository;
              import org.springframework.stereotype.Service;

              @Service
              final class FinalService {
              }

              @Repository
              class SoleConstructorRepository {
                  SoleConstructorRepository(Object connection) {
                  }
              }

              @Component
              class Consumer {
                  @Autowired
                  final Object finalHandler = null;

                  @Autowired
                  List<Object> handlers;

                  @Autowired(required = false)
                  Object optionalHandler;

                  @Autowired
                  static Object globalHandler;
              }

              @Service
              class ContextConsumer {
                  @Autowired
                  ApplicationContext context;
              }
              """,
            """
              package com.example.safety;

              import java.util.List;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.context.ApplicationContext;
              import org.springframework.stereotype.Component;
              import org.springframework.stereotype.Repository;
              import org.springframework.stereotype.Service;

              /*~~(Manual migration: this Spring stereotype cannot become a CDI bean with safe constructor injection)~~>*/@Service
              final class FinalService {
              }

              /*~~(Manual migration: Spring @Repository exception translation requires manual CDI persistence review)~~>*/@Repository
              class SoleConstructorRepository {
                  SoleConstructorRepository(Object connection) {
                  }
              }

              /*~~(Manual migration: unsupported @Autowired members require atomic CDI bean redesign)~~>*/@Component
              class Consumer {
                  @Autowired
                  final Object finalHandler = null;

                  @Autowired
                  List<Object> handlers;

                  @Autowired(required = false)
                  Object optionalHandler;

                  @Autowired
                  static Object globalHandler;
              }

              /*~~(Manual migration: unsupported @Autowired members require atomic CDI bean redesign)~~>*/@Service
              class ContextConsumer {
                  @Autowired
                  ApplicationContext context;
              }
              """
          )
        );
    }

    @Test
    void refusesSealedSpringStereotypesAtomically() {
        rewriteRun(
          java(
            """
              package com.example.proxyability;

              import org.springframework.stereotype.Service;

              @Service
              sealed class AuditService permits SpecializedAuditService {
              }

              final class SpecializedAuditService extends AuditService {
              }
              """,
            """
              package com.example.proxyability;

              import org.springframework.stereotype.Service;

              /*~~(Manual migration: this Spring stereotype cannot become a CDI bean with safe constructor injection)~~>*/@Service
              sealed class AuditService permits SpecializedAuditService {
              }

              final class SpecializedAuditService extends AuditService {
              }
              """
          )
        );
    }

    @Test
    void refusesAdjacentSpringSemanticsInheritanceAndUnsupportedTransactions() {
        rewriteRun(
          java(
            """
              package com.example.boundaries;

              import java.io.IOException;
              import org.springframework.beans.factory.InitializingBean;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.context.annotation.Import;
              import org.springframework.context.annotation.Profile;
              import org.springframework.context.annotation.Scope;
              import org.springframework.stereotype.Component;
              import org.springframework.stereotype.Service;
              import org.springframework.transaction.annotation.Transactional;

              @Service
              @Scope("prototype")
              class ScopedService {
              }

              @Configuration(proxyBeanMethods = false)
              @Import(Imported.class)
              class ImportedConfiguration {
                  @Bean
                  Object client() {
                      return new Object();
                  }
              }

              @Configuration(proxyBeanMethods = false)
              class ProfiledConfiguration {
                  @Bean
                  @Profile("production")
                  Object client() {
                      return new Object();
                  }
              }

              @Service
              class SupportedTransactionService {
                  @Transactional(rollbackFor = IOException.class)
                  void save() {
                  }
              }

              @Service
              class UnsupportedTransactionService {
                  @Transactional(readOnly = true)
                  void read() {
                  }
              }

              @Component
              class DerivedComponent extends BaseComponent {
              }

              @Component
              class SpringLifecycleComponent implements InitializingBean {
                  @Override
                  public void afterPropertiesSet() {
                  }
              }

              class Imported {
              }

              class BaseComponent {
              }
              """,
            """
              package com.example.boundaries;

              import java.io.IOException;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Named;
              import org.springframework.beans.factory.InitializingBean;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;
              import org.springframework.context.annotation.Import;
              import org.springframework.context.annotation.Profile;
              import org.springframework.context.annotation.Scope;
              import org.springframework.stereotype.Component;
              import org.springframework.stereotype.Service;
              import org.springframework.transaction.annotation.Transactional;

              /*~~(Manual migration: Spring scope, lifecycle, or conditional annotations require atomic CDI bean redesign)~~>*/@Service
              @Scope("prototype")
              class ScopedService {
              }

              /*~~(Manual migration: Spring scope, lifecycle, or conditional annotations require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              @Import(Imported.class)
              class ImportedConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean
                  Object client() {
                      return new Object();
                  }
              }

              /*~~(Manual migration: Spring scope, lifecycle, or conditional annotations require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              class ProfiledConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean
                  @Profile("production")
                  Object client() {
                      return new Object();
                  }
              }

              @ApplicationScoped
              @Named("supportedTransactionService")
              class SupportedTransactionService {
                  @Transactional(rollbackFor = IOException.class)
                  void save() {
                  }
              }

              /*~~(Manual migration: Spring scope, lifecycle, or conditional annotations require atomic CDI bean redesign)~~>*/@Service
              class UnsupportedTransactionService {
                  @Transactional(readOnly = true)
                  void read() {
                  }
              }

              /*~~(Manual migration: inherited constructors or final methods require CDI proxyability review)~~>*/@Component
              class DerivedComponent extends BaseComponent {
              }

              /*~~(Manual migration: inherited constructors or final methods require CDI proxyability review)~~>*/@Component
              class SpringLifecycleComponent implements InitializingBean {
                  @Override
                  public void afterPropertiesSet() {
                  }
              }

              class Imported {
              }

              class BaseComponent {
              }
              """
          )
        );
    }

    @Test
    void allowsOnlyValueMappingsTheDownstreamRecipeCanComplete() {
        rewriteRun(
          java(
            """
              package com.example.configvalues;

              import java.time.Duration;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.stereotype.Component;

              @Component
              class SupportedValue {
                  @Value("${attempts:3}")
                  int attempts;
              }

              @Component
              class DurationValue {
                  @Value("${timeout}")
                  Duration timeout;
              }

              @Component
              class ExpressionValue {
                  @Value("#{systemProperties['user.name']}")
                  String user;
              }

              @Component
              class StaticValue {
                  @Value("${region}")
                  static String region;
              }
              """,
            """
              package com.example.configvalues;

              import java.time.Duration;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Named;
              import org.springframework.beans.factory.annotation.Value;
              import org.springframework.stereotype.Component;

              @ApplicationScoped
              @Named("supportedValue")
              class SupportedValue {
                  @Value("${attempts:3}")
                  int attempts;
              }

              /*~~(Manual migration: unsupported @Value members require atomic CDI bean redesign)~~>*/@Component
              class DurationValue {
                  @Value("${timeout}")
                  Duration timeout;
              }

              /*~~(Manual migration: unsupported @Value members require atomic CDI bean redesign)~~>*/@Component
              class ExpressionValue {
                  @Value("#{systemProperties['user.name']}")
                  String user;
              }

              /*~~(Manual migration: unsupported @Value members require atomic CDI bean redesign)~~>*/@Component
              class StaticValue {
                  @Value("${region}")
                  static String region;
              }
              """
          )
        );
    }

    @Test
    void refusesResourceAndFactoryBeanProductsWithTheirConfiguration() {
        rewriteRun(
          java(
            """
              package com.example.resources;

              import org.springframework.beans.factory.FactoryBean;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              @Configuration(proxyBeanMethods = false)
              class ResourceConfiguration {
                  @Bean
                  AutoCloseable resource() {
                      return () -> { };
                  }

                  @Bean
                  FactoryBean<Object> factory() {
                      return null;
                  }
              }
              """,
            """
              package com.example.resources;

              import org.springframework.beans.factory.FactoryBean;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              /*~~(Manual migration: unsupported @Bean methods require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              class ResourceConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean
                  AutoCloseable resource() {
                      return () -> { };
                  }

                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean
                  FactoryBean<Object> factory() {
                      return null;
                  }
              }
              """
          )
        );
    }

    @Test
    void leavesAutowiredOutsideAnExactlyEligibleBeanClassUntouched() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(0),
          java(
            """
              package com.example.boundaries;

              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.web.bind.annotation.RestController;

              @RestController
              class ApiController {
                  @Autowired
                  Object service;
              }

              class OrdinaryHelper {
                  @Autowired
                  Object dependency;
              }
              """
          )
        );
    }

    @Test
    void refusesResidualSpringWebBehaviorOnDirectComponents() {
        rewriteRun(
          java(
            """
              package com.example.boundaries;

              import org.springframework.stereotype.Service;
              import org.springframework.web.bind.annotation.GetMapping;

              @Service
              class EndpointService {
                  @GetMapping("/status")
                  String status() {
                      return "ok";
                  }
              }
              """,
            """
              package com.example.boundaries;

              import org.springframework.stereotype.Service;
              import org.springframework.web.bind.annotation.GetMapping;

              /*~~(Manual migration: Spring scope, lifecycle, or conditional annotations require atomic CDI bean redesign)~~>*/@Service
              class EndpointService {
                  @GetMapping("/status")
                  String status() {
                      return "ok";
                  }
              }
              """
          )
        );
    }

    @Test
    void refusesAllBeanProducerParametersAtomically() {
        rewriteRun(
          java(
            """
              package com.example.producers;

              import java.util.Optional;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              @Configuration(proxyBeanMethods = false)
              class ClientConfiguration {
                  @Bean
                  Object client(Object dependency) {
                      return dependency;
                  }

                  @Bean
                  Object optionalClient(Optional<Object> dependency) {
                      return dependency.orElse(null);
                  }
              }
              """,
            """
              package com.example.producers;

              import java.util.Optional;
              import org.springframework.context.annotation.Bean;
              import org.springframework.context.annotation.Configuration;

              /*~~(Manual migration: unsupported @Bean methods require atomic CDI bean redesign)~~>*/@Configuration(proxyBeanMethods = false)
              class ClientConfiguration {
                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean
                  Object client(Object dependency) {
                      return dependency;
                  }

                  /*~~(Manual migration: @Bean must remain with its Spring bean class for atomic migration)~~>*/@Bean
                  Object optionalClient(Optional<Object> dependency) {
                      return dependency.orElse(null);
                  }
              }
              """
          )
        );
    }

    @Test
    void requiresExplicitCdiNameProofBeforeConvertingAutowired() {
        rewriteRun(
          java(
            """
              package com.example.names;

              import jakarta.inject.Named;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Component;

              @Component
              class AmbiguousConsumer {
                  @Autowired
                  Object client;
              }

              @Component
              class ExplicitConsumer {
                  @Autowired
                  @Named("primaryClient")
                  Object client;
              }
              """,
            """
              package com.example.names;

              import jakarta.enterprise.context.ApplicationScoped;
              import jakarta.inject.Inject;
              import jakarta.inject.Named;
              import org.springframework.beans.factory.annotation.Autowired;
              import org.springframework.stereotype.Component;

              /*~~(Manual migration: unqualified @Autowired may rely on Spring name fallback)~~>*/@Component
              class AmbiguousConsumer {
                  @Autowired
                  Object client;
              }

              @ApplicationScoped
              @Named("explicitConsumer")
              class ExplicitConsumer {
                  @Inject
                  @Named("primaryClient")
                  Object client;
              }
              """
          )
        );
    }

    @Test
    void refusesRepositoryExceptionTranslationSemantics() {
        rewriteRun(
          java(
            """
              package com.example.persistence;

              import org.springframework.stereotype.Repository;

              @Repository
              class OrderRepository {
              }
              """,
            """
              package com.example.persistence;

              import org.springframework.stereotype.Repository;

              /*~~(Manual migration: Spring @Repository exception translation requires manual CDI persistence review)~~>*/@Repository
              class OrderRepository {
              }
              """
          )
        );
    }
}
