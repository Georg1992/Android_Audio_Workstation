# Drag/Reorder Subsystem — Architecture Review & Integration Readiness

## 1. DragController hygiene

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/drag/DragController.kt`  
**Problem:** No KDoc; future integrators could not see that hover/drop index is intentionally computed outside the controller.  
**Why it matters:** Avoids coupling the controller to LazyListState and keeps a single responsibility.  
**Fix:** Added class KDoc and parameter docs. Clarified that drop index is computed at call site from `fingerPos` and list layout.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/drag/DragController.kt`  
**Problem:** Uses `androidx.compose.ui.geometry.Offset` and `mutableStateOf` — Compose dependency.  
**Why it matters:** For a Compose app this is acceptable; the controller still has no LazyColumn/UI view dependencies and stays reusable for any list.  
**Fix:** No change. Documented in KDoc that position is in “the same coordinate system used for hit-testing (e.g. root or list-local)”. Call site is responsible for passing consistent coordinates.

---

**State model:**  
- `draggingKey: String?` — which item is dragged.  
- `fingerPos: Offset` — current drag position.  
- No `hoverIndex` in the controller: correct. Drop index is derived from `fingerPos` + `LazyListState` in the screen (e.g. via `computeReorderDropIndex`).

**Verdict:** API is minimal and reusable; no UI-specific leaks. Improvements: KDoc added; no code changes required beyond documentation.

---

## 2. ReorderDropIndex correctness

---

**Severity:** IMPORTANT  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/drag/reorder/ReorderDropIndex.kt`  
**Problem:** When the finger is above the first visible item or below the last (e.g. overscroll, fast drag), the result was driven only by the nearest visible item and could be wrong. No explicit “above content → 0, below content → itemsCount” handling.  
**Why it matters:** Fast drag or drag in overscroll should still yield a valid drop index (0 or itemsCount).  
**Fix:**  
- Compute `contentTop` / `contentBottom` from visible track items.  
- If `draggedCenterY <= contentTop` return `0`.  
- If `draggedCenterY >= contentBottom` return `itemsCount`.  
- Otherwise keep existing nearest-item logic.  
Implemented in the updated `ReorderDropIndex.kt`.

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/drag/reorder/ReorderDropIndex.kt`  
**Problem:** Signature used `draggedTopPx` + `draggedHeightPx`; only center was used internally. Call site had to pass two values for one concept.  
**Why it matters:** Simpler API and one source of truth for “current drag Y” (e.g. finger or overlay center).  
**Fix:** Replaced with a single `draggedCenterY: Float` (list-local Y of the drag position). Call site converts finger/overlay position to list-local Y and passes the center once.

---

**Edge cases (verified):**

| Case | Handling |
|------|----------|
| Empty list (`itemsCount <= 0`) | Returns 0. |
| No visible items | Returns 0. |
| Dragging above first item | `draggedCenterY <= contentTop` → 0. |
| Dragging below last item | `draggedCenterY >= contentBottom` → itemsCount. |
| Fast drag across many items | Bounds check + nearest among visible; out-of-bounds clamped to 0 / itemsCount. |
| Only dragged item visible (single item) | `candidates.isEmpty()` → 0 (no-op drop). |
| Result range | `idx.coerceIn(0, itemsCount)`. |

---

## 3. Compose integration readiness

**What is still missing to wire drag/reorder into ProjectScreen**

1. **Ownership of DragController**  
   - Create and retain one instance per screen: e.g. `val dragController = remember { DragController() }` (or provide via composition local if needed later).

2. **Long-press on track handle**  
   - In TrackCard (or ProjectScreen), detect long-press on the reorder handle and call `dragController.start(track.id, positionInRootOrListLocal)`.
   - Position must be in the same coordinate system used later for `computeReorderDropIndex` (list-local Y) and for overlay placement (e.g. root).

3. **Pointer input at screen level**  
   - Single `pointerInput` on the root Box (or content area) that:
     - On move: `dragController.update(fingerPos)` and recompute drop index from `listState` + list-local Y.
     - On up: `val key = dragController.end()` then `vm.moveTrack(projectId, key, dropIndex)`; clear local drop index state.
     - On cancel: `dragController.cancel()`.

4. **Coordinate conversion**  
   - Finger position from the pointer input (e.g. root or Box-local) must be converted to **list-local Y** for `computeReorderDropIndex(listState, draggedKey, draggedCenterY, tracksStartIndex, itemsCount)`.
   - Use `listState.layoutInfo.visibleItemsInfo` and the LazyColumn’s position in root (or `onGloballyPositioned`) to compute: `listLocalY = fingerPosInRoot.y - lazyColumnTopInRoot`.

5. **LazyColumn filtering**  
   - During drag, exclude the dragged item from the list: `items = tracks.filterNot { it.id == dragController.draggingKey }` so the item is not drawn in the list and the gesture is not cancelled when the item “leaves” the list.

