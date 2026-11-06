//! Pairing and sync for the phone (Android SRS Sections 3.6 to 3.8), through the same
//! `booklub-sync` client the desktop's tests use. Every call here blocks, so the app makes them
//! from a background coroutine, and only while it is in the foreground (QA-26).

use std::fs;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use booklub_core::background;
use booklub_sync::client::{self, Target};
use booklub_sync::{discovery, ApplyReport, Delta, DeviceKey, Event, Host, Invitation, PairedPeer, SyncError, Vector, DEFAULT_PORT};

use crate::{lock, Booklub};

/// What the phone shows about the computer it is paired with (REQ-34).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Computer {
    pub id: String,
    pub name: String,
    /// RFC 3339, or none before the first completed session.
    pub last_session: Option<String>,
    /// REQ-34a: something stored here hasn't reached the computer yet.
    pub pending: bool,
}

/// The computer a scanned QR code names, for the confirmation (REQ-20d).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Invite {
    pub name: String,
    pub address: String,
}

#[derive(Debug, Clone, Default, PartialEq, uniffi::Record)]
pub struct SyncResult {
    pub received: u32,
    pub sent: u32,
    pub deleted: u32,
    pub images_received: u32,
    /// Whether the library changed, so the screens should reload.
    pub changed: bool,
}

/// REQ-34b: why a session or pairing failed, with the text to show.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SyncFailure {
    #[error("{reason}")]
    Unreachable { reason: String },
    #[error("{reason}")]
    IdentityChanged { reason: String },
    #[error("{reason}")]
    NotPaired { reason: String },
    #[error("{reason}")]
    PairingClosed { reason: String },
    #[error("{reason}")]
    WrongCode { remaining: u8, reason: String },
    #[error("{reason}")]
    Declined { reason: String },
    /// `update_phone` says which side needs updating (REQ-30f).
    #[error("{reason}")]
    Incompatible { update_phone: bool, reason: String },
    #[error("{reason}")]
    NotAnInvitation { reason: String },
    #[error("{reason}")]
    NotHomeNetwork { reason: String },
    #[error("{reason}")]
    Other { reason: String },
}

impl From<SyncError> for SyncFailure {
    fn from(error: SyncError) -> Self {
        let reason = error.to_string();
        match error {
            SyncError::Unreachable | SyncError::Io(_) | SyncError::Busy => SyncFailure::Unreachable { reason },
            SyncError::IdentityChanged => SyncFailure::IdentityChanged { reason },
            SyncError::NotPaired => SyncFailure::NotPaired { reason },
            SyncError::PairingClosed => SyncFailure::PairingClosed { reason },
            SyncError::WrongCode { remaining } => SyncFailure::WrongCode { remaining, reason },
            SyncError::Declined => SyncFailure::Declined { reason },
            SyncError::Incompatible { ours, theirs } => SyncFailure::Incompatible { update_phone: ours < theirs, reason },
            SyncError::NotAnInvitation => SyncFailure::NotAnInvitation { reason },
            SyncError::NotHomeNetwork(_) => SyncFailure::NotHomeNetwork { reason },
            // The store's own sentence, such as REQ-42b's "Not enough storage on this phone...".
            SyncError::Storage(detail) => SyncFailure::Other { reason: detail },
            SyncError::Protocol(_) => SyncFailure::Other { reason },
        }
    }
}

/// The app's side of a pairing or session.
#[uniffi::export(with_foreign)]
pub trait SyncListener: Send + Sync {
    /// REQ-20d: "Pair with <name>?" Blocks until the Reader answers.
    fn confirm_computer(&self, name: String) -> bool;
    /// REQ-31a.
    fn progress(&self, books_received: u32, books_total: u32, images_received: u32, images_total: u32);
    /// Free space where images are stored, in bytes (REQ-42b).
    fn free_bytes(&self) -> u64;
}

