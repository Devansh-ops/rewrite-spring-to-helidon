#!/usr/bin/env bash
set -euo pipefail

smoke_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
smoke_project_dir=$(CDPATH= cd -- "$smoke_script_dir/.." && pwd)
smoke_fixture_dir="$smoke_project_dir/src/it/smoke"
smoke_patch="$smoke_fixture_dir/target/rewrite/rewrite.patch"
smoke_recipe_version=$("$smoke_project_dir/mvnw" --quiet -f "$smoke_project_dir/pom.xml" \
  help:evaluate -Dexpression=project.version -DforceStdout)

if [[ -z "$smoke_recipe_version" ]]; then
  echo "Could not determine the recipe project version." >&2
  exit 1
fi

"$smoke_project_dir/mvnw" --batch-mode --no-transfer-progress -f "$smoke_project_dir/pom.xml" \
  -DskipTests install

"$smoke_project_dir/mvnw" --batch-mode --no-transfer-progress -f "$smoke_fixture_dir/pom.xml" \
  clean \
  org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates="io.github.devansh-ops:rewrite-spring-to-helidon:$smoke_recipe_version" \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp

if [[ ! -s "$smoke_patch" ]]; then
  echo "Expected the external fixture to produce $smoke_patch" >&2
  exit 1
fi

grep -Fq 'jakarta.enterprise.context.ApplicationScoped' "$smoke_patch"
grep -Fq '@Named("greetingService")' "$smoke_patch"

echo "Distribution smoke test passed: the Maven plugin discovered and ran the installed recipe artifact."
