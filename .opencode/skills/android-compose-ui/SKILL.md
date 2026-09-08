---
name: android-compose-ui
description: |
  TRIGGER: User asks about composables, recomposition, Modifier, LazyColumn, previews, animations, design system, or Compose UI patterns.

  ACTION: IMMEDIATELY load this skill for Compose UI patterns.

  Use for: Writing composables, optimizing recomposition, adding animations, creating previews, using Compose Unstyled.

  Out of scope: Koin ViewModel injection, generic MVI state → use android-presentation-mvi skill.

  Examples:
  - user: "Create a composable" → load skill, show stateless pattern
  - user: "Optimize recomposition" → load skill, show stability/derivedStateOf
  - user: "Add a preview" → load skill, show @Preview pattern
---

# Android Compose UI

## MANDATORY FIRST ACTIONS

Before writing Composables:
1. Ensure the composable is stateless where possible
2. Accept `modifier: Modifier = Modifier` as first optional parameter
3. Hoist state and events to the screen-level ViewModel

Skip = UI contains business logic or hardcoded state.

## Core Principle

The UI is dumb. Composables render state and forward user actions. All state lives in the ViewModel. All logic lives in the ViewModel, domain, or data layer.

## State Ownership

All application state lives in the ViewModel's `StateFlow`. Collect with lifecycle awareness:

```kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

The only exception is Compose-internal state:

```kotlin
val lazyListState = rememberLazyListState()
val showScrollToTop by remember {
    derivedStateOf { lazyListState.firstVisibleItemIndex > 5 }
}
```

## Side Effects

Side effects should be avoided when possible. If something can be handled by the ViewModel through an Action, do that instead of using a side effect in a composable.

When a side effect is truly necessary (e.g., interacting with Android lifecycle APIs that have no ViewModel equivalent), extract it into a dedicated composable to keep the Screen composable clean:

```kotlin
// Extracted into its own composable
@Composable
fun ObserveLifecycle(onStart: () -> Unit, onStop: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onStart()
                Lifecycle.Event.ON_STOP -> onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
```

Use `LocalLogger` for logging without constructor injection:

```kotlin
val logger = LocalLogger.current
logger.d(TAG_NOTE, "Message")
```

Use a tag constant defined in the feature's `/common/Constants.kt` or the global `/main/common/Constants.kt` files. A tag always starts with `TAG_`.

## Modifier Rule

Every public Composable MUST accept a modifier:

```kotlin
@Composable
fun NoteItem(
    modifier: Modifier = Modifier,
    note: NoteUi,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() }
    ) {
        // content
    }
}
```

## Lazy Layouts

Add `key` to lazy list items when there is an obvious unique identifier available. Don't force it if it's unclear which property is unique:

```kotlin
LazyColumn {
    items(
        items = uiState.notes,
        key = { it.id } // id is clearly unique
    ) { note ->
        NoteItem(note = note, onClick = { onEvent(NoteListEvent.OnNoteClick(note.id)) })
    }
}
```

---

## Animations

Avoid animations that cause recompositions. Prefer approaches that animate below the recomposition layer:

- **`graphicsLayer`** — for alpha, scale, rotation, translation
- **Offset lambda** — for position changes (`offset { ... }`)
- **`Canvas`** — for custom drawing that animates
- **`animateFloatAsState` + `graphicsLayer`** — animate a float, apply in graphicsLayer

```kotlin
// Good — animates without recomposition
val alpha by animateFloatAsState(if (state.isVisible) 1f else 0f)
Box(
    modifier = Modifier.graphicsLayer { this.alpha = alpha }
)

// Bad — causes recomposition on every frame
Box(
    modifier = Modifier.alpha(animatedAlpha)
)
```

**Deferred state reads:** When a value drives an animation, pass it as a lambda rather than reading it directly. This defers the state read to the layout/draw phase and avoids recomposition:

```kotlin
// Good — deferred read
fun Modifier.animatedOffset(offsetProvider: () -> IntOffset) =
    offset { offsetProvider() }

// Bad — immediate read causes recomposition
fun Modifier.animatedOffset(offset: IntOffset) =
    offset(x = offset.x.dp, y = offset.y.dp)
```

---

## Modifier Extensions

Prefer plain `Modifier` extension functions or `Modifier.Node`-based factories. Do not make modifier extensions `@Composable`:

```kotlin
// Good — plain extension
fun Modifier.shimmerEffect(): Modifier = composed {
    // shimmer implementation
}

