import AppKit
import os
import SwiftUI
import Foundation
import LumaPresentation

struct Activity: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let time = Date()
    var isError = false
    var icon = "checkmark.circle.fill"
    /// Raw tool result retained for rich rendering (workflow scripts, class tables). Display only.
    var payload: [String: Any]? = nil
}

@MainActor
final class CompanionModel: ObservableObject {
    @Published var connected = false
    @Published var bridgeConnected = false
    @Published var imageName = "No image open"
    @Published var selectionLabel = "Select a region"
    @Published var activities: [Activity] = []
    @Published var context: [String: Any] = [:]
    @Published var voiceReason = "Checking voice connection…"
    @Published var voiceActive = false
    @Published var voiceReady = false
    /// Display name of the scientific application the gateway is driving (QuPath, Fiji, …), from /health.
    @Published var appName = "QuPath"
    @Published var application = CompanionApplication.qupath
    @Published var voiceStarting = false
    @Published var voiceSpeaking = false
    @Published var textReady = false
    @Published var textBusy = false
    @Published var eventsReady = false
    @Published var textSending = false
    @Published var textReason = "Text needs verified AWS credits."
    @Published var input = ""
    @Published var knowledgeQuery = ""
    @Published var knowledgeResults: [[String: Any]] = []
    @Published var selectedTab = 0
    @Published var expanded = false
    @Published var detailsHeight: CGFloat = 600
    @Published var runningActionIDs = Set<String>()
    @Published var busy = false
    @Published var microphoneLevel: Float = 0
    @Published var measurementReport: MeasurementReport?
    /// Latest partial transcript while the microphone is live; cleared on the final transcript or when voice stops.
    @Published var liveTranscript = ""
    /// Short acknowledgement phrase (text_ack) shown while the task runs; cleared on result, error or idle.
    @Published var executingText = ""
    /// Final sentence of the last task, shown briefly in the bar after it finishes.
    @Published var resultText = ""
    @Published var resultIsError = false
    var canStopTask: Bool { textBusy || voiceSpeaking }
    var voiceOn: Bool { voiceActive || voiceStarting }
    var canStartVoice: Bool { connected && eventsReady && voiceReady }
    private var resultClear: Task<Void, Never>?
    /// Pending (review-mode) action IDs already shown as an activity line; the bar never surfaces approvals.
    private var notedPendingActionIDs = Set<String>()
    /// True from the researcher's Start until their Stop or a voice error. While true, a session the gateway ends on
    /// its own (stream time limit, speech stream closed) is replaced immediately without another click.
    /// Once the researcher turns voice on it stays wanted across restarts and reconnects until they press Stop.
    private var voiceWanted = UserDefaults.standard.bool(forKey: "luma.voiceWanted") {
        didSet { UserDefaults.standard.set(voiceWanted, forKey: "luma.voiceWanted") }
    }
    private let root: URL
    private var token = ""
    private var gateway: GatewaySupervisor?
    private var socket: URLSessionWebSocketTask?
    private let clientID = UUID().uuidString
    private var polling: Task<Void, Never>?
    private var receiving: Task<Void, Never>?
    private var reconnect: Task<Void, Never>?
    private var lastDispatchFailure = ""
    private var stopping = false
    private var voiceSessionID: String?
    private var voiceRequestID: String?
    private var providerListening = false
    private var capturing = false
    private var playbackEpoch = 0
    private var playbackSuppressed = false
    private var audioPending: [Data] = []
    private var audioPendingBytes = 0
    private var audioSending: Task<Void, Never>?
    private var micChunks = 0
    private var micPeak: Float = 0
    private let session = URLSession(configuration: .ephemeral)
    let pointer = PointerOverlay()
    /// Desktop-level control (Accessibility + synthetic input) answering the gateway's desktop_request events.
    let desktop = DesktopControl()
    let audio = AudioIO()

