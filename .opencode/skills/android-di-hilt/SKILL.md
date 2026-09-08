---
name: android-di-hilt
description: |
  TRIGGER: User mentions Dagger Hilt, @HiltViewModel, @Inject, @Module, @Provides, @Binds, or asks about dependency injection in this project.

  ACTION: IMMEDIATELY load this skill for Hilt DI patterns used in PreDecide2.

  Use for: Creating Hilt modules, injecting ViewModels, repositories, UseCases, or setting up DI.

  Out of scope: Koin, manual DI, Dagger without Hilt → use other resources.

  Examples:
  - user: "Add a new repository to DI" → load skill, show @Provides pattern
  - user: "How do I inject a ViewModel?" → load skill, show @HiltViewModel
  - user: "Create a Hilt module" → load skill, show @Module @InstallIn pattern
---

# Android DI with Dagger Hilt

## MANDATORY FIRST ACTIONS

Before using this skill:
1. Verify you are in a project that uses Dagger-Hilt (single-module, Hilt-based)
2. Confirm the class needs DI (ViewModels, Repositories, UseCases, Services)
3. Identify the correct Hilt component scope

Skip = incorrect DI setup.

## Hilt Setup

This project uses **Dagger Hilt** for dependency injection. All Hilt modules live in `di/`.

### ViewModel Injection

**Most feature ViewModels use `@AssistedInject` with `@AssistedFactory`** for runtime arguments (navigation args, dialog flags, shared `TopAppBarViewModel`):

```kotlin
@HiltViewModel(assistedFactory = NoteListViewModel.Factory::class)
class NoteListViewModel @AssistedInject constructor(
    private val getNotesUseCase: GetNotesForCategoryUseCase,
    private val writeLog: Logger,
    @Assisted private val args: NoteListGraphArgs,
    @Assisted private val showAsDialog: Boolean,
    @Assisted private val topAppBarViewModel: TopAppBarViewModel
) : ViewModel() {

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

**Simple ViewModels** (no runtime args) use plain `@Inject`:

```kotlin
@HiltViewModel
class IngredientListViewModel @Inject constructor(
    private val getIngredientsUseCase: GetIngredientsUseCase
) : ViewModel() {
    // ...
}
```

In Composables, use `hiltViewModel()`:

```kotlin
@Composable
fun NoteListScreen(
    viewModel: NoteListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // ...
}
```

### Module Definition

Use `@Module` with `@InstallIn(SingletonComponent::class)`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Singleton
    @Provides
    fun provideNotesRepository(
        noteDao: NoteDao,
        noteRemoteDataSource: NoteRemoteDataSource,
        // ...
    ): NotesRepository = NotesRepositoryImpl(...)
}
```

For interface bindings, use abstract modules with `@Binds`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class DataSourceModule {

    @Binds
    abstract fun bindNoteRemoteDataSource(
        impl: NoteRemoteDataSourceImpl
    ): NoteRemoteDataSource
}
```

## Scoping Rules

| Annotation | Scope | Use For |
|---|---|---|
| `@Singleton` | Application lifetime | Repositories, DAOs, APIs, Logger |
| `@ViewModelScoped` | ViewModel lifetime | UseCases injected into ViewModels |
| `@ActivityScoped` | Activity lifetime | Rare — avoid if possible |
| No scope | New instance per injection | DTOs, mappers, value objects |

## Named / Qualified Dependencies

Use `@Named` for multiple instances of the same type:

```kotlin
@Provides
@Singleton
@Named("session_datastore")
fun provideSessionDataStore(
    @ApplicationContext context: Context
): DataStore<Preferences> = // ...

@Provides
@Singleton
@Named("sync_preferences")
fun provideSyncDataStore(
    @ApplicationContext context: Context
): DataStore<Preferences> = // ...
```

Inject with matching `@Named`:

```kotlin
class SessionManagerImpl @Inject constructor(
    @Named("session_datastore") private val dataStore: DataStore<Preferences>
) : SessionManager
```

## Custom Qualifiers

For type-safe qualification, create custom annotations:

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainClient
```

Use on both `@Provides` and injection sites:

```kotlin
@AuthClient
@Provides
@Singleton
fun provideAuthOkHttpClient(...): OkHttpClient { ... }

@MainClient
@Provides
@Singleton
fun provideMainOkHttpClient(...): OkHttpClient { ... }

@FileClient
@Provides
@Singleton
fun provideFileOkHttpClient(...): OkHttpClient { ... }
```

## ApplicationScope

For coroutine scopes tied to application lifecycle:

```kotlin
@Provides
@Singleton
@ApplicationScopeIO
fun provideApplicationScopeIO(): CoroutineScope = 
    CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

The `@ApplicationScopeIO` qualifier provides an IO-scoped coroutine scope for background operations.

## Dispatcher Injection

Inject dispatchers via qualifiers instead of hardcoding:

```kotlin
class SomeRepository @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun doWork() = withContext(ioDispatcher) { ... }
}
```

| Qualifier | Dispatcher |
|---|---|
| `@IoDispatcher` | `Dispatchers.IO` |
| `@DefaultDispatcher` | `Dispatchers.Default` |
| `@MainDispatcher` | `Dispatchers.Main` |

## Key Rules

1. ALWAYS use constructor injection where possible
2. Feature ViewModels use `@AssistedInject` with `@AssistedFactory` for runtime args
3. Simple ViewModels use `@Inject constructor` when no runtime args needed
4. ALWAYS scope repositories and singletons with `@Singleton`
5. NEVER create dependencies manually — let Hilt provide them
6. ALWAYS place modules in `di/` package

## COMPLIANCE CHECKLIST

Before responding, verify:
- [ ] Feature ViewModels use `@HiltViewModel` with `@AssistedInject` + `@AssistedFactory`: (YES/NO)
- [ ] Simple ViewModels use `@HiltViewModel` with `@Inject constructor`: (YES/NO)
- [ ] Modules use `@InstallIn(SingletonComponent::class)`: (YES/NO)
- [ ] Repositories scoped with `@Singleton`: (YES/NO)
- [ ] No manual instantiation of dependencies: (YES/NO)

If NO to any question, STOP and fix before continuing.
