package sc.fidelis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.nl.DefaultNlService;
import sc.fidelis.nl.NlService;
import sc.fidelis.speech.SpeechService;
import sc.fidelis.testkit.ProductEngine;

final class ConfirmationScenario {
    private static Workspace ws;
    private static Engine engine;
    private static boolean blackBefore;

    private ConfirmationScenario() {}

    static void start() throws Exception {
        engine = ProductEngine.get();
        blackBefore = ij.Prefs.blackBackground;
        ServiceRegistry.registerNl(new DefaultNlService(engine));
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            ws = Workspace.create(engine, null, new java.awt.Dimension(1300, 850));
            ws.frame().setVisible(true);
        });
        waitFor("engine ready", () -> engine.state() == EngineState.READY, 60);
    }

    static void stop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (ws != null) ws.frame().dispose();
        });
        // leave the shared JVM as found: no documents, the original global preference, no Results rows
        for (var d : new java.util.ArrayList<>(engine.documents())) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("discard", true);
            engine.submit(ActionRequest.session(Origin.MENU, Operations.CLOSE, p, TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
        ij.Prefs.blackBackground = blackBefore;
        ij.measure.ResultsTable.getResultsTable().reset();
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static DocumentId newImage(String title) throws Exception {
        Map<String, Object> ni = new LinkedHashMap<>();
        ni.put("title", title);
        ni.put("type", "8-bit");
        ni.put("fill", "Ramp");
        ni.put("width", 64);
        ni.put("height", 64);
        ni.put("slices", 1);
        engine.submit(ActionRequest.session(Origin.MENU, Operations.NEW_IMAGE, ni, null)).get(30, TimeUnit.SECONDS);
        return engine.focused().orElseThrow();
    }

    private static Map<String, String> user(Map<String, Object> params) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : params.keySet()) m.put(k, "USER");
        return m;
    }

    private static NlService.Proposal ready(ActionRequest r) {
        return new NlService.Proposal(NlService.Proposal.Status.READY, "test", List.of(r), List.of("path"),
                List.of("macro"), List.of(user(r.params())), List.of(), null);
    }

    private static ProposalSheet sheet(NlService.Proposal p, Origin origin) throws Exception {
        ProposalSheet[] s = new ProposalSheet[1];
        edt(() -> {
            s[0] = new ProposalSheet(ws.context(), "test instruction", origin);
            ws.context().banners().setSheet(s[0]);
            s[0].showProposal(p, 0.01);
        });
        return s[0];
    }

    // ---- required: nothing is submitted until Confirm -------------------------------------------

    static void requiredConfirmationSubmitsNothingUntilConfirm() throws Exception {
        DocumentId doc = newImage("confirm-required");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sigma", 2.0);
        params.put("scaled", false);
        params.put("stack", false);
        ActionRequest r = ActionRequest.command(Origin.NL_SPOKEN, "ij1:Process>Filters>Gaussian Blur...", params, TargetScope.of(doc));
        ProposalSheet s = sheet(ready(r), Origin.NL_SPOKEN);
        assertTrue(s.decision().required(), "every spoken proposal requires confirmation");
        assertFalse(s.decision().explicit(), () -> "Gaussian Blur with a user-stated sigma is not explicit: " + s.decision().reasons());
        int before = engine.history().size();
        Thread.sleep(1500);
        assertEquals(before, engine.history().size(), "nothing may be submitted before Confirm");
        assertTrue(s.confirmEnabled(), "a required, non-explicit proposal is confirmed with the button");
        edt(s::confirmNow);
        waitFor("confirmed step in history", () -> engine.history().size() > before, 60);
        var h = engine.history().get(engine.history().size() - 1);
        assertEquals("ij1:Process>Filters>Gaussian Blur...", h.commandId());
        assertEquals(Origin.NL_SPOKEN, h.origin());
    }

    // ---- explicit: reasons shown, deliberate act, never the default button ----------------------

    static void explicitConfirmationShowsReasonsAndNeedsADeliberateAct() throws Exception {
        DocumentId doc = newImage("confirm-explicit");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("black", true);
        ActionRequest r = ActionRequest.command(Origin.NL_TYPED, "ij1:Process>Binary>Convert to Mask", params, TargetScope.of(doc));
        ProposalSheet s = sheet(ready(r), Origin.NL_TYPED);
        assertTrue(s.decision().explicit(), "a 'black' parameter needs explicit confirmation");
        String reasons = String.join(" | ", s.decision().reasons());
        assertTrue(reasons.contains("BlackBackground"), reasons);
        String shown = s.bodyText();
        assertTrue(shown.contains("Explicit confirmation required"), shown);
        assertTrue(shown.contains("BlackBackground"), "the reason must be visible in the sheet: " + shown);
        assertFalse(s.confirmEnabled(), "Confirm is disabled until the deliberate tick");
        assertFalse(s.isDefaultButton(), "an explicit confirmation is never the default (Enter) button");
        int before = engine.history().size();
        edt(s::confirmNow);
        Thread.sleep(800);
        assertEquals(before, engine.history().size(), "Confirm without the explicit act must not submit");
        edt(s::acknowledgeNow);
        assertTrue(s.confirmEnabled(), "ticking the acknowledgement enables Confirm");
        assertFalse(s.isDefaultButton());
        edt(s::confirmNow);
        waitFor("explicit step in history", () -> engine.history().size() > before, 60);
        assertEquals("true", engine.globals().get("blackBackground"));
    }

    static void notReadyProposalCannotBeConfirmed() throws Exception {
        NlService.Proposal p = new NlService.Proposal(NlService.Proposal.Status.NEEDS_CLARIFICATION, "threshold the image", List.of(),
                List.of(), List.of(), List.of(), List.of("Which threshold method?"), null);
        ProposalSheet s = sheet(p, Origin.NL_TYPED);
        assertTrue(s.decision().required() && s.decision().explicit());
        assertFalse(s.confirmEnabled());
        assertTrue(s.bodyText().contains("nothing can run"), s.bodyText());
        assertFalse(s.ackVisible(), "no acknowledgement box for a proposal that cannot run");
    }

    // ---- speech vocabulary ----------------------------------------------------------------------

    private static final class FakeSpeech implements SpeechService {
        volatile String prompt;
        volatile String promptAtRecording;

        public State state() { return State.READY; }
        public String unavailableReason() { return null; }
        public void addListener(Listener l) { }
        public void removeListener(Listener l) { }
        public void startRecording() { promptAtRecording = prompt; }
        public CompletableFuture<Transcript> stopRecording() { return CompletableFuture.completedFuture(null); }
        public CompletableFuture<Transcript> transcribeFile(Path audio) { return CompletableFuture.completedFuture(null); }
        public void setVocabularyPrompt(String p) { prompt = p; }
    }

    static void vocabularyPromptFollowsDocumentsAndRecording() throws Exception {
        FakeSpeech fake = new FakeSpeech();
        edt(() -> ws.commandBar().attachSpeech(fake));
        assertNotNull(fake.prompt, "a prompt is set as soon as the speech service attaches");
        assertTrue(fake.prompt.length() <= 560, "bounded to 560 characters, was " + fake.prompt.length());
        newImage("VocabCheckImage");
        try {
            waitFor("prompt mentions the new document", () -> fake.prompt != null && fake.prompt.contains("VocabCheckImage"), 20);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "; prompt was \"" + fake.prompt + "\"; open documents "
                    + engine.documents().stream().map(d -> d.title()).toList(), e);
        }
        newImage("SecondVocabImage");
        waitFor("prompt mentions the second document", () -> fake.prompt.contains("SecondVocabImage"), 20);
        edt(() -> ws.commandBar().toggleMic());
        assertNotNull(fake.promptAtRecording);
        assertTrue(fake.promptAtRecording.contains("SecondVocabImage"), "the prompt is current when recording starts: " + fake.promptAtRecording);
        assertEquals(fake.prompt, ws.commandBar().lastVocabularyPrompt);
    }
}
