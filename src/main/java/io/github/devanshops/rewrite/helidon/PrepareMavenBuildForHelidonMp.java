package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.AddManagedDependency;
import org.openrewrite.maven.AddManagedDependencyVisitor;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.xml.tree.Xml;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Adds the minimum Helidon MP dependency foundation alongside an existing Spring build.
 *
 * <p>This intentionally does not remove Spring dependencies, replace the project's parent,
 * or add Helidon packaging plugins. Those changes require module-specific migration decisions.</p>
 */
public final class PrepareMavenBuildForHelidonMp extends Recipe {
    static final String HELIDON_VERSION = "4.5.3";

    @Override
    public String getDisplayName() {
        return "Prepare a Maven build for Helidon MP";
    }

    @Override
    public String getDescription() {
        return "Imports Helidon dependency management and adds the MicroProfile core bundle to Spring Boot Maven projects without removing Spring dependencies or changing the parent POM.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    public List<Recipe> getRecipeList() {
        return Arrays.<Recipe>asList(
                new AddManagedDependency(
                        "io.helidon",
                        "helidon-dependencies",
                        HELIDON_VERSION,
                        "import",
                        "pom",
                        null,
                        null,
                        true,
                        "org.springframework.boot:*",
                        true,
                        null),
                new AddHelidonManagementToDirectSpringBootPoms(),
                new AddDependency(
                        "io.helidon.microprofile.bundles",
                        "helidon-microprofile-core",
                        HELIDON_VERSION,
                        null,
                        "compile",
                        true,
                        "org.springframework.boot.SpringApplication",
                        null,
                        null,
                        null,
                        null,
                        false));
    }

    /**
     * A Maven aggregator is not necessarily a parent. Modules can inherit the Spring Boot parent
     * directly, so a root-only dependency-management import would not reach them.
     */
    private static final class AddHelidonManagementToDirectSpringBootPoms extends Recipe {
        @Override
        public String getDisplayName() {
            return "Import Helidon dependency management into direct Spring Boot POMs";
        }

        @Override
        public String getDescription() {
            return "Imports Helidon dependency management into modules that directly use a Spring Boot parent, dependency, or build plugin.";
        }

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new MavenIsoVisitor<ExecutionContext>() {
                @Override
                public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                    Xml.Document d = super.visitDocument(document, ctx);
                    if (!isDirectSpringBootPom(d.getRoot())) {
                        return d;
                    }
                    Xml.Document migrated = (Xml.Document) new AddManagedDependencyVisitor(
                            "io.helidon",
                            "helidon-dependencies",
                            HELIDON_VERSION,
                            "import",
                            "pom",
                            null,
                            null).visitNonNull(d, ctx);
                    if (migrated != d) {
                        maybeUpdateModel();
                    }
                    return migrated;
                }
            };
        }

        private static boolean isDirectSpringBootPom(Xml.Tag project) {
            Xml.Tag parent = project.getChild("parent").orElse(null);
            if (isSpringBootCoordinate(parent)) {
                return true;
            }

            Xml.Tag dependencies = project.getChild("dependencies").orElse(null);
            if (containsSpringBootCoordinate(dependencies, "dependency")) {
                return true;
            }

            Xml.Tag build = project.getChild("build").orElse(null);
            Xml.Tag plugins = build == null ? null : build.getChild("plugins").orElse(null);
            return containsSpringBootCoordinate(plugins, "plugin");
        }

        private static boolean containsSpringBootCoordinate(Xml.Tag container, String childName) {
            if (container == null) {
                return false;
            }
            for (Xml.Tag child : container.getChildren(childName)) {
                if (isSpringBootCoordinate(child)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isSpringBootCoordinate(Xml.Tag coordinate) {
            return coordinate != null &&
                   "org.springframework.boot".equals(coordinate.getChildValue("groupId").orElse(null));
        }
    }
}
