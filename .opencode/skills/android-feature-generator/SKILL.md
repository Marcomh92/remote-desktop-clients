---
name: android-feature-generator
description: |
  TRIGGER: User wants to create a new Android feature, screen, or navigation graph.

  ACTION: Load this skill and guide through requirements before generating files.

  Use for: Generating complete feature packages with ViewModel, UI, events, navigation.

  Out of scope: Modifying existing features, UI-only changes without ViewModel.

  Examples:
  - user: "Create a note editor feature" → clarify requirements, then generate
  - user: "Add a settings screen" → gather specs, generate full feature package
  - user: "Build a dialog feature" → create dialog + navigation graph
---

# Android Feature Generator

Generate a complete, production-ready Android feature package following the PreDecide2 architecture.

## ⚠️ CRITICAL: OPERATIONAL PROCEDURE

You **MUST** follow this procedure. Violation will result in incorrect feature generation.

### Phase 1: Requirements Conversation (MANDATORY)

**DO NOT generate any files until Phase 2.**

Before generating anything, you **MUST** have a conversation with the user to understand:

1. **Feature Purpose**: What does this feature do? What problem does it solve?
2. **Feature Name**: PascalCase name (e.g., `NoteModify`, `FileManager`)
3. **Tag Name**: SCREAMING_SNAKE_CASE for logging (e.g., `NOTE_MODIFY`)
4. **Navigation Type**:
   - Standalone Screen (simple, no nested destinations)
   - Navigation Graph (complex, with child screens/dialogs)
5. **Arguments**: What data does the feature need? (IDs, flags, lists, etc.)
6. **Key Functionality**: What are the main actions? (CRUD operations, selections, etc.)
7. **UI Structure**: Dialog or full screen? Single view or multiple sections?
8. **Data Requirements**: What domain models/use cases does it interact with?

**Example conversation:**
```
User: "I want a feature for managing tags"
You: "Let's clarify the requirements:

1. Feature Name: How about `TagManager`? Or do you prefer something else?
2. Purpose: Is this for creating/editing tags, or selecting existing tags?
3. Navigation: Standalone screen or part of a graph with dialogs?
4. Arguments: Does it need a noteId to associate tags with? Or categoryId?
5. Data: Should it have its own UseCases like GetTagsUseCase, SaveTagUseCase?
6. UI: Full screen or dialog? Single tag editor or list management?"
```

### Phase 2: Approval Gate (LOCKED UNTIL EXPLICIT)

**Implementation is STRICTLY LOCKED until the user explicitly outputs the exact string: `"Approved"`**

After understanding requirements, present a summary:

```
## Feature Specification Summary

**Name**: TagManager  
**Tag**: TAG_MANAGER  
**Type**: Navigation Graph  
**Arguments**: 
  - parentNoteId: String? (to associate tags with a note)
  - existingTags: List<String> (currently selected tags)

**Key Files to Generate**:
1. TagManager.kt (Root + Screen composables)
2. TagManagerViewModel.kt (with observer pattern for tags)
3. TagManagerUiState.kt
4. TagManagerArgs.kt
5. TagManagerGraphArgs.kt (for graph navigation)
6. common/TagManagerConstants.kt
7. event/TagManagerEvent.kt
8. event/TagManagerUiEvent.kt
9. navigation/TagManagerGraph.kt
10. navigation/TagManagerScreen.kt
11. navigation/TagManagerDialog.kt

**Included Patterns**:
- Child-to-parent result communication (returning selected tags)
- TopAppBar with dynamic title and save action
- Flow observation for tag list
- Dialog and full-screen modes

Reply with "Approved" to generate the feature files.
```

**ACCEPTABLE**: User replies with exactly `"Approved"`  
**NOT ACCEPTABLE**: "Looks good", "Go ahead", "Sure", "Yes", "OK" - these do NOT unlock Phase 2

### Phase 3: File Generation (POST-APPROVAL ONLY)

Once `"Approved"` is received, generate ALL files in a single response following the templates below.

---

## Naming Conventions

| Component | Pattern | Example |
|-----------|---------|---------|
| Feature Name | PascalCase | `NoteModify` |
| Tag Name | SCREAMING_SNAKE_CASE | `NOTE_MODIFY` |
| Package Name | lowercase_snake_case | `note_modify` |
| Constants Object | `{Name}Constants` | `NoteModifyConstants` |
| Log Tag | `TAG_{TAG_NAME}` | `TAG_NOTE_MODIFY` |

## File Structure Generated

