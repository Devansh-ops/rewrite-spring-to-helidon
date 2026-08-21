#!/usr/bin/env bash
set -Eeuo pipefail

release_test_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
release_test_project_dir=$(CDPATH= cd -- "$release_test_script_dir/.." && pwd)
release_test_fixture_pom="$release_test_project_dir/src/test/release-tooling/release-pom.xml"
release_test_wrong_scm_pom="$release_test_project_dir/src/test/release-tooling/wrong-scm-tag-pom.xml"
release_test_snapshot_pom="$release_test_project_dir/src/test/release-tooling/snapshot-pom.xml"
release_test_guard="$release_test_script_dir/validate-release-tag.sh"
release_test_bundle_validator="$release_test_script_dir/validate-release-bundle.sh"
release_test_bundle_membership="$release_test_script_dir/test-release-bundle-membership.sh"
release_test_central_guard="$release_test_script_dir/assert-central-version-new.sh"
release_test_central_smoke="$release_test_script_dir/central-consumer-smoke-test.sh"
release_test_central_readback="$release_test_script_dir/verify-central-publication.sh"
release_test_local_bundle_builder="$release_test_script_dir/build-release-bundle-locally.sh"
release_test_main_guard="$release_test_script_dir/assert-release-commit-on-main.sh"
release_test_reproducibility="$release_test_script_dir/verify-reproducible-release.sh"
release_test_publish_workflow="$release_test_project_dir/.github/workflows/publish-central.yml"
release_test_ci_workflow="$release_test_project_dir/.github/workflows/ci.yml"
release_test_contributing="$release_test_project_dir/CONTRIBUTING.md"
release_test_publishing_docs="$release_test_project_dir/docs/publishing.md"
release_test_reproducible_pom="$release_test_project_dir/src/test/release-tooling/reproducible-release-pom.xml"

