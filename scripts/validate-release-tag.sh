#!/usr/bin/env bash
set -euo pipefail

release_tag_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
release_tag_project_dir=$(CDPATH= cd -- "$release_tag_script_dir/.." && pwd)

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <vMAJOR.MINOR.PATCH> [pom.xml]" >&2
  exit 2
fi

release_tag=$1
release_tag_pom=${2:-"$release_tag_project_dir/pom.xml"}

if [[ ! "$release_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Release tag must use the exact stable form vMAJOR.MINOR.PATCH: $release_tag" >&2
  exit 1
fi

release_tag_version=$("$release_tag_project_dir/mvnw" --quiet --no-transfer-progress \
  -f "$release_tag_pom" help:evaluate -Dexpression=project.version -DforceStdout)
release_tag_scm=$("$release_tag_project_dir/mvnw" --quiet --no-transfer-progress \
  -f "$release_tag_pom" help:evaluate -Dexpression=project.scm.tag -DforceStdout)

if [[ "$release_tag_version" == *-SNAPSHOT ]]; then
  echo "Maven Central releases cannot use a SNAPSHOT version: $release_tag_version." >&2
  exit 1
fi

if [[ "$release_tag" != "v$release_tag_version" ]]; then
  echo "Release tag $release_tag does not match Maven version $release_tag_version." >&2
  exit 1
fi

if [[ "$release_tag_scm" != "$release_tag" ]]; then
  echo "Maven SCM tag $release_tag_scm does not match release tag $release_tag." >&2
  exit 1
fi

echo "Release tag $release_tag matches Maven version $release_tag_version and SCM tag."