/// A generous size for one incoming thumbnail, so the space check errs on the safe side.
const IMAGE_ALLOWANCE: u64 = 150 * 1024;
/// Room kept free for the phone itself.
const RESERVE: u64 = 20 * 1024 * 1024;

/// REQ-42b: what a session will need, estimated before anything is stored: the database rows
/// and every image it newly refers to.
fn space_needed(delta: &Delta, have: &[String]) -> u64 {
    let rows = delta.units.iter().map(|unit| unit.value.to_string().len() as u64 + 160).sum::<u64>();
    let images = delta
        .units
        .iter()
        .filter(|unit| unit.field == "background")
        .filter_map(|unit| unit.value.as_str().map(|v| v.strip_prefix("file:").unwrap_or(v).to_string()))
        .filter(|name| !have.contains(name))
        .collect::<std::collections::BTreeSet<_>>()
        .len() as u64;
    rows * 2 + images * IMAGE_ALLOWANCE + RESERVE
}

#[uniffi::export]
impl Booklub {
    /// How this phone introduces itself, such as "Galaxy S25".
    pub fn set_device_name(&self, name: String) {
        *lock(&self.device_name) = name.trim().to_string();
    }

    /// The paired computer, if any (BR-07: at most one).
    pub fn computer(&self) -> Option<Computer> {
        let store = lock(&self.store);
        let peer = store.peers().ok()?.into_iter().next()?;
        let ours = store.sync_vector().unwrap_or_default();
        let pending = ours.iter().any(|(origin, stamp)| peer.acked.get(origin).is_none_or(|seen| seen < stamp));
        Some(Computer { id: peer.id, name: peer.name, last_session: peer.last_session, pending })
    }

    /// Reads a scanned QR code without connecting, so the app can ask the Reader first.
    pub fn read_invitation(&self, text: String) -> Result<Invite, SyncFailure> {
        let invitation = Invitation::parse(&text)?;
        let address = invitation.addresses.first().map(|ip| SocketAddr::new(*ip, invitation.port).to_string()).unwrap_or_default();
        Ok(Invite { name: invitation.name, address })
    }

    /// REQ-20: pairs using a scanned QR code, then runs the first session (REQ-31).
    pub fn pair_by_qr(&self, text: String, listener: Arc<dyn SyncListener>) -> Result<SyncResult, SyncFailure> {
        let invitation = Invitation::parse(&text)?;
        self.pair(Target::Invitation(invitation), listener)
    }

    /// REQ-20f: pairs using the address and code typed from the computer's screen.
    pub fn pair_by_code(&self, address: String, code: String, listener: Arc<dyn SyncListener>) -> Result<SyncResult, SyncFailure> {
        let address = parse_address(&address).ok_or_else(|| SyncFailure::Unreachable { reason: "That address doesn't look right. It reads like 192.168.1.20:47120.".into() })?;
        self.pair(Target::Code { address, code }, listener)
    }

    /// One session with the paired computer: the stored addresses first, because they are
    /// usually still right, then DNS-SD for a computer whose address has changed (REQ-22a).
    pub fn sync_now(&self, listener: Arc<dyn SyncListener>) -> Result<SyncResult, SyncFailure> {
        let _one_at_a_time = lock(&self.syncing);
        let key = self.device_key()?;
        let computer = lock(&self.store).peers().ok().and_then(|peers| peers.into_iter().next()).ok_or_else(|| SyncFailure::NotPaired { reason: SyncError::NotPaired.to_string() })?;
        let host = PhoneHost { booklub: self, listener };
        let stored: Vec<SocketAddr> = computer.addresses.iter().filter_map(|a| parse_address(a)).collect();
        let summary = match client::sync(&host, &key, &computer, &stored) {
            Err(SyncError::Unreachable | SyncError::Io(_)) => {
                let found = discovery::find(&computer.id, discovery::SEARCH_TIME);
                if found.is_empty() {
                    return Err(SyncError::Unreachable.into());
                }
                let summary = client::sync(&host, &key, &computer, &found)?;
                self.remember_addresses(&computer.id, &found);
                summary
            }
            other => other?,
        };
        Ok(result(&summary))
    }

