//! One HTTP/1.1 GET to the API on the internal network. No TLS: compose calls `http://api:8080`.
//! A request that does not finish in 10 seconds is the contract's upstream timeout.

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

const TIMEOUT: Duration = Duration::from_secs(10);
const MAX_BYTES: usize = 1024 * 1024;

#[derive(Debug)]
pub struct ProbeError;

pub struct Fetched {
    pub status: u16,
    pub body: String,
}

struct Target {
    host: String,
    port: u16,
    host_header: String,
    path: String,
}

pub async fn get(url: &str) -> Result<Fetched, ProbeError> {
    get_with(url, &[]).await
}

/// `headers` are extra request lines (`X-Shroud-Client` on a version probe). A name or
/// value that contains a line break is refused.
pub async fn get_with(url: &str, headers: &[(&str, &str)]) -> Result<Fetched, ProbeError> {
    let target = parse_http_url(url)?;
    let mut extra = String::new();
    for (name, value) in headers {
        if name.is_empty()
            || name
                .bytes()
                .any(|byte| byte == b'\r' || byte == b'\n' || byte == b':')
            || value.bytes().any(|byte| byte == b'\r' || byte == b'\n')
        {
            return Err(ProbeError);
        }
        extra.push_str(name);
        extra.push_str(": ");
        extra.push_str(value);
        extra.push_str("\r\n");
    }
    match tokio::time::timeout(TIMEOUT, fetch(target, extra)).await {
        Ok(result) => result,
        Err(_) => Err(ProbeError),
    }
}

async fn fetch(target: Target, extra_headers: String) -> Result<Fetched, ProbeError> {
    let mut stream = TcpStream::connect((target.host.as_str(), target.port))
        .await
        .map_err(|_| ProbeError)?;
    let request = format!(
        "GET {path} HTTP/1.1\r\nHost: {host}\r\nAccept: */*\r\n{extra_headers}Connection: close\r\n\r\n",
        path = target.path,
        host = target.host_header,
    );
    stream
        .write_all(request.as_bytes())
        .await
        .map_err(|_| ProbeError)?;
    let mut buf = Vec::new();
    let mut tmp = [0u8; 8192];
    loop {
        if let Some(fetched) = decode_response(&buf, false)? {
            return Ok(fetched);
        }
        let n = stream.read(&mut tmp).await.map_err(|_| ProbeError)?;
        if n == 0 {
            return decode_response(&buf, true)?.ok_or(ProbeError);
        }
        if buf.len() + n > MAX_BYTES {
            return Err(ProbeError);
        }
        buf.extend_from_slice(&tmp[..n]);
    }
}

fn parse_http_url(url: &str) -> Result<Target, ProbeError> {
    let Some(rest) = url.strip_prefix("http://") else {
        return Err(ProbeError);
    };
    if rest.contains('@') {
        return Err(ProbeError);
    }
    let (authority, path) = match rest.split_once('/') {
        Some((authority, path)) => (authority, format!("/{path}")),
        None => (rest, "/".to_owned()),
    };
    if authority.is_empty() {
        return Err(ProbeError);
    }
    let (host, port) = if let Some(host) = authority.strip_prefix('[') {
        let Some((host, rest)) = host.split_once(']') else {
            return Err(ProbeError);
        };
        let port = match rest.strip_prefix(':') {
            Some(port) => port.parse().map_err(|_| ProbeError)?,
            None if rest.is_empty() => 80,
            None => return Err(ProbeError),
        };
        (host.to_owned(), port)
    } else if let Some((host, port)) = authority.rsplit_once(':') {
        if host.is_empty() {
            return Err(ProbeError);
        }
        (host.to_owned(), port.parse().map_err(|_| ProbeError)?)
    } else {
        (authority.to_owned(), 80)
    };
    if host.is_empty() {
        return Err(ProbeError);
    }
    let host_header = if host.contains(':') {
        format!("[{host}]:{port}")
    } else if port == 80 {
        host.clone()
    } else {
        format!("{host}:{port}")
    };
    Ok(Target {
        host,
        port,
        host_header,
        path,
    })
}

