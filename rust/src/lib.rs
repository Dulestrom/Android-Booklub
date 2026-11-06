//! Booklub's shared Rust core, offered to the Android app through UniFFI.
//!
//! The phone runs the same rules, storage, and statistics as the desktop (Android SRS QA-40).
//! This crate holds only what the desktop keeps in its GTK layer: keeping memory and the store
//! in step, and choosing which books a view shows.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};

use booklub_core::model::new_id;
use booklub_core::paths::Paths;
use booklub_core::query::{self, CategoryIndex, HeadingLevel, SortKey};
use booklub_core::store::Store;
use booklub_core::{background, goals, isbn, rules, stats};
use booklub_core::{Category as CoreCategory, CategoryBackground, GoalId, Library, Priority, Status, SubCategory as CoreSub};

uniffi::setup_scaffolding!();

mod sync;
pub use sync::{Computer, Invite, SyncFailure, SyncListener, SyncResult};

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum BooklubError {
    /// A read or write failed. The message names the operation, as the desktop banner does.
    // Named `reason`, because Kotlin exceptions already have a `message`.
    #[error("{reason}")]
    Store { reason: String },
    #[error("{reason}")]
    Image { reason: String },
    #[error("That item no longer exists.")]
    NotFound,
}

pub(crate) fn store_error(error: booklub_core::store::StoreError) -> BooklubError {
    BooklubError::Store { reason: error.message }
}

fn image_error(error: background::ImageError) -> BooklubError {
    BooklubError::Image { reason: error.to_string() }
}

// ---------- Records ----------

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Book {
    pub id: String,
    pub title: String,
    pub author: String,
    pub category_id: Option<String>,
    pub sub_category_id: Option<String>,
    /// "High", "Medium", or "Low".
    pub priority: String,
    /// "To Read", "Reading", or "Finished".
    pub status: String,
    pub total_pages: Option<u32>,
    pub current_page: u32,
    pub start_date: Option<String>,
    pub finish_date: Option<String>,
    pub rating: Option<u8>,
    pub notes: String,
    pub order: i64,
    /// The fractional key that sets manual order across devices (SRS REQ-32f).
    #[uniffi(default = "")]
    pub sort_key: String,
    pub added_at: String,
    pub isbn: Option<String>,
    pub publisher: String,
    pub published_year: Option<i32>,
    pub language: String,
    pub background_file: Option<String>,
}

impl From<&booklub_core::Book> for Book {
    fn from(book: &booklub_core::Book) -> Self {
        Self {
            id: book.id.clone(),
            title: book.title.clone(),
            author: book.author.clone(),
            category_id: book.category_id.clone(),
            sub_category_id: book.sub_category_id.clone(),
            priority: book.priority.as_str().to_string(),
            status: book.status.as_str().to_string(),
            total_pages: book.total_pages,
            current_page: book.current_page,
            start_date: book.start_date.clone(),
            finish_date: book.finish_date.clone(),
            rating: book.rating,
            notes: book.notes.clone(),
            order: book.order,
            sort_key: book.sort_key.clone(),
            added_at: book.added_at.clone(),
            isbn: book.isbn.clone(),
            publisher: book.publisher.clone(),
            published_year: book.published_year,
            language: book.language.clone(),
            background_file: book.background_file.clone(),
        }
    }
}

