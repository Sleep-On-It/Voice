# Fidelis bridge contract (Penny × Fidelis)

Penny's gateway drives the **Fidelis workstation** (`fidelis-app/`) the same way it drives stand-alone Fiji
(`docs/FIJI-PROTOCOL.md`), through a local bridge — but every change goes through the Fidelis engine
(`Engine.submit`), so Fidelis's history, undo, provenance and macro export stay authoritative. This page is the contract
the three integration parts are built against: the Java bridge (`sc.fidelis.penny`), the gateway profile (`LUMA_APP=fidelis`)
and the workspace voice surface (`sc.fidelis.penny.ui`).

## Process and transport

- The bridge (`sc.fidelis.penny.FidelisBridge`) starts inside the Fidelis JVM from `Bootstrap`, right after the product
  engine is registered, when a Penny bridge token is found, or with `-Dfidelis.penny=true` (which still needs a token and
  logs why it stays off without one). `-Dfidelis.penny=false` keeps it off. Token: `LUMA_BRIDGE_TOKEN` (32+ characters),
  else `<root>/.local/state/bridge-token`; root from `-Dluma.root`, `LUMA_ROOT`, else the first directory holding
  `.local/state/bridge-token` walking up from the Fidelis jar, `-Dfidelis.repo.dir` and the working directory (a git
  worktree under `<root>/.local/worktrees/` finds the main checkout). `Bootstrap.exit` stops it. A plain `--smoke` never
  starts it (there is no engine in smoke); `run.py --smoke --prop fidelis.penny=true` creates the engine, starts the bridge and runs a
  self-check over HTTP (`/health` 200, `context` 200, an `Origin` request 403); the smoke exits 5 when that check fails
  (verified with the port held by another process) and 0 when it passes.
- HTTP on `127.0.0.1:49558` (`-Dfidelis.penny.port`), 4 handler threads. **Every** request, `/health` included, needs
  `Authorization: Bearer <token>` (as the Fiji bridge); any request with an `Origin` header is refused with 403 first.
  `GET /health` → `{application:"Fidelis", version, engine, confirmPolicy, adapter:{adapterVersion:"fidelis-bridge-1",
  protocolVersion:1, operations:[…30], sourceSha256, sourceShaCovers}}` (`sourceSha256` is the SHA-256 of the loaded
  `sc.fidelis.penny` classes). `POST /execute` body `{operation, arguments, expectedContext?, approved?, spoken?, taskId?,
  utterance?}` → `200 {result}`; 400 `{error}` for a malformed body, unknown operation or bad argument; 401/403/404/413;
  409 `{error, …}` for refusals and engine failures (below).
- `spoken:true` → requests use `Origin.NL_SPOKEN`, else `Origin.NL_TYPED`. Every engine request carries
  `params.transcriptRef = taskId` (reserved key; the engine records it in `HistoryRecord.transcriptRef`); without a `taskId`
  the plan id (`penny-plan-…`) is used, so Penny's steps are always traceable (legacy-route commands included: the
  engine's legacy route now ignores reserved keys instead of refusing them). Steps a confirmed macro runs are recorded by
  the engine as `MACRO` children of the `NL_*` CODE step and carry the same `transcriptRef`.
- Data, session and code operations (`open_path command segment_cells measure threshold analyze_particles filter convert
  duplicate undo redo run_macro confirm`) need `approved:true` and `expectedContext` equal to the current `contextKey`
  (same rule as Fiji); a view operation that sends an `expectedContext` is checked too. Reads never are.
- `contextKey` = SHA-256 over the focused document's id, title and plane position (c,z,t), its active ROI (type, bounds,
  vertex count), its ROI-list size, the Results row count, the **history size** and the list of open documents (id/title).
  Every completed change therefore changes it; results carry the new `contextKey`.

## Plans and confirmation (the Fidelis contract, kept)

Fidelis's rule is that language never runs anything without the user's confirmation; Penny's rule is "act immediately".
Every operation that reaches the engine is a **plan**: an ordered list of complete `ActionRequest`s built up front. The
bridge resolves the two rules with Fidelis's own `ConfirmationPolicy.assess` on the plan as a READY `Proposal` (with per
step slot provenance), using its value-aware constructor `ConfirmationPolicy(engine::schema, engine::globals)`:

