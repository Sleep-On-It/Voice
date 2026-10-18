# MCP tool reference (Fidelis)

Generated from `packages/gateway/src/apps/fidelis-tools.ts`; this profile is active when `LUMA_APP=fidelis`. Do not edit individual schemas here; regenerate after changing tool definitions. [Complete machine-readable registry](tool-schemas-fidelis.json) retains every schema constraint and description. MCP discovery reports the running gateway, which may be older than these source definitions. Other applications: [QuPath](TOOL-REFERENCE.md), [Fiji](TOOL-REFERENCE-FIJI.md).

All inputs are JSON objects. Unknown properties are rejected. Read and view tools run immediately. Data and code tools go through the recorded decision path: executed at once under the default `LUMA_APPROVALS=auto` policy, shown as human review cards under `review`. The approval control is absent from the MCP tool list. Every change runs through the Fidelis engine and is recorded in Fidelis History. An explicit plan (code, steps History cannot undo, global settings) returns `{status: "awaiting_confirmation", planId, reasons, steps}` without running; it runs after the researcher presses Confirm or says "yes" (`fidelis_confirm`). See [the bridge contract](FIDELIS-PROTOCOL.md).

| Tool | Risk | Decision path |
|---|---|---|
| [`fidelis_context`](#fidelis-context) | read | No |
| [`fidelis_images`](#fidelis-images) | read | No |
| [`fidelis_open_path`](#fidelis-open-path) | data | Yes |
| [`fidelis_activate_image`](#fidelis-activate-image) | view | No |
| [`fidelis_commands`](#fidelis-commands) | read | No |
| [`fidelis_command`](#fidelis-command) | data | Yes |
| [`fidelis_dialog`](#fidelis-dialog) | read | No |
| [`fidelis_dialog_action`](#fidelis-dialog-action) | data | Yes |
| [`fidelis_segment_cells`](#fidelis-segment-cells) | data | Yes |
| [`fidelis_measure`](#fidelis-measure) | data | Yes |
| [`fidelis_threshold`](#fidelis-threshold) | data | Yes |
| [`fidelis_analyze_particles`](#fidelis-analyze-particles) | data | Yes |
| [`fidelis_filter`](#fidelis-filter) | data | Yes |
| [`fidelis_convert`](#fidelis-convert) | data | Yes |
| [`fidelis_duplicate`](#fidelis-duplicate) | data | Yes |
| [`fidelis_select_roi`](#fidelis-select-roi) | view | No |
| [`fidelis_rois`](#fidelis-rois) | read | No |
| [`fidelis_results`](#fidelis-results) | read | No |
| [`fidelis_object_stats`](#fidelis-object-stats) | read | No |
| [`fidelis_display`](#fidelis-display) | view | No |
| [`fidelis_stack`](#fidelis-stack) | view | No |
| [`fidelis_zoom`](#fidelis-zoom) | view | No |
| [`fidelis_highlight`](#fidelis-highlight) | view | No |
| [`fidelis_undo`](#fidelis-undo) | data | Yes |
| [`fidelis_redo`](#fidelis-redo) | data | Yes |
| [`fidelis_history`](#fidelis-history) | read | No |
| [`fidelis_macro`](#fidelis-macro) | read | No |
| [`fidelis_run_macro`](#fidelis-run-macro) | code | Yes |
| [`fidelis_confirm`](#fidelis-confirm) | data | Yes |
| [`fidelis_cancel_plan`](#fidelis-cancel-plan) | view | No |
| [`fidelis_knowledge`](#fidelis-knowledge) | read | No |
| [`fidelis_find_images`](#fidelis-find-images) | read | No |
| [`fidelis_action_status`](#fidelis-action-status) | read | No |

<a id="fidelis-context"></a>

## fidelis_context

Read Fidelis’s live state: the focused document (id, title, size, channels/slices/frames, bit depth, calibration, path, unsaved changes, plane position, display range), open documents, the active ROI, counts (ROIs, Results rows, History steps), the last History step, the viewer, a plan waiting for confirmation (pending) or running. Refresh before resolving “this” or any change.

Input: `{}`.

<a id="fidelis-images"></a>

## fidelis_images

List open Fidelis documents with ids, titles, sizes, which one is focused and whether each has unsaved changes.

Input: `{}`.

<a id="fidelis-open-path"></a>

## fidelis_open_path

Open an exact local image path in Fidelis as a new document (recorded in History). Returns the document and a highlight.

| Parameter | Required | Accepted value |
|---|---|---|
| `path` | Yes | string; min 1 characters; max 4096 characters |

<a id="fidelis-activate-image"></a>

## fidelis_activate_image

Focus an open document by id ("doc-3"), ImageJ imageId or title. Display only.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `id` | No | string; max 40 characters; pattern `^doc-[0-9]+$` | Fidelis document id exactly as fidelis_context/fidelis_images report it, e.g. "doc-3". |
| `imageId` | No | integer | ImageJ image id (document.imageId), when no document id is known. |
| `title` | No | string; min 1 characters; max 500 characters |  |

<a id="fidelis-commands"></a>

## fidelis_commands

Search every Fiji/ImageJ menu command and plugin in the Fidelis catalogue by name or menu path: returns commandId, label, menuPath, whether its schema is complete, its consequence and parameters (key, type, default, choices). Use before fidelis_command and pass the exact commandId.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | Yes | string; min 1 characters; max 200 characters |
| `limit` | No | integer; min 1; max 50 |

<a id="fidelis-command"></a>

## fidelis_command

Run one catalogue command or plugin on the focused document by its exact commandId from fidelis_commands, with only the params you change (missing keys come from the schema defaults). One History step. A command whose original dialog opens returns status "dialog" with a dialogId: answer it with fidelis_dialog_action. File choosers are refused: use fidelis_open_path.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `commandId` | Yes | string; min 5 characters; max 300 characters; pattern `^[a-z0-9]+:.+$` | Exact catalogue id from fidelis_commands, e.g. "ij1:Process>Filters>Gaussian Blur...". |
| `params` | No | object | Catalogue parameter keys (as the macro recorder writes them, e.g. {"sigma": 2}); only the ones you change, schema defaults fill the rest. |

<a id="fidelis-dialog"></a>

## fidelis_dialog

Read the original Fiji dialog Penny’s running plan waits on: dialogId, title, fields (id, key, label, type, value, choices) and buttons; pending false when none waits.

Input: `{}`.

<a id="fidelis-dialog-action"></a>

## fidelis_dialog_action

Answer the waiting Fiji dialog by its dialogId: set fields by key (or id/label) and press a button (default "OK"; "Cancel" cancels the command). Returns the command’s result, the next dialog (new dialogId) or status cancelled. Never leave a dialog open.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `dialogId` | Yes | string; min 1 characters; max 100 characters | The dialogId of the waiting dialog, from the command or fidelis_dialog result. |
| `fields` | No | object |  |
| `press` | No | string; max 60 characters |  |

<a id="fidelis-segment-cells"></a>

## fidelis_segment_cells

Segment nuclei/cells on the focused document: blur, auto threshold (method, default Otsu), mask, fill holes, watershed, Analyze Particles into the ROI list and Results. Run again with another method to replace the previous segmentation from the original pixels. The document becomes the mask. Returns count, averageSize, unit, steps, undoSteps and a highlight. Leave tuning at defaults unless asked.

| Parameter | Required | Accepted value |
|---|---|---|
| `channel` | No | integer; min 1 |
| `method` | No | string; min 1 characters; max 40 characters |
| `dark` | No | boolean |
| `minSize` | No | number; min 0 |
| `maxSize` | No | number; greater than 0 |
| `pixel` | No | boolean |
| `exclude` | No | boolean |
| `watershed` | No | boolean |
| `gaussian` | No | boolean |
| `sigma` | No | number; greater than 0 |
| `replace` | No | boolean |

<a id="fidelis-measure"></a>

## fidelis_measure

Measure with ImageJ: the requested Set Measurements keys (area, mean, min, max, integrated, shape, …) on the active ROI (scope roi; the whole image without one), the whole document (all) or every ROI-list entry (rois). Multichannel: channel is measured for every ROI, else the segmented channel, else each ROI’s own. Returns rows, lastRow, summary, unit, channel, channelBy; numbers come from here.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `measurements` | No | array of ("area", "mean", "min", "max", "standard", "modal", "centroid", "center", "perimeter", "bounding", "fit", "shape", "feret", "integrated", "median", "skewness", "kurtosis", "area_fraction", "stack"); max 19 items; unique items |  |
| `scope` | Yes | "roi", "all", "rois" |  |
| `channel` | No | integer; min 1 | Channel to measure intensities on (1-based); overrides each ROI’s own channel. |

<a id="fidelis-threshold"></a>

## fidelis_threshold

Threshold the focused document: an auto method (Otsu, Default, Huang, Li, Triangle, Moments, MaxEntropy, Yen, …; dark for bright objects on dark) or explicit lower and upper. Returns lower, upper and method.

| Parameter | Required | Accepted value |
|---|---|---|
| `method` | No | string; min 1 characters; max 40 characters |
| `dark` | No | boolean |
| `lower` | No | number |
| `upper` | No | number |

<a id="fidelis-analyze-particles"></a>

## fidelis_analyze_particles

Count and outline objects in the thresholded document (Analyze Particles): size and circularity ranges ("50-Infinity", "0.50-1.00"; pixel true for pixel units) or minSize/maxSize/minCircularity, exclude edges, include holes, show, add to the ROI list. Returns count, averageSize, unit and a highlight.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `size` | No | string; max 40 characters; pattern `^[0-9]*\.?[0-9]+-(?:[0-9]*\.?[0-9]+\|Infinity)$` | Area range in calibrated units² (pixels with pixel true), e.g. "50-Infinity". |
| `pixel` | No | boolean |  |
| `circularity` | No | string; max 40 characters; pattern `^[0-9]*\.?[0-9]+-(?:[0-9]*\.?[0-9]+\|Infinity)$` | Circularity range 0–1, e.g. "0.50-1.00". |
| `minSize` | No | number; min 0 |  |
| `maxSize` | No | number; greater than 0 |  |
| `minCircularity` | No | number; min 0; max 1 |  |
| `show` | No | "Nothing", "Overlay", "Overlay Masks", "Outlines", "Bare Outlines", "Ellipses", "Masks", "Count Masks" |  |
| `display` | No | boolean |  |
| `exclude` | No | boolean |  |
| `clear` | No | boolean |  |
| `include` | No | boolean |  |
| `summarize` | No | boolean |  |
| `overlay` | No | boolean |  |
| `add` | No | boolean |  |
| `addToManager` | No | boolean |  |
| `composite` | No | boolean |  |

<a id="fidelis-filter"></a>

## fidelis_filter

Apply an ImageJ filter to the focused document: gaussian/median/mean (sigma or radius), unsharp (radius, mask), subtract_background (rolling radius, light, sliding), enhance_contrast (saturated %, normalize, equalize), invert, smooth, sharpen, find_edges; stack for every slice. One History step.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | "gaussian", "median", "mean", "unsharp", "subtract_background", "enhance_contrast", "invert", "smooth", "sharpen", "find_edges" |
| `sigma` | No | number; greater than 0 |
| `radius` | No | number; greater than 0 |
| `mask` | No | number; max 1; greater than 0 |
| `rolling` | No | number; greater than 0 |
| `light` | No | boolean |
| `sliding` | No | boolean |
| `saturated` | No | number; min 0; max 100 |
| `normalize` | No | boolean |
| `equalize` | No | boolean |
| `stack` | No | boolean |

<a id="fidelis-convert"></a>

## fidelis_convert

Convert the focused document to 8-bit, 16-bit, 32-bit or RGB.

| Parameter | Required | Accepted value |
|---|---|---|
| `to` | Yes | "8-bit", "16-bit", "32-bit", "RGB" |

<a id="fidelis-duplicate"></a>

## fidelis_duplicate

Duplicate the focused document (whole stack) into a new document with an optional title. Returns the new document.

| Parameter | Required | Accepted value |
|---|---|---|
| `title` | No | string; min 1 characters; max 200 characters |

<a id="fidelis-select-roi"></a>

## fidelis_select_roi

Set the active selection in image pixels: rectangle or oval by x, y, width, height; polygon by points [[x,y],…]; none clears it. Infer the region from the document size, never ask for coordinates. Returns the ROI and a highlight.

| Parameter | Required | Accepted value |
|---|---|---|
| `type` | Yes | "rectangle", "oval", "polygon", "none" |
| `x` | No | number |
| `y` | No | number |
| `width` | No | number; greater than 0 |
| `height` | No | number; greater than 0 |
| `points` | No | array of (array of (number); min 2 items; max 2 items); min 3 items; max 10000 items |
| `name` | No | string; max 200 characters |

<a id="fidelis-rois"></a>

## fidelis_rois

List the focused document’s ROI-list entries (index, name, type, bounds), bounded by limit and offset; total is the full count.

| Parameter | Required | Accepted value |
|---|---|---|
| `limit` | No | integer; min 1; max 500 |
| `offset` | No | integer; min 0 |

<a id="fidelis-results"></a>

## fidelis_results

Read Results table rows (bounded by limit and offset) with their column names and the total row count.

| Parameter | Required | Accepted value |
|---|---|---|
| `limit` | No | integer; min 1; max 500 |
| `offset` | No | integer; min 0 |

<a id="fidelis-object-stats"></a>

## fidelis_object_stats

Summarise the objects of the latest Analyze Particles in the Results table without re-segmenting: n and area mean/median/min/max with the unit. Use for “how many / how big”.

Input: `{}`.

<a id="fidelis-display"></a>

## fidelis_display

Brightness/contrast of the focused document (display only): min/max of the display range, or auto from the plane’s raw values; channel for a composite.

| Parameter | Required | Accepted value |
|---|---|---|
| `min` | No | number |
| `max` | No | number |
| `auto` | No | boolean |
| `channel` | No | integer; min 1 |

<a id="fidelis-stack"></a>

## fidelis_stack

Move through a stack or hyperstack: slice, channel, frame (1-based). Display only.

| Parameter | Required | Accepted value |
|---|---|---|
| `slice` | No | integer; min 1 |
| `channel` | No | integer; min 1 |
| `frame` | No | integer; min 1 |

<a id="fidelis-zoom"></a>

## fidelis_zoom

Change the focused document’s view: direction in/out (one zoom step), fit true to fit the window, or selection true to zoom to the active ROI. Display only.

| Parameter | Required | Accepted value |
|---|---|---|
| `direction` | No | "in", "out" |
| `fit` | No | boolean |
| `selection` | No | boolean |

<a id="fidelis-highlight"></a>

## fidelis_highlight

Point at what you are talking about: flash all ROI-list entries (rois all), the given indices (rois indices) or the active selection (rois active) on the Fidelis canvas, with a short label beside Penny’s cursor. Display only.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `rois` | Yes | "all", "indices", "active" |  |
| `indices` | No | array of (integer; min 0); min 1 items; max 1000 items |  |
| `label` | No | string; max 80 characters | Two to six words shown beside the cursor, e.g. "64 nuclei". |
| `limit` | No | integer; min 1; max 120 |  |

<a id="fidelis-undo"></a>

## fidelis_undo

Undo the last Fidelis History step, or the last steps (1–20; a segmentation reports its undoSteps). Returns what was undone.

| Parameter | Required | Accepted value |
|---|---|---|
| `steps` | No | integer; min 1; max 20 |

<a id="fidelis-redo"></a>

## fidelis_redo

Redo the last undone Fidelis History step, or steps (1–20).

| Parameter | Required | Accepted value |
|---|---|---|
| `steps` | No | integer; min 1; max 20 |

<a id="fidelis-history"></a>

## fidelis_history

Read Fidelis History, newest last: action id, label, command, origin (typed, spoken, menu), provenance and outcome of each step.

| Parameter | Required | Accepted value |
|---|---|---|
| `limit` | No | integer; min 1; max 500 |

<a id="fidelis-macro"></a>

## fidelis_macro

Export the recorded History as an ImageJ macro (the reproducible workflow) with its step count. Read only.

Input: `{}`.

<a id="fidelis-run-macro"></a>

## fidelis_run_macro

Run ImageJ macro code in Fidelis for anything no catalogue command does. Always waits for the researcher’s confirmation: returns awaiting_confirmation and runs after fidelis_confirm.

| Parameter | Required | Accepted value |
|---|---|---|
| `code` | Yes | string; min 1 characters; max 32000 characters |

<a id="fidelis-confirm"></a>

## fidelis_confirm

Run the plan waiting for confirmation (context.pending) when the researcher’s latest words are “yes” / “go ahead”; refused otherwise, never confirm on your own. planId defaults to the pending plan. Returns that plan’s result.

| Parameter | Required | Accepted value |
|---|---|---|
| `planId` | No | string; min 1 characters; max 100 characters |

<a id="fidelis-cancel-plan"></a>

## fidelis_cancel_plan

Discard the plan waiting for confirmation (planId defaults to it); nothing runs.

| Parameter | Required | Accepted value |
|---|---|---|
| `planId` | No | string; min 1 characters; max 100 characters |

<a id="fidelis-knowledge"></a>

## fidelis_knowledge

Search Penny’s Fidelis guidance and the locally downloaded official ImageJ documentation (user guide, menus, macro functions). Use before unfamiliar commands, filters, measurements or macros.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | Yes | string; min 2 characters; max 200 characters |

<a id="fidelis-find-images"></a>

## fidelis_find_images

Find images on this computer by a loose name ("the blobs tif", "embryos"): searches Penny’s data folder, Desktop, Downloads and Documents. Use before fidelis_open_path when the researcher names an image instead of a path; never ask them for a folder.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | No | string; max 200 characters |
| `limit` | No | integer; min 1; max 50 |

<a id="fidelis-action-status"></a>

## fidelis_action_status

Read a recorded action (completed, failed, denied or expired) by ID. Decisions are taken by policy or the researcher; there is no model approval tool.

| Parameter | Required | Accepted value |
|---|---|---|
| `actionId` | Yes | string |

See [analysis semantics and verification limits](CAPABILITIES.md), [setup](PENNY-SETUP.md) and [scientific task guidance](../skills/fidelis/SKILL.md).
