# MainBrain.md - GenAlert Project Specification & Architecture Truth

## 1. Project Overview & Role
- **Application Name**: GenAlert
- **Architecture**: Production-grade, ultra-lightweight native Android workstation & smart completion-alarm container for Google AI Studio (`https://aistudio.google.com`).
- **Phased Implementation**: 3 Sequential Phases to conserve tokens and prevent incomplete builds.
  - **Phase 1 (Completed)**: Core Architecture, True AMOLED + Frosted-Glass Material 3 UI, `KeepAliveWebView` (Screen-Lock Bypass + Multi-Account & GitHub OAuth Engine), Zero-Failure Codebase File Downloader, AMOLED Study Curtain, Vector App Icon, and Schema v1 Offline Settings Export/Import.
  - **Phase 2 (Completed)**: Dual-Layer Prompt Completion Detector, Lock-Screen Keep-Alive Foreground Service (`PARTIAL_WAKE_LOCK` + `WifiLock`), Smart Multi-Modal Alarm Engine (Audio `USAGE_ALARM` + Haptic Vibration + Native Voice TTS), Floating Prompt Status Pill, Shizuku "One Tap Auto-Configure" Engine (8 commands via reflection), and Schema v2 Offline Settings Backup/Restore.
  - **Phase 3 (Completed)**: Prompt Execution History & Study Analytics Dashboard, Future-Proof Custom DOM Selector & Calibration Lab, Built-in Quetta Browser Extension (.zip) Exporter, and Final Schema v3 Offline Backup & Restore Engine (with Full Overwrite + Smart Merge).

## 2. Core Architectural Guardrails & Coding Constraints
- **Zero Heavy Dependencies / Ultra-Lightweight APK**:
  - Exclusively use standard AndroidX, Jetpack Compose Material 3, and built-in Android SDK APIs (`org.json`, `android.webkit`, `android.media`, `java.util.zip`).
  - Strict prohibition of Room, Retrofit, Dagger/Hilt, Gson, or Coil.
  - Data persistence: `org.json.JSONObject` and `SharedPreferences` wrapped in a reactive Kotlin `StateFlow` singleton repository (`GenAlertRepository`). Fast compilation, low memory footprint, zero annotation-processing overhead.
- **Single-Activity Compose UI with In-Memory WebView Preservation**:
  - `MainActivity` retains single `KeepAliveWebView` instance across navigation tabs (`Workspace`, `Analytics`, `Settings & Backup`). Never destroy or recreate the WebView on tab change or configuration changes.
- **Dev Server & Web Companion (`server.js`)**:
  - Node.js lightweight static & API daemon on port 3000 serving interactive emulator UI (`index.html`), `/api/status`, `/api/download`, `/api/backup`, and direct downloads of `GenAlert.apk`.
- **Strict Token Efficiency**:
  - Do not run Gradle testDebugUnitTest unless requested.
  - Only verify that the project compiles cleanly.
  - Provide complete, compilable Kotlin files with zero placeholders or `// TODO` blocks.

## 3. KeepAliveWebView & Screen-Lock Bypass
- **Conditional Keep-Alive Spoofing (`KeepAliveWebView.kt`)**:
  - `keepAliveActive`: `true` ONLY when `appInBackground || WatchController.isArmed(slotId) || alwaysOnStudyLock || (!isVisibleSlot && isAiStudioDomain(url))`. Background accounts keep generating while hidden. In Safe Mode, returns `false`.
  - Overrides `onWindowVisibilityChanged` and `onWindowFocusChanged` strictly guarded by `keepAliveActive && isAiStudioDomain(url)`.
  - Google Auth domains are never spoofed to ensure 2FA challenges and BotGuard pass.
- **Conditional JS Visibility Override (`window.__gaKeepAlive`)**:
  - Injected via `WebViewCompat.addDocumentStartJavaScript` (or `onPageFinished` fallback) for AI Studio only (skipped in Safe Mode):
    ```javascript
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => window.__gaKeepAlive ? 'visible' : pv.get.call(document) });
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.__gaKeepAlive ? false : ph.get.call(document) });
    document.addEventListener('visibilitychange', e => { if (window.__gaKeepAlive) e.stopImmediatePropagation(); }, true);
    ```
  - In `onPause`/`onResume`, evaluates `window.__gaKeepAlive=true/false`.
  - On `onResume` and `onCloseWindow` (popup close), dispatches real refresh events: `window.dispatchEvent(new Event('focus')); document.dispatchEvent(new Event('visibilitychange'));` so GitHub sync state refreshes.

## 4. Persistent Multi-Account WebView Pool & Session State (`WebViewPoolManager.kt`)
- **Zero-Reload In-Memory WebView Pool**:
  - Managed by `WebViewPoolManager` maintaining `mutableMapOf<String, KeepAliveWebView>()` inside a persistent `FrameLayout` container (`MATCH_PARENT`).
  - Switching accounts does **NOT** destroy or reload WebViews.
  - New account slots instantiate `KeepAliveWebView`, immediately call unified `WebViewConfigurator.configure(webView, slot)` (binding isolated Chromium profile `WebViewCompat.setProfile` BEFORE `loadUrl`, configuring cookies, user-agent, clients, download listener, and dark mode), and load `slot.lastUrl.ifBlank { "https://aistudio.google.com/" }` **only once**.
  - Unified setup ensures account 1, 2, 3, 15... behave 100% identically without partial or duplicated configurations.
  - Account switching is instantaneous (0ms): Selected slot is set to `View.VISIBLE` and focused; background slots are set to `View.GONE` (background code streaming continues uninterrupted via KeepAlive spoofing).
- **SPA Project URL Restoration (`doUpdateVisitedHistory`)**:
  - Overrides `WebViewClient.doUpdateVisitedHistory`: updates `repository.updateAccountSlotLastUrl(slot.id, url)` whenever on `isAiStudioDomain(url)`.
  - Never overwrites `slot.lastUrl` with `accounts.google.com` URLs. Restoring or restarting the app brings back each account's exact project workspace URL.
- **Fix Blank Grey Screen After Login & Modern WebSettings**:
  - `shouldOverrideUrlLoading`: returns `false` for `http`/`https` so Chromium handles all redirects, navigations, and Google auth iframes natively.
  - WebSettings configured for Angular/WASM: `javaScriptEnabled`, `domStorageEnabled`, `databaseEnabled`, `useWideViewPort`, `loadWithOverviewMode`, `setSupportMultipleWindows(true)`, `MIXED_CONTENT_COMPATIBILITY_MODE`.

