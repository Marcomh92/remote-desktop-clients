---
name: android-presentation-mvi
description: |
  TRIGGER: User asks about ViewModels, screen state, events, UI state management, or creating a new screen.

  ACTION: IMMEDIATELY load this skill for UDF presentation patterns.

  Use for: Creating ViewModels, UiState, Event/UiEvent, screen composables, reducers.

  Out of scope: Koin injection, generic MVI frameworks → this project uses Hilt and custom UDF.

  Examples:
  - user: "Create a new screen" → load skill, show ViewModel + UiState + Event pattern
  - user: "How does state flow work?" → load skill, explain StateFlow + Channel
  - user: "Handle a button click" → load skill, show onEvent pattern
---

# Android Presentation Layer

## MANDATORY FIRST ACTIONS

Before creating presentation code:
1. Define `UiState` as immutable data class with defaults
2. Define `Event` (user actions) and `UiEvent` (one-time effects)
3. Use `@HiltViewModel` with constructor injection

Skip = incorrect state management.

## StateFlow + UiState Pattern

ViewModels expose a single `StateFlow<UiState>` using `stateIn()` for lifecycle-aware sharing:

```kotlin
data class NoteListUiState(
    val notes: List<NoteUi> = emptyList(),
    val isLoading: Boolean = false,
    val selectedItems: Set<String> = emptySet()
)

@HiltViewModel(assistedFactory = NoteListViewModel.Factory::class)
class NoteListViewModel @AssistedInject constructor(
    private val getNotesUseCase: GetNotesForCategoryUseCase,
    private val writeLog: Logger,
    @Assisted private val args: NoteListGraphArgs,
    @Assisted private val topAppBarViewModel: TopAppBarViewModel
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteListUiState())
    val uiState: StateFlow<NoteListUiState> = _uiState
        .onStart { initializeTopAppBar() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NoteListUiState()
        )

    @AssistedFactory
    interface Factory {
        fun create(
            args: NoteListGraphArgs,
            showAsDialog: Boolean,
            topAppBarViewModel: TopAppBarViewModel
        ): NoteListViewModel
    }
}
```

**Rule:** Use `.update { }` — never replace the entire flow. Use `stateIn(WhileSubscribed(5_000))` for lifecycle-aware sharing.

## Split Event Classes

Separate user actions (`Event`) from one-time UI effects (`UiEvent`):

```kotlin
// User actions — sent from UI to ViewModel
sealed interface NoteListEvent {
    data class OnNoteClick(val noteId: String) : NoteListEvent
    data class UpdateItemSelection(val id: String, val selected: Boolean) : NoteListEvent
    data object AddNote : NoteListEvent
    data object TriggerSync : NoteListEvent
}

// One-time effects — sent from ViewModel to UI
sealed interface NoteListUiEvent {
    data class ShowToast(val text: String) : NoteListUiEvent
    data class NavigateToRoute(val route: Route) : NoteListUiEvent
    data object Dismiss : NoteListUiEvent
}
```

Emit one-time effects via `Channel<UiEvent>`:

```kotlin
private val uiEventChannel = Channel<NoteListUiEvent>()
val uiEvents = uiEventChannel.receiveAsFlow()

fun onEvent(event: NoteListEvent) {
    when (event) {
        is NoteListEvent.OnNoteClick -> {
            viewModelScope.launch {
                uiEventChannel.send(NoteListUiEvent.NavigateToRoute(Route.NoteViewGraph(...)))
            }
        }
        // ...
    }
}
```

## Composable Structure

The screen composable receives `uiState` and `onEvent`:

```kotlin
@Composable
fun NoteListScreen(
    viewModel: NoteListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is NoteListUiEvent.ShowToast -> { /* show toast */ }
                is NoteListUiEvent.NavigateToRoute -> { /* navigate */ }
            }
        }
    }

    NoteListContent(
        uiState = uiState,
        onEvent = viewModel::onEvent
    )
}
```

**Rule:** Every public Composable accepts `modifier: Modifier = Modifier` as first optional parameter.

## UseCase Handler Pattern

Standardize UseCase execution with `UseCaseHandler`:

```kotlin
useCaseHandler.execute(
    useCaseCall = { softDeleteNoteUseCase(noteId) },
    logTag = TAG_NOTE_LIST_VIEW_MODEL,
    successSnackbarMessage = "Note deleted",
    errorSnackbarMessage = "Failed to delete note"
).onSuccess { result ->
    _uiState.update { /* optimistic update */ }
}.onFailure { error ->
    // Rollback optimistic update on failure
    _uiState.update { previousState }
}
```

`UseCaseHandler.execute` returns `Result<UseCaseResultPayload<T>>`. Chain `.onSuccess { }` / `.onFailure { }` to handle outcomes.

### Surgical Rollback

On failure, revert to the pre-update state:

```kotlin
val previousState = uiState.value
_uiState.update { it.copy(notes = reorderedNotes) }

useCaseHandler.execute(
    useCaseCall = { reorderUseCase(reorderedIds) },
    logTag = TAG
).onFailure {
    _uiState.update { previousState } // rollback
}
```

## Reducer Pattern

For optimistic UI updates, use pure reducer functions:

```kotlin
// presentation/features/note_list/reducers/NoteListReducers.kt
fun NoteListUiState.toggleSelection(id: String): NoteListUiState {
    return copy(
        selectedItems = if (id in selectedItems) {
            selectedItems - id
        } else {
            selectedItems + id
        }
    )
}
```

## UI Model (Presentation Model)

Create UI models for screen-specific formatting:

```kotlin
data class NoteUi(
    val id: String,
    val title: String,
    val formattedDate: String,
    val isSelected: Boolean
)

// In ViewModel or mapper
fun Note.toNoteUi(): NoteUi = NoteUi(
    id = id,
    title = title,
    formattedDate = createdAt.format(),
    isSelected = false
)
```

## Coroutine Dispatchers

The Data Layer handles all dispatcher switching. ViewModels should NOT use `Dispatchers.IO`.

ViewModel tests use `MainDispatcherRule` with `StandardTestDispatcher`.

## Naming Conventions

| Component | Convention | Example |
|---|---|---|
| ViewModel | `FeatureNameViewModel` | `NoteListViewModel` |
| UI State | `FeatureNameUiState` | `NoteListUiState` |
| User Event | `FeatureNameEvent` | `NoteListEvent` |
| UI Effect | `FeatureNameUiEvent` | `NoteListUiEvent` |
| Screen Composable | `FeatureNameScreen` | `NoteListScreen` |
| Content Composable | `FeatureNameContent` | `NoteListContent` |
| UI Model | `ModelNameUi` | `NoteUi` |

## COMPLIANCE CHECKLIST

Before creating presentation code:
- [ ] Uses `@HiltViewModel` with `@AssistedInject` + `@AssistedFactory`: (YES/NO) or simple `@Inject`
- [ ] Exposes `StateFlow<UiState>` via `stateIn(WhileSubscribed(5_000))`: (YES/NO)
- [ ] Splits Event (user actions) and UiEvent (one-time effects): (YES/NO)
- [ ] Uses `Channel<UiEvent>` named `uiEventChannel`: (YES/NO)
- [ ] Every public Composable accepts `modifier: Modifier = Modifier`: (YES/NO)
- [ ] No business logic in Composables: (YES/NO)

If NO to any question, STOP and fix before continuing.
