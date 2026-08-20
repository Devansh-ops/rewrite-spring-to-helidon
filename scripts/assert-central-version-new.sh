#!/usr/bin/env bash
set -euo pipefail

central_guard_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
central_guard_project_dir=$(CDPATH= cd -- "$central_guard_script_dir/.." && pwd)

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <MAJOR.MINOR.PATCH> [pom.xml]" >&2
  exit 2
fi

central_guard_version=$1
central_guard_pom=${2:-"$central_guard_project_dir/pom.xml"}
central_guard_repository_url=${CENTRAL_REPOSITORY_URL:-https://repo.maven.apache.org/maven2}
central_guard_curl=${CURL_BIN:-curl}

if [[ ! "$central_guard_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Maven Central version must use the exact stable form MAJOR.MINOR.PATCH: $central_guard_version" >&2
  exit 1
fi

central_guard_pom_version=$("$central_guard_project_dir/mvnw" --quiet --no-transfer-progress \
  -f "$central_guard_pom" help:evaluate -Dexpression=project.version -DforceStdout)
central_guard_group_id=$("$central_guard_project_dir/mvnw" --quiet --no-transfer-progress \
  -f "$central_guard_pom" help:evaluate -Dexpression=project.groupId -DforceStdout)
central_guard_artifact_id=$("$central_guard_project_dir/mvnw" --quiet --no-transfer-progress \
  -f "$central_guard_pom" help:evaluate -Dexpression=project.artifactId -DforceStdout)

if [[ "$central_guard_pom_version" != "$central_guard_version" ]]; then
  echo "Requested Central version $central_guard_version does not match Maven version $central_guard_pom_version." >&2
  exit 1
fi

central_guard_group_path=${central_guard_group_id//./\/}
central_guard_coordinate="$central_guard_group_id:$central_guard_artifact_id:$central_guard_version"
central_guard_artifact_url="${central_guard_repository_url%/}/$central_guard_group_path/$central_guard_artifact_id/$central_guard_version/$central_guard_artifact_id-$central_guard_version.pom"

if ! central_guard_http_status=$("$central_guard_curl" --silent --show-error --location \
    --output /dev/null --write-out '%{http_code}' "$central_guard_artifact_url"); then
  echo "Could not prove that $central_guard_coordinate is unpublished: Maven Central could not be queried." >&2
  exit 1
fi

case "$central_guard_http_status" in
  404)
    echo "Maven Central does not contain $central_guard_coordinate; the immutable version is available."
    ;;
  200)
    echo "Maven Central already contains $central_guard_coordinate; published versions are immutable." >&2
    exit 1
    ;;
  *)
    echo "Could not prove that $central_guard_coordinate is unpublished: Maven Central returned HTTP $central_guard_http_status." >&2
    exit 1
    ;;
esac
