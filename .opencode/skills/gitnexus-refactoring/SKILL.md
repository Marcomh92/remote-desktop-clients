---
name: gitnexus-refactoring
description: |
  TRIGGER: User wants to rename, extract, split, move, or restructure code.

  ACTION: Load this skill and use gitnexus tools for safe multi-file refactoring.

  Use for: Symbol renaming, module extraction, class splitting, file reorganization.

  Out of scope: General code improvements without structural changes.

  Examples:
  - user: "Rename this function" → use gitnexus_rename with dry_run first
  - user: "Extract this into a module" → plan extraction with impact analysis
  - user: "Move this class to another file" → use detect_changes after moving
---

# Refactoring with GitNexus

## MANDATORY RULES

1. Always pass `repo: "PreDecide2"` in every gitnexus_* tool call.
2. Never invoke gitnexus_* tools via the bash tool — they are MCP tools, not CLI commands.
3. Use `gitnexus_rename` with `dry_run: true` BEFORE applying edits — never rename blind.

## When to Use

- "Rename this function safely"
- "Extract this into a module"
- "Split this service"
- "Move this to a new file"
- Any task involving renaming, extracting, splitting, or restructuring code

## Workflow

```
1. gitnexus_impact({target: "X", direction: "upstream", repo: "PreDecide2"})  → Map all dependents
2. gitnexus_query({query: "X", repo: "PreDecide2"})                            → Find execution flows involving X
3. gitnexus_context({name: "X", repo: "PreDecide2"})                           → See all incoming/outgoing refs
4. Plan update order: interfaces → implementations → callers → tests
```

> If the index is stale → run bash command `gitnexus analyze`.

## Checklists

### Rename Symbol

```
- [ ] gitnexus_rename({symbol_name: "oldName", new_name: "newName", dry_run: true, repo: "PreDecide2"}) — preview all edits
- [ ] Review graph edits (high confidence) and ast_search edits (review carefully)
- [ ] If satisfied: gitnexus_rename({..., dry_run: false, repo: "PreDecide2"}) — apply edits
- [ ] gitnexus_detect_changes({repo: "PreDecide2"}) — verify only expected files changed
- [ ] Run tests for affected processes
```

### Extract Module

```
- [ ] gitnexus_context({name: target, repo: "PreDecide2"}) — see all incoming/outgoing refs
- [ ] gitnexus_impact({target, direction: "upstream", repo: "PreDecide2"}) — find all external callers
- [ ] Define new module interface
- [ ] Extract code, update imports
- [ ] gitnexus_detect_changes({repo: "PreDecide2"}) — verify affected scope
- [ ] Run tests for affected processes
```

### Split Function/Service

```
- [ ] gitnexus_context({name: target, repo: "PreDecide2"}) — understand all callees
- [ ] Group callees by responsibility
- [ ] gitnexus_impact({target, direction: "upstream", repo: "PreDecide2"}) — map callers to update
- [ ] Create new functions/services
- [ ] Update callers
- [ ] gitnexus_detect_changes({repo: "PreDecide2"}) — verify affected scope
- [ ] Run tests for affected processes
```

## Tools

**gitnexus_rename** — automated multi-file rename:

```
gitnexus_rename({symbol_name: "validateUser", new_name: "authenticateUser", dry_run: true, repo: "PreDecide2"})
→ 12 edits across 8 files
→ 10 graph edits (high confidence), 2 ast_search edits (review)
→ Changes: [{file_path, edits: [{line, old_text, new_text, confidence}]}]
```

**gitnexus_impact** — map all dependents first:

```
gitnexus_impact({target: "validateUser", direction: "upstream", repo: "PreDecide2"})
→ d=1: loginHandler, apiMiddleware, testUtils
→ Affected Processes: LoginFlow, TokenRefresh
```

**gitnexus_detect_changes** — verify your changes after refactoring:

```
gitnexus_detect_changes({scope: "all", repo: "PreDecide2"})
→ Changed: 8 files, 12 symbols
→ Affected processes: LoginFlow, TokenRefresh
→ Risk: MEDIUM
```

**gitnexus_cypher** — custom reference queries:

```cypher
MATCH (caller)-[:CodeRelation {type: 'CALLS'}]->(f:Function {name: "validateUser"})
RETURN caller.name, caller.filePath ORDER BY caller.filePath
```

## Risk Rules

| Risk Factor         | Mitigation                                |
| ------------------- | ----------------------------------------- |
| Many callers (>5)   | Use gitnexus_rename for automated updates |
| Cross-area refs     | Use detect_changes after to verify scope  |
| String/dynamic refs | gitnexus_query to find them               |
| External/public API | Version and deprecate properly            |

## Example: Rename `validateUser` to `authenticateUser`

```
1. gitnexus_rename({symbol_name: "validateUser", new_name: "authenticateUser", dry_run: true, repo: "PreDecide2"})
   → 12 edits: 10 graph (safe), 2 ast_search (review)
   → Files: validator.ts, login.ts, middleware.ts, config.json...

2. Review ast_search edits (config.json: dynamic reference!)

3. gitnexus_rename({symbol_name: "validateUser", new_name: "authenticateUser", dry_run: false, repo: "PreDecide2"})
   → Applied 12 edits across 8 files

4. gitnexus_detect_changes({scope: "all", repo: "PreDecide2"})
   → Affected: LoginFlow, TokenRefresh
   → Risk: MEDIUM — run tests for these flows
```

## Compliance Checklist

- [ ] `repo: "PreDecide2"` passed in every gitnexus_* call: (YES/NO)
- [ ] No gitnexus_* invocation went through the bash tool: (YES/NO)
- [ ] `dry_run: true` used before applying rename: (YES/NO)
- [ ] `gitnexus_detect_changes` run after refactor: (YES/NO)