#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_PROPS="$PROJECT_ROOT/gradle.properties"

NO_PUSH=0

# --- Parse arguments ---
BUMP=""
for arg in "$@"; do
  case "$arg" in
    --no-push) NO_PUSH=1 ;;
    patch|minor|major)
      if [[ -n "$BUMP" ]]; then
        echo "Error: bump type already set to '$BUMP', got duplicate '$arg'" >&2
        exit 1
      fi
      BUMP="$arg"
      ;;
    *)
      echo "Error: unknown argument '$arg'" >&2
      echo "Usage: $0 <patch|minor|major> [--no-push]" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$BUMP" ]]; then
  echo "Error: bump type is required (patch, minor, or major)" >&2
  echo "Usage: $0 <patch|minor|major> [--no-push]" >&2
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

# --- Read current version ---
CURRENT_VERSION="$(grep -E '^mod_version=' "$GRADLE_PROPS" | cut -d'=' -f2)"
if [[ -z "$CURRENT_VERSION" ]]; then
  echo "Error: could not read mod_version from $GRADLE_PROPS" >&2
  exit 1
fi

echo "Current version: $CURRENT_VERSION"

# --- Parse semver components ---
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

if [[ -z "$MAJOR" || -z "$MINOR" || -z "$PATCH" ]]; then
  echo "Error: version '$CURRENT_VERSION' is not valid semver (expected X.Y.Z)" >&2
  exit 1
fi

# --- Bump version ---
case "$BUMP" in
  patch) NEW_VERSION="$MAJOR.$MINOR.$((PATCH + 1))" ;;
  minor) NEW_VERSION="$MAJOR.$((MINOR + 1)).0" ;;
  major) NEW_VERSION="$((MAJOR + 1)).0.0" ;;
esac

echo "Bumping $BUMP: $CURRENT_VERSION -> $NEW_VERSION"

# --- Update gradle.properties ---
echo "Updating $GRADLE_PROPS..."
sed -i "s/^mod_version=.*/mod_version=$NEW_VERSION/" "$GRADLE_PROPS"

# --- Git commit ---
echo "Committing version bump..."
git -C "$PROJECT_ROOT" add gradle.properties
git -C "$PROJECT_ROOT" commit -m "chore: bump version to $NEW_VERSION"

# --- Git tag ---
TAG="v$NEW_VERSION"
echo "Creating tag: $TAG"
git -C "$PROJECT_ROOT" tag "$TAG"

# --- Push ---
if [[ "$NO_PUSH" -eq 1 ]]; then
  echo "Skipping push (--no-push)."
else
  echo "Pushing commit and tag to origin..."
  git -C "$PROJECT_ROOT" push origin
  git -C "$PROJECT_ROOT" push origin "$TAG"
fi

echo "Release $NEW_VERSION complete."
