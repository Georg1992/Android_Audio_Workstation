# Code Quality Audit — Static Review

## 1. Hardcoded UI values

---

**Severity:** IMPORTANT  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/Fader.kt`  
**Code snippet:**
```kotlin
shaftBg: Color = Color(0xFF0A0A0A),
trackColor: Color = Color(0xFF000000),
trackBorder: Color = Color(0xFF1A1A1A),
tickColor: Color = Color(0xFF2A2A2A),
thumbColor: Color = Color(0xFFE8E8E8),
thumbBorder: Color = Color(0xFF000000),
// ...
color = Color(0x33000000),
```
**Why:** Raw hex colors outside theme; Fader is not aligned with AppColors and is hard to theme.  
**Fix:** Add Fader-specific tokens to `AppColors` (or a `FaderColors` object) and use them as default parameters, or pass colors from theme.  
**Move to:** AppColors or `ui/theme/FaderTokens.kt`.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/Fader.kt`  
**Code snippet:**
```kotlin
val bottomPadPx = 2f
```
**Why:** Magic number; unclear and not reusable.  
**Fix:** `private const val FADER_BOTTOM_PAD_PX = 2f` at file top, or a `Dimens.FaderBottomPadPx` if you centralize dimensions in px.  
**Move to:** File-level const or Dimens if used elsewhere.

---

