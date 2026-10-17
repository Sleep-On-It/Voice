---
id: DEV-01
title: Active development record
stage: 03-development
status: Living record
priority: P0
source_revisions: [Fiji archive 20261013-1517 (lock f6695397 / 9403b619 LF), imagej/ImageJ@v1.54p, integration branch development/fidelis-workstation]
related_requirements: [FR-SYS-001, FR-SYS-002, FR-PROC-001, FR-HIST-002, FR-MACRO-001, FR-NL-001, FR-SPEECH-004, NFR-FID-001]
related_decisions: [DEC-010, ADR-002, DEC-048]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# ACTIVE DEVELOPMENT — Fidelis workstation (persistent progress record)

Read this first after any interruption or context reset. Then read `git log --oneline -20` on the
integration branch and the agent worktrees listed below.

## Repositories and branches

| Role | Location |
|---|---|
| Canonical remote | `reverez/Voice` (public fork of `Sleep-On-It/Voice`; contains the full Fidelis project). Remote name `voice` in the local checkouts. |
| Preserved original | `Sleep-On-It/Fidelis`: do not modify. `AyushGupta05/Voice` and Ayush's forks: never touch. `Sleep-On-It/Voice` is the primary repository since 17 Oct 2026. |
| **Integration branch** | `development/fidelis-workstation` in worktree `C:\Users\arnav\Documents\dev\fidelis-dev` (from fork `main` @ `fa70497`) |
| Architecture-gate review docs | `review/architecture-gate` in `C:\Users\arnav\Documents\dev\fidelis-arch-gate` (lead) |
| Validation spikes | `spike/architecture-gate` in `C:\Users\arnav\Documents\dev\fidelis-spikes` (Sonnet spike agent, still running EXP-02…05; **do not touch**) |
| Prototype (fallback demo) | `backend/`, `frontend/`, `speech/` on every branch; unchanged |

## Architecture in force (implementation choice, recorded; DEC-010 remains formally "proposed")

**Hybrid B** from the architecture-gate review, confirmed by spike EXP-01 (PASS for strategy S3):

- One persistent, non-headless Fiji JVM; Fiji's own main window is never shown (A15 mode M2).
- An in-process engine service (`sc.fidelis.engine`) executes typed actions through
  `IJ.run(imp, label, completeOptions)` on one worker thread; success judged by postconditions.
- **Real IJ1 `ImageCanvas` per document**, hosted in the FlatLaf workspace: `HostCanvas extends
  ImageCanvas` with `canEnlarge()` returning null, kept inside a hidden `ImageWindow`; the host drives
  the viewport (`setDrawingSize`, `setSourceRect`, `setMagnification`, in that order) and forwards
  wheel events to the hidden window. IJ1's current image follows the focused document
  (`WindowManager.setCurrentWindow`). Native tools, ROI algebra, read-out and zoom are IJ1's own code.
- Native interactions are *observed* (`CommandListener`, `RoiListener`, digests) and recorded with
  provenance NATIVE; typed actions are TYPED; original dialogs are LEGACY_DIALOG; the rest UNRECORDABLE.
- Structured history is authoritative; `.ijm` export is generated from it (D07 rules).
- Speech: persistent faster-whisper worker (existing `speech/` code), transcript → deterministic
  command resolution → preview/confirm → the same engine.

Evidence: `fidelis-spikes/spikes/arch-gate/exp01-native-canvas/RESULT.md` (committed `ce7dbf6`),
scratchpad `ARCH-REVIEW.md` (to be committed under `docs/02-design/07-architecture-gate/`).

## Toolchain (M0, verified 2026-10-17)

No Maven/Gradle on this machine; the build uses the JDK bundled with the pinned Fiji.

