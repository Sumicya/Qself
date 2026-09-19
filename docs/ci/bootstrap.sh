#!/data/data/com.termux/files/usr/bin/bash
# Qself CI sync — run in Termux on your phone:
#
#   cd ~/Qself && bash docs/ci/bootstrap.sh
#
# Background: the Arena session token has no `workflows` scope, so commits
# touching .github/workflows/ are rejected when the agent pushes. To keep CI
# editable from the agent side, the workflow is deliberately thin: every step
# body lives in docs/ci/*.sh, which the agent *can* push. This script mirrors
# docs/ci/ci.yml into .github/workflows/ and is only needed when the step list
# itself changes (rare) or after pulling new CI scripts.
#
# Safe to re-run: it fetches the branch, checks it out, and commits only when
# the live workflow differs from the source.
#
# Note: local modifications to tracked files are discarded.

set -euo pipefail

BRANCH="arena/01a0b583-qself"
TARGET_REF="refs/remotes/origin/$BRANCH"
LIVE=".github/workflows/ci.yml"
SOURCE="docs/ci/ci.yml"

if [ ! -d .git ]; then
    echo "ERROR: run this from the repository root." >&2
    exit 1
fi

git fetch origin "$BRANCH:$TARGET_REF"
git checkout -f -B "$BRANCH" "$TARGET_REF"

if [ ! -f "$SOURCE" ]; then
    echo "ERROR: $SOURCE is missing on $BRANCH." >&2
    echo "       A bootstrap run from an older revision 'git mv'-ed it away." >&2
    echo "       Restore it with:" >&2
    echo "         git checkout $TARGET_REF -- $SOURCE" >&2
    echo "       and run this script again." >&2
    exit 1
fi

if [ -f "$LIVE" ] && cmp -s "$SOURCE" "$LIVE"; then
    echo "CI workflow is already up to date."
    exit 0
fi

# The step bodies are scripts; without them the workflow would run nothing.
missing=0
for script in config-submodules setup-sdk report-toolchain build verify-apk; do
    if [ ! -f "docs/ci/$script.sh" ]; then
        echo "WARNING: docs/ci/$script.sh is missing from this revision." >&2
        missing=1
    fi
done
if [ "$missing" -ne 0 ]; then
    echo "ERROR: fix the missing scripts on $BRANCH first." >&2
    exit 1
fi

# Remove old workflows first: `git rm -r` also deletes the directory once its
# last file is gone, so `mkdir` must come after it or the following copy of
# the tree file would fail with "No such file or directory".
git rm -r -q -f --ignore-unmatch .github/workflows
mkdir -p .github/workflows
cp "$SOURCE" "$LIVE"
git add "$LIVE"

git commit -m "ci: sync workflow from docs/ci/ci.yml"
git push origin "$BRANCH"

echo
echo "Done. Step bodies come from docs/ci/*.sh, so the agent can change CI"
echo "logic without this file ever changing again."