    /// REQ-21: forgets the computer. The books stay on the phone.
    pub fn forget_computer(&self) -> Result<(), crate::BooklubError> {
        let store = lock(&self.store);
        for peer in store.peers().map_err(crate::store_error)? {
            store.remove_peer(&peer.id).map_err(crate::store_error)?;
        }
        Ok(())
    }
}

impl Booklub {
    fn device_key(&self) -> Result<DeviceKey, SyncFailure> {
        DeviceKey::load_or_create(&self.paths.device).map_err(|e| SyncFailure::Other { reason: format!("Booklub couldn't create this phone's sync key: {e}") })
    }

    fn pair(&self, target: Target, listener: Arc<dyn SyncListener>) -> Result<SyncResult, SyncFailure> {
        let _one_at_a_time = lock(&self.syncing);
        let key = self.device_key()?;
        let host = PhoneHost { booklub: self, listener };
        let (_, summary) = client::pair(&host, &key, &target)?;
        Ok(SyncResult { changed: true, ..result(&summary) })
    }

    fn remember_addresses(&self, id: &str, found: &[SocketAddr]) {
        let store = lock(&self.store);
        if let Some(mut peer) = store.peers().ok().and_then(|peers| peers.into_iter().find(|p| p.id == id)) {
            peer.addresses = found.iter().map(SocketAddr::to_string).collect();
            let _ = store.save_peer(&peer);
        }
    }

    /// Re-reads the library after a merge, and deletes the images it no longer uses (REQ-33d).
    /// Only images that the merge orphaned go, so one the Reader picked but hasn't saved stays.
    fn reload_after(&self, before: &[String]) {
        let store = lock(&self.store);
        let Ok(library) = store.load_all() else { return };
        let after = library.background_files();
        *lock(&self.library) = library;
        for gone in before.iter().filter(|name| !after.contains(name)) {
            background::remove(gone, &self.paths.backgrounds, &self.paths.thumbnails);
        }
    }
}

/// "192.168.1.20:47120", "192.168.1.20", or "[fe80::1]:47120".
pub(crate) fn parse_address(text: &str) -> Option<SocketAddr> {
    let text = text.trim();
    text.parse::<SocketAddr>().ok().or_else(|| text.parse::<IpAddr>().ok().map(|ip| SocketAddr::new(ip, DEFAULT_PORT)))
}

fn result(summary: &booklub_sync::Summary) -> SyncResult {
    let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
    SyncResult {
        received: count(summary.units_received),
        sent: count(summary.units_sent),
        deleted: count(summary.deleted),
        images_received: count(summary.images_received),
        changed: summary.units_received + summary.deleted + summary.images_received > 0,
    }
}

struct PhoneHost<'a> {
    booklub: &'a Booklub,
    listener: Arc<dyn SyncListener>,
}

fn message(error: booklub_core::store::StoreError) -> String {
    error.message
}

