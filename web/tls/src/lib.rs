//! TLS for the web client's link previews.
//!
//! Human: A browser can only reach other websites through Shroud's link relay (a WebSocket
//! that moves raw bytes to port 443 of one host). For the relay to learn nothing but the host
//! name, the browser has to speak TLS itself — this is rustls compiled to WebAssembly, with
//! the Mozilla root store, doing exactly that. It does no I/O: JavaScript moves the bytes
//! between the relay socket and a `TlsSession`, like a sans-IO state machine.
//! Agent: `new(host)` → `write(request)` → loop { send `takeOutgoing()`, `feed(frame)`, read
//! `takePlaintext()` } → `finish()` when the relay closes. Certificates are verified against
//! `webpki-roots` with the browser clock; any TLS error is returned, never panicked.

use std::io::{ErrorKind, Read, Write};
use std::sync::Arc;

use rustls::client::ClientConnection;
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, RootCertStore};
use wasm_bindgen::prelude::*;

/// One client configuration for every session: ring, TLS 1.2 + 1.3, Mozilla roots, HTTP/1.1.
///
/// Agent: Certificate validity uses the browser clock (`rustls-pki-types/web`).
fn client_config() -> Result<Arc<ClientConfig>, rustls::Error> {
    let roots: RootCertStore = webpki_roots::TLS_SERVER_ROOTS.iter().cloned().collect();
    let mut config =
        ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()?
            .with_root_certificates(roots)
            .with_no_client_auth();
    // The web client speaks plain HTTP/1.1 over the session; never negotiate h2.
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(config))
}

fn js_error(error: impl std::fmt::Display) -> JsError {
    JsError::new(&error.to_string())
}

/// A TLS client session with one website, driven by JavaScript.
#[wasm_bindgen]
pub struct TlsSession {
    connection: ClientConnection,
    plaintext: Vec<u8>,
    closed: bool,
}

#[wasm_bindgen]
impl TlsSession {
    /// Starts a session with `host` — the name used for SNI and checked against the certificate.
    ///
    /// Agent: RETURNS a JS error for a name rustls rejects; nothing is sent until
    /// `takeOutgoing()` hands over the ClientHello.
    #[wasm_bindgen(constructor)]
    pub fn new(host: &str) -> Result<TlsSession, JsError> {
        let name = ServerName::try_from(host.to_owned()).map_err(js_error)?;
        let connection =
            ClientConnection::new(client_config().map_err(js_error)?, name).map_err(js_error)?;
        Ok(Self {
            connection,
            plaintext: Vec::new(),
            closed: false,
        })
    }

    /// Queues application data (the HTTP request); it goes out once the handshake is done.
    pub fn write(&mut self, data: &[u8]) -> Result<(), JsError> {
        self.connection.writer().write_all(data).map_err(js_error)
    }

    /// Feeds bytes that arrived from the relay; decrypted data collects for `takePlaintext`.
    ///
    /// Agent: RETURNS a JS error for a TLS failure (bad certificate, wrong name, alert); the
    /// session is unusable afterwards.
    pub fn feed(&mut self, data: &[u8]) -> Result<(), JsError> {
        let mut remaining = data;
        while !remaining.is_empty() {
            let read = self.connection.read_tls(&mut remaining).map_err(js_error)?;
            if read == 0 {
                return Err(JsError::new("TLS buffer full"));
            }
            self.process()?;
        }
        Ok(())
    }

    /// Tells the session the relay closed, and collects whatever was left to decrypt.
    pub fn finish(&mut self) -> Result<(), JsError> {
        let mut end: &[u8] = &[];
        self.connection.read_tls(&mut end).map_err(js_error)?;
        self.process()
    }

    /// TLS records to send to the relay now — handshake, request, alerts. Empty when idle.
    #[wasm_bindgen(js_name = takeOutgoing)]
    pub fn take_outgoing(&mut self) -> Result<Vec<u8>, JsError> {
        let mut outgoing = Vec::new();
        while self.connection.wants_write() {
            self.connection.write_tls(&mut outgoing).map_err(js_error)?;
        }
        Ok(outgoing)
    }

    /// Decrypted bytes received since the last call.
    #[wasm_bindgen(js_name = takePlaintext)]
    pub fn take_plaintext(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.plaintext)
    }

    /// True once the website ended the session (close_notify or end of stream).
    #[wasm_bindgen(getter)]
    pub fn closed(&self) -> bool {
        self.closed
    }

    /// True until the handshake (and certificate check) has completed.
    #[wasm_bindgen(getter, js_name = isHandshaking)]
    pub fn is_handshaking(&self) -> bool {
        self.connection.is_handshaking()
    }

    /// Queues a close_notify alert (collect it with `takeOutgoing`).
    pub fn close(&mut self) {
        self.connection.send_close_notify();
    }

    /// Runs rustls over buffered records and drains the plaintext they produced.
    fn process(&mut self) -> Result<(), JsError> {
        let state = self.connection.process_new_packets().map_err(js_error)?;
        let mut chunk = [0u8; 16 * 1024];
        loop {
            match self.connection.reader().read(&mut chunk) {
                Ok(0) => {
                    self.closed = true;
                    break;
                }
                Ok(read) => {
                    if let Some(bytes) = chunk.get(..read) {
                        self.plaintext.extend_from_slice(bytes);
                    }
                }
                Err(error) if error.kind() == ErrorKind::WouldBlock => break,
                // End of stream without close_notify: whatever arrived is all there is.
                Err(error) if error.kind() == ErrorKind::UnexpectedEof => {
                    self.closed = true;
                    break;
                }
                Err(error) => return Err(js_error(error)),
            }
        }
        if state.peer_has_closed() {
            self.closed = true;
        }
        Ok(())
    }
}
