#!/usr/bin/env bash
set -euo pipefail

DRY_RUN=false
VERSION=""

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        *) VERSION="$arg" ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    echo "Usage: ./release.sh <version> [--dry-run]  (e.g. ./release.sh 1.2.3)"
    exit 1
fi

CURRENT_VERSION=$(grep "^VERSION=" gradle.properties | cut -d= -f2)
BRANCH=$(git branch --show-current)

echo "--------------------------------------"
echo "  Release summary"
echo "--------------------------------------"
echo "  Version:  $CURRENT_VERSION  →  $VERSION"
echo "  Tag:      v$VERSION"
echo "  Branch:   $BRANCH"
echo "  Commits:  git commit -m \"chore: release $VERSION\""
echo "  Push:     git push origin $BRANCH --tags"
echo "--------------------------------------"

if [[ "$DRY_RUN" == true ]]; then
    echo "(dry run — no changes made)"
    exit 0
fi

read -rp "Apply and push? [y/N] " confirm
if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "Aborted."
    exit 0
fi

echo "Running tests..."
./gradlew testLibraries

sed -i "s/^VERSION=.*/VERSION=$VERSION/" gradle.properties
git add gradle.properties
git commit -m "chore: release $VERSION"
git tag "v$VERSION"

read -rp "Push to origin now? [y/N] " push_confirm
if [[ "$push_confirm" == "y" || "$push_confirm" == "Y" ]]; then
    git push origin "$BRANCH" --tags
    echo "Pushed. GitHub Actions will publish the artifacts."
else
    echo "Not pushed. Run manually: git push origin $BRANCH --tags"
fi
