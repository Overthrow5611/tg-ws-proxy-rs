//! Process-wide counters an embedder can poll (the Android notification).
//!
//! Byte totals cover closed sessions only.  Counting live bridges would put an
//! atomic op on every relayed chunk of every connection; media sessions close
//! every minute or so, which keeps the totals current enough for a status line.

use std::sync::atomic::{AtomicU64, Ordering};

static ACTIVE_CLIENTS: AtomicU64 = AtomicU64::new(0);
static BYTES_UP: AtomicU64 = AtomicU64::new(0);
static BYTES_DOWN: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Snapshot {
    pub active_clients: u64,
    pub bytes_up: u64,
    pub bytes_down: u64,
}

pub fn snapshot() -> Snapshot {
    Snapshot {
        active_clients: ACTIVE_CLIENTS.load(Ordering::Relaxed),
        bytes_up: BYTES_UP.load(Ordering::Relaxed),
        bytes_down: BYTES_DOWN.load(Ordering::Relaxed),
    }
}

/// Zero the byte totals for a new run.  The client count is left alone: it is
/// owned by live [ClientGuard]s, which may outlive a run that is winding down.
pub fn reset_totals() {
    BYTES_UP.store(0, Ordering::Relaxed);
    BYTES_DOWN.store(0, Ordering::Relaxed);
}

pub(crate) fn add_session(bytes_up: u64, bytes_down: u64) {
    BYTES_UP.fetch_add(bytes_up, Ordering::Relaxed);
    BYTES_DOWN.fetch_add(bytes_down, Ordering::Relaxed);
}

/// Counts one accepted client for as long as it is alive.
pub(crate) struct ClientGuard(());

impl ClientGuard {
    pub(crate) fn new() -> Self {
        ACTIVE_CLIENTS.fetch_add(1, Ordering::Relaxed);
        Self(())
    }
}

impl Drop for ClientGuard {
    fn drop(&mut self) {
        ACTIVE_CLIENTS.fetch_sub(1, Ordering::Relaxed);
    }
}