6. **Overlay**  
   - Draw a drag overlay (e.g. note icon or track thumbnail) at `dragController.fingerPos` (in the same coordinate system as the overlay parent, e.g. root or Box), so it follows the finger.

7. **Drop index state**  
   - Hold current drop index in state (e.g. `var dropIndex by remember { mutableIntStateOf(0) }`), update it in the pointer move path from `computeReorderDropIndex`, and use it in the pointer up path when calling `vm.moveTrack`.

**Suggested integration pattern**

- **Overlay + LazyColumn:**  
  Root Box → (1) LazyColumn with `items = tracks.filterNot { it.id == dragController.draggingKey }`, (2) one `pointerInput` on the Box for move/up/cancel, (3) overlay composable that shows when `dragController.isDragging` and is positioned at `dragController.fingerPos`.  
- Long-press is handled inside the track item (or via a callback to the screen); after `start()`, all move/up/cancel are handled in the root `pointerInput` so the gesture continues even when the item is removed from the list.

**Blockers**

- None in DragController or ReorderDropIndex.  
- Integration is a matter of: creating the controller, wiring long-press → `start`, pointer move → `update` + drop index, pointer up → `end` + `moveTrack`, coordinate conversion, and filtering the list + overlay.

---

## 4. Code cleanliness

---

**Severity:** MINOR  
**File path:** `app/src/main/java/com/georgv/audioworkstation/ui/drag/reorder/ReorderDropIndex.kt`  
**Problem:** KDoc did not describe edge cases (above first, below last, empty list, single item).  
**Why it matters:** Future reorder behavior and bugs are easier to reason about.  
**Fix:** Extended KDoc with an “Edge cases” section and ensured implementation matches (bounds check + existing logic).

---

**Redundant code:** None removed; only additions (bounds check, KDoc) and the API simplification (single `draggedCenterY`).

**Extension points preserved:**  
- DragController: generic `String` key; `Offset` for position; no list or index logic.  
- ReorderDropIndex: pure function; call site supplies list state, key, Y, and list layout parameters.

**Naming:**  
- `fingerPos` kept (clear for “current drag position”).  
- `draggedCenterY` makes it explicit that the caller passes the center Y in list-local coordinates.

---

## Reorder integration readiness checklist

### What is already good

- **DragController:** Minimal API (`start`, `update`, `end`, `cancel`); no LazyColumn or view dependencies; state is clear (`draggingKey`, `fingerPos`); `@Stable` for Compose.
- **ReorderDropIndex:** Pure function; uses `LazyListState` and visible items; handles empty list and single-item case; result clamped to `[0, itemsCount]`; now handles finger above/below visible content.
- **ViewModel:** `moveTrack(projectId, trackId, toIndex)` exists and updates positions in Room.
- **Tracks:** Have stable `id` and `position`; LazyColumn can use `key = { it.id }` and filter out the dragged id during drag.
- **Documentation:** KDoc on both units describes responsibility and usage; ReorderDropIndex documents edge cases and new signature (`draggedCenterY`).

### What must be done before wiring UI

1. **ProjectScreen (or parent):**  
   `val dragController = remember { DragController() }` and optionally a `var dropIndex` state.

2. **TrackCard / handle:**  
   Long-press callback that reports (e.g. track id + position in chosen coords); screen calls `dragController.start(id, pos)`.

3. **Root Box pointer input:**  
   On move: convert finger to list-local Y, `dragController.update(fingerPos)`, `dropIndex = computeReorderDropIndex(listState, dragController.draggingKey!!, listLocalY, tracksStartIndex, itemsCount)`.  
   On up: `val key = dragController.end()`, then `if (key != null) vm.moveTrack(projectId, key, dropIndex)`.

4. **Coordinate conversion:**  
   Store LazyColumn top (e.g. from `onGloballyPositioned`) and compute list-local Y from finger position for `computeReorderDropIndex`.

5. **List during drag:**  
   `items = tracks.filterNot { it.id == dragController.draggingKey }` (no placeholder).

6. **Overlay:**  
   When `dragController.isDragging`, draw at `dragController.fingerPos` in the same coordinate system as the overlay parent.

7. **Call site of computeReorderDropIndex:**  
   Use the new signature: pass `draggedCenterY` (list-local Y of finger or overlay center), not `draggedTopPx`/`draggedHeightPx`.

### Estimated complexity

**Medium.**  
- Controller and drop index are ready; no structural changes needed.  
- Work is mainly: one root pointer input, coordinate conversion, long-press → `start`, list filtering, overlay, and calling `moveTrack` on `end`.  
- Main care: consistent coordinate systems (root vs list-local) and ensuring the same pointer that started the drag is the one that receives move/up (e.g. overlay architecture so the gesture is not lost when the item is removed from the list).