## 5. Zero-Failure Codebase File Downloader (`DownloadSink.kt` & `DownloadCoordinator.kt`)
- **Robust Multi-Layer Downloader Architecture**:
  - **Asset-Driven In-Page Capture (`assets/ga_dl.js`)**:
    - All download JavaScript lives in `app/src/main/assets/ga_dl.js` (loaded once and cached in `PageScripts`).
    - Guarded with `if (window.__gaDlInstalled) return; window.__gaDlInstalled = '2';` to prevent multiple installations.
    - **In-Memory Blob Registry & Delayed Revoke**: AI Studio creates blob URLs and immediately invokes `URL.revokeObjectURL(url)` in the same tick as `anchor.click()`. A 30-entry LRU `Map` caches Blobs/Files on `URL.createObjectURL` (5-minute TTL). `URL.revokeObjectURL` is patched with a 120,000ms delay to prevent premature expiration.
    - **All-Frames Document-Start Injection**: Registered via `WebViewCompat.addDocumentStartJavaScript` with expanded origin set: `{"https://aistudio.google.com", "https://*.usercontent.goog", "https://*.googleusercontent.com", "https://*.google.com"}` across top-level and embedded preview/code view frames. Fallback injection runs in `onPageStarted` and `DownloadCoordinator.handleBlob`.
    - **Dual Communication Channel**: `post()` attempts modern `GenAlertPort` (WebMessageListener) first, falling back to `GenAlertBridge.post()` (JavascriptInterface). Sends `dl-ready` once on evaluation with frame, origin, and channel diagnostics.
    - **Chunk Streaming via FileReader**: Slices blobs in 393,216-byte chunks (multiple of 3) encoded via `FileReader.readAsDataURL` (taking the base64 part after comma) and sent sequentially (`await`). Never uses `btoa` on large strings or arrays.
    - **Anchor & Dispatch Capture**: Hooks `HTMLAnchorElement.prototype.click`, `EventTarget.prototype.dispatchEvent`, document capture-phase click listener, and `window.open` for blob/data URLs. Logs `"DL: anchor-hook"`.
    - **Exported APIs**: `window.__gaFetchBlob(url, name)` and `window.__gaSelfTest()` (true round-trip: creates blob, calls `createObjectURL`, immediately calls `revokeObjectURL`, clicks anchor, returning `'ok'`).
  - **`DownloadSink.kt`**: Receives `dl-start`, `dl-chunk`, `dl-end`, `dl-err`.
    - **4-Second Deduplication**: Maps sanitized filename to timestamp (`recentDownloads`). Ignores duplicate `dl-start` calls for the same filename within 4 seconds, and `DownloadCoordinator` checks `DownloadSink.hasRecentlyStarted(name)` to skip redundant native `DownloadListener` invocations.
    - **7-Second Watchdog**: `expect(url, webView)` arms a 7-second watchdog cancelled by `dl-start`, `dl-chunk`, or `dl-err`. If expired, logs `"Download failed: timeout (no dl-start in 7s)"` to `DetectorLog` and triggers `PageScripts.injectNow(webView)`.
    - **Extension-Based MIME Mapping**: MIME = `MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)` (defaults to `application/octet-stream`). Never overrides extension with blob type to avoid MediaStore renaming `.tsx` to `.tsx.txt`.
    - **Storage Insertion**:
      - API 29+: Inserts into `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = "Download/GenAlert"`, `IS_PENDING = 1`, then resets `IS_PENDING = 0` on completion.
      - API 26–28: Writes directly into `Downloads/GenAlert/` and runs `MediaScannerConnection.scanFile`.
    - **Direct Streaming & Error Propagation**: Exposes `startDirect` / `finishDirect` for streaming HTTP responses. Replaces generic error messages with specific reasons ("http <code>", "blob revoked and not in registry", "no JS channel", "write failed: <msg>", "timeout (no dl-start in 7s)") toasted to the user and logged to `DetectorLog`.
  - **`DownloadCoordinator.kt`**:
    - `blob:` URLs: Evaluates `typeof window.__gaDlInstalled` (re-injecting and logging `"DL: hook was missing at download time"` if missing), checks `DownloadSink.hasRecentlyStarted(name)` to skip duplicate `DownloadListener` executions, and calls `window.__gaFetchBlob(url, name)`.
    - `data:` URLs: Decodes base64 or URL-encoded data directly in Kotlin and pipes chunks through `DownloadSink`.
    - `http(s)` URLs: Opens `HttpURLConnection` with Cookies from profile CookieManager (`WebViewCompat.getProfile(webView).cookieManager`), `User-Agent`, `Referer`, `Accept: */*`, parses `Content-Disposition`, and streams body to `DownloadSink.startDirect`. Falls back to in-page `__gaFetchBlob` (with `credentials: 'include'`), and finally system `DownloadManager`.
  - **Download Self-Test & Diagnostic Telemetry**:
    - Settings button executes `window.__gaSelfTest()` verifying hook and channel, saving `genalert_selftest.txt`.
    - Calibration Lab displays live `"Download hook: ready (port)"` / `"ready (bridge)"` / `"not detected"` based on `PageMessageRouter.dlReadyFlow`.
    - "Show download debug toasts" toggle displays diagnostic tags (`"DL: anchor-hook"`, `"DL: DownloadListener"`).
  - **Download Doctor / Staged Save**:
    - **Staged First, Publish Second**: `dl-start` streams chunks into `File(cacheDir, "ga_dl/<id>.part")` via `BufferedOutputStream` without in-memory buffering. On `dl-end`, `publish` tries: (0) Custom Download Location (DocumentsContract inside `treeDocUri` if usable); (a) MediaStore `Download/GenAlert`; (b) retry unique name `base-<millis>.ext`; (c) `getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)/GenAlert/`; (d) SAF `CreateDocument` via `SaveAsRequester`. Copies using 64KB buffers on `Dispatchers.IO`, deletes staged file on success, and toasts "Saved <name> (<size>) → <label>".
    - **Download Doctor & Live Trace**: Thread-safe 80-entry ring buffer in `DownloadTrace.kt` with live `StateFlow<List<String>>`, mirrored to `DetectorLog`. Settings screen displays the last 30 lines in selectable monospace text with "Copy log" and "Clear" buttons.
    - **Popup & Nav Interception**: `javaScriptCanOpenWindowsAutomatically = true` across pooled and popup WebViews. Intercepts `blob:` / `data:` in `shouldOverrideUrlLoading` and popup `onPageStarted` / `shouldOverrideUrlLoading`, routing with `lastKnownDownloadName` (fallback `"download.zip"`).
    - **Extended 3MB Self-Test**: Settings self-test pushes a 3MB payload through `URL.createObjectURL` → `URL.revokeObjectURL` → anchor click, saving `genalert_selftest_3mb.bin` and logging to `DownloadTrace`.

## 6. AMOLED Study Curtain
- 1-tap full-screen `#000000` pitch-black overlay that shuts off OLED pixels while keeping the Activity in the foreground and generation alive.
- Shows ultra-dim, movable status text with brightness minimized.
- Dismissed via a "Double-Tap to Unlock Curtain" gesture.

