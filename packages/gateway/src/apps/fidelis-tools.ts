import type { Tool } from '../tools.js';
import { toolFactory } from '../tool-schema.js';

/** Fidelis runs as a plain JVM (`java @fidelis-app/build/run.args`) from the Zulu JDK bundled with Fiji, so macOS knows
 * it by the identifier embedded in that `java` launcher, not by a Fidelis bundle; the companion uses it to find the
 * windows holding the canvas. Shared with any other Zulu `java` GUI process and not yet confirmed on a live run;
 * override with LUMA_FIDELIS_BUNDLE_ID without a rebuild. */
export const FIDELIS_BUNDLE_ID = process.env.LUMA_FIDELIS_BUNDLE_ID?.trim() || 'com.azul.zulu.java';

const number = { type: 'number' };
const boolean = { type: 'boolean' };
const positive = { type: 'number', exclusiveMinimum: 0 };
const limit = { type: 'integer', minimum: 1, maximum: 500 };
const offset = { type: 'integer', minimum: 0 };
const method = { type: 'string', minLength: 1, maxLength: 40 };
const planId = { type: 'string', minLength: 1, maxLength: 100 };
// Fidelis document ids are DocumentId strings ("doc-3"); the bridge also accepts ImageJ's integer image id.
const documentId = { type: 'string', pattern: '^doc-[0-9]+$', maxLength: 40, description: 'Fidelis document id exactly as fidelis_context/fidelis_images report it, e.g. "doc-3".' };
const imageId = { type: 'integer', description: 'ImageJ image id (document.imageId), when no document id is known.' };
// Catalogue ids are `ij1:<menu path>` (docs/data/commands.csv), e.g. "ij1:Process>Filters>Gaussian Blur...".
const commandId = { type: 'string', pattern: '^[a-z0-9]+:.+$', minLength: 5, maxLength: 300, description: 'Exact catalogue id from fidelis_commands, e.g. "ij1:Process>Filters>Gaussian Blur...".' };
const scalar = { anyOf: [{ type: 'string', maxLength: 4000 }, { type: 'number' }, { type: 'boolean' }] };
const params = { type: 'object', maxProperties: 40, additionalProperties: scalar,
  description: 'Catalogue parameter keys (as the macro recorder writes them, e.g. {"sigma": 2}); only the ones you change, schema defaults fill the rest.' };
// Set Measurements keys as the bridge takes them (docs/FIDELIS-PROTOCOL.md, measure).
const measurement = { type: 'string', enum: ['area', 'mean', 'min', 'max', 'standard', 'modal', 'centroid', 'center', 'perimeter', 'bounding', 'fit', 'shape', 'feret', 'integrated', 'median', 'skewness', 'kurtosis', 'area_fraction', 'stack'] };
const range = (description: string) => ({ type: 'string', pattern: '^[0-9]*\\.?[0-9]+-(?:[0-9]*\\.?[0-9]+|Infinity)$', maxLength: 40, description });
const steps = { type: 'integer', minimum: 1, maximum: 20 };
const tool = toolFactory('fidelis');

/** Fidelis workstation tools: one per operation in docs/FIDELIS-PROTOCOL.md (`fidelis_<operation>`), plus the local
 * image search, knowledge and action-status tools. Every change runs through the Fidelis engine, so it is recorded in
 * Fidelis History and undoable there. Session operations (`open_path`) take the data decision path. */
