use std::borrow::Cow;
use std::net::IpAddr;
use std::time::Duration;

use async_http_proxy::{http_connect_tokio, http_connect_tokio_with_basic_auth};
use tokio::net::{TcpStream, lookup_host};
use tokio_socks::TargetAddr;
use tokio_socks::tcp::Socks5Stream;

use super::config::{OutboundConfig, ProxyConfig, ProxyKind};

/// Why an outbound connect failed.
///
/// The distinction is load-bearing: a refusal or reset means the address
/// answered, while a timeout means nothing did — the signature of a
/// DPI-blocked address, which the routing ladder treats very differently from
/// an address that merely said no.  Carried as a flag rather than parsed back
/// out of the message, whose wording varies by platform and proxy kind.
#[derive(Debug, Clone)]
pub struct OutboundError {
    pub reason: String,
    pub timed_out: bool,
}

impl OutboundError {
    fn failed(reason: impl Into<String>) -> Self {
        Self {
            reason: reason.into(),
            timed_out: false,
        }
    }

    fn timed_out(reason: impl Into<String>) -> Self {
        Self {
            reason: reason.into(),
            timed_out: true,
        }
    }
}

impl std::fmt::Display for OutboundError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.reason)
    }
}

impl std::error::Error for OutboundError {}

pub struct OutboundConnector {
    config: OutboundConfig,
}

impl OutboundConnector {
    pub fn direct() -> Self {
        Self {
            config: OutboundConfig {
                proxy: None,
                no_proxy: None,
            },
        }
    }

    pub fn from_config(
        outbound_proxy: Option<&str>,
        no_proxy: Option<&str>,
        use_env: bool,
    ) -> Result<Self, String> {
        Ok(Self {
            config: OutboundConfig::from_sources(outbound_proxy, no_proxy, use_env)?,
        })
    }

    pub fn summary(&self) -> Option<String> {
        self.config.proxy.as_ref().map(ProxyConfig::summary)
    }

    pub(crate) fn connects_directly(&self, target_host: &str, target_port: u16) -> bool {
        self.config.proxy.is_none() || self.should_bypass(target_host, target_port)
    }

    pub async fn connect(
        &self,
        target_host: &str,
        target_port: u16,
        timeout: Duration,
    ) -> Result<TcpStream, OutboundError> {
        let Some(proxy) = &self.config.proxy else {
            return connect_direct(target_host, target_port, timeout).await;
        };

        if self.should_bypass(target_host, target_port) {
            return connect_direct(target_host, target_port, timeout).await;
        }

        match tokio::time::timeout(timeout, connect_via_proxy(proxy, target_host, target_port))
            .await
        {
            Ok(result) => result.map_err(OutboundError::failed),
            Err(_) => Err(OutboundError::timed_out(format!(
                "proxy {} handshake timed out",
                proxy.summary()
            ))),
        }
    }

    fn should_bypass(&self, target_host: &str, target_port: u16) -> bool {
        self.config
            .no_proxy
            .as_ref()
            .is_some_and(|no_proxy| no_proxy.matches(target_host, target_port))
    }
}

async fn connect_direct(
    host: &str,
    port: u16,
    timeout: Duration,
) -> Result<TcpStream, OutboundError> {
    match tokio::time::timeout(timeout, connect_staggered(host, port)).await {
        Ok(result) => result.map_err(OutboundError::failed),
        Err(_) => Err(OutboundError::timed_out("TCP connect timed out")),
    }
}

/// How long a direct dial may go unanswered before a second one joins it.
/// A healthy handshake to a Telegram or Cloudflare edge takes well under half
/// of this, so the second dial only ever starts on a path that is losing SYNs.
const STAGGER: Duration = Duration::from_secs(1);

/// Throttling DPI drops whole flows at the SYN, picking them per 5-tuple, so
/// the kernel's own SYN retransmits (same source port, same flow) fail with the
/// first one.  A second dial gets a new source port and a fresh roll; whichever
/// handshake completes first wins and the other is dropped.  The futures are
/// boxed to keep the per-connection handler state small.
async fn connect_staggered(host: &str, port: u16) -> Result<TcpStream, String> {
    let mut first = Box::pin(connect_tcp(host, port));
    tokio::select! {
        result = &mut first => return result,
        () = tokio::time::sleep(STAGGER) => {}
    }
    let mut second = Box::pin(connect_tcp(host, port));
    tokio::select! {
        result = &mut first => match result {
            Ok(stream) => Ok(stream),
            Err(_) => second.await,
        },
        result = &mut second => match result {
            Ok(stream) => Ok(stream),
            Err(_) => first.await,
        },
    }
}