```
feature_name/
├── {FeatureName}.kt                      # Root composable with Root/Screen
├── {FeatureName}ViewModel.kt             # ViewModel with AssistedInject
├── {FeatureName}UiState.kt               # UI State data class
├── {FeatureName}Args.kt                  # Navigation arguments (if needed)
├── {FeatureName}GraphArgs.kt             # Graph arguments (if needed)
├── common/
│   └── {FeatureName}Constants.kt         # Log tags and constants
├── event/
│   ├── {FeatureName}Event.kt             # User actions (sealed interface)
│   └── {FeatureName}UiEvent.kt           # One-time effects (sealed interface)
├── navigation/
│   ├── {FeatureName}Graph.kt             # NavGraphBuilder extension (optional)
│   ├── {FeatureName}Screen.kt            # Screen composable registration
│   └── {FeatureName}Dialog.kt            # Dialog composable registration
└── validators/                           # Input validation (optional)
    └── {FeatureName}Validator.kt
```

---

## ⚠️ CRITICAL: PLACEHOLDER CODE REQUIREMENT

**ALL placeholder and example commented-out code blocks MUST be included in the generated files.**

These are not optional - they serve as:
1. **Documentation** for common patterns used in this architecture
2. **Quick-start templates** for developers to uncomment and adapt
3. **Consistency** across all features in the codebase

**When generating files, you MUST include:**
- All `// +++ SECTION +++` and `// --- SECTION ---` comment blocks
- All multi-line commented examples (starting with `//` on each line)
- All commented function implementations
- All template code showing patterns like TopAppBar setup, observers, etc.

**Example of required placeholder (MUST be in output):**
```kotlin
    // +++ TopAppBar +++ //
    @OptIn(ExperimentalMaterial3Api::class)
    private fun initializeTopAppBar() {
//        topAppBarViewModel.onEvent(TopAppBarEvent.UpdateTitle(newTitle = {
//            val uiState by uiState.collectAsStateWithLifecycle()
//            Text(
//                text = uiState.someTitle
//            )
//        }))
//
//        /* Menu Actions */
//        val actions = topAppBarViewModel.topAppBarConfig.actions.toMutableMap()
//        actions["Save"] = {
//            IconButton(
//                content = {
//                    Icon(imageVector = Icons.Default.Save, contentDescription = "Save")
//                },
//                onClick = {
//                    onEvent(FeatureNameEvent.OnSave)
//                }
//            )
//        }
//
//        topAppBarViewModel.onEvent(TopAppBarEvent.UpdateActions(newActions = actions.toMap()))
    }
    // --- TopAppBar --- //
```

---

## Generation Templates

### Step 1: Create Directory Structure

Ensure all directories exist before writing files:
- `app/src/main/java/com/marcohuijskes/predecide2/presentation/features/{package_name}/`
- `app/src/main/java/com/marcohuijskes/predecide2/presentation/features/{package_name}/common/`
- `app/src/main/java/com/marcohuijskes/predecide2/presentation/features/{package_name}/event/`
- `app/src/main/java/com/marcohuijskes/predecide2/presentation/features/{package_name}/navigation/`
- `app/src/main/java/com/marcohuijskes/predecide2/presentation/features/{package_name}/validators/` (optional)

### Step 2: Generate Constants File (`common/{FeatureName}Constants.kt`)

```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}.common

object {FeatureName}Constants {
    
    // --- LOG TAGS --- //
    // General tags
    const val TAG_{TAG_NAME} = "TAG_{TAG_NAME}"
    
    // ViewModel tags
    const val TAG_{TAG_NAME}_VIEW_MODEL = "TAG_{TAG_NAME}_VIEW_MODEL"
    const val TAG_{TAG_NAME}_VIEW_MODEL_INITIALIZE = "TAG_{TAG_NAME}_VIEW_MODEL_INITIALIZE"

    // Screen tags
    const val TAG_{TAG_NAME}_SCREEN = "TAG_{TAG_NAME}_SCREEN"

    // Result constants
    const val {TAG_NAME}_RESULT_TO_PARENT = "{TAG_NAME}_RESULT_TO_PARENT"
}
```

### Step 3: Generate Args Files

**For Graph with Arguments** (`{FeatureName}GraphArgs.kt`):
```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}

import com.marcohuijskes.predecide2.presentation.navigation.utils.helpers.NavArgs
import kotlinx.serialization.Serializable

@Serializable
data class {FeatureName}GraphArgs(
    val id: String?, // Example argument
    // Add any other data the ViewModel needs to start
) : NavArgs
```

**For Screen/Dialog Arguments** (`{FeatureName}Args.kt`):
```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}

import com.marcohuijskes.predecide2.presentation.navigation.utils.helpers.NavArgs
import kotlinx.serialization.Serializable

// TODO: Add to .presentation.navigation.utils.serialization.JsonConfig
@Serializable
data class {FeatureName}Args(
    val id: String?, // Example argument
    val someData: List<String> = emptyList()
    // Add any other data the ViewModel needs to start
) : NavArgs
```