**Severity:** IMPORTANT  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/TrackCard.kt`  
**Code snippet:**
```kotlin
val cardShape = RoundedCornerShape(10.dp)
// ...
.padding(12.dp)
Spacer(Modifier.height(8.dp))
.height(42.dp)
Spacer(Modifier.width(10.dp))
.width(44.dp)
.heightIn(min = 100.dp)
fontSize = 10.sp,
.padding(bottom = 2.dp)
```
**Why:** Many raw dp/sp values; Dimens already has TileRadius (10.dp), TileInnerPadding (12.dp), Stroke (1.dp), etc. Inconsistent with rest of app.  
**Fix:** Use `Dimens.TileRadius`, `Dimens.TileInnerPadding`, `Dimens.Gap`, `Dimens.Stroke` where they match; add to Dimens: `TrackCardPlaceholderHeight = 42.dp`, `TrackCardFaderWidth = 44.dp`, `TrackCardFaderMinHeight = 100.dp`, `TrackCardGainFontSize = 10.sp`, `TrackCardGainBottomPadding = 2.dp`, `MenuButtonSize = 40.dp`, `SmallRadius` (8.dp).  
**Move to:** Dimens.kt.

---

**Severity:** IMPORTANT  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/TransportPanel.kt`  
**Code snippet:**
```kotlin
val shape = RoundedCornerShape(14.dp)
.padding(bottom = 8.dp)
.border(1.dp, ...)
.padding(vertical = 8.dp)
// TransportButton:
val shape = RoundedCornerShape(8.dp)
.size(56.dp)
```
**Why:** Hardcoded radii and sizes; 1.dp stroke and 8.dp padding duplicated.  
**Fix:** Use `Dimens.Stroke`, add e.g. `TransportPanelRadius = 14.dp`, `TransportPanelPadding = 8.dp`, `TransportButtonSize = 56.dp`, `TransportButtonRadius = 8.dp` in Dimens.  
**Move to:** Dimens.kt.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/projects/ProjectScreen.kt`  
**Code snippet:**
```kotlin
val panelShape = RoundedCornerShape(10.dp)
.padding(horizontal = 12.dp, vertical = 8.dp)
.height(72.dp)
.padding(horizontal = 12.dp),
verticalArrangement = Arrangement.spacedBy(10.dp)
```
**Why:** Raw dp; 10.dp and 12.dp repeat elsewhere.  
**Fix:** Use `Dimens.TileRadius`, `Dimens.Gap`, add e.g. `ScreenHorizontalPadding = 12.dp`, `PanelVerticalPadding = 8.dp`, `PanelPlaceholderHeight = 72.dp` if this is a recurring pattern.  
**Move to:** Dimens.kt.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/AppSplash.kt`  
**Code snippet:**
```kotlin
.padding(24.dp)
.border(1.dp, AppColors.Line, RoundedCornerShape(14.dp))
.padding(horizontal = 18.dp, vertical = 14.dp)
.size(width = 88.dp, height = 22.dp)
.border(1.dp, AppColors.Line, RoundedCornerShape(6.dp))
```
**Why:** All dimensions hardcoded; 1.dp stroke and 6.dp radius exist in Dimens.  
**Fix:** Use `Dimens.Stroke`, `Dimens.SmallRadius`; add `SplashCardPadding`, `SplashCardRadius`, `SplashLogoSize` to Dimens.  
**Move to:** Dimens.kt.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/mainmenu/MainMenuScreen.kt`  
**Code snippet:**
```kotlin
.padding(12.dp)
verticalArrangement = Arrangement.spacedBy(10.dp)
```
**Why:** 12.dp and 10.dp used elsewhere; should be shared.  
**Fix:** Use `Dimens.TileInnerPadding` and `Dimens.Gap`.  
**Move to:** Dimens (already defined).

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/community/CommunityScreen.kt`, `LibraryScreen.kt`, `DevicesScreen.kt`  
**Code snippet:**
```kotlin
Modifier.padding(padding).padding(16.dp)
```
**Why:** 16.dp arbitrary; inconsistent with 12.dp used on other screens.  
**Fix:** Use `Dimens.TileInnerPadding` or a shared `ScreenContentPadding` in Dimens.  
**Move to:** Dimens.kt.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/tiles/MainTile.kt`  
**Code snippet:**
```kotlin
val narrow = maxWidth < 170.dp
```
**Why:** Magic breakpoint.  
**Fix:** `private val MainTileNarrowBreakpoint = 170.dp` in file or `Dimens.MainTileNarrowBreakpoint`.  
**Move to:** Dimens.kt or file-level constant.

---

## 2. Dimension consistency

---

**Severity:** IMPORTANT  
**File path:** Multiple (TrackCard, TransportPanel, AppSplash, ProjectScreen, MainMenuScreen)  
**Code snippet:** Repeated use of `10.dp`, `12.dp`, `8.dp`, `1.dp`, radii `6.dp`, `8.dp`, `10.dp`, `14.dp`.  
**Why:** Same values redefined in multiple files; future design changes require many edits.  
**Fix:** In Dimens: use single names (e.g. `Gap = 10.dp`, `TileInnerPadding = 12.dp`, `Stroke = 1.dp`, `TileRadius`, `SmallRadius`). Add missing: `MediumRadius = 8.dp`, `PanelRadius = 14.dp`, and use them everywhere.  
**Move to:** Dimens.kt.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/LanguageSwitcher.kt`  
**Code snippet:**
```kotlin
val shape = RoundedCornerShape(6.dp)
```
**Why:** 6.dp already in Dimens as `SmallRadius` (and `LangChipRadius`).  
**Fix:** Use `RoundedCornerShape(Dimens.LangChipRadius)`.  
**Move to:** Already in Dimens.

---

## 3. Compose best practices

---

**Severity:** CRITICAL  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/projects/ProjectScreen.kt`  
**Code snippet:**
```kotlin
var gainLocal by remember(track.id) { mutableFloatStateOf(track.gain) }
// ...
onGainChange = { gainLocal = it },
```
**Why:** Gain is only in UI state; never persisted. TrackEntity has `gain` and DB supports it, but ViewModel has no `updateTrackGain`. Gain resets on process death or when list is re-built.  
**Fix:** Add `fun updateTrackGain(projectId: String, trackId: String, gain: Float)` in ViewModel (repository: get track, copy(gain = gain), updateTracks/upsertTrack). In ProjectScreen call `vm.updateTrackGain(projectId, track.id, it)` from `onGainChange` (with optional debounce). Remove or keep `gainLocal` only as transient UI if you persist immediately.  
**Move to:** N/A (architecture fix).

---

**Severity:** IMPORTANT  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/projects/ProjectScreen.kt`  
**Code snippet:**
```kotlin
items(
    items = tracks,
    key = { it.id }
) { track ->
    // ...
    onClick = { vm.toggleSelect(track.id) },
    onDelete = { vm.deleteTrack(track.id) }
```
**Why:** Lambdas `{ vm.toggleSelect(track.id) }` etc. are recreated every composition; can cause unnecessary recomposition of child.  
**Fix:** Pass stable callbacks: e.g. `onTrackClick: (String) -> Unit` and `onTrackDelete: (String) -> Unit` from parent, and call `onTrackClick(track.id)` in TrackCard. In ProjectScreen define `val onTrackClick: (String) -> Unit = { vm.toggleSelect(it) }` with `remember(vm)` or use `remember { { id: String -> vm.toggleSelect(id) } }` so the lambda reference is stable. Same for onDelete and onGainChange (or pass trackId and let ViewModel handle).  
**Move to:** N/A.

