#!/data/data/com.termux/files/usr/bin/bash
# Qself CI bootstrap — run in Termux on your phone (one time).
#
# Background: the Arena session token has no `workflows` scope, so commits
# touching .github/workflows/ cannot be pushed from the agent. This script
# moves the CI workflow from docs/ci/ into .github/workflows/ and pushes it
# with your own credentials (which DO have the scope).
#
# Usage (from inside a clone of the repo):
#   pkg install git        # once, if you haven't already
#   bash docs/ci/bootstrap.sh
#
# The script fetches the branch and checks it out itself, so it works right
# after a fresh `git clone` (which leaves you on main).

set -euo pipefail

BRANCH="arena/01a0b583-qself"
TARGET_REF="refs/remotes/origin/$BRANCH"

git fetch origin "$BRANCH:$TARGET_REF"
git checkout -B "$BRANCH" "$TARGET_REF"

if [ ! -f docs/ci/ci.yml ]; then
    echo "ERROR: docs/ci/ci.yml not found — are you on $BRANCH?" >&2
    exit 1
fi

mkdir -p .github/workflows
git rm -r -q --ignore-unmatch .github/workflows
git mv -f docs/ci/ci.yml .github/workflows/ci.yml

git commit -m "ci: enable Qself CI (workflow bootstrap)"
git push origin "$BRANCH"

echo
echo "Done. CI will now run on every push to $BRANCH."