/// `None` while the buffer is still short of a full response.
pub(crate) fn decode_response(buf: &[u8], eof: bool) -> Result<Option<Fetched>, ProbeError> {
    let Some(split) = find_header_end(buf) else {
        return Ok(None);
    };
    let header_bytes = &buf[..split];
    let header_text = std::str::from_utf8(header_bytes).map_err(|_| ProbeError)?;
    let mut lines = header_text.split("\r\n");
    let status_line = lines.next().ok_or(ProbeError)?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .ok_or(ProbeError)?
        .parse::<u16>()
        .map_err(|_| ProbeError)?;
    let mut content_length = None;
    let mut chunked = false;
    for line in lines {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        if name.eq_ignore_ascii_case("content-length") {
            content_length = Some(value.trim().parse::<usize>().map_err(|_| ProbeError)?);
        } else if name.eq_ignore_ascii_case("transfer-encoding")
            && value.to_ascii_lowercase().contains("chunked")
        {
            chunked = true;
        }
    }
    let body_at = split + 4;
    if chunked {
        let Some(body) = decode_chunks(&buf[body_at..], eof)? else {
            return Ok(None);
        };
        return Ok(Some(Fetched {
            status,
            body: String::from_utf8(body).map_err(|_| ProbeError)?,
        }));
    }
    if let Some(length) = content_length {
        if buf.len() < body_at + length {
            return Ok(None);
        }
        let body = buf[body_at..body_at + length].to_vec();
        return Ok(Some(Fetched {
            status,
            body: String::from_utf8(body).map_err(|_| ProbeError)?,
        }));
    }
    if !eof {
        return Ok(None);
    }
    let body = buf[body_at..].to_vec();
    Ok(Some(Fetched {
        status,
        body: String::from_utf8(body).map_err(|_| ProbeError)?,
    }))
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|window| window == b"\r\n\r\n")
}

fn decode_chunks(buf: &[u8], eof: bool) -> Result<Option<Vec<u8>>, ProbeError> {
    let mut out = Vec::new();
    let mut pos = 0;
    loop {
        let Some(relative) = buf[pos..].windows(2).position(|window| window == b"\r\n") else {
            return if eof { Err(ProbeError) } else { Ok(None) };
        };
        let line = std::str::from_utf8(&buf[pos..pos + relative]).map_err(|_| ProbeError)?;
        let size = usize::from_str_radix(line.trim().split(';').next().unwrap_or(""), 16)
            .map_err(|_| ProbeError)?;
        pos += relative + 2;
        if size == 0 {
            return Ok(Some(out));
        }
        if buf.len() < pos + size + 2 {
            return if eof { Err(ProbeError) } else { Ok(None) };
        }
        out.extend_from_slice(&buf[pos..pos + size]);
        if &buf[pos + size..pos + size + 2] != b"\r\n" {
            return Err(ProbeError);
        }
        pos += size + 2;
    }
}

/// The first integer after a Prometheus sample name, ignoring `#` comments.
pub fn metric(text: &str, name: &str) -> Option<u64> {
    text.lines().find_map(|line| {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            return None;
        }
        let mut parts = line.split_whitespace();
        let key = parts.next()?;
        if key != name {
            return None;
        }
        parts.next()?.parse().ok()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_content_length_and_chunked() {
        let raw = b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloEXTRA";
        let fetched = decode_response(raw, false).unwrap().unwrap();
        assert_eq!(fetched.status, 200);
        assert_eq!(fetched.body, "hello");
        assert!(
            decode_response(
                b"HTTP/1.1 204 No Content\r\nContent-Length: 2\r\n\r\nh",
                false
            )
            .unwrap()
            .is_none()
        );

        let chunked = b"HTTP/1.1 503 Service Unavailable\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n";
        let fetched = decode_response(chunked, true).unwrap().unwrap();
        assert_eq!(fetched.status, 503);
        assert_eq!(fetched.body, "hello");
    }

    #[test]
    fn metric_skips_comments() {
        let text = "# TYPE shroud_up gauge\nshroud_up 1\nshroud_uptime_seconds 90\n";
        assert_eq!(metric(text, "shroud_uptime_seconds"), Some(90));
        assert_eq!(metric(text, "missing"), None);
    }

    #[test]
    fn rejects_a_url_that_is_not_http() {
        assert!(parse_http_url("https://api:8080/health").is_err());
        let target = parse_http_url("http://api:8080/api/v1/metrics").unwrap();
        assert_eq!(target.host, "api");
        assert_eq!(target.port, 8080);
        assert_eq!(target.path, "/api/v1/metrics");
    }
}