impl Host for PhoneHost<'_> {
    fn peer_id(&self) -> String {
        lock(&self.booklub.store).peer_id().to_string()
    }

    fn device_name(&self) -> String {
        let name = lock(&self.booklub.device_name).clone();
        if name.is_empty() { "Android phone".into() } else { name }
    }

    fn peers(&self) -> Result<Vec<PairedPeer>, String> {
        lock(&self.booklub.store).peers().map_err(message)
    }

    /// BR-07: a phone pairs with one computer, so a new pairing replaces the old.
    fn save_peer(&self, peer: &PairedPeer) -> Result<(), String> {
        let store = lock(&self.booklub.store);
        for old in store.peers().map_err(message)?.into_iter().filter(|old| old.id != peer.id) {
            store.remove_peer(&old.id).map_err(message)?;
        }
        store.save_peer(peer).map_err(message)
    }

    fn vector(&self) -> Result<Vector, String> {
        lock(&self.booklub.store).sync_vector().map_err(message)
    }

    fn delta_since(&self, theirs: &Vector) -> Result<Delta, String> {
        lock(&self.booklub.store).delta_since(theirs).map_err(message)
    }

    fn apply(&self, delta: &Delta) -> Result<ApplyReport, String> {
        let before = lock(&self.booklub.library).background_files();
        let (needed, free) = (space_needed(delta, &before), self.listener.free_bytes());
        if needed > free {
            let short = (needed - free).div_ceil(1024 * 1024);
            return Err(format!("Not enough storage on this phone. Booklub needs {short} MB more."));
        }
        let report = lock(&self.booklub.store).apply_delta(delta).map_err(message)?;
        if report.units_applied + report.deleted > 0 {
            self.booklub.reload_after(&before);
        }
        Ok(report)
    }

    /// UR-02: the phone's own library gives way to the computer's.
    fn reset_for_pairing(&self) -> Result<(), String> {
        let before = lock(&self.booklub.library).background_files();
        lock(&self.booklub.store).reset_for_pairing().map_err(message)?;
        self.booklub.reload_after(&before);
        Ok(())
    }

    fn missing_images(&self) -> Vec<String> {
        let referenced = lock(&self.booklub.library).background_files();
        referenced.into_iter().filter(|name| !self.booklub.paths.backgrounds.join(name).exists()).collect()
    }

    /// The phone only ever holds thumbnails, so it sends its stored file as it is.
    fn read_image(&self, name: &str) -> Option<Vec<u8>> {
        fs::read(self.booklub.paths.backgrounds.join(name)).ok()
    }

    /// REQ-33f. A disk that is full fails here, so the session reports it and the image is
    /// asked for again next time (Android SRS REQ-42 on low storage).
    fn store_image(&self, name: &str, bytes: &[u8]) -> Result<(), String> {
        if background::check(bytes.to_vec()).is_err() {
            return Ok(());
        }
        let folder = &self.booklub.paths.backgrounds;
        fs::create_dir_all(folder).map_err(|e| e.to_string())?;
        let temporary = folder.join(format!("{name}.part"));
        fs::write(&temporary, bytes).and_then(|()| fs::rename(&temporary, folder.join(name))).map_err(|e| {
            let _ = fs::remove_file(&temporary);
            format!("This phone has no room for the books' images: {e}")
        })?;
        // A thumbnail made before the image arrived would be of nothing.
        let _ = fs::remove_file(background::thumbnail_path(&self.booklub.paths.thumbnails, name));
        let _ = fs::remove_file(background::wide_thumbnail_path(&self.booklub.paths.thumbnails, name));
        Ok(())
    }

    fn session_finished(&self, peer: &str, acked: &Vector) -> Result<(), String> {
        let store = lock(&self.booklub.store);
        if let Some(mut record) = store.peers().map_err(message)?.into_iter().find(|p| p.id == peer) {
            record.last_session = Some(chrono::Local::now().to_rfc3339());
            record.acked = acked.clone();
            store.save_peer(&record).map_err(message)?;
        }
        store.collect_tombstones().map_err(message)?;
        Ok(())
    }

    fn confirm_pairing(&self, other_name: &str) -> bool {
        self.listener.confirm_computer(other_name.to_string())
    }

    fn notify(&self, event: Event) {
        let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
        match event {
            Event::Books { received, total } => self.listener.progress(count(received), count(total), 0, 0),
            Event::Images { received, total } => self.listener.progress(0, 0, count(received), count(total)),
            _ => {}
        }
    }
}

/// How long a phone waits between sessions while the app is open (plan P15).
pub const POLL_SECONDS: u64 = 4;

#[uniffi::export]
pub fn sync_poll_seconds() -> u64 {
    POLL_SECONDS
}


#[cfg(test)]
mod tests {
    use std::net::Ipv4Addr;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::sync::Mutex;

    use booklub_core::store::Store;
    use booklub_core::Book as CoreBook;
    use booklub_sync::server::Server;