## 7. True AMOLED & Frosted-Glass Material 3 Design
- **Color Palette**:
  - Primary: `#000000` (AMOLED Pitch Black background and surface)
  - Card / Panel Surface: `#0A0A0E`
  - Accents: `#00E5FF` (Electric Cyan) & `#7C4DFF` (Electric Violet)
- **Theme Options**:
  - `AMOLED_BLACK` (Default)
  - `SLATE_CYBER_DARK`
  - `DYNAMIC_MATERIAL_DARK`
  - `CLEAN_LIGHT`
- **FrostedGlassBar**:
  - Android 12+ (`SDK_INT >= 31`): Translucent layering with subtle blur and 1dp top specular border (`Color.White.copy(alpha = 0.14f)`).
  - Android 8.0-11: Translucent gradient scrim (`Color(0xD908080C)`) with specular border.

## 8. Offline Backup & Restore Engine (Schema v2) (`BackupManager.kt`)
- Storage Access Framework: JSON export (`CreateDocument`) and import (`OpenDocument`).
- Payload schema: `"schemaVersion": 2` with full backward compatibility for v1 payloads.
- Serializes: Theme mode, Keep-Alive state, Desktop viewport preference, Custom slot names, Alarm sound type, Alarm loop toggle, Vibration pattern, Voice TTS toggle, Idle stream threshold seconds, Always-On Study Lock, and Shizuku configured state.

## 9. App Identity & Adaptive Launcher Icon
- **Application Name**: `GenAlert`
- **Background**: Minimalistic carbon-black geometric background (`#050508`).
- **Foreground**: Glowing Gemini spark morphing into an acoustic alarm bell vector.

## 10. Completion Watch Engine (`assets/ga_watch.js` & `WatchController.kt`)
- **Self-Contained Watch Script (`ga_watch.js`)**:
  - Compact IIFE (<120 lines) guarded by `window.__gaWatchInstalled`, exposed via `window.__gaWatchArm(cfgJson)` and `window.__gaWatchDisarm()`.
  - Config payload: `cfg = {done: [...], quota: [...]}` with lowercased, trimmed keywords.
  - **TreeWalker Text Counting (`countAll()`)**:
    - Uses `document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)`.
    - Skips text nodes inside `script`, `style`, `textarea`, `input`, and `[contenteditable]` elements so the user's active prompt writing or typing never triggers completion alarms.
    - Joins text with spaces, lowercases, collapses whitespace, and counts keyword occurrences with `indexOf` loops.
  - **Baseline Snapshot & 1500ms Ignore Window**:
    - On arm, snapshots initial keyword counts (`baseline = countAll()`).
    - Ignores all DOM changes for the first 1500ms after arming, guaranteeing that text already present on the page when Watch was toggled will never trigger a false alarm.
  - **Periodic Polling & Mutation Observer**:
    - Checks page text every 1000ms (`setInterval`) and upon DOM changes via `MutationObserver` (debounced 400ms on `document.body` subtree and `characterData`).
    - Adjusts baseline for text removal: `baseline[k] = Math.min(baseline[k], current[k])`.
    - If `current[k] > baseline[k]`, keyword becomes a candidate.
    - Candidate must remain true across 2 consecutive checks before firing to filter transient rendering or partial tokens.
  - **One-Shot Trigger & Dual-Channel Dispatch**:
    - Dispatches `{type: "watch-hit", kind: "DONE" | "QUOTA", keyword}` using dual-channel `post()` (`GenAlertPort` message listener with `GenAlertBridge.post` fallback).
    - If both completion and quota keywords hit, QUOTA wins.
    - Automatically disarms immediately upon firing (clears interval, disconnects MutationObserver).
    - Arming is idempotent (re-arming replaces any previous watch state). Injected exclusively via `evaluateJavascript()`.
- **Global Watch Controller Singleton (`WatchController.kt`)**:
  - Replaces legacy automated detection complexity with deterministic, user-controlled monitoring.
  - Reactive state: `armedSlots: StateFlow<Map<String, Long>>` (maps `slotId` to `armedAt` timestamp) and `isArmed(slotId)`.
  - `arm(slotId)`:
    - Reads `completionKeywords` and `quotaKeywords` from `GenAlertRepository`.
    - If completion keywords are empty, toasts "Add keywords first" and aborts.
    - Calls `alarmController.stopAlarm()` to silence any previous alarm.
    - Evaluates `__gaWatchArm(cfgJson)` on the active slot's pooled `KeepAliveWebView`.
    - Starts `PromptMonitorService` if this is the first armed slot.
    - Logs to `DetectorLog` with tag `WATCH`.
  - `disarm(slotId)`:
    - Evaluates `__gaWatchDisarm()` on the WebView.
    - Removes slot from `armedSlots`.
    - Stops `PromptMonitorService` if no armed slots remain.
  - `onHit(slotId, kind, keyword)`:
    - Computes `durationSeconds = ((now - armedAt) / 1000).toInt()`.
    - Records entry in `PromptHistoryRecord` (`status = SUCCESS` or `QUOTA_EXHAUSTED`, `startSource = "MANUAL"`, `startDetail = keyword`, `endReason = "KEYWORD"`).
    - Triggers multi-modal acoustic alarm, vibration, voice TTS announcement, and full-screen lock alarm.
    - Disarms the slot cleanly.
  - `rearmIfArmed(slotId, webView)`:
    - Invoked by `PageScripts.kt` in `onPageFinished` to transparently maintain watch state across page reloads/navigations.

## 11. Lock-Screen Keep-Alive Foreground Service (`PromptMonitorService.kt`)
- **Locks Acquired**:
  - `PowerManager.PARTIAL_WAKE_LOCK` (`"GenAlert::PromptWakeLock"`)
  - `WifiManager.WifiLock` (`WifiManager.WIFI_MODE_FULL_HIGH_PERF`, `"GenAlert::PromptWifiLock"`)
- **Notification**:
  - Shows persistent chronometer with live elapsed time (`"Watching for completion... 01:24"`).
  - Provides `"Mute Alarm"` action button (`ACTION_MUTE_ALARM`).
  - Provides `"Cancel Watch"` action button (`ACTION_CANCEL_RUN`, `PendingIntent.FLAG_IMMUTABLE`) calling `WatchController.disarmAll()`.
- **Alert Channel**:
  - High priority notification (`IMPORTANCE_HIGH`, `CATEGORY_ALARM`) with `"Dismiss Alarm"` action button.

## 12. Smart Multi-Modal Alarm & Easy-To-Stop Engine (`AlarmController.kt` & `AlarmRingActivity.kt`)
- **Acoustic Audio**:
  - Configured with `AudioAttributes.USAGE_ALARM` so audio cuts through Vibrate/Silent mode when studying.
  - Selectable tone streams: `SYSTEM_ALARM`, `NOTIFICATION_CHIME`, `RINGTONE`.
  - Loop mode vs One-Shot mode (bounded by `ringMaxSeconds`, default 45s, 0 = until dismissed).
