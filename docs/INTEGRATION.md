# Penny × Fidelis integration

How the three integration parts implement [FIDELIS-PROTOCOL.md](FIDELIS-PROTOCOL.md): the Java bridge inside Fidelis
(`sc.fidelis.penny`), Penny's gateway profile (`LUMA_APP=fidelis`) and the workspace voice surface
(`sc.fidelis.penny.ui`). Each part keeps its own section.

## Java bridge in Fidelis (`sc.fidelis.penny`)

`sc.fidelis.penny.FidelisBridge` serves the contract inside the Fidelis JVM; `PennyOps` turns each operation into a plan
of complete engine requests submitted through `Engine.submit` (origin `NL_SPOKEN`/`NL_TYPED`, `transcriptRef` = the
gateway's `taskId`) and assesses it with Fidelis's `ConfirmationPolicy`. `Params` fills COMMAND parameters from the
catalogue schema, `Highlight` builds the screen payload through `PennyUi`, `PennyCommands` lists the catalogue ids, and
`PennyPlans` is what the workspace's Confirm and Cancel buttons call. `Bootstrap` starts the bridge after the engine is
registered and `Bootstrap.exit` stops it. The decisions taken while implementing it (Set Measurements only when a column
is missing, the value-aware `black` rule, `undo {steps}`, `undoSteps`, `/health` behind the token) are recorded in
[FIDELIS-PROTOCOL.md](FIDELIS-PROTOCOL.md).

### Starting Fidelis with the bridge on macOS

1. Once per checkout: Fiji at `.local/fiji/Fiji.app` (`python3 script/setup-fiji.py`; a git worktree under
   `.local/worktrees/` uses the main checkout's), and Penny's bridge token at `.local/state/bridge-token` (Penny's setup
   or `./script/run-fidelis.sh` creates it). For the tests: `python3 -I fidelis-app/tools/fetch_junit.py`.
2. Build: `python3 -I fidelis-app/tools/build.py` (`--no-tests` for the app only).
3. Run: `python3 -I fidelis-app/tools/run.py --isolated-home`. The bridge starts by itself when the token is found
   (walking up from the jar, `-Dfidelis.repo.dir` and the working directory, so a worktree finds the main checkout's
   token) and logs `[fidelis-penny] bridge listening on 127.0.0.1:49558 (confirm=auto)`. Penny's launcher
   `./script/run-fidelis.sh` does the same with `--prop luma.root=<checkout> --prop fidelis.penny=true` and points the
   gateway at it.
4. Check it: `curl -s -H "Authorization: Bearer $(cat .local/state/bridge-token)" http://127.0.0.1:49558/health`
   (requests without the token get 401, requests with an `Origin` header 403).
5. Smoke with the bridge, without a window: `python3 -I fidelis-app/tools/run.py --smoke --isolated-home --prop
   fidelis.penny=true --prop fidelis.penny.port=49568` prints `[fidelis] penny smoke: … self-check OK`.

| Setting (`run.py --prop KEY=VALUE`) | Effect |
|---|---|
| `fidelis.penny=true` / `false` | `true`: log why when no token is found (the bridge starts whenever a token is found); `false`: never start |
| `fidelis.penny.port=N` | listen on `127.0.0.1:N` (default 49558; the stand-alone Fiji bridge uses 49557, QuPath 49556, the gateway 49555) |
| `fidelis.penny.confirm=always` | every plan waits for confirmation (default `auto`: only explicit plans wait) |
| `luma.root=<dir>` (or `LUMA_ROOT`) | where `.local/state/bridge-token` is read; `LUMA_BRIDGE_TOKEN` (32+ characters) overrides the file |

Only one Fidelis JVM per checkout (and per port) at a time.

### Verification status (bridge, 17 Oct 2026, macOS arm64)

- Engine changes made for the bridge, all opt-in (requests without the new keys behave as before):
  `DefaultEngine.runOp` refuses a request whose reserved `expectedHistorySize` no longer matches the history
  (PRECONDITION_FAILED, `STATE_CHANGED`) and `Options.RESERVED` lists the key; `CommandRunner.runLegacy` ignores
  reserved keys instead of refusing them; `DirectOps` applies an explicit TargetScope plane to threshold/display
  operations and accepts `ROI_LIST_ADD {fromDocument, replace}` (copy another document's ROI list).
- segment_cells now works on a duplicate (`<title>-mask`) and copies the objects to the original's ROI list, so the
  original keeps its pixels and `measure {scope:"rois"}` reads the real signal (the gateway's request; it matches
  stand-alone Penny Fiji). `context.document.binary` is true for a mask.
- Implemented: the 28 operations of the contract plus `dialog` and `dialog_action` (any catalogue command through Penny,
  its original Fiji dialog filled or cancelled through the bridge).
- Protocol-tested (`python3 -I fidelis-app/tools/test.py --select-class …`, JUnit in a live Fiji JVM with the product
  engine): `sc.fidelis.penny.FidelisBridgeTest` (10: token, Origin, approval, context binding, contextKey, plans and
  workspace hooks, failures, legacy Sharpen and its refused undo, particle counts with display on/off, macro
  confirmation, discarded plans, `confirm=always`, highlight geometry),
  `PennyCatalogueTest` (5: every hard-coded catalogue id with its schema, recipe provenance, explicit `true` defaults,
  the value-aware `black` rule, operation names), `FidelisBridgeJourneyTest` (6: the Blobs voice journey over HTTP; the
  exported macro replayed in plain ImageJ gives the same 67 objects and 21886 px²;  segment_cells leaves the original untouched and makes a binary mask; Li after Otsu matches Li on a
  fresh copy, 68; per-ROI
  measurements on a 3-slice stack use each ROI's plane, 62.0 and 162.0 as read from the pixels; a second Analyze Particles
  replaces the ROI list), `FidelisCatalogueWalkTest` (3: 36 catalogue commands across File/Edit/Image/Process/Analyze/
  Plugins complete, stop at their dialog and cancel cleanly, or are refused with a reason, none hangs; Variance… filled and
  pressed OK through `dialog_action`; `commands` finds TrackMate, Bio-Formats Importer, Coloc 2, 3D Objects Counter,
  Skeletonize, Directionality and Analyze Skeleton). The four classes together: 28 tests, 0 failed (FidelisBridgeTest
  includes the execution-binding test, a workspace action queued before step 3 stops the plan there, and the
  competing-analysis test, a workspace Analyze Particles right after Penny's stops the plan before it copies objects). With the NL, UI-confirmation
  and speech-to-execution classes in the same run: 297 tests, 0 failed (4 skipped: the Whisper interpreter is not
  installed on this Mac). Full suite (`test.py`, all classes, final branch state c721e93): 423 tests, 6 failed, none in
  or using the bridge: `EngineNativeSyncTest` ×3 (known, engine work in progress), `MacroReplayTest` ×2
  (`verification/reference_runner.py` looks only for Windows `.exe`/`.bat` launchers) and `InteractiveJourneyTest` ×1
  (Subtract Background's `separate` field). Two earlier full runs were cut short by a native macOS AppKit text-input
  crash inside the UI tests (`TextInputUIMacHelper` / "Exception raised accessing first rect for character range"),
  which then broke later classes; it does not involve the bridge and did not recur in the final run.
- Real-app-tested: the full workspace started with `run.py` and the bridge on a spare port, driven over HTTP the way the
  gateway does (context, then the approved change bound to it). Final state: open Blobs, `segment_cells` 67 objects on
  `blobs-mask` in 0.82 s with the original focused, not binary and holding the 67 ROIs; `measure {scope:"rois"}` 67 rows,
  mean intensity 190.34 (146.3–220.2) from the original pixels; Variance… stopped at its dialog, filled and pressed OK
  through `dialog_action` (recorded `LEGACY_DIALOG`); Gamma… cancelled (`status:"cancelled"`); every history step
  `NL_SPOKEN` with the task id. Earlier: `run_macro` waiting then confirmed, `undo`. Smoke through `Bootstrap` with
  and without `fidelis.penny=true` (exit 0, and 5 when the port is taken).
- Review: the repository's autoreview helper (Codex, branch diff against `integration/fidelis-penny`). The first run was
  a reviewer failure (its one finding used an absolute path and was discarded; no review produced). The second reported
  11 findings (3 P1: context check before plan construction, ImageJ statistics mutating the live processor's ROI, per-ROI
  plane; 8 P2: atomic confirm, keeping the bridge busy until the engine ends, failure wording, highlight with `add:false`,
  `true` defaults, `display auto` channel, `undoSteps` as an upper bound, `object_stats` units); all were checked against
  the code, accepted and fixed with tests. The third reported 5 (3 P1: segmentation replacement must check the undo
  history, the context binding must hold at execution, reads must not call ImageJ's side-effecting getters; 2 P2: ROI
  planes resolved against the starting plane, `object_stats` with an empty latest population); all accepted and fixed
  (pre-step foreign-operation wait and history check, plane pinning, `LiveRead`, a replacement allow-list later superseded
  when segment_cells moved to a duplicate). The fourth
  reported 5 (2 P1: the approval must hold when the engine dequeues a step, and the history cursor must be taken before
  submission; 3 P2: dialog answers bound to their dialog, the cancel marker set before the press, the smoke exit status);
  all accepted and fixed, the first with an opt-in engine check (`expectedHistorySize`, subcode `STATE_CHANGED`). The fifth reported 6 (3 P1: the step baseline taken before validation, undo
  snapshot identities for the automatic replacement, plane binding of threshold/display steps; 3 P2: statistics after a
  competing action, failures without a record, queued EDT edits after a timeout); all accepted and fixed (the
  replacement finding was resolved by segmenting a duplicate, which needs no undo at all). The sixth reported 5: the
  selection before duplicating (P1), mask-ROI planes, EDT task start tracking and results while foreign work is still
  active (P2) were fixed; the remaining P1 (a native canvas selection edit recorded 250 ms late can slip past the
  execution binding) is documented as a residual needing an engine-level selection fingerprint.
- Not yet tested: with the workspace's `PennyUi` implementation (screen targets are empty until it is registered), with
  the gateway and a voice turn end to end, and `segment_cells` on stacks or multichannel images.

### Verification (18 Oct 2026, macOS arm64, engine suite on `integration/engine2`)

Follow-up to the status above, on the integrated `main` (18567f8) plus the engine work recorded in
[FIDELIS-VERIFICATION.md section 12](FIDELIS-VERIFICATION.md): the Threshold-adjuster flake of
`InteractiveJourneyTest` fixed in the test's driving (IJ1's adjuster constructor applies its own Default auto-threshold
after showing the frame; 10 of 10 consecutive runs green), the speech venv and pinned model set up on this Mac so the
14 speech cases run (fixture WAV transcribed as "Invert the image."), the one order dependence found by
`--random-order 17` fixed at its source (`sc.fidelis.engine.MacroState`: Fiji's start-up leaves a finished macro
interpreter in IJ1's static `Interpreter.instance`, which made every off-EDT `show()` wait 2 s), and three engine items
(live `LogLine` events, a token-based CODE capability scan, ROI Manager sync after CODE), each with a test.

Final numbers (`python3 -I fidelis-app/tools/test.py`, branch head 473295a): default order 461 tests, 0 failed, 0 skipped (173 s), twice (the second 174 s); `--random-order 17`, `42` and `7`: 461, 0 failed, 0 skipped each (172-173 s); `--random-order 17 --random-methods`: 461, 0 failed, 0 skipped (173 s). The 461 are the 451 of the integrated `main` plus the 10 of `EngineCodeEffectsTest`; the 14 speech cases that were aborted by assumption before now pass inside these numbers (every run transcribed the fixture WAV).
`python3 -I verification/test_verification.py`: 14 passed (compare, stored baselines, the headless dumper self-test in the pinned Fiji). Codex autoreview: 15 runs (one reviewer failure, 35 findings over 13 runs checked, fixed or documented as design limits), the final run on the branch head clean.

Merged into `main` on 18 Oct 2026 (merge e199df1). On the merged tree the full suite is 468 tests (461 plus the seven
bridge tests added since the branch base). One failure appeared only there and only in the full suite:
`FidelisBridgeTest.aFileChooser…` — Coloc 2's "Save as PDF" is an AWT `FileDialog`, and on this Mac its native panel's
creation blocked the AppKit thread (`-[NSSavePanel _initBridgeAndStuff]` waiting on the panel service) while the
bridge's `dispose()` on the event thread waited for AppKit's keyboard-locale answer: 20 s per call, 6 of 6 full runs,
also on the pre-merge tree, never when the class ran alone. Fix at the source: `DefaultEngine.create` sets
`Prefs.useJFileChooser = true`, so IJ1's open/save prompts in Fidelis are Swing `JFileChooser`s like the workspace's own
Open and Export; `DialogDriver.isFileChooser` recognises either kind and cancels a Swing one with `cancelSelection()`.
Full suite after the change: 468 tests, 0 failed (two runs); Codex autoreview of the change: no findings.

## Voice in the workspace

Penny's conversation runs inside the Fidelis workspace. There is a one-line **Penny strip** under
the command bar, and it grows into a **plan card** while the bridge shows a plan. The **Mic** button gives Penny the
microphone, and typed requests reach her through an **Ask Penny** row. The code is in `fidelis-app/src/main/java/sc/fidelis/penny/voice`
(the gateway client and audio) and `.../penny/ui` (the strip, the canvas highlight and the `PennyUi` hooks). The workspace
changes are small and additive: `LiveVoice`, plus hooks in `CommandBar`, `Banners`, `Workspace` and `ScreenshotRun`.

### The strip

Screenshots, all in the default **light** theme: `docs/images/fidelis/penny-listening.png`,
`penny-thinking.png`, `penny-running.png`, `penny-answer-flash.png` and `penny-plan-confirm.png`. The opt-in dark theme
(`--theme dark`) is shown in `penny-plan-confirm-dark.png`. The workspace defaults to FlatLightLaf (`Theme.install`).

- It sits at the top of the `Banners` slot, above the dialog-pending banner and the proposal sheet. It uses the dialog
  banner's style: a 3 px bar on the left, compact 12 px type, FlatLaf colours and `Theme` helpers. The bar is the LaF accent
  while Penny is active, the warning colour while a plan waits and the border colour when idle.
- The left side shows an animated 16 px mark, **Penny** and a state word:

  | State | Mark | When |
  |---|---|---|
  | Offline / Reconnecting… | hollow dot | gateway socket not connected |
  | Ready | quiet dot | connected, voice off |
  | Starting… | spinner | `start_voice` sent, session or microphone not live yet |
  | Listening | four bars driven by the microphone level | `voice_state listening` and the microphone is capturing |
  | Thinking | slow pulse | `task_state working` |
  | Running | slow pulse | a plan reported by the bridge is running |
  | Speaking | ripple | `voice_speaking` until the last reply chunk has played |

  The animation timer runs only while an animated state is on screen.
- The conversation line shows the live partial transcript in italic, newest words kept. Then it shows `“what you said”  ·
  acknowledgement` and finally `“what you said”  →  answer`. Errors appear in the error colour. When space is short, the
  request is shortened first so the answer stays readable, and the full text is in the tooltip. Speech the gateway marks
  as not addressed to Penny is not shown.
- **Stop task** or **Stop speaking** appears while a task runs or Penny talks. It sends `stop_task` and drops the rest of the
  reply locally, the same as the Swift companion's Stop. Since 18 Oct the gateway turns `stop_task` into the profile's
  instant stop route as well (`fidelis_cancel_plan {}`), so the plan running in the bridge is stopped too: the engine
  cancels the step it is on and no later step starts (before, Stop only abandoned the conversation while the plan ran
  on; see BUG-01 in the final handoff).
- The strip is hidden while Penny's gateway has never been reachable. After a disconnect it shows *Reconnecting…* for 8 s,
  then hides until the socket returns. A plan keeps it visible.

### The plan card (`PennyUi.showPlan/planProgress/planFinished/clearPlan`)

- One row holds **Plan · <document>** (from `context.document`, `title` or `image`), the step trail and the status on the
  right. The trail reads ✓ done › ● running › ○ pending, with ✗ for the failed step, and wraps when narrow. The status shows
  *Step k of n* while running, *Done · n steps · undo in History*, *Stopped at step k of n* or *Needs your confirmation*.
- Below it are the macro preview (`macroPreview`, one monospace line, the full macro in the tooltip) and the utterance (only
  when it differs from the one in the strip).
- While the plan waits (`showPlan(plan, false)`), each reason appears on its own line beside **Cancel** and **Confirm and
  run**. Confirm is never the default button.
  - **Confirm** first sends `POST http://127.0.0.1:49558/execute` with `{operation:"context", arguments:{}, spoken:false}`.
    It then sends `{operation:"confirm", arguments:{planId}, expectedContext:<contextKey>, approved:true, spoken:false}`, so
    the plan runs only against the state the user saw. Both requests carry `Authorization: Bearer <.local/state/bridge-token>`
    and no Origin header. Port: `-Dfidelis.penny.port`.
  - **Cancel** sends `{operation:"cancel_plan", arguments:{planId}, spoken:false}`, without approval.
  - If the bridge refuses (4xx or 409), the card shows *Could not confirm: <error>* and keeps waiting. Nothing ran.
  - If `confirm` returns 200 and no progress hook reported the run, the card marks the plan done. While hooks report
    progress, `planFinished` decides the outcome.
- A finished plan collapses 6 s after *Done*. A stopped plan stays until it is dismissed (×) or the next plan arrives.

### The canvas highlight and view hooks (`WorkspacePennyUi`)

- `flash(document, imageRects, ellipses, label)` draws the outlines **inside the hosted IJ1 canvas**. The canvas is a
  heavyweight AWT component, so a glass pane or `JLayer` would be painted under it. Instead, on every animation frame
  `CanvasFlash` repaints the canvas the way IJ1 does (`ImageCanvas.paint`) and draws the outlines on the same graphics.
  The flash cannot appear over other windows, and it never touches the `ImagePlus` overlay or ROI.
  - Animation: outlines pop in (scale 1.35 → 1, staggered 25 ms each for up to 30 targets), hold, start fading at 3.0 s and
    are gone 0.8 s later. Then the canvas is repainted clean.
  - Colour: the LaF accent (read every frame, so a theme switch follows), over a light halo, so the outline reads on bright
    and dark image regions alike. The label chip is the accent with white text.
  - Up to 12 targets get a soft fill and a glow. More targets get thin outlines only, so the image stays readable
    (`penny-answer-flash.png`: 71 ROIs).
  - Geometry follows the canvas's source rectangle and magnification on every frame.
  - A null document means the focused view, and nothing is drawn for a document whose tab is not showing.
  - `ScreenshotRun` composites the flash into the PNG, and `penny-flash-onscreen.png` is the on-screen (Robot) capture.
- `zoom(document, how)`: `in`/`out` take one step of the IJ1 ladder (`DocumentView.zoomStep`), `fit` and `selection` call
  `fit`/`zoomToSelection`, and `actual`, `100`, `100%` or `1:1` call `actualPixels`.
- `canvasOnScreen` returns the canvas's global screen rectangle, in Java (macOS point) coordinates, clipped to the visible
  viewport of its document view. `toScreen` returns an image-pixel rectangle as covered screen points, clipped to that
  rectangle, or null when it is off screen. `magnification` returns the view's magnification or NaN.
- Queries from other threads wait for the EDT for at most 500 ms. Actions are posted to the EDT and never block.
- `Workspace.launch` registers the implementation, and the registration ends when the workspace window closes.

### Mic and typed requests (the least surprising split)

- **Mic.** While Penny's gateway is reachable, the Mic toggles Penny's **continuous conversation**. The button reads *Stop*,
  and the red recording indicator shows *Penny live m:ss* in the command bar and the status bar. Press again to stop.
  Otherwise the Mic keeps today's local Whisper push-to-talk (*Recording N s / 30 s*), unchanged. A local recording or
  transcription already in progress keeps the Mic until it finishes. Only one client holds the microphone: the gateway
  refuses a second voice owner, and the strip shows its *already active in another companion* message.
- **Typed requests.** While Penny is reachable, the command bar shows two rows below the command matches:
  - **Ask Penny: “…”** (*runs at once · undo in History*). This is the default row for instruction-like text, and Enter
    sends `{type:"text", text}` to Penny.
  - **Interpret locally: “…”** (*preview before anything runs*). This is Fidelis's own `DefaultNlService` with its proposal
    sheet, unchanged, and is reached with the arrow keys or the mouse.

  Command matches stay the default for command-like text. Without Penny there is only the original *Interpret as
  instruction* row. If a send fails, the request goes to the local interpreter and the status bar says so.

  Why: Penny's plans already follow Fidelis's confirmation policy through the bridge. Ordinary steps run at once and are
  undoable; explicit cases wait in the plan card. So sending to Penny is the natural default when she is there, and the
  local preview stays one keystroke away.

### The voice client (`PennyVoiceClient`)

- **Connection.** The client opens `ws://127.0.0.1:49555/events` (`-Dfidelis.penny.gateway`) with
  `Authorization: Bearer <.local/state/companion-token>` and no Origin header. The token is found from `-Dluma.root`,
  `LUMA_ROOT`, or the first folder at or above `fidelis.repo.dir`, `user.dir` or `fiji.dir` that holds `.local/state`; a git
  worktree finds its enclosing checkout this way. The token is re-read on every attempt.
- **Hello.** The client sends `{type:"hello", clientId:"fidelis-<uuid>", client:"fidelis-workspace", desktop:false}`. The
  client id is stable for the JVM, so the gateway reattaches the conversation after a reconnect.
- **Reconnect.** The client reconnects with backoff (0.5 s, doubling to 15 s) and re-arms voice that was on when the socket
  dropped. It also restarts voice when the gateway itself ends a session (time limit, stream closed, gateway closing,
  disconnected). A refused token is reported as such.
- **Voice.** The client sends `start_voice{requestId:uuid}` and then waits for `voice_state connecting{sessionId}`. Only at
  `ready_for_audio` does it open the microphone.
  - Microphone: Java Sound, preferring 24 kHz, then 48, 16 or 44.1 kHz; mono, or the first channel of stereo.
  - Processing: resampled to 24 kHz (running-average low-pass plus linear interpolation), then an automatic gain as in
    `AudioIO.swift`: toward an RMS of 0.07, at most 40×, adapting only above the 0.0015 noise floor. The Mac microphone is
    quiet.
  - Frames: 100 ms of PCM16 (4800 bytes, 6400 base64 characters), sent as `audio{sessionId, audio}` and never faster than
    the gateway's 48 bytes/ms bound. Frames are dropped rather than queued past a 1 s backlog.
  - `stop_voice{requestId}` closes the microphone at once.
  - Three seconds of exact digital silence is reported. That is what macOS delivers without microphone permission for the
    app that started the JVM.
- **Replies.** `audio{audio, sampleRate:24000, epoch, final}` plays through a 24 kHz Java Sound line. `PlaybackGate` applies
  the Swift companion's epoch rules: `voice_speaking` with an epoch at least the current one starts a reply (`queued` keeps
  the acknowledgement playing), and only the current epoch plays. `interrupted{epoch}` flushes at once, and a local Stop
  suppresses the rest of the reply.
- **Barge-in.** Java Sound has no echo cancellation. While Penny plays, microphone frames are held back 300 ms and then sent
  as silence, so Penny does not transcribe herself. A frame counts as the user when it is louder than an absolute floor
  (0.02 RMS) and twice the echo expected from what is playing now. The expected echo is the loudest reply audio of the last
  400 ms times a learned coupling. After 300 ms of such frames, playback stops locally and the held frames go out as real
  audio, so the transcriber hears the start of the sentence. The gateway's own interruption follows from the transcript.
- **Typed text and events.** `text{text, spoken?}` sends typed requests (`spoken` only for a reviewed local Whisper
  transcript), and `stop_task` stops the task. Events consumed: `connected`, `voice_state`, `voice_transcript`,
  `voice_speaking`, `audio`, `interrupted`, `voice_error`, `error`, `text_ack`, `text_progress`, `text_result`,
  `text_error`, `task_state` and `tool`. `desktop_request` is answered with a `desktop_response` error, because the
  workspace does not operate the desktop.
- **Threading.** All state lives on one loop thread, and listeners are called there. The panel and the command bar hop to
  the EDT. No socket, audio or HTTP work runs on the EDT.

### Switches

- `--no-penny` or `-Dfidelis.penny.voice=false` turns the client off.
- `--screenshot` runs never connect, so evidence runs cannot disturb a live gateway or start a paid voice session.
- `--penny-demo listening|thinking|speaking|plan|running|answer` (with `--screenshot`, after a scenario) puts the strip and
  Mic in one state without a gateway. Numbers in the demo answer and the outlines come from the engine's Results and ROI
  list.

  ```
  python3 -I fidelis-app/tools/run.py --isolated-home -- --screenshot docs/images/fidelis/penny-answer-flash.png --scenario j12 --theme dark --penny-demo answer
  ```

### Verification (17 Oct 2026, macOS, pinned Fiji)

- **Protocol-tested.** No live gateway was used, and no AWS session or audio hardware was touched.
  - `sc.fidelis.penny.voice.PennyVoiceClientTest` (8 tests) runs against an in-test RFC 6455 gateway. It checks the
    handshake (token, no Origin), hello, the session flow, frame size and rate bounds, transcript, ack and result,
    epochs and interruption, reconnect with the same client id and voice re-arm, typed text, `stop_task`, `desktop_request`,
    a refused start and a refused token.
  - `VoiceAudioLogicTest` (6) covers resampling from 48, 44.1, 24 and 16 kHz, gain, `PlaybackGate` and barge-in.
  - `sc.fidelis.penny.ui.PennyPanelTest` (5) covers the strip states and the plan card, with Confirm and Cancel against an
    in-test HTTP bridge: request bodies, `expectedContext`, approval, auth and no Origin.
  - `PennyUiGeometryTest` (1) covers a real hosted canvas: geometry at 100 % and 200 %, viewport clipping at 3200 %, zoom
    hooks, and a flash that leaves the overlay and ROI untouched.
  - `sc.fidelis.ui.CommandBarLiveVoiceTest` (2) covers Mic routing and fallback, the Ask Penny row and Enter.
  - All of these pass. The existing workspace UI classes (`ConfirmationUiTest`, `WorkspaceDisposeTest`,
    `WorkspaceLayoutTest`, `WorkspaceShortcutTest`, `WorkspaceU2Test`, `WorkspaceViewportTest`, `WorkspaceMacroEditorTest`)
    pass with the strip mounted.
- **Real-app-tested.** The screenshots above come from the real J-12 run in the light theme, plus one dark variant. A Robot
  capture of the live screen shows the flash painted inside the on-screen canvas (`penny-flash-onscreen.png`; that capture
  was taken in the dark theme before the switch to light). Java Sound on this Mac reports 24 kHz mono capture and playback
  as supported; this was checked with `AudioSystem.isLineSupported`, without opening a device.
- **Not yet verified.**
  - A real microphone and speaker session against the live gateway: deliberately not run, because it costs AWS credits and
    the live demo owns the gateway.
  - The barge-in thresholds on real speakers and headsets.
  - Windows audio.
  - The bridge driving the plan hooks end to end. The bridge is built in parallel against `PennyUi`.

## Gateway profile

`LUMA_APP=fidelis` selects the profile in `packages/gateway/src/apps/fidelis.ts`. Without the variable, a gateway
started with `LUMA_ROOT` reads `.local/state/app`, which `./script/run-fidelis.sh` writes as `fidelis`. Everything else
in the gateway (runtime, decisions and audit, voice, text, MCP, knowledge, readiness) is shared with QuPath and Fiji.
It is coded to the bridge's revised contract of 17 October (undo `steps`, `cancel_plan {planId}`, segment `replace`,
`context.adapter`, `displayRange` and `position`) plus the integrator's `dialog` / `dialog_action` operations.

| Item | Value |
|---|---|
| Profile files | `apps/fidelis.ts` (routes, sentences, confirm binding), `apps/fidelis-tools.ts` (schemas), `apps/fidelis-instructions.ts` (model instructions) |
| Bridge | `http://127.0.0.1:49558`, token `.local/state/bridge-token` (shared with QuPath and Fiji), bearer on every request including `/health` |
| Tools | 33: `fidelis_<operation>` for each of the 30 operations (the 28 of the contract table plus `dialog` and `dialog_action`), and local `fidelis_knowledge`, `fidelis_find_images`, `fidelis_action_status` ([reference](TOOL-REFERENCE-FIDELIS.md), [registry](tool-schemas-fidelis.json)) |
| Risks | contract kind `read`/`view`/`data`/`code` unchanged; `session` (`open_path`) takes the `data` decision path; `dialog` is read, `dialog_action` data |
| Guide and knowledge | [skills/fidelis/SKILL.md](../skills/fidelis/SKILL.md) (also the MCP guide resource) and `.local/upstream/imagej-docs` |
| Launcher | `./script/run-fidelis.sh` (the `launcherHint` in the gateway's `/health`) |
| Bundle id | `com.azul.zulu.java`, the identifier embedded in the Zulu `java` launcher of Fiji's bundled JDK that runs Fidelis (`LUMA_FIDELIS_BUNDLE_ID` overrides). The companion uses it to find the windows holding the canvas; it is shared with any other Zulu `java` GUI process and not yet confirmed on a live run. |
| Desktop tools | none: Fidelis's dialogs are reached through `dialog` / `dialog_action` |

### What the bridge receives

Every call is `POST /execute` with `Authorization: Bearer <token>` and exactly the contract body:

```json
{"operation": "segment_cells", "arguments": {}, "expectedContext": "k1", "approved": true,
 "spoken": true, "taskId": "4c0b…", "utterance": "Find the nuclei."}
```

- `data`, `code` and `session` operations carry `approved: true` and `expectedContext`, the `contextKey` observed when
  the action was proposed. View operations carry `expectedContext` inside a model round and on every instant route
  (below); reads carry neither. `expectedControl` is never sent to Fidelis.
- `spoken`, `taskId` and `utterance` are present for every call made by a conversation task, read calls included, and
  absent for direct `/tools/call` and MCP calls. `spoken` is true for voice and false for typed requests. `taskId` is the
  gateway's task UUID, the same value as `taskId` on the `/events` `tool`, `text_ack` and `text_result` events, so a
  History step's `transcriptRef` can be matched to the conversation. `utterance` is the latest user utterance of the
  task, trimmed and at most 4000 characters.
- Under `LUMA_APPROVALS=review` the three fields are captured when the action is proposed and sent when the researcher
  decides it, and the resulting `tool` event keeps its `taskId`. They are not written to the audit journal.

The envelope comes from two async-local stores in `packages/gateway/src/tool-request.ts` (`toolTask`, as before, and
`toolUtterance`), set by the text/voice conversation and the model backend. A profile opts in with
`conversationEnvelope: true`; QuPath and Fiji bodies are unchanged.

### Confirmation

A result `{status: "awaiting_confirmation", planId, reasons, steps}` is never reported as done:

- The spoken answer is local and immediate: "Ready to run N steps; say yes to go ahead." (N = `steps.length`).
- The request stops there, spoken or typed. Even "invert it with a macro and then measure" does not continue to the
  measurement: later calls in the same model response are cancelled before dispatch and the model gets no further
  round for that request (the profile's `pausesRequest` hook).
- The gateway's decision record keeps `status: "completed"` (the request itself completed) and adds
  `outcome: "awaiting_confirmation"` (or `"dialog"`, `"cancelled"`) to the audit journal, `fidelis_action_status` and
  the tool envelope, so no reader reports the plan as run. Under `LUMA_APPROVALS=review` the "Running it now." after approval is skipped when the plan
  waits. Acknowledgments never claim a waiting plan ran (`fidelis_run_macro` says "Preparing the macro.").
- A waiting plan is an answer, not a failure, on the instant path too (a view plan under `fidelis.penny.confirm=always`
  answers without a decision envelope).
- With `context.pending` set, a whole-utterance "yes", "yes please", "go ahead", "do it", "run it" or "confirm" runs
  `fidelis_confirm {planId}` at once, and "no", "cancel", "stop that", "don't run it", "never mind" runs
  `fidelis_cancel_plan {planId}`. Without a pending plan these words go to the model. A bare "okay" never confirms, and
  neither does a question: a bare "Hey Penny" reaches the conversation as the prompt "Yes?". The speech gate passes the
  one-word replies "yes", "no", "confirm" and "proceed" ("confirm" and "proceed" were added); chatter-prone words such
  as "sure" or "yeah" need a second word ("yeah, do it").
- Only the researcher confirms: `fidelis_confirm` is refused ("Only the researcher can confirm this plan: say "yes" or
  press Confirm in Fidelis.") unless the latest words of the conversation task are, as a whole, an assent in the same
  grammar as the instant route ("yes", "yes please", "okay, go ahead, run it", "confirm"); "yes, if…" or "confirm
  whether…" are not. Neither the model on its own nor a direct `/tools/call` or MCP client can confirm. A typed "yes"
  reaches the model, which may then confirm; the Fidelis card's own Confirm never passes through Penny.
- Spoken "stop", "cancel" or "cancel that" is consumed by the voice transport as barge-in; for Fidelis
  (`controlRoutes`) it is then offered to the instant routes only, so it cancels a waiting plan, stops a running one
  (`fidelis_cancel_plan {}`: Cancel on its Fiji dialog, else `Engine.cancel` of the current step and no further step;
  the plan's result comes back `status: "cancelled", stopped: true`) and otherwise does nothing more (it never reaches
  the model).
- The assent belongs to the plan that was pending when those words arrived (the request's first observation): if
  another plan replaced it since, the confirm is refused ("The plan changed after you said yes; check the new plan and
  say yes again."). The gateway fills `planId` from that plan and refuses one that is no longer pending, or "Nothing is
  waiting for confirmation." The confirm result is spoken from what the plan returned (a count, measurement, threshold
  or step); a single confirmed measurement names no place, since the gateway cannot tell `roi` from `all`. When no
  local sentence fits (a measured mean without `document.binary: false`), the request continues with the model, which
  gets the result, instead of ending as "Done.".

### Original Fiji dialogs

A legacy command whose original dialog opens answers `{status: "dialog", dialogId, dialog: {title, kind, fields,
buttons}}`. That is not an outcome: no local sentence is spoken, the action is recorded with `outcome: "dialog"`, and
the request goes back to the model, which fills the fields from the researcher's words (keeping the shown values
otherwise) and presses a button with `fidelis_dialog_action {dialogId, fields, press}` (`dialogId` required, bound to
that dialog; `press` defaults to "OK"; `fidelis_dialog` reads a waiting dialog). The request finishes from the
command's result ("Ran Gaussian Blur."), or, when Cancel or No ends it (`status: "cancelled"`, HTTP 200), with
"Cancelled at the Variance dialog; nothing more ran." and `outcome: "cancelled"`, never as done. File choosers are
refused by the bridge (`FILE_CHOOSER`); the instructions send the model to `fidelis_open_path`. The same holds when a confirmed plan or
another instant route opens a dialog: the profile's `needsModel` hook sends the request on to the model with what the
step returned, instead of finishing as "Done.". The instructions say any Fiji menu command
or plugin is available through `fidelis_commands` (by name or menu path) and `fidelis_command`, never to leave a dialog
open, and `fidelis_run_macro` (always confirmed) for anything else.

### Instant routes (no model round)

| Said (whole utterance) | Tool |
|---|---|
| "yes / go ahead / confirm" with a pending plan | `fidelis_confirm {planId}` |
| "no / cancel / stop that" with a pending plan | `fidelis_cancel_plan {planId}` |
| "open the X image" | the focused or open document if it matches (`fidelis_context`, `fidelis_activate_image`), else `fidelis_find_images` then `fidelis_open_path {path}` |
| "what am I looking at" | answered from `context.document` |
| "find / segment / count the nuclei" | `fidelis_segment_cells {}` (a repeat replaces the previous segmentation) |
| "stricter / looser" | `fidelis_segment_cells {method: "Li" / "Huang"}` |
| "zoom in / out", "fit to window", "zoom to the selection" | `fidelis_zoom` |
| "how many (nuclei) are there" | `fidelis_rois {limit: 1}` (or `fidelis_object_stats` when the context has no ROI count); an empty list is answered "0 objects in the ROI list." |
| "show me the nuclei" (ROIs present) | `fidelis_highlight {rois: "all"}` |
| "brighter / darker" | `fidelis_display {min, max}` from `document.displayRange` |
| "auto contrast" | `fidelis_display {auto: true}` |
| "next / previous slice" | `fidelis_stack {slice}` from `document.position` |
| "undo / go back", "undo the last N steps", "redo" | `fidelis_undo {}`, `fidelis_undo {steps: N}` (≤ 20), `fidelis_redo` |

Data routes run instantly only under the default `LUMA_APPROVALS=auto`; under `review` they go to the model. Every
Fidelis instant route is bound to the context it was chosen from (`bindInstantRoutes`): if the `contextKey` changed
before dispatch (another plan became pending, the focus moved), the route is refused with "Fidelis changed before that
ran; nothing was done. Say it again.", and view routes carry `expectedContext`. There is no instant "mean intensity of
the nuclei": after `segment_cells` the focused document is its mask (below).

### Readiness

`adapterReadiness` compares the operations the running bridge declares with the 30 the tools need: from
`context.adapter.operations`, or the bridge's `GET /health` when the context has none. The status is `operations_match`
when all are declared (no build-identity comparison: a complete but stale build passes), `restart_required` with
`missingOperations` otherwise (a bridge without `dialog`/`dialog_action` reports those two), `build_unverified` for a
protocol other than 1 or a malformed list, and `unavailable` when neither can be read. `npm run doctor` with
`LUMA_APP=fidelis` does the same and checks `fidelis-app/build/fidelis-app.jar` and `.local/fiji/Fiji.app/jars`.

### Launcher

`./script/run-fidelis.sh [Fidelis args]` refuses while any Fidelis JVM runs (`java @…/fidelis-app/build/run.args`,
paths with spaces included: this checkout's is reported and left alone, another worktree's would hold the same port) or
while port 49558 is taken; creates the bridge token if missing; builds with
`python3 -I fidelis-app/tools/build.py --no-tests` (`FIDELIS_SKIP_BUILD=1` skips it); writes
`.local/state/app = fidelis`; exports `LUMA_ROOT`; and starts
`python3 -I fidelis-app/tools/run.py --isolated-home --prop luma.root=<checkout> --prop fidelis.penny=true -- …` in the
background, logging build and run output to `.local/fidelis.log`. `run.py --prop KEY=VALUE` (repeatable) is the new
JVM system-property pass-through; it behaves the same on Windows and macOS.

### Companion sockets and presentation

The Swift companion's `CompanionApplication` gains a `fidelis` case: the image name comes from `context.document.title`,
the selection label from `roi`, the per-document ROI count and a waiting plan, and the quick actions are Fit image
(`fidelis_zoom {fit}`), Highlights (`fidelis_highlight {rois: "all"}`) and Undo (`fidelis_undo`).

The gateway's `/events` socket now attaches a client for desktop (Accessibility) requests only when its `hello` does
not say `desktop: false`, so the Fidelis workspace (`{type: "hello", clientId: "fidelis-…", client: "fidelis-workspace",
desktop: false}`) connecting after the Swift companion never takes desktop control from it; a socket without a hello is
not attached either. One client holds voice at a time; a second gets "Voice is already active in the Luma companion;
stop it there first." (or "in the Fidelis workspace").

### What the bridge could still add

1. `document.binary` (from `ImageProcessor.isBinary()`) in `context`. After `segment_cells` the focused document is its
   mask, so a `measure` of the ROIs reads mask values, not intensities. The gateway speaks a measured mean as an
   intensity locally only when `document.binary` is `false`; without the field (or with a uniform 0/255 ROI mean) the
   model phrases the answer and is told never to report a mask's Mean as an intensity.
2. Measuring object intensities on the original pixels after segmenting (for example Set Measurements redirected to a
   kept original), so "mean intensity of the nuclei" can become an instant route again.

### Verification status

- Implemented and protocol-tested against a loopback fake bridge (`packages/gateway/test/fidelis.test.ts`): schemas
  against the contract table, the request body and envelope (auto and review policies), readiness from
  `context.adapter` and `/health`, instant routes and their context binding, local sentences, the
  awaiting-confirmation stop, "yes"/"no" handling, the researcher-only and plan-bound confirm, and a command whose
  dialog is filled and pressed. Desktop-socket attachment and the voice-holder message are tested in
  `packages/gateway/test/server.test.ts`; the companion presentation in
  `apps/companion/Tests/LumaPresentationTests` (`swift test --filter LumaPresentationTests`).
- Not yet tested with the real Fidelis bridge; no voice run against Fidelis yet.