### Step 4: Generate UI State (`{FeatureName}UiState.kt`)

```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}

data class {FeatureName}UiState(
    // UI Management
    val showAsDialog: Boolean = false,
    val showExitConfirmationDialog: Boolean = false,
    val isLoading: Boolean = true,
    // val isOnline: Boolean = true,
    
    // Feature-specific data
    // val item: Item? = null,
    
    // Lists / Collections
    // val items: List<Item> = emptyList(),
)
```

### Step 5: Generate Events (`event/{FeatureName}Event.kt`)

```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}.event

import androidx.navigation.NavController

/**
 * Events for the {FeatureName} feature.
 *
 * This sealed interface defines all possible user actions and system events
 * that can be handled by the [{FeatureName}ViewModel].
 */
sealed interface {FeatureName}Event {
    
    // UI Management
    data object OnScreenEntered : {FeatureName}Event
    data class ToggleExitConfirmationDialog(val visible: Boolean? = null): {FeatureName}Event
    data object Dismiss : {FeatureName}Event
    
    // Used when this Screen is shown inside a Dialog as child of a parent Composable.
    // Returns a value to the parent Composable on confirm.
    data class OnConfirmChanges(val navController: NavController) : {FeatureName}Event
    
    // Used by the parent Composable to show another Screen inside a Dialog. 
    data class ShowChildDialog(val itemId: String) : {FeatureName}Event
    
    // Used by the parent Composable to process the result from the child Dialog.
    data class OnChildResult(val data: List<String>) : {FeatureName}Event
    
    // Navigation
    data object NavigateToGraph : {FeatureName}Event
    data object NavigateToDialog : {FeatureName}Event
}
```

### Step 6: Generate UI Events (`event/{FeatureName}UiEvent.kt`)

```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}.event

import com.marcohuijskes.predecide2.presentation.navigation.Route

sealed interface {FeatureName}UiEvent {
    data class ShowToast(val text: String): {FeatureName}UiEvent
    data class ShowToastLong(val text: String): {FeatureName}UiEvent
    data class NavigateToRoute(val route: Route): {FeatureName}UiEvent
    data object Dismiss : {FeatureName}UiEvent
}
```

### Step 7: Generate ViewModel (`{FeatureName}ViewModel.kt`)

