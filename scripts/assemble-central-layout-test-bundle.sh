#!/usr/bin/env bash
set -euo pipefail

central_test_bundle_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
central_test_bundle_project_dir=$(CDPATH= cd -- "$central_test_bundle_script_dir/.." && pwd)

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 <MAJOR.MINOR.PATCH> [pom.xml] [test-bundle.zip]" >&2
  exit 2
fi

central_test_bundle_version=$1
central_test_bundle_pom=${2:-"$central_test_bundle_project_dir/pom.xml"}
central_test_bundle_basedir=$(CDPATH= cd -- "$(dirname -- "$central_test_bundle_pom")" && pwd)
central_test_bundle_output=${3:-"$central_test_bundle_basedir/target/release-validation/central-layout-test-bundle.zip"}

if [[ ! "$central_test_bundle_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Central-layout test bundle version must use the exact stable form MAJOR.MINOR.PATCH: $central_test_bundle_version" >&2
  exit 1
fi

if [[ ! -s "$central_test_bundle_pom" ]]; then
  echo "Release POM does not exist or is empty: $central_test_bundle_pom" >&2
  exit 1
fi

central_test_bundle_tmp_dir=$(mktemp -d)
trap 'rm -rf "$central_test_bundle_tmp_dir"' EXIT
central_test_bundle_coordinate_path="io/github/devansh-ops/rewrite-spring-to-helidon/$central_test_bundle_version"
central_test_bundle_staging_dir="$central_test_bundle_tmp_dir/$central_test_bundle_coordinate_path"
central_test_bundle_prefix="$central_test_bundle_staging_dir/rewrite-spring-to-helidon-$central_test_bundle_version"
central_test_bundle_target_prefix="$central_test_bundle_basedir/target/rewrite-spring-to-helidon-$central_test_bundle_version"
mkdir -p "$central_test_bundle_staging_dir" "$(dirname -- "$central_test_bundle_output")"

for central_test_bundle_suffix in .jar -sources.jar -javadoc.jar .pom; do
  central_test_bundle_destination="$central_test_bundle_prefix$central_test_bundle_suffix"
  if [[ "$central_test_bundle_suffix" == .pom ]]; then
    central_test_bundle_source=$central_test_bundle_pom
  else
    central_test_bundle_source="$central_test_bundle_target_prefix$central_test_bundle_suffix"
  fi
  central_test_bundle_signature="$central_test_bundle_target_prefix$central_test_bundle_suffix.asc"

  if [[ ! -s "$central_test_bundle_source" ]]; then
    echo "Missing primary release artifact for structural bundle: $central_test_bundle_source" >&2
    exit 1
  fi
  if [[ ! -s "$central_test_bundle_signature" ]]; then
    echo "Missing detached release signature for structural bundle: $central_test_bundle_signature" >&2
    exit 1
  fi

  cp "$central_test_bundle_source" "$central_test_bundle_destination"
  cp "$central_test_bundle_signature" "$central_test_bundle_destination.asc"
  md5sum "$central_test_bundle_destination" | awk '{ print $1 }' >"$central_test_bundle_destination.md5"
  sha1sum "$central_test_bundle_destination" | awk '{ print $1 }' >"$central_test_bundle_destination.sha1"
  sha256sum "$central_test_bundle_destination" | awk '{ print $1 }' >"$central_test_bundle_destination.sha256"
  sha512sum "$central_test_bundle_destination" | awk '{ print $1 }' >"$central_test_bundle_destination.sha512"
done

(
  cd "$central_test_bundle_tmp_dir"
  jar --create --file "$central_test_bundle_output" .
)

echo "Created Central-layout structural test bundle: $central_test_bundle_output"
echo "This local surrogate does not prove Sonatype Central plugin staging behavior."
