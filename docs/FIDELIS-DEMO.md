# Fidelis demo (J-12 cell counting)

The demo shows **one real Fiji engine, several ways to drive it**: the native image tools, the Fiji menus, typed
commands, a spoken instruction, and the macro that the session generates and that classic Fiji replays. Every number
below comes from the pinned Fiji itself (`verification/baselines/FX-003/9403b6197ac4bf75/gui`) and is asserted by
`EngineParityTest`, `WorkspaceJourneyTest` and `MacroReplayTest` ([FIDELIS-VERIFICATION.md](FIDELIS-VERIFICATION.md)).
With Penny attached, the same workstation also takes the spoken requests of [INTEGRATION.md](INTEGRATION.md).

## 1. Prerequisites (one machine, offline except for the first downloads)

| Need | Where | Check |
|---|---|---|
| Pinned Fiji (archive 20261013-1517, ImageJ 1.54p, bundled Zulu JDK 21) | macOS: `.local/fiji/Fiji.app` from `python3 script/setup-fiji.py`; Windows: an extracted copy outside the repository, named by `FIDELIS_FIJI_DIR`. Never modified. | `python3 -I verification/reference_runner.py --verify` (file inventory against `external/fiji.lock.json`) passes |
| Python 3.11+ for the tools | system `python3` (macOS) or a virtualenv (Windows) | `python3 -I fidelis-app/tools/build.py --no-tests` prints `[build] OK` |
| Speech (optional, for the local spoken step) | `.venv-stt` (Python 3.11, faster-whisper from `speech/requirements.lock.txt`) and the hash-pinned `tiny.en` model from `speech/setup_speech.py` under `external/models`; `-Dfidelis.stt.python` names the interpreter | the Mic button is enabled a few seconds after launch; otherwise the status bar says why |
| Fixtures | `fixtures/cache/FX-003/blobs.gif` (ImageJ sample), BBBC001 under `fixtures/cache/FX-030` | `python3 -I verification/fetch_fixtures.py --only FX-003 --only FX-030` if missing |

On Windows write `python -I` for `python3 -I`.

## 2. Launch

```sh
python3 -I fidelis-app/tools/build.py --no-tests
python3 -I fidelis-app/tools/run.py --isolated-home      # the workstation alone
./script/run-fidelis.sh                                   # macOS: the workstation with Penny's bridge and gateway profile
```

`run.py` starts the bundled JDK directly with Fiji's own JVM flags (DPI-aware), creates the SciJava context with the
legacy layer, keeps Fiji's main window hidden and opens the Fidelis workspace on the real engine. The status bar reads
`Engine: ready`. Expect about 3 s.

`--isolated-home` keeps ImageJ preferences out of the user's profile. Nothing in the demo writes to the Fiji
installation or to the registry.

## 3. The demo (10 minutes)

