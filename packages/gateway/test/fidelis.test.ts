import { test } from 'node:test';
import assert from 'node:assert/strict';
import AjvModule from 'ajv';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';
import { startServer } from '../src/server.js';
import { profiles, selectApp, profileForTool, appIds } from '../src/apps/index.js';
import { fidelisTools, FIDELIS_BUNDLE_ID } from '../src/apps/fidelis-tools.js';
import { fidelisInstructions } from '../src/apps/fidelis-instructions.js';
import { fijiInstructions } from '../src/apps/fiji-instructions.js';
import { awaitingSentence, describeDocument, dialogWaiting, researcherAssents, macroForModel } from '../src/apps/fidelis.js';
import { bridgeReadiness, bridgeReady, requiredOperationsFor } from '../src/bridge-readiness.js';
import { BridgeAdapter, FidelisAdapter } from '../src/bridge.js';
import { validateTool } from '../src/tools.js';
import { Runtime } from '../src/runtime.js';
import { SpeechSession, addressed } from '../src/speech-session.js';
import { Knowledge } from '../src/knowledge.js';
import { TextConversation } from '../src/text-session.js';
import { toolTask, toolUtterance, conversationEnvelope } from '../src/tool-request.js';
import type { BackendClient, BackendRequest } from '../src/backend.js';

const { fidelis, fiji, qupath } = profiles;
const repository = new URL('../../../', import.meta.url);
const riskOfKind: Record<string, 'read' | 'view' | 'data' | 'code'> = { read: 'read', view: 'view', data: 'data', code: 'code', session: 'data' };
/** Operations the integrator announced on 17 Oct 2026 for the bridge (original Fiji dialogs) before they reach this branch's contract table. */
const announced: Record<string, string> = { dialog: 'read', dialog_action: 'data' };
/** Every operation row of docs/FIDELIS-PROTOCOL.md with its kind (`undo` / `redo` share a row). */
async function contractOperations(): Promise<Record<string, string>> {
  const protocol = await readFile(new URL('docs/FIDELIS-PROTOCOL.md', repository), 'utf8');
  const operations: Record<string, string> = {};
  for (const line of protocol.split('\n')) {
    const row = line.match(/^\| ((?:`[a-z_]+`(?: \/ )?)+) \| (read|view|data|code|session) \|/);
    if (row) for (const name of row[1].match(/`([a-z_]+)`/g)!) operations[name.slice(1, -1)] = row[2];
  }
  return operations;
}
const completed = (result: unknown) => ({ status: 'completed', error: undefined, actionId: 'a1', result });

test('LUMA_APP=fidelis selects the Fidelis profile on its own port, launcher and knowledge set', async () => {
  const empty = join(tmpdir(), 'luma-no-app-file');
  assert.equal(selectApp('fidelis', empty), 'fidelis');
  assert.ok(appIds.includes('fidelis'));
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-app-'));
  try {
    await mkdir(join(root, '.local/state'), { recursive: true });
    await writeFile(join(root, '.local/state/app'), 'fidelis\n'); assert.equal(selectApp('', root), 'fidelis', 'the launcher-written state file selects Fidelis');
  } finally { await rm(root, { recursive: true, force: true }); }
  assert.equal(fidelis.id, 'fidelis'); assert.equal(fidelis.displayName, 'Fidelis'); assert.equal(fidelis.bridgePort, 49558); assert.equal(fidelis.toolPrefix, 'fidelis');
  assert.equal(fidelis.launcherScript, './script/run-fidelis.sh'); assert.equal(fidelis.bundleId, FIDELIS_BUNDLE_ID);
  assert.equal(fidelis.bridgeIdentity, 'operations'); assert.equal(fidelis.conversationEnvelope, true);
  assert.equal(fiji.conversationEnvelope, undefined, 'Fiji keeps its original /execute body'); assert.equal(qupath.conversationEnvelope, undefined);
  assert.equal(profileForTool('fidelis_measure'), fidelis); assert.equal(profileForTool('fiji_measure'), fiji);
  assert.equal(new FidelisAdapter('/tmp').port, 49558);
  assert.deepEqual(fidelis.knowledgeSources, ['skills/fidelis', '.local/upstream/imagej-docs']); assert.equal(fidelis.skillDir, 'skills/fidelis');
  const skill = await readFile(new URL(`${fidelis.skillDir}/SKILL.md`, repository), 'utf8');
  assert.match(skill, /fidelis_commands/); assert.match(skill, /awaiting_confirmation/); assert.doesNotMatch(skill, /\bfiji_[a-z]/, 'the Fidelis guide names only fidelis_* tools');
  const knowledge = new Knowledge(fidelis); await knowledge.load(new URL('.', repository).pathname);
  assert.ok(knowledge.search('confirmation').results.some(result => result.source === 'skills/fidelis/SKILL.md'));
  const script = await readFile(new URL('script/run-fidelis.sh', repository), 'utf8');
  for (const phrase of ['python3 -I fidelis-app/tools/build.py', 'python3 -I fidelis-app/tools/run.py --isolated-home', '--prop "luma.root=$PWD"', 'printf fidelis > .local/state/app', '.local/fidelis.log', 'PORT=49558', 'already running'])
    assert.ok(script.includes(phrase), `run-fidelis.sh: ${phrase}`);
  const run = await readFile(new URL('fidelis-app/tools/run.py', repository), 'utf8');
  assert.match(run, /"--prop"/, 'run.py passes JVM properties through');
});

test('Fidelis tool schemas are valid, prefixed and cover exactly the contract operations with their risks', async () => {
  const Ajv = AjvModule.default ?? AjvModule; const ajv = new Ajv({ allErrors: true, strict: true });
  const names = new Set<string>();
  for (const tool of fidelisTools) {
    assert.match(tool.name, /^fidelis_[a-z_]+$/); assert.ok(!names.has(tool.name), `${tool.name} is unique`); names.add(tool.name);
    assert.ok(tool.description.length > 20 && tool.description.length <= 400, `${tool.name} has a short description (${tool.description.length})`);
    assert.doesNotMatch(tool.description, /human approval|after approval|requires approval|researcher reviews/i, `${tool.name} must not promise a review step`);
    assert.equal(typeof ajv.compile(tool.inputSchema), 'function');
    assert.equal((tool.inputSchema as { additionalProperties?: unknown }).additionalProperties, false);
  }
  const contract = { ...announced, ...await contractOperations() };
  assert.ok(Object.keys(contract).length >= 30, 'the contract table was parsed (27 rows, undo and redo share one) and the announced dialog operations added');
  const bridged = fidelisTools.filter(tool => tool.operation);
  assert.deepEqual(bridged.map(tool => tool.operation).sort(), Object.keys(contract).sort(), 'exactly the contract operations reach the bridge');
  for (const tool of bridged) {
    assert.equal(tool.name, `fidelis_${tool.operation}`, 'tool names are fidelis_<operation>');
    assert.equal(tool.risk, riskOfKind[contract[tool.operation!]], `${tool.operation} (${contract[tool.operation!]}) risk`);
  }
  assert.equal(validateTool('fidelis_open_path', { path: '/x.tif' }, fidelis).risk, 'data', 'session operations take the data decision path');
  assert.deepEqual(requiredOperationsFor(fidelis), Object.keys(contract).sort());
  assert.ok(requiredOperationsFor(fidelis).length <= 64);
  for (const local of ['knowledge', 'find_images', 'action_status']) assert.ok(fidelisTools.some(tool => tool.name === `fidelis_${local}` && !tool.operation && tool.risk === 'read'), local);
  // Argument contracts from the protocol table.
  const ok = (name: string, args: Record<string, unknown>) => assert.equal(validateTool(name, args, fidelis).name, name);
  const bad = (name: string, args: Record<string, unknown>) => assert.throws(() => validateTool(name, args, fidelis), /Invalid arguments/, `${name} ${JSON.stringify(args)}`);
  ok('fidelis_context', {}); bad('fidelis_context', { approved: true });
  ok('fidelis_activate_image', { id: 'doc-3' }); ok('fidelis_activate_image', { title: 'blobs.gif' }); bad('fidelis_activate_image', { id: -3 }); bad('fidelis_activate_image', { id: '3' });
  ok('fidelis_commands', { query: 'gaussian', limit: 5 }); bad('fidelis_commands', {});
  ok('fidelis_command', { commandId: 'ij1:Process>Filters>Gaussian Blur...', params: { sigma: 2, scaled: true, stack: 'all' } }); bad('fidelis_command', { params: { sigma: 2 } }); bad('fidelis_command', { commandId: 'Gaussian Blur...' });
  bad('fidelis_command', { commandId: 'ij1:Process>Filters>Gaussian Blur...', params: { nested: { a: 1 } } });
  ok('fidelis_segment_cells', {}); ok('fidelis_segment_cells', { channel: 1, method: 'Otsu', dark: true, minSize: 0, maxSize: 900, pixel: true, exclude: false, watershed: true, gaussian: true, sigma: 1, replace: true });
  bad('fidelis_segment_cells', { channel: 0 }); bad('fidelis_segment_cells', { keepArtifacts: true });
  ok('fidelis_measure', { measurements: ['area', 'mean', 'min', 'max', 'integrated', 'feret'], scope: 'rois' }); ok('fidelis_measure', { scope: 'all' }); bad('fidelis_measure', { scope: 'manager' });
  ok('fidelis_measure', { scope: 'rois', measurements: ['mean'], channel: 2 }); bad('fidelis_measure', { scope: 'rois', channel: 0 }); bad('fidelis_measure', { scope: 'rois', channel: 'blue' }); bad('fidelis_measure', { measurements: ['maximum'], scope: 'roi' });
  ok('fidelis_dialog', {}); ok('fidelis_dialog_action', { dialogId: 'dlg-7' }); ok('fidelis_dialog_action', { dialogId: 'dlg-7', fields: { radius: 3, f1: false }, press: 'OK' }); ok('fidelis_dialog_action', { dialogId: 'dlg-7', press: '' });
  bad('fidelis_dialog_action', {}); bad('fidelis_dialog_action', { fields: { radius: 3 }, press: 'OK' }); bad('fidelis_dialog_action', { dialogId: 'dlg-7', fields: { nested: { a: 1 } } }); bad('fidelis_dialog_action', { dialogId: 'dlg-7', button: 'OK' });
  ok('fidelis_undo', { steps: 9 }); bad('fidelis_undo', { steps: 21 }); ok('fidelis_cancel_plan', { planId: 'plan-7' }); ok('fidelis_highlight', { rois: 'active', limit: 10 });
  ok('fidelis_activate_image', { imageId: -3 }); ok('fidelis_rois', { limit: 5, offset: 10 }); ok('fidelis_display', { auto: true, channel: 2 });
  ok('fidelis_filter', { name: 'unsharp', radius: 1, mask: 0.6 }); ok('fidelis_filter', { name: 'enhance_contrast', saturated: 0.35, normalize: true, equalize: false, stack: true });
  ok('fidelis_analyze_particles', { minSize: 20, maxSize: 900, minCircularity: 0.5, addToManager: true });
  ok('fidelis_threshold', { method: 'Otsu', dark: true }); ok('fidelis_threshold', { lower: 40, upper: 255 });
  ok('fidelis_analyze_particles', { size: '50-Infinity', circularity: '0.50-1.00', exclude: true, include: true, add: true, display: true, clear: true, show: 'Outlines' });
  bad('fidelis_analyze_particles', { size: 'big' }); bad('fidelis_analyze_particles', { minCircularity: 2 });
  ok('fidelis_filter', { name: 'gaussian', sigma: 2 }); ok('fidelis_filter', { name: 'subtract_background', rolling: 50 }); bad('fidelis_filter', { name: 'blur' }); bad('fidelis_filter', {});
  ok('fidelis_convert', { to: '8-bit' }); bad('fidelis_convert', { to: 'Composite' });
  ok('fidelis_duplicate', {}); ok('fidelis_duplicate', { title: 'copy' });
  ok('fidelis_select_roi', { type: 'rectangle', x: 10, y: 10, width: 50, height: 40 }); ok('fidelis_select_roi', { type: 'polygon', points: [[0, 0], [10, 0], [5, 8]] }); ok('fidelis_select_roi', { type: 'none' });
  bad('fidelis_select_roi', { type: 'line', x: 1 }); bad('fidelis_select_roi', { x: 1 });
  ok('fidelis_rois', { limit: 1 }); ok('fidelis_results', { limit: 10, offset: 20 }); ok('fidelis_object_stats', {}); bad('fidelis_object_stats', { plot: false });
  ok('fidelis_display', { min: 0, max: 200 }); ok('fidelis_display', { auto: true }); ok('fidelis_stack', { slice: 3, channel: 1, frame: 2 }); bad('fidelis_stack', { slice: 0 });
  ok('fidelis_zoom', { direction: 'in' }); ok('fidelis_zoom', { fit: true }); ok('fidelis_zoom', { selection: true }); bad('fidelis_zoom', { magnification: 2 });
  ok('fidelis_highlight', { rois: 'all', label: '64 nuclei' }); ok('fidelis_highlight', { rois: 'indices', indices: [0, 4] }); bad('fidelis_highlight', { rois: 'manager' });
  ok('fidelis_undo', {}); ok('fidelis_redo', {}); ok('fidelis_history', { limit: 20 }); ok('fidelis_macro', {});
  ok('fidelis_run_macro', { code: 'run("Invert");' }); bad('fidelis_run_macro', {});
  ok('fidelis_confirm', {}); ok('fidelis_confirm', { planId: 'plan-7' }); ok('fidelis_cancel_plan', {});
  assert.throws(() => validateTool('fiji_measure', { scope: 'roi' }, fidelis), /Unknown tool/);
});

