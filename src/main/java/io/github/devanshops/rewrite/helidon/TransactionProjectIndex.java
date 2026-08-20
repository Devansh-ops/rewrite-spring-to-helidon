package io.github.devanshops.rewrite.helidon;

import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Collects transaction facts before any source annotation is changed. */
final class TransactionProjectIndex {
    private static final int MAX_META_ANNOTATION_DEPTH = 32;
    private static final Pattern SPRING_TX_ADVICE = Pattern.compile(
            "<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?(?:advice|annotation-driven|" +
            "jta-transaction-manager)\\b");
    private final Map<UUID, AnnotationFact> facts =
            new LinkedHashMap<UUID, AnnotationFact>();
    private final Map<UUID, List<AnnotationFact>> factsByClass =
            new LinkedHashMap<UUID, List<AnnotationFact>>();
    private final Map<UUID, TransactionDecision> decisions =
            new LinkedHashMap<UUID, TransactionDecision>();
    private final Map<UUID, ClassInfo> classesById =
            new LinkedHashMap<UUID, ClassInfo>();
    private final Map<String, ClassInfo> classesByName =
            new LinkedHashMap<String, ClassInfo>();
    private final Map<String, ModulePolicy> policies =
            new LinkedHashMap<String, ModulePolicy>();
    private final Set<String> composedTransactionAnnotations = new HashSet<String>();
    private boolean planned;

