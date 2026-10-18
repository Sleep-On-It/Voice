# Submission package — Penny × Fidelis

*Reinvent the Wheel: A Tools for Science Hack, London, 16–18 October 2026. Team Sleep-On-It (Arnav, Ayush, Alex).*

| | |
|---|---|
| Repository | https://github.com/Sleep-On-It/Voice |
| Submitted commit | `main` at **TBD** (filled at the final merge; see "Final SHA and tag") |
| Release tag | **`hackathon-2026-final`** (created on that commit) |
| Licence | Apache License 2.0 for our code and documentation ([LICENSE](../LICENSE), [NOTICE](../NOTICE)); upstream software keeps its own ([third_party/NOTICE.md](../third_party/NOTICE.md)) |

## Problem

Scientists spend their time driving image-analysis software by menu, dialog and macro. Fiji/ImageJ holds decades of
verified algorithms, but the way in is a 1990s window manager and a macro language. Assistants that *approximate* the
analysis are not acceptable in science: every number has to come from the real tool, be reproducible and be auditable.

## What we built

**Penny** makes the real scientific application conversational. You keep using the application; you talk (or type), Penny
acts in it, points at what it did and answers in one spoken sentence with the application's own numbers. Nothing is
mocked: every count comes out of the installed Fiji, Fidelis or QuPath.

**Fidelis** is a faithful modernisation of Fiji/ImageJ: the real Fiji runs unchanged in one persistent JVM behind a modern
single-window workspace (real IJ1 canvases, Fiji's menus, tools and plugins, parameter forms with the exact macro line,
Results at full precision, History with undo/redo). Every action goes through one engine; the structured History
generates a standard ImageJ macro that classic Fiji replays, and the verification suite compares pixels, Results doubles
and ROI vertices exactly against the pinned Fiji.

Together: Penny's voice drives the Fidelis engine through a local bridge inside the Fidelis JVM, and the workspace shows
the conversation, the plan, the confirmation and the highlighted objects on its own canvas.

## Architecture (one page)

```
 microphone ──► Amazon Transcribe ──► gateway (TypeScript, 127.0.0.1:49555) ──► GPT-OSS-120B on Bedrock ──► Amazon Polly ──► speaker
                                          │  instant routes (no model round): zoom, counts, stricter/looser, stop, yes/no
                                          │  33 Fidelis tools, validated schemas, journaled decisions
                                          ▼
                              Fidelis bridge (Java, inside the Fidelis JVM, 127.0.0.1:49558)
                                          │  every operation is a plan assessed by Fidelis's ConfirmationPolicy
                                          ▼
                              Fidelis engine ──► the real Fiji (IJ.run, macros, plugins) ──► History ──► ImageJ macro
```

- `packages/gateway`: tool validation, decisions and audit, MCP, voice and text transport, application profiles
  (Fidelis, stand-alone Fiji, QuPath). [PENNY-ARCHITECTURE.md](PENNY-ARCHITECTURE.md), [PROTOCOL.md](PROTOCOL.md).
- `fidelis-app`: the workstation (`sc.fidelis.{engine,ui,nl,speech,penny}`), its JUnit suite and build tools.
  [FIDELIS-ENGINE.md](FIDELIS-ENGINE.md), [FIDELIS-PROTOCOL.md](FIDELIS-PROTOCOL.md), [INTEGRATION.md](INTEGRATION.md).
- `apps/companion`: the native macOS bar, cursor, audio and desktop control. `adapters/`: the stand-alone Fiji plugin and
  the QuPath extension. `verification/`: reference runner and exact comparators against the pinned Fiji.

## Key features

- Spoken and typed requests that act at once on the real application, with one short answer carrying its real numbers.
- Explicit plans (macros, global settings, steps History cannot undo) wait for Confirm or a spoken "yes"; nothing runs
  without the researcher.
- Segmentation on a duplicate: the original keeps its pixels and receives the objects, so intensities are read from the
  real signal; corrections ("stricter", "split this one") replay as a workflow on a folder.
- Full provenance: every step in History with origin (typed, spoken, menu), exact parameters, undo/redo (including the
  ROI list and Results rows), and a reproducible ImageJ macro that classic Fiji replays to the same state.
- Three application profiles over one gateway: Fidelis, stand-alone Fiji (68 tools, 25 verified recipes) and QuPath.

## Demo sequence