---

**Severity:** IMPORTANT  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/tiles/MainTile.kt`  
**Code snippet:**
```kotlin
@Composable
fun IconBox() {
    // ...
}
@Composable
fun AccentBar() {
    // ...
}
```
**Why:** Composable lambdas defined inside another Composable are recreated every recomposition and can cause unnecessary recomposition and are discouraged.  
**Fix:** Extract `IconBox` and `AccentBar` to top-level `@Composable` functions (or private composables in the same file) and pass `iconSize`, `smallShape`, `accent`, `icon` etc. as parameters.  
**Move to:** N/A.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/projects/ProjectScreen.kt`  
**Code snippet:**
```kotlin
val tracks = remember(state.tracks) {
    state.tracks.sortedBy { it.position }
}
```
**Why:** Sorting on every state.tracks change is correct, but Room/DAO already return tracks `ORDER BY position ASC`. If repo guarantees order, sorting in UI is redundant.  
**Fix:** If `observeTracks` already returns ordered list, remove this sort and use `state.tracks` directly. If not, ensure DAO query is `ORDER BY position ASC` and drop UI sort.  
**Move to:** N/A.

---

## 4. Architecture hygiene

---

**Severity:** CRITICAL  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/projects/ProjectScreen.kt`  
**Code snippet:** Gain changed only in local state; no call to ViewModel/repository.  
**Why:** Data loss; violates single source of truth.  
**Fix:** As in 3.1: persist gain via ViewModel + repository.  
**Move to:** N/A.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/projects/ProjectScreen.kt`  
**Code snippet:**
```kotlin
LaunchedEffect(projectId, quickRecord) {
    vm.bind(projectId)
    val projectName = if (quickRecord) { ... } else "New Project"
    vm.ensureProjectExists(projectId, projectName)
    if (quickRecord) vm.addTrack(projectId)
}
```
**Why:** Project naming and “ensure exists + add track” are UI-driven strings and flow; could be in ViewModel for testability.  
**Fix:** Optional: add e.g. `vm.initializeProject(projectId, quickRecord)` in ViewModel that binds, derives name, ensures project exists, and optionally adds one track. Reduces logic in composable.  
**Move to:** N/A.

---

## 5. Performance risks

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/projects/ProjectScreen.kt`  
**Code snippet:** `key = { it.id }` on items.  
**Why:** Good; stable keys. No change.  
**Fix:** N/A.  
**Move to:** N/A.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/Fader.kt`  
**Code snippet:** `var lastHeight by remember { mutableStateOf(0f) }` and read in gesture callbacks; Canvas updates `lastHeight = size.height`.  
**Why:** Reading state from gesture callback is correct; no expensive work in composition. Acceptable.  
**Fix:** None.  
**Move to:** N/A.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/TrackCard.kt`  
**Code snippet:** `val gainClamped = gain.coerceIn(0f, 100f)` and `gainText = gainClamped.roundToInt().toString()` in composition.  
**Why:** Cheap; no issue.  
**Fix:** None.  
**Move to:** N/A.

---

## 6. Naming and readability

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/TransportPanel.kt`  
**Code snippet:**
```kotlin
val borderColor = when {
    isActive -> AppColors.Line
    else -> AppColors.Line
}
```
**Why:** Both branches identical; dead code.  
**Fix:** Replace with `val borderColor = AppColors.Line` and remove the when.  
**Move to:** N/A.