- **Not explicit** (AC0–AC3 typed commands, DIRECT view/selection/threshold/undo steps, open): the plan is shown in the
  workspace card (`PennyUi.showPlan(plan, true)`, then `planProgress` per step and `planFinished`) and **runs at once**
  (`-Dfidelis.penny.confirm=auto`, the default, Ayush's decision of 17 Oct 2026; the policy's `required` flag is not used).
- **Explicit** (CODE/macros, AC4+ such as a legacy command without a catalogued schema, AC5 such as Set Measurements, AC6,
  a `black` value that differs from the current BlackBackground global, a consequential slot filled only from a bare
  catalogue default): the card waits (`showPlan(plan, false)`) and the bridge answers
  `{status:"awaiting_confirmation", planId, operation, reasons[], steps[labels], macroPreview[], document, contextKey, note}`
  without running anything. `confirm` (gateway "yes"/"go ahead" route) or the workspace's *Confirm and run* runs it.
- `-Dfidelis.penny.confirm=always` makes every plan wait (the strict Fidelis behaviour); `context.confirmPolicy` reports it.
- Slot provenance: keys the gateway gave are USER; values an operation's documented recipe sets (segment_cells' σ 1, Otsu,
  the Analyze Particles reporting convention, filter defaults below) are DERIVED; catalogue defaults are DEFAULT (an
  explicit `true` flag default included), except an off-by-default flag, which is DERIVED when consequential (as
  `DefaultNlService` does); a remembered or dialog-dependent default is left out for the engine to judge. `black` is never filled from a
  default. So a `command` call that leaves a consequential number to its catalogue default is explicit.
- `black` (value-aware rule, added for the bridge): Convert to Mask on a single image takes its polarity from the global
  BlackBackground preference. segment_cells passes `black` = the **current** global, so the polarity is stated explicitly in
  history and in the exported macro (`setOption("BlackBackground", …)`) without changing the preference; the policy's
  original constructor (used by the NL service and the UI) still treats every `black` as a change.
- One plan waits at a time. It is discarded (card cleared with `clearPlan`) when another plan runs or replaces it, on
  `cancel_plan`, after 15 minutes, or when `confirm` finds the `contextKey` differs from the one it was planned against;
  a late `confirm` then answers 409 naming the plan and why. `confirm` needs `approved` + `expectedContext` like any change.
- Admission: the context check, plan construction, the confirmation decision and the reservation of the plan thread are
  serialised (one lock in `PennyOps`), and the `contextKey` is checked again after the plan is built, so a focus or
  selection change in the workspace while a plan is prepared refuses it (409) instead of running it on another image.
  While a plan runs, other data/code operations and the view plans (`select_roi`, `display`, `stack`) answer 409
  `{running:true, planId}`; reads, `zoom`, `highlight`, `activate_image`, `cancel_plan`, `dialog` and `dialog_action`
  still work.
- Before every step the plan thread waits (up to 30 s) until no engine operation other than Penny's is queued or running
  (the workspace's, tracked from the engine's events), checks the `contextKey` against the approved one before the first
  step, and before each later step refuses any history record since its previous step that is not its own (a workspace
  action, a native canvas edit); the refusal stops the plan before that step (409, "Step k was not started"). Every step
  also carries the reserved request key `expectedHistorySize` (the history size when it was submitted): the engine checks
  it when its worker dequeues the request and refuses with PRECONDITION_FAILED / subcode `STATE_CHANGED` if anything else
  entered the history in between (a workspace action queued just ahead of it), so the approval holds at execution, not
  only at admission (verified: a workspace CLEAR_ROI queued before step 3 of segment_cells ran, step 3 was refused,
  nothing after it ran). This is an additive, opt-in engine change (`DefaultEngine.runOp`, `Options.RESERVED`);
  requests without the key are unaffected. The selection is bound too: every step carries the reserved key `expectedRoi`, the `sc.fidelis.engine.RoiSignature`
  of its target document's active selection (`none`, or `TYPE:x,y,w,h:HASH`) — the selection at approval for the first
  step, the selection the previous step left for the rest. The engine reads the live selection on the EDT when the worker
  starts the step and refuses it (`STATE_CHANGED`) if it differs, so a selection drawn on the canvas in the last moment
  before a step (recorded ~250 ms late by the observer, invisible to the history check) can no longer change what the
  step acts on. Requests without the key are unaffected (`EngineExpectedRoiTest`).