    synchronized void recordProjectAnnotation(J.Annotation annotation, Cursor cursor) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
        if (type == null || !"org.springframework.transaction.annotation.EnableTransactionManagement"
                .equals(type.getFullyQualifiedName())) {
            return;
        }
        ModulePolicy policy = policy(moduleKey(cursor));
        Boolean allExceptions = Boolean.FALSE;
        if (annotation.getArguments() != null) {
            for (Expression argument : annotation.getArguments()) {
                if (!(argument instanceof J.Assignment)) {
                    continue;
                }
                J.Assignment assignment = (J.Assignment) argument;
                String attribute = assignment.getVariable().printTrimmed();
                if ("rollbackOn".equals(attribute)) {
                    String rollbackOn = enumConstant(assignment.getAssignment(),
                            "org.springframework.transaction.annotation.RollbackOn");
                    if ("ALL_EXCEPTIONS".equals(rollbackOn)) {
                        allExceptions = Boolean.TRUE;
                    } else if (!"RUNTIME_EXCEPTIONS".equals(rollbackOn)) {
                        policy.refuse("TX_GLOBAL_ROLLBACK_UNRESOLVED",
                                "the effective Spring global rollback policy cannot be resolved");
                    }
                } else if ("mode".equals(attribute) &&
                        assignment.getAssignment().printTrimmed().endsWith("ASPECTJ")) {
                    policy.refuse("TX_ASPECTJ_MODE",
                            "Spring AspectJ transaction weaving is not equivalent to CDI interception");
                }
            }
        }
        if (policy.allExceptionsRollback != null &&
                !policy.allExceptionsRollback.equals(allExceptions)) {
            policy.refuse("TX_GLOBAL_ROLLBACK_CONFLICT",
                    "conflicting Spring global rollback policies were found in this source set");
        } else {
            policy.allExceptionsRollback = allExceptions;
        }
    }

    synchronized void recordClass(J.ClassDeclaration classDecl, Cursor cursor) {
        JavaType.FullyQualified type = classDecl.getType();
        if (type != null) {
            ClassInfo info = new ClassInfo(classDecl.getId(), type.getFullyQualifiedName(),
                    moduleKey(cursor), typeName(classDecl.getExtends()),
                    typeNames(classDecl.getImplements()), classLevelRefusal(classDecl, cursor),
                    methodLevelTypeRefusal(classDecl, cursor),
                    hasUnresolvedHierarchy(classDecl));
            classesById.put(classDecl.getId(), info);
            classesByName.put(classKey(info.moduleKey, info.typeName), info);
        }
        if (type != null && classDecl.getKind() == J.ClassDeclaration.Kind.Type.Annotation) {
            for (J.Annotation annotation : classDecl.getLeadingAnnotations()) {
                JavaType.FullyQualified annotationType = TypeUtils.asFullyQualified(
                        annotation.getType());
                if (annotationType != null &&
                        "org.springframework.transaction.annotation.Transactional".equals(
                                annotationType.getFullyQualifiedName())) {
                    composedTransactionAnnotations.add(type.getFullyQualifiedName());
                    break;
                }
            }
        }
        if (type != null && !"org.springframework.transaction.annotation.AnnotationTransactionAttributeSource"
                .equals(type.getFullyQualifiedName()) &&
                TypeUtils.isAssignableTo(
                        "org.springframework.transaction.annotation.AnnotationTransactionAttributeSource",
                        type)) {
            policy(moduleKey(cursor)).refuse("TX_CUSTOM_ROLLBACK_POLICY",
                    "programmatic Spring default rollback rules require an explicit target policy");
        }
        if (type != null && TypeUtils.isAssignableTo(
                "org.springframework.transaction.annotation.TransactionManagementConfigurer", type)) {
            refuseProgrammatic(policy(moduleKey(cursor)));
        }
        if (type != null && TypeUtils.isAssignableTo(
                "org.springframework.transaction.PlatformTransactionManager", type) &&
                hasAnnotationOrMetaAnnotation(classDecl.getLeadingAnnotations(),
                        "org.springframework.beans.factory.annotation.Qualifier")) {
            policy(moduleKey(cursor)).refuse("TX_QUALIFIED_TRANSACTION_MANAGER",
                    "qualified Spring transaction managers require an explicit target routing policy");
        }
        if (type != null && !classDecl.hasModifier(J.Modifier.Type.Abstract) &&
                TypeUtils.isAssignableTo(
                        "org.springframework.transaction.PlatformTransactionManager", type) &&
                hasSpringStereotype(classDecl.getLeadingAnnotations())) {
            policy(moduleKey(cursor)).managerCount++;
        }
    }

    synchronized boolean isComposedTransactionAnnotation(String typeName) {
        return composedTransactionAnnotations.contains(typeName);
    }

    synchronized void recordManagerDeclaration(J.VariableDeclarations declarations,
                                                Cursor cursor) {
        JavaType type = declarations.getType();
        ModulePolicy policy = policy(moduleKey(cursor));
        if (TypeUtils.isAssignableTo("org.springframework.transaction.reactive.TransactionalOperator",
                type)) {
            policy.refuse("TX_REACTIVE_TRANSACTION_API",
                    "programmatic reactive Spring transactions have no invocation-scoped Jakarta mapping");
            return;
        }
        if (TypeUtils.isAssignableTo("org.springframework.transaction.support.TransactionTemplate",
                type) || TypeUtils.isAssignableTo(
                "org.springframework.transaction.interceptor.TransactionAttributeSource", type)) {
            refuseProgrammatic(policy);
            return;
        }
        if (TypeUtils.isAssignableTo("org.springframework.transaction.ReactiveTransactionManager",
                type)) {
            policy.refuse("TX_REACTIVE_TRANSACTION_MANAGER",
                    "reactive Spring transactions are not invocation-scoped Jakarta CDI transactions");
            return;
        }
        if (!TypeUtils.isAssignableTo("org.springframework.transaction.PlatformTransactionManager",
                type)) {
            return;
        }
        if (hasAnnotationOrMetaAnnotation(declarations.getLeadingAnnotations(),
                "org.springframework.beans.factory.annotation.Qualifier")) {
            policy.refuse("TX_QUALIFIED_TRANSACTION_MANAGER",
                    "qualified Spring transaction managers require an explicit target routing policy");
        }
    }

    synchronized void recordManagerMethod(J.MethodDeclaration method, Cursor cursor) {
        if (method.getReturnTypeExpression() == null || !hasAnnotationOrMetaAnnotation(
                method.getLeadingAnnotations(), "org.springframework.context.annotation.Bean")) {
            return;
        }
        JavaType returnType = method.getReturnTypeExpression().getType();
        ModulePolicy policy = policy(moduleKey(cursor));
        if (TypeUtils.isAssignableTo(
                "org.springframework.transaction.reactive.TransactionalOperator", returnType)) {
            policy.refuse("TX_REACTIVE_TRANSACTION_API",
                    "programmatic reactive Spring transactions have no invocation-scoped Jakarta mapping");
            return;
        }
        if (TypeUtils.isAssignableTo("org.springframework.transaction.support.TransactionTemplate",
                returnType) || TypeUtils.isAssignableTo(
                "org.springframework.transaction.interceptor.TransactionAttributeSource", returnType)) {
            refuseProgrammatic(policy);
            return;
        }
        if (TypeUtils.isAssignableTo("org.springframework.transaction.ReactiveTransactionManager",
                returnType)) {
            policy.refuse("TX_REACTIVE_TRANSACTION_MANAGER",
                    "reactive Spring transactions are not invocation-scoped Jakarta CDI transactions");
            return;
        }
        if (!TypeUtils.isAssignableTo("org.springframework.transaction.PlatformTransactionManager",
                returnType)) {
            return;
        }
        policy.managerCount++;
        if (hasAnnotationOrMetaAnnotation(method.getLeadingAnnotations(),
                "org.springframework.beans.factory.annotation.Qualifier")) {
            policy.refuse("TX_QUALIFIED_TRANSACTION_MANAGER",
                    "qualified Spring transaction managers require an explicit target routing policy");
        }
    }

    synchronized void recordProgrammaticPolicy(J.MethodInvocation invocation, Cursor cursor) {
        JavaType.Method methodType = invocation.getMethodType();
        JavaType.FullyQualified declaringType = methodType == null ? null :
                TypeUtils.asFullyQualified(methodType.getDeclaringType());
        if ("addDefaultRollbackRule".equals(invocation.getSimpleName()) &&
                declaringType != null &&
                "org.springframework.transaction.annotation.AnnotationTransactionAttributeSource"
                        .equals(declaringType.getFullyQualifiedName())) {
            policy(moduleKey(cursor)).refuse("TX_CUSTOM_ROLLBACK_POLICY",
                    "programmatic Spring default rollback rules require an explicit target policy");
        }
        if (declaringType != null && TypeUtils.isAssignableTo(
                "org.springframework.transaction.PlatformTransactionManager", declaringType) &&
                ("getTransaction".equals(invocation.getSimpleName()) ||
                        "commit".equals(invocation.getSimpleName()) ||
                        "rollback".equals(invocation.getSimpleName()))) {
            refuseProgrammatic(policy(moduleKey(cursor)));
        }
        if (declaringType != null && (TypeUtils.isAssignableTo(
                "org.springframework.transaction.interceptor.TransactionAspectSupport",
                declaringType) || TypeUtils.isAssignableTo(
                "org.springframework.transaction.support.TransactionSynchronizationManager",
                declaringType) || TypeUtils.isAssignableTo(
                "org.springframework.transaction.support.TransactionSynchronizationUtils",
                declaringType) || TypeUtils.isAssignableTo(
                "org.springframework.transaction.support.TransactionOperations", declaringType))) {
            refuseProgrammatic(policy(moduleKey(cursor)));
        }
        if (declaringType != null && (TypeUtils.isAssignableTo(
                "org.springframework.transaction.reactive.TransactionalOperator", declaringType) ||
                TypeUtils.isAssignableTo(
                        "org.springframework.transaction.reactive.TransactionContextManager",
                        declaringType))) {
            policy(moduleKey(cursor)).refuse("TX_REACTIVE_TRANSACTION_API",
                    "programmatic reactive Spring transactions have no invocation-scoped Jakarta mapping");
        }
    }

    synchronized void recordProgrammaticConstruction(J.NewClass newClass, Cursor cursor) {
        if (TypeUtils.isAssignableTo("org.springframework.transaction.support.TransactionTemplate",
                newClass.getType())) {
            refuseProgrammatic(policy(moduleKey(cursor)));
        }
    }

    synchronized void recordSpringTransactionExceptionUse(J.Identifier identifier,
                                                          Cursor cursor) {
        if (TypeUtils.isOfClassType(identifier.getType(),
                "org.springframework.transaction.IllegalTransactionStateException")) {
            policy(moduleKey(cursor)).springTransactionExceptionCoupling = true;
        }
    }

    synchronized void recordXmlPolicy(Path sourcePath, String xml) {
        if ((xml.contains("http://www.springframework.org/schema/tx") &&
                SPRING_TX_ADVICE.matcher(xml).find()) ||
                xml.contains("org.springframework.transaction.")) {
            policy(moduleKey(sourcePath.normalize().toString()
                    .replace(File.separatorChar, '/'))).refuse("TX_XML_TRANSACTION_POLICY",
                    "XML Spring transaction advice requires an explicit target policy");
        }
    }

    private ModulePolicy policy(String moduleKey) {
        return policies.computeIfAbsent(moduleKey, key -> new ModulePolicy());
    }

    private static void refuseProgrammatic(ModulePolicy policy) {
        policy.refuse("TX_PROGRAMMATIC_TRANSACTION_POLICY",
                "programmatic Spring transaction policy requires an explicit Jakarta target contract");
    }

    synchronized void record(J.Annotation annotation, Cursor cursor, boolean composedUsage,
                             boolean missingAttribution) {
        J.ClassDeclaration owner = cursor.firstEnclosing(J.ClassDeclaration.class);
        J.MethodDeclaration method = cursor.firstEnclosing(J.MethodDeclaration.class);
        J.CompilationUnit compilationUnit = cursor.firstEnclosing(J.CompilationUnit.class);
        if (owner == null || compilationUnit == null || facts.containsKey(annotation.getId())) {
            return;
        }
        String sourcePath = compilationUnit.getSourcePath().normalize().toString()
                .replace(File.separatorChar, '/');
        ParsedAnnotation parsed = parse(annotation);
        AnnotationFact fact = new AnnotationFact(annotation.getId(), owner.getId(), sourcePath,
                sourceKind(sourcePath), parsed.propagation, isCdiBean(owner),
                parsed.refusalCode, parsed.refusalReason, parsed.rollbackFor,
                parsed.noRollbackFor, isInterceptable(owner, method), isLifecycle(method),
                usesUserTransaction(method == null ? owner : method),
                isReactiveReturn(method),
                hasJakartaTransactional(method == null ? owner.getLeadingAnnotations() :
                        method.getLeadingAnnotations()), moduleKey(sourcePath),
                composedUsage || owner.getKind() == J.ClassDeclaration.Kind.Type.Annotation,
                missingAttribution, method == null,
                hasAnnotationOrMetaAnnotation(owner.getLeadingAnnotations(),
                        "org.springframework.beans.factory.annotation.Qualifier"));
        facts.put(fact.id, fact);
        factsByClass.computeIfAbsent(fact.ownerId,
                key -> new ArrayList<AnnotationFact>()).add(fact);
    }

    synchronized void plan(boolean includesSupports) {
        if (planned) {
            return;
        }
        planned = true;
        for (FactGroup group : hierarchyFactGroups()) {
            List<AnnotationFact> classFacts = group.facts;
            Map<UUID, Refusal> refusals = new LinkedHashMap<UUID, Refusal>();
            for (AnnotationFact fact : classFacts) {
                ModulePolicy policy = policy(fact.moduleKey);
                Refusal projectRefusal = policy.refusal();
                Refusal propagationRefusal = policy.propagationRefusal(fact.propagation);
                Refusal globalRollbackRefusal = globalRollbackRefusal(fact, policy);
                Refusal hierarchyRefusal = externalHierarchyRefusal(fact);
                Refusal governedRefusal = fact.classLevel ? group.classLevelRefusal :
                        fact.cdiBean ? methodLevelRefusal(fact) : null;
                Refusal refusal = projectRefusal != null ? projectRefusal :
                        propagationRefusal != null ? propagationRefusal :
                        globalRollbackRefusal != null ? globalRollbackRefusal :
                        hierarchyRefusal != null ? hierarchyRefusal :
                                governedRefusal != null ? governedRefusal :
                                        refusalFor(fact, includesSupports);
                if (refusal != null) {
                    refusals.put(fact.id, refusal);
                }
            }
            for (AnnotationFact fact : classFacts) {
                Refusal ownRefusal = refusals.get(fact.id);
                if (ownRefusal != null) {
                    decisions.put(fact.id, TransactionDecision.refused(fact.sourcePath,
                            fact.sourceKind, ownRefusal.code, ownRefusal.reason));
                } else if (!refusals.isEmpty()) {
                    decisions.put(fact.id, TransactionDecision.refused(fact.sourcePath,
                            fact.sourceKind, "TX_ATOMIC_SCOPE_REFUSED",
                            "another Spring transaction annotation in this class cannot be migrated"));
                } else {
                    String target = targetAnnotation(fact, Boolean.TRUE.equals(
                            policy(fact.moduleKey).allExceptionsRollback));
                    decisions.put(fact.id, TransactionDecision.migrated(fact.sourcePath,
                            fact.sourceKind, "TX_MIGRATED_" + fact.propagation,
                            "migrated Spring " + fact.propagation +
                            " semantics to Jakarta Transactions", target));
                }
            }
        }
    }

    synchronized TransactionDecision decision(UUID annotationId) {
        return decisions.get(annotationId);
    }

    private List<FactGroup> hierarchyFactGroups() {
        Map<UUID, UUID> parents = new LinkedHashMap<UUID, UUID>();
        for (UUID classId : classesById.keySet()) {
            parents.put(classId, classId);
        }
        for (ClassInfo info : classesById.values()) {
            unionSourceType(parents, info, info.superType);
            for (String interfaceType : info.interfaces) {
                unionSourceType(parents, info, interfaceType);
            }
        }
        Map<UUID, FactGroup> groups = new LinkedHashMap<UUID, FactGroup>();
        for (Map.Entry<UUID, List<AnnotationFact>> entry : factsByClass.entrySet()) {
            UUID root = find(parents, entry.getKey());
            groups.computeIfAbsent(root, key -> new FactGroup()).facts.addAll(entry.getValue());
        }
        for (ClassInfo info : classesById.values()) {
            FactGroup group = groups.get(find(parents, info.id));
            if (group != null && group.classLevelRefusal == null &&
                    info.classLevelRefusal != null) {
                group.classLevelRefusal = info.classLevelRefusal;
            }
        }
        return new ArrayList<FactGroup>(groups.values());
    }

    private void unionSourceType(Map<UUID, UUID> parents, ClassInfo child, String relatedType) {
        if (relatedType == null) {
            return;
        }
        ClassInfo related = classesByName.get(classKey(child.moduleKey, relatedType));
        if (related != null) {
            UUID childRoot = find(parents, child.id);
            UUID relatedRoot = find(parents, related.id);
            if (childRoot != null && relatedRoot != null && !childRoot.equals(relatedRoot)) {
                parents.put(childRoot, relatedRoot);
            }
        }
    }

    private static UUID find(Map<UUID, UUID> parents, UUID id) {
        UUID parent = parents.get(id);
        if (parent == null || parent.equals(id)) {
            return id;
        }
        UUID root = find(parents, parent);
        parents.put(id, root);
        return root;
    }

    private Refusal externalHierarchyRefusal(AnnotationFact fact) {
        ClassInfo info = classesById.get(fact.ownerId);
        if (info == null) {
            return new Refusal("TX_MISSING_ATTRIBUTION",
                    "transaction migration requires an attributed source-visible target type");
        }
        if (info.unresolvedHierarchy) {
            return new Refusal("TX_MISSING_ATTRIBUTION",
                    "the transactional type declares a hierarchy member whose type is not attributed");
        }
        if (isExternal(info, info.superType)) {
            return new Refusal("TX_EXTERNAL_HIERARCHY",
                    "the transactional type has a hierarchy member outside this source module");
        }
        for (String interfaceType : info.interfaces) {
            if (isExternal(info, interfaceType)) {
                return new Refusal("TX_EXTERNAL_HIERARCHY",
                        "the transactional type has a hierarchy member outside this source module");
            }
        }
        return null;
    }

    private static Refusal classLevelRefusal(J.ClassDeclaration classDecl, Cursor cursor) {
        if (classDecl.getKind() == J.ClassDeclaration.Kind.Type.Annotation) {
            return null;
        }
        Refusal typeRefusal = methodLevelTypeRefusal(classDecl, cursor);
        if (typeRefusal != null) {
            return typeRefusal.reason.startsWith("a normal-scoped") ? typeRefusal :
                    nonInterceptableClassLevelRefusal();
        }
        for (org.openrewrite.java.tree.Statement statement :
                classDecl.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration)) {
                continue;
            }
            J.MethodDeclaration method = (J.MethodDeclaration) statement;
            if (method.isConstructor()) {
                continue;
            }
            if (method.isAbstract() || method.getBody() == null ||
                    method.hasModifier(J.Modifier.Type.Private) ||
                    method.hasModifier(J.Modifier.Type.Static) ||
                    method.hasModifier(J.Modifier.Type.Final) ||
                    method.hasModifier(J.Modifier.Type.Native)) {
                return nonInterceptableClassLevelRefusal();
            }
            if (isLifecycle(method)) {
                return new Refusal("TX_LIFECYCLE_METHOD",
                        "a class-level transaction governs a lifecycle callback");
            }
            if (usesUserTransaction(method)) {
                return new Refusal("TX_USER_TRANSACTION",
                        "a class-level transaction governs code that directly uses Jakarta UserTransaction");
            }
            JavaType returnType = method.getReturnTypeExpression() == null ? null :
                    method.getReturnTypeExpression().getType();
            if (TypeUtils.isAssignableTo("org.reactivestreams.Publisher", returnType)) {
                return new Refusal("TX_REACTIVE_RETURN",
                        "Spring reactive transaction completion cannot be represented by " +
                        "Jakarta invocation-scoped interception");
            }
        }
        return null;
    }

    private Refusal methodLevelRefusal(AnnotationFact fact) {
        ClassInfo info = classesById.get(fact.ownerId);
        return info == null ? null : info.methodLevelRefusal;
    }

    private static Refusal methodLevelTypeRefusal(J.ClassDeclaration classDecl, Cursor cursor) {
        boolean nestedNonStatic = cursor.getParentTreeCursor()
                .firstEnclosing(J.ClassDeclaration.class) != null &&
                !classDecl.hasModifier(J.Modifier.Type.Static);
        if (classDecl.getKind() != J.ClassDeclaration.Kind.Type.Class ||
                classDecl.hasModifier(J.Modifier.Type.Final) ||
                classDecl.hasModifier(J.Modifier.Type.Abstract) ||
                classDecl.hasModifier(J.Modifier.Type.Sealed) || nestedNonStatic) {
            return new Refusal("TX_NON_INTERCEPTABLE_TARGET",
                    "the target class or method cannot be intercepted by CDI");
        }
        if (hasNormalScope(classDecl) && !hasProxyConstructor(classDecl)) {
            return new Refusal("TX_NON_INTERCEPTABLE_TARGET",
                    "a normal-scoped transactional bean has no non-private no-argument " +
                    "proxy constructor");
        }
        return null;
    }

    private static boolean hasNormalScope(J.ClassDeclaration classDecl) {
        return hasAnnotation(classDecl.getLeadingAnnotations(),
                "jakarta.enterprise.context.ApplicationScoped") ||
                hasAnnotation(classDecl.getLeadingAnnotations(),
                        "jakarta.enterprise.context.RequestScoped") ||
                hasAnnotation(classDecl.getLeadingAnnotations(),
                        "jakarta.enterprise.context.SessionScoped") ||
                hasAnnotation(classDecl.getLeadingAnnotations(),
                        "jakarta.enterprise.context.ConversationScoped") ||
                hasProvenAnnotationOrMetaAnnotation(classDecl.getLeadingAnnotations(),
                        "jakarta.enterprise.context.NormalScope");
    }

    private static boolean hasProxyConstructor(J.ClassDeclaration classDecl) {
        boolean explicitConstructor = false;
        for (org.openrewrite.java.tree.Statement statement :
                classDecl.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration) ||
                    !((J.MethodDeclaration) statement).isConstructor()) {
                continue;
            }
            explicitConstructor = true;
            J.MethodDeclaration constructor = (J.MethodDeclaration) statement;
            List<org.openrewrite.java.tree.Statement> parameters = constructor.getParameters();
            boolean noArguments = parameters.isEmpty() ||
                    (parameters.size() == 1 && parameters.get(0) instanceof J.Empty);
            if (noArguments && !constructor.hasModifier(J.Modifier.Type.Private)) {
                return true;
            }
        }
        return !explicitConstructor;
    }

    private static Refusal nonInterceptableClassLevelRefusal() {
        return new Refusal("TX_NON_INTERCEPTABLE_TARGET",
                "a class-level transaction governs a member or type that cannot be " +
                "intercepted safely by CDI");
    }

    private boolean isExternal(ClassInfo owner, String relatedType) {
        if (relatedType == null || "java.lang.Object".equals(relatedType)) {
            return false;
        }
        return classesByName.get(classKey(owner.moduleKey, relatedType)) == null;
    }

    private static String classKey(String moduleKey, String typeName) {
        return moduleKey + '\u0000' + typeName;
    }

    private static String typeName(org.openrewrite.java.tree.TypeTree typeTree) {
        if (typeTree == null) {
            return null;
        }
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(typeTree.getType());
        return type == null ? null : type.getFullyQualifiedName();
    }

    private static List<String> typeNames(List<org.openrewrite.java.tree.TypeTree> typeTrees) {
        List<String> names = new ArrayList<String>();
        if (typeTrees == null) {
            return names;
        }
        for (org.openrewrite.java.tree.TypeTree typeTree : typeTrees) {
            String name = typeName(typeTree);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static boolean hasUnresolvedHierarchy(J.ClassDeclaration classDecl) {
        if (classDecl.getExtends() != null && typeName(classDecl.getExtends()) == null) {
            return true;
        }
        if (classDecl.getImplements() != null) {
            for (org.openrewrite.java.tree.TypeTree implemented : classDecl.getImplements()) {
                if (typeName(implemented) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDirectPropagation(String propagation) {
        return "REQUIRED".equals(propagation) || "REQUIRES_NEW".equals(propagation) ||
                "MANDATORY".equals(propagation) || "NOT_SUPPORTED".equals(propagation) ||
                "NEVER".equals(propagation);
    }

    private static String targetAnnotation(AnnotationFact fact, boolean allExceptionsRollback) {
        List<String> members = new ArrayList<String>();
        if (!"REQUIRED".equals(fact.propagation)) {
            members.add("value = Transactional.TxType." + fact.propagation);
        }
        List<String> rollback = new ArrayList<String>();
        rollback.add("Error.class");
        if (allExceptionsRollback) {
            rollback.add("Exception.class");
        }
        for (ClassRule rule : fact.rollbackFor) {
            if (!"java.lang.Error".equals(rule.fullyQualifiedName)) {
                rollback.add(rule.fullyQualifiedName + ".class");
            }
        }
        members.add("rollbackOn = " + classArray(rollback));
        if (!fact.noRollbackFor.isEmpty()) {
            List<String> noRollback = new ArrayList<String>();
            for (ClassRule rule : fact.noRollbackFor) {
                noRollback.add(rule.fullyQualifiedName + ".class");
            }
            members.add("dontRollbackOn = " + classArray(noRollback));
        }
        return "@Transactional(" + join(members) + ")";
    }

    private static String classArray(List<String> values) {
        if (values.size() == 1) {
            return values.get(0);
        }
        return "{" + join(values) + "}";
    }

    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(value);
        }
        return joined.toString();
    }

    private static Refusal refusalFor(AnnotationFact fact, boolean includesSupports) {
        if (fact.missingAttribution) {
            return new Refusal("TX_MISSING_ATTRIBUTION",
                    "exact Spring @Transactional syntax is present but its type is not attributed");
        }
        if (fact.composed) {
            return new Refusal("TX_COMPOSED_TRANSACTION",
                    "composed Spring transaction annotations require an explicit target annotation contract");
        }
        if (fact.managerQualifiedBean) {
            return new Refusal("TX_MANAGER_SELECTION",
                    "Spring transaction-manager selection requires an explicit target routing policy");
        }
        if (fact.refusalCode != null) {
            return new Refusal(fact.refusalCode, fact.refusalReason);
        }
        if ("JAVA_TEST".equals(fact.sourceKind)) {
            return new Refusal("TX_TEST_TRANSACTION",
                    "Spring test-managed transactions are not Jakarta CDI business transactions");
        }
        if (!fact.cdiBean) {
            return new Refusal("TX_NON_CDI_TARGET",
                    "the enclosing type is not an attributed CDI bean");
        }
        if (fact.jakartaCollision) {
            return new Refusal("TX_JAKARTA_COLLISION",
                    "the same target already declares Jakarta @Transactional");
        }
        if (!fact.interceptable) {
            return new Refusal("TX_NON_INTERCEPTABLE_TARGET",
                    "the target class or method cannot be intercepted by CDI");
        }
        if (fact.lifecycle) {
            return new Refusal("TX_LIFECYCLE_METHOD",
                    "Jakarta transaction interception does not apply to lifecycle callbacks");
        }
        if (fact.usesUserTransaction) {
            return new Refusal("TX_USER_TRANSACTION",
                    "this transaction scope directly uses Jakarta UserTransaction");
        }
        if (fact.reactiveReturn) {
            return new Refusal("TX_REACTIVE_RETURN",
                    "Spring reactive transaction completion cannot be represented by " +
                    "Jakarta invocation-scoped interception");
        }
        if ("NESTED".equals(fact.propagation)) {
            return new Refusal("TX_NESTED_NO_EQUIVALENT",
                    "Spring NESTED propagation has no Jakarta Transactions equivalent");
        }
        if ("SUPPORTS".equals(fact.propagation) && !includesSupports) {
            return new Refusal("TX_SUPPORTS_POLICY",
                    "Spring SUPPORTS may create a resource-synchronization scope; " +
                    "activate the explicit opt-in recipe only after accepting that difference");
        }
        if (!isDirectPropagation(fact.propagation) &&
                !(includesSupports && "SUPPORTS".equals(fact.propagation))) {
            return new Refusal("TX_UNRESOLVED_PROPAGATION",
                    "the Spring propagation value is not a direct supported enum constant");
        }
        return null;
    }

    private static ParsedAnnotation parse(J.Annotation annotation) {
        ParsedAnnotation parsed = new ParsedAnnotation();
        if (annotation.getArguments() == null) {
            return parsed;
        }
        for (Expression argument : annotation.getArguments()) {
            if (!(argument instanceof J.Assignment)) {
                if (!isEmptyString(argument)) {
                    parsed.refuse("TX_MANAGER_SELECTION",
                            "Spring transaction-manager selection requires an explicit target routing policy");
                }
                continue;
            }
            J.Assignment assignment = (J.Assignment) argument;
            String attribute = assignment.getVariable().printTrimmed();
            Expression value = assignment.getAssignment();
            if ("propagation".equals(attribute)) {
                parsed.propagation = enumConstant(value,
                        "org.springframework.transaction.annotation.Propagation");
                if (parsed.propagation == null) {
                    parsed.propagation = "UNRESOLVED";
                }
            } else if ("isolation".equals(attribute)) {
                if (!"DEFAULT".equals(enumConstant(value,
                        "org.springframework.transaction.annotation.Isolation"))) {
                    parsed.refuse("TX_NON_DEFAULT_ISOLATION",
                            "non-default Spring isolation has no Jakarta Transactions annotation member");
                }
            } else if ("timeout".equals(attribute)) {
                if (!"-1".equals(value.printTrimmed())) {
                    parsed.refuse("TX_TIMEOUT_POLICY",
                            "non-default Spring timeout has no Jakarta Transactions annotation member");
                }
            } else if ("timeoutString".equals(attribute)) {
                if (!isEmptyString(value)) {
                    parsed.refuse("TX_TIMEOUT_POLICY",
                            "non-default Spring timeout has no Jakarta Transactions annotation member");
                }
            } else if ("readOnly".equals(attribute)) {
                if (!isBooleanLiteral(value, false)) {
                    parsed.refuse("TX_READ_ONLY_POLICY",
                            "Spring read-only behavior requires an explicit target persistence policy");
                }
            } else if ("label".equals(attribute)) {
                if (!isEmptyArray(value)) {
                    parsed.refuse("TX_LABEL_POLICY",
                            "Spring transaction labels require an explicit target provider policy");
                }
            } else if ("value".equals(attribute) || "transactionManager".equals(attribute)) {
                if (!isEmptyString(value)) {
                    parsed.refuse("TX_MANAGER_SELECTION",
                            "Spring transaction-manager selection requires an explicit target routing policy");
                }
            } else if ("rollbackFor".equals(attribute) || "noRollbackFor".equals(attribute)) {
                List<ClassRule> rules = classRules(value);
                if (rules == null) {
                    parsed.refuse("TX_UNATTRIBUTED_ROLLBACK_TYPE",
                            "rollback type rules require attributed class literals");
                } else if ("rollbackFor".equals(attribute)) {
                    parsed.rollbackFor.addAll(rules);
                } else {
                    parsed.noRollbackFor.addAll(rules);
                }
            } else if ("rollbackForClassName".equals(attribute) ||
                    "noRollbackForClassName".equals(attribute)) {
                if (!isEmptyArray(value)) {
                    parsed.refuse("TX_PATTERN_ROLLBACK_RULE",
                            "Spring string-pattern rollback rules have no Jakarta Transactions equivalent");
                }
            } else {
                parsed.refuse("TX_UNRESOLVED_ATTRIBUTE",
                        "an unresolved Spring transaction attribute cannot be discarded safely");
            }
        }
        if (parsed.refusalCode == null && !preservesRollbackPrecedence(parsed)) {
            parsed.refuse("TX_ROLLBACK_PRECEDENCE",
                    "Jakarta dontRollbackOn precedence would change this Spring rollback rule hierarchy");
        }
        return parsed;
    }

    private static List<ClassRule> classRules(Expression value) {
        if (isEmptyArray(value)) {
            return new ArrayList<ClassRule>();
        }
        List<Expression> expressions = new ArrayList<Expression>();
        if (value instanceof J.NewArray) {
            List<Expression> initializer = ((J.NewArray) value).getInitializer();
            if (initializer != null) {
                expressions.addAll(initializer);
            }
        } else {
            expressions.add(value);
        }
        List<ClassRule> rules = new ArrayList<ClassRule>();
        for (Expression expression : expressions) {
            if (!(expression instanceof J.FieldAccess) ||
                    !"class".equals(((J.FieldAccess) expression).getName().getSimpleName())) {
                return null;
            }
            Expression target = ((J.FieldAccess) expression).getTarget();
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(target.getType());
            if (type == null) {
                return null;
            }
            rules.add(new ClassRule(type.getFullyQualifiedName(), type));
        }
        return rules;
    }

    private static boolean preservesRollbackPrecedence(ParsedAnnotation parsed) {
        for (ClassRule rollback : parsed.rollbackFor) {
            for (ClassRule noRollback : parsed.noRollbackFor) {
                if (rollback.fullyQualifiedName.equals(noRollback.fullyQualifiedName) ||
                        TypeUtils.isAssignableTo(noRollback.fullyQualifiedName, rollback.type)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Refusal globalRollbackRefusal(AnnotationFact fact, ModulePolicy policy) {
        if (!Boolean.TRUE.equals(policy.allExceptionsRollback)) {
            return null;
        }
        for (ClassRule noRollback : fact.noRollbackFor) {
            if ("java.lang.Exception".equals(noRollback.fullyQualifiedName) ||
                    "java.lang.Throwable".equals(noRollback.fullyQualifiedName)) {
                return new Refusal("TX_ROLLBACK_PRECEDENCE",
                        "Jakarta dontRollbackOn precedence would change this Spring rollback rule hierarchy");
            }
        }
        return null;
    }

    private static String enumConstant(Expression value, String expectedType) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(value.getType());
        if (type == null || !expectedType.equals(type.getFullyQualifiedName())) {
            return null;
        }
        String printed = value.printTrimmed();
        int separator = printed.lastIndexOf('.');
        return separator < 0 ? printed : printed.substring(separator + 1);
    }

    private static boolean isEmptyString(Expression value) {
        return value instanceof J.Literal && "".equals(((J.Literal) value).getValue());
    }

    private static boolean isBooleanLiteral(Expression value, boolean expected) {
        return value instanceof J.Literal &&
                Boolean.valueOf(expected).equals(((J.Literal) value).getValue());
    }

    private static boolean isEmptyArray(Expression value) {
        if (value instanceof J.NewArray) {
            List<Expression> initializer = ((J.NewArray) value).getInitializer();
            return initializer == null || initializer.isEmpty() ||
                    (initializer.size() == 1 && initializer.get(0) instanceof J.Empty);
        }
        return false;
    }

    private static String sourceKind(String sourcePath) {
        String normalized = '/' + sourcePath + '/';
        if (normalized.contains("/src/main/java/")) {
            return "JAVA_MAIN";
        }
        int sourceRoot = normalized.indexOf("/src/");
        if (sourceRoot >= 0) {
            int sourceSetStart = sourceRoot + "/src/".length();
            int sourceSetEnd = normalized.indexOf('/', sourceSetStart);
            if (sourceSetEnd > sourceSetStart) {
                String sourceSet = normalized.substring(sourceSetStart, sourceSetEnd)
                        .toLowerCase(java.util.Locale.ROOT);
                if (sourceSet.contains("test") || "it".equals(sourceSet) ||
                        sourceSet.contains("integration") || sourceSet.contains("acceptance")) {
                    return "JAVA_TEST";
                }
            }
        }
        return "JAVA_SOURCE";
    }

    private static String moduleKey(Cursor cursor) {
        J.CompilationUnit compilationUnit = cursor.firstEnclosing(J.CompilationUnit.class);
        return compilationUnit == null ? "" : moduleKey(compilationUnit.getSourcePath()
                .normalize().toString().replace(File.separatorChar, '/'));
    }

    private static String moduleKey(String sourcePath) {
        int sourceRoot = sourcePath.indexOf("/src/");
        return sourceRoot < 0 ? "" : sourcePath.substring(0, sourceRoot);
    }

    private static boolean isCdiBean(J.ClassDeclaration owner) {
        for (J.Annotation annotation : owner.getLeadingAnnotations()) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
            if (type == null) {
                continue;
            }
            String name = type.getFullyQualifiedName();
            if ("jakarta.enterprise.context.ApplicationScoped".equals(name) ||
                    "jakarta.enterprise.context.RequestScoped".equals(name) ||
                    "jakarta.enterprise.context.SessionScoped".equals(name) ||
                    "jakarta.enterprise.context.ConversationScoped".equals(name) ||
                    "jakarta.enterprise.context.Dependent".equals(name) ||
                    "jakarta.inject.Singleton".equals(name)) {
                return true;
            }
        }
        return hasProvenAnnotationOrMetaAnnotation(owner.getLeadingAnnotations(),
                "jakarta.enterprise.inject.Stereotype") ||
                hasProvenAnnotationOrMetaAnnotation(owner.getLeadingAnnotations(),
                        "jakarta.enterprise.context.NormalScope");
    }

    private static boolean isInterceptable(J.ClassDeclaration owner,
                                           J.MethodDeclaration method) {
        if (owner.getKind() != J.ClassDeclaration.Kind.Type.Class ||
                owner.hasModifier(J.Modifier.Type.Final)) {
            return false;
        }
        return method == null || (!method.isConstructor() && !method.isAbstract() &&
                method.getBody() != null && !method.hasModifier(J.Modifier.Type.Private) &&
                !method.hasModifier(J.Modifier.Type.Static) &&
                !method.hasModifier(J.Modifier.Type.Final) &&
                !method.hasModifier(J.Modifier.Type.Native));
    }

    private static boolean isLifecycle(J.MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        for (J.Annotation annotation : method.getLeadingAnnotations()) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
            String name = type == null ? annotation.getAnnotationType().printTrimmed() :
                    type.getFullyQualifiedName();
            if ("jakarta.annotation.PostConstruct".equals(name) ||
                    "jakarta.annotation.PreDestroy".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesUserTransaction(J tree) {
        final AtomicBoolean found = new AtomicBoolean();
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean value) {
                J.Identifier visited = super.visitIdentifier(identifier, value);
                if (TypeUtils.isOfClassType(visited.getType(),
                        "jakarta.transaction.UserTransaction")) {
                    value.set(true);
                }
                return visited;
            }
        }.visit(tree, found);
        return found.get();
    }

    private static boolean isReactiveReturn(J.MethodDeclaration method) {
        if (method == null || method.getReturnTypeExpression() == null) {
            return false;
        }
        return TypeUtils.isAssignableTo("org.reactivestreams.Publisher",
                method.getReturnTypeExpression().getType());
    }

    private static boolean hasJakartaTransactional(List<J.Annotation> annotations) {
        return hasAnnotation(annotations, "jakarta.transaction.Transactional");
    }

    private static boolean hasAnnotation(List<J.Annotation> annotations, String expectedType) {
        for (J.Annotation annotation : annotations) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
            if (type != null && expectedType.equals(
                    type.getFullyQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotationOrMetaAnnotation(List<J.Annotation> annotations,
                                                          String expectedType) {
        for (J.Annotation annotation : annotations) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
            if (type == null) {
                continue;
            }
            MetaAnnotationMatch match = metaAnnotationMatch(type, expectedType,
                    new HashSet<String>(), 0);
            if (match != MetaAnnotationMatch.NO_MATCH) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProvenAnnotationOrMetaAnnotation(List<J.Annotation> annotations,
                                                                String expectedType) {
        for (J.Annotation annotation : annotations) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
            if (type != null && metaAnnotationMatch(type, expectedType,
                    new HashSet<String>(), 0) == MetaAnnotationMatch.MATCH) {
                return true;
            }
        }
        return false;
    }

    private static MetaAnnotationMatch metaAnnotationMatch(JavaType.FullyQualified annotationType,
                                                            String expectedType,
                                                            Set<String> visiting,
                                                            int depth) {
        String typeName = annotationType.getFullyQualifiedName();
        if (expectedType.equals(typeName)) {
            return MetaAnnotationMatch.MATCH;
        }
        if (depth >= MAX_META_ANNOTATION_DEPTH) {
            return MetaAnnotationMatch.INDETERMINATE;
        }
        if (!visiting.add(typeName)) {
            return MetaAnnotationMatch.NO_MATCH;
        }
        MetaAnnotationMatch result = MetaAnnotationMatch.NO_MATCH;
        try {
            for (JavaType.FullyQualified metaAnnotation : annotationType.getAnnotations()) {
                MetaAnnotationMatch nested = metaAnnotationMatch(metaAnnotation, expectedType,
                        visiting, depth + 1);
                if (nested == MetaAnnotationMatch.MATCH) {
                    return nested;
                }
                if (nested == MetaAnnotationMatch.INDETERMINATE) {
                    result = nested;
                }
            }
            return result;
        } finally {
            visiting.remove(typeName);
        }
    }

    private static boolean hasSpringStereotype(List<J.Annotation> annotations) {
        return hasAnnotationOrMetaAnnotation(annotations,
                "org.springframework.stereotype.Component");
    }

    private enum MetaAnnotationMatch {
        MATCH,
        NO_MATCH,
        INDETERMINATE
    }

    private static final class AnnotationFact {
        private final UUID id;
        private final UUID ownerId;
        private final String sourcePath;
        private final String sourceKind;
        private final String propagation;
        private final boolean cdiBean;
        private final String refusalCode;
        private final String refusalReason;
        private final List<ClassRule> rollbackFor;
        private final List<ClassRule> noRollbackFor;
        private final boolean interceptable;
        private final boolean lifecycle;
        private final boolean usesUserTransaction;
        private final boolean reactiveReturn;
        private final boolean jakartaCollision;
        private final String moduleKey;
        private final boolean composed;
        private final boolean missingAttribution;
        private final boolean classLevel;
        private final boolean managerQualifiedBean;

        private AnnotationFact(UUID id, UUID ownerId, String sourcePath, String sourceKind,
                               String propagation, boolean cdiBean, String refusalCode,
                               String refusalReason, List<ClassRule> rollbackFor,
                               List<ClassRule> noRollbackFor, boolean interceptable,
                               boolean lifecycle, boolean usesUserTransaction,
                               boolean reactiveReturn,
                               boolean jakartaCollision, String moduleKey, boolean composed,
                               boolean missingAttribution, boolean classLevel,
                               boolean managerQualifiedBean) {
            this.id = id;
            this.ownerId = ownerId;
            this.sourcePath = sourcePath;
            this.sourceKind = sourceKind;
            this.propagation = propagation;
            this.cdiBean = cdiBean;
            this.refusalCode = refusalCode;
            this.refusalReason = refusalReason;
            this.rollbackFor = rollbackFor;
            this.noRollbackFor = noRollbackFor;
            this.interceptable = interceptable;
            this.lifecycle = lifecycle;
            this.usesUserTransaction = usesUserTransaction;
            this.reactiveReturn = reactiveReturn;
            this.jakartaCollision = jakartaCollision;
            this.moduleKey = moduleKey;
            this.composed = composed;
            this.missingAttribution = missingAttribution;
            this.classLevel = classLevel;
            this.managerQualifiedBean = managerQualifiedBean;
        }
    }

    private static final class ParsedAnnotation {
        private String propagation = "REQUIRED";
        private String refusalCode;
        private String refusalReason;
        private final List<ClassRule> rollbackFor = new ArrayList<ClassRule>();
        private final List<ClassRule> noRollbackFor = new ArrayList<ClassRule>();

        private void refuse(String code, String reason) {
            if (refusalCode == null) {
                refusalCode = code;
                refusalReason = reason;
            }
        }
    }

    private static final class ClassRule {
        private final String fullyQualifiedName;
        private final JavaType.FullyQualified type;

        private ClassRule(String fullyQualifiedName, JavaType.FullyQualified type) {
            this.fullyQualifiedName = fullyQualifiedName;
            this.type = type;
        }
    }

    private static final class Refusal {
        private final String code;
        private final String reason;

        private Refusal(String code, String reason) {
            this.code = code;
            this.reason = reason;
        }
    }

    private static final class ModulePolicy {
        private Boolean allExceptionsRollback;
        private String refusalCode;
        private String refusalReason;
        private int managerCount;
        private boolean springTransactionExceptionCoupling;

        private void refuse(String code, String reason) {
            if (refusalCode == null) {
                refusalCode = code;
                refusalReason = reason;
            }
        }

        private Refusal refusal() {
            if (refusalCode != null) {
                return new Refusal(refusalCode, refusalReason);
            }
            return managerCount > 1 ? new Refusal("TX_MULTIPLE_TRANSACTION_MANAGERS",
                    "multiple Spring transaction managers require an explicit target routing policy") :
                    null;
        }

        private Refusal propagationRefusal(String propagation) {
            return springTransactionExceptionCoupling &&
                    ("MANDATORY".equals(propagation) || "NEVER".equals(propagation)) ?
                    new Refusal("TX_SPRING_TRANSACTION_EXCEPTION_COUPLING",
                            "source code depends on Spring transaction-state exceptions for " +
                            "this propagation mode") : null;
        }
    }

    private static final class FactGroup {
        private final List<AnnotationFact> facts = new ArrayList<AnnotationFact>();
        private Refusal classLevelRefusal;
    }

    private static final class ClassInfo {
        private final UUID id;
        private final String typeName;
        private final String moduleKey;
        private final String superType;
        private final List<String> interfaces;
        private final Refusal classLevelRefusal;
        private final Refusal methodLevelRefusal;
        private final boolean unresolvedHierarchy;

        private ClassInfo(UUID id, String typeName, String moduleKey, String superType,
                          List<String> interfaces, Refusal classLevelRefusal,
                          Refusal methodLevelRefusal,
                          boolean unresolvedHierarchy) {
            this.id = id;
            this.typeName = typeName;
            this.moduleKey = moduleKey;
            this.superType = superType;
            this.interfaces = interfaces;
            this.classLevelRefusal = classLevelRefusal;
            this.methodLevelRefusal = methodLevelRefusal;
            this.unresolvedHierarchy = unresolvedHierarchy;
        }
    }
}