export const fidelisTools: Tool[] = [
  tool('context', 'Read Fidelis’s live state: the focused document (id, title, size, channels/slices/frames, bit depth, calibration, path, unsaved changes, plane position, display range), open documents, the active ROI, counts (ROIs, Results rows, History steps), the last History step, the viewer, a plan waiting for confirmation (pending) or running. Refresh before resolving “this” or any change.', 'context', 'read'),
  tool('images', 'List open Fidelis documents with ids, titles, sizes, which one is focused and whether each has unsaved changes.', 'images', 'read'),
  tool('open_path', 'Open an exact local image path in Fidelis as a new document (recorded in History). Returns the document and a highlight.', 'open_path', 'data', { path: { type: 'string', minLength: 1, maxLength: 4096 } }, ['path']),
  tool('activate_image', 'Focus an open document by id ("doc-3"), ImageJ imageId or title. Display only.', 'activate_image', 'view', { id: documentId, imageId, title: { type: 'string', minLength: 1, maxLength: 500 } }),
  tool('commands', 'Search every Fiji/ImageJ menu command and plugin in the Fidelis catalogue by name or menu path: returns commandId, label, menuPath, whether its schema is complete, its consequence and parameters (key, type, default, choices). Use before fidelis_command and pass the exact commandId.', 'commands', 'read', { query: { type: 'string', minLength: 1, maxLength: 200 }, limit: { type: 'integer', minimum: 1, maximum: 50 } }, ['query']),
  tool('command', 'Run one catalogue command or plugin on the focused document by its exact commandId from fidelis_commands, with only the params you change (missing keys come from the schema defaults). One History step. A command whose original dialog opens returns status "dialog" with a dialogId: answer it with fidelis_dialog_action. File choosers are refused: use fidelis_open_path.', 'command', 'data', { commandId, params }, ['commandId']),
  tool('dialog', 'Read the original Fiji dialog Penny’s running plan waits on: dialogId, title, fields (id, key, label, type, value, choices) and buttons; pending false when none waits.', 'dialog', 'read'),
  tool('dialog_action', 'Answer the waiting Fiji dialog by its dialogId: set fields by key (or id/label) and press a button (default "OK"; "Cancel" cancels the command). Returns the command’s result, the next dialog (new dialogId) or status cancelled. Never leave a dialog open.', 'dialog_action', 'data', {
    dialogId: { type: 'string', minLength: 1, maxLength: 100, description: 'The dialogId of the waiting dialog, from the command or fidelis_dialog result.' },
    fields: { type: 'object', maxProperties: 60, additionalProperties: scalar }, press: { type: 'string', maxLength: 60 } }, ['dialogId']),
  tool('segment_cells', 'Segment nuclei/cells on the focused document: blur, auto threshold (method, default Otsu), mask, fill holes, watershed, Analyze Particles into the ROI list and Results. Run again with another method to replace the previous segmentation from the original pixels. The document becomes the mask. Returns count, averageSize, unit, steps, undoSteps and a highlight. Leave tuning at defaults unless asked.', 'segment_cells', 'data', {
    channel: { type: 'integer', minimum: 1 }, method, dark: boolean, minSize: { type: 'number', minimum: 0 }, maxSize: positive, pixel: boolean, exclude: boolean, watershed: boolean, gaussian: boolean, sigma: positive, replace: boolean }),
  tool('measure', 'Measure with ImageJ: the requested Set Measurements keys (area, mean, min, max, integrated, shape, …) on the active ROI (scope roi; the whole image without one), the whole document (all) or every ROI-list entry (rois). Multichannel: channel is measured for every ROI, else the segmented channel, else each ROI’s own. Returns rows, lastRow, summary, unit, channel, channelBy; numbers come from here.', 'measure', 'data', { measurements: { type: 'array', items: measurement, maxItems: 19, uniqueItems: true }, scope: { type: 'string', enum: ['roi', 'all', 'rois'] },
    channel: { type: 'integer', minimum: 1, description: 'Channel to measure intensities on (1-based); overrides each ROI’s own channel.' } }, ['scope']),
  tool('threshold', 'Threshold the focused document: an auto method (Otsu, Default, Huang, Li, Triangle, Moments, MaxEntropy, Yen, …; dark for bright objects on dark) or explicit lower and upper. Returns lower, upper and method.', 'threshold', 'data', { method, dark: boolean, lower: number, upper: number }),
  tool('analyze_particles', 'Count and outline objects in the thresholded document (Analyze Particles): size and circularity ranges ("50-Infinity", "0.50-1.00"; pixel true for pixel units) or minSize/maxSize/minCircularity, exclude edges, include holes, show, add to the ROI list. Returns count, averageSize, unit and a highlight.', 'analyze_particles', 'data', {
    size: range('Area range in calibrated units² (pixels with pixel true), e.g. "50-Infinity".'), pixel: boolean, circularity: range('Circularity range 0–1, e.g. "0.50-1.00".'),
    minSize: { type: 'number', minimum: 0 }, maxSize: positive, minCircularity: { type: 'number', minimum: 0, maximum: 1 },
    show: { type: 'string', enum: ['Nothing', 'Overlay', 'Overlay Masks', 'Outlines', 'Bare Outlines', 'Ellipses', 'Masks', 'Count Masks'] },
    display: boolean, exclude: boolean, clear: boolean, include: boolean, summarize: boolean, overlay: boolean, add: boolean, addToManager: boolean, composite: boolean }),
  tool('filter', 'Apply an ImageJ filter to the focused document: gaussian/median/mean (sigma or radius), unsharp (radius, mask), subtract_background (rolling radius, light, sliding), enhance_contrast (saturated %, normalize, equalize), invert, smooth, sharpen, find_edges; stack for every slice. One History step.', 'filter', 'data', {
    name: { type: 'string', enum: ['gaussian', 'median', 'mean', 'unsharp', 'subtract_background', 'enhance_contrast', 'invert', 'smooth', 'sharpen', 'find_edges'] },
    sigma: positive, radius: positive, mask: { type: 'number', exclusiveMinimum: 0, maximum: 1 }, rolling: positive, light: boolean, sliding: boolean, saturated: { type: 'number', minimum: 0, maximum: 100 }, normalize: boolean, equalize: boolean, stack: boolean }, ['name']),
  tool('convert', 'Convert the focused document to 8-bit, 16-bit, 32-bit or RGB.', 'convert', 'data', { to: { type: 'string', enum: ['8-bit', '16-bit', '32-bit', 'RGB'] } }, ['to']),
  tool('duplicate', 'Duplicate the focused document (whole stack) into a new document with an optional title. Returns the new document.', 'duplicate', 'data', { title: { type: 'string', minLength: 1, maxLength: 200 } }),
  tool('select_roi', 'Set the active selection in image pixels: rectangle or oval by x, y, width, height; polygon by points [[x,y],…]; none clears it. Infer the region from the document size, never ask for coordinates. Returns the ROI and a highlight.', 'select_roi', 'view', { type: { type: 'string', enum: ['rectangle', 'oval', 'polygon', 'none'] }, x: number, y: number, width: positive, height: positive, points: { type: 'array', items: { type: 'array', items: number, minItems: 2, maxItems: 2 }, minItems: 3, maxItems: 10000 }, name: { type: 'string', maxLength: 200 } }, ['type']),
  tool('rois', 'List the focused document’s ROI-list entries (index, name, type, bounds), bounded by limit and offset; total is the full count.', 'rois', 'read', { limit, offset }),
  tool('results', 'Read Results table rows (bounded by limit and offset) with their column names and the total row count.', 'results', 'read', { limit, offset }),
  tool('object_stats', 'Summarise the objects of the latest Analyze Particles in the Results table without re-segmenting: n and area mean/median/min/max with the unit. Use for “how many / how big”.', 'object_stats', 'read'),
  tool('display', 'Brightness/contrast of the focused document (display only): min/max of the display range, or auto from the plane’s raw values; channel for a composite.', 'display', 'view', { min: number, max: number, auto: boolean, channel: { type: 'integer', minimum: 1 } }),
  tool('stack', 'Move through a stack or hyperstack: slice, channel, frame (1-based). Display only.', 'stack', 'view', { slice: { type: 'integer', minimum: 1 }, channel: { type: 'integer', minimum: 1 }, frame: { type: 'integer', minimum: 1 } }),
  tool('zoom', 'Change the focused document’s view: direction in/out (one zoom step), fit true to fit the window, or selection true to zoom to the active ROI. Display only.', 'zoom', 'view', { direction: { type: 'string', enum: ['in', 'out'] }, fit: boolean, selection: boolean }),
  tool('highlight', 'Point at what you are talking about: flash all ROI-list entries (rois all), the given indices (rois indices) or the active selection (rois active) on the Fidelis canvas, with a short label beside Penny’s cursor. Display only.', 'highlight', 'view', { rois: { type: 'string', enum: ['all', 'indices', 'active'] }, indices: { type: 'array', items: { type: 'integer', minimum: 0 }, minItems: 1, maxItems: 1000 }, label: { type: 'string', maxLength: 80, description: 'Two to six words shown beside the cursor, e.g. "64 nuclei".' }, limit: { type: 'integer', minimum: 1, maximum: 120 } }, ['rois']),
  tool('undo', 'Undo the last Fidelis History step, or the last steps (1–20; a segmentation reports its undoSteps). Returns what was undone.', 'undo', 'data', { steps }),
  tool('redo', 'Redo the last undone Fidelis History step, or steps (1–20).', 'redo', 'data', { steps }),
  tool('history', 'Read Fidelis History, newest last: action id, label, command, origin (typed, spoken, menu), provenance and outcome of each step.', 'history', 'read', { limit }),
  tool('macro', 'Export the recorded History as an ImageJ macro (the reproducible workflow) with its step count. Read only.', 'macro', 'read'),
  tool('run_macro', 'Run ImageJ macro code in Fidelis for anything no catalogue command does. Always waits for the researcher’s confirmation: returns awaiting_confirmation and runs after fidelis_confirm.', 'run_macro', 'code', { code: { type: 'string', minLength: 1, maxLength: 32000 } }, ['code']),
  tool('confirm', 'Run the plan waiting for confirmation (context.pending) when the researcher’s latest words are “yes” / “go ahead”; refused otherwise, never confirm on your own. planId defaults to the pending plan. Returns that plan’s result.', 'confirm', 'data', { planId }),
  tool('cancel_plan', 'Discard the plan waiting for confirmation (planId defaults to it); nothing runs.', 'cancel_plan', 'view', { planId }),
  tool('knowledge', 'Search Penny’s Fidelis guidance and the locally downloaded official ImageJ documentation (user guide, menus, macro functions). Use before unfamiliar commands, filters, measurements or macros.', undefined, 'read', { query: { type: 'string', minLength: 2, maxLength: 200 } }, ['query']),
  tool('find_images', 'Find images on this computer by a loose name ("the blobs tif", "embryos"): searches Penny’s data folder, Desktop, Downloads and Documents. Use before fidelis_open_path when the researcher names an image instead of a path; never ask them for a folder.', undefined, 'read', { query: { type: 'string', maxLength: 200 }, limit: { type: 'integer', minimum: 1, maximum: 50 } }),
  tool('action_status', 'Read a recorded action (completed, failed, denied or expired) by ID. Decisions are taken by policy or the researcher; there is no model approval tool.', undefined, 'read', { actionId: { type: 'string' } }, ['actionId']),
];