release_test_usage() {
  echo "Usage: $0 development | stable-tag <vMAJOR.MINOR.PATCH>" >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  release_test_usage
  exit 2
fi

release_test_mode=$1
release_test_release_tag=
case "$release_test_mode" in
  development)
    if [[ $# -ne 1 ]]; then
      release_test_usage
      exit 2
    fi
    ;;
  stable-tag)
    if [[ $# -ne 2 ]]; then
      release_test_usage
      exit 2
    fi
    release_test_release_tag=$2
    if [[ ! "$release_test_release_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
      echo "Stable release tooling mode requires an exact vMAJOR.MINOR.PATCH tag: $release_test_release_tag" >&2
      exit 2
    fi
    ;;
  *)
    release_test_usage
    exit 2
    ;;
esac

release_test_output_file=$(mktemp)
release_test_tmp_dir=$(mktemp -d)
release_test_gnupg_home=

release_test_report_failure() {
  local release_test_failure_status=$?
  echo "Release tooling tests failed at line ${BASH_LINENO[0]} (exit $release_test_failure_status)." >&2
  return "$release_test_failure_status"
}

release_test_cleanup() {
  local release_test_cleanup_status=$?
  trap - ERR EXIT
  if [[ -n "$release_test_gnupg_home" && -d "$release_test_gnupg_home" ]]; then
    GNUPGHOME="$release_test_gnupg_home" gpgconf --kill gpg-agent >/dev/null 2>&1 || true
  fi
  rm -f "$release_test_output_file"
  rm -rf "$release_test_tmp_dir"
  exit "$release_test_cleanup_status"
}

trap release_test_report_failure ERR
trap release_test_cleanup EXIT

release_test_commit=0123456789abcdef0123456789abcdef01234567

"$release_test_bundle_membership" >"$release_test_output_file"
grep -Fq 'Release-bundle membership validation passed without a pipefail/SIGPIPE false negative.' \
  "$release_test_output_file"

if grep -Eq 'unzip.*-Z1.*\|.*grep' "$release_test_bundle_validator"; then
  echo "Release bundle membership checks must never pipe archive listing into grep -q under pipefail." >&2
  exit 1
fi

release_test_fake_git="$release_test_tmp_dir/fake-git"
release_test_git_log="$release_test_tmp_dir/git.log"
cat >"$release_test_fake_git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_GIT_LOG"
exit "$FAKE_GIT_ANCESTRY_STATUS"
EOF
chmod +x "$release_test_fake_git"

if ! GIT_BIN="$release_test_fake_git" FAKE_GIT_LOG="$release_test_git_log" \
    FAKE_GIT_ANCESTRY_STATUS=0 "$release_test_main_guard" "$release_test_commit" \
    >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected the release-main guard to accept a commit reachable from origin/main." >&2
  exit 1
fi

grep -Fxq "merge-base --is-ancestor $release_test_commit origin/main" "$release_test_git_log"

if GIT_BIN="$release_test_fake_git" FAKE_GIT_LOG="$release_test_git_log" \
    FAKE_GIT_ANCESTRY_STATUS=1 "$release_test_main_guard" "$release_test_commit" \
    >"$release_test_output_file" 2>&1; then
  echo "Expected the release-main guard to reject a commit outside origin/main." >&2
  exit 1
fi

grep -Fq "Release commit $release_test_commit is not reachable from origin/main." \
  "$release_test_output_file"

if GIT_BIN="$release_test_fake_git" FAKE_GIT_LOG="$release_test_git_log" \
    FAKE_GIT_ANCESTRY_STATUS=128 "$release_test_main_guard" "$release_test_commit" \
    >"$release_test_output_file" 2>&1; then
  echo "Expected the release-main guard to fail closed when ancestry cannot be proved." >&2
  exit 1
fi

grep -Fq "Could not prove that release commit $release_test_commit is reachable from origin/main." \
  "$release_test_output_file"

if RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1-SNAPSHOT \
    "$release_test_tmp_dir/nonexistent-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject a non-stable version." >&2
  exit 1
fi

grep -Fq 'Release bundle version must use the exact stable form MAJOR.MINOR.PATCH: 0.2.1-SNAPSHOT' \
  "$release_test_output_file"

if CENTRAL_READBACK_TIMEOUT_SECONDS=0 "$release_test_central_readback" 0.2.1 \
    >"$release_test_output_file" 2>&1; then
  echo "Expected Central readback to reject an unbounded zero-second shared timeout." >&2
  exit 1
fi

grep -Fq 'CENTRAL_READBACK_TIMEOUT_SECONDS must be a positive integer.' \
  "$release_test_output_file"

if CENTRAL_SMOKE_REQUEST_TIMEOUT_SECONDS=0 "$release_test_central_smoke" 0.2.1 \
    >"$release_test_output_file" 2>&1; then
  echo "Expected the Central consumer probe to reject an unbounded request timeout." >&2
  exit 1
fi

grep -Fq 'CENTRAL_SMOKE_REQUEST_TIMEOUT_SECONDS must be a positive integer.' \
  "$release_test_output_file"

if ! "$release_test_guard" v0.2.1 "$release_test_fixture_pom" >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected the release tag guard to accept an exact tag, Maven version, and SCM tag." >&2
  exit 1
fi

if "$release_test_guard" v0.2.2 "$release_test_fixture_pom" >"$release_test_output_file" 2>&1; then
  echo "Expected the release tag guard to reject a tag that differs from the Maven version." >&2
  exit 1
fi

grep -Fq 'Release tag v0.2.2 does not match Maven version 0.2.1.' "$release_test_output_file"

if "$release_test_guard" v0.2.1 "$release_test_wrong_scm_pom" >"$release_test_output_file" 2>&1; then
  echo "Expected the release tag guard to reject a different Maven SCM tag." >&2
  exit 1
fi

grep -Fq 'Maven SCM tag HEAD does not match release tag v0.2.1.' "$release_test_output_file"

if "$release_test_guard" v0.2.1 "$release_test_snapshot_pom" >"$release_test_output_file" 2>&1; then
  echo "Expected the release tag guard to reject a snapshot Maven version." >&2
  exit 1
fi

grep -Fq 'Maven Central releases cannot use a SNAPSHOT version: 0.2.1-SNAPSHOT.' "$release_test_output_file"

release_test_project_version=$("$release_test_project_dir/mvnw" --quiet --no-transfer-progress \
  -f "$release_test_project_dir/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout)
release_test_project_scm_tag=$("$release_test_project_dir/mvnw" --quiet --no-transfer-progress \
  -f "$release_test_project_dir/pom.xml" help:evaluate -Dexpression=project.scm.tag -DforceStdout)

case "$release_test_mode" in
  development)
    if [[ ! "$release_test_project_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-SNAPSHOT$ ]]; then
      echo "Development release-tooling mode requires a MAJOR.MINOR.PATCH-SNAPSHOT Maven version, found $release_test_project_version." >&2
      exit 1
    fi

    if [[ "$release_test_project_scm_tag" != HEAD ]]; then
      echo "Development release-tooling mode requires Maven SCM tag HEAD, found $release_test_project_scm_tag." >&2
      exit 1
    fi

    if "$release_test_project_dir/mvnw" --batch-mode --no-transfer-progress \
        -f "$release_test_project_dir/pom.xml" -Prelease validate \
        >"$release_test_output_file" 2>&1; then
      echo "Expected the release Maven profile to reject a SNAPSHOT project version." >&2
      exit 1
    fi

    grep -Fq 'The release profile cannot publish a SNAPSHOT project version.' \
      "$release_test_output_file"
    ;;
  stable-tag)
    if ! "$release_test_guard" "$release_test_release_tag" "$release_test_project_dir/pom.xml" \
        >"$release_test_output_file" 2>&1; then
      cat "$release_test_output_file" >&2
      echo "Stable release-tooling mode requires matching tag, Maven version, and SCM tag." >&2
      exit 1
    fi

    if ! "$release_test_project_dir/mvnw" --batch-mode --no-transfer-progress \
        -f "$release_test_project_dir/pom.xml" -Prelease validate \
        >"$release_test_output_file" 2>&1; then
      cat "$release_test_output_file" >&2
      echo "Expected the release Maven profile to accept stable release metadata." >&2
      exit 1
    fi
    ;;
esac

release_test_effective_pom="$release_test_tmp_dir/effective-release-pom.xml"
"$release_test_project_dir/mvnw" --quiet --no-transfer-progress -f "$release_test_project_dir/pom.xml" \
  -Prelease help:effective-pom -Doutput="$release_test_effective_pom"
release_test_default_skip_publishing=$("$release_test_project_dir/mvnw" --quiet \
  --no-transfer-progress -f "$release_test_project_dir/pom.xml" \
  help:evaluate -Dexpression=central.skipPublishing -DforceStdout)

if [[ "$release_test_default_skip_publishing" != true ]]; then
  echo "central.skipPublishing must default to true; publishing requires an explicit protected-workflow override." >&2
  exit 1
fi

for release_test_required_profile_fragment in \
    '<artifactId>maven-source-plugin</artifactId>' '<version>3.4.0</version>' \
    '<artifactId>maven-javadoc-plugin</artifactId>' '<version>3.12.0</version>' \
    '<artifactId>maven-gpg-plugin</artifactId>' '<version>3.2.8</version>' \
    '<bestPractices>true</bestPractices>' '<arg>--pinentry-mode</arg>' '<arg>loopback</arg>' \
    '<id>enforce-release-version</id>' '<requireReleaseVersion>' \
    '<artifactId>maven-deploy-plugin</artifactId>' '<version>3.1.4</version>' \
    '<artifactId>central-publishing-maven-plugin</artifactId>' '<version>0.11.0</version>' \
    '<extensions>true</extensions>' '<publishingServerId>central</publishingServerId>' \
    '<autoPublish>true</autoPublish>' '<waitUntil>published</waitUntil>' \
    '<waitMaxTime>7200</waitMaxTime>' '<waitPollingInterval>10</waitPollingInterval>' \
    '<checksums>all</checksums>' '<ignorePublishedComponents>false</ignorePublishedComponents>' \
    '<skipPublishing>true</skipPublishing>'; do
  if ! grep -Fq "$release_test_required_profile_fragment" "$release_test_effective_pom"; then
    echo "Release Maven profile is missing: $release_test_required_profile_fragment" >&2
    exit 1
  fi
done

if [[ $(grep -Fc 'central.skipPublishing=false' "$release_test_publish_workflow") -ne 1 ]]; then
  echo "Only one explicit publishing override is allowed in the protected Maven Central workflow." >&2
  exit 1
fi

for release_test_safe_default_file in \
    "$release_test_project_dir/pom.xml" \
    "$release_test_project_dir/README.md" \
    "$release_test_project_dir/CONTRIBUTING.md" \
    "$release_test_project_dir/CHANGELOG.md" \
    "$release_test_project_dir/docs"/*.md \
    "$release_test_project_dir/scripts"/*.sh \
    "$release_test_project_dir/.github/workflows"/*.yml; do
  if [[ "$release_test_safe_default_file" == "$release_test_publish_workflow" \
      || "$release_test_safe_default_file" == "$release_test_project_dir/scripts/test-release-tooling.sh" ]]; then
    continue
  fi
  if grep -Fq 'central.skipPublishing=false' "$release_test_safe_default_file"; then
    echo "Publishing override escaped the protected workflow: $release_test_safe_default_file" >&2
    exit 1
  fi
done

if grep -Eq '^[[:space:]]+gpg-(private-key|passphrase):' "$release_test_publish_workflow"; then
  echo "The Maven Central workflow must pass signing secrets only through the publication step environment." >&2
  exit 1
fi

if [[ ! -s "$release_test_publish_workflow" ]]; then
  echo "Expected a Maven Central publication workflow at $release_test_publish_workflow." >&2
  exit 1
fi

if grep -Eq '^[[:space:]]*(pull_request|pull_request_target|schedule|workflow_dispatch):' "$release_test_publish_workflow"; then
  echo "The Maven Central publication workflow must be tag-push-only." >&2
  exit 1
fi

for release_test_required_workflow_fragment in \
    'tags:' '- "v*.*.*"' 'contents: read' 'persist-credentials: false' 'fetch-depth: 0' \
    'name: maven-central' 'id-token: write' 'attestations: write' \
    'artifact-metadata: write' './scripts/validate-release-tag.sh' \
    './scripts/assert-release-commit-on-main.sh' './scripts/verify-reproducible-release.sh' \
    './scripts/assert-central-version-new.sh' 'central.skipPublishing=false' \
    './scripts/validate-release-bundle.sh' \
    'run: ./scripts/verify-central-publication.sh "${{ steps.release.outputs.version }}" "${{ github.workspace }}/target/central-publishing/central-bundle.zip" "${{ github.workspace }}"' \
    './scripts/central-consumer-smoke-test.sh' 'secrets.MAVEN_CENTRAL_USERNAME' \
    'CENTRAL_READBACK_MAX_ATTEMPTS: "80"' 'CENTRAL_READBACK_RETRY_SECONDS: "15"' \
    'CENTRAL_READBACK_TIMEOUT_SECONDS: "1200"' \
    'CENTRAL_READBACK_REQUEST_TIMEOUT_SECONDS: "10"' \
    'CENTRAL_SMOKE_MAX_ATTEMPTS: "24"' 'CENTRAL_SMOKE_RETRY_SECONDS: "15"' \
    'CENTRAL_SMOKE_REQUEST_TIMEOUT_SECONDS: "10"' \
    'secrets.MAVEN_CENTRAL_TOKEN' 'secrets.MAVEN_GPG_PRIVATE_KEY' \
    'secrets.MAVEN_GPG_PASSPHRASE' 'gpg --batch --quiet --import' \
    'unset MAVEN_GPG_PRIVATE_KEY' 'release-public-key.gpg' \
    'release-verification-gnupg'; do
  if ! grep -Fq -- "$release_test_required_workflow_fragment" "$release_test_publish_workflow"; then
    echo "Maven Central publication workflow is missing: $release_test_required_workflow_fragment" >&2
    exit 1
  fi
done

if [[ $(grep -Fxc '    timeout-minutes: 180' "$release_test_publish_workflow") -ne 1 ]]; then
  echo "The protected publication job must have one 180-minute bounded timeout." >&2
  exit 1
fi

if (( 7200 + 1200 + 10 + (24 * (15 + 10)) >= 180 * 60 )); then
  echo "Configured Central wait/retry limits must fit inside the protected publication timeout." >&2
  exit 1
fi

release_test_workflow_line() {
  local release_test_workflow_fragment=$1
  local release_test_match
  local release_test_line
  if ! release_test_match=$(grep -m 1 -n -F -- "$release_test_workflow_fragment" \
      "$release_test_publish_workflow"); then
    echo "Maven Central publication workflow is missing ordered step: $release_test_workflow_fragment" >&2
    exit 1
  fi
  release_test_line=${release_test_match%%:*}
  printf '%s\n' "$release_test_line"
}

release_test_identity_line=$(release_test_workflow_line \
  'run: ./scripts/validate-release-tag.sh "$GITHUB_REF_NAME"')
release_test_ancestry_line=$(release_test_workflow_line \
  'run: ./scripts/assert-release-commit-on-main.sh "$GITHUB_SHA"')
release_test_stable_mode_line=$(release_test_workflow_line \
  'run: ./scripts/test-release-tooling.sh stable-tag "$GITHUB_REF_NAME"')
release_test_build_line=$(release_test_workflow_line \
  'run: ./mvnw --batch-mode --no-transfer-progress clean verify')
release_test_smoke_line=$(release_test_workflow_line 'run: ./scripts/smoke-test.sh')
release_test_reproducibility_line=$(release_test_workflow_line \
  'run: ./scripts/verify-reproducible-release.sh "$GITHUB_REF_NAME"')

if ! (( release_test_identity_line < release_test_ancestry_line
    && release_test_ancestry_line < release_test_stable_mode_line
    && release_test_stable_mode_line < release_test_build_line
    && release_test_build_line < release_test_smoke_line
    && release_test_smoke_line < release_test_reproducibility_line )); then
  echo "The tag workflow must validate identity and main ancestry, run stable-tag tooling, then build, smoke-test, and verify reproducibility in that order." >&2
  exit 1
fi

for release_test_required_ci_fragment in \
    './scripts/test-release-tooling.sh development' \
    './scripts/test-release-tooling.sh stable-tag "v$release_ci_version"'; do
  if ! grep -Fq -- "$release_test_required_ci_fragment" "$release_test_ci_workflow"; then
    echo "Ordinary CI is missing release-tooling mode: $release_test_required_ci_fragment" >&2
    exit 1
  fi
done

for release_test_required_contributing_fragment in \
    './scripts/test-release-tooling.sh development' \
    './scripts/test-release-tooling.sh stable-tag v0.2.1'; do
  if ! grep -Fq -- "$release_test_required_contributing_fragment" "$release_test_contributing"; then
    echo "CONTRIBUTING.md is missing release-tooling guidance: $release_test_required_contributing_fragment" >&2
    exit 1
  fi
done

if ! grep -Fq './scripts/test-release-tooling.sh stable-tag v0.2.1' \
    "$release_test_publishing_docs"; then
  echo "The release runbook must use stable-tag release-tooling mode after preparing stable metadata." >&2
  exit 1
fi

for release_test_required_environment_fragment in \
    'deployment tags restricted to protected release tags matching `v*.*.*`' \
    'required approval from `Devansh-ops`' \
    '5-minute wait timer'; do
  if ! grep -Fq -- "$release_test_required_environment_fragment" \
      "$release_test_publishing_docs"; then
    echo "The release runbook is missing the current protected-environment contract: $release_test_required_environment_fragment" >&2
    exit 1
  fi
done

if [[ $(grep -Fxc '          fetch-depth: 0' "$release_test_publish_workflow") -ne 2 ]]; then
  echo "Both Maven Central jobs must fetch full history before checking origin/main ancestry." >&2
  exit 1
fi

if grep -R -Fq 'UPLOAD_DISABLED_LOCAL_VALIDATION' \
    "$release_test_project_dir/.github" "$release_test_project_dir/docs" \
    "$release_test_project_dir/README.md" "$release_test_project_dir/CONTRIBUTING.md" \
    "$release_test_project_dir/CHANGELOG.md"; then
  echo "Upload-disabled placeholder server values must never appear in workflows or documentation." >&2
  exit 1
fi

if grep -E '^[[:space:]]*uses:[[:space:]]+[^@[:space:]]+@(v|main|master|[0-9a-f]{1,39}([^0-9a-f]|$))' \
    "$release_test_publish_workflow"; then
  echo "Every third-party action in the Maven Central publication workflow must be pinned to a full commit SHA." >&2
  exit 1
fi

release_test_fake_curl="$release_test_tmp_dir/fake-curl"
release_test_curl_log="$release_test_tmp_dir/curl.log"
cat >"$release_test_fake_curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_CURL_LOG"
printf '%s' "$FAKE_CURL_STATUS"
EOF
chmod +x "$release_test_fake_curl"

if ! CURL_BIN="$release_test_fake_curl" FAKE_CURL_LOG="$release_test_curl_log" FAKE_CURL_STATUS=404 \
    "$release_test_central_guard" 0.2.1 "$release_test_fixture_pom" >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected an absent Maven Central coordinate to pass the duplicate-version guard." >&2
  exit 1
fi

grep -Fq 'https://repo.maven.apache.org/maven2/io/github/devansh-ops/release-tooling-fixture/0.2.1/release-tooling-fixture-0.2.1.pom' \
  "$release_test_curl_log"

if CURL_BIN="$release_test_fake_curl" FAKE_CURL_LOG="$release_test_curl_log" FAKE_CURL_STATUS=200 \
    "$release_test_central_guard" 0.2.1 "$release_test_fixture_pom" >"$release_test_output_file" 2>&1; then
  echo "Expected an existing Maven Central coordinate to fail the duplicate-version guard." >&2
  exit 1
fi

grep -Fq 'Maven Central already contains io.github.devansh-ops:release-tooling-fixture:0.2.1; published versions are immutable.' \
  "$release_test_output_file"

if CURL_BIN="$release_test_fake_curl" FAKE_CURL_LOG="$release_test_curl_log" FAKE_CURL_STATUS=503 \
    "$release_test_central_guard" 0.2.1 "$release_test_fixture_pom" >"$release_test_output_file" 2>&1; then
  echo "Expected an inconclusive Maven Central response to fail closed." >&2
  exit 1
fi

grep -Fq 'Could not prove that io.github.devansh-ops:release-tooling-fixture:0.2.1 is unpublished: Maven Central returned HTTP 503.' \
  "$release_test_output_file"

release_test_fake_maven="$release_test_tmp_dir/fake-maven"
cat >"$release_test_fake_maven" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
fake_maven_settings=
fake_maven_local_repository=
for fake_maven_argument in "$@"; do
  case "$fake_maven_argument" in
    --settings=*) fake_maven_settings=${fake_maven_argument#--settings=} ;;
    -Dmaven.repo.local=*) fake_maven_local_repository=${fake_maven_argument#-Dmaven.repo.local=} ;;
  esac
done
test -n "$fake_maven_settings"
test -n "$fake_maven_local_repository"
grep -Fq '<mirrorOf>*</mirrorOf>' "$fake_maven_settings"
grep -Fq '<url>https://repo.maven.apache.org/maven2</url>' "$fake_maven_settings"
printf '%s\n' "$*" | grep -Fq -- '-Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.2.1'
mkdir -p target/rewrite/datatables/fixture
mkdir -p "$fake_maven_local_repository/io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1"
printf '%s\n' 'PARTIAL: Dependency injection -> Jakarta CDI and jakarta.inject' \
  'MANUAL: Spring Java API [SPRING_JAVA_API]' >target/rewrite/rewrite.patch
printf '%s\n' 'PARTIAL,Jakarta CDI and jakarta.inject' >target/rewrite/datatables/fixture/SpringUsageTable.csv
printf '%s\n' 'SPRING_MAVEN_DEPENDENCY' 'SPRING_JAVA_API' >target/rewrite/datatables/fixture/MigrationAssessmentTable.csv
printf '%s\n' 'published recipe' \
  >"$fake_maven_local_repository/io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1.jar"
EOF
chmod +x "$release_test_fake_maven"

if ! CURL_BIN="$release_test_fake_curl" FAKE_CURL_LOG="$release_test_curl_log" FAKE_CURL_STATUS=200 \
    MAVEN_BIN="$release_test_fake_maven" CENTRAL_SMOKE_MAX_ATTEMPTS=1 CENTRAL_SMOKE_RETRY_SECONDS=0 \
    "$release_test_central_smoke" 0.2.1 >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected the clean Central-only consumer smoke seam to execute the released recipe." >&2
  exit 1
fi

grep -Fq 'Central-only consumer smoke test passed for io.github.devansh-ops:rewrite-spring-to-helidon:0.2.1.' \
  "$release_test_output_file"

release_test_incomplete_root="$release_test_tmp_dir/incomplete"
release_test_coordinate_path="io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1"
mkdir -p "$release_test_incomplete_root/$release_test_coordinate_path"
printf 'fixture\n' >"$release_test_incomplete_root/$release_test_coordinate_path/README.txt"
(
  cd "$release_test_incomplete_root"
  jar --create --file "$release_test_tmp_dir/incomplete-bundle.zip" .
)

if RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/incomplete-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject a bundle missing its main artifact." >&2
  exit 1
fi

grep -Fq 'Missing required release bundle entry: io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1.jar' \
  "$release_test_output_file"

release_test_missing_sources_root="$release_test_tmp_dir/missing-sources"
mkdir -p "$release_test_missing_sources_root/$release_test_coordinate_path"
printf 'fixture jar\n' >"$release_test_missing_sources_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1.jar"
(
  cd "$release_test_missing_sources_root"
  jar --create --file "$release_test_tmp_dir/missing-sources-bundle.zip" .
)

if RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/missing-sources-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject a bundle missing its sources artifact." >&2
  exit 1
fi

grep -Fq 'Missing required release bundle entry: io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1-sources.jar' \
  "$release_test_output_file"

release_test_missing_javadoc_root="$release_test_tmp_dir/missing-javadoc"
mkdir -p "$release_test_missing_javadoc_root/$release_test_coordinate_path"
printf 'fixture jar\n' >"$release_test_missing_javadoc_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1.jar"
printf 'fixture sources\n' >"$release_test_missing_javadoc_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1-sources.jar"
(
  cd "$release_test_missing_javadoc_root"
  jar --create --file "$release_test_tmp_dir/missing-javadoc-bundle.zip" .
)

if RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/missing-javadoc-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject a bundle missing its javadoc artifact." >&2
  exit 1
fi

grep -Fq 'Missing required release bundle entry: io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1-javadoc.jar' \
  "$release_test_output_file"

release_test_missing_pom_root="$release_test_tmp_dir/missing-pom"
mkdir -p "$release_test_missing_pom_root/$release_test_coordinate_path"
printf 'fixture jar\n' >"$release_test_missing_pom_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1.jar"
printf 'fixture sources\n' >"$release_test_missing_pom_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1-sources.jar"
printf 'fixture javadoc\n' >"$release_test_missing_pom_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1-javadoc.jar"
(
  cd "$release_test_missing_pom_root"
  jar --create --file "$release_test_tmp_dir/missing-pom-bundle.zip" .
)

if RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/missing-pom-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject a bundle missing its POM." >&2
  exit 1
fi

grep -Fq 'Missing required release bundle entry: io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1.pom' \
  "$release_test_output_file"

release_test_unsigned_root="$release_test_tmp_dir/unsigned"
mkdir -p "$release_test_unsigned_root/$release_test_coordinate_path"
for release_test_suffix in .jar -sources.jar -javadoc.jar .pom; do
  printf 'fixture artifact\n' >"$release_test_unsigned_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1$release_test_suffix"
done
(
  cd "$release_test_unsigned_root"
  jar --create --file "$release_test_tmp_dir/unsigned-bundle.zip" .
)

if RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/unsigned-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject unsigned artifacts." >&2
  exit 1
fi

grep -Fq 'Missing required release bundle entry: io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1.jar.asc' \
  "$release_test_output_file"

release_test_missing_checksums_root="$release_test_tmp_dir/missing-checksums"
mkdir -p "$release_test_missing_checksums_root/$release_test_coordinate_path"
for release_test_suffix in .jar -sources.jar -javadoc.jar .pom; do
  release_test_artifact="$release_test_missing_checksums_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1$release_test_suffix"
  printf 'fixture artifact\n' >"$release_test_artifact"
  printf 'fixture signature\n' >"$release_test_artifact.asc"
done
(
  cd "$release_test_missing_checksums_root"
  jar --create --file "$release_test_tmp_dir/missing-checksums-bundle.zip" .
)

if RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/missing-checksums-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject artifacts without checksums." >&2
  exit 1
fi

grep -Fq 'Missing required release bundle entry: io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1.jar.md5' \
  "$release_test_output_file"

release_test_valid_root="$release_test_tmp_dir/valid"
mkdir -p "$release_test_valid_root/$release_test_coordinate_path"
for release_test_suffix in .jar -sources.jar -javadoc.jar .pom; do
  release_test_artifact="$release_test_valid_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1$release_test_suffix"
  printf 'fixture artifact %s\n' "$release_test_suffix" >"$release_test_artifact"
  printf 'fixture signature\n' >"$release_test_artifact.asc"
  md5sum "$release_test_artifact" | awk '{ print $1 }' >"$release_test_artifact.md5"
  sha1sum "$release_test_artifact" | awk '{ print $1 }' >"$release_test_artifact.sha1"
  sha256sum "$release_test_artifact" | awk '{ print $1 }' >"$release_test_artifact.sha256"
  sha512sum "$release_test_artifact" | awk '{ print $1 }' >"$release_test_artifact.sha512"
done
(
  cd "$release_test_valid_root"
  jar --create --file "$release_test_tmp_dir/valid-bundle.zip" .
)

if ! RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/valid-bundle.zip" >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected a structurally complete bundle with valid checksums to pass." >&2
  exit 1
fi

printf '0000000000000000000000000000000000000000000000000000000000000000\n' \
  >"$release_test_valid_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1.jar.sha256"
(
  cd "$release_test_valid_root"
  jar --create --file "$release_test_tmp_dir/bad-checksum-bundle.zip" .
)

if RELEASE_GPG_VERIFY=false "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/bad-checksum-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject a mismatched checksum." >&2
  exit 1
fi

grep -Fq 'Checksum mismatch for io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1.jar.sha256.' \
  "$release_test_output_file"

release_test_gnupg_home="$release_test_tmp_dir/gnupg"
release_test_signed_root="$release_test_tmp_dir/signed"
release_test_gpg_log="$release_test_tmp_dir/gpg.log"
mkdir -m 700 "$release_test_gnupg_home"
mkdir -p "$release_test_signed_root/$release_test_coordinate_path"

release_test_generate_signing_key() {
  local release_test_gpg_attempt=1
  local release_test_gpg_max_attempts=3
  local release_test_gpg_status

  while (( release_test_gpg_attempt <= release_test_gpg_max_attempts )); do
    if GNUPGHOME="$release_test_gnupg_home" gpg --batch --quiet --pinentry-mode loopback \
        --passphrase '' --quick-generate-key \
        'Release tooling test <release-tooling@example.invalid>' rsa2048 sign 1d \
        >"$release_test_gpg_log" 2>&1; then
      return 0
    else
      release_test_gpg_status=$?
    fi

    echo "Disposable GPG key generation failed on attempt $release_test_gpg_attempt/$release_test_gpg_max_attempts (exit $release_test_gpg_status)." >&2
    cat "$release_test_gpg_log" >&2
    GNUPGHOME="$release_test_gnupg_home" gpgconf --kill gpg-agent >/dev/null 2>&1 || true

    if (( release_test_gpg_attempt == release_test_gpg_max_attempts )); then
      return "$release_test_gpg_status"
    fi

    rm -rf "$release_test_gnupg_home"
    mkdir -m 700 "$release_test_gnupg_home"
    sleep 1
    release_test_gpg_attempt=$((release_test_gpg_attempt + 1))
  done
}

release_test_sign_artifact() {
  local release_test_signing_artifact=$1
  local release_test_signing_status

  if GNUPGHOME="$release_test_gnupg_home" gpg --batch --quiet --yes --armor \
      --output "$release_test_signing_artifact.asc" --detach-sign \
      "$release_test_signing_artifact" >"$release_test_gpg_log" 2>&1; then
    return 0
  else
    release_test_signing_status=$?
  fi

  echo "Disposable GPG signing failed for $release_test_signing_artifact (exit $release_test_signing_status)." >&2
  cat "$release_test_gpg_log" >&2
  return "$release_test_signing_status"
}

release_test_generate_signing_key

for release_test_suffix in .jar -sources.jar -javadoc.jar .pom; do
  release_test_artifact="$release_test_signed_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1$release_test_suffix"
  printf 'signed fixture artifact %s\n' "$release_test_suffix" >"$release_test_artifact"
  release_test_sign_artifact "$release_test_artifact"
  md5sum "$release_test_artifact" | awk '{ print $1 }' >"$release_test_artifact.md5"
  sha1sum "$release_test_artifact" | awk '{ print $1 }' >"$release_test_artifact.sha1"
  sha256sum "$release_test_artifact" | awk '{ print $1 }' >"$release_test_artifact.sha256"
  sha512sum "$release_test_artifact" | awk '{ print $1 }' >"$release_test_artifact.sha512"
done
(
  cd "$release_test_signed_root"
  jar --create --file "$release_test_tmp_dir/signed-bundle.zip" .
)

if ! GNUPGHOME="$release_test_gnupg_home" "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/signed-bundle.zip" >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected a correctly signed bundle to pass cryptographic verification." >&2
  exit 1
fi

release_test_expected_project="$release_test_tmp_dir/expected-release-project"
mkdir -p "$release_test_expected_project/target"
cp "$release_test_signed_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1.jar" \
  "$release_test_expected_project/target/rewrite-spring-to-helidon-0.2.1.jar"
cp "$release_test_signed_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1-sources.jar" \
  "$release_test_expected_project/target/rewrite-spring-to-helidon-0.2.1-sources.jar"
cp "$release_test_signed_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1-javadoc.jar" \
  "$release_test_expected_project/target/rewrite-spring-to-helidon-0.2.1-javadoc.jar"
cp "$release_test_signed_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1.pom" \
  "$release_test_expected_project/pom.xml"

release_test_fake_central_curl="$release_test_tmp_dir/fake-central-curl"
cat >"$release_test_fake_central_curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
fake_central_output=
fake_central_url=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      shift
      fake_central_output=$1
      ;;
    http*)
      fake_central_url=$1
      ;;
  esac
  shift
done
if [[ "$fake_central_output" == /dev/null ]]; then
  printf '200'
  exit 0
fi
fake_central_relative_path=${fake_central_url#*maven2/}
cp "$FAKE_CENTRAL_ROOT/$fake_central_relative_path" "$fake_central_output"
test -s "$fake_central_output"
EOF
chmod +x "$release_test_fake_central_curl"

if ! GNUPGHOME="$release_test_gnupg_home" CURL_BIN="$release_test_fake_central_curl" \
    FAKE_CENTRAL_ROOT="$release_test_signed_root" CENTRAL_READBACK_MAX_ATTEMPTS=1 \
    CENTRAL_READBACK_RETRY_SECONDS=0 "$release_test_central_readback" 0.2.1 \
    "$release_test_tmp_dir/signed-bundle.zip" "$release_test_expected_project" \
    >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected Central readback to validate downloaded artifacts, checksums, and signatures." >&2
  exit 1
fi

grep -Fq 'Maven Central readback passed for io.github.devansh-ops:rewrite-spring-to-helidon:0.2.1.' \
  "$release_test_output_file"

release_test_different_root="$release_test_tmp_dir/different-signed"
cp -R "$release_test_signed_root" "$release_test_different_root"
release_test_different_main="$release_test_different_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1.jar"
printf 'different but signed Maven Central artifact\n' >"$release_test_different_main"
release_test_sign_artifact "$release_test_different_main"
md5sum "$release_test_different_main" | awk '{ print $1 }' >"$release_test_different_main.md5"
sha1sum "$release_test_different_main" | awk '{ print $1 }' >"$release_test_different_main.sha1"
sha256sum "$release_test_different_main" | awk '{ print $1 }' >"$release_test_different_main.sha256"
sha512sum "$release_test_different_main" | awk '{ print $1 }' >"$release_test_different_main.sha512"
(
  cd "$release_test_different_root"
  jar --create --file "$release_test_tmp_dir/different-signed-bundle.zip" .
)

if ! GNUPGHOME="$release_test_gnupg_home" "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/different-signed-bundle.zip" >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected the byte-different negative fixture to remain checksum-valid and correctly signed." >&2
  exit 1
fi

if GNUPGHOME="$release_test_gnupg_home" CURL_BIN="$release_test_fake_central_curl" \
    FAKE_CENTRAL_ROOT="$release_test_different_root" CENTRAL_READBACK_MAX_ATTEMPTS=1 \
    CENTRAL_READBACK_RETRY_SECONDS=0 "$release_test_central_readback" 0.2.1 \
    "$release_test_tmp_dir/signed-bundle.zip" "$release_test_expected_project" \
    >"$release_test_output_file" 2>&1; then
  echo "Expected Central readback to reject different bytes even when their checksums and signature are valid." >&2
  exit 1
fi

grep -Fq 'Maven Central bytes differ from submitted bundle: main.jar' \
  "$release_test_output_file"

release_test_fake_release_maven="$release_test_tmp_dir/fake-release-maven"
release_test_release_maven_log="$release_test_tmp_dir/release-maven.log"
release_test_local_release_project="$release_test_tmp_dir/local-release-project"
mkdir -p "$release_test_local_release_project"
cp "$release_test_reproducible_pom" "$release_test_local_release_project/pom.xml"
cat >"$release_test_fake_release_maven" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
fake_release_pom=
printf '%s\n' "$*" >"$FAKE_RELEASE_MAVEN_LOG"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f)
      shift
      fake_release_pom=$1
      ;;
  esac
  shift
done
test -n "$fake_release_pom"
fake_release_project_dir=$(CDPATH= cd -- "$(dirname -- "$fake_release_pom")" && pwd)
fake_release_prefix="$fake_release_project_dir/target/rewrite-spring-to-helidon-0.2.1"
mkdir -p "$fake_release_project_dir/target"
for fake_release_suffix in .jar -sources.jar -javadoc.jar .pom; do
  fake_release_signature="$fake_release_prefix$fake_release_suffix.asc"
  if [[ "$fake_release_suffix" == .pom ]]; then
    fake_release_artifact=$fake_release_pom
  else
    fake_release_artifact="$fake_release_prefix$fake_release_suffix"
    printf 'local structural artifact %s\n' "$fake_release_suffix" >"$fake_release_artifact"
  fi
  gpg --batch --quiet --armor --output "$fake_release_signature" \
    --detach-sign "$fake_release_artifact"
done
EOF
chmod +x "$release_test_fake_release_maven"

if ! GNUPGHOME="$release_test_gnupg_home" MAVEN_BIN="$release_test_fake_release_maven" \
    FAKE_RELEASE_MAVEN_LOG="$release_test_release_maven_log" \
    RELEASE_BUNDLE_FILE="$release_test_tmp_dir/local-structural-bundle.zip" \
    "$release_test_local_bundle_builder" v0.2.1 "$release_test_local_release_project/pom.xml" \
    >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected local bundle-only release validation to pass for a signed release bundle." >&2
  exit 1
fi

grep -Fq -- '-Prelease' "$release_test_release_maven_log"
grep -Fq -- '-Dcentral.skipPublishing=true' "$release_test_release_maven_log"
grep -Fq 'clean verify' "$release_test_release_maven_log"
if grep -Eq '(^|[[:space:]])(deploy|publish)([[:space:]]|$)' "$release_test_release_maven_log"; then
  echo "Upload-free local bundle validation must not execute a deploy or publish goal." >&2
  exit 1
fi
grep -Fq 'Local structural release-bundle validation passed for v0.2.1.' "$release_test_output_file"
grep -Fq 'Only the protected publish job validates the Sonatype plugin-produced bundle.' \
  "$release_test_output_file"

printf 'not a signature\n' \
  >"$release_test_signed_root/$release_test_coordinate_path/rewrite-spring-to-helidon-0.2.1.jar.asc"
(
  cd "$release_test_signed_root"
  jar --create --file "$release_test_tmp_dir/bad-signature-bundle.zip" .
)

if GNUPGHOME="$release_test_gnupg_home" "$release_test_bundle_validator" 0.2.1 \
    "$release_test_tmp_dir/bad-signature-bundle.zip" >"$release_test_output_file" 2>&1; then
  echo "Expected the release bundle validator to reject an invalid detached signature." >&2
  exit 1
fi

grep -Fq 'GPG verification failed for io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1.jar.asc.' \
  "$release_test_output_file"

release_test_fake_reproducible_maven="$release_test_tmp_dir/fake-reproducible-maven"
release_test_reproducible_maven_log="$release_test_tmp_dir/reproducible-maven.log"
cat >"$release_test_fake_reproducible_maven" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

fake_reproducible_pom=
for fake_reproducible_argument_index in "$@"; do
  printf '%s\n' "$fake_reproducible_argument_index" >>"$FAKE_REPRODUCIBLE_MAVEN_LOG"
done

while [[ $# -gt 0 ]]; do
  case "$1" in
    -f)
      shift
      fake_reproducible_pom=$1
      ;;
  esac
  shift
done

test -n "$fake_reproducible_pom"
fake_reproducible_project_dir=$(CDPATH= cd -- "$(dirname -- "$fake_reproducible_pom")" && pwd)
fake_reproducible_prefix="$fake_reproducible_project_dir/target/rewrite-spring-to-helidon-0.2.1"
mkdir -p "$fake_reproducible_project_dir/target"

for fake_reproducible_suffix in .jar -sources.jar -javadoc.jar .pom; do
  fake_reproducible_signature="$fake_reproducible_prefix$fake_reproducible_suffix.asc"
  if [[ "$fake_reproducible_suffix" == .pom ]]; then
    fake_reproducible_artifact=$fake_reproducible_pom
  else
    fake_reproducible_artifact="$fake_reproducible_prefix$fake_reproducible_suffix"
    printf 'reproducible artifact %s\n' "$fake_reproducible_suffix" >"$fake_reproducible_artifact"
  fi

  if [[ "${FAKE_REPRODUCIBLE_DIFFER:-false}" == true \
      && "$fake_reproducible_project_dir" == */build-2/project/* \
      && "$fake_reproducible_suffix" == -javadoc.jar ]]; then
    printf 'non-reproducible second build\n' >>"$fake_reproducible_artifact"
  fi

  gpg --batch --quiet --armor --output "$fake_reproducible_signature" \
    --detach-sign "$fake_reproducible_artifact"
done
EOF
chmod +x "$release_test_fake_reproducible_maven"

if ! MAVEN_BIN="$release_test_fake_reproducible_maven" \
    FAKE_REPRODUCIBLE_MAVEN_LOG="$release_test_reproducible_maven_log" \
    "$release_test_reproducibility" v0.2.1 "$release_test_reproducible_pom" \
    >"$release_test_output_file" 2>&1; then
  cat "$release_test_output_file" >&2
  echo "Expected two clean release builds with equal primary artifacts to be reproducible." >&2
  exit 1
fi

grep -Fq 'Reproducible release artifacts verified for v0.2.1 across two isolated, upload-free builds.' \
  "$release_test_output_file"
grep -Fq -- '-Dcentral.skipPublishing=true' "$release_test_reproducible_maven_log"
if [[ $(grep -Fxc 'clean' "$release_test_reproducible_maven_log") -ne 2 \
    || $(grep -Fxc 'verify' "$release_test_reproducible_maven_log") -ne 2 ]]; then
  echo "Expected the reproducibility seam to execute exactly two clean, upload-free verify builds." >&2
  exit 1
fi
if grep -Eq '^(deploy|publish)$' "$release_test_reproducible_maven_log"; then
  echo "Upload-free reproducibility verification must not execute a deploy or publish goal." >&2
  exit 1
fi

if MAVEN_BIN="$release_test_fake_reproducible_maven" \
    FAKE_REPRODUCIBLE_MAVEN_LOG="$release_test_reproducible_maven_log" \
    FAKE_REPRODUCIBLE_DIFFER=true \
    "$release_test_reproducibility" v0.2.1 "$release_test_reproducible_pom" \
    >"$release_test_output_file" 2>&1; then
  echo "Expected a byte difference between release artifacts to fail reproducibility verification." >&2
  exit 1
fi

grep -Fq 'Release artifact is not reproducible: javadoc.jar' "$release_test_output_file"

echo "Release tooling tests passed."