- **Haptic Vibration**:
  - Strong alarm pulse, Continuous cadence, Subtle, or Disabled.
  - Distinct Quota Error Pattern: Urgent 3-pulse vibration (`[0, 150, 100, 150, 100, 450]`).
- **Native Voice TTS**:
  - `android.speech.tts.TextToSpeech` announcing slot label: *"Account 2: your prompt finished in [X] minutes and [Y] seconds."*
  - Quota error announcement: *"Account 2 warning: token quota exhausted. Ready to switch account."*
- **Idempotent Multi-Route Auto-Silence (`stopAlarm()`)**:
  - Stops player, cancels vibrator, stops TTS, and cancels alarm notification.
  - Callable from:
    1. Tapping floating pill while ringing.
    2. Notification "Stop" action button, tapping notification body, or swipe-away (`deleteIntent`).
    3. Volume up/down keys consumed in `MainActivity.dispatchKeyEvent` and `AlarmRingActivity`.
    4. Arming any slot in `WatchController`.
    5. App brought to foreground.
    6. Auto-stop timer expiring after `ringMaxSeconds`.
- **Lock-Screen `AlarmRingActivity`**:
  - Pure black Compose activity launched via `fullScreenIntent` (`showWhenLocked`, `turnScreenOn`, `excludeFromRecents`, `singleTask`).
  - Huge 200.dp **STOP ALARM** button and "Open GenAlert" button. Consumes volume keys to stop alarm.

## 13. Shizuku One-Tap Auto-Configure Engine (`ShizukuConfigurator.kt`)
- **Artifacts**: `dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5`.
- **Safe Reflection Invocation**: `Shizuku.newProcess` invoked via Java reflection to circumvent package-private API access restrictions across environments.
- **11 System Commands Executed**:
  1. `dumpsys deviceidle whitelist +$pkg` (Doze mode exemption)
  2. `cmd appops set $pkg RUN_IN_BACKGROUND allow` (Unrestricted background JS)
  3. `cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow` (Disable OEM freezer)
  4. `cmd appops set $pkg WAKE_LOCK allow` (Permanent WakeLock guarantee)
  5. `cmd appops set $pkg SCHEDULE_EXACT_ALARM allow` (Exact alarm scheduling)
  6. `cmd appops set $pkg SYSTEM_ALERT_WINDOW allow` (Lock-screen overlay & curtain)
  7. `cmd activity set-standby-bucket $pkg active` (Highest OS standby bucket)
  8. `pm grant $pkg android.permission.POST_NOTIFICATIONS` & `cmd appops set $pkg USE_FULL_SCREEN_INTENT allow || true` (Lock-screen alarm intent)
  9. Audio & Media Permissions: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `READ_MEDIA_AUDIO`, `PLAY_AUDIO`, `TAKE_AUDIO_FOCUS`
  10. Android & MIUI / HyperOS Clipboard: `READ_CLIPBOARD`, `WRITE_CLIPBOARD`, `SYSTEM_EXEMPT_FROM_CLIPBOARD_NOTIFICATION`, AppOps `10019` & `10020`
- **UI**: Step-by-step live progress dialog with green checkmarks, real-time command output logs, and quick launcher in Workspace top strip.

## 14. Prompt Execution History & Study Analytics Dashboard (`PromptAnalyticsScreen.kt` & `PromptHistoryRecord.kt`)
- **Automated Logging with End Reason Attribution**:
  - `PromptHistoryRecord` fields: `id: Long`, `timestamp: Long`, `durationSeconds: Int`, `status: String` ("SUCCESS", "QUOTA_EXHAUSTED", or "CANCELLED"), `accountSlotLabel: String`, `slotId: String`, `startSource: String`, `startDetail: String`, `endReason: String` ("BUTTON_STATE" or "KEYWORD").
  - Serialized via `toJson()`/`fromJson()`.
- **4 Glassmorphic Stat Cards (SUCCESS Only)**:
  1. `Total Prompts Completed`: Count of successful prompt runs only (CANCELLED does not count).
  2. `Total Study / Wait Time Tracked`: Accumulated wait duration of SUCCESS runs formatted as `Xh Ym`.
  3. `Average Prompt Generation Time`: Mean prompt duration of SUCCESS runs (`Xm Ys`).
  4. `Quota Exhaustions Logged`: Rate limit occurrences to identify accounts hitting limits.
- **Filter Chips**:
  - Filter history list by: `All` • `Completed` • `Quota` • `Cancelled`.
- **Simplified Expandable Detail Panel**:
  - Status badges: Electric Green for COMPLETED, Gray for CANCELLED, Amber for QUOTA HIT.
  - Tapping a history item toggles an expandable panel (`AnimatedVisibility`, remembered IDs). Displays:
    - "Triggered by: Keyword 'xxx' or Button state" (derived from `endReason` and `startDetail`)
    - "Watch duration: Xm Ys"
  - Completely cleaned: trace timeline UI, Copy trace button, and Suspicious logic removed.

## 15. Dual-Trigger Completion Watch Engine (`ga_watch.js`, `WatchController.kt`, `SnapshotDiff.kt`)
- **Dual Independent Triggers**:
  - **Trigger 1 (Keyword Trigger)**: Monitored via TreeWalker text counting + MutationObserver with 1500ms initial ignore window. Dispatches `DONE` or `QUOTA` when keywords hit.
  - **Trigger 2 (Button-State Trigger)**: Configured with `{selector, signals:[{kind, key, idleValue, busyValue}]}`. Evaluated every 500ms via `readField(el, kind, key)` comparing current state against busyValue and idleValue.
  - Independent triggers: whichever fires first wins and immediately disarms the watch.
- **Button State Machine**:
  - Evaluates scores: `busyScore` vs `idleScore` -> `BUSY`, `IDLE`, `UNKNOWN`, or `NOT_FOUND`.
  - `seenBusy` becomes `true` after observing `BUSY` continuously for 800ms.
  - Fires alarm when `seenBusy` is true and state remains `IDLE` continuously for 2000ms. Dispatches `{type: "watch-hit", kind: "DONE", detail: "BUTTON_STATE"}`.
  - If armed while button is already IDLE and was never busy, does NOT fire (prevents instant false alarms).
  - Emits `{type: "watch-state", state}` upon state changes (debounced max 1 per second).
  - Standalone probe: `window.__gaWatchProbe(cfgJson)` evaluates and returns current state string once without arming.
- **WatchController Singleton (`WatchController.kt`)**:
  - Assembles `cfgJson` with button section only if both snapshots exist AND "Use button-state trigger" is ON (default ON when snapshots exist).
  - Keyword section included only if "Use keyword trigger" is ON (default ON).
  - If both triggers are OFF, prevents arming and toasts "Enable at least one trigger".
  - Exposes reactive `liveState: StateFlow<String>` ("IDLE", "BUSY", "UNKNOWN", "NOT_FOUND").
  - Records history with `endReason = "BUTTON_STATE"` or `"KEYWORD"`.
