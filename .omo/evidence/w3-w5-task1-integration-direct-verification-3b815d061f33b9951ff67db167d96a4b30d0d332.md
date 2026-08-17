# W3-W5 Task 1 integration direct verification

Date: 2026-08-16
Hook evidence root: `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence`
Target worktree: `/Users/atomex/Desktop/surprising/w3-w5-production-closure-worktree`

## Actual command output

```text
SCENARIO=direct verification of target integration worktree from hook cwd
TARGET=/Users/atomex/Desktop/surprising/w3-w5-production-closure-worktree
INVOCATION=git -C TARGET branch --show-current
codex/w3-w5-production-closure
INVOCATION=git -C TARGET rev-parse HEAD
3b815d061f33b9951ff67db167d96a4b30d0d332
INVOCATION=git -C TARGET show -s --format=%H%n%P%n%s HEAD
3b815d061f33b9951ff67db167d96a4b30d0d332
73d97579ac68148bd937824c80559f203d4377ae
feat(aeron-protocol): version default route wire
INVOCATION=git -C TARGET ls-remote origin refs/heads/codex/w3-w5-production-closure
3b815d061f33b9951ff67db167d96a4b30d0d332	refs/heads/codex/w3-w5-production-closure
OBSERVABLE=local_and_remote_sha_match=1
INVOCATION=git -C TARGET diff-tree --no-commit-id --name-status -r HEAD
M	surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java
M	surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java
M	surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreProtocol.java
A	surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreRoute.java
M	surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreExportCodecTest.java
M	surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreMessageCodecTest.java
A	surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreNativeSnapshotProductLineTest.java
A	surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/W1W2InvariantFenceTest.java
OBSERVABLE=picked_commit_file_count=8
INVOCATION=source_and_picked_patch_ids
source_patch_id=6f1bcf5affd1c13dee3435faabc33824cf4300b6
picked_patch_id=6f1bcf5affd1c13dee3435faabc33824cf4300b6
OBSERVABLE=source_and_picked_patch_id_match=1
INVOCATION=git -C TARGET merge-base --is-ancestor TASK13 HEAD; BASELINE HEAD
task13_ancestor_exit=0
baseline_ancestor_exit=0
INVOCATION=git -C TARGET diff --quiet; git -C TARGET diff --cached --quiet
OBSERVABLE=tracked_and_staged_diffs_empty=1
RESULT=PASS
```

## Judgment

The direct verification passed: target branch and local HEAD are correct; the remote ref has the exact same full SHA; the picked commit contains exactly eight files and has the same stable patch-id as source commit `f7923da89c9ab53df54a9465171694a2425622ab`; Task 13 commit `73d97579ac68148bd937824c80559f203d4377ae` and baseline `7e78e04ae4dac16d364117392f960a65a4f4db2d` remain ancestors; and tracked/staged diffs are empty. Tests were not run because the task explicitly prohibited rerunning them.
