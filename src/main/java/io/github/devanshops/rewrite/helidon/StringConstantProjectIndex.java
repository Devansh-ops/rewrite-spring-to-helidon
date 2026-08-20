package io.github.devanshops.rewrite.helidon;

import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Module-scoped, thread-safe index of source-declared {@code static final String} constants. */
final class StringConstantProjectIndex {
    private StringConstantProjectIndex() {
    }

    static State newAccumulator() {
        return new State();
    }

    static void scanSource(SourceFile sourceFile, final State state) {
        if (!(sourceFile instanceof J.CompilationUnit)) {
            return;
        }
        final J.CompilationUnit compilationUnit = (J.CompilationUnit) sourceFile;
        final Path sourceScope = sourceScope(sourceFile.getSourcePath());
        final String packageName = compilationUnit.getPackageDeclaration() == null ? "" :
                compilationUnit.getPackageDeclaration().getExpression().printTrimmed();

        new JavaIsoVisitor<State>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations variables,
                                                                      State constants) {
                J.VariableDeclarations declarations = super.visitVariableDeclarations(variables, constants);
                if (!hasModifier(declarations, J.Modifier.Type.Static) ||
                        !hasModifier(declarations, J.Modifier.Type.Final) ||
                        !TypeUtils.isOfClassType(declarations.getType(), "java.lang.String")) {
                    return declarations;
                }
                J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
                String ownerName = ownerName(owner, packageName);
                if (ownerName == null) {
                    return declarations;
                }
                for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
                    if (variable.getInitializer() != null) {
                        constants.initializers.put(new ConstantKey(sourceScope, ownerName,
                                variable.getSimpleName()), new ConstantDefinition(
                                variable.getInitializer(), compilationUnit));
                    }
                }
                return declarations;
            }
        }.visit(compilationUnit, state);
    }

    static String resolve(Expression expression,
                          State state,
                          Path sourcePath,
                          J.CompilationUnit compilationUnit) {
        return resolve(expression, state, sourceScope(sourcePath),
                compilationUnit, new HashSet<ConstantKey>());
    }

    private static String resolve(Expression expression,
                                  State state,
                                  Path module,
                                  J.CompilationUnit compilationUnit,
                                  Set<ConstantKey> resolving) {
        if (expression instanceof J.Literal && ((J.Literal) expression).getValue() instanceof String) {
            return (String) ((J.Literal) expression).getValue();
        }
        if (expression instanceof J.Parentheses) {
            J tree = ((J.Parentheses<?>) expression).getTree();
            return tree instanceof Expression ?
                    resolve((Expression) tree, state, module, compilationUnit, resolving) : null;
        }
        if (expression instanceof J.Binary &&
                ((J.Binary) expression).getOperator() == J.Binary.Type.Addition) {
            String left = resolve(((J.Binary) expression).getLeft(), state, module,
                    compilationUnit, resolving);
            String right = resolve(((J.Binary) expression).getRight(), state, module,
                    compilationUnit, resolving);
            return left == null || right == null ? null : left + right;
        }

        ConstantKey key = expressionKey(expression, module, compilationUnit);
        if (key == null || !resolving.add(key)) {
            return null;
        }
        ConstantDefinition definition = state.initializers.get(key);
        String resolved = definition == null ? null :
                resolve(definition.initializer, state, module,
                        definition.compilationUnit, resolving);
        resolving.remove(key);
        return resolved;
    }

    private static ConstantKey expressionKey(Expression expression,
                                             Path module,
                                             J.CompilationUnit compilationUnit) {
        JavaType.Variable fieldType = null;
        if (expression instanceof J.Identifier) {
            fieldType = ((J.Identifier) expression).getFieldType();
        } else if (expression instanceof J.FieldAccess) {
            fieldType = ((J.FieldAccess) expression).getName().getFieldType();
        }
        if (fieldType != null) {
            JavaType.FullyQualified owner = TypeUtils.asFullyQualified(fieldType.getOwner());
            if (owner != null) {
                return new ConstantKey(module, owner.getFullyQualifiedName(), fieldType.getName());
            }
        }
        if (!(expression instanceof J.FieldAccess)) {
            return null;
        }
        if (compilationUnit == null) {
            return null;
        }
        J.FieldAccess fieldAccess = (J.FieldAccess) expression;
        String owner = resolveOwnerName(fieldAccess.getTarget().printTrimmed(), compilationUnit);
        return owner == null ? null : new ConstantKey(module, owner,
                fieldAccess.getName().getSimpleName());
    }

    private static String resolveOwnerName(String printedOwner, J.CompilationUnit compilationUnit) {
        if (printedOwner.indexOf('.') >= 0) {
            return printedOwner;
        }
        for (J.Import anImport : compilationUnit.getImports()) {
            if (!anImport.isStatic() && anImport.getTypeName().endsWith('.' + printedOwner)) {
                return anImport.getTypeName();
            }
        }
        String packageName = compilationUnit.getPackageDeclaration() == null ? "" :
                compilationUnit.getPackageDeclaration().getExpression().printTrimmed();
        return packageName.isEmpty() ? printedOwner : packageName + '.' + printedOwner;
    }

    private static String ownerName(J.ClassDeclaration owner, String packageName) {
        if (owner == null) {
            return null;
        }
        List<String> names = new ArrayList<String>();
        J.ClassDeclaration current = owner;
        names.add(current.getSimpleName());
        // Constants used by the migration are overwhelmingly top-level. Nested ownership is still
        // stable when type attribution is present; this syntax fallback intentionally stays simple.
        JavaType.FullyQualified attributed = TypeUtils.asFullyQualified(owner.getType());
        if (attributed != null) {
            return attributed.getFullyQualifiedName();
        }
        return packageName.isEmpty() ? names.get(0) : packageName + '.' + names.get(0);
    }

    private static boolean hasModifier(J.VariableDeclarations variables, J.Modifier.Type type) {
        for (J.Modifier modifier : variables.getModifiers()) {
            if (modifier.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private static Path sourceScope(Path sourcePath) {
        Path normalized = sourcePath.normalize();
        for (int i = 0; i + 1 < normalized.getNameCount(); i++) {
            if ("src".equals(normalized.getName(i).toString())) {
                return normalized.subpath(0, i + 2);
            }
        }
        return SpringSecurityProjectGate.moduleRoot(sourcePath);
    }

    static final class State {
        private final ConcurrentMap<ConstantKey, ConstantDefinition> initializers =
                new ConcurrentHashMap<ConstantKey, ConstantDefinition>();
    }

    private static final class ConstantDefinition {
        private final Expression initializer;
        private final J.CompilationUnit compilationUnit;

        private ConstantDefinition(Expression initializer, J.CompilationUnit compilationUnit) {
            this.initializer = initializer;
            this.compilationUnit = compilationUnit;
        }
    }

    private static final class ConstantKey {
        private final Path module;
        private final String owner;
        private final String name;

        private ConstantKey(Path module, String owner, String name) {
            this.module = module;
            this.owner = owner;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ConstantKey)) {
                return false;
            }
            ConstantKey that = (ConstantKey) other;
            return module.equals(that.module) && owner.equals(that.owner) && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            int result = module.hashCode();
            result = 31 * result + owner.hashCode();
            result = 31 * result + name.hashCode();
            return result;
        }
    }
}
