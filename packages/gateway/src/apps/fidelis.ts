import type { AppProfile, FastRoute, PrepareInput } from './profile.js';
import { cleanUtterance, completedPayload, compoundRequest, continuesGenerally, finite, highlightAcknowledgment } from './profile.js';
import { fidelisTools, FIDELIS_BUNDLE_ID } from './fidelis-tools.js';
import { fidelisInstructions } from './fidelis-instructions.js';
import { friendlyName } from './fiji.js';
import { imageNameMatches, queryWords } from '../local-images.js';
import { conversationEnvelope, observedContext } from '../tool-request.js';

/** The parts of the bridge's `context` result (docs/FIDELIS-PROTOCOL.md) the instant routes and sentences read. */
type FidelisDocument = { id?: unknown; title?: unknown; width?: unknown; height?: unknown; channels?: unknown; slices?: unknown; frames?: unknown; bitDepth?: unknown;
  unit?: unknown; pixelWidth?: unknown; pixelHeight?: unknown; path?: unknown;
  /** Brighter/darker and next/previous slice read these; `binary` (requested of the bridge) gates spoken intensities. */
  displayRange?: unknown; display?: { min?: unknown; max?: unknown }; position?: { channel?: unknown; slice?: unknown; frame?: unknown }; binary?: unknown };
type FidelisContext = { document?: FidelisDocument | null; documents?: Array<{ id?: unknown; title?: unknown; focused?: unknown }>; roi?: unknown;
  counts?: { rois?: unknown; resultsRows?: unknown; history?: unknown }; pending?: { planId?: unknown; reasons?: unknown } | null;
  lastStep?: { actionId?: unknown; label?: unknown; origin?: unknown; outcome?: unknown } | null };

/** A mean measured on the focused document is an intensity only when the bridge says the document is not a binary mask.
 * The last History step cannot tell (a selection or zoom after segmenting hides it), so without `document.binary: false`
 * intensities are phrased by the model, never spoken locally. */
