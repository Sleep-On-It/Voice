package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.FlatClientProperties;

import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.nl.NlService;
import sc.fidelis.speech.SpeechService;

/**
 * The command bar (docs D12 UI-11, D14 C-02, C-11): one field with two explicit outcomes, a ranked
 * list of catalogue commands and a final "Interpret as instruction" row; plus the microphone
 * toggle bound to {@link SpeechService}. Nothing is guessed silently: the user picks the row.
 */
public final class CommandBar extends JPanel {
    private enum Kind { COMMAND, INSTRUCTION }

    private record Row(Kind kind, CommandSchema.Summary summary, String text) {}

    private final UiContext ctx;
    private final JTextField field = new JTextField();
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JPopupMenu popup = new JPopupMenu();
    private final JToggleButton mic = new JToggleButton("Mic");
    private final StatusBar.RecordingIndicator recording = new StatusBar.RecordingIndicator();
    private final Timer debounce;
    private final Timer recordingClock;
    private long recordingStart;
    private boolean suppressSearch;
    private boolean spoken;
    private SpeechService speech;
    private SpeechService.State speechState = SpeechService.State.UNAVAILABLE;
    private SpeechService.Listener speechListener;
    private volatile Timer interpretClock;

    CommandBar(UiContext ctx) {
        super(new BorderLayout(8, 0));
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search commands or type an instruction…");
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        field.setToolTipText("Type to search Fiji commands; Enter runs the highlighted row. Press l in an image to focus this field.");
        field.getAccessibleContext().setAccessibleName("Command search and instruction");
        field.setEnabled(false);

        list.setCellRenderer(renderer());
        list.setFocusable(false);
        list.setVisibleRowCount(9);
        list.setFixedCellHeight(24);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    list.setSelectedIndex(i);
                    activate();
                }
            }
        });
        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(Theme.panelBorder()));
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        sp.setFocusable(false);
        popup.add(sp);

        debounce = new Timer(80, e -> refreshResults());
        debounce.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                if (!suppressSearch) debounce.restart();
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        move(1);
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
                        move(-1);
                        e.consume();
                    }
                    case KeyEvent.VK_ENTER -> {
                        activate();
                        e.consume();
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        if (popup.isVisible() || !field.getText().isEmpty()) {
                            hidePopup();
                            field.setText("");
                        } else if (ctx.documents().focusedView() != null) {
                            ctx.documents().focusedView().canvas().requestFocusInWindow();
                        }
                        e.consume();
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() != '\n') spoken = false;
            }
        });

        mic.setFocusable(false);
        mic.addActionListener(e -> toggleMic());
        recording.setToolTipText("Recording from the microphone");
        recordingClock = new Timer(500, e -> updateRecordingText());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(recording);
        right.add(mic);
        add(field, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
        bindSpeech(ServiceRegistry.speech());
        updateMic();
    }

    void focusField() {
        field.requestFocusInWindow();
        field.selectAll();
    }

    String text() {
        return field.getText();
    }

    /** Test and screenshot hook: set the text as if typed. */
    void setText(String s) {
        field.setText(s);
    }

    void setEngineState(EngineState s) {
        boolean ready = s == EngineState.READY;
        field.setEnabled(ready);
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                ready ? "Search commands or type an instruction…" : "Engine starting… commands are available when it is ready");
        updateMic();
    }

    // ---- search -------------------------------------------------------------------------------

    private void refreshResults() {
        String q = field.getText().trim();
        if (q.isEmpty() || !field.isEnabled()) {
            hidePopup();
            return;
        }
        List<CommandSchema.Summary> hits = ctx.engine.search(q, 8);
        model.clear();
        for (CommandSchema.Summary s : hits) model.addElement(new Row(Kind.COMMAND, s, q));
        model.addElement(new Row(Kind.INSTRUCTION, null, q));
        list.setSelectedIndex(looksLikeInstruction(q, hits) ? model.size() - 1 : 0);
        showPopup();
    }

    /** No command match, or a multi-word phrase whose first hit is not a prefix of what was typed. */
    static boolean looksLikeInstruction(String q, List<CommandSchema.Summary> hits) {
        if (hits.isEmpty()) return true;
        int words = q.trim().split("\\s+").length;
        if (words < 3) return false;
        String first = hits.get(0).label().toLowerCase(java.util.Locale.ROOT);
        return !q.toLowerCase(java.util.Locale.ROOT).startsWith(first.replace("...", "").trim());
    }

    private void showPopup() {
        int w = Math.min(Math.max(field.getWidth() / 2, 520), 680);
        list.setPreferredSize(null);
        int rows = Math.min(model.size(), 9);
        popup.setPreferredSize(new Dimension(w, rows * 24 + 4));
        if (!popup.isVisible()) popup.show(field, 0, field.getHeight() + 2);
        else {
            popup.pack();
            popup.revalidate();
        }
        field.requestFocusInWindow();
    }

    private void hidePopup() {
        popup.setVisible(false);
    }

    private void move(int d) {
        if (model.isEmpty()) {
            refreshResults();
            return;
        }
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + d));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void activate() {
        Row row = list.getSelectedValue();
        String text = field.getText().trim();
        if (row == null) {
            if (text.isEmpty()) return;
            // Enter before the list appeared: run the best command match, else interpret
            List<CommandSchema.Summary> hits = ctx.engine.search(text, 8);
            row = hits.isEmpty() || looksLikeInstruction(text, hits)
                    ? new Row(Kind.INSTRUCTION, null, text) : new Row(Kind.COMMAND, hits.get(0), text);
        }
        hidePopup();
        if (row.kind() == Kind.COMMAND) {
            suppressSearch = true;
            field.setText("");
            suppressSearch = false;
            ctx.runCommand(row.summary().commandId(), Origin.SEARCH);
        } else {
            interpret(text, spoken ? Origin.NL_SPOKEN : Origin.NL_TYPED);
        }
    }

    private ListCellRenderer<Row> renderer() {
        return (l, row, index, selected, focus) -> {
            JPanel p = new JPanel(new BorderLayout(10, 0));
            p.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            p.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            JLabel main = new JLabel();
            JLabel path = new JLabel();
            if (row.kind() == Kind.COMMAND) {
                main.setText(row.summary().label().trim());
                main.setFont(main.getFont().deriveFont(Font.BOLD));
                path.setText(row.summary().menuPath() + (row.summary().shortcut() != null ? "   " + row.summary().shortcut() : ""));
            } else {
                main.setText("Interpret as instruction: “" + row.text() + "”");
                main.setFont(main.getFont().deriveFont(Font.ITALIC));
                path.setText(ServiceRegistry.nl() == null ? "interpreter not available" : "preview before anything runs");
            }
            Component fgSource = l;
            main.setForeground(selected ? l.getSelectionForeground() : fgSource.getForeground());
            path.setForeground(selected ? l.getSelectionForeground() : Theme.muted());
            p.add(main, BorderLayout.CENTER);
            p.add(path, BorderLayout.EAST);
            return p;
        };
    }

    // ---- natural language ---------------------------------------------------------------------

    ProposalSheet interpret(String text, Origin origin) {
        NlService nl = ServiceRegistry.nl();
        ProposalSheet sheet = new ProposalSheet(ctx, text, origin);
        ctx.banners().setSheet(sheet);
        if (nl == null) {
            sheet.showUnavailable();
            ctx.notice("Natural-language interpreter not available", true);
            return sheet;
        }
        sheet.showBusy();
        final long t0 = System.nanoTime();
        Timer clock = new Timer(200, e -> sheet.showBusy((System.nanoTime() - t0) / 1e9));
        interpretClock = clock;
        clock.start();
        CompletableFuture.supplyAsync(() -> nl.interpret(text, origin, ctx.focusedId()))
                .whenComplete((p, ex) -> SwingUtilities.invokeLater(() -> {
                    clock.stop();
                    if (ex != null) sheet.showError(String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
                    else sheet.showProposal(p, (System.nanoTime() - t0) / 1e9);
                }));
        return sheet;
    }

    // ---- speech -------------------------------------------------------------------------------

    /** Attach a speech service that became available after start-up (call on the EDT). */
    void attachSpeech(SpeechService s) {
        bindSpeech(s);
        updateMic();
    }

    private String lastTranscriptId;

    /** Put a transcript in the field for review; nothing runs from speech directly. Duplicates are ignored. */
    private void deliver(SpeechService.Transcript t) {
        if (t != null && t.id() != null && t.id().equals(lastTranscriptId)) return;
        if (t != null) lastTranscriptId = t.id();
        if (t == null || !t.speechDetected() || t.text() == null || t.text().isBlank()) {
            ctx.notice("No speech detected", false);
            return;
        }
        suppressSearch = false;
        field.setText(t.text().trim());
        spoken = true;
        focusField();
        ctx.notice("Transcript ready: review it, then press Enter to interpret", false);
    }

    /** The last vocabulary prompt handed to the speech service (test hook). */
    volatile String lastVocabularyPrompt;

    /**
     * Bias the next transcription with command labels and the open image names (D08): called when a speech service
     * attaches, on every DocumentsChanged and before each recording.
     */
    void refreshVocabulary() {
        SpeechService s = speech;
        NlService nl = ServiceRegistry.nl();
        if (s == null || !(nl instanceof sc.fidelis.nl.DefaultNlService d)) return;
        try {
            String prompt = d.vocabularyPrompt(ctx.engine.documents().stream().map(sc.fidelis.engine.api.DocumentInfo::title).toList());
            lastVocabularyPrompt = prompt;
            s.setVocabularyPrompt(prompt);
        } catch (RuntimeException ex) {
            System.err.println("[fidelis] vocabulary prompt not set: " + ex);
        }
    }

    private void bindSpeech(SpeechService s) {
        detachSpeech();
        speech = s;
        if (s == null) return;
        refreshVocabulary();
        if (s instanceof sc.fidelis.speech.WhisperSpeechService w) {
            // auto-stopped recordings (30 s limit) deliver their transcript only through this callback
            w.onTranscript(t -> SwingUtilities.invokeLater(() -> deliver(t)));
        }
        speechState = s.state();
        speechListener = new SpeechService.Listener() {
            @Override
            public void stateChanged(SpeechService.State state) {
                SwingUtilities.invokeLater(() -> {
                    speechState = state;
                    updateMic();
                });
            }
        };
        s.addListener(speechListener);
    }

    /** Stop listening to the speech service (a disposed workspace must not keep receiving its events, #16). */
    void detachSpeech() {
        if (speech != null && speechListener != null) speech.removeListener(speechListener);
        speechListener = null;
    }

    void toggleMic() {
        if (speech == null) return;
        if (speechState == SpeechService.State.READY) {
            try {
                refreshVocabulary();
                speech.startRecording();
            } catch (IllegalStateException ex) {
                ctx.notice("Cannot record: " + ex.getMessage(), true);
            }
        } else if (speechState == SpeechService.State.RECORDING) {
            speech.stopRecording().whenComplete((t, ex) -> SwingUtilities.invokeLater(() -> {
                if (ex != null) {
                    ctx.notice("Transcription failed: " + (ex.getCause() != null ? ex.getCause() : ex), true);
                } else {
                    deliver(t);
                }
            }));
        }
    }

    private void updateMic() {
        boolean engineReady = ctx.engine.state() == EngineState.READY;
        String tip;
        boolean enabled;
        if (speech == null) {
            enabled = false;
            tip = "Voice unavailable: the speech service is not installed in this build";
        } else {
            switch (speechState) {
                case UNAVAILABLE -> {
                    enabled = false;
                    String r = speech.unavailableReason();
                    tip = "Voice unavailable" + (r == null || r.isBlank() ? "" : ": " + r);
                }
                case STARTING -> {
                    enabled = false;
                    tip = "Starting the microphone…";
                }
                case READY -> {
                    enabled = engineReady;
                    tip = "Start recording (the transcript appears here for review; nothing runs from speech directly)";
                }
                case RECORDING -> {
                    enabled = true;
                    tip = "Stop recording and transcribe";
                }
                default -> {
                    enabled = false;
                    tip = "Transcribing…";
                }
            }
        }
        mic.setEnabled(enabled);
        mic.setSelected(speechState == SpeechService.State.RECORDING);
        mic.setToolTipText(tip);
        mic.setText(speechState == SpeechService.State.RECORDING ? "Stop" : "Mic");
        mic.getAccessibleContext().setAccessibleDescription(tip);
        boolean rec = speech != null && speechState == SpeechService.State.RECORDING;
        if (rec && !recordingClock.isRunning()) {
            recordingStart = System.nanoTime();
            recordingClock.start();
        } else if (!rec) {
            recordingClock.stop();
        }
        if (rec) updateRecordingText();
        else {
            recording.setRecording(false, "");
            ctx.status().recording().setRecording(false, "");
        }
    }

    private void updateRecordingText() {
        long s = (System.nanoTime() - recordingStart) / 1_000_000_000L;
        String t = "Recording " + s + " s / 30 s";
        recording.setRecording(true, t);
        ctx.status().recording().setRecording(true, t);
    }

    /** Whether the persistent recording indicator is showing (test hook). */
    boolean isRecordingIndicatorVisible() {
        return recording.isRecording();
    }

    JToggleButton micButton() {
        return mic;
    }

    boolean popupVisible() {
        return popup.isVisible();
    }

    List<String> visibleRowLabels() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            Row r = model.get(i);
            out.add(r.kind() == Kind.COMMAND ? r.summary().label() : "Interpret as instruction");
        }
        return out;
    }
}