    use super::*;

    /// A computer with just enough of a Host to serve the phone.
    struct Desk {
        store: Mutex<Store>,
        images: std::path::PathBuf,
        _dir: tempfile::TempDir,
    }

    impl Host for Desk {
        fn peer_id(&self) -> String {
            self.store.lock().unwrap().peer_id().to_string()
        }
        fn device_name(&self) -> String {
            "Desk".into()
        }
        fn peers(&self) -> Result<Vec<PairedPeer>, String> {
            self.store.lock().unwrap().peers().map_err(message)
        }
        fn save_peer(&self, peer: &PairedPeer) -> Result<(), String> {
            self.store.lock().unwrap().save_peer(peer).map_err(message)
        }
        fn vector(&self) -> Result<Vector, String> {
            self.store.lock().unwrap().sync_vector().map_err(message)
        }
        fn delta_since(&self, theirs: &Vector) -> Result<Delta, String> {
            self.store.lock().unwrap().delta_since(theirs).map_err(message)
        }
        fn apply(&self, delta: &Delta) -> Result<ApplyReport, String> {
            self.store.lock().unwrap().apply_delta(delta).map_err(message)
        }
        fn reset_for_pairing(&self) -> Result<(), String> {
            Err("no".into())
        }
        fn missing_images(&self) -> Vec<String> {
            Vec::new()
        }
        fn read_image(&self, name: &str) -> Option<Vec<u8>> {
            fs::read(self.images.join(name)).ok()
        }
        fn store_image(&self, _: &str, _: &[u8]) -> Result<(), String> {
            Ok(())
        }
        fn session_finished(&self, _: &str, _: &Vector) -> Result<(), String> {
            Ok(())
        }
        fn confirm_pairing(&self, _: &str) -> bool {
            true
        }
    }

    struct Reader {
        accept: bool,
        books_seen: AtomicU32,
        free: std::sync::atomic::AtomicU64,
    }

    fn reader(accept: bool) -> Arc<Reader> {
        Arc::new(Reader { accept, books_seen: AtomicU32::new(0), free: std::sync::atomic::AtomicU64::new(u64::MAX) })
    }

    impl SyncListener for Reader {
        fn confirm_computer(&self, _: String) -> bool {
            self.accept
        }
        fn progress(&self, books_received: u32, _: u32, _: u32, _: u32) {
            self.books_seen.fetch_max(books_received, Ordering::SeqCst);
        }
        fn free_bytes(&self) -> u64 {
            self.free.load(Ordering::SeqCst)
        }
    }

    fn desk() -> (Arc<Desk>, Server) {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(&dir.path().join("library.sqlite3"), &dir.path().join("backups")).unwrap();
        let book = CoreBook { title: "Ѓердан од ќерамиди".into(), ..CoreBook::new(0) };
        store.save_book(&book).unwrap();
        let desk = Arc::new(Desk { store: Mutex::new(store), images: dir.path().join("images"), _dir: dir });
        let server = Server::start_on(desk.clone(), DeviceKey::generate(), IpAddr::V4(Ipv4Addr::LOCALHOST), 0).unwrap();
        (desk, server)
    }

    #[test]
    fn a_phone_pairs_by_code_takes_the_library_and_syncs_back() {
        let (desk, server) = desk();
        let (_dir, phone) = crate::tests_support::library();
        phone.set_device_name("Galaxy S25".into());
        let code = server.open_pairing(vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]).code;
        let reader = reader(true);
        let paired = phone.pair_by_code(format!("127.0.0.1:{}", server.port()), code, reader.clone()).unwrap();
        assert!(paired.changed);
        assert_eq!(phone.books().iter().map(|b| b.title.as_str()).collect::<Vec<_>>(), ["Ѓердан од ќерамиди"]);
        assert_eq!(reader.books_seen.load(Ordering::SeqCst), 1);
        let computer = phone.computer().unwrap();
        assert_eq!(computer.name, "Desk");
        assert!(computer.last_session.is_some() && !computer.pending);
        assert_eq!(desk.peers().unwrap()[0].name, "Galaxy S25");