// Better — Modifier factory (no composition needed)
fun Modifier.roundedBackground(color: Color, radius: Dp) =
    background(color, RoundedCornerShape(radius))
```

---

## Design System & Slot APIs

The design system lives in `:core:design-system` and contains reusable Compose components, colors, theme, and typography.

Use slot APIs (passing `@Composable` lambdas) primarily for design system components that need flexible content areas:

```kotlin
// Slot API — design system component
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Card(modifier = modifier) {
        header()
        content()
    }
}
```

Feature-level composables should prefer typed parameters over slots for clarity.

---

## Previews

Every Screen composable should have at least one meaningful `@Preview` that shows a realistic state:

```kotlin
@Preview
@Composable
private fun NoteListScreenPreview() {
    AppTheme {
        NoteListScreen(
            state = NoteListState(
                notes = listOf(
                    NoteUi("1", "Meeting notes", "Mar 15"),
                    NoteUi("2", "Shopping list", "Mar 14")
                )
            ),
            onAction = {}
        )
    }
}
```

Wrap previews in the app theme so they reflect real appearance. Use realistic sample data, not empty states (unless previewing the empty state specifically).

## Accessibility

Use meaningful `contentDescription` on all interactive or informational visual elements. Always use string resources to allow localization:

```kotlin
Icon(
    imageVector = Icons.Default.Delete,
    contentDescription = stringResource(R.string.cd_delete_note)
)
```

For decorative elements that convey no information, set `contentDescription = null`.

## TextField

Text input state lives in the ViewModel. Every keystroke dispatches an Action:

```kotlin
// In the Screen composable
TextField(
    value = uiState.title,
    onValueChange = { onAction(NoteEditorAction.OnTitleChange(it)) }
)
```

The ViewModel updates uiState (and optionally persists to `SavedStateHandle`) in response to the Action

## SnackbarController

Use the singleton `SnackbarController` for app-wide snackbars:

```kotlin
// Convenience extension
SnackbarController.sendSnackbar(
    message = "Note saved",
    duration = SnackbarDuration.Short
)

// Or with full event
SnackbarController.sendEvent(
    SnackbarEvent.Show(
        message = "Note saved",
        action = null,
        duration = SnackbarDuration.Long
    )
)
```

## Drag-and-Drop Reordering

The project uses `org.burnoutcrew.reorderable` for list reordering:

```kotlin
val reorderableState = rememberReorderableLazyListState(
    onMove = { from, to ->
        // Update local list immediately (optimistic)
        localItems = localItems.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    },
    onDragEnd = { from, to ->
        // Emit to ViewModel when drag completes
        onEvent(NoteListEvent.ReorderItems(localItems.map { it.id }))
    }
)

LazyColumn(
    state = reorderableState.listState,
    modifier = Modifier.reorderable(reorderableState)
) {
    items(localItems, key = { it.id }) { item ->
        ReorderableItem(reorderableState, key = item.id) { isDragging ->
            val scale by animateFloatAsState(if (isDragging) 1.05f else 1f)
            NoteItem(
                note = item,
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .detectReorderAfterLongPress(reorderableState)
            )
        }
    }
}
```

Always use the `.presentation.utils.fixes.ReorderableItem` instead of the one from the library.

## Advanced Compose Patterns

### snapshotFlow

Observe mutable Compose state as a Flow:

```kotlin
snapshotFlow { reorderableState.draggingItemTop }
    .collectLatest { topOffset ->
        // Handle hover detection during drag
    }
```

### Pull-to-Refresh with Custom Indicator

```kotlin
PullToRefreshBox(
    isRefreshing = uiState.isSyncing,
    onRefresh = { onEvent(NoteListEvent.TriggerSync) },
    indicator = {
        CustomSyncIndicator(
            isSyncing = uiState.isSyncing,
            lastSyncTime = uiState.lastSyncTime
        )
    }
) {
    LazyColumn { /* content */ }
}
```

## COMPLIANCE CHECKLIST

Before finishing a Composable:
- [ ] Accepts `modifier: Modifier = Modifier`: (YES/NO)
- [ ] Stateless — no business logic: (YES/NO)
- [ ] Uses `collectAsStateWithLifecycle()`: (YES/NO)
- [ ] Has `@Preview` if reusable component: (YES/NO) (screens are typically commented-out)
- [ ] `contentDescription` on interactive elements: (YES/NO)
- [ ] No hardcoded strings (use string resources): (YES/NO)

If NO to any question, STOP and fix before continuing.
