---
name: android-error-handling
description: |
  TRIGGER: User asks about error handling, Result types, exceptions, validation, or how errors flow between layers.

  ACTION: IMMEDIATELY load this skill for error handling patterns.

  Use for: Handling network/DB errors, validation, mapping errors to UI messages, sync exceptions.

  Out of scope: Generic Result<T,E> wrappers, Ktor error handling → not used in this project.

  Examples:
  - user: "How do I handle API errors?" → load skill, show repository catch pattern
  - user: "Validate user input" → load skill, show ValidationResult
  - user: "Map error to snackbar" → load skill, show ErrorMessageUiMapper
---

# Android Error Handling

## MANDATORY FIRST ACTIONS

Before handling errors:
1. Identify which layer owns the error (Data / Domain / Presentation)
2. Determine if error is expected (network failure) or unexpected (bug)
3. Choose the appropriate mechanism for the layer

Skip = incorrect error propagation.

## Layer Responsibilities

| Layer | Responsibility | Mechanism |
|---|---|---|
| Data | Catch exceptions, map to domain errors | `runCatching { }` in RepositoryImpl |
| Domain | Wrap operations, return structured results | `runCatching { }` + `toUseCaseResultPayload()` in UseCase |
| Presentation | Map to user-friendly messages | `ErrorMessageUiMapper`, `UseCaseHandler` |

## Data Layer Error Handling

Repositories use `runCatching { }` to wrap operations and return `Result<T>`:

```kotlin
// In NotesRepositoryImpl
override suspend fun getNotes(): Result<List<Note>> = withContext(Dispatchers.IO) {
    runCatching {
        noteDao.getAllNotes().map { noteDomainMapper.toDomain(it, ...) }
    }.onFailure { e ->
        writeLog.e(TAG_NOTE_REPOSITORY, "Error fetching notes", e)
    }
}
```

**Rules:**
- Never let raw SQLite or HTTP exceptions cross into Domain. Catch and map them.
- Repositories return `Result<T>`; UseCases consume them with `.getOrThrow()` inside their own `runCatching` block.

## Domain Layer Results

UseCases wrap their entire operation in `runCatching { }` and return `Result<UseCaseResultPayload<T>>`:

```kotlin
data class UseCaseResultPayload<T>(
    val data: T,
    val message: String? = null
)
```

Note: `data` is **non-nullable**. There is no `success` boolean — presence of data implies success.

### UseCase Pattern with runCatching

```kotlin
class StartTaskUseCase @Inject constructor(
    private val notesRepository: NotesRepository,
    private val activeTaskRepository: ActiveTaskRepository,
    // ... other dependencies
) {
    suspend operator fun invoke(noteId: String): Result<UseCaseResultPayload<ActiveTask>> = runCatching {
        val note = notesRepository.getByLocalId(noteId)
            ?: throw NoteNotFoundException("Note not found: $noteId")

        if (!note.canEdit) {
            throw PermissionException("User does not have EDIT permission")
        }

        if (activeTaskRepository.getByNoteId(noteId) != null) {
            throw ActiveTaskAlreadyExistsException("Note already has an active task")
        }

        val task = ActiveTask(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            // ...
        )

        activeTaskRepository.insert(task)

        task
    }.toUseCaseResultPayload("Task started")
}
```

**Pattern:**
1. Wrap the entire use case body in `runCatching { }`
2. Throw domain exceptions (`NoteNotFoundException`, `PermissionException`, etc.) for expected failure cases
3. Call `toUseCaseResultPayload("Success message")` on the `Result<T>` to convert to `Result<UseCaseResultPayload<T>>`
4. Return type is always `Result<UseCaseResultPayload<T>>`

### toUseCaseResultPayload Extension

The `toUseCaseResultPayload` extension function converts any `Result<T>` to `Result<UseCaseResultPayload<T>>`:

```kotlin
import com.marcohuijskes.predecide2.domain.mappers.toUseCaseResultPayload

// Usage within runCatching block
runCatching {
    repository.someOperation()
}.toUseCaseResultPayload("Operation completed successfully")

// With no message
runCatching {
    repository.logout()
}.toUseCaseResultPayload()

// Converting an existing Result<T>
repository.updatePermissions(noteId, permissions)
    .toUseCaseResultPayload("Successfully updated permissions")
```

**Signature:** `fun <T> Result<T>.toUseCaseResultPayload(successMessage: String? = null): Result<UseCaseResultPayload<T>>`