---

**Severity:** IMPORTANT  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/drag/DragController.kt`, `app/src/main/java/com/georgv/audioworkstation/ui/drag/reorder/ReorderDropIndex.kt`  
**Code snippet:** Entire files.  
**Why:** Not referenced from ProjectScreen or any other screen; ProjectScreen implements its own drag state and drop index. Dead/orphaned code.  
**Fix:** If drag reorder is fully handled in ProjectScreen, remove `DragController.kt` and `ReorderDropIndex.kt`, or refactor ProjectScreen to use them and delete the duplicated logic.  
**Move to:** N/A.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/components/AppSplash.kt`  
**Code snippet:** Comment `// Минималистичный "логомарк"`.  
**Why:** Non-English comment (audit requested English-only).  
**Fix:** Replace with e.g. `// Minimal "logomark" placeholder`.  
**Move to:** N/A.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/screens/mainmenu/MainMenuScreen.kt`  
**Code snippet:** Comment `// GRID (занимает всё доступное место)`.  
**Why:** Non-English.  
**Fix:** e.g. `// Grid (fills remaining space)`.  
**Move to:** N/A.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/theme/AppText.kt`  
**Code snippet:** Comment `// Если захочешь центрирование как дефолт:`.  
**Why:** Non-English.  
**Fix:** e.g. `// Optional: center-aligned variants`.  
**Move to:** N/A.

---

## Summary table

| Severity   | Count |
|-----------|--------|
| CRITICAL  | 2     |
| IMPORTANT | 6     |
| MINOR     | 14    |

---

# Prioritized cleanup plan

## Phase 1 — Must fix now

1. **Persist track gain (CRITICAL)**  
   - Add `updateTrackGain(projectId, trackId, gain)` (or equivalent) in ViewModel and call it from ProjectScreen `onGainChange`.  
   - Ensure gain is read from `state.tracks` (or single source of truth) and not lost on recomposition.

2. **Remove dead code or integrate it (IMPORTANT)**  
   - Either delete `DragController.kt` and `ReorderDropIndex.kt` or refactor ProjectScreen to use them and remove duplicated drag/drop logic.

3. **Stable callbacks in ProjectScreen (IMPORTANT)**  
   - Replace inline lambdas in `items { }` with remembered or stable callbacks (e.g. `onTrackClick`, `onTrackDelete`, `onGainChange` taking ids) to avoid unnecessary recomposition.

## Phase 2 — Should fix soon

4. **Centralize Fader colors**  
   - Move Fader hex colors to AppColors or a small Fader theme object and use them in Fader defaults.

5. **Extract MainTile composable lambdas**  
   - Move `IconBox` and `AccentBar` to top-level (or file-level) composables with parameters.

6. **Dimens for TrackCard and TransportPanel**  
   - Replace hardcoded dp/sp in TrackCard and TransportPanel with Dimens constants (and add new ones where needed).

7. **Remove redundant TransportPanel borderColor when**  
   - Use a single `AppColors.Line` assignment.

8. **Dimens for ProjectScreen, AppSplash, MainMenuScreen**  
   - Use Dimens for 10.dp, 12.dp, 8.dp, radii, and add any missing screen-specific values.

## Phase 3 — Nice to have

9. **Fader `bottomPadPx`**  
   - Replace magic `2f` with a named constant (file or Dimens).

10. **MainTile breakpoint 170.dp**  
    - Move to Dimens or file-level constant.

11. **Community/Library/Devices padding**  
    - Use shared Dimens for 16.dp vs 12.dp consistency.

12. **English-only comments**  
    - Replace Russian comments in AppSplash, MainMenuScreen, AppText with English.

13. **Optional: ViewModel project init**  
    - Move project name and “ensure exists + add track” into a single ViewModel method for cleaner UI and testability.

14. **Redundant sort in ProjectScreen**  
    - Rely on DAO order and remove `sortedBy { it.position }` if repo already returns ordered list.
