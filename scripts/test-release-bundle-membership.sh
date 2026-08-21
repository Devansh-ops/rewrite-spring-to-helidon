#!/usr/bin/env bash
set -euo pipefail

membership_test_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
membership_test_tmp_dir=$(mktemp -d)
trap 'rm -rf "$membership_test_tmp_dir"' EXIT

membership_test_version=0.2.1
membership_test_coordinate_path="io/github/devansh-ops/rewrite-spring-to-helidon/$membership_test_version"
membership_test_prefix="$membership_test_coordinate_path/rewrite-spring-to-helidon-$membership_test_version"
membership_test_root="$membership_test_tmp_dir/repository"
membership_test_bundle="$membership_test_tmp_dir/large-central-bundle.zip"
membership_test_fake_unzip="$membership_test_tmp_dir/sigpipe-unzip"
membership_test_real_unzip=$(command -v unzip)

mkdir -p "$membership_test_root/$membership_test_coordinate_path"

for membership_test_suffix in .jar -sources.jar -javadoc.jar .pom; do
  membership_test_artifact="$membership_test_root/$membership_test_prefix$membership_test_suffix"
  printf 'membership fixture %s\n' "$membership_test_suffix" >"$membership_test_artifact"
  printf 'structural signature fixture\n' >"$membership_test_artifact.asc"
  md5sum "$membership_test_artifact" | awk '{ print $1 }' >"$membership_test_artifact.md5"
  sha1sum "$membership_test_artifact" | awk '{ print $1 }' >"$membership_test_artifact.sha1"
  sha256sum "$membership_test_artifact" | awk '{ print $1 }' >"$membership_test_artifact.sha256"
  sha512sum "$membership_test_artifact" | awk '{ print $1 }' >"$membership_test_artifact.sha512"
done

(
  cd "$membership_test_root"
  jar --create --file "$membership_test_bundle" .
)

cat >"$membership_test_fake_unzip" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == -Z1 && -p /dev/stdout ]]; then
  printf '%s\n' 'io/github/devansh-ops/rewrite-spring-to-helidon/0.2.1/rewrite-spring-to-helidon-0.2.1.jar'
  exit 141
fi

exec "$MEMBERSHIP_TEST_REAL_UNZIP" "$@"
EOF
chmod +x "$membership_test_fake_unzip"

RELEASE_GPG_VERIFY=false UNZIP_BIN="$membership_test_fake_unzip" \
  MEMBERSHIP_TEST_REAL_UNZIP="$membership_test_real_unzip" \
  "$membership_test_script_dir/validate-release-bundle.sh" \
  "$membership_test_version" "$membership_test_bundle"

echo "Release-bundle membership validation passed without a pipefail/SIGPIPE false negative."