```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcohuijskes.predecide2.domain.repository.SettingsProvider
import com.marcohuijskes.predecide2.common.logging.Logger
import com.marcohuijskes.predecide2.presentation.features.{package_name}.common.{FeatureName}Constants.TAG_{TAG_NAME}_VIEW_MODEL
import com.marcohuijskes.predecide2.presentation.features.{package_name}.event.{FeatureName}Event
import com.marcohuijskes.predecide2.presentation.features.{package_name}.event.{FeatureName}UiEvent
import com.marcohuijskes.predecide2.presentation.navigation.top_app_bar.TopAppBarViewModel
import com.marcohuijskes.predecide2.presentation.utils.UseCaseHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = {FeatureName}ViewModel.Factory::class)
class {FeatureName}ViewModel @AssistedInject constructor(
    // Use cases - add as needed
    // private val getItemUseCase: GetItemUseCase,
    // private val saveItemUseCase: SaveItemUseCase,

    // Validators - add as needed
    // private val itemValidator: ItemValidator,
    
    // General
    @ApplicationContext private val appContext: Context,
    private val settingsProvider: SettingsProvider,
    private val writeLog: Logger,
    private val useCaseHandler: UseCaseHandler,

    // Runtime arguments, passed by the ViewModelFactory.
    @Assisted private val args: {FeatureName}Args,
    @Assisted private val showAsDialog: Boolean,
    @Assisted private val topAppBarViewModel: TopAppBarViewModel,
) : ViewModel() {

    // Define the factory Hilt will implement for us
    @AssistedFactory
    interface Factory {
        fun create(
            args: {FeatureName}Args,
            showAsDialog: Boolean,
            topAppBarViewModel: TopAppBarViewModel
        ): {FeatureName}ViewModel
    }

    private val _uiState = MutableStateFlow({FeatureName}UiState())
    val uiState = _uiState
        .onStart {  }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = {FeatureName}UiState()
        )

    init {
        initializeViewModel()
    }

    private val uiEventChannel = Channel<{FeatureName}UiEvent>()
    val uiEvents = uiEventChannel.receiveAsFlow()

    /**
     * Handles all incoming UI events from the {FeatureName}Screen.
     */
    fun onEvent(event: {FeatureName}Event) {
        when(event) {
            // UI Management
            is {FeatureName}Event.OnScreenEntered -> initializeTopAppBar()

            is {FeatureName}Event.ToggleExitConfirmationDialog -> _uiState.update {
                it.copy(showExitConfirmationDialog = event.visible ?: !it.showExitConfirmationDialog)
            }
            is {FeatureName}Event.Dismiss -> viewModelScope.launch {
                uiEventChannel.send({FeatureName}UiEvent.Dismiss)
            }
            
            // Used when this Screen is shown inside a Dialog as child of a parent Composable.
            // Returns a value to the parent Composable on confirm.
            is {FeatureName}Event.OnConfirmChanges -> viewModelScope.launch {
//                // 1. Get the current value from the state
//                val finalValue = _uiState.value.someValue
//    
//                // 2. Set the result for the previous screen to observe
//                event.navController.previousBackStackEntry
//                    ?.savedStateHandle
//                    ?.set({TAG_NAME}_RESULT_TO_PARENT, finalValue)
//    
//                // 3. Dismiss the dialog
//                uiEventChannel.send({FeatureName}UiEvent.Dismiss)
            }
            
            // Used by the parent Composable to show another Screen inside a Dialog. 
            is {FeatureName}Event.ShowChildDialog -> viewModelScope.launch {
//                // Find the item the user clicked on
//                val selectedItem = _uiState.value.items.find { it.id == event.itemId }
//                selectedItem?.let { item ->
//                    // Create the type-safe route
//                    val route = Route.ChildScreen(args = ...)
//                    // Send a navigation event back to the UI
//                    uiEventChannel.send({FeatureName}UiEvent.NavigateToRoute(route))
//                }
            }
            
            // Used by the parent Composable to process the result from the child Dialog.
            is {FeatureName}Event.OnChildResult -> viewModelScope.launch {
//                // Process child result
//                viewModel.onEvent({FeatureName}Event.UpdateSomething(event.data))
            }
            
            // Navigation
            is {FeatureName}Event.NavigateToGraph -> viewModelScope.launch(Dispatchers.Main) {
//                uiEventChannel.send({FeatureName}UiEvent.NavigateToRoute(Route.{FeatureName}Graph({FeatureName}Args(...))))
            }
            is {FeatureName}Event.NavigateToDialog -> viewModelScope.launch(Dispatchers.Main) {
//                uiEventChannel.send({FeatureName}UiEvent.NavigateToRoute(Route.{FeatureName}Dialog({FeatureName}Args(...))))
            }
        }
    }

    private suspend fun sendSnackbar(
        message: String,
        action: SnackbarAction? = null,
        duration: SnackbarDuration = SnackbarDuration.Long
    ) {
        SnackbarController.sendEvent(
            SnackbarEvent.Show(
                message = message,
                action = action,
                duration = duration
            )
        )
    }

    // +++ TopAppBar +++ //
    @OptIn(ExperimentalMaterial3Api::class)
    private fun initializeTopAppBar() {
//        topAppBarViewModel.onEvent(TopAppBarEvent.UpdateTitle(newTitle = {
//            val uiState by uiState.collectAsStateWithLifecycle()
//            Text(
//                text = uiState.someTitle
//            )
//        }))
//
//        /* Menu Actions */
//        val actions = topAppBarViewModel.topAppBarConfig.actions.toMutableMap()
//        actions["Save"] = {
//            IconButton(
//                content = {
//                    Icon(imageVector = Icons.Default.Save, contentDescription = "Save")
//                },
//                onClick = {
//                    onEvent({FeatureName}Event.OnSave)
//                }
//            )
//        }
//        actions["Submit"] = {
//            IconButton(
//                content = {
//                    Icon(imageVector = Icons.Default.Check, contentDescription = "Submit")
//                },
//                onClick = {
//                    onEvent({FeatureName}Event.OnSubmit)
//                }
//            )
//        }
//
//        topAppBarViewModel.onEvent(TopAppBarEvent.UpdateActions(newActions = actions.toMap()))
//
//        /* OverflowMenu Actions */
//        val overflowActions = topAppBarViewModel.topAppBarConfig.overflowActions.toMutableList()
//
//        // Add divider between Settings and Import logs
//        overflowActions.add(DropdownMenuDivider)
//        overflowActions.add(
//            DropdownMenuItem(
//                text = "Placeholder",
//                onClick = { onEvent({FeatureName}Event.Placeholder) },
//                icon = Icons.Outlined.FileOpen
//            )
//        )
//
//        topAppBarViewModel.onEvent(TopAppBarEvent.UpdateNavigationIcon(
//            icon = Icons.Default.ArrowBack,
//            contentDescription = "Return button",
//            action = { onEvent({FeatureName}Event.Dismiss) }
//        ))
    }
    // --- TopAppBar --- //
    
    
    // +++ ViewModel Management +++ //    
    private fun initializeViewModel() = viewModelScope.launch {
//        val exampleSetting = async { settingsProvider.get(SETTING_NAME) }
        
        /** Load initial data here **/
//        val item = async { args.itemId?.let { getItemByIdUseCase.invoke(it) }?.first() }

        _uiState.update { it.copy(
//            item = item.await(),
            showAsDialog = showAsDialog,
            isLoading = false
        ) }
    }
    
    // --- ViewModel Management --- //

    // +++ Observers +++ //
//    init {
//        observe()
//        observeNetworkStatus()
//    }

//    private var observerJob: Job? = null

//    private fun observe() {
//        observerJob?.cancel()
//        observerJob = viewModelScope.launch {
//            // Create a flow for the set of enabled log types, which only emits when the set actually changes.
//            val enabledLogTypesFlow = _uiState.map { state ->
//                setOfNotNull(
//                    if (state.showVerboseLogs) LogType.VERBOSE else null,
//                    if (state.showDebugLogs) LogType.DEBUG else null,
//                    if (state.showInfoLogs) LogType.INFO else null,
//                    if (state.showWarningLogs) LogType.WARNING else null,
//                    if (state.showErrorLogs) LogType.ERROR else null
//                )
//            }.distinctUntilChanged()
//
//            // Create a flow for the set of selected entry IDs, which only emits when the set changes.
//            val selectedEntriesFlow = _uiState.map { it.selectedLogEntries }.distinctUntilChanged()
//
//            // Combine all source flows. The lambda will be re-executed if any of them emit a new value.
//            combine(
//                logService.getAll(), // The third flow is used directly (without defining first).
//                enabledLogTypesFlow,
//                selectedEntriesFlow
//            ) { logEntries, enabledLogTypes, selectedEntryIds ->
//                // This transformation logic is now inside combine.
//                // It runs whenever logs, filters, or selections change.
//                logEntries
//                    .filter { enabledLogTypes.contains(it.type) }
//                    .map { it.toUiEntry(selectedEntryIds.contains(it.id)) }
//            }.collectLatest { computedList ->
//                // The collector now receives the final, computed list.
//                _uiState.update { it.copy(logEntries = computedList) }
//            }
//        }
//    }
    
//    private fun observeAllNotes() {
//        viewModelScope.launch {
//            getNoteUseCase.getAll().toUiList().collectLatest { noteUiList ->
//                _uiState.update { it.copy(notes = noteUiList) }
//            }
//        }
//    }

//    /**
//     * Launches a long-lived coroutine to observe network status.
//     *
//     * We use [collectLatest] to automatically cancel processing of a
//     * previous status if a new one arrives. This elegantly handles
//     * the "flicker" problem during network switches.
//     */
//    private fun observeNetworkStatus() {
//        viewModelScope.launch {
//            connectivityService.networkStatus
//                .collectLatest { status ->
//                    if (status == NetworkStatus.Available) {
//                        // 1. We are ONLINE.
//                        // Update the UI immediately.
//                        _uiState.update { currentState ->
//                            currentState.copy(
//                                isOnline = true,
//                            )
//                        }
//                    } else {
//                        // 2. We are OFFLINE.
//                        // Wait for our delay. If 'Available' is emitted
//                        // during this time, 'collectLatest' will cancel
//                        // this entire block and start a new one for 'Available'.
//                        delay(1000L)
//
//                        sendSnackbar("Please check your network connection")
//
//                        // 3. If the delay finishes, we're still offline.
//                        // Now, update the UI.
//                        _uiState.update { currentState ->
//                            currentState.copy(
//                                isOnline = false,
//                            )
//                        }
//                    }
//                }
//        }
//    }
    // --- Observers --- //

    // +++ Helper functions +++ //
    // --- Helper functions --- //
}
```

