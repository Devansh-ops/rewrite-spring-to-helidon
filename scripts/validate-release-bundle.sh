#!/usr/bin/env bash
set -euo pipefail

release_bundle_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
release_bundle_project_dir=$(CDPATH= cd -- "$release_bundle_script_dir/.." && pwd)

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <MAJOR.MINOR.PATCH> [central-bundle.zip]" >&2
  exit 2
fi

release_bundle_version=$1
release_bundle_file=${2:-"$release_bundle_project_dir/target/central-publishing/central-bundle.zip"}
release_bundle_coordinate_path="io/github/devansh-ops/rewrite-spring-to-helidon/$release_bundle_version"
release_bundle_prefix="$release_bundle_coordinate_path/rewrite-spring-to-helidon-$release_bundle_version"
release_bundle_gpg_verify=${RELEASE_GPG_VERIFY:-true}
release_bundle_unzip=${UNZIP_BIN:-unzip}

if [[ ! "$release_bundle_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Release bundle version must use the exact stable form MAJOR.MINOR.PATCH: $release_bundle_version" >&2
  exit 1
fi

case "$release_bundle_gpg_verify" in
  true|false) ;;
  *)
    echo "RELEASE_GPG_VERIFY must be true or false." >&2
    exit 2
    ;;
esac

if [[ ! -s "$release_bundle_file" ]]; then
  echo "Release bundle does not exist or is empty: $release_bundle_file" >&2
  exit 1
fi

release_bundle_work_dir=$(mktemp -d)
trap 'rm -rf "$release_bundle_work_dir"' EXIT
release_bundle_entries_file="$release_bundle_work_dir/archive-entries.txt"
release_bundle_verify_dir="$release_bundle_work_dir/verified-artifacts"
mkdir -p "$release_bundle_verify_dir"

if ! "$release_bundle_unzip" -Z1 "$release_bundle_file" >"$release_bundle_entries_file"; then
  echo "Could not list release bundle entries: $release_bundle_file" >&2
  exit 1
fi

release_bundle_require_entry() {
  local release_bundle_required_entry=$1
  if ! grep -Fxq -- "$release_bundle_required_entry" "$release_bundle_entries_file"; then
    echo "Missing required release bundle entry: $release_bundle_required_entry" >&2
    return 1
  fi
}

for release_bundle_primary_entry in \
    "$release_bundle_prefix.jar" \
    "$release_bundle_prefix-sources.jar" \
    "$release_bundle_prefix-javadoc.jar" \
    "$release_bundle_prefix.pom"; do
  release_bundle_require_entry "$release_bundle_primary_entry"
done

for release_bundle_suffix in .jar -sources.jar -javadoc.jar .pom; do
  release_bundle_signature="$release_bundle_prefix$release_bundle_suffix.asc"
  release_bundle_require_entry "$release_bundle_signature"

  for release_bundle_checksum_suffix in md5 sha1 sha256 sha512; do
    release_bundle_checksum="$release_bundle_prefix$release_bundle_suffix.$release_bundle_checksum_suffix"
    release_bundle_require_entry "$release_bundle_checksum"

    release_bundle_expected_checksum=$("$release_bundle_unzip" -p "$release_bundle_file" "$release_bundle_checksum" | tr -d '[:space:]')
    case "$release_bundle_checksum_suffix" in
      md5)
        release_bundle_actual_checksum=$("$release_bundle_unzip" -p "$release_bundle_file" "$release_bundle_prefix$release_bundle_suffix" | md5sum | awk '{ print $1 }')
        ;;
      sha1)
        release_bundle_actual_checksum=$("$release_bundle_unzip" -p "$release_bundle_file" "$release_bundle_prefix$release_bundle_suffix" | sha1sum | awk '{ print $1 }')
        ;;
      sha256)
        release_bundle_actual_checksum=$("$release_bundle_unzip" -p "$release_bundle_file" "$release_bundle_prefix$release_bundle_suffix" | sha256sum | awk '{ print $1 }')
        ;;
      sha512)
        release_bundle_actual_checksum=$("$release_bundle_unzip" -p "$release_bundle_file" "$release_bundle_prefix$release_bundle_suffix" | sha512sum | awk '{ print $1 }')
        ;;
    esac

    if [[ "$release_bundle_expected_checksum" != "$release_bundle_actual_checksum" ]]; then
      echo "Checksum mismatch for $release_bundle_checksum." >&2
      exit 1
    fi
  done

  if [[ "$release_bundle_gpg_verify" == true ]]; then
    release_bundle_artifact_file="$release_bundle_verify_dir/artifact$release_bundle_suffix"
    release_bundle_signature_file="$release_bundle_artifact_file.asc"
    "$release_bundle_unzip" -p "$release_bundle_file" "$release_bundle_prefix$release_bundle_suffix" >"$release_bundle_artifact_file"
    "$release_bundle_unzip" -p "$release_bundle_file" "$release_bundle_signature" >"$release_bundle_signature_file"
    if ! gpg --batch --verify "$release_bundle_signature_file" "$release_bundle_artifact_file" >/dev/null 2>&1; then
      echo "GPG verification failed for $release_bundle_signature." >&2
      exit 1
    fi
  fi
done

echo "Release bundle contains the main, sources, javadoc, POM, detached signatures, and checksums for version $release_bundle_version."