1. **Open a real microscopy image.** File > Open… → `fixtures/cache/FX-003/blobs.gif` (or drag it in). The tab shows
   the real IJ1 canvas (zoom with the wheel; the native rectangle, oval, polygon and wand tools in the palette are
   Fiji's own). Status bar: `256x254 pixels; 8-bit; 64K`.
2. **A native tool.** Draw a rectangle on the image, then Analyze > Measure. The ROI panel shows the selection with
   float geometry; the Results panel shows the row at full precision; History shows the step with provenance NATIVE
   (observed) or TYPED (menu). Undo is available.
3. **Typed commands with real parameters.** In the command bar type `subtract background` → choose *Process > Subtract
   Background…* → the Parameters form shows every macro key with its Fiji default and the exact macro line
   (`run("Subtract Background...", "rolling=50");`). Run. Then `gaussian` → sigma 2 → Run.
4. **Threshold with the overlay visible.** Right dock, *Threshold* tab: method **Otsu**, tick **Dark background**
   (Blobs has an inverting LUT; plain Otsu gives 66 particles, Otsu dark 71). The red overlay is IJ1's own and nothing
   changes in the pixels. *Apply → Convert to Mask…* opens the form with `black` shown as the global
   `Prefs.blackBackground` it is (never set silently) → Run.
5. **Binary and measurements.** Process > Binary > Watershed. Analyze > Set Measurements… (area, mean, min, integrated;
   decimal 9). Analyze > Measure: **Area 65024, Mean 155.9435746801181**.
6. **Count.** Analyze > Analyze Particles… size `20-Infinity`, show Masks, display, clear, include, summarize, add →
   Run. Results: **71 rows**; ROI list: **71 ROIs**; a `Mask of blobs.gif` tab appears. Click a row or an ROI: the
   selection is shown on the real canvas.
7. **History and macro.** Bottom dock *History & macro*: every step with origin, provenance, parameters and outcome; the
   generated macro updates live. *Export .ijm…* writes it. Undo the watershed and redo it: the pixels are restored from
   the snapshot (hash-verified in tests).
8. **Replay in classic Fiji.** `python3 -I verification/replay.py --macro <exported.ijm> --run-dir <dir>` runs the
   macro in the pinned Fiji in a separate JVM and dumps the final state; `MacroReplayTest` does this automatically and
   compares pixels, Results doubles and ROI vertices exactly.
9. **Spoken instruction.** Click **Mic**, say *"count the cells"* (or type it in the command bar). Without Penny, the
   local Whisper worker transcribes it and the proposal sheet shows the resolved command, its parameters and the macro
   line; nothing runs until **Confirm**. After confirmation the same engine path executes it and History records origin
   `NL_SPOKEN`. Without a microphone, `run.py --isolated-home -- --instruction "count the cells"` shows the same sheet.
   With Penny attached (`./script/run-fidelis.sh`), the Mic hands the microphone to Penny's live voice instead and the
   Penny strip shows the conversation; see [INTEGRATION.md](INTEGRATION.md) "Voice in the workspace".
10. **A second dataset.** Open `fixtures/cache/FX-030/extracted/human_ht29_colon_cancer_1_images/AS_09125_050118150001_A03f00d0.tif`
    (BBBC001, Hoechst-stained HT29 nuclei) and repeat steps 3–6: 345 particles (the published manual counts are 350 and
    362; reported only, as in [FIDELIS-VERIFICATION.md](FIDELIS-VERIFICATION.md)).

## 4. Verification commands

```sh
python3 -I fidelis-app/tools/build.py               # compiles app and tests with the bundled JDK
python3 -I fidelis-app/tools/test.py                # the whole suite in one live Fiji JVM (several minutes)
python3 -I fidelis-app/tools/run.py --smoke --isolated-home   # engine self-test, exit 0
python3 -I verification/test_verification.py        # harness self-tests (comparators, dumper, stored baselines)
python3 -I verification/gui_smoke.py --timeout 150  # launches the app, screenshot, luminance checks
python3 -I verification/baseline.py --check --fixture FX-003   # re-run a reference baseline and compare, nothing overwritten
python3 -I fidelis-app/tools/run.py --isolated-home -- --screenshot fidelis-app/build/shots/demo.png --open fixtures/cache/FX-003/blobs.gif
```

## 5. What is not in the demo

- Live microphone capture of the local Whisper route has not been verified by a person (the fixture WAV path is tested).
- Only commands with a catalogued parameter schema (53 of about 950) open as typed forms; the others run through Fiji's
  own dialog and are recorded with provenance LEGACY_DIALOG.
- A filter that leaves the pixels unchanged completes with a "no-op" note in History and nothing to undo.
- Plots, histograms and other special IJ1 windows are shown as legacy windows, not as documents.

## Screenshots

Real screenshots of the workstation on the real engine are in `docs/images/fidelis/` (J-12 on Blobs, the proposal
sheet, the compare view, and the Penny strip states):

- Workspace layout at the demo resolution: [j12-layout-1920x1080.png](images/fidelis/j12-layout-1920x1080.png) and at
  the MacBook's working size [j12-layout-1500x940.png](images/fidelis/j12-layout-1500x940.png); the opt-in dark theme
  [j12-dark-1500x940.png](images/fidelis/j12-dark-1500x940.png); labelled tools
  [j12-labelled-tools-1500x940.png](images/fidelis/j12-labelled-tools-1500x940.png); the macro editor dock
  [j12-editor-1500x940.png](images/fidelis/j12-editor-1500x940.png).
- J-12 on Blobs: [j12-blobs.png](images/fidelis/j12-blobs.png), with the particle ROIs
  [j12-blobs-roi.png](images/fidelis/j12-blobs-roi.png); the before/after compare view
  [real-compare.png](images/fidelis/real-compare.png).
- Typed and spoken instructions: the proposal sheet [j12-proposal.png](images/fidelis/j12-proposal.png), a typed
  instruction proposed [j12-typed-proposal.png](images/fidelis/j12-typed-proposal.png) and executed
  [j12-typed-executed.png](images/fidelis/j12-typed-executed.png).
- Penny in the workspace: [penny-listening.png](images/fidelis/penny-listening.png),
  [penny-thinking.png](images/fidelis/penny-thinking.png), [penny-running.png](images/fidelis/penny-running.png),
  [penny-answer-flash.png](images/fidelis/penny-answer-flash.png) and
  [penny-flash-onscreen.png](images/fidelis/penny-flash-onscreen.png), the plan card waiting for Confirm
  [penny-plan-confirm.png](images/fidelis/penny-plan-confirm.png) and its dark variant
  [penny-plan-confirm-dark.png](images/fidelis/penny-plan-confirm-dark.png).