    init() {
        let path = ProcessInfo.processInfo.environment["LUMA_ROOT"]
            ?? Bundle.main.object(forInfoDictionaryKey: "LumaRoot") as? String
            ?? FileManager.default.currentDirectoryPath
        root = URL(fileURLWithPath: path)
        gateway = GatewaySupervisor(root: root)
    }
    func start() {
        stopping = false
        polling?.cancel()
        polling = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(for: .seconds(2))
            }
        }
        connectEvents()
    }
    func stop() {
        stopping = true
        textBusy = false
        eventsReady = false
        polling?.cancel(); receiving?.cancel(); reconnect?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        audio.stop()
        resetVoice()
        gateway?.stop()
    }
    private func loadToken() throws {
        token = try String(contentsOf: root.appendingPathComponent(".local/state/companion-token"), encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private func request(_ path: String, body: [String: Any]? = nil) async throws -> [String: Any] {
        if token.isEmpty { try loadToken() }
        var request = URLRequest(url: URL(string: "http://127.0.0.1:49555\(path)")!)
        request.timeoutInterval = 130
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await session.data(for: request)
        guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw CompanionError.message("Invalid gateway response") }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw CompanionError.message(result["error"] as? String ?? "Gateway request failed") }
        return result
    }
    func refresh() async {
        do {
            let health = try await request("/health")
            connected = true
            bridgeConnected = health["bridgeError"] == nil
            voiceReason = (health["voice"] as? [String: Any])?["reason"] as? String ?? "Voice funding has not been verified."
            voiceReady = (health["voice"] as? [String: Any])?["enabled"] as? Bool ?? false
            application = CompanionApplication(id: (health["app"] as? [String: Any])?["id"] as? String)
            if let name = (health["app"] as? [String: Any])?["displayName"] as? String, !name.isEmpty { appName = name }
            // Outlines are clipped to this application's windows (PointerOverlay).
            if let bundle = (health["app"] as? [String: Any])?["bundleId"] as? String, !bundle.isEmpty { pointer.appBundleID = bundle }
            armVoiceIfWanted()
            let text = health["text"] as? [String: Any] ?? [:]
            textReady = text["configured"] as? Bool ?? false
            textReason = text["reason"] as? String ?? "Text needs verified AWS credits."
            context = health["context"] as? [String: Any] ?? [:]
            if let dispatch = context["lastDispatch"] as? [String: Any], dispatch["status"] as? String == "failed",
               let id = dispatch["id"] as? String, id != lastDispatchFailure {
                lastDispatchFailure = id
                activities.insert(Activity(title: "\(appName) action stopped", detail: dispatch["error"] as? String ?? "Review the action again.", isError: true), at: 0)
            }
            imageName = application.imageName(context: context)
            selectionLabel = application.selectionLabel(context: context)
            let actions = health["actions"] as? [[String: Any]] ?? []
            runningActionIDs = Set(actions.filter { $0["status"] as? String == "running" }.compactMap { $0["id"] as? String })
            for action in actions where action["status"] as? String == "pending" { notePendingAction(action) }
        } catch {
            // HTTP polling can fail while the task's WebSocket is still alive; retain Stop.
            connected = false; bridgeConnected = false; textReady = false
            voiceReason = "Starting the local gateway…"
            if !stopping {
                do { try gateway?.ensureRunning() }
                catch { voiceReason = "Gateway unavailable: \(error.localizedDescription)" }
            }
        }
    }
    private func connectEvents() {
        guard !stopping else { return }
        eventsReady = false
        do {
            try loadToken()
            var request = URLRequest(url: URL(string: "ws://127.0.0.1:49555/events")!)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let connection = session.webSocketTask(with: request)
            receiving?.cancel()
            socket?.cancel(with: .goingAway, reason: nil)
            socket = connection
            // Large tool results (dialog inspections, workflow scripts) must not kill the socket: the default limit is 1 MiB.
            connection.maximumMessageSize = 16 * 1024 * 1024
            connection.resume()
            // A stable id lets the gateway hand the same conversation back after a reconnect instead of dropping the running task.
            if let hello = try? JSONSerialization.data(withJSONObject: ["type": "hello", "clientId": clientID]) {
                connection.send(.string(String(decoding: hello, as: UTF8.self))) { _ in }
            }
            receiving = Task { [weak self] in
                do {
                    while !Task.isCancelled {
                        let message = try await connection.receive()
                        let data: Data
                        switch message { case .string(let text): data = Data(text.utf8); case .data(let bytes): data = bytes; @unknown default: continue }
                        guard let self, self.socket === connection else { return }
                        // A malformed event is logged and skipped; it must never trigger a reconnect.
                        guard let event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                            Logger(subsystem: "science.luma.companion", category: "events").error("Ignored unparseable gateway event (\(data.count) bytes)")
                            continue
                        }
                        self.receive(event)
                    }
                } catch {
                    guard let self, self.socket === connection else { return }
                    Logger(subsystem: "science.luma.companion", category: "events").error("Gateway socket closed: \(error.localizedDescription); reconnecting")
                    self.eventsReady = false; self.textBusy = false; self.resetVoice(); self.scheduleReconnect()
                }
            }
        } catch { scheduleReconnect() }
    }
    private func scheduleReconnect() {
        guard !stopping else { return }
        reconnect?.cancel()
        reconnect = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            self?.connectEvents()
        }
    }
    private func receive(_ event: [String: Any]) {
        switch event["type"] as? String {
        case "connected": eventsReady = true; armVoiceIfWanted()
        case "desktop_request":
            guard let requestID = event["requestId"] as? String else { return }
            let op = event["op"] as? String ?? "", args = event["args"] as? [String: Any] ?? [:]
            desktop.show = { [weak self] highlight in self?.pointer.highlight(highlight) }
            Task { [weak self] in
                guard let self else { return }
                do { let result = try await self.desktop.perform(op: op, args: args); self.send(["type": "desktop_response", "requestId": requestID, "result": result]) }
                catch { self.send(["type": "desktop_response", "requestId": requestID, "error": error.localizedDescription]) }
            }
        case "tool":
            let name = event["name"] as? String ?? "Action"
            if name == "qupath_context" || name == "fiji_context" { Task { await refresh() }; return }
            let result = event["result"] as? [String: Any] ?? [:]
            let nested = result["result"] as? [String: Any] ?? result
            if name == "qupath_measurement_summary", let data = try? JSONSerialization.data(withJSONObject: nested),
               let report = try? JSONDecoder().decode(MeasurementReport.self, from: data) {
                measurementReport = report
            }
            let failure = ToolFeedback.failure(result)
            activities.insert(Activity(title: failure == nil ? friendly(name) : "Couldn’t finish \(friendly(name).lowercased())", detail: failure ?? summary(nested, toolName: name), isError: failure != nil, payload: richPayload(nested)), at: 0)
            if failure == nil {
                if let highlight = nested["highlight"] as? [String: Any] { self.pointer.highlight(highlight) }
                else if let pointer = nested["pointer"] as? [String: Any], let x = pointer["screenX"] as? Double, let y = pointer["screenY"] as? Double { self.pointer.show(x: x, y: y, label: "Right here") }
            }
            activities = Array(activities.prefix(40))
            Task { await refresh() }
        case "action":
            if let action = event["action"] as? [String: Any], let id = action["id"] as? String {
                if action["status"] as? String == "running" { runningActionIDs.insert(id) }
                else { runningActionIDs.remove(id) }
            }
            if let action = event["action"] as? [String: Any], action["status"] as? String == "pending" { notePendingAction(action) }
            if let action = event["action"] as? [String: Any], let status = action["status"] as? String, ["failed", "denied", "completed", "expired"].contains(status) {
                let tool = friendly(action["tool"] as? String ?? "Action")
                let warning = action["auditWarning"] as? String
                // A completed action is reported by its tool event as a done thing; only problems need their own entry.
                if status != "completed" || warning != nil {
                    let title = status == "failed" ? "Couldn’t finish" : status == "denied" ? "Declined" : status == "expired" ? "Expired" : tool
                    let detail = action["error"] as? String ?? tool
                    activities.insert(Activity(title: title, detail: detail + (warning.map { "\n" + $0 } ?? ""), isError: status == "failed" || warning != nil), at: 0)
                    activities = Array(activities.prefix(40))
                }
            }
            Task { await refresh() }
        case "error":
            if let requestID = event["requestId"] as? String, requestID != voiceRequestID { return }
            let message = event["message"] as? String ?? "Unknown error"
            activities.insert(Activity(title: "Needs attention", detail: message, isError: true), at: 0)
            showResult(message, isError: true)
            voiceWanted = false
            resetVoice()
        case "text_ack":
            if let text = event["text"] as? String, !text.isEmpty { executingText = text; textBusy = true; resultClear?.cancel(); resultText = ""; resultIsError = false }
        case "text_result":
            if let text = event["text"] as? String {
                let failed = event["outcome"] as? String == "failed" || event["isError"] as? Bool == true
                activities.insert(Activity(title: "Penny", detail: text, isError: failed, icon: "sparkle"), at: 0)
                activities = Array(activities.prefix(40))
                showResult(text, isError: failed)
            }
        case "text_progress":
            if let text = event["text"] as? String {
                activities.insert(Activity(title: "Penny", detail: text, icon: "clock"), at: 0)
                activities = Array(activities.prefix(40))
            }
        case "text_error":
            textBusy = false; executingText = ""
            let message = event["message"] as? String ?? "Inspect the application before retrying."
            activities.insert(Activity(title: "Task stopped", detail: message, isError: true), at: 0)
            showResult(message, isError: true)
        case "task_state":
            textBusy = event["state"] as? String == "working"
            if !textBusy { executingText = ""; if !resultText.isEmpty { scheduleResultClear() } }
        case "audio":
            if event["epoch"] as? Int != playbackEpoch || playbackSuppressed {
                Logger(subsystem: "science.luma.companion", category: "audio").notice("Ignored reply audio: epoch \(String(describing: event["epoch"])) vs \(self.playbackEpoch), suppressed=\(self.playbackSuppressed)")
            }
            guard event["requestId"] as? String == voiceRequestID, event["sessionId"] as? String == voiceSessionID, voiceSessionID != nil,
                  let epoch = event["epoch"] as? Int, epoch == playbackEpoch, !playbackSuppressed,
                  event["sampleRate"] as? Int == 24000, let base64 = event["audio"] as? String,
                  let data = Data(base64Encoded: base64) else { return }
            let final = event["final"] as? Bool == true
            audio.play(pcm: data, completion: final ? { [weak self] in Task { @MainActor in
                guard let self, self.voiceSessionID == event["sessionId"] as? String, self.playbackEpoch == epoch else { return }
                self.voiceSpeaking = false
            }} : nil)
        case "interrupted":
            guard event["requestId"] as? String == voiceRequestID, event["sessionId"] as? String == voiceSessionID else { return }
            playbackEpoch = event["epoch"] as? Int ?? playbackEpoch
            playbackSuppressed = false; voiceSpeaking = false; audio.interruptPlayback()
            // A spoken Stop is a transport event, so no later answer will replace an old success sentence.
            resultClear?.cancel(); resultText = ""; resultIsError = false; executingText = ""
        case "voice_speaking":
            guard event["requestId"] as? String == voiceRequestID, event["sessionId"] as? String == voiceSessionID, let epoch = event["epoch"] as? Int, epoch >= playbackEpoch else { return }
            playbackEpoch = epoch; playbackSuppressed = false; voiceSpeaking = true
            // A queued final answer follows its acknowledgment; anything else replaces current speech.
            if event["queued"] as? Bool != true { audio.interruptPlayback() }
        case "voice_transcript":
            guard event["requestId"] as? String == voiceRequestID, event["sessionId"] as? String == voiceSessionID else { return }
            // The gateway also observes room speech rejected by its wake-word gate; that is not a new user request.
            if event["addressed"] as? Bool == false { liveTranscript = ""; return }
            if event["partial"] as? Bool == false, let text = event["text"] as? String {
                liveTranscript = ""
                activities.insert(Activity(title: "You", detail: text, icon: "mic"), at: 0)
                activities = Array(activities.prefix(40))
            } else if let text = event["text"] as? String {
                liveTranscript = text
            }
        case "voice_error":
            guard event["requestId"] as? String == voiceRequestID else { return }
            Logger(subsystem: "science.luma.companion", category: "audio").error("Voice error from gateway: \(event["message"] as? String ?? "unknown")")
            let message = event["message"] as? String ?? "Speech is unavailable."
            activities.insert(Activity(title: "Voice needs attention", detail: message, isError: true), at: 0)
            voiceWanted = false
            // A refused Start has no session that could send an idle event; clear its connecting state here.
            if voiceSessionID == nil { resetVoice() }
            showResult(message, isError: true)
            expanded = true
        case "voice_state":
            guard let requestID = event["requestId"] as? String else { return }
            let state = event["state"] as? String
            if requestID != voiceRequestID {
                // The gateway confirmed the end of a session this companion already dropped (audio backlog): voice is still
                // wanted and nothing else is starting, so re-arm it now that the gateway's voice slot is free.
                if state == "idle", voiceRequestID == nil, voiceWanted { armVoiceIfWanted() }
                return
            }
            if state == "connecting", let id = event["sessionId"] as? String { resetVoice(keepingRequest: true); voiceSessionID = id; voiceStarting = true }
            else if state == "ready_for_audio", let id = event["sessionId"] as? String, id == voiceSessionID { startMicrophone(sessionID: id) }
            else if state == "listening", event["sessionId"] as? String == voiceSessionID { providerListening = true; voiceActive = capturing; voiceStarting = !capturing; pointer.follow(true) }
            else if state == "idle", event["sessionId"] == nil || event["sessionId"] as? String == voiceSessionID {
                let reason = event["reason"] as? String ?? ""
                resetVoice()
                if voiceWanted && Self.isGatewayEndedSession(reason) {
                    Logger(subsystem: "science.luma.companion", category: "audio").notice("Gateway ended the voice session (\(reason)); starting a new one")
                    startVoice()
                }
            }
        default: break
        }
    }
    func call(_ name: String, _ arguments: [String: Any] = [:]) async {
        busy = true
        defer { busy = false }
        do {
            _ = try await request("/tools/call", body: ["name": name, "arguments": arguments])
            await refresh()
        } catch { activities.insert(Activity(title: "Couldn’t complete action", detail: error.localizedDescription, isError: true), at: 0) }
    }
    /// Review mode (`LUMA_APPROVALS=review`) parks an action on the gateway; the companion only journals it once.
    private func notePendingAction(_ action: [String: Any]) {
        guard let id = action["id"] as? String, !notedPendingActionIDs.contains(id) else { return }
        notedPendingActionIDs.insert(id)
        activities.insert(Activity(title: friendly(action["tool"] as? String ?? "Action"), detail: "Waiting on the gateway’s review setting.", icon: "clock"), at: 0)
        activities = Array(activities.prefix(40))
    }
    func search() async {
        guard knowledgeQuery.count >= 2 else { return }
        do {
            let response = try await request("/tools/call", body: ["name": application.knowledgeTool, "arguments": ["query": knowledgeQuery]])
            knowledgeResults = (response["result"] as? [String: Any])?["results"] as? [[String: Any]] ?? []
        } catch { activities.insert(Activity(title: "Search unavailable", detail: error.localizedDescription, isError: true), at: 0) }
    }
    func sendText() {
        let draft = input
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        guard !textSending else { return }
        guard text.utf16.count <= 4000 else {
            activities.insert(Activity(title: "Message too long", detail: "Use a message of up to 4000 characters.", isError: true), at: 0)
            return
        }
        guard textReady else {
            activities.insert(Activity(title: "Text setup needed", detail: textReason, isError: true), at: 0)
            return
        }
        guard eventsReady, let connection = socket else {
            activities.insert(Activity(title: "Reconnecting", detail: "Your draft is kept. Wait for the conversation connection.", isError: true), at: 0)
            return
        }
        textSending = true
        Task {
            defer { textSending = false }
            do {
                let data = try JSONSerialization.data(withJSONObject: ["type": "text", "text": text])
                try await connection.send(.string(String(decoding: data, as: UTF8.self)))
                if input == draft { input = "" }
                selectedTab = 0
                activities.insert(Activity(title: "You", detail: text, icon: "bubble.right"), at: 0)
                activities = Array(activities.prefix(40))
            } catch {
                activities.insert(Activity(title: "Delivery unconfirmed", detail: "Your draft is kept. Inspect the session before retrying.", isError: true), at: 0)
            }
        }
    }
    func stopTextTask() {
        send(["type": "stop_task"])
        playbackSuppressed = true; voiceSpeaking = false; audio.interruptPlayback()
        textBusy = false; executingText = ""
        resultClear?.cancel(); resultText = ""; resultIsError = false
    }
    /// Keep the final sentence in the bar for a few seconds, then fall back to Listening or Idle.
    private func showResult(_ text: String, isError: Bool = false) {
        executingText = ""
        resultText = text
        resultIsError = isError
        scheduleResultClear()
    }
    private func scheduleResultClear() {
        resultClear?.cancel()
        resultClear = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            self?.resultText = ""; self?.resultIsError = false
        }
    }
    /// Results worth rendering beyond one line: saved/generated workflow scripts and per-class counts.
    private func richPayload(_ value: [String: Any]) -> [String: Any]? {
        if let workflow = value["workflow"] as? [String: Any], workflow["script"] is String || workflow["steps"] != nil { return ["workflow": workflow] }
        if let classes = classRows(value) { return ["classes": classes, "total": value["total"] ?? (value["summary"] as? [String: Any])?["total"] ?? NSNull()] }
        return nil
    }
    func classRows(_ value: [String: Any]) -> [[String: Any]]? {
        if let rows = value["classes"] as? [[String: Any]], !rows.isEmpty { return rows }
        if let summary = value["summary"] as? [String: Any], let rows = summary["classes"] as? [[String: Any]], !rows.isEmpty { return rows }
        if let rows = value["summary"] as? [[String: Any]], !rows.isEmpty { return rows }
        return nil
    }
    func toggleVoice() {
        if voiceActive || voiceStarting || voiceSessionID != nil { stopVoice() }
        else { startVoice() }
    }
    private func startVoice() {
        voiceWanted = true
        // Not ready yet (gateway or funding still connecting): armVoiceIfWanted() starts it as soon as both are.
        guard voiceReady && eventsReady else { return }
        let id = UUID().uuidString; voiceRequestID = id; voiceStarting = true
        send(["type": "start_voice", "requestId": id])
    }
    /// Hands-free: re-arm the microphone after a companion/gateway restart or reconnect when voice was left on.
    private func armVoiceIfWanted() {
        guard voiceWanted, voiceReady, eventsReady, voiceSessionID == nil, !voiceStarting, voiceRequestID == nil else { return }
        startVoice()
    }
    /// The gateway closes a session itself at the speech stream's time limit or when the stream closes; neither is a user stop.
    static func isGatewayEndedSession(_ reason: String) -> Bool {
        let lower = reason.lowercased()
        // Restarts and reconnects end sessions from the gateway side too; none of these is the researcher pressing Stop.
        return lower.contains("time limit") || lower.contains("speech stream closed") || lower.contains("gateway closing") || lower.contains("disconnected")
    }
    /// User stop or a local failure (microphone, audio backlog, socket): voice stays off until the next Start.
    private func stopVoice() {
        voiceWanted = false
        if let id = voiceRequestID { send(["type": "stop_voice", "requestId": id]) }
        resetVoice()
    }
    private func resetVoice(keepingRequest: Bool = false) {
        if !keepingRequest { voiceRequestID = nil }
        voiceSessionID = nil; capturing = false; providerListening = false
        voiceActive = false; voiceStarting = false; voiceSpeaking = false; microphoneLevel = 0; liveTranscript = ""
        pointer.follow(false)
        audioSending?.cancel(); audioSending = nil; audioPending.removeAll(); audioPendingBytes = 0
        audio.stop(); playbackEpoch = 0; playbackSuppressed = false
    }
    private func startMicrophone(sessionID: String) {
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.audio.start { [weak self] data, level in Task { @MainActor in
                    guard let self, self.voiceSessionID == sessionID else { return }
                    // Without echo cancellation, send silence while Penny speaks so its own voice is not transcribed.
                    let muted = self.voiceSpeaking && !self.audio.echoCancelled
                    self.microphoneLevel = muted ? 0 : level
                    self.micChunks += 1; self.micPeak = max(self.micPeak, level)
                    if self.micChunks % 48 == 1 {
                        Logger(subsystem: "science.luma.companion", category: "audio").info("Microphone: \(self.micChunks) chunks captured, peak level \(self.micPeak), pending \(self.audioPendingBytes) bytes, socket \(self.socket == nil ? "missing" : "open")")
                        self.micPeak = 0
                    }
                    self.enqueueAudio(muted ? Data(count: data.count) : data, sessionID: sessionID)
                }}
                guard self.voiceSessionID == sessionID else { return }
                self.capturing = true; self.voiceActive = self.providerListening; self.voiceStarting = !self.providerListening
            } catch {
                guard self.voiceSessionID == sessionID else { return }
                self.stopVoice()
                self.activities.insert(Activity(title: "Microphone unavailable", detail: error.localizedDescription, isError: true), at: 0)
                self.expanded = true
            }
        }
    }
    private func enqueueAudio(_ data: Data, sessionID: String) {
        guard audioPendingBytes + data.count <= 48000 else {
            // A stalled socket, not a user decision. End the session on the gateway too: it keeps holding voice for this
            // request otherwise, and silently ignores the next start_voice, so the bar stays Connecting. The gateway's
            // idle for this (dropped) request re-arms voice (see voice_state below); voiceWanted stays true.
            if let id = voiceRequestID { send(["type": "stop_voice", "requestId": id]) }
            resetVoice()
            activities.insert(Activity(title: "Voice restarting", detail: "Audio could not be sent in time; reconnecting.", isError: true), at: 0)
            return
        }
        audioPending.append(data); audioPendingBytes += data.count
        guard audioSending == nil, let connection = socket else { return }
        audioSending = Task { [weak self] in
            guard let self else { return }
            defer { if self.voiceSessionID == sessionID { self.audioSending = nil } }
            while !Task.isCancelled && self.voiceSessionID == sessionID && !self.audioPending.isEmpty {
                let chunk = self.audioPending.removeFirst(); self.audioPendingBytes -= chunk.count
                do {
                    let payload = try JSONSerialization.data(withJSONObject: ["type": "audio", "sessionId": sessionID, "audio": chunk.base64EncodedString()])
                    try await connection.send(.string(String(decoding: payload, as: UTF8.self)))
                } catch {
                    // The socket went away (gateway restart/reconnect): drop this session but keep voice wanted,
                    // so armVoiceIfWanted() starts a fresh one when the gateway is back.
                    if self.voiceSessionID == sessionID { self.resetVoice() }
                    return
                }
            }
        }
    }
    func toggleDetails() { expanded.toggle() }
    private func send(_ event: [String: Any]) {
        guard let socket, let data = try? JSONSerialization.data(withJSONObject: event), let text = String(data: data, encoding: .utf8) else { return }
        Task { do { try await socket.send(.string(text)) } catch { activities.insert(Activity(title: "Gateway disconnected", detail: "Reconnect to continue.", isError: true), at: 0) } }
    }
    func friendly(_ name: String) -> String {
        let labels = ["qupath_project_images": "Project images", "qupath_analysis_catalog": "Analysis options", "qupath_measurement_summary": "Measured results", "qupath_open_path": "Open image or project", "qupath_open_project_image": "Open project image", "qupath_inspect_import": "Inspect annotation file", "qupath_import_annotations": "Import annotations",
                      "qupath_detect_cells": "Detected cells", "qupath_class_summary": "Counted classes", "qupath_workflow_steps": "Workflow steps", "qupath_save_workflow": "Saved workflow", "qupath_select": "Selected objects", "qupath_snapshot": "Captured the viewer", "qupath_delete_selected": "Deleted selected objects", "qupath_image_type": "Set image type", "qupath_run_script": "Ran a script", "qupath_run_command": "Ran a command", "qupath_compare": "Compared with checkpoint", "qupath_checkpoints": "Listed checkpoints", "qupath_ui_action": "Operated a control"]
        if let label = labels[name] { return label }
        return ["qupath_context": "Read current selection", "qupath_focus": "Focused your view", "qupath_overlays": "Updated overlays", "qupath_highlight": "Pointed it out", "qupath_checkpoint": "Saved a checkpoint", "qupath_objects": "Found objects", "qupath_measurements": "Read measurements", "qupath_annotate": "Created a region", "qupath_classify": "Classified cells", "qupath_restore": "Restored earlier results"][name] ?? name.replacingOccurrences(of: "qupath_", with: "").replacingOccurrences(of: "fiji_", with: "").replacingOccurrences(of: "_", with: " ").capitalized
    }
    func summary(_ value: [String: Any], toolName: String? = nil) -> String {
        if toolName == "qupath_project_images" {
            return value["open"] as? Bool == false ? "No project is open" : "Project images matched: \(value["total"] ?? 0)"
        }
        if toolName == "qupath_analysis_catalog" { return "Measurement names: \(value["measurementNameCount"] ?? 0) · Classes: \(value["classificationCount"] ?? 0)" }
        if toolName == "qupath_detect_cells", let counts = value["counts"] as? [String: Any], let cells = counts["cells"] {
            return "Detected \(cells) cells · inspect segmentation in \(appName)"
        }
        if let positive = value["positive"], let negative = value["negative"] { return "\(positive) positive · \(negative) negative" }
        if let workflow = value["workflow"] as? [String: Any] {
            if let path = workflow["path"] as? String { return "Saved \(workflow["steps"] ?? 0) steps to \((path as NSString).lastPathComponent)" }
            return "\(workflow["total"] ?? 0) recorded steps"
        }
        if let rows = classRows(value) {
            let total = (value["summary"] as? [String: Any])?["total"] ?? value["total"] ?? rows.reduce(0) { $0 + (($1["count"] as? Int) ?? 0) }
            return "\(total) objects in \(rows.count) classes"
        }
        if let highlight = value["highlight"] as? [String: Any] {
            let shown = highlight["shown"] as? Int ?? 0, total = highlight["total"] as? Int ?? shown
            let label = (highlight["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Highlighted"
            return shown < total ? "\(label) · \(shown) of \(total) visible" : label
        }
        if toolName == "qupath_inspect_import" { return "\(value["annotations"] ?? 0) annotations validated for review. No objects imported." }
        if let imported = value["imported"] { return "\(imported) annotations imported" }
        if value["alreadyActive"] as? Bool == true { return "That image is already active" }
        if value["dispatched"] as? Bool == true { return "Dispatched to \(appName). Inspect the application to confirm completion." }
        if value["completionVerified"] as? Bool == false { return "Operation finished. Inspect the result and log in \(appName)." }
        if let total = value["total"] { return "\(total) objects in scope" }
        if let selected = value["selected"] { return "\(selected) objects selected" }
        if let counts = value["counts"] as? [String: Any] { return counts.keys.sorted().prefix(3).map { "\(counts[$0]!) \($0)" }.joined(separator: " · ") }
        if value["pointer"] != nil { return "Centered on your selected region" }
        if value["updated"] != nil { return "Display updated in \(appName)" }
        if let label = value["label"] as? String { return label }
        return "Operation returned from \(appName)"
    }
}

enum CompanionError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
