# Task 14 git-only integration evidence

- Scenario: integrate independently confirmed Task 14 into `codex/w3-w5-production-closure`.
- Target worktree: `/Users/atomex/Desktop/surprising/w3-w5-production-closure-worktree`.
- Source commit: `7194bd7607cdff9c76d9343742296b31443717ef`.
- Integration parent: `3b815d061f33b9951ff67db167d96a4b30d0d332`.
- Result commit: `10277b140ca3db1916fa7c4be505a9b4777d958e`.

## Invocations and binary observables

1. `git cherry-pick --no-edit 7194bd7607cdff9c76d9343742296b31443717ef`
   - Exit: `0`.
   - Observable: created result commit `10277b14` with exactly five files changed.
2. `git diff-tree --no-commit-id --name-only -r HEAD`
   - Observable count: `5`.
   - Files:
     - `surprising-aeron-core/runtime/w3-w5/README.md`
     - `surprising-aeron-core/runtime/w3-w5/compose.yaml`
     - `surprising-aeron-core/runtime/w3-w5/run.sh`
     - `surprising-aeron-core/runtime/w3-w5/scenarios/common.sh`
     - `surprising-aeron-core/runtime/w3-w5/tests/ownership-safe-cleanup.sh`
3. Parent and ancestry checks:
   - `git rev-parse HEAD^` equals `3b815d061f33b9951ff67db167d96a4b30d0d332`.
   - `git merge-base --is-ancestor 3b815d061f33b9951ff67db167d96a4b30d0d332 HEAD`: exit `0`.
   - `git merge-base --is-ancestor 73d97579ac68148bd937824c80559f203d4377ae HEAD`: exit `0` (Task 13 preserved).
   - `git diff --check HEAD^ HEAD`: exit `0`.
4. `git push origin HEAD:refs/heads/codex/w3-w5-production-closure`
   - Exit: `0`.
   - Observable: remote advanced `3b815d06..10277b14`.
5. `git ls-remote origin refs/heads/codex/w3-w5-production-closure`
   - Remote SHA: `10277b140ca3db1916fa7c4be505a9b4777d958e`.
   - Local SHA: `10277b140ca3db1916fa7c4be505a9b4777d958e`.
   - Equality check exit: `0`.
6. `git show --format= --no-ext-diff <commit> | git patch-id --stable`
   - Source patch-id: `e6a4dcbae227e2992df162df97e172e361cf03d7`.
   - Result/remote patch-id: `e6a4dcbae227e2992df162df97e172e361cf03d7`.
   - Equality check exit: `0`.

Tests were intentionally not rerun per the integration instruction; source SHA was independently verified. The target worktree retained only its pre-existing untracked `.omo` entries and no tracked changes.
