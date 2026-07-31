import AVFoundation
import Foundation
import UIKit
import WebKit

/// WebRTC peer connection engine backed by an embedded `WKWebView` + browser WebRTC.
///
/// Human: Real DTLS-SRTP media between devices without shipping a WebRTC xcframework.
/// Agent: Local HTML/JS RTCPeerConnection; Swift relays SDP/ICE via message handlers.
@MainActor
final class WebRTCEngine: NSObject {
    enum EngineError: Error, LocalizedError {
        case notReady
        case scriptFailed(String)
        case invalidResponse

        var errorDescription: String? {
            switch self {
            case .notReady: "WebRTC engine is not ready."
            case let .scriptFailed(msg): msg
            case .invalidResponse: "Invalid WebRTC bridge response."
            }
        }
    }

    /// Outbound signaling the host should relay to the peer.
    var onLocalSignal: ((String, String) -> Void)?
    /// Connection state labels from the browser peer connection.
    var onConnectionState: ((String) -> Void)?
    var onError: ((String) -> Void)?

    private var webView: WKWebView?
    private var readyContinuation: CheckedContinuation<Void, Error>?
    private var isReady = false
    private var pendingContinuations: [String: CheckedContinuation<String, Error>] = [:]
    private var requestCounter = 0

    /// Loads the bridge page and waits until JS reports ready.
    func prepare(iceServers: [IceServerDTO], video: Bool) async throws {
        teardown()

        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let uc = config.userContentController
        uc.add(self, name: "shroudRtc")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isHidden = true
        webView.navigationDelegate = self
        // Keep a strong reference; attach off-screen under key window when available.
        if let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        {
            webView.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
            window.addSubview(webView)
        }
        self.webView = webView

        let html = Self.htmlDocument(iceServers: iceServers, video: video)
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            self.readyContinuation = cont
            webView.loadHTMLString(html, baseURL: URL(string: "https://shroud.local/"))
            // Timeout if JS never boots.
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                if let pending = self.readyContinuation {
                    self.readyContinuation = nil
                    pending.resume(throwing: EngineError.notReady)
                }
            }
        }
        isReady = true
    }

    /// Creates a local SDP offer (caller).
    func createOffer() async throws -> String {
        try await callJS(name: "createOffer")
    }

    /// Applies a remote offer and returns a local answer (callee).
    func createAnswer(remoteOfferSDP: String) async throws -> String {
        try await callJS(name: "createAnswer", arg: remoteOfferSDP)
    }

    /// Applies a remote answer on the caller side.
    func setRemoteAnswer(_ sdp: String) async throws {
        _ = try await callJS(name: "setRemoteAnswer", arg: sdp)
    }

    /// Adds a remote ICE candidate JSON string.
    func addIceCandidate(_ json: String) async throws {
        _ = try await callJS(name: "addIceCandidate", arg: json)
    }

    func setMuted(_ muted: Bool) async {
        _ = try? await callJS(name: "setMuted", arg: muted ? "true" : "false")
    }

    func setVideoEnabled(_ enabled: Bool) async {
        _ = try? await callJS(name: "setVideoEnabled", arg: enabled ? "true" : "false")
    }

    func hangup() {
        Task { @MainActor in
            _ = try? await callJS(name: "hangup")
            teardown()
        }
    }

    func teardown() {
        isReady = false
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "shroudRtc")
        webView?.removeFromSuperview()
        webView = nil
        for (_, cont) in pendingContinuations {
            cont.resume(throwing: EngineError.notReady)
        }
        pendingContinuations.removeAll()
        if let readyContinuation {
            self.readyContinuation = nil
            readyContinuation.resume(throwing: EngineError.notReady)
        }
    }

    // MARK: - JS bridge

    private func callJS(name: String, arg: String? = nil) async throws -> String {
        guard isReady, let webView else { throw EngineError.notReady }
        requestCounter += 1
        let id = "r\(requestCounter)"
        let escapedArg: String
        if let arg {
            escapedArg = String(data: try JSONEncoder().encode(arg), encoding: .utf8) ?? "null"
        } else {
            escapedArg = "null"
        }
        let idJSON = String(data: try JSONEncoder().encode(id), encoding: .utf8)!
        let nameJSON = String(data: try JSONEncoder().encode(name), encoding: .utf8)!
        let js = "window.__shroudCall(\(idJSON), \(nameJSON), \(escapedArg));"

        return try await withCheckedThrowingContinuation { cont in
            pendingContinuations[id] = cont
            webView.evaluateJavaScript(js) { [weak self] _, error in
                Task { @MainActor in
                    if let error {
                        if let pending = self?.pendingContinuations.removeValue(forKey: id) {
                            pending.resume(throwing: EngineError.scriptFailed(error.localizedDescription))
                        }
                    }
                }
            }
        }
    }

    private static func htmlDocument(iceServers: [IceServerDTO], video: Bool) -> String {
        let iceJSON: String = {
            let objects: [[String: Any]] = iceServers.map { server in
                var dict: [String: Any] = ["urls": server.urls]
                if let u = server.username { dict["username"] = u }
                if let c = server.credential { dict["credential"] = c }
                return dict
            }
            if objects.isEmpty {
                return #"[{"urls":["stun:stun.l.google.com:19302"]}]"#
            }
            guard let data = try? JSONSerialization.data(withJSONObject: objects),
                  let s = String(data: data, encoding: .utf8)
            else {
                return #"[{"urls":["stun:stun.l.google.com:19302"]}]"#
            }
            return s
        }()

        return """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width"/></head>
        <body>
        <script>
        const iceServers = \(iceJSON);
        const wantVideo = \(video ? "true" : "false");
        let pc = null;
        let localStream = null;
        const pending = {};

        function post(msg) {
          try { window.webkit.messageHandlers.shroudRtc.postMessage(msg); } catch (e) {}
        }

        function ensurePC() {
          if (pc) return pc;
          pc = new RTCPeerConnection({ iceServers });
          pc.onicecandidate = (ev) => {
            if (!ev.candidate) return;
            post({ type: 'ice', payload: JSON.stringify(ev.candidate) });
          };
          pc.onconnectionstatechange = () => {
            post({ type: 'state', payload: pc.connectionState || '' });
          };
          pc.ontrack = () => { /* remote media plays via OS audio route */ };
          return pc;
        }

        async function ensureMedia() {
          if (localStream) return localStream;
          localStream = await navigator.mediaDevices.getUserMedia({
            audio: true,
            video: wantVideo ? { facingMode: 'user' } : false
          });
          for (const track of localStream.getTracks()) {
            ensurePC().addTrack(track, localStream);
          }
          return localStream;
        }

        async function createOffer() {
          await ensureMedia();
          const offer = await ensurePC().createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: wantVideo });
          await pc.setLocalDescription(offer);
          return pc.localDescription.sdp;
        }

        async function createAnswer(remoteSdp) {
          await ensureMedia();
          await ensurePC().setRemoteDescription({ type: 'offer', sdp: remoteSdp });
          const answer = await pc.createAnswer();
          await pc.setLocalDescription(answer);
          return pc.localDescription.sdp;
        }

        async function setRemoteAnswer(remoteSdp) {
          await ensurePC().setRemoteDescription({ type: 'answer', sdp: remoteSdp });
          return 'ok';
        }

        async function addIceCandidate(json) {
          const c = JSON.parse(json);
          await ensurePC().addIceCandidate(c);
          return 'ok';
        }

        function setMuted(flag) {
          if (!localStream) return 'ok';
          for (const t of localStream.getAudioTracks()) t.enabled = (flag !== 'true');
          return 'ok';
        }

        function setVideoEnabled(flag) {
          if (!localStream) return 'ok';
          for (const t of localStream.getVideoTracks()) t.enabled = (flag === 'true');
          return 'ok';
        }

        function hangup() {
          try {
            if (localStream) localStream.getTracks().forEach(t => t.stop());
            if (pc) pc.close();
          } catch (e) {}
          localStream = null;
          pc = null;
          return 'ok';
        }

        window.__shroudCall = async function(id, name, arg) {
          try {
            let result = '';
            if (name === 'createOffer') result = await createOffer();
            else if (name === 'createAnswer') result = await createAnswer(arg);
            else if (name === 'setRemoteAnswer') result = await setRemoteAnswer(arg);
            else if (name === 'addIceCandidate') result = await addIceCandidate(arg);
            else if (name === 'setMuted') result = setMuted(arg);
            else if (name === 'setVideoEnabled') result = setVideoEnabled(arg);
            else if (name === 'hangup') result = hangup();
            else throw new Error('unknown method ' + name);
            post({ type: 'result', id, ok: true, value: result });
          } catch (e) {
            post({ type: 'result', id, ok: false, value: String(e && e.message ? e.message : e) });
          }
        };

        post({ type: 'ready' });
        </script>
        </body></html>
        """
    }
}

extension WebRTCEngine: WKScriptMessageHandler {
    nonisolated func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        Task { @MainActor in
            guard let body = message.body as? [String: Any],
                  let type = body["type"] as? String
            else { return }

            switch type {
            case "ready":
                if let cont = readyContinuation {
                    readyContinuation = nil
                    cont.resume()
                }
            case "ice":
                if let payload = body["payload"] as? String {
                    onLocalSignal?("ice_candidate", payload)
                }
            case "state":
                if let payload = body["payload"] as? String {
                    onConnectionState?(payload)
                }
            case "result":
                guard let id = body["id"] as? String,
                      let cont = pendingContinuations.removeValue(forKey: id)
                else { return }
                let ok = body["ok"] as? Bool ?? false
                let value = body["value"] as? String ?? ""
                if ok {
                    cont.resume(returning: value)
                } else {
                    cont.resume(throwing: EngineError.scriptFailed(value))
                }
            default:
                break
            }
        }
    }
}

extension WebRTCEngine: WKNavigationDelegate {
    nonisolated func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        Task { @MainActor in
            if let cont = readyContinuation {
                readyContinuation = nil
                cont.resume(throwing: EngineError.scriptFailed(error.localizedDescription))
            }
        }
    }
}