## Operations

Results are JSON objects; every result that concerns something visible may include `highlight` (below). Ids: documents are
Fidelis `DocumentId` strings (`"doc-3"`); `imageId` is the IJ1 id. Commands are catalogue ids (`ij1:<menu path>>label`),
hard-coded ones listed in `sc.fidelis.penny.PennyCommands` and checked by `PennyCatalogueTest`. Operations that need an
image use the **focused** document (409 "No image is open/focused" otherwise).

| Operation | Kind | Arguments | Result |
|---|---|---|---|
| `context` | read | | `{contextKey, application:"Fidelis", version, adapter, engine:{state}, document:{id, imageId, title, width, height, channels, slices, frames, bitDepth, type, unit, pixelWidth, pixelHeight, path, changed, position:{channel,slice,frame}, displayRange:[min,max], binary (true for a mask: its intensities are 0/255, not the signal)}\|null, documents:[{id,title,focused}], roi:{type,bounds:{x,y,width,height},name?}\|null, counts:{rois, resultsRows, history}, lastStep:{actionId,label,origin,outcome}\|null, globals:{blackBackground, measurements, precision, …}, viewer:{magnification\|null, canvasScreen:{x,y,width,height}\|null}\|null, pending:{planId, operation, reasons, steps}\|null, running:{planId, operation}\|null, confirmPolicy}` |
| `images` | read | | `{documents:[{id,imageId,title,width,height,focused,changed}]}` |
| `open_path` | session | `path` (absolute, `~/` expanded; must be a file) | SESSION `OPEN` → `{opened, document, highlight}` |
| `activate_image` | view | `id` (doc id or IJ1 id) or `imageId` or `title` | `Engine.focus` (not a history step) → `{document, contextKey}` |
| `commands` | read | `query`, `limit?` (1–50, 10) | the whole live catalogue (~950 ids from Fiji's menus, Plugins included): `Engine.search` ranked first, topped up by a token scan of label and menu path → `{commands:[{commandId,label,menuPath,schemaComplete,consequence,parameters?:[{key,type,default,choices,consequential}] (complete schemas), route? (legacy entries: "original Fiji dialog, if it has one: answered with dialog_action" or "file chooser: not available from Penny (use open_path)")}]}` |
| `command` | data | `commandId` (any catalogue id), `params?` | one COMMAND step. A complete schema runs typed (missing keys from the schema, provenance above; a key without a usable default is left to the engine, which refuses with VALIDATION_FAILED naming it). Any other entry runs through the engine's legacy route with no params: its original Fiji dialog, if any, answers `status:"dialog"` (below). Commands whose first act is an OS file chooser (File>Import…, File>Save As…, File>Export…, Open…, Bio-Formats Importer/Exporter, …) are refused with 409 `code:"FILE_CHOOSER"` (use `open_path`). Legacy entries without a postcondition family are AC4 (no undo snapshot), so they wait for `confirm` → `{step, provenance, resultsRowsAdded, document? (new image), globalsChanged?, windowsOpened?, log?, highlight}` |
| `segment_cells` | data | `channel?` (default on a multichannel image, 18 Oct: the blue-LUT channel, else a channel labelled DAPI/Hoechst/nuc, else the current one; the result reports `channel` and `channelBy`: `argument`, `lut-blue`, `label` or `current`), `method?` (Otsu), `dark?` (default: not `dark` when the image shows an inverting LUT, else `dark`), `minSize?`, `maxSize?` (calibrated units unless `pixel:true`), `exclude?`, `watershed?` (true), `gaussian?` (true), `sigma?` (1), `replace?` (true), `keepMask?` (false: the mask is closed again once the objects are on the original) | on a **duplicate**, so the original keeps its pixels (changed from the first contract, at the gateway's request, to match stand-alone Penny Fiji): [SET_POSITION channel (chosen or given)] → [Select None on the original when it has a selection: ImageJ would restore it onto the duplicate and can crop it] → Duplicate the current plane of the focused document as `<title>-mask` (whole image) → on the mask: [8-bit if RGB/indexed colour] → Gaussian Blur σ → `SET_AUTO_THRESHOLD{method,dark}` → Convert to Mask (polarity = current BlackBackground, stated) → Fill Holes → [Watershed] → [Set Measurements area mean centroid bounding shape ∪ current, **only when Area is not recorded**] → Analyze Particles (size, circularity 0–1, show Nothing, display, clear, add; exclude/include false) → `ROI_LIST_ADD{fromDocument: mask, replace}` copies the objects to the **original's ROI list** (replacing it unless `replace:false`; on a stack each object keeps the plane it was found on) → [the selection restored] → [SESSION `CLOSE {discard:true}` of the mask unless `keepMask`: a housekeeping step the policy does not assess, recorded by the engine, so repeated segmentations never pile up `-mask` tabs] → focus back on the original → `{count, countedFrom, averageSize, totalArea, unit, rois, resultsRows, document, channel?, channelBy?, mask:{id,title,closed}, threshold:{method,dark}, watershed, replacedRois, highlight, steps, undoSteps (0: the original is unchanged), note}`. `count`, `averageSize` and `totalArea` are the Results rows the engine attributed to Analyze Particles on the mask. `measure {scope:"rois"}` on the original then reads the real signal; "stricter/looser" (`method: "Li"/"Huang"`) simply segments the untouched original again (with `keepMask:true` each run leaves its own `-mask` document open) |
| `measure` | data | `measurements?:[…]` (area, mean, min/max, standard, modal, centroid, center, perimeter, bounding, fit, shape, feret, integrated, median, skewness, kurtosis, area_fraction, stack), `scope:roi\|all\|rois` (`manager` = `rois`), `channel?` (on a multichannel image, 18 Oct: the channel given, else the channel the document's last `segment_cells` segmented, else the displayed one; `roi`/`all` switch to it with SET_POSITION and switch back afterwards; for `rois` a given channel is measured for **every** ROI, whatever channel it was found on (objects found on the DAPI channel, intensity wanted on another), while without one each ROI's own channel is used, then the segmented channel, then the displayed one; the result reports `channel` and `channelBy` for the channel **actually measured**: `argument`, `segmented`, `roi position` or `displayed`, or `channels` with `channelBy: "roi position"` when the ROIs' own channels span several) | Set Measurements (current ∪ requested) **only when a requested column is not recorded** (AC5 → explicit), then `roi`: Measure (the selection, or the whole image without one); `all`: [Select None] Measure [restore the selection]; `rois`: one Measure per ROI-list entry (≤ 1000), each targeted at the plane the ROI was recorded on (its c/z/t; 0 = current), then the plane position and the selection restored → `{rows, totalRows, columns, lastRow, summary:{area:{min,max,mean,count}, …}, unit, scope, highlight}`: `rows` is the number of rows **this call** measured (the summary and `lastRow` cover only those rows; `totalRows` is the whole table); summary keys are lower-case without '.' |
| `threshold` | data | `method?` (Otsu), `dark?` (as segment_cells), or `lower` + `upper` | `SET_AUTO_THRESHOLD` or `SET_THRESHOLD` → `{lower, upper, method, dark?, highlight}` (levels read back from the image) |
| `analyze_particles` | data | Analyze Particles schema keys (`size`, `circularity`, `show`, `display`, `exclude`, `clear`, `include`, `summarize`, `add`, …); aliases `minSize`, `maxSize`, `minCircularity`, `addToManager` | defaults show Nothing, display, clear, add → `{count, countedFrom, averageSize, totalArea, unit, rois, resultsRows, document, highlight}`; `count` is the Results rows of that action, or the ROI list when `display` is off and `add` on; with `add:false` the highlight is the image (the ROI list then holds earlier objects); a second run replaces the ROI list (verified) |
| `filter` | data | `name: gaussian\|median\|mean\|unsharp\|subtract_background\|enhance_contrast\|invert\|smooth\|sharpen\|find_edges`, `sigma?` (2), `radius?` (2; unsharp 1), `mask?` (0.6), `rolling?` (50), `light?`, `sliding?`, `saturated?` (0.35), `normalize?`, `equalize?`, `stack?` | the catalogue command (sharpen and find_edges have no catalogued options: they run at once through the engine's legacy route, which keeps no undo snapshot, so `undo` answers 409 "nothing to undo" and `undoSteps` does not count them) → `{step, highlight}` |
| `convert` | data | `to: 8-bit\|16-bit\|32-bit\|RGB` | `ij1:Image>Type>…` → `{step, document}` |
| `duplicate` | data | `title?` | `ij1:Image>Duplicate...` (whole stack for stacks) → `{document, step, highlight}` |
| `select_roi` | view | `type: rectangle\|oval\|polygon\|none`, `x,y,width,height` or `points:[[x,y],…]` (image px), `name?` | `SET_ROI`/`CLEAR_ROI` → `{roi, highlight}` |
| `rois` | read | `limit?` (100), `offset?` | `Engine.rois(focused)` → `{document, total, rois:[{index,name,type,bounds}]}` |
| `results` | read | `limit?` (50, ≤ 500), `offset?` | `Engine.results()` → `{columns, rows:[{row, Label?, <column>: value}], total, offset}` (non-finite values as "NaN") |
| `object_stats` | read | | from the rows of the latest completed Analyze Particles (else all rows) → `{n, unit, source, area:{mean,median,min,max,count}, mean?, circularity?, perimeter?, integratedDensity?}`; `unit` is the source document's (null with `unitNote` when the rows come from several or closed documents) |
| `display` | view | `min?`, `max?`, `auto?` (raw min/max of the requested channel's current plane, read from the pixel array, never through ImageJ statistics on the live processor), `channel?` | `SET_DISPLAY_RANGE` → `{min, max, step}` |
| `stack` | view | `slice?`, `channel?`, `frame?` | `SET_POSITION` → `{position, step}` |
| `zoom` | view | `direction: in\|out`, `fit?`, `selection?` | `PennyUi.zoom(doc, "in"\|"out"\|"fit"\|"selection")` (not a history step) → `{zoom, document, viewer, highlight}` |
| `highlight` | view | `rois: all\|indices\|active`, `indices?`, `label?`, `limit?` (120) | `PennyUi.flash` + payload → `{highlight, total}`; 409 when there is nothing to show |
| `undo` / `redo` | data | `steps?` (1–20) | DIRECT `UNDO`/`REDO`, repeated, stopping at the first refusal → `{step, undoneActionId, undoneActionIds, restored:{pixels, rois:{before,after}?, resultsRows:{before,after}?}, document, highlight}`. Since 18 Oct the engine's undo stack also holds ROI-list and Results changes (ROI_LIST_ADD/DELETE/RENAME, Analyze Particles with `add`/`display`, Measure, Clear Results): undo rebuilds the ROI list, removes the rows that action appended (per action, so other documents' rows stay) and puts back rows it cleared; the UNDO record carries `undoneOperation`, `pixelsRestored`, `roisBefore/After`, `resultsRowsBefore/After`. A ROI copy with `resultsOf` (segment_cells names its Analyze Particles) takes those rows with it, and puts back the rows that Analyze Particles cleared (it reads them from the mask's own snapshot while the mask is still open), so "go back" after a segmentation leaves the original with no objects, no particle rows and the measurements it had before (18 Oct: before, those were lost with the closed mask). Redo of that copy does not run the copy again (its mask is closed): the engine re-establishes what the undo recorded, the objects and their rows, and clears the earlier rows again, as one `ROI_LIST_ADD` record flagged `redone` (before, redo answered 409 "fromDocument … is not open"). `undoSteps` counts these restorable steps too (segment_cells: 1, the copy). Redo re-runs the request; a copy whose mask was closed cannot be redone |
| `history` | read | `limit?` (20) | `Engine.history()` → `{total, steps:[{actionId,label,commandId,origin,provenance,outcome,transcriptRef,time,macroLines,note?}]}` |
| `macro` | read | | `Engine.exportMacro()` → `{macro, steps}` |
| `run_macro` | code | `code` (≤ 32000 characters, ImageJ macro language) | CODE `ijm`: the bridge submits it once without a token, the engine answers AWAITING_CONFIRMATION with its capability token and reason; the plan waits (always explicit) → `awaiting_confirmation`; after `confirm` → `{step, resultsRowsAdded, log, documentsAdded, focused?}`; when the macro added images (a replay re-opens its file) the bridge focuses the last one and reports it as `focused`, so the result is on screen |
| `confirm` | data | `planId?` (default: the waiting plan) | runs the waiting plan → its result with `confirmed:true` |
| `cancel_plan` | view | `planId?` | the waiting plan is discarded → `{cancelled, planId}`; the running plan (`planId` names it, or nothing waits) is stopped: Cancel is pressed on the Fiji dialog it is stopped at (`{cancelled, planId, dialogCancelled}`), or the step it is on is cancelled in the engine (`Engine.cancel`: Escape for a command, abort for a macro) and no further step starts (`{cancelled, planId, stopped:true, engineCancelRequested}`). Either way the plan's own result (the answer to the call that started it) comes back as `{status:"cancelled", stopped:true, step?, state?, completedSteps, note}` (18 Oct): what ran stays in History and is undoable there; documents the plan created (a mask) stay open and are named in `note`. The gateway sends it for the companion's and the workspace's Stop (`stop_task`) as well as for a spoken "stop" / "cancel" |
| `dialog` | read | | the original Fiji dialog Penny's running plan waits on → `{pending:true, status:"dialog", planId, operation, step, dialog, contextKey}`, else `{pending:false, others:[{title, kind, buttons}]}` (dialogs not waiting for Penny; never operated) |
| `dialog_action` | data | `dialogId` (from the dialog result; required), `fields?:{<id\|key\|label>: value}`, `press?` (button label, default "OK"; "" fills without pressing) | fills and presses that exact dialog window, then waits for the plan again → its result: `completed`, another `dialog` (new `dialogId`), `cancelled` (Cancel/No pressed) or a failure. 400 for a missing `dialogId`, an unknown field, a value of the wrong type, or an unknown button (nothing is touched); 409 when no dialog waits for Penny or `dialogId` names an earlier dialog |

Verified numbers (17 Oct 2026, macOS arm64, pinned Fiji 1.54p, FX-003 Blobs 256×254, uncalibrated): `segment_cells` with
defaults finds **67** objects, mean area **326.657 px²**, total 21886 px² (Gaussian σ1, Otsu not-dark because Blobs has an
inverting LUT, Watershed) on the duplicate `blobs-mask`, the original's pixels unchanged; `method:"Li"` afterwards finds
**68** (mean 353.941 px²), the same as Li on a fresh copy; measuring every ROI-list entry reproduces the mean area exactly; the macro Fidelis exports from
that voice session, replayed in plain ImageJ in the same JVM, gives 67 rows and 21886 px²; `minSize:20` gives 65. These are
not comparable with the J-12 baseline's 71 (a different pipeline: Subtract Background 50, σ2, Otsu dark, size ≥ 20).

## Highlight and the workspace (`sc.fidelis.penny.PennyUi`)

The bridge never draws; it calls the `PennyUi` hooks the workspace registers (`PennyUi.register(impl)`). With no UI
(tests) a no-op implementation is used. Highlights are drawn **inside the Fidelis canvas** (a glass layer over the hosted
IJ1 canvas), so they can never appear over other windows. The returned `highlight` payload keeps Penny's screen-geometry
shape (`{label, targets:[{x,y,width,height,shape}], pointer:{screenX,screenY}, viewer:{x,y,width,height}}`) for the
companion cursor, computed from the hosted canvas's screen location.

As implemented (`sc.fidelis.penny.Highlight`): ROI-list or selection bounds (rectangle/oval bounds, else the box around
the vertices, image pixels) go to `PennyUi.flash(doc, rects, ellipses, label)` (ellipses only when every ROI is an oval;
at most `limit`, default 120) and each one through `PennyUi.toScreen(doc, rect)`; `null` drops that target. The payload is
`{label (≤ 80 chars), total, shown, truncated, kind:"image", targets:[{x, y, width, height, shape:"rect"|"ellipse"}],
pointer:{screenX, screenY} (centre of the targets) or {}, viewer: PennyUi.canvasOnScreen(doc) as {x,y,width,height} or
null, coordinateSystem:"screen-top-left", flash, note?}`; targets are at least 8×8 points around their centre. Whole-image
results (open, filter, undo, measure without a selection) use the visible canvas as the single target, without a flash.
With no workspace registered (`PennyUi.NONE`, tests, smoke) `targets` is empty and `viewer` null. `context.viewer` reads
`PennyUi.magnification` and `canvasOnScreen`. All hooks are called from the bridge's threads (HTTP or plan thread), never
from the EDT; an exception in a hook is logged and ignored.

## Original Fiji dialogs (any command through Penny)

Every catalogue entry can be run through `command`. When the engine's operation stops at a native dialog (the engine's
Guard reports `DialogPending` and WAITING_FOR_DIALOG), the bridge answers as soon as the dialog is modal, or the command is
still blocked on it after 500 ms, with HTTP 200:

```json
{"status": "dialog", "dialogId": "dlg-7", "planId": "plan-…", "operation": "command", "step": {"index": 1, "of": 1, "label": "Variance..."},
 "dialog": {"title": "Variance...", "kind": "generic", "text": "Radius\npixels",
            "fields": [{"id": "f0", "key": "radius", "label": "Radius", "type": "number", "value": "2.0"},
                       {"id": "f1", "key": "preview", "label": "Preview", "type": "boolean", "value": false}],
            "buttons": ["Cancel", "OK"]},
 "note": "…", "contextKey": "…"}
```

- Field types: `number`, `string`, `text` (multi-line), `boolean`, `choice` (with `choices`), `radio` (a checkbox group,
  with `choices`). `key` is the macro key ImageJ derives from the label (first word, lower case); `id` is the position.
  `kind` is `generic` (ImageJ GenericDialog), `awt`, `swing` or `file` (a file chooser: only Cancel works).
- `dialog_action {dialogId, fields, press}` is bound to that dialog (a later dialog of the same command gets a new
  `dialogId`, and an answer for the earlier one is refused); it sets the values through the components' own events on the EDT (the plugin reads them as
  if typed) and presses the button; the command continues **inside the same engine operation**, so History records it
  (provenance `LEGACY_DIALOG`, the step's origin `NL_SPOKEN`/`NL_TYPED`). Pressing Cancel (or No) ends the plan with
  HTTP 200 `{status:"cancelled", dialog, state, engineMessage, step, completedSteps, note}` (the engine reports the
  plugin's "Macro canceled" as FAILED; the bridge does not present that as a failure).
- A file chooser (`kind: "file"`: the Swing `JFileChooser` IJ1 shows in Fidelis — the engine sets `Prefs.useJFileChooser`, so no native macOS panel, whose creation blocks the AppKit thread and cannot be cancelled from code — or an AWT `FileDialog` a plugin builds itself) that a command opens **on its way or at its end** (Coloc 2
  finishes by asking where to save a PDF) is never offered to Penny: the plan thread presses its Cancel at once (18 Oct)
  and the step goes on; the result then carries `cancelledFileDialog: true`, `cancelledFileDialogs: [titles]` and a
  note ("Fiji wanted to save a file; Penny does not drive OS file dialogs, use export instead"). A command that ends
  because its chooser was cancelled answers `status: "cancelled"` with the same two fields (never `completed`). Commands
  whose **first** act is a chooser are still refused up front (`FILE_CHOOSER`, above).
- While a dialog waits, the plan keeps the bridge (other changes answer 409 `running`), the user can also answer it in
  Fidelis, and `cancel_plan` presses its Cancel. Non-modal tool windows a command opens (Brightness/Contrast, the
  Recorder, TrackMate's window) are results, not dialogs: the command completes and they stay open.
- Verified on this Mac (`FidelisCatalogueWalkTest`, catalogue of 952 commands): of 36 commands sampled across File, Edit,
  Image, Process, Analyze and Plugins, 10 completed, 19 stopped at their dialog and ended `cancelled` on Cancel (New
  Hyperstack, Enlarge, Appearance, Canvas Size, Rename, Properties, Remove Outliers, Gamma, Variance, Surface Plot,
  Calibrate, Scale Bar, Coloc 2, 3D Objects Counter, Directionality, …), 2 file choosers were refused (Image Sequence,
  Bio-Formats Importer) and 5 were refused by the engine with ImageJ's own reason (Draw and Add Selection need a
  selection, Skeletonize a binary image, Distribution a Results table, Histogram's typed schema needs `x_min`…); none
  hung and none left a dialog open. TrackMate is found by `commands` and completes (it opens its wizard "TrackMate on
  <image>"), but it is not part of the walk: in the shared test JVM it leaves an ImageJ image that later test classes'
  isolation cannot close (InJvmCrossCheckTest and EngineQuitTest then failed); in Fidelis the user drives its wizard. Variance… filled with radius 3 and OK through `dialog_action` changed the pixels and
  was recorded as `LEGACY_DIALOG`/`NL_SPOKEN` with the task's `transcriptRef`.
- `run_macro` (CODE, always explicit) remains the route for anything a dialog cannot express.

### Notes for the other integration parts

- Gateway: the bridge now declares **30** operations: add `fidelis_dialog` (read) and `fidelis_dialog_action` (data:
  `dialogId`, `fields`, `press`) and handle `status:"dialog"` (speak the dialog title and fields, fill from the user's answer, or
  cancel) and `status:"cancelled"`; `command` takes any catalogue id, and `commands` returns `route` for legacy entries.
  Send `spoken`, `taskId` (becomes `transcriptRef`) and `utterance` with every `/execute`; handle
  `status:"awaiting_confirmation"` (speak the `reasons`, route "yes"/"go ahead" to `confirm` with the `planId`, "no" to
  `cancel_plan`); a 409 with `running:true` means the plan is still running in Fidelis. `undo {steps:n}` reverts n pixel
  steps of the focused document (every plan reports `undoSteps` for its own document; segment_cells reports 0 because the original is untouched). The readiness `operations` list is in `/health` and `context.adapter`.
- Workspace: register a `PennyUi` at launch; wire *Confirm and run* / *Cancel* on a waiting card to
  `PennyPlans.confirm(planId)` / `PennyPlans.cancel(planId)`. Single-step view plans (select_roi, display, stack, undo) also
  produce a running card and `planFinished`; showing those briefly or not at all is the workspace's choice.
- Engine: `CodeRunner.scan` reports `exec, eval, call, runMacro` for a plain `run("Invert");`, so the
  macro card's engine reason over-states the capabilities; the bridge passes the engine's wording through unchanged.

## Voice in the workspace

The workspace shows Penny's conversation in a strip under the command bar: live transcript,
what Penny is doing (listening · thinking · running "Step 2 of 6" · speaking), the spoken answer, and the plan card with
Confirm when a plan is explicit. It connects to the gateway's `/events` websocket (`ws://127.0.0.1:49555/events`,
`Authorization: Bearer <.local/state/companion-token>`): client `hello`, `start_voice{requestId}`, `audio{sessionId,
audio:base64 PCM16 mono 24 kHz}`, `stop_voice`, `text{text, spoken}`; server `connected`, `voice_state`, `voice_transcript
{partial,text}`, `text_ack`, `text_result`, `voice_speaking`, `audio{audio, sampleRate:24000, final, epoch}`, `interrupted`,
`tool`, `action`, `voice_error`. The workspace Mic button starts Penny's live voice when the gateway is reachable and falls
back to Fidelis's local Whisper push-to-talk otherwise. Only one voice client should hold the microphone: when Fidelis
owns voice, the Swift companion's bar stays idle.