export const intensityTrusted = (context: unknown) => (context as FidelisContext | undefined)?.document?.binary === false;
/** Spoken at once when the model's first call is one of these. A plan may still wait for confirmation, so a macro is "prepared", never "running". */
const acknowledgments: Record<string, string> = {
  fidelis_segment_cells: 'Segmenting cells.', fidelis_measure: 'Measuring.', fidelis_threshold: 'Thresholding.', fidelis_analyze_particles: 'Counting particles.',
  fidelis_filter: 'Applying the filter.', fidelis_open_path: 'Opening it.', fidelis_command: 'On it.', fidelis_select_roi: 'Selecting the region.', fidelis_zoom: 'Zooming.',
  fidelis_undo: 'Undoing.', fidelis_redo: 'Redoing.', fidelis_run_macro: 'Preparing the macro.', fidelis_confirm: 'Running it.', fidelis_cancel_plan: 'Cancelling.',
  fidelis_convert: 'Converting.', fidelis_duplicate: 'Duplicating.', fidelis_display: 'Adjusting the display.', fidelis_stack: 'Changing slice.', fidelis_activate_image: 'Switching image.',
  fidelis_find_images: 'Finding it.', fidelis_images: 'Checking.', fidelis_rois: 'Checking.', fidelis_results: 'Checking.', fidelis_history: 'Checking.', fidelis_object_stats: 'Counting.',
  fidelis_commands: 'Looking it up.', fidelis_macro: 'Here is the macro.', fidelis_dialog: 'Checking the dialog.', fidelis_dialog_action: 'Filling in the dialog.',
};
function acknowledgmentFor(name: string, args: Record<string, unknown>): string | undefined {
  if (name === 'fidelis_highlight') return highlightAcknowledgment(args);
  return acknowledgments[name];
}
const silentFinishTools = new Set(['fidelis_zoom', 'fidelis_highlight', 'fidelis_select_roi', 'fidelis_activate_image', 'fidelis_stack', 'fidelis_display']);
const round = (value: number) => Number.isInteger(value) ? String(value) : Math.abs(value) >= 100 ? value.toFixed(0) : Math.abs(value) >= 10 ? value.toFixed(1) : value.toFixed(2);
const text = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : undefined;
const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? '' : 's'}`;
const record = (value: unknown) => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
/** An area in the unit the bridge reported; a sentence never guesses "px" or "µm". */
function spokenArea(area: number, unit: unknown): string {
  const u = text(unit);
  if (!u) return round(area);
  if (/^pixels?$/i.test(u)) return `${round(area)} square pixels`;
  if (/^(?:µm|um|μm|microns?)$/i.test(u) && area >= 1e6) return `${round(area / 1e6)} mm²`;
  return `${round(area)} ${/^(?:um|microns?)$/i.test(u) ? 'µm' : u}²`;
}
/** A Results column by its ImageJ name, whatever the case ("Mean", "mean"). */
function column(source: unknown, name: string): unknown {
  const table = record(source); if (!table) return undefined;
  const key = Object.keys(table).find(candidate => candidate.toLowerCase() === name.toLowerCase());
  return key === undefined ? undefined : table[key];
}
/** The bridge payload when it holds a plan waiting for the researcher's Confirm or "yes" (docs/FIDELIS-PROTOCOL.md, Confirmation). */
export function awaitingConfirmation(result: unknown): Record<string, unknown> | undefined {
  const inner = completedPayload(result) ?? record(result);
  return inner?.status === 'awaiting_confirmation' ? inner : undefined;
}
/** The bridge payload when a legacy command's original Fiji dialog is waiting (`status: "dialog"`): the model fills it. */
export function dialogWaiting(result: unknown): boolean { return (completedPayload(result) ?? record(result))?.status === 'dialog'; }
/** A command ended at its dialog's Cancel or No (`status: "cancelled"`, HTTP 200): nothing more ran, and it never reads as done. */
export function cancelledAtDialog(result: unknown): Record<string, unknown> | undefined { const inner = completedPayload(result) ?? record(result); return inner?.status === 'cancelled' ? inner : undefined; }
/** What a completed bridge call achieved short of running: a plan that waits, a dialog that waits, or a cancelled command. */
export function fidelisOutcome(result: unknown): 'awaiting_confirmation' | 'dialog' | 'cancelled' | undefined {
  return awaitingConfirmation(result) ? 'awaiting_confirmation' : dialogWaiting(result) ? 'dialog' : cancelledAtDialog(result) ? 'cancelled' : undefined;
}
/** "Ready to run 3 steps; say yes to go ahead." — nothing has run. */
export function awaitingSentence(plan: Record<string, unknown>): string {
  const steps = Array.isArray(plan.steps) ? plan.steps.length : finite(plan.steps);
  return steps === undefined || steps < 1 ? 'Ready to run it; say yes to go ahead.' : `Ready to run ${plural(steps, 'step')}; say yes to go ahead.`;
}
/** "Gaussian Blur..." → "Gaussian Blur"; a catalogue id's last menu item when the step has no label. */
function stepName(step: unknown, fallbackId?: unknown): string | undefined {
  const label = text(record(step)?.label) ?? text(fallbackId)?.split('>').pop();
  return label?.replace(/^ij1:/, '').replace(/(?:\.{3}|…)$/, '').trim() || undefined;
}
function countSentence(inner: Record<string, unknown>): string | undefined {
  const count = finite(inner.count); if (count === undefined) return undefined;
  const mean = finite(inner.averageSize);
  // The bridge names the channel it segmented on a multichannel image (the blue/DAPI channel unless one was asked for).
  const channel = finite(inner.channel), by = text(inner.channelBy);
  const where = channel !== undefined && by !== undefined && by !== 'current' ? ` on channel ${channel}${by === 'lut-blue' ? ' (blue)' : ''}` : '';
  return `Found ${count} cells${where}${mean !== undefined ? `, mean area ${spokenArea(mean, inner.unit)}` : ''}.`;
}
/** "Fiji wanted to save a file" after a command: the bridge cancelled the OS chooser itself (docs/FIDELIS-PROTOCOL.md). */
const skippedSave = (inner: Record<string, unknown>) => inner.cancelledFileDialog === true ? ' Fiji asked where to save a file; Penny skipped that.' : '';
/** A macro that ran: what it produced (Results rows, images) from the engine's own counts. */
function macroRunSentence(inner: Record<string, unknown>): string {
  const rows = finite(inner.resultsRowsAdded), docs = Array.isArray(inner.documentsAdded) ? inner.documentsAdded.length : finite(inner.documentsAdded);
  const parts: string[] = [];
  if (rows !== undefined && rows > 0) parts.push(`${plural(rows, 'result row')}`);
  if (docs !== undefined && docs > 0) parts.push(`${plural(docs, 'new image')}${typeof inner.focused === 'string' ? ' on screen' : ''}`);
  return parts.length ? `Ran the macro: ${parts.join(', ')}.` : 'Ran the macro.';
}
/** The exported macro, summarised: a Fidelis session can hold hundreds of steps, which is not a spoken answer. */
function macroSentence(raw: Record<string, unknown>): string | undefined {
  const macro = text(raw.macro); if (!macro) return undefined;
  const steps = Array.isArray(raw.steps) ? raw.steps.length : finite(raw.steps);
  const lines = macro.split('\n').filter(line => line.trim() && !line.trim().startsWith('//')).length;
  return `The macro has ${plural(lines, 'line')}${steps !== undefined ? ` from ${plural(steps, 'step')}` : ''}; it is in the History and macro tab, ready to export.`;
}
/** The model never receives a whole session macro: its size, its header and first lines, and the step count. */
const MODEL_MACRO_LINES = 40;
export function macroForModel(result: unknown): unknown {
  const raw = record(result); const macro = text(raw?.macro); if (!raw || !macro) return result;
  const all = macro.split('\n');
  if (all.length <= MODEL_MACRO_LINES && macro.length <= 6000) return result;
  const steps = Array.isArray(raw.steps) ? raw.steps.length : raw.steps;
  return { ...raw, steps, macroLines: all.length, macroChars: macro.length, macro: all.slice(0, MODEL_MACRO_LINES).join('\n') + `\n// … ${all.length - MODEL_MACRO_LINES} more lines; the full macro is in Fidelis's History and macro tab (Export .ijm).`,
    note: 'The macro is shown truncated to the model; tell the researcher its size and where it is, never paste it.' };
}
function measureSentence(args: Record<string, unknown>, inner: Record<string, unknown>): string | undefined {
  if (args.scope === 'rois') {
    // The summary covers the rows this call measured (one per ROI-list entry).
    const mean = column(inner.summary, 'Mean') as { mean?: unknown; count?: unknown } | undefined;
    const value = finite(mean?.mean), rows = finite(mean?.count) ?? finite(inner.rows);
    if (value === undefined) return undefined;
    // Every object reading exactly 0 or 255 is a binary mask (segment_cells works in place), not an intensity.
    const low = finite((mean as { min?: unknown }).min), high = finite((mean as { max?: unknown }).max);
    if (low === high && (value === 0 || value === 255) && low === value) return undefined;
    const channel = finite(inner.channel), onChannel = channel !== undefined && inner.channelBy !== 'displayed' ? ` on channel ${channel}` : '';
    return rows === undefined ? `Mean intensity ${round(value)} across the regions${onChannel}.` : `Mean intensity ${round(value)} over ${plural(rows, 'region')}${onChannel}.`;
  }
  // Without a known scope (a confirmed plan) the sentence names no place rather than guess one.
  const last = record(inner.lastRow); const where = args.scope === 'all' ? ' in the image' : args.scope === 'roi' ? ' in this region' : '';
  const mean = finite(column(last, 'Mean'));
  const channel = finite(inner.channel), onChannel = channel !== undefined && inner.channelBy !== 'displayed' ? ` on channel ${channel}` : '';
  if (mean !== undefined) return `Mean intensity ${round(mean)}${where}${onChannel}.`;
  const area = finite(column(last, 'Area'));
  return area === undefined ? undefined : `Area ${spokenArea(area, inner.unit)}${where}.`;
}
/** Whether a measurement result carries a Mean (the intensity a sentence would speak). */
const measuresIntensity = (inner: Record<string, unknown>) => column(inner.summary, 'Mean') !== undefined || column(inner.lastRow, 'Mean') !== undefined;
function thresholdSentence(inner: Record<string, unknown>): string | undefined {
  const lower = finite(inner.lower), upper = finite(inner.upper);
  if (lower === undefined || upper === undefined) return undefined;
  const method = text(inner.method) ? ` (${text(inner.method)})` : '';
  return `Thresholded at ${round(lower)}–${round(upper)}${method}.`;
}
/** A confirmed plan answers with the result of what it ran: a count, a measurement, a threshold or its steps. */
function confirmSentence(inner: Record<string, unknown>, context?: unknown): string | undefined {
  const counted = countSentence(inner); if (counted) return counted;
  const threshold = thresholdSentence(inner); if (threshold) return threshold;
  if (inner.lastRow || inner.summary) {
    // A mean is spoken as an intensity only when the document is known not to be a mask; the model explains otherwise.
    if (measuresIntensity(inner) && !intensityTrusted(context)) return undefined;
    // The confirm call does not carry the plan's scope: more than one measured row is a multi-ROI measurement, reported
    // from its summary; a single row names no place (roi and all look the same).
    const rows = finite(inner.rows) ?? finite((column(inner.summary, 'Mean') as { count?: unknown } | undefined)?.count);
    const measured = measureSentence({ scope: !inner.lastRow || (rows !== undefined && rows > 1) ? 'rois' : undefined }, inner); if (measured) return measured;
  }
  const name = stepName(inner.step, record(inner.step)?.commandId);
  if (name && /macro/i.test(name)) return macroRunSentence(inner);
  if (name) return `Ran ${name}.`;
  if (Array.isArray(inner.steps) && inner.steps.length) return `Ran ${plural(inner.steps.length, 'step')}.`;
  return 'Done.';
}
/** Spoken outcome built from the real result; '' stays silent, undefined asks the model to phrase or continue. */
function localSentenceFor(name: string, args: Record<string, unknown>, result: unknown, context?: unknown): string | undefined {
  if (!result || typeof result !== 'object' || (result as { error?: unknown }).error != null) return undefined;
  const plan = awaitingConfirmation(result); if (plan) return awaitingSentence(plan);
  // A waiting dialog is not an outcome: the model continues and fills it with fidelis_dialog_action.
  if (dialogWaiting(result) || name === 'fidelis_dialog') return undefined;
  const cancelled = cancelledAtDialog(result);
  if (cancelled) {
    const title = (text(cancelled.dialog) ?? text(record(cancelled.dialog)?.title))?.replace(/(?:\.{3}|…)$/, '');
    if (cancelled.cancelledFileDialog === true) return `Fiji asked where to save a file${title ? ` (${title})` : ''}; Penny does not drive file dialogs, so the command ended there.`;
    return title ? `Cancelled at the ${title} dialog; nothing more ran.` : 'Cancelled; nothing more ran.';
  }
  if (silentFinishTools.has(name)) return '';
  const inner = completedPayload(result);
  // Read and view operations return the bridge result itself, without a decision envelope.
  const raw = inner ?? record(result) ?? {};
  // A zero is a real answer and keeps its digit, so an instant route speaks it instead of its "Counting." placeholder.
  if (name === 'fidelis_rois') { const total = finite(raw.total); return total === undefined ? undefined : total === 0 ? '0 objects in the ROI list.' : `${plural(total, 'object')}.`; }
  if (name === 'fidelis_object_stats') {
    const n = finite(raw.n); if (n === undefined) return undefined;
    if (n === 0) return '0 measured objects.';
    const area = record(raw.area), mean = finite(area?.mean), median = finite(area?.median);
    return `${plural(n, 'object')}${mean !== undefined ? `, mean area ${spokenArea(mean, raw.unit)}` : ''}${median !== undefined ? `, median ${spokenArea(median, raw.unit)}` : ''}.`;
  }
  // A stopped running plan is never announced as "nothing ran": its completed steps stay in History.
  if (name === 'fidelis_cancel_plan') return raw.stopped ? 'Stopped; what had run stays in History.' : raw.dialogCancelled ? 'Cancelled the waiting dialog.' : raw.cancelled === false ? 'Nothing was waiting.' : 'Cancelled; nothing ran.';
  if (name === 'fidelis_macro') return macroSentence(raw);
  if (inner === undefined) return undefined;
  if (name === 'fidelis_segment_cells' || name === 'fidelis_analyze_particles') return countSentence(inner);
  // A mean is spoken as an intensity only on a document the bridge reports as not binary (segment_cells leaves the mask
  // in place); otherwise the model phrases it. Areas are valid either way.
  if (name === 'fidelis_measure') return measuresIntensity(inner) && !intensityTrusted(context) ? undefined : measureSentence(args, inner);
  if (name === 'fidelis_threshold') return thresholdSentence(inner);
  if (name === 'fidelis_open_path') {
    const document = record(inner.document); const title = text(document?.title), width = finite(document?.width), height = finite(document?.height);
    if (!title || width === undefined || height === undefined) return undefined;
    return `Opened ${friendlyName(title, document?.path)}, ${width} by ${height}.`;
  }
  if (name === 'fidelis_undo' || name === 'fidelis_redo') {
    const undo = name === 'fidelis_undo';
    const ids = inner.undoneActionIds ?? inner.redoneActionIds;
    if (Array.isArray(ids) && ids.length > 1) return `${undo ? 'Undid' : 'Redid'} ${ids.length} steps.`;
    const what = stepName(inner.step)?.replace(undo ? /^undo\b[:\s-]*/i : /^redo\b[:\s-]*/i, '').trim();
    // The engine says what an undo put back (18 Oct): the ROI list, Results rows, pixels.
    const restored = record(inner.restored); const parts: string[] = [];
    const rois = record(restored?.rois), rows = record(restored?.resultsRows);
    const roisBefore = finite(rois?.before), roisAfter = finite(rois?.after), rowsBefore = finite(rows?.before), rowsAfter = finite(rows?.after);
    if (roisBefore !== undefined && roisAfter !== undefined && roisBefore !== roisAfter) parts.push(`${roisBefore} objects → ${roisAfter}`);
    if (rowsBefore !== undefined && rowsAfter !== undefined && rowsBefore !== rowsAfter) parts.push(rowsAfter === 0 ? 'Results cleared' : `Results ${rowsBefore} → ${rowsAfter} rows`);
    if (restored?.pixels === true) parts.push('pixels restored');
    if (undo && parts.length) return `Undid${what ? ` ${what}` : ''}: ${parts.join(', ')}.`;
    return what ? `${undo ? 'Undid' : 'Redid'} ${what}.` : undo ? 'Undone.' : 'Redone.';
  }
  if (name === 'fidelis_command' || name === 'fidelis_dialog_action') { const what = stepName(inner.step, args.commandId); return `${what ? `Ran ${what}.` : 'Done.'}${skippedSave(inner)}`; }
  if (name === 'fidelis_filter') return 'Filter applied.';
  if (name === 'fidelis_convert') return typeof args.to === 'string' ? `Converted to ${args.to}.` : 'Converted.';
  if (name === 'fidelis_duplicate') return 'Duplicated.';
  if (name === 'fidelis_run_macro') return macroRunSentence(inner);
  if (name === 'fidelis_confirm') { const sentence = confirmSentence(inner, context); return sentence === undefined ? undefined : `${sentence}${skippedSave(inner)}`; }
  return undefined;
}
const analysisWords = /\b(?:measur\w*|count\w*|segment\w*|detect\w*|quantif\w*|coloc\w*|correlat\w*|overlap\w*|track\w*|analy[sz]\w*|threshold\w*|classif\w*|how many|intensit\w*|percent\w*|compar\w*|subtract\w*|project\w*|export\w*|macro)\b/;
/** Wording the local sentence cannot answer sends the turn back to the model. A plan waiting for confirmation is the
 * whole answer: nothing later in the request may run before the researcher confirms it. */