```
python fidelis-app/tools/fetch_junit.py        # once: pinned JUnit console runner (sha256 b016ef6b…)
python fidelis-app/tools/build.py              # javac → build/classes, build/test-classes, build/fidelis-app.jar
python fidelis-app/tools/run.py --smoke --isolated-home   # engine self-test: real Invert verified, exit 0 (3.6 s)
python fidelis-app/tools/run.py --isolated-home            # launch the application
python fidelis-app/tools/test.py               # JUnit 5 in a live Fiji JVM; isolated user.home
```
Use the repo's `.venv` Python (`C:\Users\arnav\Documents\dev\click-to-batch\.venv\Scripts\python.exe -I`).
JVM flags mirror Fiji's launcher dry-run (`tools/fijienv.py`); updater autocheck is always disabled.

## Shared contract (Section 6 of the mission) — `sc.fidelis.engine.api`

`Engine` (facade), `ActionRequest` (+ `Operations`), `ActionResult` (+ `EngineError`, `StateDigest`),
`CommandSchema`, `DocumentId`/`DocumentInfo`/`TargetScope`, `RoiGeometry`, `HistoryRecord`,
`MeasurementRow`, `EngineEvent` (sealed), `EngineListener`, `Enums`. **Changes need lead review** and
a line in the "Contract changes" section below.

## Agent team and file ownership

| Agent | Model | Worktree / branch | Owns (write) | Reads |
|---|---|---|---|---|
| Lead (integration) | Fable 5.1 | `fidelis-dev` / `development/fidelis-workstation` | `docs/03-development/`, `fidelis-app/tools/`, `engine/api`, merges | everything |
| A1 Fiji Engine | Sonnet high | `fidelis-agent-engine` / `agent/engine` | `fidelis-app/src/main/java/sc/fidelis/engine/**` (except `api`), `src/main/resources/sc/fidelis/engine/**` | api, docs A08–A15, D04–D07 |
| A2 Desktop UI | Sonnet high | `fidelis-agent-ui` / `agent/ui` | `fidelis-app/src/main/java/sc/fidelis/ui/**`, `src/main/resources/sc/fidelis/ui/**` | api, D11–D15, EXP-01 |
| A3 Voice & NL | Sonnet high | `fidelis-agent-voice` / `agent/voice` | `fidelis-app/src/main/java/sc/fidelis/{speech,nl}/**`, `speech/` (worker protocol only) | api, A16–A17, D08 |
| A4 Verification | Sonnet high | `fidelis-agent-verify` / `agent/verify` | `fidelis-app/src/test/**`, `verification/**`, `fixtures/manifest.*` | everything |

Rules: never write outside your ownership; never run two Fiji JVMs from the same worktree at once;
always `--isolated-home` / test.py; never modify `C:\Users\arnav\Documents\dev\Fiji`; commit on your
own branch only; the lead merges.

## Active agents (started 2026-10-17 ~12:20, all Sonnet 5.5 high effort, background)

