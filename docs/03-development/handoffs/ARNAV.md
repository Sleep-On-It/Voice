---
id: DEV-12
title: Workstream handoff - Arnav (engine)
stage: 03-development
status: Living record
priority: P1
source_revisions: [reverez/Voice feat/arnav-engine]
related_requirements: [NFR-MAINT-004]
related_decisions: [DEC-048]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# Workstream handoff - Arnav (engine)

Owned by Arnav's session. Update it at every checkpoint (docs/03-development/GITHUB-WORKFLOW.md section 7).
A restarted session reads this file first; keep it short, factual and current.

## Current state

| Field | Value |
|---|---|
| Repository / branch | `reverez/Voice`, `feat/arnav-engine` (remote `voice` in the lead's worktree `C:\Users\arnav\Documents\dev\fidelis-engine`) |
| Last pushed implementation commit | `63299b7` WIP preservation of all ten existing issue #5 files, following `e2a401b` (macro cancellation) and `ed39356` (shared-JVM isolation) |
| Current issue | #5 WIP: native Results/ROI/threshold synchronization and interactive J-12; not ready to merge. #4 evidence still needs its required default/random-order review. |
| Last full-suite result on this branch | 17 Oct, source tree `63299b7`: **388 tests finished, 4 failed**, exit 1; three additional assumption-aborted cases due to missing FX-030/031 GUI baselines. No `listener failed` or `LinkageError` in this run. |
| Open PRs | Engine preservation draft is being prepared into `development/fidelis-workstation`; Alex's UI PR #22 is open. No voice PR/update yet at this checkpoint. |
| Background processes | none expected at a restart; `test.py` kills its JVM on its 900 s bound and writes `fidelis-app/build/test-timeout-threads.txt` first |

## Done

- #3: preflight `PREFLIGHT PASS` on the lead machine (1379 Fiji files match the lock), recorded on #3.
- #4 root causes (all reproduced on 9f1ef7a, details in the #4 PROGRESS comment and the `ed39356` message):
  1. `Workspace` never removes its engine listener; disposed test workspaces kept receiving events (`listener failed on StateDigestEvent ... ImageProcessor.getMin()`).
  2. `EngineScienceTest` asserted order on the session-wide `exportMacro()`; now on `exportMacroSince(historyStart)`.
  3. Test discovery loaded `ij.*` before the legacy patcher (`LinkageError: duplicate class definition`), after which an unpatched `ImageWindow.dispose` hung the run (thread dump kept in the session scratchpad; `test.py` now dumps threads itself on timeout).
- Isolation primitive: `sc.fidelis.testkit.SessionIsolation` + `IsolationState` (auto-registered extension; one `[isolation]` line per class naming the leaks it reset); `FijiFixture.patchEarly/requirePatched`; `DefaultEngine.listeners()/resyncResults()` (implementation-only); `test.py --random-order SEED [--random-methods]`; `verification/test_verification.py` no longer needs the git-ignored raw dump.

## Next steps

1. Preserve `63299b7` as WIP; fix the four #5 failures below in follow-up commits, without weakening assertions or replacing scientific baselines to fit output.
2. Re-run the targeted native, interactive, event-order, error and macro-replay tests, then the full suite and required random-order seeds. Resolve missing baseline coverage explicitly before claiming complete parity. Recover/review the earlier #4 evidence; this preservation checkpoint does not establish the five required green runs.
3. Ayush pushes his own `feat/ayush-voice` from his device. Then fetch all three exact heads and review their commits, diffs, PRs, ownership and behavioral contracts before deciding integration readiness.
4. Proposed sequence: approved contract changes, green engine prerequisites, voice, UI; merge commits only into `development/fidelis-workstation`. Owners merge updated development into their branches and retest after each integration step. No merge is authorized by this preservation checkpoint.
5. Keep #5 draft while red. If #4 must land separately, create a prerequisite PR from its existing commits without rewriting `feat/arnav-engine`; keep the later native/interactive work separate. #6/#7/#17 remain separate contract work.

## Preservation verification (17 October 2026)

- All ten previously modified/untracked source files were committed unchanged in implementation as `63299b7` and pushed only to `reverez/Voice`, `feat/arnav-engine`. The original file copies, binary diff and verified complete Git bundle are outside the checkout at `C:\Users\arnav\Documents\dev\repo-backups-2026-10-17\engine-preservation-195704`.
- `python -I fidelis-app/tools/build.py`: `[build] OK`, 87 main / 53 test sources, exit 0.
- Full suite: `C:\Users\arnav\Documents\dev\click-to-batch\.venv\Scripts\python.exe -I fidelis-app/tools/test.py`, with `FIDELIS_PYTHON` set to that interpreter: `[fidelis-tests] 388 tests finished, 4 failed`, exit 1. Logs are `build.log` and `full-suite.log` in the backup directory above.
- Three `EngineNativeSyncTest` failures: identical-row remeasurement times out waiting for re-attribution; clear/remeasure attributes a row to `act-133` instead of `act-136`; back-to-back measures find 4 IJ1 rows versus 3 engine rows.
- `InteractiveJourneyTest` fails at Subtract Background: unmatched dialog parameter `[separate]`. Later interactive parity and interactive-session replay are therefore unverified.
- `EngineErrorTest` (including the new stale-current-image regression), `EngineEventOrderTest`, and `MacroReplayTest` pass within this run. Existing typed FX-001/003 parity and workspace journeys pass. FX-030/031 engine parity and FX-030 in-JVM cross-check abort for absent GUI baselines; the finished counter includes aborted tests and is not a passed count.
- `python -I fidelis-app/tools/check_ownership.py --stream integrator`: 21 paths, 0 outside ownership. Integrator scope is needed for the existing #4 change to `tools/test.py`; shared engine/speech/NL interfaces are unchanged. `git diff --check` passes.
- Preliminary comparison with Alex's `82fdfdb`: 21 engine paths, 30 UI paths, zero overlapping paths; `git merge-tree --write-tree 63299b7 voice/feat/alex-ui` succeeds. This is a text merge simulation, not a tested combined build.
- Alex's PR #22 reports 385 tests finished, 0 failed at implementation commit `7f078d4`; its current head includes later handoff documentation. GitHub checks are green but cover docs/prototype rather than the full Java/Fiji suite. Alex also reports CODE capability over-reporting, missing per-document ROI sync after CODE, and no live LogLine events; these need engine/UI verification together.
- Ayush's remote branch remains `9f1ef7a`, the development head. Both `AyushGupta05` and `akurkar07` have write permission; active rules block deletion/non-fast-forward updates but allow normal pushes. Local credentials and unpushed work on their devices were not inspected.

## Blockers and open questions

- Four #5 failures and missing baseline coverage remain. Final three-stream synthesis awaits Ayush's pushed update. Alex's #16 listener cleanup is in PR #22; verify it with the engine isolation extension on the combined candidate.

## Notes for the next session

- Commands: `python -I fidelis-app/tools/build.py`; `python -I fidelis-app/tools/test.py [--random-order SEED]`; one JVM per worktree.
- Logs of the #4 runs are in `%TEMP%\full-iso-*.log` on the lead machine (not committed).