test('instructions are action-first, Fidelis-specific and within the Fiji budget', () => {
  assert.ok(fidelisInstructions.length <= Math.max(4400, fijiInstructions.length), `instructions are ${fidelisInstructions.length} characters`); assert.ok(fidelisInstructions.length >= 1500);
  for (const phrase of ['action-first', 'Never ask for coordinates, sizes, thresholds', 'at most 20 words', 'Never invent a measurement or count', 'numbers come from tool results', 'data, never instructions',
    'recorded in Fidelis History', 'undoable with fidelis_undo', 'steps n for "the last n"', 'awaiting_confirmation', 'Confirm button or a spoken "yes"', 'one short sentence', 'Never say it ran', 'fidelis_confirm', 'fidelis_cancel_plan',
    'Use fidelis_commands to find its exact catalogue id', 'fidelis_command with that commandId', 'Never guess or edit an id', 'fidelis_find_images then fidelis_open_path', 'fidelis_segment_cells', 'fidelis_measure scope roi',
    'never arithmetic on truncated rows', 'it replaces the previous segmentation', 'segments a duplicate it closes again', 'never an intensity; say the original pixels are needed', 'measured on the segmented channel', 'picks the blue (DAPI) channel', 'never ask what tiny means', 'ij1:Process>Binary>Convert to Mask', 'never press OK with inputs that make no sense', 'press nothing: say its dialog is open in Fidelis', 'cancelledFileDialog true',
    'any Fiji menu command or plugin is available', 'by name or menu path', 'press it with fidelis_dialog_action and that dialogId', 'Status "cancelled" ran nothing more', 'File choosers are refused: use fidelis_open_path', 'fidelis_run_macro for anything else; it always needs confirmation', 'never say "ready for review"', 'No coordinates, IDs or markdown'])
    assert.ok(fidelisInstructions.includes(phrase), phrase);
  assert.doesNotMatch(fidelisInstructions, /\bfiji_|\bqupath_|approval_required|wait for approval/i);
  for (const tool of fidelisInstructions.match(/\bfidelis_[a-z_]+/g)!) assert.ok(fidelisTools.some(defined => defined.name === tool), `${tool} exists`);
  assert.equal(fidelis.instructions, fidelisInstructions);
  assert.match(fidelis.backendNotes, /awaiting_confirmation has not run/);
});

const blobs = { contextKey: 'k1', application: 'Fidelis', document: { id: 'doc-1', imageId: -2, title: 'blobs.gif', width: 256, height: 254, channels: 1, slices: 1, frames: 1, bitDepth: 8, unit: 'pixel', pixelWidth: 1, pixelHeight: 1, path: '/data/blobs.gif', changed: false },
  documents: [{ id: 'doc-1', title: 'blobs.gif', focused: true }, { id: 'doc-2', title: 'hela-cells.tif', focused: false }], roi: null, counts: { rois: 0, resultsRows: 0, history: 2 }, lastStep: null, viewer: { magnification: 1 }, pending: null };

