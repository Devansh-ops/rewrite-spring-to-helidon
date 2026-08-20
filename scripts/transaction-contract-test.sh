#!/usr/bin/env bash
set -euo pipefail

contract_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
contract_project_dir=$(CDPATH= cd -- "$contract_script_dir/.." && pwd)
contract_fixture_dir="$contract_project_dir/src/it/transaction-contract"
contract_supports_fixture_dir="$contract_fixture_dir/supports-fixture"
contract_target_dir="$contract_fixture_dir/target"
contract_expected="$contract_fixture_dir/expected-required.txt"
contract_supports_spring_expected="$contract_fixture_dir/expected-supports-spring.txt"
contract_supports_helidon_expected="$contract_fixture_dir/expected-supports-helidon.txt"
contract_spring_output="$contract_target_dir/spring-contract.txt"
contract_helidon_output="$contract_target_dir/helidon-contract.txt"
contract_supports_spring_output="$contract_target_dir/supports-spring-contract.txt"
contract_supports_helidon_output="$contract_target_dir/supports-helidon-contract.txt"
contract_supports_spring_common="$contract_target_dir/supports-spring-common.txt"
contract_supports_helidon_common="$contract_target_dir/supports-helidon-common.txt"
contract_dependency_tree="$contract_target_dir/helidon-dependencies.txt"
contract_supports_dependency_tree="$contract_target_dir/supports-helidon-dependencies.txt"
contract_recipe_version=$("$contract_project_dir/mvnw" --quiet \
  -f "$contract_project_dir/pom.xml" help:evaluate \
  -Dexpression=project.version -DforceStdout)

if [[ -z "$contract_recipe_version" ]]; then
  echo "Could not determine the recipe project version." >&2
  exit 1
fi

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_fixture_dir/pom.xml" clean

contract_work_root=$(mktemp -d "${TMPDIR:-/tmp}/rewrite-spring-to-helidon-transaction-contract.XXXXXX")
contract_rewrite_subject="$contract_work_root/rewrite-subject"
contract_supports_rewrite_subject="$contract_work_root/supports-rewrite-subject"
mkdir -p "$contract_rewrite_subject" "$contract_supports_rewrite_subject"
trap 'rm -rf -- "$contract_work_root"' EXIT
cp -R "$contract_fixture_dir/fixture/." "$contract_rewrite_subject/"
cp -R "$contract_supports_fixture_dir/." "$contract_supports_rewrite_subject/"

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_project_dir/pom.xml" -DskipTests install

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_fixture_dir/pom.xml" -pl spring-runtime \
  -Dcontract.fixtureSource="$contract_fixture_dir/fixture/src/main/java" \
  -Dcontract.output="$contract_spring_output" \
  compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=io.github.devanshops.rewrite.helidon.it.transaction.spring.SpringRuntimeMain

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_rewrite_subject/pom.xml" \
  org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates="io.github.devansh-ops:rewrite-spring-to-helidon:$contract_recipe_version" \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.MigrateSpringTransactionalToJakarta

if grep -R -Fq 'org.springframework' "$contract_rewrite_subject/src/main/java"; then
  echo "The rewritten Helidon fixture still contains Spring source references." >&2
  exit 1
fi

if ! grep -R -Fq 'jakarta.transaction.Transactional' "$contract_rewrite_subject/src/main/java"; then
  echo "The transaction recipe did not generate Jakarta Transactions annotations." >&2
  exit 1
fi

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_fixture_dir/pom.xml" -pl helidon-runtime \
  -Dcontract.generatedSource="$contract_rewrite_subject/src/main/java" \
  compile org.codehaus.mojo:exec-maven-plugin:3.5.0:exec \
  -Dexec.executable=java \
  -Dexec.classpathScope=runtime \
  -Dexec.workingdir="$contract_target_dir" \
  -Dexec.args="-Dcontract.output=$contract_helidon_output -classpath %classpath io.github.devanshops.rewrite.helidon.it.transaction.helidon.HelidonRuntimeMain"

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_fixture_dir/pom.xml" -pl helidon-runtime \
  -DoutputFile="$contract_dependency_tree" \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree

