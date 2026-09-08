---
name: android-module-structure
description: |
  TRIGGER: User asks about project structure, where to place a file, package organization, or how the codebase is laid out.

  ACTION: IMMEDIATELY load this skill for single-module package-by-layer structure.

  Use for: Deciding where new classes go, understanding package boundaries, feature organization.

  Out of scope: Multi-module Gradle projects, KMP module setup → not applicable to this project.

  Examples:
  - user: "Where do I put a new repository?" → load skill, show data/repository/
  - user: "How is this project structured?" → load skill, explain package layers
  - user: "Where does the ViewModel go?" → load skill, show presentation/features/
---

# Android Module Structure

## MANDATORY FIRST ACTIONS

Before creating new files:
1. Identify the layer: Presentation, Domain, or Data
2. Identify the feature or shared concern
3. Place in the correct package following existing conventions

Skip = files end up in wrong locations.

## Project Architecture

This project uses a **single Android module** with **package-by-layer** organization. There are NO `:feature:<name>:domain` multi-module structures.

```
app/src/main/java/com/marcohuijskes/predecide2/
├── presentation/          # ViewModels, Composables, Navigation, UI
│   ├── features/          # One package per feature
│   │   ├── note_list/
│   │   ├── note_modify/
│   │   ├── category_list/
│   │   └── ...
│   ├── navigation/        # Type-safe routes, NavHost
│   ├── ui/                # Shared UI components, theme, snackbar
│   ├── model/             # UI-specific models (NoteUi, etc.)
│   ├── mappers/           # Domain → UI model conversion
│   ├── activities/        # MainActivity
│   └── utils/             # UseCaseHandler, shared utilities
│
├── domain/                # Business logic, pure Kotlin
│   ├── model/             # Domain models (Note, Category)
│   ├── use_case/          # Single-responsibility operations
│   ├── repository/        # Repository interfaces
│   ├── results/           # UseCaseResultPayload, ValidationResult
│   ├── enums/             # Domain enumerations
│   ├── exceptions/        # Custom domain exceptions
│   ├── service/           # Cross-cutting service interfaces
│   ├── mappers/           # Domain-level mappings
│   ├── factories/         # Object creation logic
│   ├── network/           # Network contracts (SessionManager)
│   ├── state/             # Shared state holders
│   └── utils/             # Domain utilities (PermissionUtils)
│
├── data/                  # Repository implementations, DAOs, APIs
│   ├── local/             # Room entities, DAOs, database
│   │   ├── database/dao/  # All DAOs
│   │   ├── entity/        # Room entities
│   │   ├── mappers/       # Entity → Domain mappers
│   │   └── helpers/       # DB helpers
│   ├── remote/            # Retrofit APIs, DTOs, sync engine
│   │   ├── api/           # Retrofit interfaces
│   │   ├── dto/           # Request/response DTOs
│   │   ├── datasource/    # Remote data sources
│   │   ├── mappers/       # DTO → Domain mappers
│   │   └── sync/          # Sync engine, workers, handlers
│   ├── repository/        # Repository implementations
│   ├── session/           # Auth state, token storage
│   └── worker/            # WorkManager workers
│
├── common/                # Shared utilities across all layers
│   ├── converters/        # Type converters
│   ├── extension_functions/
│   ├── formatters/        # Date, number formatters
│   ├── logging/           # Logger interface, implementations
│   ├── serializers/       # JSON serializers
│   └── utils/             # Shared utilities
│
├── core/                  # Security, exceptions, SortOrderMerger
│   ├── security/
│   └── util/
│
└── di/                    # Dagger Hilt modules
    ├── AppModule.kt
    ├── RepositoryModule.kt
    ├── DatabaseModule.kt
    ├── NetworkModule.kt
    └── ...
```

## Feature Package Structure

Each feature in `presentation/features/` follows this layout (with variation by feature complexity):

```
feature_name/
├── FeatureName.kt                    # Root composable (screen entry)
├── FeatureNameViewModel.kt           # ViewModel + StateFlow
├── FeatureNameUiState.kt             # UI state data class
├── FeatureNameArgs.kt                # Navigation arguments (not always GraphArgs)
├── common/
│   └── FeatureNameConstants.kt       # Feature-specific constants
├── components/
│   ├── FeatureNameContent.kt         # Main screen content
│   ├── FeatureNameTopBar.kt          # App bar
│   └── ...                           # Other UI components
├── event/
│   ├── FeatureNameEvent.kt           # User actions (sealed interface)
│   └── FeatureNameUiEvent.kt         # One-time effects (sealed interface)
├── navigation/
│   ├── FeatureNameGraph.kt           # Navigation graph
│   └── FeatureNameScreen.kt          # Screen wrapper
├── reducers/                         # Optimistic UI update functions (optional)
│   └── FeatureNameReducers.kt
├── validators/                       # Input validation (optional)
│   └── FeatureNameValidator.kt
└── results/                          # Feature-specific results (optional)
    └── FeatureNameResults.kt
```

**Note:** Not all subpackages exist in every feature. Simple features may omit `reducers/`, `validators/`, or `results/`.

### Domain Subpackages

Domain models and use cases are organized by feature subpackage:

```
domain/
├── model/
│   ├── category/           # Category, LayoutMode
│   ├── note_group/         # NoteGroup, NoteGroupInput
│   ├── sync/               # SyncJob hierarchy
│   └── ...
├── use_case/
│   ├── categories/
│   │   ├── get/            # GetAllCategoriesUseCase, GetCategoryByIdUseCase
│   │   ├── save/           # UpsertCategoryUseCase
│   │   └── order/          # ReorderCategoryItemIdsUseCase
│   ├── notes/
│   │   ├── get/            # GetNotesForCategoryUseCase
│   │   ├── save/           # UpsertNoteUseCase
│   │   └── delete/         # SoftDeleteNoteUseCase
│   └── ...
```

### DI Subpackages

Hilt modules have subpackages for organization:

```
di/
├── sync/
│   ├── ConsolidationModule.kt
│   └── SyncHandlerModule.kt
├── qualifiers/
│   └── NetworkQualifiers.kt
└── ...
```

## Dependency Rules

| From | To | Allowed? |
|---|---|---|
| Presentation | Domain | Yes — via UseCase interfaces |
| Domain | Data | No — Domain defines interfaces only |
| Data | Domain | Yes — implements domain interfaces |
| Presentation | Data | No — must go through Domain UseCases |
| Sync Engine | Data Local | Yes — direct DAO access in TransactionProvider |

## Key Rules

1. NEVER create new Gradle modules — this is a single-module project
2. ALWAYS place feature code in `presentation/features/<feature_name>/`
3. ALWAYS keep repository interfaces in `domain/repository/` and implementations in `data/repository/`
4. ALWAYS place Hilt modules in `di/`
5. NEVER cross-import between features — shared code goes in `common/`, `core/`, or `presentation/ui/`

## COMPLIANCE CHECKLIST

Before creating new files:
- [ ] Layer identified (Presentation / Domain / Data / Common): (YES/NO)
- [ ] Feature package name matches existing convention (snake_case): (YES/NO)
- [ ] No cross-feature imports introduced: (YES/NO)
- [ ] Repository interface in domain/, implementation in data/: (YES/NO)
- [ ] Hilt module added to di/ if new bindings needed: (YES/NO)

If NO to any question, STOP and resolve before continuing.
