#!/usr/bin/env bash
set -euo pipefail

reproducible_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
reproducible_project_dir=$(CDPATH= cd -- "$reproducible_script_dir/.." && pwd)

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <vMAJOR.MINOR.PATCH> [pom.xml]" >&2
  exit 2
fi

reproducible_tag=$1
reproducible_source_pom=${2:-"$reproducible_project_dir/pom.xml"}
reproducible_maven=${MAVEN_BIN:-"$reproducible_project_dir/mvnw"}

if [[ ! -f "$reproducible_source_pom" ]]; then
  echo "Release POM does not exist: $reproducible_source_pom" >&2
  exit 1
fi

reproducible_source_pom=$(realpath "$reproducible_source_pom")
case "$reproducible_source_pom" in
  "$reproducible_project_dir"/*)
    reproducible_pom_relative=${reproducible_source_pom#"$reproducible_project_dir"/}
    ;;
  *)
    echo "Release POM must be inside the project directory: $reproducible_source_pom" >&2
    exit 1
    ;;
esac

"$reproducible_script_dir/validate-release-tag.sh" "$reproducible_tag" "$reproducible_source_pom"
reproducible_version=${reproducible_tag#v}
reproducible_coordinate_path="io/github/devansh-ops/rewrite-spring-to-helidon/$reproducible_version"
reproducible_prefix="$reproducible_coordinate_path/rewrite-spring-to-helidon-$reproducible_version"

for reproducible_required_command in tar unzip cmp gpg gpgconf sha256sum; do
  if ! command -v "$reproducible_required_command" >/dev/null 2>&1; then
    echo "Reproducibility verification requires $reproducible_required_command." >&2
    exit 2
  fi
done

reproducible_tmp_dir=$(mktemp -d)
reproducible_gpg_homes=()
reproducible_cleanup() {
  local reproducible_gpg_home
  for reproducible_gpg_home in "${reproducible_gpg_homes[@]}"; do
    if [[ -d "$reproducible_gpg_home" ]]; then
      GNUPGHOME="$reproducible_gpg_home" gpgconf --kill gpg-agent >/dev/null 2>&1 || true
    fi
  done
  rm -rf "$reproducible_tmp_dir"
}
trap reproducible_cleanup EXIT

reproducible_artifact_names=(main.jar sources.jar javadoc.jar published.pom)
reproducible_artifact_suffixes=(.jar -sources.jar -javadoc.jar .pom)

for reproducible_build_number in 1 2; do
  reproducible_build_root="$reproducible_tmp_dir/build-$reproducible_build_number"
  reproducible_build_project="$reproducible_build_root/project"
  reproducible_build_home="$reproducible_build_root/home"
  reproducible_build_maven_home="$reproducible_build_root/maven-user-home"
  reproducible_build_repository="$reproducible_build_root/maven-repository"
  reproducible_build_gpg_home="$reproducible_build_root/gnupg"
  reproducible_build_compare="$reproducible_build_root/primary-artifacts"
  reproducible_gpg_homes+=("$reproducible_build_gpg_home")

  mkdir -p "$reproducible_build_project" "$reproducible_build_home" \
    "$reproducible_build_maven_home" "$reproducible_build_repository" \
    "$reproducible_build_compare"
  mkdir -m 700 "$reproducible_build_gpg_home"

  tar -C "$reproducible_project_dir" \
    --exclude=.git --exclude=.codex --exclude=target --exclude='*/target' \
    -cf - . | tar -C "$reproducible_build_project" -xf -

  reproducible_build_pom="$reproducible_build_project/$reproducible_pom_relative"
  reproducible_build_bundle="$(dirname -- "$reproducible_build_pom")/target/release-validation/central-layout-test-bundle.zip"

  GNUPGHOME="$reproducible_build_gpg_home" gpg --batch --quiet \
    --pinentry-mode loopback --passphrase '' \
    --quick-generate-key \
    "Reproducibility build $reproducible_build_number <reproducible-$reproducible_build_number@example.invalid>" \
    rsa2048 sign 1d

  HOME="$reproducible_build_home" \
    MAVEN_USER_HOME="$reproducible_build_maven_home" \
    GNUPGHOME="$reproducible_build_gpg_home" \
    MAVEN_GPG_PASSPHRASE='' \
    "$reproducible_maven" --batch-mode --no-transfer-progress \
    -f "$reproducible_build_pom" \
    -Duser.home="$reproducible_build_home" \
    -Dmaven.repo.local="$reproducible_build_repository" \
    -Prelease -Dcentral.skipPublishing=true -DskipTests clean verify

  "$reproducible_build_project/scripts/assemble-central-layout-test-bundle.sh" \
    "$reproducible_version" "$reproducible_build_pom" "$reproducible_build_bundle"

  GNUPGHOME="$reproducible_build_gpg_home" \
    "$reproducible_build_project/scripts/validate-release-bundle.sh" \
    "$reproducible_version" "$reproducible_build_bundle"

  for reproducible_artifact_index in "${!reproducible_artifact_names[@]}"; do
    reproducible_artifact_name=${reproducible_artifact_names[$reproducible_artifact_index]}
    reproducible_artifact_suffix=${reproducible_artifact_suffixes[$reproducible_artifact_index]}
    unzip -p "$reproducible_build_bundle" \
      "$reproducible_prefix$reproducible_artifact_suffix" \
      >"$reproducible_build_compare/$reproducible_artifact_name"
    if [[ ! -s "$reproducible_build_compare/$reproducible_artifact_name" ]]; then
      echo "Could not extract release artifact for reproducibility comparison: $reproducible_artifact_name" >&2
      exit 1
    fi
  done
done

for reproducible_artifact_name in "${reproducible_artifact_names[@]}"; do
  reproducible_first="$reproducible_tmp_dir/build-1/primary-artifacts/$reproducible_artifact_name"
  reproducible_second="$reproducible_tmp_dir/build-2/primary-artifacts/$reproducible_artifact_name"
  if ! cmp -s "$reproducible_first" "$reproducible_second"; then
    echo "Release artifact is not reproducible: $reproducible_artifact_name" >&2
    sha256sum "$reproducible_first" "$reproducible_second" >&2
    exit 1
  fi
  reproducible_digest=$(sha256sum "$reproducible_first" | awk '{ print $1 }')
  printf '%s  %s\n' "$reproducible_digest" "$reproducible_artifact_name"
done

echo "Reproducible release artifacts verified for $reproducible_tag across two isolated, upload-free builds."
