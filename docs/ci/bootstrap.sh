#!/data/data/com.termux/files/usr/bin/bash
# Qself CI bootstrap — run in Termux on your phone (one time).
#
# Background: the Arena session token has no `workflows` scope, so commits
# touching .github/workflows/ cannot be pushed from the agent. This script
# moves the CI workflow from docs/ci/ into .github/workflows/ and pushes it
# with your own credentials (which DO have the scope).
#
# Usage (from inside a clone of the repo):
#   bash docs/ci/bootstrap.sh
#
# Notes:
#   - The script fetches and checks out the branch itself, so it works right
#     after a fresh `git clone` (which leaves you on main) and is safe to
#     re-run after a failed attempt.
#   - Local modifications to tracked files are discarded (`git checkout -f`).
#     Do not run it if you have uncommitted work you care about.

set -euo pipefail

BRANCH="arena/01a0b583-qself"
TARGET_REF="refs/remotes/origin/$BRANCH"

if [ ! -d .git ]; then
    echo "ERROR: run this from the repository root." >&2
    exit 1
fi

git fetch origin "$BRANCH:$TARGET_REF"
git checkout -f -B "$BRANCH" "$TARGET_REF"

if [ ! -f docs/ci/ci.yml ]; then
    if [ -f .github/workflows/ci.yml ]; then
        echo "CI is already bootstrapped on $BRANCH."
        exit 0
    fi
    echo "ERROR: docs/ci/ci.yml not found — are you on $BRANCH?" >&2
    exit 1
fi

# Remove the old workflows first: `git rm -r` also deletes the directory
# once its last file is gone, so `mkdir` has to come after it or the
# following `git mv` fails with "renaming ... No such file or directory".
git rm -r -q -f --ignore-unmatch .github/workflows
mkdir -p .github/workflows
git mv -f docs/ci/ci.yml .github/workflows/ci.yml

git commit -m "ci: enable Qself CI (workflow bootstrap)"
git push origin "$BRANCH"

echo
echo "Done. CI will now run on every push to $BRANCH."