1. Open a real microscopy image in the Fidelis workspace (Blobs, or BBBC nuclei).
2. "Find the nuclei": Penny segments on a duplicate; the objects appear outlined on the original.
3. "How many are there?" / "What is their mean intensity?": answers from the Results table, highlighted on the canvas.
4. A confirmation-required operation (a macro, or "measure everything"): the plan card shows the steps; Confirm.
5. History shows every step with its origin; the generated ImageJ macro is exported or replayed.
6. "Go back": undo removes the objects and their rows and restores what was there before.

**Fallback if voice fails:** the same requests typed into the workspace's *Ask Penny* row reach the gateway's instant
routes without a model round; if the gateway is unreachable, the workspace's own deterministic interpreter proposes the
Fiji command and runs it on Confirm. Fiji's own menus and dialogs are always available in the same window.

## Screenshots

Real screenshots of the running application are listed in [FIDELIS-DEMO.md § Screenshots](FIDELIS-DEMO.md#screenshots)
(`docs/images/fidelis/`): the workspace at 1920×1080 and at the MacBook's size, the dark theme, J-12 on Blobs with its
particles, the proposal sheet, and Penny's listening/thinking/running/answer and plan-confirmation states.

## Verification evidence

| Check | Result | Where |
|---|---|---|
| Fidelis JUnit suite in one live Fiji JVM | 473 tests, 0 failed (18 Oct, Windows, `fix/final-stability`); 468/0 twice on `main` a27a892 (macOS) | `python -I fidelis-app/tools/test.py`, [BUILD-JOURNAL.md](BUILD-JOURNAL.md) |
| J-12 cell counting against the pinned Fiji's own output | exact at every step on four fixtures (71 particles on Blobs, 345 on BBBC001, 300 on BBBC004) | [FIDELIS-VERIFICATION.md](FIDELIS-VERIFICATION.md) |
| Verification harness self-tests | 14/14 | `python -I verification/test_verification.py` |
| Gateway (TypeScript, focused tests, generated references) | 252 tests on macOS CI; `tsc` and `docs:check` clean | `npm run check`, `.github/workflows/verify.yml` |
| Penny × Fidelis bridge journeys over HTTP | Blobs voice journey, 36 catalogue commands through their dialogs, stop/undo/redo/channel regressions | [INTEGRATION.md](INTEGRATION.md), `FidelisBridge*Test` |
| macOS companion | 48 tests (4 preview skips) | `swift test --package-path apps/companion` |

Skips are reported, not hidden: on the Windows machine 14 speech tests skip without a local Whisper venv and 3 J-12
baseline tests skip without the licence-restricted fixtures FX-030/FX-031; 17 gateway tests fail only on Windows
(symlinks, directory fsync, path separators). Counts verify that the software did what was asked; they are not
validated segmentation or biology ([DATASETS.md](DATASETS.md)).

## Known limitations

- Voice needs the team's AWS credits (Transcribe, Bedrock, Polly); without them the typed path and every local tool work.
- The floating bar, cursor and desktop control are a macOS app; on Windows the workspace's Penny strip stands in.
- The AWS-backed model and voice calls do not run on Windows yet (credit-ledger directory sync).
- The companion's audio-backlog recovery fix (BUG-05) is prepared but awaits a build and a live-microphone check on the
  Mac; start_voice while the gateway still holds the previous session is a known race.
- Checkpoints and History snapshots undo image state, ROI lists and Results, not files or arbitrary macro effects.
- The stand-alone Fiji and QuPath profiles were verified on 17 October; the Fidelis profile is the demo.

## Credits and licences

Fidelis builds on Fiji / ImageJ (ImageJ 1.x, ImageJ2, SciJava, ImgLib2, SCIFIO, Bio-Formats), faster-whisper and OpenAI
Whisper; Penny builds on QuPath and is inspired by Clicky. Schindelin et al. (2012) doi:10.1038/nmeth.2019; Rueden et
al. (2017) doi:10.1186/s12859-017-1934-z; Bankhead et al. (2017) doi:10.1038/s41598-017-17204-5. This is an independent
project, not endorsed by the Fiji, ImageJ or QuPath developers. Sample imagery: [DATASETS.md](DATASETS.md).

## Final SHA and tag

Filled by the integrator at Stage E of the final handoff, after the integrated candidate (`integration/final-submission`)
is merged to `main`:

- Submitted commit: `TBD`
- Tag: `hackathon-2026-final` → `TBD`
- Verification of that exact commit: `TBD` (suite numbers and the demo rehearsal)
