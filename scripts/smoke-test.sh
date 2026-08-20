#!/usr/bin/env bash
set -euo pipefail

smoke_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
smoke_project_dir=$(CDPATH= cd -- "$smoke_script_dir/.." && pwd)
smoke_fixture_dir="$smoke_project_dir/src/it/smoke"
smoke_source="$smoke_fixture_dir/src/main/java/com/example/GreetingService.java"
smoke_pom="$smoke_fixture_dir/pom.xml"
smoke_patch="$smoke_fixture_dir/target/rewrite/rewrite.patch"
smoke_datatables_dir="$smoke_fixture_dir/target/rewrite/datatables"
smoke_recipe_version=$("$smoke_project_dir/mvnw" --quiet -f "$smoke_project_dir/pom.xml" \
  help:evaluate -Dexpression=project.version -DforceStdout)

if [[ -z "$smoke_recipe_version" ]]; then
  echo "Could not determine the recipe project version." >&2
  exit 1
fi

smoke_source_checksum_before=$(cksum < "$smoke_source")
smoke_pom_checksum_before=$(cksum < "$smoke_pom")

"$smoke_project_dir/mvnw" --batch-mode --no-transfer-progress -f "$smoke_project_dir/pom.xml" \
  -DskipTests install

(
  cd "$smoke_fixture_dir"
  "$smoke_project_dir/mvnw" --batch-mode --no-transfer-progress \
    clean \
    org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
    -Drewrite.recipeArtifactCoordinates="io.github.devansh-ops:rewrite-spring-to-helidon:$smoke_recipe_version" \
    -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp \
    -Drewrite.exportDatatables=true
)

if [[ ! -s "$smoke_patch" ]]; then
  echo "Expected the external fixture to produce $smoke_patch" >&2
  exit 1
fi

if [[ "$(cksum < "$smoke_source")" != "$smoke_source_checksum_before" ]]; then
  echo "The assessment-only dry run changed the fixture source file." >&2
  exit 1
fi

if [[ "$(cksum < "$smoke_pom")" != "$smoke_pom_checksum_before" ]]; then
  echo "The assessment-only dry run changed the fixture POM." >&2
  exit 1
fi

grep -Fq 'PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject' "$smoke_patch"
grep -Fq 'MANUAL: Spring Java API [SPRING_JAVA_API]' "$smoke_patch"
grep -Fq 'PARTIAL: Spring Maven dependency [SPRING_MAVEN_DEPENDENCY]' "$smoke_patch"
grep -Fq '+/*~~(PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject)~~>*//*~~(MANUAL: Spring Java API [SPRING_JAVA_API] -> Inspect for a Jakarta, MicroProfile, or Helidon equivalent)~~>*/import org.springframework.stereotype.Service;' "$smoke_patch"
grep -Fq '+@/*~~(PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject)~~>*/Service' "$smoke_patch"

if grep -Eq '^\+[[:space:]]*import (jakarta\.|io\.helidon\.)|^\+[[:space:]]*@(ApplicationScoped|Named|Inject|Produces|Singleton|Path|GET|POST|PUT|DELETE|PATCH|Transactional|ConfigProperty)([^[:alnum:]_]|$)' "$smoke_patch"; then
  echo "The canonical assessment proposed a runtime or source migration." >&2
  exit 1
fi

smoke_usage_table=$(find "$smoke_datatables_dir" -type f \
  -name '*SpringUsageTable.csv' -print -quit 2>/dev/null || true)
smoke_project_table=$(find "$smoke_datatables_dir" -type f \
  -name '*MigrationAssessmentTable.csv' -print -quit 2>/dev/null || true)

if [[ -z "$smoke_usage_table" ]]; then
  echo "Expected an exported SpringUsageTable under $smoke_datatables_dir" >&2
  exit 1
fi

if [[ -z "$smoke_project_table" ]]; then
  echo "Expected an exported MigrationAssessmentTable under $smoke_datatables_dir" >&2
  exit 1
fi

grep -Fq 'PARTIAL' "$smoke_usage_table"
grep -Fq 'Jakarta CDI and jakarta.inject' "$smoke_usage_table"
grep -Fq 'SPRING_MAVEN_DEPENDENCY' "$smoke_project_table"
grep -Fq 'SPRING_JAVA_API' "$smoke_project_table"

echo "Distribution smoke test passed: the Maven plugin discovered the installed artifact, ran the assessment-only canonical recipe, preserved Spring source/build semantics, and exported both assessment tables."
