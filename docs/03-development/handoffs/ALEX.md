---
id: DEV-14
title: Workstream handoff - Alex (ui)
stage: 03-development
status: Living record
priority: P1
source_revisions: [reverez/Voice feat/alex-ui]
related_requirements: [NFR-MAINT-004]
related_decisions: [DEC-048]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# Workstream handoff - Alex (ui)

Owned by Alex's session. Update it at every checkpoint (docs/03-development/GITHUB-WORKFLOW.md section 7).
A restarted session reads this file first; keep it short, factual and current.

## Current state

| Field | Value |
|---|---|
| Developer / GitHub | Alex Kurkar, `akurkar07` (collaborator on reverez/Voice, `push: true` since 17 Oct evening) |
| Branch | `feat/alex-ui` (local clone of reverez/Voice, remote `origin`) |
| Last pushed commit | `5434147` (feat/alex-ui = origin/feat/alex-ui; #15, #16, #19, #20 all pushed) |
| Current issue | PR #22 (#15/#16/#19/#20) open into `development/fidelis-workstation`, waiting for Arnav's review; #17 contract proposal posted, waiting for Arnav; #6 waits for the engine contract; #18 blocked (#11/#12) |
| Machine | Windows 11, 1920x1200 at 125 % (`dpiScale=1.25`, logical desktop 1536x960); `FIDELIS_FIJI_DIR=C:\Users\al3xk\dev\Fiji` (archive 20261013-1517, SHA-256 `9201287a…794c54`, 1379 files match `external/fiji.lock.json`); Python 3.11 via `py -3.11 -I` |
| Last UI-test result on this branch | `WorkspaceLayoutTest` 2/2, `WorkspaceViewportTest` 1/1, `WorkspaceU2Test` 4/4, `ConfirmationUiTest` 4/4 inside the full suite (17 Oct, `0eced5d`); `WorkspaceDisposeTest` 1/1 and `WorkspaceShortcutTest` 1/1 at `da1a4d6`; `WorkspaceU2Test` 20 consecutive runs, 20 × `4 tests finished, 0 failed` |
| Last full-suite result on this branch | `[fidelis-tests] 385 tests finished, 0 failed` at `7f078d4` (17 Oct); previously `[fidelis-tests] 384 tests finished, 0 failed` at `088f7e2` (17 Oct, `FIDELIS_PYTHON` = Python 3.11) (previous: `382 tests finished, 0 failed` at `da1a4d6`) | <!-- older:  `[fidelis-tests] 380 tests finished, 2 failed` at `0eced5d`: the two `MacroReplay` tests failed only because `python` on PATH is 3.10 (`hashlib.file_digest` needs 3.11); rerun with `FIDELIS_PYTHON` = Python 3.11 recorded in the #3 comment | -->
| FX-001 baseline diff | regenerated here with the pinned Fiji: numbers and hashes identical, only the lead's absolute clone paths and `seconds` differ (restored with `git checkout -- verification`; details in the #3 comment) |
| Open PRs | #22 `ui: demo layout, reliability, macro editor and dark-theme polish (#15 #16 #19 #20)` |

## Done

- #3 environment on this machine: pinned Fiji downloaded, hash-verified and extracted outside the clone; lock check 1379/0;
  `build.py` OK (87 main / 48 test sources at baseline, 50 test sources with the layout test); `run.py --smoke --isolated-home`
  -> `Invert pixel(10,10) 40 -> 215 : OK`, exit 0 in 9.2 s, `dpiScale=1.25`; fixtures fetched (FX-001/003/004/005/030/031/040).
- #15 demo layout (`sc.fidelis.ui.Docks` rewritten, no new library): right column = Parameters/B&C/Threshold tab group above the
  ROI list; bottom row = Results beside History & macro/Log/Activity; four draggable dividers; `applyDefaultDividers()` /
  `resetLayout()` derive the defaults from the frame size (right 380 px, 420 px at >= 1800 px; bottom 30 % bounded 220-320 px;
  Results 46 % of the row; tools 52 % of the column). Collapse/restore of both docks kept.
- History table: fixed widths for Time/Origin/Provenance/Outcome, tooltip with the full parameters, one-touch collapsible macro pane.
- `Workspace`: engine listener removed on frame dispose (first item of #16, needed so a disposed test workspace stops hosting
  canvases); interactive launches clamp the frame to the screen, screenshot runs keep the exact size (undecorated when larger
  than the desktop, because Windows clamps decorated frames).
- `--scenario j12` prints the measured layout (`frame WxH; layout documents=… results=… ; canvas … at 200%; results columns visible N`).
- New `WorkspaceLayoutTest` (+ `LayoutScenario`): runs the real J-12 through the workspace on the product engine, then asserts
  canvas + Results (71 rows, >= 6 columns) + ROI list (71) + History showing, non-overlapping, inside the frame at 1500x940 and
  1920x1080; dividers move their panels; reset restores the defaults; collapse/restore of both docks.
- Screenshots opened and inspected: `docs/03-development/screenshots/j12-layout-1500x940.png`, `j12-layout-1920x1080.png`.
- #16 reliability (`da1a4d6`): `CommandBar` keeps and detaches its speech listener on dispose; `WorkspaceDisposeTest` (counting
  engine delegate) proves no engine listener survives `dispose()`. `DocumentArea.snapshotDiagnostics()` + `U2Scenario` report why
  a before-copy was not taken when "before snapshot" times out (flake not reproduced: 20/20 green). Shortcut parity: `MenuBuilder`
  registers Fiji's accelerators with `UiContext`, accelerator activations are recorded as `Origin.SHORTCUT`, `Routes` sends
  `Edit>Selection>Add to Manager` to `ROI_LIST_ADD` (the engine syncs the IJ1 ROI Manager only around Analyze Particles);
  `WorkspaceShortcutTest` reads every binding from Fiji (`Menus.getShortcuts()` + menu bar) and asserts Ctrl+M, Ctrl+T,
  Ctrl+Shift+A, Ctrl+Shift+T, Ctrl+Z, Ctrl+Shift+D (Fiji's Duplicate binding), + / -, space-pan on the product engine; the
  table it prints is in the #16 PR body draft.
- #19 macro editor (`088f7e2`): `MacroEditorPanel` tab in the bottom dock (RSyntaxTextArea from Fiji's jars) with Load generated
  macro / Open / Save / Run / Cancel and a console; Run = CODE request, the engine's capability confirmation shown as an inline
  sheet, only the explicit act resubmits with the token; Cancel by operation id; console shows the run's Log lines (delivered
  with the result). Workspace menu *Macro editor* (Ctrl+Alt+E), `File>New>Script...` etc. route to the dock, History & macro
  has *Open in editor*; `ActivityPanel` no longer shows a parked (awaiting confirmation) operation as running.
  `WorkspaceMacroEditorTest` runs the macro generated from a real J-12 in the editor: nothing before confirmation, decline runs
  nothing, confirm -> 71 Results rows + 71 ROIs (ROI Manager), CODE step + 11 observed child steps in history; print line in
  the console; long macro cancelled -> CANCELLED. Screenshot `docs/03-development/screenshots/j12-editor-1500x940.png`.