- **UI Status Integration (`PromptStatusPill.kt`)**:
  - Floating pill while armed displays: `"Account N • Watching • BUSY"` (colored dot: Cyan for BUSY, Electric Green for IDLE, Gray for UNKNOWN, Red for NOT_FOUND).

## 15B. Quetta-Style Website Inspector (`ga_inspect.js`, `InspectController.kt`)
- **Strict Trusted Types & Closed Shadow DOM**:
  - Self-contained IIFE guarded by `window.__gaInspectInstalled`. Loaded via `PageScripts` on AI Studio pages only.
  - Attached to a closed Shadow DOM host (`position:fixed; inset:0; z-index:2147483647; pointer-events:none; overflow:hidden;`) so page CSS cannot bleed in.
  - Built 100% with `createElement`, `textContent`, `setAttribute`, and element style properties. Zero `innerHTML` or `outerHTML`.
- **UI & Interaction**:
  - Fullscreen transparent capture layer. On `pointerup`, layer switches to `pointer-events:none`, calls `document.elementFromPoint`, and selects closest `button, [role="button"], a` ancestor if tapped within one.
  - Swallows `touchstart`, `touchend`, `click`, and `contextmenu` events in capture phase (`preventDefault` + `stopPropagation`).
  - Red highlight box (`2px solid #FF5252`, `rgba(255,82,82,0.25)` fill) dynamically repositioned on scroll and resize via `getBoundingClientRect`.
  - Quetta-style dark rounded bottom toolbar:
    - `✕` Cancel (`__gaInspectStop('cancel')`)
    - `⟳` Re-pick element
    - `▲` Select parent
    - `▼` Select first child
    - `👁` Preview (temporarily outlines ALL elements matching selector in cyan for 2s with match count banner)
    - `⚙` Settings panel toggle
    - `✓` Save snapshot (`post({type:"inspect-saved", role, snapshot})` then `__gaInspectStop("saved")`)
- **Specificity Levels (`buildSelectorLevels`)**:
  - L1: Tag + stable attributes (`aria-label`, `mattooltip`, `data-test-id`, `title`, `type`, `name`).
  - L2: L1 + up to 2 stable classes (filters out `/^(mat-|cdk-|ng-)/`, 4+ digit runs, and ripple/focus/hover/active/disabled).
  - L3: Parent tag + L2.
  - L4: `:nth-of-type` chain up to 4 ancestors.
  - Default level is the first one where `querySelectorAll(sel).length === 1`.
- **Snapshot Extraction (`captureSnapshot`)**:
  - Serializes: `{selector, role, ts, tag, attrs (class split into sorted array, style omitted), text (max 80 chars), iconText (mat-icon / material-symbols text), hasSvg, svgPathHash (djb2 of path d), hasSpinner, disabled, computed {display, opacity, color, backgroundColor, animationName, pointerEvents, cursor}, rect {w,h}}`.
- **Kotlin InspectController (`InspectController.kt`)**:
  - Singleton managing `mode: StateFlow<String?>` ("IDLE", "BUSY", or null).
  - `start(role)`: switches to Workspace tab, evaluates `window.__gaInspectStart('<role>')` on active pooled WebView, arms 100s watchdog.
  - `cancel(reason)`: evaluates `window.__gaInspectStop('<reason>')` on ALL pooled WebViews.
  - Auto-cancels on tab change away from Workspace, account slot switch, or `onPageStarted`.
  - `PageMessageRouter`: handles `inspect-saved`, delegates to `GenAlertRepository.saveSnapshot(role, snapshotJson)`, logs with tag `INSPECT`.
- **Snapshot Diff Engine (`SnapshotDiff.kt`)**:
  - Diffs IDLE and BUSY JSON snapshots into `List<Signal>`: attribute differences, class additions/removals, text changes, iconText, hasSvg, svgPathHash, hasSpinner, disabled, computed styles.
  - Skips noise: style, rect, framework classes (`ng-`, `cdk-`, `mat-mdc-focus/ripple`), data attributes with 4+ digit runs.
  - Persists signals in `GenAlertRepository.signalsJson` and default `buttonSelector`. Warning displayed if idle.selector != busy.selector.
  - Settings UI displays each signal with Switch toggle row (`aria-label: "Run" → "Stop"`).

## 16. Built-in Quetta Browser Extension Exporter (`ExtensionExporter.kt`)
- **Zero Third-Party Dependency**:
  - Built with pure `java.util.zip.ZipOutputStream` to generate `GenAlert_Quetta_Extension.zip` in `Downloads/GenAlert/`.
- **Package Manifest V3**:
  - `manifest.json`: permissions `["notifications", "storage"]` for `https://aistudio.google.com/*`.
  - `content.js`: Injected DOM `MutationObserver`, Web Audio API synthesized oscillating tone generator, Web Speech API speech synthesis announcer, and Chrome notification dispatch.
- **Standalone Mobility**: Can be loaded directly into Quetta or Chromium-based Android browsers.

## 17. Final Schema v6 Offline Backup, Restore & Smart Merge (`BackupManager.kt`)
- **Schema v6 JSON**:
  - Fully serializes Phase 1 settings, Phase 2 alarm/vibration/TTS/Shizuku configurations, and Schema v6 dual-trigger keys: `watchKeywords`, `quotaKeywords`, `useKeywordTrigger`, `useButtonTrigger`, `idleSnapshot`, `busySnapshot`, `signals`, `buttonSelector`, full `promptHistory` array, unlimited `AccountSlot` objects with `activeSlotId`, `webThemeMode` (`DARK` / `LIGHT`), `safeMode`, and `downloadDebugToasts`.
  - Never exports removed keys (`netKeywords`, etc.).
- **Dual Restore Flow & Order Preservation**:
  - **Smart Merge (Recommended)**: Preserves local values unless local is empty. Preserves local slot order and merges prompt history by `id`/`timestamp` without overwriting newer local records.
  - **Full Overwrite Restore**: Completely replaces all local settings, account slots, snapshots, signals, and history with the backup file using backup values.
- **Full Backward Compatibility**: Seamlessly imports Schema v1 through v5 backups with safe defaults (`optString`, `optBoolean`, never `getString`), silently ignoring removed keys.

## 18. Sleek Single-Row Top Bar & Account Switcher Dropdown (`AccountSwitcherStrip.kt`)
- **Single-Button Vertical Account Switcher (48.dp Top Bar)**:
  - Replaces horizontal LazyRow chip clutter with a single compact `AccountSelectorButton`: Box containing a cyan 1dp bordered clickable pill Row: `[status dot] [active slot name, maxLines=1, Ellipsis, maxWidth=150dp] [ArrowDropDown rotating 0°→180°]`.
  - Box uses `Modifier.weight(1f, fill = false)` ensuring right-side action buttons are never pushed off-screen.
  - Sibling `DropdownMenu` inside Box (width 240–300.dp, max height 420.dp, `#0A0A0E` background). Items rendered in custom repository order: leading position number ("1.", "2."), slot name (active in Electric Cyan), trailing check icon if active, plus cyan dot if slot is generating.
  - Divider follows with "+ Add account" (dialog) and "Manage & reorder…" (opens Account Manager sheet).
  - Collects `settingsFlow` with `collectAsState` so pill name and dropdown items update instantaneously on switch, rename, delete, or reorder.
  - Collapsed 26.dp edge-tab mode remains preserved.