- On success: wraps data in `UseCaseResultPayload(data, successMessage)`
- On failure: propagates the original exception unchanged

### Chaining Other UseCases

When a use case calls another use case that returns `Result<UseCaseResultPayload<T>>`, use `.getOrThrow()` to unwrap within the `runCatching` block:

```kotlin
runCatching {
    createOrUpdateAlarmFromUiUseCase(alarmInput, triggerSync = false)
        .getOrThrow()  // throws on failure, caught by outer runCatching

    activeTaskRepository.insert(task)

    updateNoteUpdatedAtTimestampUseCase(noteId, now, triggerSync)
        .getOrThrow()

    task
}.toUseCaseResultPayload("Task started")
```

## Validation Results

Features with user input use typed validation results:

```kotlin
data class NoteValidationResult(
    val isSuccess: Boolean,
    val titleError: String? = null,
    val bodyError: String? = null
)

class NoteValidator @Inject constructor() {
    fun validate(title: String, body: String): NoteValidationResult {
        return NoteValidationResult(
            isSuccess = title.isNotBlank(),
            titleError = if (title.isBlank()) "Title required" else null
        )
    }
}
```

Note: The field is `isSuccess`, not `isValid`.

## Presentation Layer Error Mapping

Use `UseCaseHandler` for standardized error handling with snackbar feedback:

```kotlin
// In ViewModel
useCaseHandler.execute(
    useCaseCall = { softDeleteNoteUseCase(noteId) },
    logTag = TAG_NOTE_LIST_VIEW_MODEL,
    successSnackbarMessage = "Note deleted",
    errorSnackbarMessage = "Failed to delete note"
).onSuccess { result ->
    _uiState.update { /* optimistic update */ }
}.onFailure { error ->
    // Rollback optimistic update
    _uiState.update { previousState }
}
```

`UseCaseHandler` returns `Result<UseCaseResultPayload<T>>`. Use `.onSuccess { }` / `.onFailure { }` to handle outcomes.

`ErrorMessageUiMapper` maps exceptions to user-friendly `R.string` resources.

### SilentException

Some errors should not show snackbars. `UseCaseHandler` suppresses snackbars for `SilentException`:

```kotlin
throw SilentException("Background sync failed silently")
```

## Sync Exception Control Flow

The sync engine uses dedicated exception types for control flow:

- `DelegateSyncException(newJob)` — handler swaps job type transactionally
- `AbortSyncException` — obsolete job, remove without error
- Both caught in `SafeSyncExecutor` and `SyncNotesUseCase` with explicit handling

## Custom Domain Exceptions

Define exceptions in `core/util/exceptions/` (primary location) or `domain/exceptions/` (validation exceptions only):

```kotlin
class NoteNotFoundException(noteId: String) : Exception("Note not found: $noteId")
class NoteNotSyncedException(noteId: String) : Exception("Note not synced: $noteId")
```

## Key Rules

1. NEVER use empty catch blocks — always log or handle
2. NEVER expose raw exceptions to the UI — map to user-friendly messages
3. ALWAYS catch exceptions at the layer that owns them
4. ALWAYS use `UseCaseHandler` for ViewModel-initiated operations needing feedback
5. Repositories use `runCatching { }` returning `Result<T>` for error-prone operations
6. UseCases wrap their entire body in `runCatching { }` and return `Result<UseCaseResultPayload<T>>`
7. Use `.toUseCaseResultPayload("Success message")` to convert `Result<T>` to `Result<UseCaseResultPayload<T>>`
8. Throw domain exceptions within `runCatching` for expected failure cases (not found, permission denied, etc.)
9. `UseCaseResultPayload` has non-nullable `data` and optional `message` — no `success` boolean

## COMPLIANCE CHECKLIST

Before handling errors:
- [ ] Error caught at the correct layer: (YES/NO)
- [ ] Raw exceptions mapped before crossing layer boundaries: (YES/NO)
- [ ] User-facing errors mapped to readable messages: (YES/NO)
- [ ] Empty catch blocks avoided (or explicitly justified): (YES/NO)
- [ ] UseCase wraps body in `runCatching { }` and returns `Result<UseCaseResultPayload<T>>`: (YES/NO)
- [ ] UseCase uses `.toUseCaseResultPayload("message")` at the end of the chain: (YES/NO)
- [ ] Domain exceptions thrown within `runCatching` for expected failures: (YES/NO)
- [ ] Sync exceptions use dedicated control-flow types: (YES/NO)

If NO to any question, STOP and fix before continuing.