        let mut book = phone.books()[0].clone();
        book.current_page = 12;
        book.total_pages = Some(200);
        assert!(phone.save_book(book.clone()).unwrap().saved);
        assert!(phone.computer().unwrap().pending, "REQ-34a");
        phone.sync_now(reader.clone()).unwrap();
        assert!(!phone.computer().unwrap().pending);
        let on_desk = desk.store.lock().unwrap().load_all().unwrap().books[0].clone();
        assert_eq!((on_desk.current_page, on_desk.total_pages), (12, Some(200)));

        phone.forget_computer().unwrap();
        assert!(phone.computer().is_none());
        assert!(matches!(phone.sync_now(reader), Err(SyncFailure::NotPaired { .. })));
        assert_eq!(phone.books().len(), 1, "REQ-21: the books stay on the phone");
    }

    #[test]
    fn declining_on_the_phone_pairs_nothing() {
        let (desk, server) = desk();
        let (_dir, phone) = crate::tests_support::library();
        let code = server.open_pairing(vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]).code;
        let reader = reader(false);
        assert!(matches!(phone.pair_by_code(format!("127.0.0.1:{}", server.port()), code, reader), Err(SyncFailure::Declined { .. })));
        assert!(phone.computer().is_none() && desk.peers().unwrap().is_empty());
    }

    #[test]
    fn a_full_phone_stores_nothing_and_says_how_much_it_needs() {
        let (desk, server) = desk();
        let (_dir, phone) = crate::tests_support::library();
        let code = server.open_pairing(vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]).code;
        let reader = reader(true);
        reader.free.store(1024 * 1024, Ordering::SeqCst);
        match phone.pair_by_code(format!("127.0.0.1:{}", server.port()), code, reader.clone()) {
            Err(SyncFailure::Other { reason }) => assert!(reason.starts_with("Not enough storage on this phone. Booklub needs ") && reason.ends_with(" MB more."), "{reason}"),
            other => panic!("expected the storage message, got {other:?}"),
        }
        // REQ-42b: nothing from the session was stored, and the next session, with room, works.
        assert!(phone.books().is_empty());
        reader.free.store(u64::MAX, Ordering::SeqCst);
        phone.sync_now(reader).unwrap();
        assert_eq!(phone.books().len(), desk.store.lock().unwrap().load_all().unwrap().books.len());
    }

    #[test]
    fn an_editor_open_across_a_sync_keeps_the_computers_changes() {
        let (desk, server) = desk();
        let (_dir, phone) = crate::tests_support::library();
        let code = server.open_pairing(vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]).code;
        let reader = reader(true);
        phone.pair_by_code(format!("127.0.0.1:{}", server.port()), code, reader.clone()).unwrap();
        let original = phone.books()[0].clone();
        {
            let store = desk.store.lock().unwrap();
            let mut on_desk = store.load_all().unwrap().books[0].clone();
            on_desk.notes = "Typed on the computer".into();
            store.save_book(&on_desk).unwrap();
        }
        phone.sync_now(reader.clone()).unwrap();
        let mut edited = original.clone();
        edited.rating = Some(5);
        assert!(phone.save_edited_book(original, edited).unwrap().saved);
        let saved = phone.books()[0].clone();
        assert_eq!((saved.rating, saved.notes.as_str()), (Some(5), "Typed on the computer"));
    }

    #[test]
    fn addresses_read_with_or_without_a_port() {
        assert_eq!(parse_address("192.168.1.20:47121"), Some("192.168.1.20:47121".parse().unwrap()));
        assert_eq!(parse_address(" 192.168.1.20 "), Some("192.168.1.20:47120".parse().unwrap()));
        assert_eq!(parse_address("[fe80::1]:47120"), Some("[fe80::1]:47120".parse().unwrap()));
        assert_eq!(parse_address("my-pc"), None);
    }
}