| Agent | Status | Branch | Task |
|---|---|---|---|
| A1 Engine | **merged** 14:05 (c52dc86); wired into the UI (c2f7bb6); defects found by the verification suite fixed by the lead (eaddf4a) | `agent/engine` | E1–E7: DefaultEngine, catalogue/schemas, R1 execution with postconditions and dialog watch, observation, ROI Manager singleton bridge, globals, snapshot undo, history/macro export, engine tests |
| A2 UI | **merged** 13:15 (cb016b9) and U2 follow-up 14:20 (57b8081: Threshold and B&C panels, before/after compare, stack/channel sliders, scenario screenshots) | `agent/ui` | Workspace per D12 W-1 with hosted real IJ1 canvases (EXP-01 S3), Fiji menu bar from `Menus.getMenuBar()`, tool palette, command bar + proposal sheet, parameters/ROI/results/history/log/activity panels, status read-out, themes, `--screenshot` flag, UI test |
| A3 Voice/NL | **merged** 13:05 (7f046e0, 2b42cdc, db9093b) | `agent/voice` | `speech/server.py` persistent worker + `WhisperSpeechService`; `DefaultNlService` (grammar + catalogue retrieval + deterministic validator/policy); eval set ≥30 cases; tests |
| A4 Verify | **merged** 13:40 (395275b) and T6 follow-up 14:20 (ef9ba1e: GUI smoke, workspace journey, Results TSV round trip, E7 replay) | `agent/verify` | fixtures manifest + cache (blobs, OME-TIFF, BBBC metadata), independent reference runner + comparators, J-12 baselines, parity tests, GUI smoke harness, VERIFICATION-RESULTS.md |
| Spike (prior session) | **finished** 12:50 | `spike/architecture-gate` (pushed) | EXP-01…05 PASS/INCONCLUSIVE per `spikes/arch-gate/README.md`; EXP-06 script only (NOT RUN) |
| A2 U3 | **merged** 16:20 (9dd2128): `--scenario j12` on the real engine with asserted numbers (Area 65024, Mean 155.9435746801181, 71 rows / 71 ROIs) and inspected screenshots, `--instruction`/`--confirm`/`--close-window`, confirmation policy in the proposal sheet (explicit acknowledgement for AC4+/`black`/CODE), vocabulary prompt wiring, quit path exit 0, real-engine fixes in B&C/Threshold/status bar | `agent/ui` | `--scenario j12` on the real engine with asserted numbers and screenshot; `--instruction` proposal → confirm → execute screenshots; public `ResultsPanel.tsv()`; U2 scenarios re-inspected on the real engine; quit path exit code |
| A4 T7 | **merged** 15:45 (6abd443): parity on FX-001/003/030/031 exact (345 and 300 particles on BBBC001/BBBC004), speech-to-execution E2E, GUI smoke asserts "Engine: ready" by OCR and a live status-bar test, suite twice 216/0 | `agent/verify` | BBBC001/BBBC004 J-12 through the real engine vs the reference runner (reported-only vs published counts); speech-to-execution E2E; GUI smoke on the real engine; suite run twice for order dependence; VERIFICATION-RESULTS section 10 rewritten |
| A1 E8 | **merged** 16:30 (0e63d8a): `SET_DISPLAY_RANGE channel`, `NEW_IMAGE channels/frames`; no-op rule replaced (identity = COMPLETED with a note; failure only on an error signal or a vetoed command, subcode NOT_RUN); `quit` tests; transient images; Plot/Histogram windows reported in `windowsOpened`; stale-singleton tests; observer memory release | `agent/engine` | `SET_DISPLAY_RANGE channel`, `NEW_IMAGE channels/frames`; principled no-op rule; `quit` test; transient images; window count; stale-singleton review |
| A3 V5 | **merged** 16:00: eval 124/124 on both FakeEngine and DefaultEngine (retrieval fixed: `Engine.search` misses long clauses, labels are scanned too), ten J-12 phrases executed on the real engine with exact numbers, `ConfirmationPolicy` (required/explicit/reasons), `vocabularyPrompt` | `agent/voice` | eval 124 cases on the real catalogue; J-12 phrases executed on the real engine with asserted numbers; confirmation policy tests; vocabulary prompt |

Shared brief: scratchpad `DEV-BRIEF.md` (copied to `docs/03-development/DEV-BRIEF.md` at the next integration).

## Milestones

