# KothaPad Optimization - Phase 1 Report: Batch SAF Directory Queries

## Summary of Changes
Phase 1 eliminates the O(N^2) cross-process Binder IPC overhead associated with querying individual properties on `DocumentFile` objects for every file and folder inside the vault.

### 1. New Helper & Fast Lister (`SafFastLister.kt`)
- Created `SafFastLister` with `listChildrenFast(treeUri, parentDocumentId)` which executes **ONE** `ContentResolver.query` on `DocumentsContract.buildChildDocumentsUriUsingTree` with projection (`COLUMN_DOCUMENT_ID`, `COLUMN_DISPLAY_NAME`, `COLUMN_MIME_TYPE`, `COLUMN_LAST_MODIFIED`, `COLUMN_SIZE`).
- Introduced `FastEntry` data class holding metadata and providing convenient URI builders to convert back to `DocumentFile` only when mutation or stream opening is needed.
- Added an in-memory directory cache (`Map<parentDocumentId, List<FastEntry>>`) with invalidation methods on any write, create, rename, or delete operation.
- Added robust try/catch fallback to standard `DocumentFile` traversal if a DocumentsProvider does not support the batch query.

### 2. VaultManager Optimization
- Replaced per-child `listFiles()`, `findFile()`, and property getters (`name`, `isFile`, `isDirectory`, `lastModified()`, `length()`) inside loops with `listChildrenFast` and name-to-entry lookups.
- Added directory cache invalidation (`invalidateDirCache`, `clearDirCache`) on mutations (`rename`, `move`, `delete`, `createFile`, `createDir`, `writeTextAtomic`).

### 3. VaultNoteRepository & VaultConfigStore & BackupManager & VaultMigrationManager
- **VaultNoteRepository**: Optimized `scanVaultRecursive`, trash reconciliation, template synchronization, folder operations, and trashing collision checks to use `listChildrenFast`.
- **VaultConfigStore**: Optimized `.KOTHAPAD` configuration directory resolution, file lookup (`getOrCreateFileInternal`), corrupted file/backup recovery, and duplicate cleanup/migration (`cleanupAndMigrateDuplicatesInternal`) to avoid repeated `listFiles()` IPC calls.
- **BackupManager**: Optimized ZIP archive packaging and rollback/snapshotting to use batch queries.
- **VaultMigrationManager**: Optimized vault data existence checks, migration snapshots, and rollbacks.

## Performance Impact
- Vault scans and directory resolutions now execute in a single batch query per folder rather than hundreds or thousands of individual IPC calls.
- UI responsiveness during startup, folder navigation, note searching, and syncing is significantly improved.