### Step 8: Generate Root Composable (`{FeatureName}.kt`)

```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.marcohuijskes.predecide2.common.logging.LocalLogger
import com.marcohuijskes.predecide2.presentation.features.{package_name}.common.{FeatureName}Constants.TAG_{TAG_NAME}
import com.marcohuijskes.predecide2.presentation.features.{package_name}.event.{FeatureName}Event
import com.marcohuijskes.predecide2.presentation.features.{package_name}.event.{FeatureName}UiEvent
import com.marcohuijskes.predecide2.presentation.navigation.event.NavigationEvent

@Composable
fun {FeatureName}Root(
    showAsDialog: Boolean = false,
    onNavigate: (NavigationEvent) -> Unit,
    onDismiss: () -> Unit,
    innerPadding: PaddingValues,
    viewModel: {FeatureName}ViewModel = hiltViewModel(),
    navController: NavHostController,
    backStackEntry: NavBackStackEntry
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val writeLog = LocalLogger.current
    
    // ++ This block listens for the result from the child dialog ++
//    LaunchedEffect(backStackEntry) {
//        // 1. Observe a StateFlow from the SavedStateHandle.
//        val resultFlow = backStackEntry
//            .savedStateHandle
//            .getStateFlow<List<String>?>(RESULT_FROM_CHILD, null)
//
//        resultFlow.collect { result ->
//            // 2. Only process the result if it's not the initial null value.
//            if (result != null) {
//                writeLog.v(TAG_{TAG_NAME}, "Received result: $result")
//
//                // 3. IMPORTANT: Reset the value back to null
//                backStackEntry.savedStateHandle[RESULT_FROM_CHILD] = null
//
//                // 4. Process the result
//                viewModel.onEvent({FeatureName}Event.OnChildResult(result))
//
//                // 5. Hide the child dialog
//                viewModel.onEvent({FeatureName}Event.HideChildDialog)
//            }
//        }
//    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is {FeatureName}UiEvent.ShowToast -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is {FeatureName}UiEvent.ShowToastLong -> Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
                is {FeatureName}UiEvent.NavigateToRoute -> onNavigate(NavigationEvent.toRoute(event.route))
                is {FeatureName}UiEvent.Dismiss -> {
                    onDismiss()
                }
            }
        }
    }

    if(showAsDialog) {
        Dialog(
            onDismissRequest = { viewModel.onEvent({FeatureName}Event.Dismiss) },
            properties = DialogProperties(
                usePlatformDefaultWidth = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                {FeatureName}Screen(
                    modifier = Modifier
                        .fillMaxWidth(),
                    showAsDialog = showAsDialog,
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            {FeatureName}Screen(
                modifier = Modifier
                    .fillMaxSize(),
                showAsDialog = showAsDialog,
                uiState = uiState,
                onEvent = viewModel::onEvent,
            )
        }
    }

    // +++ Helper functions +++ //
//    BackHandler(enabled = !uiState.showExitConfirmationDialog) {
//        viewModel.onEvent({FeatureName}Event.ToggleExitConfirmationDialog(true))
//    }
    // --- Helper functions --- //
}


@Composable
fun {FeatureName}Screen(
    modifier: Modifier = Modifier,
    showAsDialog: Boolean,
    uiState: {FeatureName}UiState,
    onEvent: ({FeatureName}Event) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val writeLog = LocalLogger.current

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    
    val scope = rememberCoroutineScope()
    
    fun clearFocus() {
        focusManager.clearFocus()
    }
    
    fun vibrate() = ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK)
    
    Box {
        val scrollState = rememberScrollState()
        ReactiveScreenHolder(
            modifier = Modifier,
            showAsDialog = showAsDialog,
            onDismissRequest = { onEvent({FeatureName}Event.Dismiss) }
        )
         {
             Column(
                 modifier = modifier
                     .padding(12.dp)
                     .pointerInput(Unit) {
                         detectTapGestures(onTap = { clearFocus() })
                     }
                     .verticalScroll(state = scrollState),
             ) {
                // TODO: The main screen content goes here.
                
            }
        }

        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (showAsDialog) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    // +++ DIALOGS / POPUPS +++ //
    // if(!uiState.isLoading && uiState.showPopup) {  }
    // --- DIALOGS / POPUPS --- //
}

//@Preview(showBackground = true)
//@Composable
//private fun {FeatureName}ScreenPreview() {
//    AppTheme {
//        {FeatureName}Screen(
//            modifier = Modifier,
//            uiState = {FeatureName}UiState(isLoading = false),
//            onEvent = {},
//            showAsDialog = false,
//        )
//    }
//}
```

