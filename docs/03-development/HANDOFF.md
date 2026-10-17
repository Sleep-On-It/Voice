---
id: DEV-05
title: Development handoff (pause of 2026-10-17)
stage: 03-development
status: Handoff
priority: P0
source_revisions: [Fiji archive 20261013-1517 (lock f6695397 / 9403b619 LF), imagej/ImageJ@v1.54p, integration branch development/fidelis-workstation @ 1d643bc]
related_requirements: [FR-SYS-001, FR-SYS-002, FR-PROC-001, FR-ROI-001, FR-MEAS-003, FR-HIST-002, FR-MACRO-001, FR-NL-001, FR-SPEECH-004, NFR-FID-001, NFR-MAINT-004]
related_decisions: [DEC-010, ADR-002, DEC-048]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# Development handoff — Fidelis workstation, paused 2026-10-17

Written for the three independent development workstreams that will continue Fidelis. Everything
stated here was run on 2026-10-17; where a number is given it was produced by the pinned Fiji or by
the test suite, not estimated. Read `ACTIVE-DEVELOPMENT.md` for the running record and
`VERIFICATION-RESULTS.md` for the scientific evidence.

## 1. Where everything is

| Item | Value |
|---|---|
| Authorised remote | `Sleep-On-It/Voice` (primary since 17 Oct 2026; `reverez/Voice` no longer required). Never push to `AyushGupta05/Voice` or `Sleep-On-It/Fidelis`; never merge into `main`; never force-push. |
| Integration branch | `development/fidelis-workstation` — **this is the baseline**. All agent branches are merged into it (non-fast-forward, so their commits are published through it). |
| Agent branches (merged, pushed for reference) | `agent/engine` (A1), `agent/ui` (A2), `agent/voice` (A3), `agent/verify` (A4) |
| Earlier branches | `review/architecture-gate` (adversarial review R01), `spike/architecture-gate` (EXP-01…06), `migration/import-fidelis` (history import), `main` (fa70497, untouched) |
| Entry point | `fidelis-app/tools/run.py --isolated-home` → `sc.fidelis.Main` → `Bootstrap` (bundled Zulu 21 of the pinned Fiji; no Maven, no Gradle) |
| Pinned Fiji | `C:\Users\arnav\Documents\dev\Fiji`, archive 20261013-1517, ImageJ 1.54p; `verify_runtime` → `f66953972bb5…` (CRLF checkout) = `9403b619…` (LF clones); baselines keyed by `9403b6197ac4bf75`. The installation was never modified. |
| Local worktrees (lead's machine) | `fidelis-dev` (integration), `fidelis-agent-{engine,ui,voice,verify}`, `fidelis-arch-gate`, `fidelis-spikes`, `fidelis-voice-migration`, `click-to-batch` (`docs/analysis-design`, the original docs checkout) |
| Git-ignored but needed locally | `fixtures/cache/` (run `verification/fetch_fixtures.py`), `fidelis-app/build/`, `fidelis-app/lib/*.jar` (run `tools/fetch_junit.py`), `.venv-stt` and `external/models/tiny.en` for speech |

Commands (Python = `C:\Users\arnav\Documents\dev\click-to-batch\.venv\Scripts\python.exe -I`):

```
python fidelis-app/tools/fetch_junit.py              # once per clone
python fidelis-app/tools/build.py                    # app + tests, javac of the bundled JDK
python fidelis-app/tools/run.py --isolated-home      # the workstation
python fidelis-app/tools/run.py --smoke --isolated-home
python fidelis-app/tools/test.py [--select-class FQCN ...]   # ~6 min for the whole suite
python fidelis-app/tools/run.py --isolated-home -- --screenshot fidelis-app/build/shots/j12.png --scenario j12
python docs/tools/check_docs.py                      # documentation CI (0 errors expected)
```

## 2. What is implemented and verified

Architecture in force: **hybrid B** (DEC-010 as revised by the architecture gate): one persistent,
non-headless Fiji JVM with the main window hidden; an in-process engine; real IJ1 `ImageCanvas`
instances hosted in the modern workspace (hidden `ImageWindow` + `HostCanvas` with `canEnlarge()==null`
and a null layout); typed actions executed as real `IJ.run` with complete option strings; success by
postconditions; native interactions observed and recorded; history authoritative; macros generated
from history.

| Area | Package | Verified by |
|---|---|---|
| Engine: documents, hosting, focus, adoption of IJ1-created images, transient helper images ignored | `sc.fidelis.engine` (`DefaultEngine`, `Registry`, `Observers`) | `EngineDocumentsTest`, `EngineLifecycleTest` |
| Catalogue of 953 commands, 53 with complete parameter schemas from `docs/data/commands*.csv`; validation; option-string generation | `Catalogue`, `Options`, `CommandRunner` | `EngineCatalogueTest`, `EngineCommandTest`, `EngineConventionsTest` |
| Execution with Guard (error text, exception windows, modal dialogs captured and disposed), postconditions, snapshot undo/redo, no-op rule (identity completes with a note; failure only on an error signal or a vetoed command) | `CommandRunner`, `Guard`, `Snapshots` | `EngineLifecycleTest`, `EngineErrorTest`, `EngineScienceTest` |
| ROI lists per document mirrored into the IJ1 ROI Manager (singleton resolved on every use), float geometry, exact macro lines | `RoiLists`, `RoiCodec`, `DirectOps` | `EngineScienceTest`, `EngineSingletonsTest` |
| Results rows at full double precision mirroring the IJ1 table (rebuilt on `clear`) | `Results` | `EngineParityTest`, `WorkspaceJourneyTest` (TSV bit-exact) |
| Globals captured and shown (`blackBackground`, measurements, precision, conversions); `black` on single-image Convert to Mask handled as an explicit AC5 global change | `Globals`, `CommandRunner` | `EngineScienceTest` |
| History and macro export (D07 rules), `exportMacroSince(int)` | `History`, `MacroExport` | `MacroReplayTest` (E7: real export replayed in reference Fiji equals the live session) |
| Macro/script execution (CODE) with cancellation and observation | `CodeRunner` | `EngineLifecycleTest`, `EngineScienceTest` |
| Quit (refuses with unsaved changes; discard closes everything) | `DefaultEngine.quit` | `EngineQuitTest`; UI `--close-window` exit 0 |
| Workspace: Fiji menu bar, tool palette, hosted canvases with viewport control, tabs, parameter forms with the exact macro line, Threshold (IJ1 overlay) and B&C panels, ROI list, Results, History & macro, Log, Activity, status bar, legacy-window tracker, before/after compare, stack/channel sliders | `sc.fidelis.ui` | `WorkspaceViewportTest`, `WorkspaceU2Test`, `WorkspaceJourneyTest`, `ConfirmationUiTest`, inspected screenshots in `screenshots/` |
| Command bar: ranked search, typed instructions → proposal sheet with derived/explicit parameters, Fiji defaults, "settings that affect this result", confirmation policy (explicit acknowledgement for AC4+, `black`, CODE, not-READY) | `CommandBar`, `ProposalSheet` | `ConfirmationUiTest`; `screenshots/j12-proposal.png` |
| Speech: persistent `speech/server.py` (faster-whisper tiny.en, hash-verified, VAD, JSON lines) and `WhisperSpeechService` (restart after crash, process-tree kill, vocabulary prompt) | `sc.fidelis.speech`, `speech/` | `WhisperSpeechServiceTest` (13), `speech/test_server.py` (10) |
| NL: `DefaultNlService` (grammar + catalogue retrieval + deterministic validator/policy, no code generation), `VoiceToProposal`, `ConfirmationPolicy`, `vocabularyPrompt` | `sc.fidelis.nl` | eval 124/124 on `FakeEngine` and on `DefaultEngine`, 0 unsafe executions; `NlRealEngineJourneyTest` |
| Verification: fixtures manifest, reference runner (pinned Fiji, GUI and headless), dumper, comparators, baselines FX-001/003/030/031 (+ FX-003 otsu-light), GUI smoke with OCR of the status bar, replay | `verification/`, `sc.fidelis.verification`, `sc.fidelis.testkit` | `HarnessSelfTest`, `InJvmCrossCheckTest`, `EngineParityTest`, `SpeechToExecutionTest` |

### The demo journey J-12 (open → Subtract Background → Gaussian → Otsu dark → Convert to Mask → Watershed → Set Measurements → Measure → Analyze Particles → save)

| Fixture | Measure (Area, Mean) | Particles = ROIs | Against |
|---|---|---|---|
| FX-003 Blobs | 65024, 155.9435746801181 | 71 | pinned-Fiji baseline, exact at every step (pixels, Results doubles, ROI vertices, globals, saved TIFF) |
| FX-001 two-squares | 4096, 30.87890625 | 2 | exact |
| FX-030 BBBC001 A03f00d0 | — | 345 | exact vs the reference runner; published manual counts 350/362 reported only |
| FX-031 BBBC004 1GRAY | — | 300 | exact; ground truth 300 reported only |

Verified three ways on the real engine: `EngineParityTest` (engine API), `WorkspaceJourneyTest` (through
the workspace, incl. the Results panel's TSV parsed back bit-exactly), `MacroReplayTest` (the exported
macro replayed in the reference Fiji in a separate JVM reproduces the live session). The UI scenario
`--scenario j12` asserts the same numbers and produced `screenshots/j12-blobs.png` (71 numbered ROIs on
the real canvas). The ten spoken/typed J-12 phrases resolve to these exact requests and, executed in
order on the real engine, give the same numbers (`NlRealEngineJourneyTest`). The spoken fixture
`invert-command.wav` → transcript → proposal → confirm → execute changes the pixels to the
independently computed 255−v (`SpeechToExecutionTest`).

### Test and build outcome at the pause

- `build.py`: OK (87 main sources, 48 test sources; 15.9 k / 6.1 k lines).
- `test.py` on 1d643bc: **378 tests, 0 failed, 0 skipped** (~6 min; the replay test launches the reference Fiji twice). Earlier points: 211/0/1, 217/0, 359/0.
- `run.py --smoke --isolated-home`: `Invert pixel(10,10) 40 -> 215 : OK`, exit 0 in 5.9 s.
- `--scenario j12 --close-window`: numbers asserted, discard confirmation shown after 108 ms, exit 0 in 15.5 s.
- `docs/tools/check_docs.py`: 0 errors, 0 warnings. Documentation CI workflow exists (`.github/workflows/docs-check.yml`).
- Fiji pin verified after all runs (`verify_runtime` → `f66953972bb5…`); Java Preferences registry hive identical to the pre-experiment backup (`repo-backups-2026-10-17\JavaSoft-Prefs-before-arch-gate.reg`, 0 differing lines).

## 3. Architecture decisions taken during development (record them in DECISIONS.md when the baseline is agreed)

1. Hybrid B with the **real IJ1 canvas** re-parented (EXP-01 S3) rather than a re-implemented viewer; hosted-window invariants: `canEnlarge()==null`, `setLayout(null)`, viewport order size → source rect → magnification, never `zoomOut/unzoom`, wheel forwarded to the hidden window, `WindowManager.setCurrentWindow` for focus.
2. Success of a typed command is judged by **postconditions and the Guard**, never by `IJ.run` returning (IJ1 swallows exceptions). Identity results are COMPLETED with a note (E8), not failures.
3. **Adoption before native commands**: images IJ1 opens behind the engine's back are re-hosted synchronously on the calling thread before any native command runs (Executer listener) under an adoption lock. Asynchronous adoption raced `open(); run(...)` and the command hit another image.
4. **Results mirror the IJ1 table** (rebuilt on `clear`) rather than an accumulating log; history is the log.
5. IJ1 singletons (ROI Manager, Results/Log windows) are **resolved on every use**, never cached.
6. `black` on single-image Convert to Mask is an explicit AC5 global change; `Prefs.blackBackground` is never set silently.
7. Reserved request keys `transcriptRef`, `parentActionId` are stripped from options and recorded in history.
8. Speech never executes directly; every spoken proposal requires confirmation; AC4+/`black`/CODE/not-READY require an explicit acknowledgement (ConfirmationPolicy).
9. No Maven: build/run/test through `fidelis-app/tools/*.py` with the bundled JDK, Jaunch-equivalent JVM flags, isolated `user.home`; test JVM ends through `ExitListener` (bounded dispose, then `halt`) because `System.exit` deadlocks on the SciJava shutdown hook.
10. The `sc.fidelis.engine.api` contract is frozen; additive implementation methods only (`DefaultEngine.exportMacroSince`, `HostCanvas.TRANSIENT_PROPERTY`).

## 4. Unresolved problems and known limitations

- Live microphone capture is untested by a person (fixture WAVs are tested; `AudioCapture` opens a line).
- Only 53 of 953 commands have complete parameter schemas; the rest run through Fiji's own dialog (provenance LEGACY_DIALOG; preview dialogs may drop options in the Recorder).
- `NEW_IMAGE` rejects `fill=Random` and `type="16-bit composite-mode"`; `channels`/`frames` were added in E8 but the macro form was verified by replay, not against IJ1's Recorder.
- The NL proposal for "count the cells" uses Analyze Particles without `add` (no ROI list); the eight explicit J-12 phrases do add. "remove outliers radius 2 threshold 50" is routed to the threshold grammar (safe but wrong).
- NL preview for Convert to Mask shows `run("Convert to Mask");` while the engine records `run("Convert to Mask", "method=[Default] background=[Default]");` (both valid; align the preview).
- Session ops emit no QUEUED/RUNNING events (only COMPLETED).
- `Engine.quit` javadoc mentions a history-journal flush; no journal exists yet.
- `Results` tracks only a row count on the native path: a table closed and re-measured to the same size purely natively could drift.
- `Workspace` never removes its engine listener on dispose (only matters in tests).
- One order-dependent flake seen once (A1, not reproduced): `WorkspaceU2Test.compareShowsTwoRealCanvases` "timed out waiting for before snapshot" (`U2Scenario.java:180`).
- Right dock at 380 px shows few Results columns; Results and ROI list are tabs, not side by side.
- GUI smoke's OCR check is Windows-only; robot screen capture is black without an interactive session (screenshots are composited from the real components).
- Plots, histograms and other special IJ1 windows are legacy windows, not documents (counted in `windowsOpened`).
- DEC-010 is implemented but formally still "proposed" in `docs/DECISIONS.md`.

## 5. Suggested split into three independent workstreams

| Workstream | Owns | First tasks |
|---|---|---|
| Engine and science | `sc.fidelis.engine/**`, `verification/**`, engine tests | more complete schemas (the 900 uncatalogued commands, starting with the Analyze/Process menus), stacks/hyperstacks and Bio-Formats through `OPEN`, the history journal, native-path Results drift, QUEUED/RUNNING for session ops |
| Workspace | `sc.fidelis.ui/**`, UI tests | Results/ROI side-by-side layout, channels panel, legacy dialog handling as forms, listener removal on dispose, keyboard shortcuts parity, the `compare` flake |
| Language and speech | `sc.fidelis.{nl,speech}/**`, `speech/`, NL tests | live-microphone verification, phrase fixtures for the J-12 set, preview/record macro-line alignment, retrieval on long clauses, multi-step plans with confirmation |

Shared rules that kept the team safe: strict file ownership; one JVM per worktree at a time; `--isolated-home`; no edits to the Fiji installation or the registry; numbers, not appearance, decide scientific correctness; never weaken a test to go green; the lead merges.