function requestContinuesAfter(name: string, utterance: string, result?: unknown): boolean {
  if (awaitingConfirmation(result)) return false;
  if (dialogWaiting(result)) return true;
  const said = utterance.toLowerCase();
  if (continuesGenerally(said)) return true;
  if ((silentFinishTools.has(name) || name === 'fidelis_open_path') && (analysisWords.test(said) || compoundRequest(utterance))) return true;
  if (name === 'fidelis_segment_cells' && /\b(strict\w*|loose\w*|tight\w*|sensitiv\w*|aggressive|conservative)\b/.test(said)) return false;
  if (name === 'fidelis_segment_cells') return !/\b(segment|count|find|detect|identify|outline|how many)\b/.test(said) || /\b(area|size|intens|measur|mean|bright|perimeter|circular)/.test(said)
    || /\b(positive|negative|ki-?67|marker|percent|fraction|proportion|index|express)/.test(said);
  if (name === 'fidelis_measure') return /\b(area|size|how many|count|perimeter|circular|feret|min|max|integrated|median)\b/.test(said);
  if (name === 'fidelis_threshold') return /\b(count|particles?|how many|cells?|nuclei|segment\w*|strict\w*|loose\w*|tight\w*|sensitiv\w*|aggressive|conservative|more|less|higher|lower|again|redo)\b/.test(said);
  if (name === 'fidelis_analyze_particles') return /\b(intens|mean gray|brightness)\b/.test(said);
  if (name === 'fidelis_rois') return !/\b(how many|count|number of)\b/.test(said);
  if (name === 'fidelis_macro') return false;
  if (name === 'fidelis_object_stats') return /\b(intens|bright|circular|round|shape|largest|smallest|biggest|total)/.test(said);
  return false;
}
const lengthWords = (unit: string) => /^(?:microns?|[µμu]m)$/i.test(unit) ? 'microns' : unit;
/** "What am I looking at?" from the live document metadata alone: instant, and only what Fidelis reports (no guessed biology). */
export function describeDocument(context: unknown): string | undefined {
  const c = context as FidelisContext | undefined; const doc = c?.document; if (!doc) return undefined;
  const width = finite(doc.width), height = finite(doc.height), depth = finite(doc.bitDepth); if (width === undefined || height === undefined) return undefined;
  const channels = finite(doc.channels) ?? 1, slices = finite(doc.slices) ?? 1, frames = finite(doc.frames) ?? 1;
  const rgb = depth === 24;
  const parts = [`${friendlyName(String(doc.title ?? 'This image'), doc.path)}: ${width} by ${height}${rgb ? ', RGB colour' : depth ? `, ${depth}-bit` : ''}`];
  if (channels > 1) parts.push(`${channels} channels`); else if (!rgb) parts.push('one channel');
  if (frames > 1) parts.push(`a time-lapse of ${frames} frames`);
  if (slices > 1) parts.push(`a ${slices}-slice z-stack`);
  const unit = text(doc.unit) ?? 'pixel', px = finite(doc.pixelWidth), py = finite(doc.pixelHeight);
  if (px !== undefined && !/^pixels?$/i.test(unit)) {
    const spoken = (value: number) => { const rounded = new Intl.NumberFormat('en-US', { maximumSignificantDigits: 3, useGrouping: false }).format(value); return `${Number(rounded) === value ? '' : 'about '}${rounded}`; };
    parts.push(py === undefined || px === py ? `${spoken(px)} ${lengthWords(unit)} per pixel` : `${spoken(px)} by ${spoken(py)} ${lengthWords(unit)} per pixel (horizontal by vertical)`);
  }
  const objects = finite(c?.counts?.rois);
  if (objects) parts.push(`${objects} objects outlined`);
  const sentence = `${parts.join('; ')}.`;
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
}
const confirmWords = /^(?:(?:yes|yeah|yep|yup|sure)(?: please)?(?: (?:go ahead|do it|run it|go|confirm|proceed))?(?: (?:go ahead|do it|run it))?(?: thanks| thank you)?|(?:go ahead|go for it|do it|run it|proceed)(?: (?:go ahead|do it|run it))?(?: please)?|confirm(?: it| that| the plan)?|confirmed|confirm and run)$/;
const cancelWords = /^(?:no|nope|nah|no thanks|no thank you|cancel(?: it| that| this| the plan)?|no cancel(?: it| that)?|stop(?: that| it)?|don't(?: run it| do it| do that)?|do not run it|no don't|never ?mind|forget it)$/;
/** Whole-utterance instant routes: confirm or cancel a pending plan, open, describe, zoom, count, brightness, find the nuclei, slices, undo and redo. */
/** Ends in a question mark (cleanUtterance drops it): "Yes?" asks, it does not answer. */
const isQuestion = (value: string) => /\?["'\u201d\u2019)\s]*$/.test(value);
/** Typographic apostrophes and quotes as ASCII, so "don’t" reads like "don't" (cleanUtterance keeps only ASCII ones). */
const asciiApostrophes = (value: string) => value.replace(/[\u2018\u2019\u02bc\u0060\u00b4]/g, "'");
function fastRoute(spokenText: string, context: unknown): FastRoute | undefined {
  const said = cleanUtterance(asciiApostrophes(spokenText));
  const state = context as FidelisContext | undefined;
  // A plan waiting for confirmation is answered by "yes" or "no" before anything else (docs/FIDELIS-PROTOCOL.md, Confirmation).
  const pending = record(state?.pending);
  if (pending) {
    const planId = text(pending.planId);
    if (confirmWords.test(said) && !isQuestion(spokenText)) return { tool: 'fidelis_confirm', arguments: planId ? { planId } : {}, reply: 'Running it.' };
    if (cancelWords.test(said)) return { tool: 'fidelis_cancel_plan', arguments: planId ? { planId } : {}, reply: 'Cancelled; nothing ran.' };
  }
  // A plan still running in Fidelis is stopped at one of its original dialogs: "cancel" presses that dialog's Cancel.
  const running = record((state as { running?: unknown } | undefined)?.running);
  if (!pending && running && cancelWords.test(said)) return { tool: 'fidelis_cancel_plan', arguments: {}, reply: 'Cancelling.' };
  // "Open the hela cells image": the host resolves imageQuery with fidelis_find_images; an already-open document is focused instead.
  const raw = spokenText.toLowerCase().replace(/'s\b/g, 's').replace(/[.!?,]/g, ' ').replace(/\s+/g, ' ').trim();
  const opening = raw.match(/^(?:(?:hey )?(?:fidelis|fiji|penny)\s+)?(?:please |can you |could you )?(?:open|opened|load|bring up|pull up|switch to|switch back to|go to|go back to|bring back)(?: up)? (?:the |my |that )?(.+?)(?: (?:image|picture|file|movie|video|stack|slide|sample))?(?: please)?$/);
  if (opening && !/\b(and|then|also)\b/.test(opening[1]) && !/^(?:a|an|new)\b/.test(opening[1]) && opening[1].length >= 2) {
    const words = queryWords(opening[1]);
    const open = Array.isArray(state?.documents) ? state!.documents : [];
    const squash = (value: string) => value.toLowerCase().replace(/[^a-z0-9]+/g, '');
    const canonical = (value: string) => squash(queryWords(value).join(' '));
    const requested = canonical(opening[1]);
    const exactTitle = open.find(entry => typeof entry.title === 'string' && canonical(entry.title) === requested);
    const exactStem = open.find(entry => typeof entry.title === 'string' && canonical(entry.title.replace(/\.(?:ome\.)?[^.]+$/i, '')) === requested);
    const hit = exactTitle ?? exactStem ?? (words.length ? open.find(entry => typeof entry.title === 'string' && imageNameMatches(entry.title, opening[1])
      && words.filter(word => /\d$/.test(word)).every(word => new RegExp(`${squash(word)}(?!\\d)`).test(squash(entry.title as string)))) : undefined);
    if (hit && typeof hit.title === 'string') {
      const name = friendlyName(hit.title);
      if (hit.focused === true) return { tool: 'fidelis_context', arguments: {}, reply: `${name.charAt(0).toUpperCase()}${name.slice(1)} is already open.` };
      if (typeof hit.id === 'string') return { tool: 'fidelis_activate_image', arguments: { id: hit.id }, reply: `Switched to ${name}.` };
    }
    // "Open the blobs image" opens the blobs.gif on disk (Penny's data folder) instantly, like any other image; only
    // when no file matches does the host ask, and ImageJ's built-in sample (File > Open Samples) stays a catalogue command.
    return { tool: 'fidelis_open_path', arguments: {}, reply: 'Opening it.', imageQuery: opening[1] };
  }
  const doc = state?.document;
  if (!doc) return undefined;
  if (/^(?:so )*(?:what am i looking at|what is this(?: image)?|what's this(?: image)?|describe (?:this|the) image|tell me about (?:this|the) image|what (?:kind of )?image is this)$/.test(said)) {
    const described = describeDocument(context); if (described) return { tool: 'fidelis_context', arguments: {}, reply: described };
  }
  // A fast action is the whole request, never just the first half of a spoken chain.
  if (continuesGenerally(said.replace(/^(?:and|also)\s+/, ''))) return undefined;
  const zoom = said.match(/^zoom (in|out)(?: (a (?:little|bit)|slightly|a lot|way (?:in|out)|more))?(?: (?:on|to|into) (?:the |this )?(?:image|picture|it|here))?$/);
  if (zoom) return { tool: 'fidelis_zoom', arguments: { direction: zoom[1] }, reply: zoom[1] === 'in' ? 'Zooming in.' : 'Zooming out.' };
  if (/^(?:zoom to fit|fit (?:to|the) (?:window|screen|view)|fit to window)$/.test(said)) return { tool: 'fidelis_zoom', arguments: { fit: true }, reply: 'Fitting to the window.' };
  if (/^zoom (?:in )?(?:to|on) (?:the |this |my )?(?:selection|selected region|roi)$/.test(said)) return { tool: 'fidelis_zoom', arguments: { selection: true }, reply: 'Zooming to the selection.' };
  // Threshold strictness re-segments with a stricter or looser method; the bridge replaces the previous segmentation
  // from the original pixels (segment_cells `replace`, docs/FIDELIS-PROTOCOL.md).
  const strictness = said.match(/^(?:(?:now |please )?(?:make (?:it|that|the threshold|the segmentation) )?)(?:a (?:bit|little) )?(stricter|tighter|more strict|more conservative|less sensitive|looser|more sensitive|less strict|more aggressive)(?: threshold)?$/);
  if (strictness) {
    const stricter = /strict(?!.*less)|tight|conservative|less sensitive/.test(strictness[1]) && !/less strict/.test(strictness[1]);
    return { tool: 'fidelis_segment_cells', arguments: { method: stricter ? 'Li' : 'Huang' }, reply: stricter ? 'Segmenting with a stricter threshold.' : 'Segmenting with a looser threshold.' };
  }
  const objects = finite(state?.counts?.rois);
  // "How many?" reads the ROI list (the segmentation output); re-segmenting would discard the researcher's corrections.
  if (/^(?:so |and )?how many(?: (?:cells|objects|particles|nuclei|blobs|of them))?(?: are)?(?: there| left| now| remaining| do (?:we|i) have| did (?:you|that) (?:find|get|keep))?(?: now)?$/.test(said))
    return objects !== undefined ? { tool: 'fidelis_rois', arguments: { limit: 1 }, reply: 'Counting.' } : { tool: 'fidelis_object_stats', arguments: {}, reply: 'Counting.' };
  if (objects && /^(?:show(?: me)?|highlight|outline|point out) (?:all )?(?:of )?(?:the )?(?:nuclei|cells|objects|detections)(?: again| here)?$/.test(said)) return { tool: 'fidelis_highlight', arguments: { rois: 'all', label: `${objects} objects` }, reply: 'Here they are.' };
  // No instant "mean intensity of the nuclei": segment_cells leaves the focused document as its mask, so measuring the ROI
  // list right after it would read mask values (0/255), not intensities. The model handles intensity requests.
  // Brightness is display-only: move the display range from the live values, never filter the pixels.
  const range = Array.isArray(doc.displayRange) ? doc.displayRange : [doc.display?.min, doc.display?.max];
  if (range.length === 2 && range.every(value => finite(value) !== undefined)) {
    const [low, high] = range as [number, number], span = Math.max(1e-6, high - low);
    if (/^(?:make (?:it|the image|this) |a (?:bit|little) )*(?:a (?:bit|little) )?(brighter|lighter)$/.test(said)) return { tool: 'fidelis_display', arguments: { min: low, max: low + span * 0.7 }, reply: 'Brighter.' };
    if (/^(?:make (?:it|the image|this) |a (?:bit|little) )*(?:a (?:bit|little) )?darker$/.test(said)) return { tool: 'fidelis_display', arguments: { min: low, max: low + span * 1.4 }, reply: 'Darker.' };
  }
  if (/^(?:(?:auto|fix|improve|boost|enhance|more) (?:the )?contrast|auto(?:matic)? (?:brightness|contrast)|make (?:it|this) (?:clearer|easier to see|pop))$/.test(said)) return { tool: 'fidelis_display', arguments: { auto: true }, reply: 'Auto contrast.' };
  // "Find all the nuclei" is the whole request: the bridge runs its segmentation plan on the focused document (a repeat
  // replaces the previous segmentation from the original pixels).
  if (/^(?:now )*(?:find|segment|detect|identify|outline|count|show me|highlight) (?:all )?(?:of )?(?:the )?(?:nuclei|nucleus|cells|cell nuclei)(?: here)?$/.test(said)) return { tool: 'fidelis_segment_cells', arguments: {}, reply: 'Finding the nuclei.' };
  // "Convert it to a mask (with a black background)" is Process > Binary > Convert to Mask on the focused document, never a
  // segmentation: the polarity asked for is passed as `black`; a polarity that differs from Fidelis's BlackBackground
  // preference makes the plan explicit (the bridge's value-aware rule), so it waits for "yes".
  const mask = said.match(/^(?:(?:please |now )?(?:convert|turn|make|change)(?: (?:it|this|that|the image|this image))? (?:in)?to (?:a )?(?:binary )?mask|(?:make|create) (?:a )?(?:binary )?mask(?: of (?:it|this|the image))?)(?: with (?:a )?(black|white|dark|light) background)?$/);
  if (mask) {
    const params: Record<string, unknown> = {};
    if (mask[1]) params.black = mask[1] === 'black' || mask[1] === 'dark';
    return { tool: 'fidelis_command', arguments: { commandId: 'ij1:Process>Binary>Convert to Mask', params }, reply: 'Converting to a mask.' };
  }
  if (/^(?:show (?:me )?|what is |what's |give me |export |save |print )?(?:the )?(?:workflow|macro|script|steps so far|recorded (?:macro|steps))(?: so far| for this| please)?$/.test(said)) return { tool: 'fidelis_macro', arguments: {}, reply: 'Here is the macro.' };
  // "Run this macro": the session's own macro (History → macro) is the code; the bridge always holds it for "yes".
  if (/^(?:run|replay|execute|play|rerun|re run) (?:this|the|that|my|the whole|this whole) (?:macro|workflow|script|recorded (?:macro|steps))(?: again| now)?$/.test(said)) return { tool: 'fidelis_run_macro', arguments: {}, reply: 'Preparing the macro.', macroFromHistory: true };
  const slice = said.match(/^(next|previous|prev) slice$/);
  if (slice) {
    const slices = finite(doc.slices), current = finite(doc.position?.slice);
    if (slices === undefined || current === undefined || slices < 2) return undefined;
    const next = slice[1] === 'next' ? current + 1 : current - 1;
    if (next < 1 || next > slices) return undefined;
    return { tool: 'fidelis_stack', arguments: { slice: next }, reply: slice[1] === 'next' ? 'Next slice.' : 'Previous slice.' };
  }
  if (/^undo(?: that| it| the last step)?$|^go back$|^take that back$/.test(said)) return { tool: 'fidelis_undo', arguments: {}, reply: 'Undoing.' };
  // cleanUtterance drops digits, so "undo the last 3 steps" is read from the raw words.
  const several = raw.replace(/^(?:(?:hey )?(?:penny|fidelis) |please |okay |ok |so )+/, '').match(/^(?:undo|go back) (?:the )?(?:last )?(two|three|four|five|six|seven|eight|nine|ten|\d{1,2}) steps$/);
  if (several) {
    const count = Number(several[1]) || ['two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten'].indexOf(several[1]) + 2;
    if (count >= 1 && count <= 20) return { tool: 'fidelis_undo', arguments: { steps: count }, reply: 'Undoing.' };
  }
  if (/^redo(?: that| it)?$|^go forward$|^put it back$/.test(said)) return { tool: 'fidelis_redo', arguments: {}, reply: 'Redoing.' };
  return undefined;
}
/** Segmentation tuning the bridge picks itself; the model tends to fill every optional field. Kept only when the researcher mentions it. */
const expertArguments: Record<string, string[]> = { fidelis_segment_cells: ['channel', 'method', 'minSize', 'watershed'] };
const mentionedBy: Record<string, RegExp> = {
  channel: /\b(channel|red|green|blue|dapi|hoechst|gfp|nuclear|c\d)\b/, minSize: /\b(size|smaller|bigger|larger|area|pixels?|microns?)\b/, watershed: /\bwatershed\b/,
  method: /\b(otsu|li|huang|triangle|default|yen|moments|maxentropy|isodata|mean|minimum|intermodes|renyi|shanbhag|percentile|strict\w*|loose\w*|sensitiv\w*|threshold)\b/,
};
function modelArguments(name: string, args: Record<string, unknown>, utterance: string): Record<string, unknown> {
  const expert = expertArguments[name]; if (!expert) return args;
  const said = utterance.toLowerCase(), numbers = new Set((said.match(/\d+(?:\.\d+)?/g) ?? []).map(Number));
  const kept: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(args)) {
    if (!expert.includes(key)) { kept[key] = value; continue; }
    const mentioned = (mentionedBy[key]?.test(said) ?? said.includes(key.toLowerCase())) || typeof value === 'number' && numbers.has(value) || typeof value === 'string' && value.length > 1 && said.includes(value.toLowerCase());
    if (mentioned) kept[key] = value;
  }
  return kept;
}
/** The researcher's own latest words are, as a whole, an assent to the plan ("yes", "yes please", "okay, go ahead, run
 * it", "confirm"): the same grammar as the instant "yes" route. Anything more ("yes, if…", "confirm whether…") is not. */
export function researcherAssents(utterance: string | undefined): boolean {
  // A question is never an assent: "Yes?" is also the voice transport's prompt for a bare "Hey Penny".
  return !isQuestion(utterance ?? '') && confirmWords.test(cleanUtterance(asciiApostrophes(utterance ?? '')));
}
/** `fidelis_confirm` runs an explicit plan, which only the researcher may approve (docs/FIDELIS-PROTOCOL.md,
 * Confirmation): it is refused unless the latest words of the conversation task are the researcher's assent, so neither
 * the model on its own nor a direct tool client can confirm. The assent belongs to the plan that was pending when those
 * words arrived (the request's first observation): a plan that replaced it since needs a fresh "yes", and the planId
 * sent is that plan's, so the bridge never runs another one. */
async function prepareAction(input: PrepareInput) {
  if (input.name !== 'fidelis_confirm') return;
  if (!researcherAssents(conversationEnvelope().utterance)) throw new Error('Only the researcher can confirm this plan: say "yes" or press Confirm in Fidelis.');
  const pending = text(record((input.context as FidelisContext).pending)?.planId);
  if (!pending) throw new Error('Nothing is waiting for confirmation.');
  const assented = text(record((observedContext() as FidelisContext | undefined)?.pending)?.planId);
  if (assented !== pending) throw new Error('The plan changed after you said yes; check the new plan and say yes again.');
  if (input.args.planId === undefined) input.args.planId = pending;
  else if (input.args.planId !== pending) throw new Error('That plan is no longer the one waiting; check the plan card in Fidelis.');
}

export const fidelis: AppProfile = {
  id: 'fidelis', displayName: 'Fidelis', bundleId: FIDELIS_BUNDLE_ID, bridgePort: 49558, toolPrefix: 'fidelis',
  tools: fidelisTools, instructions: fidelisInstructions,
  backendNotes: 'Relative view requests use fidelis_zoom: direction in/out for "zoom in/out", fit true for "fit to window", selection true for "zoom to the selection"; "next/previous slice" uses fidelis_stack with a 1-based slice number. A data result with status awaiting_confirmation has not run: say in one sentence that it waits for Confirm or "yes", and stop.',
  skillDir: 'skills/fidelis', knowledgeSources: ['skills/fidelis', '.local/upstream/imagej-docs'], apiSource: false,
  // Fidelis is built by its own tools; there is no separate bridge artifact or build manifest (bridgeIdentity 'operations').
  launcherScript: './script/run-fidelis.sh', bridgeJar: 'fidelis-app/build/fidelis-app.jar', buildManifest: '', buildScript: 'python3 -I fidelis-app/tools/build.py --no-tests',
  doctorArtifacts: [['fidelis-app/build/fidelis-app.jar', 'Fidelis build'], ['.local/fiji/Fiji.app/jars', 'Fiji runtime for Fidelis']],
  bridgeIdentity: 'operations', conversationEnvelope: true, bindInstantRoutes: true, controlRoutes: true,
  fastRoute, acknowledgmentFor, localSentenceFor, requestContinuesAfter, silentFinishTools,
  pausesRequest: (_name, result) => { const plan = awaitingConfirmation(result); return plan ? awaitingSentence(plan) : undefined; },
  // A waiting dialog, or a confirmed plan whose result has no local sentence (a measured mean on a possible mask), is
  // finished by the model with the real result instead of a bare "Done.".
  outcomeOf: (_name, result) => fidelisOutcome(result),
  needsModel: (name, result, context) => dialogWaiting(result) || name === 'fidelis_confirm' && localSentenceFor(name, {}, result, context) === undefined,
  longRunningTools: new Set(['fidelis_segment_cells', 'fidelis_analyze_particles', 'fidelis_command', 'fidelis_confirm', 'fidelis_open_path']),
  prepareAction, modelArguments,
  resultForModel: (name, result) => name === 'fidelis_macro' ? macroForModel(result) : result,
};