test('fast routes: nuclei, zoom, undo/redo, counts, brightness, open, describe, and confirm or cancel only with a pending plan', () => {
  const route = (text: string, context: unknown = blobs) => fidelis.fastRoute(text, context);
  assert.deepEqual(route('Find the nuclei.'), { tool: 'fidelis_segment_cells', arguments: {}, reply: 'Finding the nuclei.' });
  assert.deepEqual(route('Hey Penny, segment all the cells'), { tool: 'fidelis_segment_cells', arguments: {}, reply: 'Finding the nuclei.' });
  assert.deepEqual(route('Zoom in a little.'), { tool: 'fidelis_zoom', arguments: { direction: 'in' }, reply: 'Zooming in.' });
  assert.deepEqual(route('Zoom in on the image.'), { tool: 'fidelis_zoom', arguments: { direction: 'in' }, reply: 'Zooming in.' });
  assert.deepEqual(route('zoom out'), { tool: 'fidelis_zoom', arguments: { direction: 'out' }, reply: 'Zooming out.' });
  assert.deepEqual(route('Fit to window'), { tool: 'fidelis_zoom', arguments: { fit: true }, reply: 'Fitting to the window.' });
  assert.deepEqual(route('zoom to the selection'), { tool: 'fidelis_zoom', arguments: { selection: true }, reply: 'Zooming to the selection.' });
  assert.deepEqual(route('Undo'), { tool: 'fidelis_undo', arguments: {}, reply: 'Undoing.' });
  assert.deepEqual(route('go back')?.tool, 'fidelis_undo');
  assert.deepEqual(route('Redo that'), { tool: 'fidelis_redo', arguments: {}, reply: 'Redoing.' });
  // The bridge replaces the previous segmentation from the original pixels, so stricter/looser is instant.
  assert.deepEqual(route('make it stricter'), { tool: 'fidelis_segment_cells', arguments: { method: 'Li' }, reply: 'Segmenting with a stricter threshold.' });
  assert.deepEqual(route('more sensitive')?.arguments, { method: 'Huang' });
  assert.deepEqual(route('undo the last three steps'), { tool: 'fidelis_undo', arguments: { steps: 3 }, reply: 'Undoing.' });
  assert.deepEqual(route('go back 2 steps')?.arguments, { steps: 2 });
  assert.equal(route('undo the last 30 steps'), undefined, 'the bridge undoes at most 20');
  assert.deepEqual(route('undo the last 9 steps')?.arguments, { steps: 9 });
  // Counts read the ROI list (the segmentation output), never re-segment.
  const segmented = { ...blobs, counts: { rois: 64, resultsRows: 64, history: 11 } };
  assert.deepEqual(route('How many nuclei are there?', segmented), { tool: 'fidelis_rois', arguments: { limit: 1 }, reply: 'Counting.' });
  assert.deepEqual(route('how many cells', blobs)?.tool, 'fidelis_rois', 'an empty ROI list is still counted');
  assert.deepEqual(route('how many are left', { ...blobs, counts: undefined })?.tool, 'fidelis_object_stats', 'without ROI counts the Results table answers');
  assert.deepEqual(route('show me the nuclei', segmented), { tool: 'fidelis_highlight', arguments: { rois: 'all', label: '64 objects' }, reply: 'Here they are.' });
  assert.deepEqual(route('find the nuclei', segmented)?.tool, 'fidelis_segment_cells', 'a repeat replaces the previous segmentation');
  for (const other of ['what is the mean intensity of the nuclei', 'mean intensity of the nuclei in the top left', 'what is the median intensity of the nuclei'])
    assert.equal(route(other, segmented), undefined, `${other}: the ROI list would be measured on the mask`);
  // Brightness is display-only and needs the live display range; auto contrast does not.
  assert.equal(route('make it brighter'), undefined, 'no display range in the context, no guessed range');
  const ranged = { ...blobs, document: { ...blobs.document, displayRange: [8, 248] } };
  assert.deepEqual(route('make it brighter', ranged), { tool: 'fidelis_display', arguments: { min: 8, max: 8 + 240 * 0.7 }, reply: 'Brighter.' });
  assert.deepEqual(route('a bit darker', { ...blobs, document: { ...blobs.document, display: { min: 0, max: 100 } } }), { tool: 'fidelis_display', arguments: { min: 0, max: 140 }, reply: 'Darker.' });
  assert.deepEqual(route('auto contrast'), { tool: 'fidelis_display', arguments: { auto: true }, reply: 'Auto contrast.' });
  // Slices need the current position, which the bridge reports when it has one.
  assert.equal(route('next slice', { ...blobs, document: { ...blobs.document, slices: 5 } }), undefined);
  assert.deepEqual(route('next slice', { ...blobs, document: { ...blobs.document, slices: 5, position: { slice: 2 } } }), { tool: 'fidelis_stack', arguments: { slice: 3 }, reply: 'Next slice.' });
  // Opening: an open document is focused instead of opened twice; otherwise the host resolves the name.
  assert.deepEqual(route('open the hela cells image'), { tool: 'fidelis_activate_image', arguments: { id: 'doc-2' }, reply: 'Switched to hela cells.' });
  assert.deepEqual(route('open blobs'), { tool: 'fidelis_context', arguments: {}, reply: 'Blobs is already open.' });
  assert.deepEqual(route('Switch to the hela cells image.'), { tool: 'fidelis_activate_image', arguments: { id: 'doc-2' }, reply: 'Switched to hela cells.' });
  assert.deepEqual(route('go back to blobs'), { tool: 'fidelis_context', arguments: {}, reply: 'Blobs is already open.' });
  assert.deepEqual(route('Open the embryos image.'), { tool: 'fidelis_open_path', arguments: {}, reply: 'Opening it.', imageQuery: 'embryos' });
  assert.deepEqual(route('open the blobs image', { ...blobs, documents: [] }), { tool: 'fidelis_open_path', arguments: {}, reply: 'Opening it.', imageQuery: 'blobs' }, 'the blobs.gif on disk opens instantly');
  assert.deepEqual(route('What am I looking at?'), { tool: 'fidelis_context', arguments: {}, reply: 'Blobs: 256 by 254, 8-bit; one channel.' });
  // Confirmation answers exist only while a plan waits.
  const pending = { ...blobs, pending: { planId: 'plan-7', reasons: ['step 1 (run_macro) is code'] } };
  for (const yes of ['Yes.', 'yes please', 'Go ahead', 'Okay, run it.', 'yeah do it', 'confirm', 'Yes, please go ahead.']) assert.deepEqual(route(yes, pending), { tool: 'fidelis_confirm', arguments: { planId: 'plan-7' }, reply: 'Running it.' }, yes);
  for (const no of ['No.', 'cancel', 'Cancel that.', 'stop that', "don't run it", 'Don\u2019t run it.', 'never mind', 'no thanks']) assert.deepEqual(route(no, pending), { tool: 'fidelis_cancel_plan', arguments: { planId: 'plan-7' }, reply: 'Cancelled; nothing ran.' }, no);
  assert.deepEqual(route('yes', { ...pending, document: null })?.tool, 'fidelis_confirm', 'a pending plan is confirmed even without a focused document');
  // A bare "Hey Penny" reaches the conversation as the prompt "Yes?": a question never confirms.
  assert.equal(addressed('Hey Penny', { wakeWord: false, attentive: false }).request, 'Yes?');
  assert.equal(route('Yes?', pending), undefined); assert.equal(researcherAssents('Yes?'), false); assert.equal(researcherAssents('Go ahead?'), false);
  // The speech gate lets the one-word assents through; chatter-prone words need a second word ("yeah, do it").
  for (const word of ['Yes.', 'Confirm.', 'Proceed.', 'No.']) assert.equal(addressed(word, { wakeWord: false, attentive: false }).accept, true, word);
  assert.equal(addressed('Sure.', { wakeWord: false, attentive: false }).accept, false); assert.equal(addressed('Yeah, do it.', { wakeWord: false, attentive: false }).accept, true);
  for (const word of ['yes', 'go ahead', 'no', 'cancel']) assert.equal(route(word), undefined, `${word}: nothing pending, the model answers`);
  for (const other of ['okay', 'yes and then measure it', 'zoom in on the cells', 'find the nuclei and measure their area', 'undo the threshold', 'fit', 'segment the cells then count them']) assert.equal(route(other, pending.document ? { ...pending, pending: null } : pending), undefined, other);
  assert.equal(route('zoom in', { ...blobs, document: null }), undefined, 'no document, no view route');
});

