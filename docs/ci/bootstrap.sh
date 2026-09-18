#!/data/data/com.termux/files/usr/bin/bash
# Qself CI bootstrap — run in Termux on your phone (one time).
#
# Background: the Arena session token has no `workflows` scope, so commits
# touching .github/workflows/ cannot be pushed from the agent. This script
# moves the CI workflow from docs/ci/ into .github/workflows/ and pushes it
# with your own credentials (which DO have the scope).
#
# Usage:
#   pkg install git        # once, if you haven't already
#   termux-setup-storage   # once, if you want the repo in shared storage
#   cd ~/Qself             # or wherever you cloned the repo
#   git fetch origin arena/01a0b583-qself
#   bash docs/ci/bootstrap.sh

set -euo pipefail

BRANCH="arena/01a0b583-qself"

git checkout "$BRANCH"
git reset --hard "origin/$BRANCH"

git rm -r -q .github/workflows
git mv docs/ci/ci.yml .github/workflows/ci.yml

git commit -m "ci: enable Qself CI (workflow bootstrap)"
git push origin "$BRANCH"

echo
echo "Done. CI will now run on every push to $BRANCH."