- **Account Manager Sheet with Manual Reordering**:
  - "Reorder" toggle FilterChip beside "Add Account" button (`reorderMode`).
  - Order model: Stored slot list order in `GenAlertRepository` IS the user custom order (no extra sortOrder field required). `moveAccountSlot(slotId, direction)` (-1 up, +1 down) and `moveAccountSlotToTop(slotId)` swap/move slots, persist via JSON array, and emit new list instances. Reordering never alters `activeSlotId`.
  - Normal Mode: Position number ("1.", "2.") before name with switch/rename/re-login/delete buttons.
  - Reorder Mode: Position number plus Up (`KeyboardArrowUp`), Down (`KeyboardArrowDown`), and "Top" (`TextButton`). Up/Top disabled on first row; Down disabled on last row. Normal action icons hidden to prevent mis-taps.
  - Rendered with `itemsIndexed(slots, key = { _, it -> it.id })`.
  - Caption: "This order = order in the account dropdown".

## 19. Desktop / Mobile Mode Toggle with True Viewport Rewriting
- **Dynamic `<meta name="viewport">` Tag Rewriting**:
  - Simply changing User-Agent does not trigger desktop layout in AI Studio due to CSS media queries (`min-width: 1024px`).
  - `applyViewportAndUserAgentMode`:
    - Desktop: Injects `<meta name="viewport" content="width=1280, initial-scale=0.33, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes">`, sets Linux x86_64 User-Agent, enables wide viewport and overview mode.
    - Mobile: Injects `<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">` and restores clean mobile User-Agent.
    - 1-Tap Toggle button shows Toast feedback (`"Desktop Viewport Applied (1280px)"` or `"Mobile Viewport Applied"`).

## 20. View-Level Keyboard Insets & Text Field Auto-Scroll Architecture
- **Root Cause & Core Setup**:
  - In edge-to-edge on API 30+, `adjustResize` is ignored and Compose `.imePadding()` does not reach the native WebView hosted in pooled FrameLayout.
  - `MainActivity.onCreate` sets `WindowCompat.setDecorFitsSystemWindows(window, false)` before `setContent`.
  - Manifest retains `android:windowSoftInputMode="adjustResize"`.
  - Removed all Compose `.imePadding()` and `WindowInsets.ime` logic to eliminate double-consumption. `Scaffold` sets `contentWindowInsets = WindowInsets(0, 0, 0, 0)`.
- **KeyboardInsets Dual Measurement (`KeyboardInsets.kt`)**:
  - Measures keyboard height using two independent sources: `ViewCompat.setOnApplyWindowInsetsListener` (`WindowInsetsCompat.Type.ime().bottom`) and `viewTreeObserver.addOnGlobalLayoutListener` (display frame diff > 15% screen height, MIUI-safe fallback).
  - Publishes larger value: `_bottomPx.value = maxOf(insetPx, layoutPx)`.
- **Single Root Layout**:
  - `val imePx by keyboardInsets.bottomPx.collectAsState()`
  - `val imeDp = with(LocalDensity.current) { imePx.toDp() }`
  - `Column(Modifier.fillMaxSize().padding(bottom = imeDp))` contains top bar, WebView container (`weight(1f)`), and bottom navigation (`AnimatedVisibility(visible = !keyboardOpen)`).
  - When keyboard opens, bottom bar is hidden and only `imeDp` is applied (already encompassing nav-bar area).
- **Smooth Center Scrolling & Capture-Phase focusin**:
  - When `imePx` changes, calls `requestLayout()` on active WebView; if keyboard is open and URL passes `isAiStudioDomain(url)`, evaluates JavaScript after 250ms to scroll active input/textarea/contenteditable into center.
  - NEVER run custom scroll JS on `accounts.google.com` or `myaccount.google.com` (native Chromium resize handles login forms).
  - Injected script adds capture-phase `focusin` listener on AI Studio URLs: `setTimeout(() => el.scrollIntoView({block:'center'}), 350)`.

## 21. Draggable & Auto-Collapsible Floating Prompt Status Pill
- **Non-Obstructive Floating Status Widget**:
  - Freely draggable anywhere on screen via `detectDragGestures` with `IntOffset`.
  - Defaults to Top-Right corner below top bar, completely avoiding Google AI Studio's prompt input, Run button, and Chat/Artifacts buttons.
  - Dedicated `–` collapse toggle shrinks pill into minimal `28.dp` glowing status dot on screen edge.
  - Multi-account generation tracking: Shows active slot label and live chronometer when generating (e.g. "Account 2 • 01:24").
  - Live Telemetry: Shows 3 tiny dots for NET, BTN, DOM signals when expanded.
  - 1-Tap Alarm Dismiss: When alarm rings, displays a prominent STOP ALARM button; tapping anywhere on pill silences alarm immediately.
  - Automatically collapses into mini dot 4 seconds after prompt completion (`COMPLETED_SUCCESS`).

## 22. Dark Mode For All Accounts & Zero-Reload Web Theme Toggle (`WebViewConfigurator.kt`)
- **Centralized WebView Configuration**:
  - Single `WebViewConfigurator.configure(webView, slot)` manages profile binding, cookies, settings, user-agent, clients, download listeners, custom DOM watchers, and dark mode.
  - Called by `WebViewPoolManager` for EVERY WebView created, ensuring accounts 1, 2, 3, 15... behave 100% identically.
- **Repository State & Default**:
  - `enum class WebThemeMode { DARK, LIGHT }` in `GenAlertRepository`, default `DARK`, global across all accounts.
- **Algorithmic Darkening & Dark XML Theme**:
  - `applyWebTheme(webView, mode)` uses `WebSettingsCompat.setAlgorithmicDarkeningAllowed` (or `setForceDark` fallback).
  - XML theme parent in `themes.xml` set to dark (`android:Theme.Material.NoActionBar`) so WebView reliably reports `prefers-color-scheme: dark`.
- **Dynamic CSS Scheme Injected Script**:
  - Registered via `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("https://aistudio.google.com"))` (or `onPageStarted` fallback) for AI Studio only (NEVER auth domains).
  - Overrides `window.matchMedia` for `prefers-color-scheme` and exposes `window.__gaSetScheme(scheme)`.
