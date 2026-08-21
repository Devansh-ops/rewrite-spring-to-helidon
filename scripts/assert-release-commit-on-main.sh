#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <40-character-commit-sha>" >&2
  exit 2
fi

release_main_commit=$1
release_main_git=${GIT_BIN:-git}

if [[ ! "$release_main_commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Release commit must be an exact lowercase 40-character Git SHA: $release_main_commit" >&2
  exit 1
fi

if "$release_main_git" merge-base --is-ancestor "$release_main_commit" origin/main; then
  echo "Release commit $release_main_commit is reachable from origin/main."
else
  release_main_status=$?
  if [[ $release_main_status -eq 1 ]]; then
    echo "Release commit $release_main_commit is not reachable from origin/main." >&2
  else
    echo "Could not prove that release commit $release_main_commit is reachable from origin/main." >&2
  fi
  exit 1
fi