### Step 9: Generate Navigation Files

**Screen Navigation** (`navigation/{FeatureName}Screen.kt`):
```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.marcohuijskes.predecide2.common.logging.LocalLogger
import com.marcohuijskes.predecide2.presentation.features.{package_name}.{FeatureName}Args
import com.marcohuijskes.predecide2.presentation.features.{package_name}.{FeatureName}Root
import com.marcohuijskes.predecide2.presentation.features.{package_name}.{FeatureName}ViewModel
import com.marcohuijskes.predecide2.presentation.features.{package_name}.common.{FeatureName}Constants.TAG_{TAG_NAME}
import com.marcohuijskes.predecide2.presentation.navigation.Route
import com.marcohuijskes.predecide2.presentation.navigation.top_app_bar.TopAppBarViewModel
import com.marcohuijskes.predecide2.presentation.navigation.top_app_bar.event.TopAppBarEvent
import com.marcohuijskes.predecide2.presentation.navigation.top_app_bar.model.TopAppBarConfig
import com.marcohuijskes.predecide2.presentation.navigation.utils.handleNavigationEvent
import com.marcohuijskes.predecide2.presentation.navigation.utils.helpers.decodeArguments
import com.marcohuijskes.predecide2.presentation.navigation.utils.helpers.rememberParentEntry

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.{featureNameLower}Screen(
    navController: NavHostController,
    innerPadding: PaddingValues
) {
    // --- Template for a Standalone Screen WITH Arguments ---
    composable<Route.{FeatureName}ScreenWithJson> { navBackStackEntry ->
        val context = LocalContext.current
        val writeLog = LocalLogger.current

        // - GET SHARED TOP_APP_BAR VIEWMODEL -
        val topAppBarViewModel: TopAppBarViewModel = hiltViewModel(
            LocalActivity.current as ViewModelStoreOwner
        )
        // - INITIALIZE TOP_APP_BAR -
        LaunchedEffect(Unit) {
            topAppBarViewModel.onEvent(
                TopAppBarEvent.SetTopAppBarConfig(
                    newConfig = TopAppBarConfig.DefaultAppBar(
//                        title = {
//                            Text(
//                                text = context.getString(R.string.app_name),
//                                maxLines = 1,
//                                overflow = TextOverflow.Ellipsis
//                            )
//                        }
                    ),
                    navController = navController
                )
            )
        }

        // - SCREEN ARGUMENTS -
        val args: {FeatureName}Args = navBackStackEntry.decodeArguments(
            routeClass = Route.{FeatureName}ScreenWithJson::class
        )

        // - INITIALIZE VIEWMODEL -
        val viewModel: {FeatureName}ViewModel = hiltViewModel(
            key = "{TAG_NAME}_SCREEN_${'$'}{args.toString().hashCode()}",
            creationCallback = { factory: {FeatureName}ViewModel.Factory ->
                factory.create(
                    args = args,
                    showAsDialog = false,
                    topAppBarViewModel = topAppBarViewModel
                )
            }
        )

        LaunchedEffect(Unit) {
            viewModel.onEvent({FeatureName}Event.OnScreenEntered)
        }

        // - COMPOSABLE -
        {FeatureName}Root(
            viewModel = viewModel,
            onNavigate = { event -> navController.handleNavigationEvent(event) },
            showAsDialog = false,
            onDismiss = {
                writeLog.v(TAG_{TAG_NAME}, "Dismissing Screen via onDismiss")
                navController.popBackStack()
            },
            innerPadding = innerPadding,
            navController = navController,
            backStackEntry = navBackStackEntry
        )
    }
}
```

