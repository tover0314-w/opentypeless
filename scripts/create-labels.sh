#!/bin/bash
# Run this script after authenticating: gh auth login
# Usage: bash scripts/create-labels.sh

REPO="dengxuezhao/opentypeless"

# Labels that likely already exist (update color/description)
gh label edit "bug" --color "d73a4a" --description "Something isn't working" --repo "$REPO" 2>/dev/null || gh label create "bug" --color "d73a4a" --description "Something isn't working" --repo "$REPO"
gh label edit "enhancement" --color "a2eeef" --description "New feature or request" --repo "$REPO" 2>/dev/null || gh label create "enhancement" --color "a2eeef" --description "New feature or request" --repo "$REPO"
gh label edit "duplicate" --color "cfd3d7" --description "Duplicate issue" --repo "$REPO" 2>/dev/null || gh label create "duplicate" --color "cfd3d7" --description "Duplicate issue" --repo "$REPO"
gh label edit "wontfix" --color "ffffff" --description "Will not be fixed" --repo "$REPO" 2>/dev/null || gh label create "wontfix" --color "ffffff" --description "Will not be fixed" --repo "$REPO"

# New labels
gh label create "good first issue" --color "7057ff" --description "Good for newcomers" --repo "$REPO" 2>/dev/null
gh label create "help wanted" --color "008672" --description "Extra attention is needed" --repo "$REPO" 2>/dev/null
gh label create "docs" --color "0075ca" --description "Documentation" --repo "$REPO" 2>/dev/null
gh label create "frontend" --color "1d76db" --description "React / TypeScript" --repo "$REPO" 2>/dev/null
gh label create "rust" --color "e4572e" --description "Rust backend" --repo "$REPO" 2>/dev/null
gh label create "i18n" --color "fbca04" --description "Internationalization" --repo "$REPO" 2>/dev/null
gh label create "ci" --color "666666" --description "CI / CD" --repo "$REPO" 2>/dev/null
gh label create "security" --color "b60205" --description "Security related" --repo "$REPO" 2>/dev/null
gh label create "pinned" --color "006b75" --description "Pinned, will not auto-close" --repo "$REPO" 2>/dev/null
gh label create "no-stale" --color "006b75" --description "Exempt from stale bot" --repo "$REPO" 2>/dev/null
gh label create "stale" --color "ededed" --description "No recent activity" --repo "$REPO" 2>/dev/null

# Size labels (used by PR size labeler)
gh label create "size: XS" --color "ededed" --description "0-9 lines changed" --repo "$REPO" 2>/dev/null
gh label create "size: S" --color "d4c5f9" --description "10-29 lines changed" --repo "$REPO" 2>/dev/null
gh label create "size: M" --color "c2e0c6" --description "30-99 lines changed" --repo "$REPO" 2>/dev/null
gh label create "size: L" --color "fef2c0" --description "100-499 lines changed" --repo "$REPO" 2>/dev/null
gh label create "size: XL" --color "f9d0c4" --description "500+ lines changed" --repo "$REPO" 2>/dev/null

echo "Done! Labels created."