async fn connect_via_proxy(
    proxy: &ProxyConfig,
    target_host: &str,
    target_port: u16,
) -> Result<TcpStream, String> {
    match proxy.kind {
        ProxyKind::Http => connect_http_proxy(proxy, target_host, target_port)
            .await
            .map_err(|e| format!("HTTP proxy {}: {e}", proxy.summary())),
        ProxyKind::Socks5 { remote_dns } => {
            connect_socks5_proxy(proxy, target_host, target_port, remote_dns)
                .await
                .map_err(|e| format!("SOCKS5 proxy {}: {e}", proxy.summary()))
        }
    }
}

async fn connect_http_proxy(
    proxy: &ProxyConfig,
    target_host: &str,
    target_port: u16,
) -> Result<TcpStream, String> {
    let mut stream = connect_tcp(&proxy.host, proxy.port).await?;
    let target_host = if target_host.contains(':') && !target_host.starts_with('[') {
        Cow::Owned(format!("[{target_host}]"))
    } else {
        Cow::Borrowed(target_host)
    };

    if let Some(username) = proxy.username.as_deref() {
        http_connect_tokio_with_basic_auth(
            &mut stream,
            &target_host,
            target_port,
            username,
            proxy.password.as_deref().unwrap_or(""),
        )
        .await
    } else {
        http_connect_tokio(&mut stream, &target_host, target_port).await
    }
    .map_err(|e| e.to_string())?;

    Ok(stream)
}

async fn connect_socks5_proxy(
    proxy: &ProxyConfig,
    target_host: &str,
    target_port: u16,
    remote_dns: bool,
) -> Result<TcpStream, String> {
    let target = socks5_target(target_host, target_port, remote_dns).await?;
    let proxy_addr = (proxy.host.as_str(), proxy.port);

    let stream = if let Some(username) = proxy.username.as_deref() {
        Socks5Stream::connect_with_password(
            proxy_addr,
            target,
            username,
            proxy.password.as_deref().unwrap_or(""),
        )
        .await
    } else {
        Socks5Stream::connect(proxy_addr, target).await
    }
    .map_err(|e| e.to_string())?;

    Ok(stream.into_inner())
}

async fn connect_tcp(host: &str, port: u16) -> Result<TcpStream, String> {
    let stream = TcpStream::connect((host, port))
        .await
        .map_err(|e| format!("TCP connect: {e}"))?;
    set_user_timeout(&stream);
    Ok(stream)
}

/// DPI boxes that throttle Telegram tend to stop forwarding a flow rather than
/// reset it, and a handover can strand a socket the same way.  Nothing on the
/// relay path would notice: the read side waits forever, so Telegram sits on a
/// half-downloaded file until its own timeouts fire.  TCP_USER_TIMEOUT aborts
/// the socket once sent data (the client's acks and pings) has gone unacked
/// this long, without the extra wakeups keepalive probes would cost on a phone.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn set_user_timeout(stream: &TcpStream) {
    let _ = socket2::SockRef::from(stream).set_tcp_user_timeout(Some(UPSTREAM_USER_TIMEOUT));
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn set_user_timeout(_stream: &TcpStream) {}

#[cfg(any(target_os = "linux", target_os = "android"))]
const UPSTREAM_USER_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(20);

async fn socks5_target<'a>(
    host: &'a str,
    port: u16,
    remote_dns: bool,
) -> Result<TargetAddr<'a>, String> {
    if remote_dns {
        if let Ok(ip) = host.parse::<IpAddr>() {
            return Ok(TargetAddr::Ip((ip, port).into()));
        }
        return Ok(TargetAddr::Domain(Cow::Borrowed(host), port));
    }

    lookup_host((host, port))
        .await
        .map_err(|e| format!("SOCKS5 local DNS lookup: {e}"))?
        .next()
        .map(TargetAddr::Ip)
        .ok_or_else(|| "SOCKS5 local DNS lookup returned no addresses".to_string())
}