| Milestone | Status | Evidence |
|---|---|---|
| M0 Integration baseline (branch, toolchain, contract, launch) | **Done** 2026-10-17 | build OK; smoke OK; 2 JUnit tests pass |
| M1 Real Fiji execution (engine) | **Done** 2026-10-17 14:50 | `DefaultEngine` runs real `IJ.run` on the worker with postconditions, dialog capture and rollback; `EngineParityTest` on FX-001 and FX-003 equals the pinned-Fiji GUI baseline at every J-12 step (E1 pixels, E5 doubles, E4 ROI vertices, E6 globals) |
| M2 Modern workspace (hosted canvas, menus, panels) | **Done on the real engine** | `Bootstrap` registers `DefaultEngine` with `EngineProvider`; `WorkspaceJourneyTest` drives J-12 through the workspace on the product engine and matches the baseline incl. the Results panel's TSV (bit-exact doubles); screenshot `fidelis-app/build/shots/real-blobs.png` inspected (status bar "Engine: ready", no "development stub") |
| M3 ROIs and results | **Done** | Analyze Particles fills the engine ROI list (71 on Blobs, 2 on two-squares, vertices exact) and the Results rows mirror IJ1's table at full precision; ROI panel, Threshold panel (IJ1 overlay), B&C panel verified by A2's tests |
| M4 Macros and history | **Done** | History is authoritative; `exportMacro()` output of the J-12 session replayed in the pinned reference Fiji (separate JVM, fresh fixture copy) reproduces the live session exactly (`MacroReplayTest`, E7); undo/redo from snapshots tested |
| M5 Speech and NL | **Done except live-microphone verification** | eval 124/124 incl. the 10 J-12 demo phrases, 0 unsafe; worker restarts after a kill; fixture transcribes ("Invert the image." in 272 ms); live microphone needs a person |
| M6 End-to-end demo (J-12 cell counting) | **Done** 16:30 | `run.py --isolated-home -- --screenshot … --scenario j12` runs the whole journey through the UI on the real engine and asserts the numbers (71 ROIs drawn on the real canvas; `fidelis-app/build/shots/j12-blobs.png`, inspected by lead and agent); `--instruction "count the cells"` shows the proposal sheet, `--confirm` executes through the engine; spoken fixture → transcript → proposal → confirm → execute verified (`SpeechToExecutionTest`, `NlRealEngineJourneyTest`); BBBC001 345 and BBBC004 300 particles exact against the reference runner; `DEMO-RUNBOOK.md` |
| M7 Hardening | **Partly** | stale-singleton, quit, window-count and transient-image tests (E8); suite run twice without order dependence (T7); one order-dependent flake seen once by A1 in `WorkspaceU2Test.compareShowsTwoRealCanvases` ("timed out waiting for before snapshot"), not reproduced |

Full suite on the integration branch after all merges (16:35): **378 tests, 0 failed, 0 skipped**. Runtime ~6 min; the replay test launches the reference Fiji twice. Earlier points: 211/0/1 at eaddf4a, 217/0 after T7, 359/0 after V5.

## Working capabilities (verified by running)

- Engine bootstrap: SciJava context with the legacy layer, FlatLaf, real `IJ.run` on a synthetic image, orderly exit (EXP-04 recipe: close images → dispose → halt; 3.6 s total).
- Workspace: Fiji menu bar (from `Menus.getMenuBar()`), tool palette driving the IJ1 Toolbar, command bar (ranked search + NL proposal sheet + mic), tabbed documents hosting real IJ1 canvases with native ROI tools, parameters/ROI/results/history/log/activity panels, status read-out with IJ1 formatting, legacy-window tracker; `run.py --isolated-home -- --screenshot <png>` produces a composited screenshot.
- Speech: persistent `speech/server.py` worker (hash-verified tiny.en, VAD) through `WhisperSpeechService`; typed/spoken text → `DefaultNlService` proposals with deterministic policy (no code generation, consequential slots only from the user).
- Launching `java.exe` directly (tools/run.py) gives a **DPI-aware** JVM: `dpiScale=1.50` on the 150 % display. The spikes' Jaunch-launched JVM was DPI-unaware (blurry); the product launcher avoids that.

## Spike facts every workstream must respect (from EXP-01…05)

- Host window: `canEnlarge()==null` **and** `win.setLayout(null)`; viewport order size → source rect → magnification; never `zoomOut`/`unzoom` on the canvas.
- Current image follows `WindowManager.setCurrentWindow(win)` only (not clicks/focus); make the target current **before** any `RoiManager.select`.
- Error signals are non-uniform; a plugin exception on a hidden-window document can be silent → postconditions decide success. Modal `MessageDialog`s block the worker → the dialog watch must capture and dispose them.
- Macros ending with `setBatchMode(false)` re-show hidden windows → re-apply the window policy after CODE actions. First macro `selectImage` on a hidden window costs ~1 s.
- Shutdown: close images, then `context.dispose()` (1.45 s) or `IJ.getInstance().quit()` (0.44 s); never bare `System.exit` with documents open.
- Native canvas paint 0.14–1.7 ms median at 2048²; IJ1 caches the converted image.

## Scientific baselines (A4; `verification/baselines/**`, `docs/03-development/VERIFICATION-RESULTS.md`)