- #20 polish, safe slice (`7f078d4`): `--theme dark` launch/screenshot option (Workspace > Theme toggle existed), the Macro editor
  re-applies its RSyntaxTextArea theme on a switch, dark variants of the History provenance badge colours; opt-in
  Workspace > Labelled tools (`--tool-labels`), icon-only palette stays the default; header metadata is IJ1's own subtitle.
  `WorkspaceLayoutTest.darkThemeKeepsTheDemoLayout`. Screenshots `j12-dark-1500x940.png`, `j12-labelled-tools-1500x940.png`.

## Next steps

1. Answer review comments on PR #22; after merge, rebase `feat/alex-ui` on `development/fidelis-workstation` (no force-push: `git merge` if needed).
2. (see 3)
3. Open the PRs: the PR bodies are posted as "PR BODY DRAFT" comments on #15, #16, #19 and #20 (one PR per issue, or one PR with all three if the integrator prefers).
4. #17 channels strip once Arnav acknowledges the contract comment (SET_COMPOSITE_MODE / SET_ACTIVE_CHANNELS) and lands the
   engine side; #6 adoption once the `previewMacroLines` contract merges.

## Blockers and open questions

- (resolved 17 Oct evening) push permission on reverez/Voice; preflight (#3) now all PASS except the optional speech lines (WARN).
- Engine observations from #19 (posted on #19 for Arnav): the CODE capability scan returns `exec,eval,call,runMacro` for every
  text (even `print(...)`), so the confirmation sheet over-reports and pure macros are recorded `UNRECORDABLE` with 0 macro
  lines; `Engine.rois(doc)` stays 0 after a CODE run that filled the ROI Manager (71); `EngineEvent.LogLine` is never emitted
  (Log tab empty), log lines arrive only in the result digest.
- From #16 (posted on #16): `CommandRunner` syncs the document ROI list with IJ1's ROI Manager only around Analyze Particles;
  the UI routes `Add to Manager` (menu and Ctrl+T) to `ROI_LIST_ADD` for that reason.
- Test-order observation (for #4): `ConfirmationUiTest.vocabularyPromptFollowsDocumentsAndRecording` fails when run *after*
  `WorkspaceViewportTest` + `WorkspaceU2Test` in one JVM: those scenarios leave seven documents open (two-squares.png x3,
  stack10, comp3, ramp16, confirm-required), the vocabulary prompt lists at most five titles, so the new document never
  appears. It passes alone, first, and in the full suite's alphabetical order. Not caused by #15 (`LayoutScenario.stop()`
  closes its documents; `[layout] after cleanup: documents=[] results=0`).
- Observed while testing (for #4, integrator): the test JVM's isolated `build/test-home` keeps IJ_Prefs between runs
  (`blackBackground=true`, `measurements=area mean min` were already set at the start of a fresh test JVM after an earlier
  J-12 run). `LayoutScenario.stop()` restores `blackBackground`, measurements and precision and clears Results through the
  engine, but the on-disk prefs are outside the UI stream.

## Notes for the next session

- Read `CLAUDE.md` and `docs/03-development/ALEX-EXECUTION.md` before anything else.
- Use `py -3.11 -I` (the plain `python` on this machine is 3.10). `FIDELIS_FIJI_DIR` is set for the user; a shell opened
  before 17 Oct 16:00 needs `$env:FIDELIS_FIJI_DIR = "C:\Users\al3xk\dev\Fiji"`.
- Evidence at 1920x1080 on this 1536x960 logical desktop comes from the undecorated screenshot frame; interactive runs are clamped.
- `FIDELIS_PYTHON` is set (user level) to the Python 3.11 interpreter so the macro-replay tests do not pick up the 3.10 `python`.
