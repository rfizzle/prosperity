#!/usr/bin/env bash
set -euo pipefail

# The pushed v* tag is the single source of version truth (see AGENTS.md
# "Version scheme"): the release workflow injects the tag as the build version.
# Releasing is therefore just creating and pushing a tag — this script never
# edits mod_version in gradle.properties (that stays the 0.0.0 dev base) and
# never opens a "set version" commit.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

NO_PUSH=0

# --- Parse arguments ---
# One positional: a bump keyword (patch|minor|major) or an explicit SemVer version,
# which may carry a pre-release suffix — v1.0.0-beta.1 is how a beta is cut.
TARGET=""
# Initialised so the unassigned-version guard below can test it: under set -u a
# reference to an unset variable aborts first, with a worse message than the
# usage error the guard is there to print.
NEW_VERSION=""
for arg in "$@"; do
  case "$arg" in
    --no-push) NO_PUSH=1 ;;
    patch|minor|major|[0-9]*.[0-9]*.[0-9]*)
      if [[ -n "$TARGET" ]]; then
        echo "Error: version target already set to '$TARGET', got duplicate '$arg'" >&2
        exit 1
      fi
      TARGET="$arg"
      ;;
    *)
      echo "Error: unknown argument '$arg'" >&2
      echo "Usage: $0 <patch|minor|major|X.Y.Z[-prerelease]> [--no-push]" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$TARGET" ]]; then
  echo "Error: a version target is required (patch, minor, major, or X.Y.Z[-prerelease])" >&2
  echo "Usage: $0 <patch|minor|major|X.Y.Z[-prerelease]> [--no-push]" >&2
  exit 1
fi

# --- Check for dirty working tree ---
echo "Checking working tree..."
if ! git -C "$PROJECT_ROOT" diff --quiet || ! git -C "$PROJECT_ROOT" diff --cached --quiet; then
  echo "Error: working tree is dirty. Please commit or stash changes before releasing." >&2
  exit 1
fi

if [[ -n "$(git -C "$PROJECT_ROOT" ls-files --others --exclude-standard)" ]]; then
  echo "Error: there are untracked files. Please commit or remove them before releasing." >&2
  exit 1
fi

# --- Determine the new version ---
# Full SemVer, pre-release and build metadata included: a beta is cut as an explicit
# 1.0.0-beta.1, and a target the bump branch below cannot parse must land here rather
# than falling through it.
if [[ "$TARGET" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
  NEW_VERSION="$TARGET"
  echo "Explicit version: $NEW_VERSION"
else
  # Current version comes from the latest v* tag, not gradle.properties (which
  # holds only the dev base). No tags yet → the first release starts from 0.0.0.
  # Pre-release tags are excluded from the base on purpose: SemVer precedence for
  # them is not something `sort -V` gets right, and a pre-release should not advance
  # the release line. Cut the release that follows a beta as an explicit X.Y.Z.
  CURRENT_VERSION="$(git -C "$PROJECT_ROOT" tag --list 'v*' | sed 's/^v//' \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)"
  if [[ -z "$CURRENT_VERSION" ]]; then
    CURRENT_VERSION="0.0.0"
    echo "No existing v* tag; starting from $CURRENT_VERSION"
  else
    echo "Current version (latest tag): $CURRENT_VERSION"
  fi

  IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"
  case "$TARGET" in
    patch) NEW_VERSION="$MAJOR.$MINOR.$((PATCH + 1))" ;;
    minor) NEW_VERSION="$MAJOR.$((MINOR + 1)).0" ;;
    major) NEW_VERSION="$((MAJOR + 1)).0.0" ;;
  esac
  echo "Bumping $TARGET: $CURRENT_VERSION -> $NEW_VERSION"
fi

# Belt and braces. Every branch above either assigns NEW_VERSION or exits, but an
# unassigned one would tag a bare "v" — a wrong tag created silently, which the
# release workflow would then build and publish from. Refuse instead.
if [[ -z "$NEW_VERSION" ]]; then
  echo "Error: could not derive a version from '$TARGET'." >&2
  echo "Usage: $0 <patch|minor|major|X.Y.Z[-prerelease]> [--no-push]" >&2
  exit 1
fi

TAG="v$NEW_VERSION"

# --- Refuse to clobber an existing tag ---
if git -C "$PROJECT_ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "Error: tag '$TAG' already exists." >&2
  exit 1
fi

# --- Git tag ---
echo "Creating tag: $TAG"
git -C "$PROJECT_ROOT" tag -a "$TAG" -m "Respite $NEW_VERSION"

# --- Push ---
if [[ "$NO_PUSH" -eq 1 ]]; then
  echo "Skipping push (--no-push). Push it with: git push origin $TAG"
else
  echo "Pushing tag to origin..."
  git -C "$PROJECT_ROOT" push origin "$TAG"
fi

echo "Release $NEW_VERSION complete."