- **Instant Top Bar & Settings Toggle**:
  - Top bar features dedicated Sun/Moon theme toggle icon (`ic_web_theme_toggle.xml` split contrast circle, distinct from Study Curtain).
  - Tapping flips `WebThemeMode`, persists to repository, updates `applyWebTheme` and calls `window.__gaSetScheme` across all pooled WebViews with 0 page reload, showing Toast feedback.
  - Settings screen features dedicated "Website Theme (Google AI Studio)" Dark/Light radio selector.

## 23. Zero Preset Accounts & Reliable Button-State Trigger (Phase 2)
- **Zero Preset Accounts & Clean Initialization**:
  - Removed all hardcoded slot 1–3 seeding from initial repository launch and backup restores. Initial state is an empty slot list with `activeSlotId = null`.
  - Removed "can't delete last account" restriction: users can delete all accounts.
  - `WorkspaceScreen` displays centered empty-state card ("No accounts yet" + cyan "Add account" button) when zero accounts exist; `WebViewPoolManager` creates no WebViews until a slot is added.
  - `AccountSwitcherStrip` pill reads "Add account" and opens the add dialog directly; nickname is required, trimmed (1–24 chars), with confirm button disabled while blank.
  - Unique profile generator utilizes persistent monotonic counter (`slotProfileCounter`) so deleted slot numbers are never reused and isolated cookies cannot return.
  - Backup v6 export/import and smart merge fully support empty slot lists (`optJSONArray`).
- **Reliable Button-State (Spinner) Trigger**:
  - `SnapshotDiff.kt` classifies signals into `strong` (class-token membership `has class token <tok>`, text, iconText, hasSvg, svgPathHash, hasSpinner, computed animationName) and `noisy` (jslog, aria-describedby counters, aria-disabled, disabled, id, 4+ digit runs, other computed styles).
  - Class signals evaluate individual token membership (`el.classList.contains(token)`) rather than volatile whole-class string equality.
  - Repository persists `enabledSignalKeys` (JSON array, key = `"kind:key"`), defaulting to strong signals only upon snapshot creation. Added "Auto-pick best signals" button and "strong"/"noisy" tags per row in Settings.
  - `ga_watch.js` state evaluation calculates `busyHits` and `idleHits` across enabled signals. State is `BUSY` if `busyHits > idleHits`, `IDLE` if `idleHits > busyHits`, and preserves previous state on ties (hysteresis). Re-queries selector every tick.

## 24. Native GitHub Tab & Raw Asset Downloader (Phase 3)
- **Zero-Blob Native REST Architecture (`GitHubApi.kt`)**:
  - Direct HTTPS REST operations avoiding WebView `blob:` restrictions using pure `HttpURLConnection` on `Dispatchers.IO` and `org.json`.
  - Required headers: `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `User-Agent: GenAlert`, and conditional `Authorization: Bearer <token>`.
  - Human-friendly HTTP error mapping: 401 ("Token invalid or expired"), 403 with `X-RateLimit-Remaining: 0` ("Rate limit reached — add a token"), 404 ("Not found (private repo? token needs Contents: Read)"), and 5xx errors.
  - Paginated repository retrieval: `/user/repos` (with token) or `/users/{user}/repos` (without token) up to 100 items per page.
  - Branch listing: `/repos/{owner}/{repo}/branches`.
  - Directory contents: `/repos/{owner}/{repo}/contents/{path}?ref={ref}` with encoded segments, sorted folders first, then files alphabetically.
- **Global Reactive Navigation & Persistence (`GitHubBrowserState.kt`)**:
  - Global singleton state with `StateFlow` streams for `repos`, `selectedRepo`, `branches`, `ref`, `pathStack`, `entries`, `loading`, `error`, `progress`, and `activeDownloadName`.
  - Persistent state in `github_prefs`: `user`, `token`, `lastRepo`, `lastRef`, `lastPath` (never exported in backup). Reopens exactly at saved navigation state across app restarts.
  - In-memory `dirCache` (`Map<"owner/repo@ref:path", List<GitHubDirEntry>>`) for instant back navigation and background re-fetching.
  - Scroll positions tracked per listing key (`scrollPositions: MutableMap<String, Pair<Int, Int>>`) restored in `LazyListState` and captured on dispose.
  - Coroutine scope with cancelable active `Job` ensuring downloads survive navigation tab switches.
- **Streamed Multi-Tier Downloads (`DownloadSink` integration)**:
  - File download: `/repos/{o}/{r}/contents/{path}?ref={ref}` with `Accept: application/vnd.github.raw+json` streaming raw binary content up to 100MB via `DownloadSink.startDirect` / `finishDirect`.
  - Repo ZIP: `/repos/{o}/{r}/zipball/{ref}` intercepted on 302 redirect (`instanceFollowRedirects = false`), opening redirect location without authorization header, streaming as `<repo>-<ref>.zip`.
  - Folder ZIP: Single-request extraction streaming repo zipball through `ZipInputStream`, writing matching relative entries into `ZipOutputStream` temp file, publishing `<repo>-<folder>.zip` without burning API rate limits.
- **UI & Bottom Navigation (`GitHubScreen.kt` & `MainActivity.kt`)**:
  - 5-tab bottom navigation: `Workspace`, `GitHub`, `Downloads`, `Analytics`, `Settings & Backup`.
  - AMOLED styling (`#0A0A0E` cards, `#00E5FF` electric cyan accents) matching Analytics dashboard.
  - Responsive hierarchical `BackHandler`: pops `pathStack`, then clears `selectedRepo`, then falls back to Workspace.

## 25. Phase 1: cookies, UA helper, session tools, wildcard-origin download hook
- **A1. Cookie Management & Persistence**:
  - Profile-aware CookieManager `cm = getEffectiveCookieManager(webView)` with fallback to `CookieManager.getInstance()`.
  - Both `setAcceptCookie(true)` and `setAcceptThirdPartyCookies(webView, true)` enforced in `configureCommon()` for pooled KeepAliveWebViews and OAuth popup WebViews.
  - Systematic `cm.flush()` invoked on `onPageFinished`, when navigation transitions from `accounts.google.com` to other domains, on host activity `Lifecycle.Event.ON_PAUSE`, and on popup dismissal.
- **A2. Dynamic Chrome User-Agent Helpers**:
  - Zero hardcoded Chrome versions. Built dynamically from `WebSettings.getDefaultUserAgent(context)`.
  - Clean Mobile UA: removes `; wv` and `Version/4.0 `.
  - Clean Desktop UA: replaces Android token `(Linux; U; Android...)` or `(Linux; Android...)` with `(X11; Linux x86_64)`.
  - Unified helper used everywhere User-Agent is set or manipulated.
- **A3. Passive HTTP 401/403 Diagnostic Logger**:
  - Passive detection in `WebViewClient.onReceivedHttpError`: checks for status 401 or 403 where host ends with `google.com`.
  - Logs `<status> <host><path>` without query string under DetectorLog tag `"HTTP"`.
  - Dedupes identical error entries within a 10-second window.
  - Leaves in-page fetch/XHR unmodified to avoid BotGuard/anti-bot detection.
