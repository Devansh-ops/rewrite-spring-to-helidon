#!/usr/bin/env bash
set -euo pipefail

central_smoke_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
central_smoke_project_dir=$(CDPATH= cd -- "$central_smoke_script_dir/.." && pwd)

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <MAJOR.MINOR.PATCH>" >&2
  exit 2
fi

central_smoke_version=$1
central_smoke_repository_url=${CENTRAL_REPOSITORY_URL:-https://repo.maven.apache.org/maven2}
central_smoke_curl=${CURL_BIN:-curl}
central_smoke_maven=${MAVEN_BIN:-"$central_smoke_project_dir/mvnw"}
central_smoke_max_attempts=${CENTRAL_SMOKE_MAX_ATTEMPTS:-120}
central_smoke_retry_seconds=${CENTRAL_SMOKE_RETRY_SECONDS:-15}
central_smoke_request_timeout_seconds=${CENTRAL_SMOKE_REQUEST_TIMEOUT_SECONDS:-10}

if [[ ! "$central_smoke_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Central consumer version must use the exact stable form MAJOR.MINOR.PATCH: $central_smoke_version" >&2
  exit 1
fi

if [[ ! "$central_smoke_max_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "CENTRAL_SMOKE_MAX_ATTEMPTS must be a positive integer." >&2
  exit 2
fi

if [[ ! "$central_smoke_retry_seconds" =~ ^[0-9]+$ ]]; then
  echo "CENTRAL_SMOKE_RETRY_SECONDS must be a non-negative integer." >&2
  exit 2
fi

if [[ ! "$central_smoke_request_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "CENTRAL_SMOKE_REQUEST_TIMEOUT_SECONDS must be a positive integer." >&2
  exit 2
fi

central_smoke_coordinate="io.github.devansh-ops:rewrite-spring-to-helidon:$central_smoke_version"
central_smoke_artifact_path="io/github/devansh-ops/rewrite-spring-to-helidon/$central_smoke_version"
central_smoke_pom_url="${central_smoke_repository_url%/}/$central_smoke_artifact_path/rewrite-spring-to-helidon-$central_smoke_version.pom"

central_smoke_attempt=1
while true; do
  central_smoke_http_status=000
  if central_smoke_http_result=$("$central_smoke_curl" --silent --show-error --location \
      --connect-timeout "$central_smoke_request_timeout_seconds" \
      --max-time "$central_smoke_request_timeout_seconds" \
      --output /dev/null --write-out '%{http_code}' "$central_smoke_pom_url"); then
    central_smoke_http_status=$central_smoke_http_result
  fi

  if [[ "$central_smoke_http_status" == 200 ]]; then
    break
  fi

  if (( central_smoke_attempt >= central_smoke_max_attempts )); then
    echo "Maven Central did not expose $central_smoke_coordinate after $central_smoke_attempt attempts; last HTTP status was $central_smoke_http_status." >&2
    exit 1
  fi

  sleep "$central_smoke_retry_seconds"
  central_smoke_attempt=$((central_smoke_attempt + 1))
done

central_smoke_tmp_dir=$(mktemp -d)
trap 'rm -rf "$central_smoke_tmp_dir"' EXIT
central_smoke_fixture_dir="$central_smoke_tmp_dir/consumer"
central_smoke_local_repository="$central_smoke_tmp_dir/maven-repository"
central_smoke_settings="$central_smoke_tmp_dir/settings.xml"

mkdir -p "$central_smoke_fixture_dir" "$central_smoke_local_repository"
cp -R "$central_smoke_project_dir/src/it/smoke/." "$central_smoke_fixture_dir/"

cat >"$central_smoke_settings" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
    <interactiveMode>false</interactiveMode>
    <mirrors>
        <mirror>
            <id>central-only</id>
            <name>Maven Central only</name>
            <url>${central_smoke_repository_url%/}</url>
            <mirrorOf>*</mirrorOf>
        </mirror>
    </mirrors>
</settings>
EOF

(
  cd "$central_smoke_fixture_dir"
  "$central_smoke_maven" --settings="$central_smoke_settings" \
    -Dmaven.repo.local="$central_smoke_local_repository" \
    --batch-mode --no-transfer-progress -U clean \
    org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
    -Drewrite.recipeArtifactCoordinates="$central_smoke_coordinate" \
    -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp \
    -Drewrite.exportDatatables=true
)

central_smoke_patch="$central_smoke_fixture_dir/target/rewrite/rewrite.patch"
central_smoke_datatables_dir="$central_smoke_fixture_dir/target/rewrite/datatables"
central_smoke_recipe_jar="$central_smoke_local_repository/$central_smoke_artifact_path/rewrite-spring-to-helidon-$central_smoke_version.jar"

if [[ ! -s "$central_smoke_recipe_jar" ]]; then
  echo "The isolated consumer did not resolve $central_smoke_coordinate from Maven Central." >&2
  exit 1
fi

if [[ ! -s "$central_smoke_patch" ]]; then
  echo "Expected the Central-only consumer to produce $central_smoke_patch" >&2
  exit 1
fi

grep -Fq 'PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject' "$central_smoke_patch"
grep -Fq 'MANUAL: Spring Java API [SPRING_JAVA_API]' "$central_smoke_patch"

central_smoke_usage_table=$(find "$central_smoke_datatables_dir" -type f \
  -name '*SpringUsageTable.csv' -print -quit 2>/dev/null || true)
central_smoke_project_table=$(find "$central_smoke_datatables_dir" -type f \
  -name '*MigrationAssessmentTable.csv' -print -quit 2>/dev/null || true)

if [[ -z "$central_smoke_usage_table" || -z "$central_smoke_project_table" ]]; then
  echo "The Central-only consumer did not export both migration assessment tables." >&2
  exit 1
fi

grep -Fq 'PARTIAL' "$central_smoke_usage_table"
grep -Fq 'SPRING_MAVEN_DEPENDENCY' "$central_smoke_project_table"
grep -Fq 'SPRING_JAVA_API' "$central_smoke_project_table"

echo "Central-only consumer smoke test passed for $central_smoke_coordinate."
