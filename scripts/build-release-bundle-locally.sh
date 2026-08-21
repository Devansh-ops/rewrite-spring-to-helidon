#!/usr/bin/env bash
set -euo pipefail

local_bundle_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
local_bundle_project_dir=$(CDPATH= cd -- "$local_bundle_script_dir/.." && pwd)

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <vMAJOR.MINOR.PATCH> [pom.xml]" >&2
  exit 2
fi

local_bundle_tag=$1
local_bundle_pom=${2:-"$local_bundle_project_dir/pom.xml"}
local_bundle_maven=${MAVEN_BIN:-"$local_bundle_project_dir/mvnw"}
local_bundle_file=${RELEASE_BUNDLE_FILE:-"$(dirname -- "$local_bundle_pom")/target/release-validation/central-layout-test-bundle.zip"}

"$local_bundle_script_dir/validate-release-tag.sh" "$local_bundle_tag" "$local_bundle_pom"
local_bundle_version=${local_bundle_tag#v}

"$local_bundle_maven" --batch-mode --no-transfer-progress -f "$local_bundle_pom" \
  -Prelease -Dcentral.skipPublishing=true clean verify

"$local_bundle_script_dir/assemble-central-layout-test-bundle.sh" \
  "$local_bundle_version" "$local_bundle_pom" "$local_bundle_file"
"$local_bundle_script_dir/validate-release-bundle.sh" "$local_bundle_version" "$local_bundle_file"

echo "Local structural release-bundle validation passed for $local_bundle_tag. Nothing was uploaded."
echo "Only the protected publish job validates the Sonatype plugin-produced bundle."