**Dialog Navigation** (`navigation/{FeatureName}Dialog.kt`):
```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.dialog
import com.marcohuijskes.predecide2.common.logging.LocalLogger
import com.marcohuijskes.predecide2.presentation.features.{package_name}.{FeatureName}Args
import com.marcohuijskes.predecide2.presentation.features.{package_name}.{FeatureName}Root
import com.marcohuijskes.predecide2.presentation.features.{package_name}.{FeatureName}ViewModel
import com.marcohuijskes.predecide2.presentation.features.{package_name}.common.{FeatureName}Constants.TAG_{TAG_NAME}
import com.marcohuijskes.predecide2.presentation.navigation.Route
import com.marcohuijskes.predecide2.presentation.navigation.top_app_bar.TopAppBarViewModel
import com.marcohuijskes.predecide2.presentation.navigation.utils.handleNavigationEvent
import com.marcohuijskes.predecide2.presentation.navigation.utils.helpers.decodeArguments

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.{featureNameLower}Dialog(
    navController: NavHostController,
    innerPadding: PaddingValues
) {
    // --- Template for a Standalone Dialog WITH Arguments ---
    dialog<Route.{FeatureName}DialogWithJson> { navBackStackEntry ->
        val context = LocalContext.current
        val writeLog = LocalLogger.current

        // - GET FAKE TOP_APP_BAR VIEWMODEL -
        val topAppBarViewModel: TopAppBarViewModel = hiltViewModel()

        // - DIALOG ARGUMENTS -
        val args: {FeatureName}Args = navBackStackEntry.decodeArguments(
            routeClass = Route.{FeatureName}DialogWithJson::class
        )

        // - INITIALIZE VIEWMODEL -
        val viewModel: {FeatureName}ViewModel = hiltViewModel(
            key = "{TAG_NAME}_DIALOG_${'$'}{args.toString().hashCode()}",
            creationCallback = { factory: {FeatureName}ViewModel.Factory ->
                factory.create(
                    args = args,
                    showAsDialog = true,
                    topAppBarViewModel = topAppBarViewModel
                )
            }
        )

        // - COMPOSABLE -
        {FeatureName}Root(
            viewModel = viewModel,
            onNavigate = { event -> navController.handleNavigationEvent(event) },
            showAsDialog = true,
            onDismiss = {
                writeLog.v(TAG_{TAG_NAME}, "Dismissing Dialog via onDismiss")
                navController.popBackStack()
            },
            innerPadding = innerPadding,
            navController = navController,
            backStackEntry = navBackStackEntry
        )
    }
}
```

