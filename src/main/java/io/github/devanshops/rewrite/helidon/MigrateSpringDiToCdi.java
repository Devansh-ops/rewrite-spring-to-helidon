package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MigrateSpringDiToCdi extends Recipe {
    private static final AnnotationMatcher CONFIGURATION =
            new AnnotationMatcher("@org.springframework.context.annotation.Configuration");
    private static final AnnotationMatcher SERVICE =
            new AnnotationMatcher("@org.springframework.stereotype.Service");
    private static final AnnotationMatcher COMPONENT =
            new AnnotationMatcher("@org.springframework.stereotype.Component");
    private static final AnnotationMatcher REPOSITORY =
            new AnnotationMatcher("@org.springframework.stereotype.Repository");
    private static final AnnotationMatcher AUTOWIRED =
            new AnnotationMatcher("@org.springframework.beans.factory.annotation.Autowired");
    private static final AnnotationMatcher QUALIFIER =
            new AnnotationMatcher("@org.springframework.beans.factory.annotation.Qualifier");
    private static final AnnotationMatcher VALUE =
            new AnnotationMatcher("@org.springframework.beans.factory.annotation.Value");
    private static final AnnotationMatcher BEAN =
            new AnnotationMatcher("@org.springframework.context.annotation.Bean");
    private static final AnnotationMatcher PRODUCES =
            new AnnotationMatcher("@jakarta.enterprise.inject.Produces");
    private static final AnnotationMatcher TRANSACTIONAL =
            new AnnotationMatcher("@org.springframework.transaction.annotation.Transactional");
    private static final AnnotationMatcher INJECT =
            new AnnotationMatcher("@jakarta.inject.Inject");
    private static final AnnotationMatcher NAMED =
            new AnnotationMatcher("@jakarta.inject.Named");
    private static final AnnotationMatcher APPLICATION_SCOPED =
            new AnnotationMatcher("@jakarta.enterprise.context.ApplicationScoped");

    private static final Comparator<J.Annotation> ANNOTATION_COMPARATOR =
            Comparator.comparing(annotation -> annotation.getSimpleName().toLowerCase());

    private static final String PROXIED_CONFIGURATION_REVIEW =
            "Manual migration: proxied @Configuration semantics require CDI redesign";
    private static final String PROXIED_BEAN_REVIEW =
            "Manual migration: @Bean belongs to a proxied @Configuration and must migrate atomically";
    private static final String CONSTRUCTOR_REVIEW =
            "Manual migration: this Spring stereotype cannot become a CDI bean with safe constructor injection";
    private static final String INJECTION_CLASS_REVIEW =
            "Manual migration: unsupported @Autowired members require atomic CDI bean redesign";
    private static final String ADJACENT_SEMANTICS_REVIEW =
            "Manual migration: Spring scope, lifecycle, or conditional annotations require atomic CDI bean redesign";
    private static final String PRODUCER_CLASS_REVIEW =
            "Manual migration: unsupported @Bean methods require atomic CDI bean redesign";
    private static final String INHERITANCE_REVIEW =
            "Manual migration: inherited constructors or final methods require CDI proxyability review";
    private static final String ATOMIC_BEAN_REVIEW =
            "Manual migration: @Bean must remain with its Spring bean class for atomic migration";
    private static final String VALUE_CLASS_REVIEW =
            "Manual migration: unsupported @Value members require atomic CDI bean redesign";
    private static final String NAME_FALLBACK_REVIEW =
            "Manual migration: unqualified @Autowired may rely on Spring name fallback";
    private static final String REPOSITORY_REVIEW =
            "Manual migration: Spring @Repository exception translation requires manual CDI persistence review";

    @Override
    public String getDisplayName() {
        return "Migrate Spring dependency injection to CDI";
    }

    @Override
    public String getDescription() {
        return "Converts supported Spring stereotypes, injection points, and bean methods to CDI equivalents.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(
                        new UsesType<>("org.springframework.context.annotation.Configuration", false),
                        new UsesType<>("org.springframework.context.annotation.Bean", false),
                        new UsesType<>("org.springframework.stereotype.Service", false),
                        new UsesType<>("org.springframework.stereotype.Component", false),
                        new UsesType<>("org.springframework.stereotype.Repository", false),
                        new UsesType<>("org.springframework.beans.factory.annotation.Autowired", false)),
                new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.Annotation configuration = findAnnotation(classDecl.getLeadingAnnotations(), CONFIGURATION);
                if (configuration != null && !hasProxyBeanMethodsFalse(configuration)) {
                    return markProxiedConfiguration(classDecl);
                }

                J.Annotation stereotype = findStereotype(classDecl.getLeadingAnnotations());
                J.Annotation repository = findAnnotation(classDecl.getLeadingAnnotations(), REPOSITORY);
                if (repository != null) {
                    return markSpringBeanClass(classDecl, repository, REPOSITORY_REVIEW, ATOMIC_BEAN_REVIEW);
                }
                if (stereotype != null && hasArguments(stereotype)) {
                    return markSpringBeanClass(classDecl, stereotype,
                            "Manual migration: named stereotypes need CDI review", ATOMIC_BEAN_REVIEW);
                }

                J.Annotation springBeanAnnotation = configuration != null ? configuration : stereotype;
                if (springBeanAnnotation != null && hasUnsupportedAdjacentSpringSemantics(classDecl)) {
                    return markSpringBeanClass(classDecl, springBeanAnnotation,
                            ADJACENT_SEMANTICS_REVIEW, ATOMIC_BEAN_REVIEW);
                }
                if (springBeanAnnotation != null && hasUnsupportedQualifier(classDecl)) {
                    return markSpringBeanClass(classDecl, springBeanAnnotation,
                            "Manual migration: unresolved Spring @Qualifier requires atomic CDI bean redesign",
                            ATOMIC_BEAN_REVIEW);
                }
                if (springBeanAnnotation != null && hasUnsupportedValueMembers(classDecl)) {
                    return markSpringBeanClass(classDecl, springBeanAnnotation,
                            VALUE_CLASS_REVIEW, ATOMIC_BEAN_REVIEW);
                }
                if (springBeanAnnotation != null && hasUnsupportedAutowiredMembers(classDecl)) {
                    return markSpringBeanClass(classDecl, springBeanAnnotation,
                            INJECTION_CLASS_REVIEW, ATOMIC_BEAN_REVIEW);
                }
                if (springBeanAnnotation != null && hasAmbiguousAutowiredDependencies(classDecl)) {
                    return markSpringBeanClass(classDecl, springBeanAnnotation,
                            NAME_FALLBACK_REVIEW, ATOMIC_BEAN_REVIEW);
                }
                if (springBeanAnnotation != null && hasUnsupportedBeanMethods(classDecl)) {
                    return markSpringBeanClass(classDecl, springBeanAnnotation,
                            PRODUCER_CLASS_REVIEW, ATOMIC_BEAN_REVIEW);
                }
                if (springBeanAnnotation != null && hasUnreviewedInheritance(classDecl)) {
                    return markSpringBeanClass(classDecl, springBeanAnnotation,
                            INHERITANCE_REVIEW, ATOMIC_BEAN_REVIEW);
                }
                if (configuration != null && !hasSafeCdiBeanShape(classDecl)) {
                    return markConfigurationForConstructorReview(classDecl);
                }
                if (stereotype != null && !hasArguments(stereotype) && !hasSafeCdiBeanShape(classDecl)) {
                    return classDecl.withLeadingAnnotations(ListUtils.map(
                            classDecl.getLeadingAnnotations(),
                            annotation -> isStereotype(annotation) ? SearchResult.found(annotation, CONSTRUCTOR_REVIEW) : annotation
                    ));
                }

                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                if (configuration != null) {
                    cd = replaceClassAnnotation(cd, CONFIGURATION);
                    maybeRemoveImport("org.springframework.context.annotation.Configuration");
                    maybeAddImport("jakarta.enterprise.context.ApplicationScoped", false);
                    return cd;
                }

                if (stereotype == null) {
                    return cd;
                }

                cd = replaceClassAnnotation(cd, stereotypeMatcher(stereotype));
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(stereotype.getType());
                if (type != null) {
                    maybeRemoveImport(type.getFullyQualifiedName());
                }
                maybeAddImport("jakarta.enterprise.context.ApplicationScoped", false);
                return cd;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

                if (shouldAddImplicitConstructorInjection(method)) {
                    m = addAnnotation(m, "@Inject", "jakarta.inject.Inject");
                }

                J.Annotation bean = findAnnotation(m.getLeadingAnnotations(), BEAN);
                if (bean == null) {
                    return m;
                }
                if (m.hasModifier(J.Modifier.Type.Static)) {
                    return markMethodAnnotation(m, BEAN,
                            "Manual migration: static @Bean methods need CDI producer lifecycle review");
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !isSupportedProducerDeclaringBean(enclosing)) {
                    return markMethodAnnotation(m, BEAN,
                            "Manual migration: @Bean declaring class is not a supported CDI bean");
                }
                if (!hasLiteralDestroyInferenceOptOut(bean)) {
                    return markMethodAnnotation(m, BEAN,
                            "Manual migration: @Bean requires a literal destroyMethod = \"\" before CDI producer conversion");
                }
                if (hasUnsupportedAdjacentAnnotations(m.getLeadingAnnotations()) ||
                        hasUnsupportedProducerSignature(m)) {
                    return markMethodAnnotation(m, BEAN,
                            "Manual migration: @Bean lifecycle annotations or signature need CDI producer review");
                }

                m = m.withLeadingAnnotations(ListUtils.map(m.getLeadingAnnotations(),
                        annotation -> BEAN.matches(annotation) ? null : annotation));
                maybeRemoveImport("org.springframework.context.annotation.Bean");
                m = addAnnotation(m, "@Named(\"" + m.getSimpleName() + "\")", "jakarta.inject.Named");
                m = addAnnotation(m, "@Produces", "jakarta.enterprise.inject.Produces");
                m = addAnnotation(m, "@Singleton", "jakarta.inject.Singleton");
                return m;
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation a = super.visitAnnotation(annotation, ctx);
                if (!AUTOWIRED.matches(a)) {
                    return a;
                }
                J.ClassDeclaration declaringClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (declaringClass == null || !isDirectlyConvertibleSpringBean(declaringClass)) {
                    return a;
                }

                Object parent = getCursor().getParentOrThrow().getValue();
                if ((parent instanceof J.VariableDeclarations && ((J.VariableDeclarations) parent)
                        .hasModifier(J.Modifier.Type.Static)) ||
                        (parent instanceof J.MethodDeclaration && ((J.MethodDeclaration) parent)
                                .hasModifier(J.Modifier.Type.Static))) {
                    return SearchResult.found(a,
                            "Manual migration: CDI does not inject static fields or methods");
                }
                if (hasAggregateInjectionTarget(parent)) {
                    return SearchResult.found(a,
                            "Manual migration: Spring aggregate or optional injection has no direct CDI @Inject equivalent");
                }
                if (!isDefaultAutowired(a)) {
                    return SearchResult.found(a,
                            "Manual migration: optional @Autowired injection has no direct @Inject equivalent");
                }

                maybeRemoveImport("org.springframework.beans.factory.annotation.Autowired");
                maybeAddImport("jakarta.inject.Inject", false);
                return HelidonJavaTemplate.builder("@Inject")
                        .imports("jakarta.inject.Inject")
                        .build()
                        .apply(getCursor(), a.getCoordinates().replace());
            }

            private J.ClassDeclaration markProxiedConfiguration(J.ClassDeclaration classDecl) {
                return markConfiguration(classDecl, PROXIED_CONFIGURATION_REVIEW, PROXIED_BEAN_REVIEW);
            }

            private J.ClassDeclaration markConfigurationForConstructorReview(J.ClassDeclaration classDecl) {
                return markConfiguration(classDecl, CONSTRUCTOR_REVIEW, ATOMIC_BEAN_REVIEW);
            }

            private J.ClassDeclaration markSpringBeanClass(J.ClassDeclaration classDecl,
                                                           J.Annotation springBeanAnnotation,
                                                           String classMessage,
                                                           String beanMessage) {
                if (CONFIGURATION.matches(springBeanAnnotation)) {
                    return markConfiguration(classDecl, classMessage, beanMessage);
                }
                J.ClassDeclaration marked = classDecl.withLeadingAnnotations(ListUtils.map(
                        classDecl.getLeadingAnnotations(), annotation ->
                                annotation == springBeanAnnotation ?
                                        SearchResult.found(annotation, classMessage) : annotation));
                return markBeanMethods(marked, beanMessage);
            }

            private J.ClassDeclaration markConfiguration(J.ClassDeclaration classDecl,
                                                         String configurationMessage,
                                                         String beanMessage) {
                J.ClassDeclaration marked = classDecl.withLeadingAnnotations(ListUtils.map(
                        classDecl.getLeadingAnnotations(), annotation ->
                                CONFIGURATION.matches(annotation) ?
                                        SearchResult.found(annotation, configurationMessage) : annotation));
                return markBeanMethods(marked, beanMessage);
            }

            private J.ClassDeclaration markBeanMethods(J.ClassDeclaration classDecl, String beanMessage) {
                J.ClassDeclaration marked = classDecl;
                return marked.withBody(marked.getBody().withStatements(ListUtils.map(
                        marked.getBody().getStatements(), statement -> {
                            if (!(statement instanceof J.MethodDeclaration)) {
                                return statement;
                            }
                            return markMethodAnnotation((J.MethodDeclaration) statement, BEAN,
                                    beanMessage);
                        })));
            }

            private J.ClassDeclaration replaceClassAnnotation(J.ClassDeclaration cd, AnnotationMatcher matcher) {
                cd = cd.withLeadingAnnotations(ListUtils.map(cd.getLeadingAnnotations(),
                        annotation -> matcher.matches(annotation) ? null : annotation));
                if (findAnnotation(cd.getLeadingAnnotations(), APPLICATION_SCOPED) == null) {
                    cd = HelidonJavaTemplate.builder("@ApplicationScoped")
                            .imports("jakarta.enterprise.context.ApplicationScoped")
                            .build()
                            .apply(updateCursor(cd), cd.getCoordinates().addAnnotation(ANNOTATION_COMPARATOR));
                }
                if (findAnnotation(cd.getLeadingAnnotations(), NAMED) == null) {
                    String beanName = decapitalize(cd.getSimpleName());
                    maybeAddImport("jakarta.inject.Named", false);
                    cd = HelidonJavaTemplate.builder("@Named(\"" + beanName + "\")")
                            .imports("jakarta.inject.Named")
                            .build()
                            .apply(updateCursor(cd), cd.getCoordinates().addAnnotation(ANNOTATION_COMPARATOR));
                }
                return cd;
            }

            private J.MethodDeclaration addAnnotation(J.MethodDeclaration method,
                                                      String template,
                                                      String type) {
                AnnotationMatcher matcher = new AnnotationMatcher("@" + type);
                if (findAnnotation(method.getLeadingAnnotations(), matcher) != null) {
                    return method;
                }
                maybeAddImport(type, false);
                return HelidonJavaTemplate.builder(template)
                        .imports(type)
                        .build()
                        .apply(updateCursor(method), method.getCoordinates().addAnnotation(ANNOTATION_COMPARATOR));
            }

            private boolean shouldAddImplicitConstructorInjection(J.MethodDeclaration method) {
                if (!method.isConstructor() || !hasParameters(method) ||
                        findAnnotation(method.getLeadingAnnotations(), AUTOWIRED) != null ||
                        findAnnotation(method.getLeadingAnnotations(), INJECT) != null) {
                    return false;
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !hasConvertibleStereotype(enclosing)) {
                    return false;
                }
                int constructorCount = 0;
                for (org.openrewrite.java.tree.Statement statement : enclosing.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration &&
                            ((J.MethodDeclaration) statement).isConstructor()) {
                        constructorCount++;
                    }
                }
                return constructorCount == 1;
            }

            private boolean hasConvertibleStereotype(J.ClassDeclaration classDecl) {
                J.Annotation configuration = findAnnotation(classDecl.getLeadingAnnotations(), CONFIGURATION);
                if (configuration != null) {
                    return hasProxyBeanMethodsFalse(configuration);
                }
                J.Annotation stereotype = findStereotype(classDecl.getLeadingAnnotations());
                return stereotype != null && !hasArguments(stereotype);
            }

            private boolean isDirectlyConvertibleSpringBean(J.ClassDeclaration classDecl) {
                J.Annotation configuration = findAnnotation(classDecl.getLeadingAnnotations(), CONFIGURATION);
                if (configuration != null) {
                    return hasProxyBeanMethodsFalse(configuration);
                }
                J.Annotation stereotype = findStereotype(classDecl.getLeadingAnnotations());
                return stereotype != null && !REPOSITORY.matches(stereotype) && !hasArguments(stereotype);
            }

            private boolean isSupportedProducerDeclaringBean(J.ClassDeclaration classDecl) {
                if (findAnnotation(classDecl.getLeadingAnnotations(), APPLICATION_SCOPED) != null) {
                    return true;
                }
                J.Annotation configuration = findAnnotation(classDecl.getLeadingAnnotations(), CONFIGURATION);
                if (configuration != null) {
                    return hasProxyBeanMethodsFalse(configuration);
                }
                J.Annotation stereotype = findStereotype(classDecl.getLeadingAnnotations());
                return stereotype != null && !hasArguments(stereotype);
            }

            private boolean hasUnreviewedInheritance(J.ClassDeclaration classDecl) {
                if (classDecl.getExtends() != null &&
                        !TypeUtils.isOfClassType(classDecl.getExtends().getType(), "java.lang.Object")) {
                    return true;
                }
                if (classDecl.getImplements() != null) {
                    for (org.openrewrite.java.tree.TypeTree implemented : classDecl.getImplements()) {
                        JavaType.FullyQualified type = TypeUtils.asFullyQualified(implemented.getType());
                        if (type != null && type.getFullyQualifiedName().startsWith("org.springframework.")) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasSafeCdiBeanShape(J.ClassDeclaration classDecl) {
                if (classDecl.getKind() != J.ClassDeclaration.Kind.Type.Class ||
                        classDecl.hasModifier(J.Modifier.Type.Abstract) ||
                        classDecl.hasModifier(J.Modifier.Type.Final) ||
                        classDecl.hasModifier(J.Modifier.Type.Sealed)) {
                    return false;
                }
                J.ClassDeclaration enclosing = getCursor().getParentTreeCursor()
                        .firstEnclosing(J.ClassDeclaration.class);
                if (enclosing != null && !classDecl.hasModifier(J.Modifier.Type.Static)) {
                    return false;
                }

                int constructorCount = 0;
                int autowiredConstructorCount = 0;
                boolean hasNonPrivateNoArgConstructor = false;
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration &&
                            ((J.MethodDeclaration) statement).isConstructor()) {
                        J.MethodDeclaration constructor = (J.MethodDeclaration) statement;
                        constructorCount++;
                        if (!hasParameters(constructor) &&
                                !constructor.hasModifier(J.Modifier.Type.Private)) {
                            hasNonPrivateNoArgConstructor = true;
                        }
                        J.Annotation autowired = findAnnotation(constructor.getLeadingAnnotations(), AUTOWIRED);
                        if (autowired != null) {
                            if (!isDefaultAutowired(autowired) ||
                                    constructor.hasModifier(J.Modifier.Type.Private) ||
                                    hasAggregateParameters(constructor)) {
                                return false;
                            }
                            autowiredConstructorCount++;
                        }
                    } else if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        if (method.hasModifier(J.Modifier.Type.Final) &&
                                !method.hasModifier(J.Modifier.Type.Private) &&
                                !method.hasModifier(J.Modifier.Type.Static)) {
                            return false;
                        }
                    }
                }
                if (constructorCount == 0) {
                    return true;
                }
                if (constructorCount == 1) {
                    for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                        if (statement instanceof J.MethodDeclaration &&
                                ((J.MethodDeclaration) statement).isConstructor()) {
                            J.MethodDeclaration constructor = (J.MethodDeclaration) statement;
                            return !constructor.hasModifier(J.Modifier.Type.Private) &&
                                    !hasParameters(constructor);
                        }
                    }
                }
                if (autowiredConstructorCount > 1) {
                    return false;
                }
                return hasNonPrivateNoArgConstructor && autowiredConstructorCount <= 1;
            }

            private boolean hasUnsupportedAutowiredMembers(J.ClassDeclaration classDecl) {
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.VariableDeclarations) {
                        J.VariableDeclarations field = (J.VariableDeclarations) statement;
                        J.Annotation autowired = findAnnotation(field.getLeadingAnnotations(), AUTOWIRED);
                        if (autowired != null && (!isDefaultAutowired(autowired) ||
                                field.hasModifier(J.Modifier.Type.Static) ||
                                field.hasModifier(J.Modifier.Type.Final) ||
                                isAggregateType(field.getType()) || isSpringType(field.getType()))) {
                            return true;
                        }
                    } else if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        J.Annotation autowired = findAnnotation(method.getLeadingAnnotations(), AUTOWIRED);
                        if (autowired != null && (!isDefaultAutowired(autowired) ||
                                method.hasModifier(J.Modifier.Type.Static) ||
                                method.hasModifier(J.Modifier.Type.Abstract) ||
                                method.hasModifier(J.Modifier.Type.Private) && method.isConstructor() ||
                                method.getTypeParameters() != null && !method.getTypeParameters().isEmpty() ||
                                hasAggregateParameters(method))) {
                            return true;
                        }
                        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
                            if (parameter instanceof J.VariableDeclarations &&
                                    findAnnotation(((J.VariableDeclarations) parameter).getLeadingAnnotations(),
                                            AUTOWIRED) != null) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            private boolean hasAmbiguousAutowiredDependencies(J.ClassDeclaration classDecl) {
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.VariableDeclarations) {
                        J.VariableDeclarations field = (J.VariableDeclarations) statement;
                        if (findAnnotation(field.getLeadingAnnotations(), AUTOWIRED) != null &&
                                (field.getVariables().size() != 1 ||
                                        findAnnotation(field.getLeadingAnnotations(), NAMED) == null)) {
                            return true;
                        }
                    } else if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        if (findAnnotation(method.getLeadingAnnotations(), AUTOWIRED) == null) {
                            continue;
                        }
                        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
                            if (parameter instanceof J.VariableDeclarations &&
                                    findAnnotation(((J.VariableDeclarations) parameter).getLeadingAnnotations(),
                                            NAMED) == null) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            private boolean hasUnsupportedQualifier(J.ClassDeclaration classDecl) {
                if (findAnnotation(classDecl.getLeadingAnnotations(), QUALIFIER) != null) {
                    return true;
                }
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.VariableDeclarations &&
                            findAnnotation(((J.VariableDeclarations) statement).getLeadingAnnotations(),
                                    QUALIFIER) != null) {
                        return true;
                    }
                    if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        if (findAnnotation(method.getLeadingAnnotations(), QUALIFIER) != null) {
                            return true;
                        }
                        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
                            if (parameter instanceof J.VariableDeclarations &&
                                    findAnnotation(((J.VariableDeclarations) parameter).getLeadingAnnotations(),
                                            QUALIFIER) != null) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            private boolean hasUnsupportedValueMembers(J.ClassDeclaration classDecl) {
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.VariableDeclarations) {
                        if (findAnnotation(((J.VariableDeclarations) statement).getLeadingAnnotations(),
                                VALUE) != null) {
                            return true;
                        }
                    } else if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        if (findAnnotation(method.getLeadingAnnotations(), VALUE) != null) {
                            return true;
                        }
                        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
                            if (!(parameter instanceof J.VariableDeclarations)) {
                                continue;
                            }
                            if (findAnnotation(((J.VariableDeclarations) parameter).getLeadingAnnotations(),
                                    VALUE) != null) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            private boolean hasProducerAnnotation(J.MethodDeclaration method) {
                return findAnnotation(method.getLeadingAnnotations(), BEAN) != null ||
                        findAnnotation(method.getLeadingAnnotations(), PRODUCES) != null;
            }

            private boolean hasAnotherInjectionConstructor(J.ClassDeclaration classDecl,
                                                           J.MethodDeclaration current) {
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration candidate = (J.MethodDeclaration) statement;
                        if (candidate != current && candidate.isConstructor() &&
                                (findAnnotation(candidate.getLeadingAnnotations(), AUTOWIRED) != null ||
                                        findAnnotation(candidate.getLeadingAnnotations(), INJECT) != null)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasUnsupportedBeanMethods(J.ClassDeclaration classDecl) {
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (!(statement instanceof J.MethodDeclaration)) {
                        continue;
                    }
                    J.MethodDeclaration method = (J.MethodDeclaration) statement;
                    J.Annotation bean = findAnnotation(method.getLeadingAnnotations(), BEAN);
                    if (bean != null && (method.hasModifier(J.Modifier.Type.Static) ||
                            method.hasModifier(J.Modifier.Type.Abstract) ||
                            !hasLiteralDestroyInferenceOptOut(bean) ||
                            hasUnsupportedAdjacentAnnotations(method.getLeadingAnnotations()) ||
                            hasUnsupportedProducerSignature(method))) {
                        return true;
                    }
                }
                return false;
            }

            private boolean hasLiteralDestroyInferenceOptOut(J.Annotation bean) {
                if (bean.getArguments() == null || bean.getArguments().size() != 1 ||
                        !(bean.getArguments().get(0) instanceof J.Assignment)) {
                    return false;
                }
                J.Assignment assignment = (J.Assignment) bean.getArguments().get(0);
                if (!"destroyMethod".equals(assignment.getVariable().printTrimmed()) ||
                        !(assignment.getAssignment() instanceof J.Literal)) {
                    return false;
                }
                return "".equals(((J.Literal) assignment.getAssignment()).getValue());
            }

            private boolean hasUnsupportedProducerSignature(J.MethodDeclaration method) {
                if (hasParameters(method) ||
                        method.getTypeParameters() != null && !method.getTypeParameters().isEmpty()) {
                    return true;
                }
                JavaType returnType = method.getReturnTypeExpression() == null ? null :
                        method.getReturnTypeExpression().getType();
                return returnType == null || returnType == JavaType.Primitive.Void ||
                        returnType instanceof JavaType.GenericTypeVariable ||
                        TypeUtils.isAssignableTo("java.lang.AutoCloseable", returnType) ||
                        TypeUtils.isAssignableTo("java.io.Closeable", returnType) ||
                        TypeUtils.isAssignableTo("org.springframework.beans.factory.FactoryBean", returnType);
            }

            private boolean hasUnsupportedAdjacentSpringSemantics(J.ClassDeclaration classDecl) {
                if (hasUnsupportedAdjacentAnnotations(classDecl.getLeadingAnnotations()) ||
                        hasUnsupportedTransactionalPlacement(classDecl)) {
                    return true;
                }
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.VariableDeclarations) {
                        if (hasUnsupportedAdjacentAnnotations(
                                ((J.VariableDeclarations) statement).getLeadingAnnotations())) {
                            return true;
                        }
                    } else if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        if (hasUnsupportedAdjacentAnnotations(method.getLeadingAnnotations())) {
                            return true;
                        }
                        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
                            if (parameter instanceof J.VariableDeclarations &&
                                    hasUnsupportedAdjacentAnnotations(
                                            ((J.VariableDeclarations) parameter).getLeadingAnnotations())) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            private boolean hasUnsupportedTransactionalPlacement(J.ClassDeclaration classDecl) {
                for (org.openrewrite.java.tree.Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        if (findAnnotation(method.getLeadingAnnotations(), TRANSACTIONAL) != null &&
                                (method.isConstructor() || method.hasModifier(J.Modifier.Type.Private) ||
                                        method.hasModifier(J.Modifier.Type.Static) ||
                                        method.hasModifier(J.Modifier.Type.Abstract))) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasUnsupportedAdjacentAnnotations(List<J.Annotation> annotations) {
                for (J.Annotation annotation : annotations) {
                    if (isLombokConstructorGeneratingAnnotation(annotation)) {
                        return true;
                    }
                    if (isHandledSpringAnnotation(annotation)) {
                        continue;
                    }
                    if (TRANSACTIONAL.matches(annotation)) {
                        if (!isSupportedTransactional(annotation)) {
                            return true;
                        }
                        continue;
                    }
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
                    if (type == null) {
                        continue;
                    }
                    String name = type.getFullyQualifiedName();
                    if (name.startsWith("org.springframework.")) {
                        if (!"org.springframework.beans.factory.annotation.Value".equals(name)) {
                            return true;
                        }
                    } else if (hasUnsupportedSpringMetaAnnotation(type, new HashSet<String>(), 0)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean isLombokConstructorGeneratingAnnotation(J.Annotation annotation) {
                String simpleName = annotation.getSimpleName();
                if (!("RequiredArgsConstructor".equals(simpleName) ||
                        "AllArgsConstructor".equals(simpleName) ||
                        "NoArgsConstructor".equals(simpleName) ||
                        "Data".equals(simpleName) || "Value".equals(simpleName) ||
                        "Builder".equals(simpleName) || "SuperBuilder".equals(simpleName))) {
                    return false;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
                return type == null || type.getFullyQualifiedName().startsWith("lombok.");
            }

            private boolean isHandledSpringAnnotation(J.Annotation annotation) {
                return CONFIGURATION.matches(annotation) || SERVICE.matches(annotation) ||
                        COMPONENT.matches(annotation) || REPOSITORY.matches(annotation) ||
                        AUTOWIRED.matches(annotation) || BEAN.matches(annotation) ||
                        QUALIFIER.matches(annotation);
            }

            private boolean isSupportedTransactional(J.Annotation annotation) {
                if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                    return true;
                }
                for (Expression argument : annotation.getArguments()) {
                    if (!(argument instanceof J.Assignment)) {
                        return false;
                    }
                    String attribute = ((J.Assignment) argument).getVariable().printTrimmed();
                    if (!"rollbackFor".equals(attribute) && !"noRollbackFor".equals(attribute)) {
                        return false;
                    }
                }
                return true;
            }

            private boolean hasUnsupportedSpringMetaAnnotation(JavaType.FullyQualified type,
                                                               Set<String> visited,
                                                               int depth) {
                if (depth > 4 || !visited.add(type.getFullyQualifiedName())) {
                    return false;
                }
                for (JavaType.FullyQualified metaAnnotation : type.getAnnotations()) {
                    String name = metaAnnotation.getFullyQualifiedName();
                    if (isExplicitlyUnsupportedSpringAnnotation(name) ||
                            hasUnsupportedSpringMetaAnnotation(metaAnnotation, visited, depth + 1)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean isExplicitlyUnsupportedSpringAnnotation(String name) {
                return "org.springframework.context.annotation.Scope".equals(name) ||
                        "org.springframework.context.annotation.Profile".equals(name) ||
                        "org.springframework.context.annotation.Conditional".equals(name) ||
                        "org.springframework.context.annotation.Lazy".equals(name) ||
                        "org.springframework.context.annotation.DependsOn".equals(name) ||
                        "org.springframework.context.annotation.Import".equals(name) ||
                        "org.springframework.context.annotation.ImportResource".equals(name) ||
                        "org.springframework.context.annotation.ComponentScan".equals(name) ||
                        "org.springframework.context.annotation.ComponentScans".equals(name) ||
                        "org.springframework.context.annotation.PropertySource".equals(name) ||
                        "org.springframework.context.annotation.PropertySources".equals(name) ||
                        name.startsWith("org.springframework.boot.autoconfigure.condition.Conditional");
            }

            private boolean hasAggregateInjectionTarget(Object parent) {
                if (parent instanceof J.VariableDeclarations) {
                    JavaType type = ((J.VariableDeclarations) parent).getType();
                    return isAggregateType(type) || isSpringType(type);
                }
                return parent instanceof J.MethodDeclaration &&
                        hasAggregateParameters((J.MethodDeclaration) parent);
            }

            private boolean hasAggregateParameters(J.MethodDeclaration method) {
                for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
                    if (parameter instanceof J.VariableDeclarations &&
                            (isAggregateType(((J.VariableDeclarations) parameter).getType()) ||
                                    isSpringType(((J.VariableDeclarations) parameter).getType()))) {
                            return true;
                    }
                }
                return false;
            }

            private boolean isAggregateType(JavaType type) {
                return type instanceof JavaType.Array ||
                        TypeUtils.isAssignableTo("java.util.Collection", type) ||
                        TypeUtils.isAssignableTo("java.util.Map", type) ||
                        TypeUtils.isAssignableTo("java.util.Optional", type) ||
                        TypeUtils.isAssignableTo("java.util.stream.Stream", type) ||
                        TypeUtils.isAssignableTo("org.springframework.beans.factory.ObjectProvider", type) ||
                        TypeUtils.isAssignableTo("org.springframework.beans.factory.ObjectFactory", type);
            }

            private boolean isSpringType(JavaType type) {
                JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
                return fullyQualified != null &&
                        fullyQualified.getFullyQualifiedName().startsWith("org.springframework.");
            }

            private boolean hasParameters(J.MethodDeclaration method) {
                for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
                    if (!(parameter instanceof J.Empty)) {
                        return true;
                    }
                }
                return false;
            }

            private J.MethodDeclaration markMethodAnnotation(J.MethodDeclaration method,
                                                             AnnotationMatcher matcher,
                                                             String message) {
                return method.withLeadingAnnotations(ListUtils.map(method.getLeadingAnnotations(), annotation ->
                        matcher.matches(annotation) ? SearchResult.found(annotation, message) : annotation));
            }

            private AnnotationMatcher stereotypeMatcher(J.Annotation annotation) {
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
                if (type == null) {
                    return SERVICE.matches(annotation) ? SERVICE :
                            COMPONENT.matches(annotation) ? COMPONENT : REPOSITORY;
                }
                return new AnnotationMatcher("@" + type.getFullyQualifiedName());
            }

            private String decapitalize(String name) {
                if (name.isEmpty() || (name.length() > 1 && Character.isUpperCase(name.charAt(0)) &&
                        Character.isUpperCase(name.charAt(1)))) {
                    return name;
                }
                return Character.toLowerCase(name.charAt(0)) + name.substring(1);
            }
        });
    }

    private static J.Annotation findStereotype(List<J.Annotation> annotations) {
        for (J.Annotation annotation : annotations) {
            if (isStereotype(annotation)) {
                return annotation;
            }
        }
        return null;
    }

    private static J.Annotation findAnnotation(List<J.Annotation> annotations, AnnotationMatcher matcher) {
        for (J.Annotation annotation : annotations) {
            if (matcher.matches(annotation)) {
                return annotation;
            }
        }
        return null;
    }

    private static boolean isStereotype(J.Annotation annotation) {
        return SERVICE.matches(annotation) || COMPONENT.matches(annotation) ||
                REPOSITORY.matches(annotation);
    }

    private static boolean hasArguments(J.Annotation annotation) {
        return annotation.getArguments() != null && !annotation.getArguments().isEmpty();
    }

    private static boolean hasProxyBeanMethodsFalse(J.Annotation annotation) {
        if (annotation.getArguments() == null || annotation.getArguments().size() != 1) {
            return false;
        }
        Expression argument = annotation.getArguments().get(0);
        if (!(argument instanceof J.Assignment)) {
            return false;
        }
        J.Assignment assignment = (J.Assignment) argument;
        if (!"proxyBeanMethods".equals(assignment.getVariable().printTrimmed())) {
            return false;
        }
        return assignment.getAssignment() instanceof J.Literal &&
                Boolean.FALSE.equals(((J.Literal) assignment.getAssignment()).getValue());
    }

    private static boolean isDefaultAutowired(J.Annotation annotation) {
        if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
            return true;
        }
        if (annotation.getArguments().size() != 1 ||
                !(annotation.getArguments().get(0) instanceof J.Assignment)) {
            return false;
        }
        J.Assignment assignment = (J.Assignment) annotation.getArguments().get(0);
        if (!"required".equals(assignment.getVariable().printTrimmed())) {
            return false;
        }
        return assignment.getAssignment() instanceof J.Literal &&
                Boolean.TRUE.equals(((J.Literal) assignment.getAssignment()).getValue());
    }
}
