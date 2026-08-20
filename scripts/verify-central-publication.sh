#!/usr/bin/env bash
set -euo pipefail

central_readback_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
central_readback_project_dir=$(CDPATH= cd -- "$central_readback_script_dir/.." && pwd)

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 <MAJOR.MINOR.PATCH> [submitted-central-bundle.zip] [local-project-directory]" >&2
  exit 2
fi

central_readback_version=$1
central_readback_submitted_bundle=${2:-"$central_readback_project_dir/target/central-publishing/central-bundle.zip"}
central_readback_local_project=${3:-"$central_readback_project_dir"}
central_readback_repository_url=${CENTRAL_REPOSITORY_URL:-https://repo.maven.apache.org/maven2}
central_readback_curl=${CURL_BIN:-curl}
central_readback_max_attempts=${CENTRAL_READBACK_MAX_ATTEMPTS:-120}
central_readback_retry_seconds=${CENTRAL_READBACK_RETRY_SECONDS:-15}
central_readback_timeout_seconds=${CENTRAL_READBACK_TIMEOUT_SECONDS:-1800}
central_readback_request_timeout_seconds=${CENTRAL_READBACK_REQUEST_TIMEOUT_SECONDS:-10}

if [[ ! "$central_readback_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Central readback version must use the exact stable form MAJOR.MINOR.PATCH: $central_readback_version" >&2
  exit 1
fi

if [[ ! "$central_readback_max_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "CENTRAL_READBACK_MAX_ATTEMPTS must be a positive integer." >&2
  exit 2
fi

if [[ ! "$central_readback_retry_seconds" =~ ^[0-9]+$ ]]; then
  echo "CENTRAL_READBACK_RETRY_SECONDS must be a non-negative integer." >&2
  exit 2
fi

if [[ ! "$central_readback_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "CENTRAL_READBACK_TIMEOUT_SECONDS must be a positive integer." >&2
  exit 2
fi

if [[ ! "$central_readback_request_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "CENTRAL_READBACK_REQUEST_TIMEOUT_SECONDS must be a positive integer." >&2
  exit 2
fi

central_readback_coordinate="io.github.devansh-ops:rewrite-spring-to-helidon:$central_readback_version"
central_readback_coordinate_path="io/github/devansh-ops/rewrite-spring-to-helidon/$central_readback_version"
central_readback_prefix="$central_readback_coordinate_path/rewrite-spring-to-helidon-$central_readback_version"
central_readback_pom_url="${central_readback_repository_url%/}/$central_readback_prefix.pom"
central_readback_deadline=$((SECONDS + central_readback_timeout_seconds))

central_readback_tmp_dir=$(mktemp -d)
trap 'rm -rf "$central_readback_tmp_dir"' EXIT
central_readback_expected_dir="$central_readback_tmp_dir/submitted"
mkdir -p "$central_readback_expected_dir"

"$central_readback_script_dir/validate-release-bundle.sh" \
  "$central_readback_version" "$central_readback_submitted_bundle"

for central_readback_suffix in .jar -sources.jar -javadoc.jar .pom; do
  case "$central_readback_suffix" in
    .jar)
      central_readback_label=main.jar
      central_readback_local_file="$central_readback_local_project/target/rewrite-spring-to-helidon-$central_readback_version.jar"
      ;;
    -sources.jar)
      central_readback_label=sources.jar
      central_readback_local_file="$central_readback_local_project/target/rewrite-spring-to-helidon-$central_readback_version-sources.jar"
      ;;
    -javadoc.jar)
      central_readback_label=javadoc.jar
      central_readback_local_file="$central_readback_local_project/target/rewrite-spring-to-helidon-$central_readback_version-javadoc.jar"
      ;;
    .pom)
      central_readback_label=pom.xml
      central_readback_local_file="$central_readback_local_project/pom.xml"
      ;;
  esac

  central_readback_submitted_file="$central_readback_expected_dir/$central_readback_label"
  unzip -p "$central_readback_submitted_bundle" \
    "$central_readback_prefix$central_readback_suffix" >"$central_readback_submitted_file"

  if [[ ! -f "$central_readback_local_file" ]]; then
    echo "Local release artifact does not exist: $central_readback_local_file" >&2
    exit 1
  fi

  if ! cmp -s "$central_readback_submitted_file" "$central_readback_local_file"; then
    echo "Submitted bundle bytes differ from local release artifact: $central_readback_label" >&2
    exit 1
  fi
done

central_readback_attempt=1
while true; do
  if (( SECONDS >= central_readback_deadline )); then
    echo "Shared Maven Central readback timeout expired while waiting for $central_readback_coordinate." >&2
    exit 1
  fi

  central_readback_http_status=000
  if central_readback_http_result=$("$central_readback_curl" --silent --show-error --location \
      --connect-timeout "$central_readback_request_timeout_seconds" \
      --max-time "$central_readback_request_timeout_seconds" \
      --output /dev/null --write-out '%{http_code}' "$central_readback_pom_url"); then
    central_readback_http_status=$central_readback_http_result
  fi

  if [[ "$central_readback_http_status" == 200 ]]; then
    break
  fi

  if (( central_readback_attempt >= central_readback_max_attempts )); then
    echo "Maven Central did not expose $central_readback_coordinate after $central_readback_attempt attempts; last HTTP status was $central_readback_http_status." >&2
    exit 1
  fi

  sleep "$central_readback_retry_seconds"
  central_readback_attempt=$((central_readback_attempt + 1))
done

central_readback_root="$central_readback_tmp_dir/repository"
central_readback_artifact_dir="$central_readback_root/$central_readback_coordinate_path"
central_readback_bundle="$central_readback_tmp_dir/central-readback-bundle.zip"
mkdir -p "$central_readback_artifact_dir"
central_readback_bundle_entries=()

central_readback_download() {
  local central_readback_relative_path=$1
  local central_readback_destination="$central_readback_root/$central_readback_relative_path"
  local central_readback_download_attempt=1

  if (( SECONDS >= central_readback_deadline )); then
    echo "Shared Maven Central readback timeout expired before downloading $central_readback_relative_path." >&2
    return 1
  fi

  while true; do
    if (( SECONDS >= central_readback_deadline )); then
      echo "Shared Maven Central readback timeout expired while downloading $central_readback_relative_path." >&2
      return 1
    fi

    if "$central_readback_curl" --fail --silent --show-error --location \
        --connect-timeout "$central_readback_request_timeout_seconds" \
        --max-time "$central_readback_request_timeout_seconds" \
        --output "$central_readback_destination" \
        "${central_readback_repository_url%/}/$central_readback_relative_path"; then
      break
    fi

    if (( central_readback_download_attempt >= central_readback_max_attempts )); then
      echo "Could not download $central_readback_relative_path from Maven Central after $central_readback_download_attempt attempts." >&2
      return 1
    fi
    sleep "$central_readback_retry_seconds"
    central_readback_download_attempt=$((central_readback_download_attempt + 1))
  done

  if [[ ! -s "$central_readback_destination" ]]; then
    echo "Maven Central returned an empty or missing file: $central_readback_relative_path" >&2
    return 1
  fi
  central_readback_bundle_entries+=("$central_readback_relative_path")
}

for central_readback_suffix in .jar -sources.jar -javadoc.jar .pom; do
  central_readback_primary="$central_readback_prefix$central_readback_suffix"
  central_readback_download "$central_readback_primary"
  central_readback_download "$central_readback_primary.asc"
  for central_readback_checksum_suffix in md5 sha1 sha256 sha512; do
    central_readback_download "$central_readback_primary.$central_readback_checksum_suffix"
  done
done

if (( ${#central_readback_bundle_entries[@]} != 24 )); then
  echo "Expected 24 nonempty Central readback files, found ${#central_readback_bundle_entries[@]}." >&2
  exit 1
fi

(
  cd "$central_readback_root"
  jar --create --file "$central_readback_bundle" "${central_readback_bundle_entries[@]}"
)

if ! "$central_readback_script_dir/validate-release-bundle.sh" \
    "$central_readback_version" "$central_readback_bundle"; then
  echo "Downloaded Central bundle entries were:" >&2
  jar --list --file "$central_readback_bundle" >&2 || true
  exit 1
fi

for central_readback_suffix in .jar -sources.jar -javadoc.jar .pom; do
  case "$central_readback_suffix" in
    .jar) central_readback_label=main.jar ;;
    -sources.jar) central_readback_label=sources.jar ;;
    -javadoc.jar) central_readback_label=javadoc.jar ;;
    .pom) central_readback_label=pom.xml ;;
  esac
  central_readback_downloaded_file="$central_readback_root/$central_readback_prefix$central_readback_suffix"
  if ! cmp -s "$central_readback_expected_dir/$central_readback_label" \
      "$central_readback_downloaded_file"; then
    echo "Maven Central bytes differ from submitted bundle: $central_readback_label" >&2
    exit 1
  fi
  sha256sum "$central_readback_downloaded_file"
done

echo "Maven Central readback passed for $central_readback_coordinate."