**Graph Navigation** (`navigation/{FeatureName}Graph.kt`) - If needed:
```kotlin
package com.marcohuijskes.predecide2.presentation.features.{package_name}.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import com.marcohuijskes.predecide2.common.utils.DeepLinkConstants
import com.marcohuijskes.predecide2.presentation.navigation.Route

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.{featureNameLower}Graph(
    navController: NavHostController,
    innerPadding: PaddingValues
) {
    navigation<Route.{FeatureName}GraphWithJson>(
        startDestination = Route.{FeatureName}GraphWithJson.{FeatureName}Screen,
//        deepLinks = listOf(
//            navDeepLink {
//                uriPattern = "${DeepLinkConstants.SCHEME}://${DeepLinkConstants.HOST}/.../{argsAsJson}"
//            }
//        )
    ) {
        // - COMPOSABLES -
        {featureNameLower}Screen(navController, innerPadding)
        {featureNameLower}Dialog(navController, innerPadding)
        
        // Add child destinations here
    }
}
```

---

## Post-Generation Checklist

After generating the feature files:

1. **Add Route to Route.kt** - Add the new route class to `presentation/navigation/Route.kt`
2. **Register Args in JsonConfig** - Add `{FeatureName}Args` to the serializers in `presentation/navigation/serialization/JsonConfig.kt`
3. **Add Graph to Main Navigation** - Call `featureGraph()` in `presentation/navigation/AppNavigation.kt`
4. **Add UseCase Bindings** - Add any new UseCases to DI modules
5. **Add Strings** - Add any needed strings to `res/values/strings.xml`

## Common Patterns

### Child-to-Parent Result Communication

In Parent (listening for result):
```kotlin
LaunchedEffect(backStackEntry) {
    val resultFlow = backStackEntry
        .savedStateHandle
        .getStateFlow<ResultType?>(CHILD_RESULT_KEY, null)
    
    resultFlow.collect { result ->
        if (result != null) {
            backStackEntry.savedStateHandle[CHILD_RESULT_KEY] = null
            viewModel.onEvent(ParentEvent.OnChildResult(result))
        }
    }
}
```

In Child (sending result):
```kotlin
// In ViewModel when confirming
navController.previousBackStackEntry
    ?.savedStateHandle
    ?.set(CHILD_RESULT_KEY, resultData)
uiEventChannel.send(ChildUiEvent.Dismiss)
```

### Observing Flows in ViewModel

```kotlin
private fun observeData() = viewModelScope.launch {
    combine(
        flow1,
        flow2
    ) { v1, v2 ->
        // Transform
    }.collectLatest { result ->
        _uiState.update { it.copy(data = result) }
    }
}
```

### TopAppBar Configuration

```kotlin
private fun initializeTopAppBar() {
    // Dynamic title
    topAppBarViewModel.onEvent(TopAppBarEvent.UpdateTitle(newTitle = {
        val uiState by uiState.collectAsStateWithLifecycle()
        Text(text = uiState.title)
    }))
    
    // Actions
    val actions = mutableMapOf<String, @Composable () -> Unit>()
    actions["Action"] = { IconButton(...) { Icon(...) } }
    topAppBarViewModel.onEvent(TopAppBarEvent.UpdateActions(actions.toImmutableMap()))
    
    // Overflow menu
    val overflow = mutableListOf<DropdownMenuComponent>()
    overflow.add(DropdownMenuItem(text = "Item", onClick = { }, icon = Icons.Default.X))
    topAppBarViewModel.onEvent(TopAppBarEvent.UpdateOverflowActions(overflow))
}
```

## Important Notes

1. **Always use `Dispatchers.Main` for navigation events** - Navigation must happen on main thread
2. **Reset SavedStateHandle values after reading** - Prevents re-processing on config change
3. **Use `WhileSubscribed(5000)` for StateFlow** - Keeps data alive briefly after UI leaves
4. **ViewModels are scoped to graph** - Use `rememberParentEntry()` for graph-scoped VMs
5. **TopAppBarVM is scoped to Activity** - Get it via `LocalActivity.current as ViewModelStoreOwner`
6. **ALL placeholder comments must be preserved** - They are part of the architecture documentation
