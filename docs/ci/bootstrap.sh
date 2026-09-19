#!/data/data/com.termux/files/usr/bin/bash
# Qself CI sync — run in Termux on your phone.
#
# Background: the Arena session token has no `workflows` scope, so commits
# touching .github/workflows/ cannot be pushed from the agent. The workflow
# is kept as a normal file under docs/ci/ci.yml (editable by the agent) and
# this script mirrors it into .github/workflows/ with your credentials.
#
# Usage (from inside a clone of the repo):
#   bash docs/ci/bootstrap.sh
#
# Safe to re-run: it fetches the branch, checks it out, and only commits
# when .github/workflows/ci.yml actually differs from docs/ci/ci.yml.
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
    echo "ERROR: $SOURCE not found — are you on $BRANCH?" >&2
    exit 1
fi

if [ -f "$LIVE" ] && cmp -s "$SOURCE" "$LIVE"; then
    echo "CI workflow is already up to date."
    exit 0
fi

# Remove old workflows first: `git rm -r` also deletes the directory once
# its last file is gone, so `mkdir` must come after it or the following
# `git mv` fails with "renaming ... No such file or directory".
git rm -r -q -f --ignore-unmatch .github/workflows
mkdir -p .github/workflows
cp "$SOURCE" "$LIVE"
git add "$LIVE"

git commit -m "ci: sync workflow from docs/ci/ci.yml"
git push origin "$BRANCH"

echo
echo "Done. CI will now run on every push to $BRANCH."
