---
name: android-navigation
description: |
  TRIGGER: User asks about navigation, routes, NavController, adding a screen, or deep links.

  ACTION: IMMEDIATELY load this skill for type-safe navigation patterns.

  Use for: Adding routes, navigating between screens, passing arguments, setting up nav graphs.

  Out of scope: Koin ViewModel injection, multi-module navigation graphs → not used in this project.

  Examples:
  - user: "Add a new screen" → load skill, show Route.kt pattern
  - user: "How do I navigate?" → load skill, show NavController pattern
  - user: "Pass arguments" → load skill, show RouteWithArgs pattern
---

# Android Navigation

## MANDATORY FIRST ACTIONS

Before adding navigation:
1. Define args class implementing `NavArgs`
2. Register args class in `JsonConfig.kt`
3. Add route to `Route.kt` sealed interface

Skip = navigation fails at runtime.

## Type-Safe Navigation

Routes are defined in a centralized `Route.kt` as `@Serializable` nested classes:

```kotlin
// presentation/navigation/Route.kt
sealed interface Route {

    @Serializable
    data class NoteListGraph(override val args: NoteListGraphArgs) : Route,
        RouteWithArgs<NoteListGraphArgs> {
        override fun toRouteWithJson(json: String) = NoteListGraphWithJson(json)

        data object NoteListScreen : Route
    }

    @Serializable
    data class NoteListGraphWithJson(val argsAsJson: String) : Route {
        data object NoteListScreen : Route
    }

    // ... other routes
}
```

## Navigation Arguments

Args classes implement `NavArgs` and are serialized to JSON:

```kotlin
// presentation/features/note_list/NoteListGraphArgs.kt
@Serializable
data class NoteListGraphArgs(
    val categoryId: String,
    val categoryName: String,
    val isReadOnly: Boolean = false
) : NavArgs
```

**Critical:** Register the args class in `presentation/navigation/utils/serialization/JsonConfig.kt`:

```kotlin
val appSerializersModule = SerializersModule {
    polymorphic(NavArgs::class) {
        subclass(NoteListGraphArgs::class)
        // ... register all NavArgs subclasses
    }
}

val appJson = Json {
    serializersModule = appSerializersModule
    ignoreUnknownKeys = true
}
```

## Navigating

Use `NavigationEvent.toRoute()` to convert dev-facing routes to navigation-compatible routes:

```kotlin
// In ViewModel
uiEventChannel.send(NoteListUiEvent.NavigateToRoute(
    Route.NoteModifyGraph(args = NoteModifyGraphArgs(noteId = id))
))

// In Composable / MainActivity
LaunchedEffect(Unit) {
    viewModel.uiEvents.collect { event ->
        when (event) {
            is NoteListUiEvent.NavigateToRoute -> {
                val navEvent = NavigationEvent.toRoute(event.route)
                navController.navigate(navEvent.route)
            }
        }
    }
}
```

**Critical:** `NavigationEvent.toRoute()` automatically converts `RouteWithArgs` to `...WithJson` variants. Navigation graphs use the JSON-compatible variants.

## Receiving Arguments

Extract args in the destination using `decodeArguments()`:

```kotlin
@Composable
fun NoteListScreen(
    navBackStackEntry: NavBackStackEntry
) {
    val args = navBackStackEntry.decodeArguments<NoteListGraphArgs>()
    val categoryId = args.categoryId
    // ...
}
```

## Deep Link Handling

Deep links use URL-encoded JSON for argument safety:

```kotlin
// Build deep link URI
val json = JsonConverter.toSafeJson(args)
val uri = "$SCHEME://$NOTE_HOST/$json"
```

Parse incoming deep links via `extractDeepLinkDestination()`:

```kotlin
// In MainActivity
val deepLinkDestination = extractDeepLinkDestination(intent)
when (deepLinkDestination) {
    is DeepLinkDestination.Note -> navController.navigate(
        Route.NoteViewGraph(NoteViewGraphArgs(deepLinkDestination.noteId))
    )
    is DeepLinkDestination.Alarm -> // ...
}
```

## Navigation Graph

The `NavigationGraph` composable assembles all routes:

```kotlin
@Composable
fun NavigationGraph(
    navController: NavHostController,
    // Callbacks for cross-feature navigation
    onNavigateToNoteView: (String) -> Unit
) {
    NavHost(navController = navController, startDestination = Route.AuthGraph) {
        // Auth
        composable<Route.AuthGraph> { AuthScreen(navController) }
        
        // Note List — navigation graph uses WithJson variant
        composable<Route.NoteListGraphWithJson> { backStackEntry ->
            val args = backStackEntry.decodeArguments<NoteListGraphArgs>()
            NoteListScreen(args = args)
        }
        
        // ... other destinations
    }
}
```

## Key Rules

1. ALWAYS use `@Serializable` route classes — no string-based routes
2. ALWAYS register new `NavArgs` in `JsonConfig.kt` at the correct path
3. ALWAYS pass simple types (String, Int, Boolean) in args — never complex objects (pass IDs and load data in the destination ViewModel)
4. NEVER navigate directly from Composables — send UiEvent to ViewModel
5. ALWAYS use `RouteWithArgs<T>` for routes with arguments
6. ALWAYS navigate via `NavigationEvent.toRoute()` — never pass dev-facing routes directly to `NavController`
7. Navigation graphs use `...WithJson` variants, not dev-facing `Route` classes

## COMPLIANCE CHECKLIST

Before adding navigation:
- [ ] Args class implements `NavArgs` and is `@Serializable`: (YES/NO)
- [ ] Args registered in `presentation/navigation/utils/serialization/JsonConfig.kt`: (YES/NO)
- [ ] Route added to `Route.kt` sealed interface with both dev-facing and WithJson variants: (YES/NO)
- [ ] Navigation uses `NavigationEvent.toRoute()` before passing to NavController: (YES/NO)
- [ ] Navigation triggered via `UiEvent` (not from Composable directly): (YES/NO)

If NO to any question, STOP and fix before continuing.