test('local sentences speak only real results, and a waiting plan says how many steps', () => {
  const say = (name: string, result: unknown, args: Record<string, unknown> = {}) => fidelis.localSentenceFor(name, args, result);
  const plan = { status: 'awaiting_confirmation', planId: 'plan-7', reasons: ['step 1 (run_macro) is code'], steps: [{ label: 'Run macro' }] };
  assert.equal(say('fidelis_run_macro', completed(plan), { code: 'x' }), 'Ready to run 1 step; say yes to go ahead.');
  assert.equal(say('fidelis_segment_cells', completed({ ...plan, steps: new Array(9).fill({}) })), 'Ready to run 9 steps; say yes to go ahead.');
  assert.equal(awaitingSentence({ status: 'awaiting_confirmation' }), 'Ready to run it; say yes to go ahead.');
  assert.equal(say('fidelis_segment_cells', completed({ count: 64, averageSize: 351.42, unit: 'pixel', steps: [], highlight: {} })), 'Found 64 cells, mean area 351 square pixels.');
  assert.equal(say('fidelis_segment_cells', completed({ count: 12, averageSize: 42.37, unit: 'µm' })), 'Found 12 cells, mean area 42.4 µm².');
  assert.equal(say('fidelis_analyze_particles', completed({ count: 3 })), 'Found 3 cells.');
  assert.equal(say('fidelis_segment_cells', { status: 'failed', error: 'No image open' }), undefined, 'failures go back to the model');
  assert.equal(say('fidelis_segment_cells', completed({ steps: [] })), undefined, 'no count, no sentence');
  // Intensities are spoken only for a document the bridge reports as not binary.
  const grey = { ...blobs, document: { ...blobs.document, binary: false } };
  const measured = (args: Record<string, unknown>, result: unknown, context: unknown = grey) => fidelis.localSentenceFor('fidelis_measure', args, result, context);
  assert.equal(measured({ scope: 'rois' }, completed({ rows: 64, summary: { Area: { min: 1, max: 9, mean: 4 }, Mean: { min: 10, max: 250, mean: 103.25 } }, unit: 'pixel' })), 'Mean intensity 103 over 64 regions.');
  assert.equal(measured({ scope: 'rois' }, completed({ rows: 64, summary: { Mean: { min: 255, max: 255, mean: 255 } } })), undefined, 'a uniform 255 is the mask, never an intensity');
  assert.equal(measured({ scope: 'roi' }, completed({ rows: 1, lastRow: { Area: 400, Mean: 87.5 }, summary: {} })), 'Mean intensity 87.5 in this region.');
  assert.equal(measured({ scope: 'roi' }, completed({ rows: 1, lastRow: { Mean: 87.5 } }), blobs), undefined, 'no binary flag, no local intensity');
  assert.equal(measured({ scope: 'roi' }, completed({ rows: 1, lastRow: { Mean: 87.5 } }), { ...blobs, lastStep: { label: 'Set ROI' } }), undefined, 'a later History step does not hide a mask');
  assert.equal(say('fidelis_measure', completed({ rows: 1, lastRow: { Area: 65024 }, unit: 'pixel' }), { scope: 'all', measurements: ['area'] }), 'Area 65024 square pixels in the image.');
  assert.equal(say('fidelis_measure', completed({ rows: 1 }), { scope: 'roi' }), undefined);
  const onMask = { ...blobs, document: { ...blobs.document, binary: true } };
  assert.equal(measured({ scope: 'roi' }, completed({ rows: 1, lastRow: { Mean: 87.5 } }), onMask), undefined, 'a mean measured on a segmentation mask is not spoken as an intensity');
  assert.equal(measured({ scope: 'all' }, completed({ rows: 1, lastRow: { Area: 65024 }, unit: 'pixel' }), onMask), 'Area 65024 square pixels in the image.', 'areas stay valid on a mask');
  assert.equal(say('fidelis_threshold', completed({ lower: 125, upper: 255, method: 'Otsu' })), 'Thresholded at 125–255 (Otsu).');
  assert.equal(say('fidelis_undo', completed({ step: { actionId: 'h9', label: 'Undo Gaussian Blur...', outcome: 'COMPLETED' } })), 'Undid Gaussian Blur.');
  assert.equal(say('fidelis_undo', completed({ step: { label: 'Undo' } })), 'Undone.');
  assert.equal(say('fidelis_redo', completed({ step: {} })), 'Redone.');
  assert.equal(say('fidelis_undo', completed({ step: { label: 'Undo' }, undoneActionId: 'h9', undoneActionIds: ['h9', 'h8', 'h7'] }), { steps: 3 }), 'Undid 3 steps.');
  // An original Fiji dialog waiting is not an outcome: the model fills it.
  const dialog = { status: 'dialog', dialogId: 'dlg-7', dialog: { title: 'Gaussian Blur...', kind: 'generic', fields: [{ id: 'f0', key: 'sigma', label: 'Sigma', type: 'number', value: '2.0' }], buttons: ['Cancel', 'OK'] } };
  assert.equal(dialogWaiting(completed(dialog)), true); assert.equal(say('fidelis_command', completed(dialog), { commandId: 'ij1:Process>Filters>Gaussian Blur...' }), undefined);
  assert.equal(fidelis.requestContinuesAfter('fidelis_command', 'blur it', completed(dialog)), true);
  assert.equal(say('fidelis_dialog', { dialog: dialog.dialog }), undefined); assert.equal(say('fidelis_dialog_action', completed(dialog)), undefined, 'a second dialog is still waiting');
  assert.equal(say('fidelis_dialog_action', completed({ status: 'completed', step: { label: 'Gaussian Blur...' }, steps: [{}] })), 'Ran Gaussian Blur.');
  // Cancel (or No) on the dialog ends the command: never "Ran …".
  const cancelled = { status: 'cancelled', dialog: { title: 'Variance...' }, state: 'FAILED', engineMessage: 'Macro canceled', step: { label: 'Variance...' }, completedSteps: [] };
  assert.equal(say('fidelis_dialog_action', completed(cancelled), { dialogId: 'dlg-7', press: 'Cancel' }), 'Cancelled at the Variance dialog; nothing more ran.');
  assert.equal(say('fidelis_confirm', completed(cancelled)), 'Cancelled at the Variance dialog; nothing more ran.');
  assert.equal(fidelis.outcomeOf!('fidelis_command', completed(cancelled)), 'cancelled'); assert.equal(fidelis.outcomeOf!('fidelis_command', completed(dialog)), 'dialog');
  assert.equal(fidelis.outcomeOf!('fidelis_run_macro', completed(plan)), 'awaiting_confirmation'); assert.equal(fidelis.outcomeOf!('fidelis_filter', completed({ step: {} })), undefined);
  assert.equal(say('fidelis_cancel_plan', { cancelled: true, dialogCancelled: true }), 'Cancelled the waiting dialog.');
  assert.equal(say('fidelis_command', completed({ step: { label: 'Gaussian Blur...', commandId: 'ij1:Process>Filters>Gaussian Blur...', outcome: 'COMPLETED' } }), { commandId: 'ij1:Process>Filters>Gaussian Blur...' }), 'Ran Gaussian Blur.');
  assert.equal(say('fidelis_command', completed({ step: {} }), { commandId: 'ij1:Process>Binary>Fill Holes' }), 'Ran Fill Holes.');
  assert.equal(say('fidelis_open_path', completed({ document: { id: 'doc-4', title: 'hela-cells.tif', width: 672, height: 512 }, highlight: {} })), 'Opened hela cells, 672 by 512.');
  assert.equal(say('fidelis_convert', completed({ step: {} }), { to: '8-bit' }), 'Converted to 8-bit.');
  assert.equal(say('fidelis_confirm', completed({ count: 64, averageSize: 351.42, unit: 'pixel' })), 'Found 64 cells, mean area 351 square pixels.');
  assert.equal(say('fidelis_confirm', completed({ step: { label: 'Run macro' } })), 'Ran the macro.');
  assert.equal(fidelis.localSentenceFor('fidelis_confirm', {}, completed({ rows: 2, lastRow: { Mean: 90 }, summary: { Mean: { min: 10, max: 90, mean: 50 } } }), grey), 'Mean intensity 50 over 2 regions.', 'a confirmed multi-ROI measurement reports its summary');
  assert.equal(fidelis.localSentenceFor('fidelis_confirm', {}, completed({ rows: 1, lastRow: { Mean: 90 }, summary: { Mean: { min: 90, max: 90, mean: 90 } } }), grey), 'Mean intensity 90.', 'one confirmed row names no place it cannot know');
  assert.equal(say('fidelis_confirm', completed({ rows: 1, lastRow: { Mean: 90 } })), undefined, 'no binary flag, no local intensity');
  assert.equal(say('fidelis_confirm', completed({ steps: [{}, {}] })), 'Ran 2 steps.');
  assert.equal(say('fidelis_cancel_plan', { cancelled: true }), 'Cancelled; nothing ran.');
  assert.equal(say('fidelis_cancel_plan', { cancelled: true, planId: 'plan-9', stopped: true, engineCancelRequested: true }), 'Stopped; what had run stays in History.', 'a stopped running plan is never "nothing ran"');
  assert.equal(say('fidelis_cancel_plan', { cancelled: false, note: 'nothing was waiting' }), 'Nothing was waiting.');
  // Reads answer with the bridge result itself.
  assert.equal(say('fidelis_rois', { total: 64, rois: [{ index: 0 }] }), '64 objects.');
  assert.equal(say('fidelis_rois', { total: 0, rois: [] }), '0 objects in the ROI list.', 'a zero keeps its digit for the instant route');
  assert.equal(say('fidelis_object_stats', { n: 64, area: { mean: 351.4, median: 340, min: 20, max: 900 }, unit: 'pixel' }), '64 objects, mean area 351 square pixels, median 340 square pixels.');
  assert.equal(say('fidelis_object_stats', { n: 0 }), '0 measured objects.');
  assert.equal(say('fidelis_history', { steps: [] }), undefined, 'open-ended reads go to the model');
  assert.equal(say('fidelis_zoom', { viewer: {} }), '', 'view tools finish silently after their acknowledgment');
  // Continuation: a waiting plan is the whole answer; otherwise the request decides.
  assert.equal(fidelis.requestContinuesAfter('fidelis_segment_cells', 'segment the cells and measure their area', completed(plan)), false);
  assert.equal(fidelis.requestContinuesAfter('fidelis_segment_cells', 'segment the cells and measure their area', completed({ count: 3 })), true);
  assert.equal(fidelis.requestContinuesAfter('fidelis_segment_cells', 'find the nuclei'), false);
  assert.equal(fidelis.requestContinuesAfter('fidelis_rois', 'how many nuclei are there'), false);
  assert.equal(fidelis.requestContinuesAfter('fidelis_rois', 'which nucleus is largest'), true);
  assert.equal(fidelis.requestContinuesAfter('fidelis_threshold', 'make the threshold stricter'), true);
  assert.equal(fidelis.requestContinuesAfter('fidelis_open_path', 'open the embryos image'), false);
  assert.equal(fidelis.requestContinuesAfter('fidelis_open_path', 'open the embryos image and count the nuclei'), true);
  assert.equal(fidelis.localSentenceFor('fidelis_confirm', {}, completed({ rows: 1, lastRow: { Mean: 255 } }), { ...blobs, document: { ...blobs.document, binary: true } }), undefined, 'a confirmed measurement on a mask is not spoken as an intensity');
  // Acknowledgments never claim a waiting macro ran.
  assert.equal(fidelis.acknowledgmentFor('fidelis_run_macro', {}, {}), 'Preparing the macro.');
  assert.equal(fidelis.acknowledgmentFor('fidelis_segment_cells', {}, {}), 'Segmenting cells.');
  assert.equal(fidelis.acknowledgmentFor('fidelis_highlight', { label: '64 nuclei' }, {}), 'Here: 64 nuclei.');
  assert.equal(fidelis.acknowledgmentFor('fidelis_context', {}, {}), undefined);
  assert.equal(describeDocument({ document: { title: 'hela-cells.tif', width: 672, height: 512, channels: 3, slices: 1, frames: 1, bitDepth: 16, unit: 'micron', pixelWidth: 0.08, pixelHeight: 0.08 }, counts: { rois: 12 } }),
    'Hela cells: 672 by 512, 16-bit; 3 channels; 0.08 microns per pixel; 12 objects outlined.');
  for (const yes of ['Yes.', 'yes please', 'Okay, go ahead, run it.', 'confirm', 'yeah do it', 'Yes, go ahead, thanks.']) assert.equal(researcherAssents(yes), true, yes);
  for (const qualified of ['yes, if you use the original pixels', 'confirm whether the image is calibrated', 'go ahead and measure it first']) assert.equal(researcherAssents(qualified), false, qualified);
  for (const other of ['okay', 'no', "yes but don't", 'Yes, but don\u2019t run it yet.', 'yes dont', 'yes wait', 'yes, later', 'invert it with a macro', '', undefined]) assert.equal(researcherAssents(other), false, String(other));
  assert.deepEqual(fidelis.modelArguments!('fidelis_segment_cells', { method: 'Triangle', minSize: 40, watershed: false, channel: 2 }, 'find the nuclei'), {}, 'unrequested tuning is dropped');
  assert.deepEqual(fidelis.modelArguments!('fidelis_segment_cells', { method: 'Triangle', channel: 2 }, 'segment the green channel with triangle'), { method: 'Triangle', channel: 2 });
});