J-12 reference values from the pinned Fiji (GUI mode authoritative; headless cross-check exact on E1/E3/E5/E6):
- Blobs (Otsu dark): Area 65024, Mean 155.9435746801181, IntDen 10140075; Analyze Particles 71 particles, 61.15 % area.
- two-squares: Area 4096, Mean 30.87890625; 2 particles (192, 304).
- BBBC001 A03f00d0: 345 particles (manual counts 350/362, reported only). BBBC004 1GRAY: 300 (ground truth 300, reported only).
Fixtures are cached (git-ignored) in `fixtures/cache/` (copy from `fidelis-agent-verify` or run `verification/fetch_fixtures.py`).

## Known failures / open issues

- Lock-file hash: the prototype's `verify_runtime` returns the SHA-256 of `external/fiji.lock.json` *as checked out* — `f6695397…` in the original CRLF checkout, `9403b619…` in fresh LF clones. The runtime inventory check itself passes in both; the documentation's `f6695397…` denotes the same pin. Baselines are keyed by `9403b619…`.
- Threshold polarity matters: on Blobs (inverting LUT) `Otsu dark` → 71 particles, plain `Otsu` → 66. The UI must show the overlay before the user commits.
- Test JVM exit: fixed by `ExitListener`.
- No-op rule (replaced in E8): unchanged pixels are not a failure. An in-place command fails only when the Guard saw an error signal, the call threw, or ImageJ never started the command (a `CommandListener` veto; subcode NOT_RUN). An identity completes with a "no-op: …" note in `errorSummary` and keeps no undo snapshot. Evidence: 15 probed cases; every unsuitable-type case in IJ1 signals an error.
- Known gaps: the `Engine.quit` javadoc mentions a history-journal flush that does not exist (no journal yet); `Results` tracks only a row count on the native path (a table closed and re-measured to the same size purely natively can drift); the NL proposal for "count the cells" uses Analyze Particles without `add` (no ROI list) — the eight J-12 phrases do; `Workspace` never removes its engine listener on dispose.
- Adoption race (fixed eaddf4a): asynchronous re-hosting of an image IJ1 opened raced a macro's `open(); run(...)` and the command hit another image. Adoption now happens synchronously before any native command (Executer listener) under an adoption lock; keep that invariant.
- Results semantics (changed eaddf4a): the engine's `results()` mirrors the IJ1 table (a `clear` rebuilds it) instead of accumulating a log.
- Shared test JVM: the engine is one per JVM (`sc.fidelis.testkit.ProductEngine`); tests count relative to existing documents and `IjState.reset()` closes IJ1 text windows with `close()` (not `dispose()`), otherwise `IJ.isResultsWindow()` stays true and `Set Measurements...` writes headings into an empty table.
- `verify_runtime`/baseline key: `9403b619…` (LF checkout); see above.

## Contract changes

- (none to `sc.fidelis.engine.api` since baseline.) Additive implementation methods: `DefaultEngine.exportMacroSince(int)` (session slice for verification).
- Requested by A2, not yet done: `SET_DISPLAY_RANGE` optional `channel` (1-based, composites); `NEW_IMAGE` optional `channels`/`frames`. Both are parameter conventions, not interface changes.

## Demo readiness

J-12 (open Blobs → Subtract Background → Gaussian Blur → Otsu dark threshold → Convert to Mask → Watershed → Set Measurements → Measure → Analyze Particles → save) runs end to end through the workspace on the real engine and is verified by `WorkspaceJourneyTest`, `EngineParityTest` and `MacroReplayTest`. Still open for the demo: runbook, the spoken-instruction path through the real engine, BBBC001 image through the real engine, a demo screenshot. Fallback: the prototype (`README.md`, "What works today").

## Resume procedure

1. `cd C:\Users\arnav\Documents\dev\fidelis-dev && git status && git log --oneline -10`.
2. Build and smoke (commands above). If they fail, fix the baseline before anything else.
3. For each agent worktree: `git -C <worktree> log --oneline -5`, `git status`; merge finished work
   into the integration branch only after build + tests + a real run.
4. Update this file and commit.