- **A4. Google Session Tools in Settings Screen**:
  - Dedicated "Google session" card in `SettingsScreen`.
  - "Session check": inspects active slot profile cookies for `https://aistudio.google.com` and `https://accounts.google.com`. Logs boolean presence of `SID`, `HSID`, `SAPISID`, `__Secure-1PAPISID`, `__Secure-3PAPISID` to DetectorLog `"SESSION"` (never logs values). Toasts "Session cookies OK" if `SAPISID` and `__Secure-3PAPISID` are present, else warns "Session cookies missing — tap Refresh session".
  - "Refresh session": switches to Workspace and opens `https://accounts.google.com/AccountChooser?continue=https%3A%2F%2Faistudio.google.com%2F` on active slot's WebView to refresh session cookies.
- **B1-B5. Zero-Failure Download in Sandboxed Iframe (Opaque Origin)**:
  - Document-start registration for `ga_dl.js` uses `allowedOriginRules = setOf("*")`, allowing execution in sandboxed iframes with `origin=null`.
  - `addWebMessageListener("GenAlertPort", setOf("*"), ...)` with payload validation (filters only `dl-*` message types).
  - `ga_dl.js` guards against execution on `accounts.google.com`.
  - In anchor and dispatch hooks, `dl-start` is dispatched synchronously before the first `await` so 4-second deduplication engages immediately.
  - `preventDefault` and `stopImmediatePropagation` are called only if the blob URL is found in that specific frame's registry, allowing unhandled clicks to propagate.
  - `DownloadSink.anyStartSince(ms)` added. On registry miss in `DownloadSink`, failure is delayed by 2500ms and reported only if `anyStartSince(4000)` remains false.
  - `hook-ready` traces origin explicitly, verifying registration in `origin=null` frames.

## 26. Downloads Tab (Phase 2)
- **Manifest & FileProvider**:
  - Manifest queries intent `<action android:name="android.intent.action.VIEW"/><data android:mimeType="*/*"/>` so `queryIntentActivities` succeeds on Android 11+.
  - FileProvider declared for `${applicationId}.fileprovider` mapped via `res/xml/file_paths.xml` for `Download/GenAlert/` (external public storage) and `Download/GenAlert/` (external files dir).
- **DownloadsRepository**:
  - `DownloadItem(uri, name, size, modifiedMs, mime, file)`.
  - Reactive `changeTick` state flow with `notifyChanged()`. Invoked whenever new files are published in `DownloadSink` or deleted.
  - Hybrid querying: MediaStore query for API 29+ (`RELATIVE_PATH LIKE 'Download/GenAlert%'`), FileProvider list for API 26–28, plus `getExternalFilesDir(DIRECTORY_DOWNLOADS)/GenAlert/` fallback storage.
  - Case-insensitive sorting by `NEWEST`, `OLDEST`, `NAME_AZ`, `NAME_ZA`, `LARGEST`, `SMALLEST`, persisted in SharedPreferences `downloads_prefs` key `"sort"`.
  - Smart MIME open chooser fallback: Markdown types (`text/markdown`, `text/plain`, `*/*`) vs generic (`mime`, `*/*`) with `FLAG_GRANT_READ_URI_PERMISSION` and `ClipData.newRawUri`.
  - Robust deletion handling direct delete and API 30+ `MediaStore.createDeleteRequest(contentResolver, listOf(uri))` intent sender launcher.
- **Downloads UI (`DownloadsScreen.kt`)**:
  - AMOLED `#000000` styling with `#0A0A0E` cards and `#00E5FF` electric cyan accents.
  - Cyan-outlined extension badge (uppercase, max 4 chars: MD, PDF, ZIP, TXT, etc.).
  - File size + relative time formatting via `Formatter.formatShortFileSize` and `DateUtils.getRelativeTimeSpanString`.
  - Delete confirmation dialog with non-destructive cancel and red delete confirmation.
- **5-Tab Navigation**:
  - Explicit ordered bottom navigation: `Workspace`, `GitHub`, `Downloads`, `Analytics`, `Settings`.
  - Settings bottom tab labeled "Settings" with 11.sp maxLines 1; sub-screen title remains "Settings & Backup".
  - BackHandler on Downloads navigates back to Workspace. Never destroys or recreates pooled WebViews.

## 27. Custom Download Location (Phase 3)
- **DownloadLocation.kt Singleton**:
  - Persists custom tree URI in `download_location` SharedPreferences under key `"tree"`. Never exported in BackupManager (device-specific).
  - `label(ctx)`: returns portion after `:` of `DocumentsContract.getTreeDocumentId(tree)` (e.g. `"Download/MyFolder"`), or `"Default • Downloads/GenAlert"` if unconfigured.
  - `isUsable(ctx)`: verifies `tree != null` and `contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }`.
  - `treeDocUri(tree)`: builds document URI using `DocumentsContract.buildDocumentUriUsingTree`.
  - `set(ctx, uri)`: takes persistable read/write URI permission, releases previous persistable permission, and saves to prefs.
  - `reset(ctx)`: releases persistable permission and removes pref.
  - `probe(ctx, uri)`: creates `"ga_probe.tmp"`, writes 1 byte, flushes, and deletes document; returns `true` only if all succeed.
- **Settings Screen Integration**:
  - "Download Location" card positioned above the Download Doctor card.
  - Displays cyan mono `label(ctx)` with fallback explanation caption.
  - "Choose folder" button (`OpenDocumentTree`). Probes URI on `Dispatchers.IO`; toasts warning and preserves previous folder on failure, saves and confirms on success.
  - "Reset to default" button visible only when custom tree is set.
- **DownloadSink Publish Ladder Step (0)**:
  - Inserted before MediaStore attempt (a). If `isUsable(ctx)` is true:
    - Creates document via `DocumentsContract.createDocument(resolver, treeDocUri, docMime, name)`.
    - Streams 64KB buffer on `Dispatchers.IO` to `resolver.openOutputStream(doc, "w")`.
    - Resolves and corrects potential provider suffix renames via `COLUMN_DISPLAY_NAME` and `renameDocument`.
    - Logs `"published custom <name>"`, toasts `"Saved <name> (<size>) → <label>"`, calls `DownloadsRepository.notifyChanged()`, and deletes staged `.part` file.
  - If tree is set but permission was revoked: toasts `"Custom folder unavailable — saved to default"` once per app launch and falls through to standard ladder (a)→(d).
- **Downloads Screen & Repository Integration**:
  - `DownloadsRepository.list()` queries children via `buildChildDocumentsUriUsingTree`, maps metadata to `DownloadItem(..., custom = true)`.
  - `delete()` removes custom files via `DocumentsContract.deleteDocument`.
  - `DownloadsScreen` displays `"Saving to: <label>"` and three FilterChips (`All`, `Default`, `Custom`) when custom folder is configured.
  - Reloads files on `Lifecycle.Event.ON_RESUME` so changes in Settings apply immediately.