type Recorded = { operation: string; arguments: Record<string, unknown>; [key: string]: unknown };
/** A loopback stand-in for the Java bridge: bearer-checked /execute with per-operation answers, and /health with its operations. */
async function fakeBridge(root: string, answer: (request: Recorded, state: { context: Record<string, unknown> }) => unknown, operations = requiredOperationsFor(fidelis)) {
  await mkdir(join(root, '.local/state'), { recursive: true });
  await writeFile(join(root, '.local/state/bridge-token'), 'fixture-token\n');
  const requests: Recorded[] = []; const healthHeaders: Array<string | undefined> = [];
  const state = { context: structuredClone(blobs) as Record<string, unknown> };
  const server = createServer(async (req: IncomingMessage, res: ServerResponse) => {
    const send = (status: number, value: unknown) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
    if (req.method === 'GET' && req.url === '/health') { healthHeaders.push(req.headers.authorization); send(200, { application: 'Fidelis', version: '0.1.0', adapter: { adapterVersion: '0.1.0', protocolVersion: 1, operations } }); return; }
    if (req.headers.authorization !== 'Bearer fixture-token') { send(401, { error: 'Unauthorized' }); return; }
    const chunks: Buffer[] = []; for await (const chunk of req) chunks.push(chunk as Buffer);
    const request = JSON.parse(Buffer.concat(chunks).toString('utf8')) as Recorded; requests.push(request);
    if (request.operation === 'context') { send(200, { result: state.context }); return; }
    try { send(200, { result: await answer(request, state) }); } catch (error) { send(409, { error: error instanceof Error ? error.message : String(error), code: 'ENGINE', suggestions: [] }); }
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const address = server.address(); assert(address && typeof address === 'object');
  return { port: address.port, requests, healthHeaders, state, close: async () => { server.close(); await once(server, 'close'); } };
}

test('the bridge receives the contract body: spoken, taskId and utterance inside a task, nothing extra outside it, and readiness from /health', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-envelope-'));
  const bridge = await fakeBridge(root, request => request.operation === 'zoom' ? { viewer: { magnification: 2 } } : { ok: true });
  try {
    const adapter = new BridgeAdapter(root, fidelis, bridge.port);
    const context = await adapter.execute('context') as { adapterReadiness: { status: string; message: string } };
    assert.equal(context.adapterReadiness.status, 'operations_match'); assert.match(context.adapterReadiness.message, /declares all \d+ required operations/);
    assert.equal(bridge.healthHeaders[0], 'Bearer fixture-token');
    assert.deepEqual(bridge.requests[0], { operation: 'context', arguments: {} }, 'no envelope fields outside a conversation task');
    await toolTask.run('task-1', () => toolUtterance.run({ spoken: true, utterance: () => '  zoom in  ' }, () => adapter.execute('zoom', { direction: 'in' }, { expectedContext: 'k1' })));
    assert.deepEqual(bridge.requests[1], { operation: 'zoom', arguments: { direction: 'in' }, expectedContext: 'k1', spoken: true, utterance: 'zoom in', taskId: 'task-1' });
    await toolTask.run('task-2', () => toolUtterance.run({ spoken: false, utterance: () => 'threshold it' }, () => adapter.execute('threshold', {}, { expectedContext: 'k1', approved: true, expectedControl: { id: 'x' } })));
    assert.deepEqual(bridge.requests[2], { operation: 'threshold', arguments: {}, expectedContext: 'k1', approved: true, spoken: false, utterance: 'threshold it', taskId: 'task-2' }, 'never expectedControl');
    assert.deepEqual(toolTask.run('t', () => conversationEnvelope()), { taskId: 't' }, 'a task without an utterance store sends only its id');
    // Other profiles keep their original body.
    await toolTask.run('task-3', () => toolUtterance.run({ spoken: true, utterance: () => 'zoom in' }, () => new BridgeAdapter(root, fiji, bridge.port).execute('zoom', { direction: 'in' })));
    assert.deepEqual(bridge.requests[3], { operation: 'zoom', arguments: { direction: 'in' } });
  } finally { await bridge.close(); await rm(root, { recursive: true, force: true }); }
  // Readiness reports missing operations and an unreadable /health.
  const missing = await bridgeReadiness('/nowhere', { adapter: { protocolVersion: 1, operations: requiredOperationsFor(fidelis).filter(op => op !== 'confirm') } }, fidelis);
  assert.equal(missing.status, 'restart_required'); assert.deepEqual(missing.missingOperations, ['confirm']); assert.match(missing.message, /fidelis-app\/tools\/build\.py/); assert.equal(bridgeReady(missing), false);
  assert.equal((await bridgeReadiness('/nowhere', { contextKey: 'k' }, fidelis)).status, 'unavailable');
  assert.equal((await bridgeReadiness('/nowhere', { adapter: { protocolVersion: 2, operations: [] } }, fidelis)).status, 'build_unverified');
  assert.equal(bridgeReady(await bridgeReadiness('/nowhere', { adapter: { protocolVersion: 1, operations: requiredOperationsFor(fidelis) } }, fidelis)), true);
});

/** Engine stand-in: segment_cells runs at once (or waits when the plan is explicit), run_macro always waits, confirm runs the pending plan. */
function engine(options: { explicitSegmentation?: boolean } = {}) {
  return (request: Recorded, state: { context: Record<string, unknown> }) => {
    const wait = (steps: number, reasons: string[]) => { const planId = `plan-${request.operation}`; state.context = { ...state.context, contextKey: `k-${planId}`, pending: { planId, reasons } }; return { status: 'awaiting_confirmation', planId, reasons, steps: Array.from({ length: steps }, (_, i) => ({ label: `step ${i + 1}` })) }; };
    if (request.operation === 'segment_cells') return options.explicitSegmentation ? wait(9, ['fidelis.penny.confirm=always']) : { count: 64, averageSize: 351.42, unit: 'pixel', steps: [], highlight: { label: '64 cells', targets: [] } };
    if (request.operation === 'run_macro') return wait(1, ['step 1 (run_macro) is code']);
    if (request.operation === 'confirm') {
      const pending = state.context.pending as { planId: string } | null;
      if (!pending || pending.planId !== request.arguments.planId) throw new Error('No such pending plan.');
      state.context = { ...state.context, contextKey: 'k-after', pending: null, counts: { rois: 64, resultsRows: 64, history: 12 } };
      return pending.planId === 'plan-run_macro' ? { step: { label: 'Run macro', outcome: 'COMPLETED' } } : { count: 64, averageSize: 351.42, unit: 'pixel' };
    }
    if (request.operation === 'cancel_plan') { state.context = { ...state.context, pending: null }; return { cancelled: true }; }
    if (request.operation === 'rois') return { total: 64, rois: [{ index: 0, name: '0001', bounds: {} }] };
    throw new Error(`unexpected ${request.operation}`);
  };
}
function conversation(runtime: Runtime, client: BackendClient = { complete: async () => { throw new Error('no model round expected'); } }) {
  const events: Array<Record<string, unknown>> = [];
  return { events, conversation: new TextConversation(runtime, client, 'synthetic', event => events.push(event)), result: () => events.filter(event => event.type === 'text_result').at(-1) };
}

test('end to end over HTTP: spoken "find the nuclei" runs at once with the envelope; an explicit plan waits until "yes"', async () => {
  for (const explicitSegmentation of [false, true]) {
    const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-e2e-'));
    const bridge = await fakeBridge(root, engine({ explicitSegmentation }));
    const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
    const { conversation: talk, events, result } = conversation(runtime);
    try {
      await talk.submit('Find the nuclei.', true);
      const segment = bridge.requests.find(request => request.operation === 'segment_cells')!;
      const taskId = result()!.taskId;
      assert.deepEqual(segment, { operation: 'segment_cells', arguments: {}, expectedContext: 'k1', approved: true, spoken: true, utterance: 'Find the nuclei.', taskId });
      assert.ok(bridge.requests.filter(request => request.operation === 'context').every(request => request.taskId === taskId && request.spoken === true), 'every call of the task carries it');
      assert.deepEqual(events.find(event => event.type === 'text_ack')?.text, 'Finding the nuclei.');
      if (!explicitSegmentation) { assert.equal(result()!.text, 'Found 64 nuclei, mean area 351 square pixels.'); continue; }
      assert.equal(result()!.text, 'Ready to run 9 steps; say yes to go ahead.', 'a waiting plan is never reported as run');
      await talk.submit('Yes, go ahead.', true);
      const confirm = bridge.requests.find(request => request.operation === 'confirm')!;
      assert.deepEqual(confirm.arguments, { planId: 'plan-segment_cells' }); assert.equal(confirm.approved, true); assert.equal(confirm.expectedContext, 'k-plan-segment_cells');
      assert.equal(confirm.utterance, 'Yes, go ahead.'); assert.equal(confirm.taskId, result()!.taskId);
      assert.equal(result()!.text, 'Found 64 nuclei, mean area 351 square pixels.');
      await talk.submit('How many nuclei are there?', true);
      assert.equal(result()!.text, '64 nuclei.');
      assert.equal(bridge.requests.filter(request => request.operation === 'segment_cells').length, 1, 'the count never re-segments');
    } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
  }
});

test('a model-chosen macro waits for confirmation, stops the request there and is cancelled by "no"', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-macro-'));
  const bridge = await fakeBridge(root, engine());
  const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  const requests: BackendRequest[] = [];
  const client: BackendClient = { complete: async request => {
    requests.push(request);
    return { output: [{ type: 'function_call', call_id: `call-${requests.length}`, name: 'fidelis_run_macro', arguments: JSON.stringify({ code: 'run("Invert");' }) }] };
  } };
  const { conversation: talk, events, result } = conversation(runtime, client);
  try {
    await talk.submit('Invert it with a macro and then measure the mean', true);
    assert.equal(requests.length, 1, 'no second model round: the waiting plan is the answer');
    assert.equal(result()!.text, 'Ready to run 1 step; say yes to go ahead.');
    assert.deepEqual(events.find(event => event.type === 'text_ack')?.text, 'Preparing the macro.');
    assert.ok(!bridge.requests.some(request => request.operation === 'measure'), 'nothing later in the request ran');
    const action = [...runtime.actions.values()].find(entry => entry.tool === 'fidelis_run_macro')!;
    assert.equal(action.status, 'completed'); assert.equal(action.outcome, 'awaiting_confirmation', 'the action is recorded as not run');
    assert.equal((await runtime.call('fidelis_action_status', { actionId: action.id }) as { outcome?: unknown }).outcome, 'awaiting_confirmation');
    assert.match(await readFile(join(root, '.local/state/actions.jsonl'), 'utf8'), /"outcome":"awaiting_confirmation"/);
    const macro = bridge.requests.find(request => request.operation === 'run_macro')!;
    assert.equal(macro.utterance, 'Invert it with a macro and then measure the mean'); assert.equal(macro.spoken, true); assert.equal(macro.taskId, result()!.taskId); assert.equal(macro.approved, true);
    const tools = requests[0].tools.map(tool => tool.name);
    assert.ok(tools.includes('fidelis_confirm') && !tools.includes('fidelis_context'), 'the host supplies context; confirm is offered');
    assert.match(requests[0].instructions, /awaiting_confirmation has not run/);
    assert.ok(JSON.stringify(requests[0].input).includes('fidelisObservation'));
    await talk.submit('No.', true);
    assert.equal(bridge.requests.at(-1)!.operation, 'cancel_plan'); assert.equal(result()!.text, 'Cancelled; nothing ran.');
    assert.equal(requests.length, 1);
  } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('confirm binds to the observed plan, and a reviewed action decided later keeps its conversation envelope', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-review-'));
  const bridge = await fakeBridge(root, (request, state) => request.operation === 'filter' ? { step: { label: 'Gaussian Blur...' } } : engine()(request, state));
  try {
    const auto = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
    // The researcher's words and what Fidelis showed when they arrived (the request's first observation).
    const saying = <T>(words: string, work: () => Promise<T>, observed: unknown = structuredClone(bridge.state.context)) => toolTask.run('task-c', () => toolUtterance.run({ spoken: false, utterance: () => words, observed: () => observed }, work));
    await assert.rejects(saying('yes', () => auto.call('fidelis_confirm', {})), /Nothing is waiting for confirmation/);
    bridge.state.context = { ...bridge.state.context, contextKey: 'k2', pending: { planId: 'plan-9', reasons: [] } };
    await assert.rejects(saying('yes', () => auto.call('fidelis_confirm', { planId: 'plan-1' })), /no longer the one waiting/);
    await assert.rejects(saying('yes', () => auto.call('fidelis_confirm', {}), { ...bridge.state.context, pending: { planId: 'plan-8', reasons: [] } }), /The plan changed after you said yes/, 'a "yes" to plan 8 never runs plan 9');
    // Only the researcher's own assent confirms: never a direct client, never the model on another request.
    await assert.rejects(auto.call('fidelis_confirm', {}), /Only the researcher can confirm this plan/);
    await assert.rejects(saying('invert it with a macro', () => auto.call('fidelis_confirm', {})), /Only the researcher can confirm/);
    await assert.rejects(saying("yes, but don't run it yet", () => auto.call('fidelis_confirm', {})), /Only the researcher can confirm/);
    assert.ok(!bridge.requests.some(request => request.operation === 'confirm'), 'nothing was dispatched');
    const review = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: false, profile: fidelis });
    const attributed: Array<string | undefined> = []; review.on('tool', () => attributed.push(toolTask.getStore()));
    const proposal = await toolTask.run('task-r', () => toolUtterance.run({ spoken: true, utterance: () => 'blur it a little' }, () => review.call('fidelis_filter', { name: 'gaussian', sigma: 1 }))) as { status: string; actionId: string };
    assert.equal(proposal.status, 'approval_required');
    const decided = await review.decide(proposal.actionId, true);
    assert.equal(decided.status, 'completed');
    assert.deepEqual(attributed, ['task-r'], 'the reviewed action\'s tool event names its task');
    assert.deepEqual(bridge.requests.at(-1), { operation: 'filter', arguments: { name: 'gaussian', sigma: 1 }, expectedContext: 'k2', approved: true, spoken: true, utterance: 'blur it a little', taskId: 'task-r' });
    assert.equal(fidelis.localSentenceFor('fidelis_filter', {}, decided), 'Filter applied.');
    const audit = await readFile(join(root, '.local/state/actions.jsonl'), 'utf8');
    assert.doesNotMatch(audit, /blur it a little/, 'the utterance is sent to the bridge, not written to the audit journal');
  } finally { await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('a waiting plan ends a typed request too, and later calls in the same model batch never run', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-batch-'));
  const bridge = await fakeBridge(root, (request, state) => request.operation === 'measure' ? { rows: 1, lastRow: { Mean: 5 } } : engine()(request, state));
  const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  let rounds = 0;
  const client: BackendClient = { complete: async () => {
    rounds++;
    return { output: [
      { type: 'function_call', call_id: 'c1', name: 'fidelis_run_macro', arguments: JSON.stringify({ code: 'run("Invert");' }) },
      { type: 'function_call', call_id: 'c2', name: 'fidelis_measure', arguments: JSON.stringify({ scope: 'all', measurements: ['mean'] }) },
    ] };
  } };
  const { conversation: talk, result } = conversation(runtime, client);
  try {
    await talk.submit('invert it with a macro, then measure the mean', false);
    assert.equal(rounds, 1);
    assert.equal(result()!.text, 'Ready to run 1 step; say yes to go ahead.');
    assert.ok(bridge.requests.some(request => request.operation === 'run_macro'));
    assert.ok(!bridge.requests.some(request => request.operation === 'measure'), 'the measurement waits for the confirmed macro');
    assert.equal(bridge.requests.find(request => request.operation === 'run_macro')!.spoken, false, 'typed requests say so');
  } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('an empty ROI list is answered with its zero, and a reviewed plan that waits is never announced as running', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-zero-'));
  const bridge = await fakeBridge(root, (request, state) => request.operation === 'rois' ? { total: 0, rois: [] } : engine({ explicitSegmentation: true })(request, state));
  try {
    const auto = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
    const first = conversation(auto);
    await first.conversation.submit('How many nuclei are there?', true);
    assert.equal(first.result()!.text, '0 nuclei in the ROI list.'); first.conversation.stop();
    const review = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: false, profile: fidelis });
    review.on('action', action => { if (action.status === 'pending') void review.decide(action.id, true); });
    const client: BackendClient = { complete: async () => ({ output: [{ type: 'function_call', call_id: 'c1', name: 'fidelis_segment_cells', arguments: '{}' }] }) };
    const second = conversation(review, client);
    await second.conversation.submit('Find the nuclei.', true);
    const acks = second.events.filter(event => event.type === 'text_ack').map(event => event.text);
    assert.ok(acks.includes('Ready for your approval.')); assert.ok(!acks.includes('Running it now.'), `acks: ${acks.join(' | ')}`);
    assert.equal(second.result()!.text, 'Ready to run 9 steps; say yes to go ahead.');
    second.conversation.stop();
  } finally { await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('instant routes are bound to the context they were chosen from: a changed plan is never cancelled by a stale "no"', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-stale-'));
  const bridge = await fakeBridge(root, engine());
  let reads = 0;
  const adapter = new BridgeAdapter(root, fidelis, bridge.port);
  // Every observation sees a different pending plan, as if Fidelis replaced it between the route choice and dispatch.
  const changing = { execute: async (operation: string, args?: Record<string, unknown>, options?: Record<string, unknown>) => {
    if (operation === 'context') { reads++; bridge.state.context = { ...bridge.state.context, contextKey: `k-${reads}`, pending: { planId: `plan-${reads}`, reasons: [] } }; }
    return adapter.execute(operation, args, options);
  } };
  const runtime = new Runtime(changing, new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  const { conversation: talk, result } = conversation(runtime);
  try {
    await talk.submit('No.', true);
    assert.equal(result()!.outcome, 'failed'); assert.match(String(result()!.text), /Fidelis changed before that ran; nothing was done/);
    assert.ok(!bridge.requests.some(request => request.operation === 'cancel_plan'), 'nothing was cancelled');
    await talk.submit('yes', true);
    assert.ok(!bridge.requests.some(request => request.operation === 'confirm'), 'nothing was confirmed');
  } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
  // Unchanged context: the view route carries the observed key.
  const root2 = await mkdtemp(join(tmpdir(), 'luma-fidelis-bound-'));
  const steady = await fakeBridge(root2, () => ({ viewer: { magnification: 2 } }));
  const runtime2 = new Runtime(new BridgeAdapter(root2, fidelis, steady.port), new Knowledge(fidelis), join(root2, '.local/state'), { autoApprove: true, profile: fidelis });
  const second = conversation(runtime2);
  try {
    await second.conversation.submit('zoom in', true);
    assert.equal(steady.requests.find(request => request.operation === 'zoom')!.expectedContext, 'k1');
    assert.equal(second.result()!.text, 'Zooming in.');
  } finally { second.conversation.stop(); await steady.close(); await rm(root2, { recursive: true, force: true }); }
});

test('a legacy command whose original dialog waits is filled and pressed, then finishes with the command result', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-dialog-'));
  let open: Record<string, unknown> | null = null;
  const bridge = await fakeBridge(root, (request, state) => {
    if (request.operation === 'command') {
      open = { title: 'Gaussian Blur...', fields: [{ key: 'sigma', label: 'Sigma (Radius)', type: 'number', value: 2, choices: null }, { key: 'scaled', label: 'Scaled units', type: 'checkbox', value: false }], buttons: ['OK', 'Cancel'] };
      return { status: 'dialog', dialogId: 'dlg-1', planId: 'plan-gauss', operation: 'command', dialog: open };
    }
    if (request.operation === 'dialog') return open ? { pending: true, status: 'dialog', dialogId: 'dlg-1', dialog: open } : { pending: false, others: [] };
    if (request.operation === 'dialog_action') {
      assert.ok(open, 'a dialog is waiting'); assert.equal(request.arguments.dialogId, 'dlg-1', 'the answer is bound to that dialog');
      open = null; state.context = { ...state.context, contextKey: 'k-blurred', counts: { rois: 0, resultsRows: 0, history: 3 } };
      return { status: 'completed', step: { actionId: 'h3', label: 'Gaussian Blur...', commandId: 'ij1:Process>Filters>Gaussian Blur...', outcome: 'COMPLETED' }, steps: [{}], contextKey: 'k-blurred' };
    }
    throw new Error(`unexpected ${request.operation}`);
  });
  const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  const rounds: BackendRequest[] = [];
  const client: BackendClient = { complete: async request => {
    rounds.push(request);
    if (rounds.length === 1) return { output: [{ type: 'function_call', call_id: 'c1', name: 'fidelis_command', arguments: JSON.stringify({ commandId: 'ij1:Process>Filters>Gaussian Blur...' }) }] };
    const seen = JSON.stringify(request.input);
    assert.match(seen, /\\"status\\":\\"dialog\\"/, 'the model sees the waiting dialog');
    return { output: [{ type: 'function_call', call_id: 'c2', name: 'fidelis_dialog_action', arguments: JSON.stringify({ dialogId: 'dlg-1', fields: { sigma: 3 }, press: 'OK' }) }] };
  } };
  const { conversation: talk, result } = conversation(runtime, client);
  try {
    await talk.submit('Blur it with a Gaussian of sigma 3', true);
    assert.equal(rounds.length, 2, 'the dialog sent the request back to the model, which filled it');
    assert.deepEqual(bridge.requests.filter(request => request.operation !== 'context').map(request => [request.operation, request.arguments]),
      [['command', { commandId: 'ij1:Process>Filters>Gaussian Blur...' }], ['dialog_action', { dialogId: 'dlg-1', fields: { sigma: 3 }, press: 'OK' }]]);
    assert.deepEqual([...runtime.actions.values()].map(action => [action.tool, action.outcome]), [['fidelis_command', 'dialog'], ['fidelis_dialog_action', undefined]], 'the command is recorded as waiting at its dialog');
    const pressed = bridge.requests.find(request => request.operation === 'dialog_action')!;
    assert.equal(pressed.approved, true); assert.equal(pressed.utterance, 'Blur it with a Gaussian of sigma 3');
    assert.equal(result()!.text, 'Ran Gaussian Blur.');
    assert.equal(open, null, 'no dialog is left open');
  } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('readiness uses the operations a newer bridge reports in context.adapter without asking /health', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-adapter-'));
  const bridge = await fakeBridge(root, () => ({}));
  bridge.state.context = { ...bridge.state.context, adapter: { adapterVersion: 'fidelis-bridge-1', protocolVersion: 1, operations: requiredOperationsFor(fidelis).filter(op => !op.startsWith('dialog')), sourceSha256: 'f'.repeat(64) } };
  try {
    const context = await new BridgeAdapter(root, fidelis, bridge.port).execute('context') as { adapterReadiness: { status: string; missingOperations?: string[] } };
    assert.equal(bridge.healthHeaders.length, 0, 'no /health request');
    assert.equal(context.adapterReadiness.status, 'restart_required'); assert.deepEqual(context.adapterReadiness.missingOperations, ['dialog', 'dialog_action']);
  } finally { await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('a spoken "cancel" consumed as barge-in still discards a waiting plan, and never reaches the model', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-stopword-'));
  const bridge = await fakeBridge(root, engine());
  const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  let modelRounds = 0;
  const { conversation: talk, result } = conversation(runtime, { complete: async () => { modelRounds++; throw new Error('no model round expected'); } });
  try {
    // The voice transport hands the stop word to control() after interrupting; with nothing pending it does nothing.
    await talk.control('Cancel that.');
    assert.equal(modelRounds, 0); assert.ok(!bridge.requests.some(request => request.operation === 'cancel_plan'));
    bridge.state.context = { ...bridge.state.context, contextKey: 'k-wait', pending: { planId: 'plan-run_macro', reasons: ['code'] } };
    await talk.control('Cancel that.');
    const cancel = bridge.requests.find(request => request.operation === 'cancel_plan')!;
    assert.deepEqual(cancel.arguments, { planId: 'plan-run_macro' }); assert.equal(cancel.expectedContext, 'k-wait');
    assert.equal(result()!.text, 'Cancelled; nothing ran.'); assert.equal(modelRounds, 0);
    // QuPath and Fiji conversations ignore control words, as before.
    const fijiRuntime = new Runtime({ execute: async () => { throw new Error('no bridge call expected'); } }, new Knowledge(fiji), join(root, '.local/fiji-state'), { profile: fiji });
    const fijiTalk = new TextConversation(fijiRuntime, { complete: async () => { throw new Error('no model round expected'); } }, 'synthetic', () => {});
    await fijiTalk.control('stop');
  } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
  // The speech session routes a final "cancel" to control(), not to submit().
  const controls: string[] = [], submits: string[] = []; let callbacks!: { transcript: (value: { id: string; text: string; partial: boolean }) => void; listening: () => void; inputReady: () => void };
  const session = new SpeechSession({ transcribe: async (_audio, signal, c) => { callbacks = c as typeof callbacks; c.inputReady(); await new Promise<void>(resolve => signal.addEventListener('abort', () => resolve(), { once: true })); }, speak: async () => new Uint8Array([0, 0]) },
    { submit: async text => { submits.push(text); }, stop: () => {}, control: async text => { controls.push(text); } }, () => {}, () => {}, 1_800_000, { wakeWord: false });
  session.start(); await new Promise(resolve => setImmediate(resolve));
  callbacks.transcript({ id: 'c1', text: 'Cancel that.', partial: false });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(controls, ['Cancel that.']); assert.deepEqual(submits, []);
  session.stop(); await session.settled();
});

test('a confirmed plan whose command opens its original dialog continues with the model instead of saying "Done."', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-confirm-dialog-'));
  const bridge = await fakeBridge(root, (request, state) => {
    if (request.operation === 'confirm') { state.context = { ...state.context, contextKey: 'k-dialog', pending: null }; return { status: 'dialog', dialogId: 'dlg-9', planId: 'plan-legacy', dialog: { title: 'Bandpass Filter...', fields: [{ key: 'filter_large', label: 'Filter large structures down to', type: 'number', value: 40 }], buttons: ['OK', 'Cancel'] } }; }
    if (request.operation === 'dialog_action') return { status: 'completed', step: { label: 'Bandpass Filter...', outcome: 'COMPLETED' }, steps: [{}] };
    throw new Error(`unexpected ${request.operation}`);
  });
  bridge.state.context = { ...bridge.state.context, contextKey: 'k-wait', pending: { planId: 'plan-legacy', reasons: ['legacy command without a catalogued schema'] } };
  const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  const rounds: BackendRequest[] = [];
  const client: BackendClient = { complete: async request => {
    rounds.push(request);
    assert.match(JSON.stringify(request.input), /fidelis_confirm .* returned .*Bandpass Filter/, 'the model sees what the instant step returned');
    return { output: [{ type: 'function_call', call_id: 'd1', name: 'fidelis_dialog_action', arguments: JSON.stringify({ dialogId: 'dlg-9', press: 'OK' }) }] };
  } };
  const { conversation: talk, events, result } = conversation(runtime, client);
  try {
    await talk.submit('Yes, go ahead.', true);
    assert.deepEqual(events.filter(event => event.type === 'text_ack').map(event => event.text), ['Running it.', 'Filling in the dialog.']);
    assert.equal(rounds.length, 1); assert.ok(bridge.requests.some(request => request.operation === 'dialog_action'));
    assert.equal(result()!.text, 'Ran Bandpass Filter.'); assert.notEqual(result()!.fastPath, true);
  } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('a confirmed measurement without a local sentence is phrased by the model, never a bare "Done."', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-confirm-measure-'));
  const bridge = await fakeBridge(root, (request, state) => {
    if (request.operation === 'confirm') { state.context = { ...state.context, contextKey: 'k-measured', pending: null }; return { status: 'completed', confirmed: true, operation: 'measure', rows: 1, lastRow: { Area: 400, Mean: 255 }, summary: { mean: { min: 255, max: 255, mean: 255, count: 1 } }, unit: 'pixel', steps: [{}, {}] }; }
    throw new Error(`unexpected ${request.operation}`);
  });
  bridge.state.context = { ...bridge.state.context, contextKey: 'k-wait', pending: { planId: 'plan-measure', reasons: ['Set Measurements is AC5'] } };
  const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  let rounds = 0;
  const { conversation: talk, result } = conversation(runtime, { complete: async request => {
    rounds++; assert.match(JSON.stringify(request.input), /fidelis_confirm .* returned .*Mean/);
    return { output: [], output_text: 'That region reads 255 everywhere: it is the segmentation mask, so the original pixels are needed for intensity.' };
  } });
  try {
    await talk.submit('Yes.', true);
    assert.ok(bridge.requests.some(request => request.operation === 'confirm'));
    assert.equal(rounds, 1); assert.match(String(result()!.text), /segmentation mask/);
  } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('a view plan that waits for confirmation (confirm=always) is announced, not reported as a failure', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-view-wait-'));
  const bridge = await fakeBridge(root, (request, state) => {
    if (request.operation !== 'display') throw new Error(`unexpected ${request.operation}`);
    state.context = { ...state.context, contextKey: 'k-wait', pending: { planId: 'plan-display', reasons: ['fidelis.penny.confirm=always'] } };
    return { status: 'awaiting_confirmation', planId: 'plan-display', operation: 'display', reasons: ['fidelis.penny.confirm=always'], steps: ['Set display range'] };
  });
  bridge.state.context = { ...bridge.state.context, document: { ...blobs.document, displayRange: [8, 248] } };
  const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  const { conversation: talk, result } = conversation(runtime);
  try {
    await talk.submit('Make it brighter.', true);
    assert.equal(result()!.text, 'Ready to run 1 step; say yes to go ahead.'); assert.equal(result()!.outcome, undefined);
  } finally { talk.stop(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
});

test('battery fixes: convert-to-mask and macro routes, cancel of a running dialog, channel-aware sentences, skipped save dialogs and a bounded macro for the model', () => {
  const context = { document: { id: 'doc-1', title: 'FluorescentCells.tif', width: 512, height: 512, channels: 3, bitDepth: 8, displayRange: [0, 255] }, documents: [], counts: { rois: 6 }, pending: null, running: null };
  // "Convert it to a mask with a black background" is Convert to Mask with black true, never a segmentation.
  assert.deepEqual(fidelis.fastRoute('Convert it to a mask with a black background.', context), { tool: 'fidelis_command', arguments: { commandId: 'ij1:Process>Binary>Convert to Mask', params: { black: true } }, reply: 'Converting to a mask.' });
  assert.deepEqual(fidelis.fastRoute('convert it to a mask', context)?.arguments, { commandId: 'ij1:Process>Binary>Convert to Mask', params: {} });
  assert.deepEqual(fidelis.fastRoute('Convert to mask.', context)?.arguments, { commandId: 'ij1:Process>Binary>Convert to Mask', params: {} });
  assert.equal((fidelis.fastRoute('make a mask with a white background', context)?.arguments.params as { black?: boolean }).black, false);
  assert.equal(fidelis.fastRoute('convert it to a mask and measure', context), undefined);
  // "Show me the workflow / the macro" is answered from fidelis_macro locally, and the request ends there.
  assert.equal(fidelis.fastRoute('Show me the workflow', context)?.tool, 'fidelis_macro');
  assert.equal(fidelis.fastRoute('show me the macro', context)?.tool, 'fidelis_macro');
  assert.equal(fidelis.requestContinuesAfter('fidelis_macro', 'show me the workflow', { macro: 'x', steps: 2 }), false);
  const macro = ['// Generated by Fidelis', 'open("a.tif");', 'run("Measure");', 'run("Measure");', '', 'close();'].join('\n');
  assert.equal(fidelis.localSentenceFor('fidelis_macro', {}, { macro, steps: 4 }), 'The macro has 4 lines from 4 steps; it is in the History and macro tab, ready to export.');
  assert.equal(fidelis.localSentenceFor('fidelis_macro', {}, {}), undefined);
  // The model sees a bounded macro: a long session macro is cut to its first lines with its size, never pasted whole.
  assert.deepEqual(macroForModel({ macro, steps: 4 }), { macro, steps: 4 });
  const long = Array.from({ length: 300 }, (_, i) => `run("Measure"); // ${i}`).join('\n');
  const bounded = macroForModel({ macro: long, steps: Array(300).fill({}) }) as Record<string, unknown>;
  assert.equal(bounded.steps, 300); assert.equal(bounded.macroLines, 300); assert.equal(bounded.macroChars, long.length);
  assert((bounded.macro as string).length < 3000 && /260 more lines/.test(bounded.macro as string));
  assert.equal(JSON.stringify(fidelis.resultForModel?.('fidelis_macro', { macro: long, steps: [] })).length < 4000, true);
  assert.deepEqual(fidelis.resultForModel?.('fidelis_rois', { total: 3 }), { total: 3 });
  // "Cancel" while a plan is stopped at one of its original dialogs presses that dialog's Cancel.
  assert.deepEqual(fidelis.fastRoute('cancel', { ...context, running: { planId: 'p1', operation: 'command' } }), { tool: 'fidelis_cancel_plan', arguments: {}, reply: 'Cancelling.' });
  assert.equal(fidelis.fastRoute('cancel', context), undefined);
  // A segmentation on a multichannel image names the channel the bridge chose; a measurement names the channel it read.
  assert.equal(fidelis.localSentenceFor('fidelis_segment_cells', {}, completed({ count: 6, averageSize: 855, unit: 'pixel', channel: 3, channelBy: 'lut-blue' })), 'Found 6 cells on channel 3 (blue), mean area 855 square pixels.');
  assert.equal(fidelis.localSentenceFor('fidelis_segment_cells', {}, completed({ count: 6, averageSize: 855, unit: 'pixel', channel: 1, channelBy: 'current' })), 'Found 6 cells, mean area 855 square pixels.');
  assert.equal(fidelis.localSentenceFor('fidelis_measure', { scope: 'rois' }, completed({ summary: { mean: { mean: 201.2, min: 150, max: 230, count: 6 } }, channel: 3, channelBy: 'segmented' }), { document: { binary: false } }), 'Mean intensity 201 over 6 regions on channel 3.');
  assert.equal(fidelis.localSentenceFor('fidelis_measure', { scope: 'all' }, completed({ lastRow: { Mean: 26.4 }, channel: 1, channelBy: 'displayed' }), { document: { binary: false } }), 'Mean intensity 26.4 in the image.');
  // A command whose save-file chooser the bridge cancelled says so; one that ended there is not reported as done.
  assert.equal(fidelis.localSentenceFor('fidelis_dialog_action', {}, completed({ step: { label: 'Coloc 2' }, cancelledFileDialog: true })), 'Ran Coloc 2. Fiji asked where to save a file; Penny skipped that.');
  assert.equal(fidelis.localSentenceFor('fidelis_confirm', {}, completed({ step: { label: 'Coloc 2' }, cancelledFileDialog: true })), 'Ran Coloc 2. Fiji asked where to save a file; Penny skipped that.');
  assert.equal(fidelis.localSentenceFor('fidelis_dialog_action', {}, completed({ status: 'cancelled', dialog: 'Save as PDF', cancelledFileDialog: true })), 'Fiji asked where to save a file (Save as PDF); Penny does not drive file dialogs, so the command ended there.');
  assert.equal(fidelis.outcomeOf?.('fidelis_dialog_action', completed({ status: 'cancelled', dialog: 'Save as PDF', cancelledFileDialog: true })), 'cancelled');
});

test('an undo says what the engine put back: the ROI list, the Results rows and the pixels', () => {
  assert.equal(fidelis.localSentenceFor('fidelis_undo', {}, completed({ step: { label: 'Undo' }, undoneActionId: 'act-9', undoneActionIds: ['act-9'], restored: { pixels: false, rois: { before: 67, after: 0 }, resultsRows: { before: 67, after: 0 } } })), 'Undid: 67 objects → 0, Results cleared.');
  assert.equal(fidelis.localSentenceFor('fidelis_undo', {}, completed({ step: { label: 'Undo' }, undoneActionId: 'act-3', undoneActionIds: ['act-3'], restored: { pixels: true } })), 'Undid: pixels restored.');
  assert.equal(fidelis.localSentenceFor('fidelis_undo', {}, completed({ step: { label: 'Undo' }, undoneActionId: 'act-3', undoneActionIds: ['act-3'], restored: { pixels: false, resultsRows: { before: 5, after: 4 } } })), 'Undid: Results 5 → 4 rows.');
  assert.equal(fidelis.localSentenceFor('fidelis_undo', {}, completed({ step: { label: 'Undo' }, undoneActionId: 'act-3', undoneActionIds: ['act-3'] })), 'Undone.');
});

test('"run this macro" routes to fidelis_run_macro with the session macro as its code, and a run says what it produced', () => {
  const context = { document: { id: 'doc-1', title: 'blobs.gif', width: 256, height: 254, channels: 1, bitDepth: 8 }, documents: [], counts: { rois: 0 }, pending: null, running: null };
  assert.deepEqual(fidelis.fastRoute('Run this macro.', context), { tool: 'fidelis_run_macro', arguments: {}, reply: 'Preparing the macro.', macroFromHistory: true });
  assert.equal(fidelis.fastRoute('replay the workflow again', context)?.macroFromHistory, true);
  assert.equal(fidelis.fastRoute('run this macro and measure', context), undefined);
  assert.equal(fidelis.localSentenceFor('fidelis_confirm', {}, completed({ step: { label: 'Run macro (12 lines)', commandId: null }, resultsRowsAdded: 67, documentsAdded: ['doc-2'] })), 'Ran the macro: 67 result rows, 1 new image.');
  assert.equal(fidelis.localSentenceFor('fidelis_confirm', {}, completed({ step: { label: 'Run macro (12 lines)', commandId: null }, resultsRowsAdded: 67, documentsAdded: ['doc-2'], focused: 'doc-2' })), 'Ran the macro: 67 result rows, 1 new image on screen.');
  assert.equal(fidelis.localSentenceFor('fidelis_run_macro', {}, completed({ step: { label: 'Run macro (3 lines)' }, resultsRowsAdded: 0, documentsAdded: [] })), 'Ran the macro.');
});

test('Stop (stop_task) reaches the plan running in Fidelis: the gateway sends fidelis_cancel_plan to the bridge, and nothing when no plan runs', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-fidelis-stop-'));
  const bridge = await fakeBridge(root, (request, state) => {
    if (request.operation === 'cancel_plan') { state.context = { ...state.context, running: null }; return { cancelled: true, planId: 'plan-9', stopped: true, engineCancelRequested: true }; }
    return { ok: true };
  });
  const runtime = new Runtime(new BridgeAdapter(root, fidelis, bridge.port), new Knowledge(fidelis), join(root, '.local/state'), { autoApprove: true, profile: fidelis });
  const gateway = await startServer(root, 0, { runtime, profile: fidelis });
  const address = gateway.server.address(); assert(address && typeof address === 'object');
  const token = (await readFile(join(root, '.local/state/companion-token'), 'utf8')).trim();
  const ws = new WebSocket(`ws://127.0.0.1:${address.port}/events`, { headers: { Authorization: `Bearer ${token}` } });
  const events: Array<Record<string, unknown>> = []; ws.on('message', raw => events.push(JSON.parse(raw.toString())));
  const until = async (what: string, done: () => boolean) => { const deadline = Date.now() + 3000; while (!done()) { assert(Date.now() < deadline, `Expected ${what}`); await new Promise(resolve => setTimeout(resolve, 5)); } };
  try {
    await once(ws, 'open');
    ws.send(JSON.stringify({ type: 'hello', clientId: 'fidelis-workspace-test-01', client: 'fidelis-workspace', desktop: false }));
    // nothing runs or waits: Stop only ends the conversation's task
    ws.send(JSON.stringify({ type: 'stop_task' }));
    await until('the control turn to finish', () => events.filter(event => event.type === 'task_state' && event.state === 'idle').length >= 1);
    assert.ok(!bridge.requests.some(request => request.operation === 'cancel_plan'), 'no plan to stop, nothing was sent');
    // a plan is running in Fidelis (its context says so): Stop stops it there
    bridge.state.context = { ...bridge.state.context, pending: null, running: { planId: 'plan-9', operation: 'measure' } };
    ws.send(JSON.stringify({ type: 'stop_task' }));
    await until('cancel_plan at the bridge', () => bridge.requests.some(request => request.operation === 'cancel_plan'));
    const cancel = bridge.requests.find(request => request.operation === 'cancel_plan')!;
    assert.deepEqual(cancel.arguments, {}); assert.equal(cancel.spoken, true); assert.equal(typeof cancel.taskId, 'string');
    await until('the spoken reply', () => events.some(event => event.type === 'text_result'));
    assert.equal(events.find(event => event.type === 'text_result')!.text, 'Cancelling.');
    assert.equal(bridge.requests.filter(request => request.operation === 'cancel_plan').length, 1);
  } finally { ws.terminate(); await gateway.close(); await bridge.close(); await rm(root, { recursive: true, force: true }); }
});