if grep -Fq 'org.springframework' "$contract_dependency_tree"; then
  echo "The compiled Helidon runtime has a Spring dependency." >&2
  exit 1
fi

if [[ ! -s "$contract_spring_output" || ! -s "$contract_helidon_output" ]]; then
  echo "Both runtimes must produce normalized transaction-contract outcomes." >&2
  exit 1
fi

cmp "$contract_expected" "$contract_spring_output"
cmp "$contract_expected" "$contract_helidon_output"
cmp "$contract_spring_output" "$contract_helidon_output"

echo "Transaction runtime contract passed for Spring Boot 4 and rewritten Helidon MP 4.5.3."

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_fixture_dir/pom.xml" -pl supports-spring-runtime \
  -Dcontract.supportsFixtureSource="$contract_supports_fixture_dir/src/main/java" \
  -Dcontract.output="$contract_supports_spring_output" \
  compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=io.github.devanshops.rewrite.helidon.it.transaction.supports.spring.SupportsSpringMain

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_supports_rewrite_subject/pom.xml" \
  org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates="io.github.devansh-ops:rewrite-spring-to-helidon:$contract_recipe_version" \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.MigrateSpringTransactionalToJakartaIncludingSupports

if grep -R -Fq 'org.springframework' "$contract_supports_rewrite_subject/src/main/java"; then
  echo "The rewritten Helidon SUPPORTS fixture still contains Spring source references." >&2
  exit 1
fi

if ! grep -R -Fq 'jakarta.transaction.Transactional' \
  "$contract_supports_rewrite_subject/src/main/java"; then
  echo "The SUPPORTS recipe did not generate Jakarta Transactions annotations." >&2
  exit 1
fi

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_fixture_dir/pom.xml" -pl supports-helidon-runtime \
  -Dcontract.supportsGeneratedSource="$contract_supports_rewrite_subject/src/main/java" \
  compile org.codehaus.mojo:exec-maven-plugin:3.5.0:exec \
  -Dexec.executable=java \
  -Dexec.classpathScope=runtime \
  -Dexec.workingdir="$contract_target_dir" \
  -Dexec.args="-Dcontract.output=$contract_supports_helidon_output -classpath %classpath io.github.devanshops.rewrite.helidon.it.transaction.supports.helidon.SupportsHelidonMain"

"$contract_project_dir/mvnw" --batch-mode --no-transfer-progress \
  -f "$contract_fixture_dir/pom.xml" -pl supports-helidon-runtime \
  -DoutputFile="$contract_supports_dependency_tree" \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree

if grep -Fq 'org.springframework' "$contract_supports_dependency_tree"; then
  echo "The compiled Helidon SUPPORTS runtime has a Spring dependency." >&2
  exit 1
fi

if [[ ! -s "$contract_supports_spring_output" || \
      ! -s "$contract_supports_helidon_output" ]]; then
  echo "Both runtimes must produce SUPPORTS transaction-contract outcomes." >&2
  exit 1
fi

cmp "$contract_supports_spring_expected" "$contract_supports_spring_output"
cmp "$contract_supports_helidon_expected" "$contract_supports_helidon_output"
sed 's/,SPRING_SYNCHRONIZATION_[A-Z_]*$//' \
  "$contract_supports_spring_output" > "$contract_supports_spring_common"
sed 's/,SPRING_SYNCHRONIZATION_[A-Z_]*$//' \
  "$contract_supports_helidon_output" > "$contract_supports_helidon_common"
cmp "$contract_supports_spring_common" "$contract_supports_helidon_common"

echo "Opt-in SUPPORTS runtime contract passed with the documented synchronization boundary."