impl Book {
    fn to_core(&self) -> booklub_core::Book {
        booklub_core::Book {
            id: self.id.clone(),
            title: self.title.clone(),
            author: self.author.clone(),
            category_id: self.category_id.clone(),
            sub_category_id: self.sub_category_id.clone(),
            priority: Priority::parse(&self.priority).unwrap_or(Priority::Medium),
            status: Status::parse(&self.status).unwrap_or(Status::ToRead),
            total_pages: self.total_pages,
            current_page: self.current_page,
            start_date: self.start_date.clone(),
            finish_date: self.finish_date.clone(),
            rating: self.rating,
            notes: self.notes.clone(),
            order: self.order,
            sort_key: self.sort_key.clone(),
            added_at: self.added_at.clone(),
            isbn: self.isbn.clone(),
            publisher: self.publisher.clone(),
            published_year: self.published_year,
            language: self.language.clone(),
            background_file: self.background_file.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SubCategory {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Category {
    pub id: String,
    pub name: String,
    pub sub_categories: Vec<SubCategory>,
    /// "preset:<id>" for built-in artwork, "file:<name>" for an image, or none.
    pub background: Option<String>,
}

impl From<&CoreCategory> for Category {
    fn from(category: &CoreCategory) -> Self {
        Self {
            id: category.id.clone(),
            name: category.name.clone(),
            sub_categories: category.sub_categories.iter().map(|sub| SubCategory { id: sub.id.clone(), name: sub.name.clone() }).collect(),
            background: category.background.as_ref().map(CategoryBackground::encode),
        }
    }
}

/// A Category tile's numbers and the image it shows (desktop REQ-45f).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CategorySummary {
    pub category: Category,
    pub books: u32,
    pub finished: u32,
    /// A full path to an image file, or "preset:<id>" for built-in artwork.
    pub art: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HomeSummary {
    pub categories: Vec<CategorySummary>,
    pub uncategorized_books: u32,
    pub uncategorized_finished: u32,
    pub reading: Vec<Book>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum Scope {
    All,
    Status { status: String },
    /// No id means the Uncategorized tile.
    Category { id: Option<String>, sub: SubScope },
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum SubScope {
    All,
    Sub { id: String },
    NoSub,
}

#[derive(Debug, Clone, Default, PartialEq, uniffi::Record)]
pub struct Filters {
    pub search: String,
    pub category_ids: Vec<String>,
    pub sub_category_ids: Vec<String>,
    pub statuses: Vec<String>,
    pub priorities: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Heading {
    pub title: String,
    pub count: u32,
    pub major: bool,
    pub first_book: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BookList {
    pub books: Vec<Book>,
    pub headings: Vec<Heading>,
    /// Books in the view's scope before filters and search, for "Showing 5 of 96".
    pub scope_total: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Count {
    pub key: String,
    pub count: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Facets {
    pub statuses: Vec<Count>,
    pub priorities: Vec<Count>,
    pub categories: Vec<Count>,
    pub sub_categories: Vec<Count>,
    pub total: u32,
}

#[derive(Debug, Clone, Default, PartialEq, uniffi::Record)]
pub struct FieldErrors {
    pub title: Option<String>,
    pub total_pages: Option<String>,
    pub current_page: Option<String>,
    pub start_date: Option<String>,
    pub finish_date: Option<String>,
    pub rating: Option<String>,
    pub isbn: Option<String>,
    pub published_year: Option<String>,
}

impl FieldErrors {
    fn from_core(errors: rules::FieldErrors) -> Self {
        Self {
            title: errors.title,
            total_pages: errors.total_pages,
            current_page: errors.current_page,
            start_date: errors.start_date,
            finish_date: errors.finish_date,
            rating: errors.rating,
            isbn: errors.isbn,
            published_year: errors.published_year,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SaveResult {
    pub saved: bool,
    pub errors: FieldErrors,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Goal {
    /// "booksPerYear" or "booksPerMonth".
    pub id: String,
    pub label: String,
    pub target: u32,
    pub active: bool,
    pub achieved: u32,
    pub percent: u32,
    pub remaining: u32,
    pub per_week_needed: f64,
    /// "Ahead", "On Track", or "Behind".
    pub pacing: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CategoryCount {
    pub name: String,
    pub count: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PriorityRow {
    pub priority: String,
    pub reading: u32,
    pub to_read: u32,
    pub finished: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Stats {
    pub total: u32,
    pub reading: u32,
    pub to_read: u32,
    pub finished: u32,
    pub finished_this_year: u32,
    pub finished_by_month: Vec<u32>,
    pub mean_days_to_finish: Option<f64>,
    pub mean_rating: Option<f64>,
    pub by_category: Vec<CategoryCount>,
    pub uncategorized: u32,
    pub thinnest: Vec<String>,
    pub priority_by_status: Vec<PriorityRow>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ImageCheck {
    pub small: bool,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Preset {
    pub id: String,
    pub title: String,
}

// ---------- Pure rules, so the editor can show their effects before Save ----------

#[uniffi::export]
pub fn apply_status(book: Book, status: String) -> Book {
    let mut core = book.to_core();
    let status = Status::parse(&status).unwrap_or(core.status);
    rules::apply_status_change(&mut core, status);
    Book::from(&core)
}

#[uniffi::export]
pub fn apply_current_page(book: Book, page: u32) -> Book {
    let mut core = book.to_core();
    rules::apply_page_change(&mut core, page);
    Book::from(&core)
}

#[uniffi::export]
pub fn apply_total_pages(book: Book, total: Option<u32>) -> Book {
    let mut core = book.to_core();
    rules::apply_total_pages_change(&mut core, total);
    Book::from(&core)
}

#[uniffi::export]
pub fn apply_category(book: Book, category_id: Option<String>) -> Book {
    let mut core = book.to_core();
    rules::apply_category_change(&mut core, category_id);
    Book::from(&core)
}

#[uniffi::export]
pub fn validate(book: Book) -> FieldErrors {
    FieldErrors::from_core(rules::validate(&book.to_core()))
}

#[uniffi::export]
pub fn completion_percent(book: Book) -> u32 {
    rules::completion_percent(&book.to_core())
}

#[uniffi::export]
pub fn is_at_last_page(book: Book) -> bool {
    rules::is_at_last_page(&book.to_core())
}

#[uniffi::export]
pub fn initials(title: String) -> String {
    rules::initials(&title)
}

#[uniffi::export]
pub fn presets() -> Vec<Preset> {
    background::PRESETS.iter().map(|(id, title)| Preset { id: id.to_string(), title: title.to_string() }).collect()
}

/// Checks a picked or pasted image against the desktop's limits, before any decoding (REQ-15c).
#[uniffi::export]
pub fn check_image(bytes: Vec<u8>) -> Result<ImageCheck, BooklubError> {
    let checked = background::check(bytes).map_err(image_error)?;
    Ok(ImageCheck { small: checked.small, width: checked.width, height: checked.height })
}

// ---------- The library ----------

#[derive(uniffi::Object)]
pub struct Booklub {
    store: Mutex<Store>,
    library: Mutex<Library>,
    paths: Paths,
    device_name: Mutex<String>,
    /// One pairing or session at a time.
    syncing: Mutex<()>,
}

pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    // A panic elsewhere would poison the lock, but the data it guards is still consistent,
    // because every write goes to the store before memory changes.
    mutex.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[uniffi::export]
impl Booklub {
    /// Opens or creates the library under the app's own storage. `images_dir` is where
    /// background thumbnails go (Android SRS REQ-42).
    #[uniffi::constructor]
    pub fn open(data_dir: String, images_dir: String, cache_dir: String) -> Result<Arc<Self>, BooklubError> {
        let mut paths = Paths::under(PathBuf::from(&data_dir), PathBuf::from(&cache_dir).join("thumbnails-root"));
        paths.backgrounds = PathBuf::from(images_dir);
        paths.thumbnails = PathBuf::from(cache_dir).join("thumbnails");
        paths.ensure().map_err(|error| BooklubError::Store { reason: format!("Booklub could not create its folders: {error}") })?;
        let store = Store::open(&paths.database, &paths.backups).map_err(store_error)?;
        let library = store.load_all().map_err(store_error)?;
        if let Ok(referenced) = store.referenced_backgrounds() {
            background::remove_unreferenced(&referenced, &paths.backgrounds, &paths.thumbnails);
        }
        Ok(Arc::new(Self { store: Mutex::new(store), library: Mutex::new(library), paths, device_name: Mutex::default(), syncing: Mutex::default() }))
    }

    pub fn books(&self) -> Vec<Book> {
        lock(&self.library).books.iter().map(Book::from).collect()
    }

    pub fn book(&self, id: String) -> Option<Book> {
        lock(&self.library).books.iter().find(|book| book.id == id).map(Book::from)
    }

    pub fn categories(&self) -> Vec<Category> {
        lock(&self.library).categories.iter().map(Category::from).collect()
    }

    pub fn category_name(&self, id: Option<String>) -> String {
        let library = lock(&self.library);
        CategoryIndex::new(&library.categories).category_name(id.as_deref()).to_string()
    }

    pub fn sub_category_name(&self, id: Option<String>) -> String {
        let library = lock(&self.library);
        CategoryIndex::new(&library.categories).sub_category_name(id.as_deref()).to_string()
    }

    /// A blank book, placed after every existing one (REQ-13c).
    pub fn new_book(&self) -> Book {
        let order = query::next_position(&lock(&self.library).books);
        Book::from(&booklub_core::Book::new(order))
    }

    /// The tiles Home shows, and the books in progress for Continue Reading.
    pub fn home(&self) -> HomeSummary {
        let library = lock(&self.library);
        let mut uncategorized = (0, 0);
        let categories = library
            .categories
            .iter()
            .map(|category| {
                let books: Vec<&booklub_core::Book> = library.books.iter().filter(|book| book.category_id.as_deref() == Some(&category.id)).collect();
                let finished = books.iter().filter(|book| book.status == Status::Finished).count() as u32;
                let art = match &category.background {
                    Some(CategoryBackground::Preset(id)) => Some(format!("preset:{id}")),
                    Some(CategoryBackground::File(name)) => self.wide_path(name),
                    None => books.iter().find_map(|book| book.background_file.as_deref()).and_then(|file| self.thumbnail_path(file)),
                };
                CategorySummary { category: Category::from(category), books: books.len() as u32, finished, art }
            })
            .collect();
        for book in library.books.iter().filter(|book| book.category_id.is_none()) {
            uncategorized.0 += 1;
            if book.status == Status::Finished {
                uncategorized.1 += 1;
            }
        }
        let reading = library.books.iter().filter(|book| book.status == Status::Reading).map(Book::from).collect();
        HomeSummary { categories, uncategorized_books: uncategorized.0, uncategorized_finished: uncategorized.1, reading }
    }

    /// The books a view shows: its scope, then filters and search, then the sort, with headings
    /// for Priority and Category (desktop REQ-08j to REQ-08m).
    pub fn view(&self, scope: Scope, filters: Filters, sort: String) -> BookList {
        let library = lock(&self.library);
        let in_scope: Vec<booklub_core::Book> = library
            .books
            .iter()
            .filter(|book| match &scope {
                Scope::All => true,
                Scope::Status { status } => Some(book.status) == Status::parse(status),
                Scope::Category { id: None, .. } => book.category_id.is_none(),
                Scope::Category { id: Some(id), sub } => {
                    book.category_id.as_deref() == Some(id.as_str())
                        && match sub {
                            SubScope::All => true,
                            SubScope::Sub { id } => book.sub_category_id.as_deref() == Some(id.as_str()),
                            SubScope::NoSub => book.sub_category_id.is_none(),
                        }
                }
            })
            .cloned()
            .collect();
        let core_filters = query::Filters {
            search: filters.search,
            category_ids: filters.category_ids,
            sub_category_ids: filters.sub_category_ids,
            statuses: filters.statuses.iter().filter_map(|status| Status::parse(status)).collect(),
            priorities: filters.priorities.iter().filter_map(|priority| Priority::parse(priority)).collect(),
        };
        let index = CategoryIndex::new(&library.categories);
        let key = sort_key(&sort);
        let found: Vec<booklub_core::Book> = query::filter_books(&in_scope, &core_filters).into_iter().cloned().collect();
        let sorted = query::sort_books(&found, key, &index);
        let headings = query::headings(&sorted, key, &index);
        let headings = match &scope {
            Scope::Category { id: Some(_), sub: SubScope::All } => headings.into_iter().filter(|heading| heading.level == HeadingLevel::Minor).collect(),
            Scope::Category { .. } if key == SortKey::Category => Vec::new(),
            _ => headings,
        };
        BookList {
            books: sorted.iter().map(Book::from).collect(),
            headings: headings
                .into_iter()
                .map(|heading| Heading { title: heading.title, count: heading.count as u32, major: heading.level == HeadingLevel::Major, first_book: heading.first_book as u32 })
                .collect(),
            scope_total: in_scope.len() as u32,
        }
    }

    /// Counts for the status chips and the filter sheet.
    pub fn facets(&self) -> Facets {
        let library = lock(&self.library);
        let counts = query::count_facets(&library.books);
        let list = |pairs: Vec<(String, usize)>| pairs.into_iter().map(|(key, count)| Count { key, count: count as u32 }).collect();
        Facets {
            statuses: list(Status::ALL.iter().map(|status| (status.as_str().to_string(), counts.statuses.get(status).copied().unwrap_or(0))).collect()),
            priorities: list(Priority::ALL.iter().map(|priority| (priority.as_str().to_string(), counts.priorities.get(priority).copied().unwrap_or(0))).collect()),
            categories: list(counts.categories.into_iter().collect()),
            sub_categories: list(counts.sub_categories.into_iter().collect()),
            total: library.books.len() as u32,
        }
    }

    /// The first other book with this title and author, for the duplicate warning (REQ-13b).
    pub fn duplicate_of(&self, book: Book) -> Option<Book> {
        let library = lock(&self.library);
        rules::find_duplicate(&book.to_core(), &library.books).map(Book::from)
    }

    /// Validates, then stores the book. A replaced background is deleted once the new one is saved.
    /// Saves the editor's changes to a book that was `original` when the editor opened. A sync
    /// while the editor was open may have changed other fields, and those stay (SRS REQ-32b).
    pub fn save_edited_book(&self, original: Book, edited: Book) -> Result<SaveResult, BooklubError> {
        let current = lock(&self.library).books.iter().find(|book| book.id == edited.id).cloned();
        match current {
            Some(current) => {
                let rebased = booklub_core::sync::rebase(&original.to_core(), &edited.to_core(), &current);
                self.save_book(Book::from(&rebased))
            }
            None => self.save_book(edited),
        }
    }

    pub fn save_book(&self, book: Book) -> Result<SaveResult, BooklubError> {
        let mut core = book.to_core();
        let errors = rules::validate(&core);
        if !errors.is_empty() {
            return Ok(SaveResult { saved: false, errors: FieldErrors::from_core(errors) });
        }
        core.title = core.title.trim().to_string();
        core.author = core.author.trim().to_string();
        core.isbn = core.isbn.as_deref().map(str::trim).filter(|value| !value.is_empty()).map(|value| isbn::normalize(value).unwrap_or_else(|_| value.to_string()));
        lock(&self.store).save_book(&core).map_err(store_error)?;
        let replaced = {
            let mut library = lock(&self.library);
            let replaced = match library.books.iter().position(|existing| existing.id == core.id) {
                Some(index) => {
                    let previous = library.books[index].background_file.clone();
                    library.books[index] = core.clone();
                    previous.filter(|file| Some(file) != core.background_file.as_ref())
                }
                None => {
                    library.books.push(core.clone());
                    None
                }
            };
            library.books.sort_by(query::in_manual_order);
            replaced
        };
        if let Some(file) = replaced {
            background::remove(&file, &self.paths.backgrounds, &self.paths.thumbnails);
        }
        Ok(SaveResult { saved: true, errors: FieldErrors::default() })
    }

    /// REQ-14a: deletes the book and its background, then closes the gap in manual order.
    pub fn delete_book(&self, id: String) -> Result<(), BooklubError> {
        let Some(book) = lock(&self.library).books.iter().find(|book| book.id == id).cloned() else { return Err(BooklubError::NotFound) };
        let store = lock(&self.store);
        store.delete_book(&book).map_err(store_error)?;
        if let Some(file) = &book.background_file {
            background::remove(file, &self.paths.backgrounds, &self.paths.thumbnails);
        }
        let changed = {
            let mut library = lock(&self.library);
            library.books.retain(|existing| existing.id != id);
            query::renumber(&mut library.books)
        };
        store.save_order(&changed).map_err(store_error)
    }

    /// Stores a picked or pasted image as the phone's thumbnail, and returns its file name. The
    /// book refers to it only once the Reader saves (REQ-15d, REQ-15e).
    pub fn store_book_image(&self, bytes: Vec<u8>) -> Result<String, BooklubError> {
        let checked = background::check(bytes).map_err(image_error)?;
        let reduced = background::make_thumbnail(&background::decode(&checked.bytes).map_err(image_error)?);
        let thumbnail = jpeg_image(&reduced)?;
        background::store(&thumbnail, &self.paths.backgrounds, &self.paths.thumbnails).map_err(image_error)
    }

    /// Removes an image stored by `store_book_image` that the Reader then discarded.
    pub fn discard_image(&self, file_name: String) {
        let referenced = lock(&self.library).background_files();
        if !referenced.contains(&file_name) {
            background::remove(&file_name, &self.paths.backgrounds, &self.paths.thumbnails);
        }
    }

    /// The path to show for a background file, building its thumbnail when it is missing.
    pub fn thumbnail_path(&self, file_name: &str) -> Option<String> {
        background::ensure_thumbnail(file_name, &self.paths.backgrounds, &self.paths.thumbnails).ok().map(|path| path.to_string_lossy().into_owned())
    }

    pub fn add_category(&self, name: String) -> Result<Category, BooklubError> {
        let name = name.trim().to_string();
        let category = CoreCategory { id: new_id(), name, sub_categories: vec![], background: None };
        let position = lock(&self.library).categories.len();
        lock(&self.store).save_category(&category, position).map_err(store_error)?;
        lock(&self.library).categories.push(category.clone());
        Ok(Category::from(&category))
    }

    pub fn rename_category(&self, id: String, name: String) -> Result<(), BooklubError> {
        let name = name.trim().to_string();
        self.update_category(&id, |category| category.name = name)
    }

    pub fn add_sub_category(&self, category_id: String, name: String) -> Result<(), BooklubError> {
        let name = name.trim().to_string();
        self.update_category(&category_id, |category| category.sub_categories.push(CoreSub { id: new_id(), name }))
    }

    pub fn rename_sub_category(&self, category_id: String, sub_id: String, name: String) -> Result<(), BooklubError> {
        let name = name.trim().to_string();
        self.update_category(&category_id, |category| {
            if let Some(sub) = category.sub_categories.iter_mut().find(|sub| sub.id == sub_id) {
                sub.name = name;
            }
        })
    }

    /// Sets "preset:<id>", or none to remove the background (REQ-12a).
    pub fn set_category_preset(&self, category_id: String, preset: Option<String>) -> Result<(), BooklubError> {
        let old = self.category_file(&category_id);
        self.update_category(&category_id, |category| category.background = preset.map(CategoryBackground::Preset))?;
        if let Some(file) = old {
            background::remove(&file, &self.paths.backgrounds, &self.paths.thumbnails);
        }
        Ok(())
    }

    /// Stores a picked or pasted image as a Category's background, as a landscape thumbnail.
    pub fn set_category_image(&self, category_id: String, bytes: Vec<u8>) -> Result<(), BooklubError> {
        let checked = background::check(bytes).map_err(image_error)?;
        let decoded = background::decode(&checked.bytes).map_err(image_error)?;
        let reduced = decoded.resize_to_fill(background::WIDE_WIDTH, background::WIDE_HEIGHT, image::imageops::FilterType::Triangle);
        let file = background::store_for_category(&jpeg_image(&reduced)?, &self.paths.backgrounds, &self.paths.thumbnails).map_err(image_error)?;
        let old = self.category_file(&category_id);
        self.update_category(&category_id, |category| category.background = Some(CategoryBackground::File(file.clone())))?;
        if let Some(old) = old {
            background::remove(&old, &self.paths.backgrounds, &self.paths.thumbnails);
        }
        Ok(())
    }

    /// REQ-12d: the books stay, without this Category.
    pub fn delete_category(&self, id: String) -> Result<(), BooklubError> {
        let Some(category) = lock(&self.library).categories.iter().find(|c| c.id == id).cloned() else { return Err(BooklubError::NotFound) };
        lock(&self.store).delete_category(&category).map_err(store_error)?;
        if let Some(file) = category.background.as_ref().and_then(CategoryBackground::file_name) {
            background::remove(file, &self.paths.backgrounds, &self.paths.thumbnails);
        }
        let mut library = lock(&self.library);
        library.categories.retain(|c| c.id != id);
        for book in library.books.iter_mut().filter(|book| book.category_id.as_deref() == Some(id.as_str())) {
            book.category_id = None;
            book.sub_category_id = None;
        }
        Ok(())
    }

    /// REQ-12f: the books keep their Category.
    pub fn delete_sub_category(&self, category_id: String, sub_id: String) -> Result<(), BooklubError> {
        lock(&self.store).delete_sub_category(&sub_id).map_err(store_error)?;
        let mut library = lock(&self.library);
        if let Some(category) = library.categories.iter_mut().find(|c| c.id == category_id) {
            category.sub_categories.retain(|sub| sub.id != sub_id);
        }
        for book in library.books.iter_mut().filter(|book| book.sub_category_id.as_deref() == Some(sub_id.as_str())) {
            book.sub_category_id = None;
        }
        Ok(())
    }

    pub fn books_in_category(&self, id: String) -> u32 {
        lock(&self.library).books.iter().filter(|book| book.category_id.as_deref() == Some(id.as_str())).count() as u32
    }

    pub fn books_in_sub_category(&self, sub_id: String) -> u32 {
        lock(&self.library).books.iter().filter(|book| book.sub_category_id.as_deref() == Some(sub_id.as_str())).count() as u32
    }

    pub fn goals(&self) -> Vec<Goal> {
        let library = lock(&self.library);
        let now = chrono::Local::now().naive_local();
        library
            .goals
            .iter()
            .map(|goal| {
                let progress = goals::goal_progress(goal, &library.books, now);
                Goal {
                    id: goal.id.as_str().to_string(),
                    label: progress.label.to_string(),
                    target: goal.target,
                    active: goal.active,
                    achieved: progress.achieved,
                    percent: progress.percent,
                    remaining: progress.remaining,
                    per_week_needed: progress.per_week_needed,
                    pacing: progress.pacing.label().to_string(),
                }
            })
            .collect()
    }

    /// REQ-45a: a target of 1 or more, under desktop REQ-14g.
    pub fn save_goal(&self, id: String, target: u32, active: bool) -> Result<(), BooklubError> {
        let goal_id = GoalId::parse(&id).ok_or(BooklubError::NotFound)?;
        let goal = booklub_core::Goal { id: goal_id, target: target.max(1), active };
        lock(&self.store).save_goal(&goal).map_err(store_error)?;
        if let Some(existing) = lock(&self.library).goals.iter_mut().find(|existing| existing.id == goal_id) {
            *existing = goal;
        }
        Ok(())
    }

    pub fn stats(&self) -> Stats {
        let library = lock(&self.library);
        let computed = stats::compute(&library.books, &library.categories, chrono::Local::now().naive_local());
        let status = |s: Status| computed.by_status.get(&s).copied().unwrap_or(0) as u32;
        let cell = |p: Priority, s: Status| computed.priority_by_status.get(&(p, s)).copied().unwrap_or(0) as u32;
        Stats {
            total: computed.total as u32,
            reading: status(Status::Reading),
            to_read: status(Status::ToRead),
            finished: status(Status::Finished),
            finished_this_year: computed.finished_this_year,
            finished_by_month: computed.finished_by_month.to_vec(),
            mean_days_to_finish: computed.mean_days_to_finish,
            mean_rating: computed.mean_rating,
            by_category: computed.by_category.iter().map(|c| CategoryCount { name: c.name.clone(), count: c.count as u32 }).collect(),
            uncategorized: computed.uncategorized as u32,
            thinnest: computed.thinnest.iter().map(|c| c.name.clone()).collect(),
            priority_by_status: Priority::ALL
                .iter()
                .map(|p| PriorityRow { priority: p.as_str().to_string(), reading: cell(*p, Status::Reading), to_read: cell(*p, Status::ToRead), finished: cell(*p, Status::Finished) })
                .collect(),
        }
    }
}

impl Booklub {
    fn update_category(&self, id: &str, change: impl FnOnce(&mut CoreCategory)) -> Result<(), BooklubError> {
        let (position, mut category) = {
            let library = lock(&self.library);
            let position = library.categories.iter().position(|c| c.id == id).ok_or(BooklubError::NotFound)?;
            (position, library.categories[position].clone())
        };
        change(&mut category);
        lock(&self.store).save_category(&category, position).map_err(store_error)?;
        lock(&self.library).categories[position] = category;
        Ok(())
    }

    fn category_file(&self, id: &str) -> Option<String> {
        let library = lock(&self.library);
        library.categories.iter().find(|c| c.id == id)?.background.as_ref()?.file_name().map(str::to_string)
    }

    fn wide_path(&self, file_name: &str) -> Option<String> {
        background::ensure_wide_thumbnail(file_name, &self.paths.backgrounds, &self.paths.thumbnails).ok().map(|path| path.to_string_lossy().into_owned())
    }
}

fn sort_key(name: &str) -> SortKey {
    SortKey::ALL.into_iter().find(|key| key.label() == name).unwrap_or(SortKey::Manual)
}

/// A reduced image as a JPEG, wrapped so the core's storage checks accept it.
fn jpeg_image(image: &image::DynamicImage) -> Result<background::CheckedImage, BooklubError> {
    let mut bytes = Vec::new();
    image
        .to_rgb8()
        .write_to(&mut std::io::Cursor::new(&mut bytes), image::ImageFormat::Jpeg)
        .map_err(|_| BooklubError::Image { reason: "That image could not be saved. Your existing data has not been changed.".into() })?;
    background::check(bytes).map_err(image_error)
}

#[uniffi::export]
pub fn sort_keys() -> Vec<String> {
    SortKey::ALL.iter().map(|key| key.label().to_string()).collect()
}

#[cfg(test)]
pub(crate) mod tests_support {
    use super::*;

    pub(crate) fn library() -> (tempfile::TempDir, Arc<Booklub>) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let opened = Booklub::open(root.join("data").display().to_string(), root.join("images").display().to_string(), root.join("cache").display().to_string()).unwrap();
        (dir, opened)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn library() -> (tempfile::TempDir, Arc<Booklub>) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let opened = Booklub::open(root.join("data").display().to_string(), root.join("images").display().to_string(), root.join("cache").display().to_string()).unwrap();
        (dir, opened)
    }

    #[test]
    fn a_new_phone_library_starts_with_fiction_and_non_fiction() {
        let (_dir, booklub) = library();
        let names: Vec<String> = booklub.categories().into_iter().map(|c| c.name).collect();
        assert_eq!(names, ["Fiction", "Non-fiction"]);
        assert_eq!(booklub.goals().len(), 2);
    }

    #[test]
    fn a_saved_book_survives_reopening() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let open = || Booklub::open(root.join("d").display().to_string(), root.join("i").display().to_string(), root.join("c").display().to_string()).unwrap();
        let first = open();
        let mut book = first.new_book();
        book.title = "  Ѓердан од ќерамиди ".into();
        assert!(first.save_book(book.clone()).unwrap().saved);
        drop(first);
        let again = open();
        assert_eq!(again.book(book.id).unwrap().title, "Ѓердан од ќерамиди");
    }

    #[test]
    fn an_invalid_book_is_refused_with_the_desktop_messages() {
        let (_dir, booklub) = library();
        let mut book = booklub.new_book();
        book.isbn = Some("9780140328722".into());
        let result = booklub.save_book(book).unwrap();
        assert!(!result.saved);
        assert_eq!(result.errors.title.as_deref(), Some(rules::MSG_TITLE_REQUIRED));
        assert_eq!(result.errors.isbn.as_deref(), Some(rules::MSG_INVALID_ISBN));
        assert!(booklub.books().is_empty());
    }

    #[test]
    fn deleting_a_book_closes_the_gap_in_manual_order() {
        let (_dir, booklub) = library();
        let mut ids = Vec::new();
        for title in ["A", "B", "C"] {
            let mut book = booklub.new_book();
            book.title = title.into();
            ids.push(book.id.clone());
            booklub.save_book(book).unwrap();
        }
        booklub.delete_book(ids[1].clone()).unwrap();
        let orders: Vec<(String, i64)> = booklub.books().into_iter().map(|b| (b.title, b.order)).collect();
        assert_eq!(orders, [("A".to_string(), 0), ("C".to_string(), 1)]);
    }

    #[test]
    fn views_filter_sort_and_head_like_the_desktop() {
        let (_dir, booklub) = library();
        let fiction = booklub.categories()[0].clone();
        for (title, priority, status) in [("High one", "High", "Reading"), ("Low one", "Low", "To Read"), ("High two", "High", "Finished")] {
            let mut book = booklub.new_book();
            book.title = title.into();
            book.priority = priority.into();
            book.category_id = Some(fiction.id.clone());
            let book = apply_status(book, status.into());
            booklub.save_book(book).unwrap();
        }
        let list = booklub.view(Scope::All, Filters::default(), "Priority".into());
        assert_eq!(list.books.len(), 3);
        assert_eq!(list.headings.iter().map(|h| h.title.as_str()).collect::<Vec<_>>(), ["High Priority", "Low Priority"]);
        let reading = booklub.view(Scope::Status { status: "Reading".into() }, Filters::default(), "Manual order".into());
        assert_eq!(reading.books.len(), 1);
        let searched = booklub.view(Scope::All, Filters { search: "low".into(), ..Filters::default() }, "Title".into());
        assert_eq!(searched.books.len(), 1);
        assert_eq!(searched.scope_total, 3);
        let home = booklub.home();
        assert_eq!(home.categories[0].books, 3);
        assert_eq!(home.categories[0].finished, 1);
        assert_eq!(home.reading.len(), 1);
    }

    #[test]
    fn status_rules_match_the_desktop() {
        let (_dir, booklub) = library();
        let mut book = booklub.new_book();
        book.title = "Pages".into();
        let book = apply_total_pages(book, Some(100));
        let book = apply_current_page(book, 10);
        assert_eq!(book.status, "Reading");
        assert!(book.start_date.is_some());
        let book = apply_status(book, "Finished".into());
        assert_eq!(book.current_page, 100);
        assert!(book.finish_date.is_some());
    }

    #[test]
    fn categories_and_sub_categories_can_be_created_renamed_and_deleted() {
        let (_dir, booklub) = library();
        let shelf = booklub.add_category(" Poetry ".into()).unwrap();
        assert_eq!(shelf.name, "Poetry");
        booklub.add_sub_category(shelf.id.clone(), "Sonnets".into()).unwrap();
        booklub.rename_category(shelf.id.clone(), "Verse".into()).unwrap();
        let shelf = booklub.categories().into_iter().find(|c| c.id == shelf.id).unwrap();
        assert_eq!(shelf.name, "Verse");
        let sub = shelf.sub_categories[0].clone();
        let mut book = booklub.new_book();
        book.title = "Odes".into();
        book.category_id = Some(shelf.id.clone());
        book.sub_category_id = Some(sub.id.clone());
        let id = book.id.clone();
        booklub.save_book(book).unwrap();
        assert_eq!(booklub.books_in_sub_category(sub.id.clone()), 1);
        booklub.delete_sub_category(shelf.id.clone(), sub.id).unwrap();
        assert_eq!(booklub.book(id.clone()).unwrap().category_id, Some(shelf.id.clone()));
        booklub.delete_category(shelf.id).unwrap();
        assert_eq!(booklub.book(id).unwrap().category_id, None);
    }

    #[test]
    fn a_book_image_is_stored_as_a_thumbnail_and_a_replaced_one_is_deleted() {
        let (dir, booklub) = library();
        let mut png = Vec::new();
        image::DynamicImage::new_rgb8(900, 1350).write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png).unwrap();
        let first = booklub.store_book_image(png.clone()).unwrap();
        let mut book = booklub.new_book();
        book.title = "Pictured".into();
        book.background_file = Some(first.clone());
        booklub.save_book(book.clone()).unwrap();
        let stored = image::open(dir.path().join("images").join(&first)).unwrap();
        assert_eq!((stored.width(), stored.height()), (background::THUMB_WIDTH, background::THUMB_HEIGHT));
        let second = booklub.store_book_image(png).unwrap();
        book.background_file = Some(second);
        booklub.save_book(book).unwrap();
        assert!(!dir.path().join("images").join(&first).exists());
    }

    #[test]
    fn goals_can_be_retargeted_and_switched_off() {
        let (_dir, booklub) = library();
        booklub.save_goal("booksPerYear".into(), 30, false).unwrap();
        let goal = booklub.goals().into_iter().find(|g| g.id == "booksPerYear").unwrap();
        assert_eq!((goal.target, goal.active), (30, false));
        assert_eq!(booklub.stats().finished_by_month.len(), 12);
    }
}
