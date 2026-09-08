---
name: gitnexus-impact-analysis
description: |
  TRIGGER: User asks "what breaks if I change this?" or needs pre-edit safety analysis.

  ACTION: Load this skill and run gitnexus_impact to map dependents and assess risk.

  Use for: Blast radius analysis, dependency mapping, pre-commit safety checks.

  Out of scope: Post-change verification → use gitnexus_detect_changes directly.

  Examples:
  - user: "Is it safe to change X?" → run impact analysis and report risk
  - user: "What depends on this?" → show upstream callers at d=1/d=2/d=3
  - user: "What will break?" → assess blast radius before editing
---

# Impact Analysis with GitNexus

## MANDATORY RULES

1. Always pass `repo: "PreDecide2"` in every gitnexus_* tool call.
2. Never invoke gitnexus_* tools via the bash tool — they are MCP tools, not CLI commands.
3. HIGH or CRITICAL risk must be reported to the user BEFORE editing.

## When to Use

- "Is it safe to change this function?"
- "What will break if I modify X?"
- "Show me the blast radius"
- "Who uses this code?"
- Before making non-trivial code changes
- Before committing — to understand what your changes affect

## Workflow

```
1. gitnexus_impact({target: "X", direction: "upstream", repo: "PreDecide2"})                                  → What depends on this
2. gitnexus_cypher({query: "MATCH (p:Process) RETURN p.heuristicLabel, p.stepCount ORDER BY p.stepCount DESC", repo: "PreDecide2"})  → Check affected execution flows
3. gitnexus_detect_changes({repo: "PreDecide2"})                                                              → Map current git changes to affected flows
4. Assess risk and report to user
```

> If the index is stale → run bash command `gitnexus analyze`.

## Checklist

```
- [ ] gitnexus_impact({target, direction: "upstream", repo: "PreDecide2"}) to find dependents
- [ ] Review d=1 items first (these WILL BREAK)
- [ ] Check high-confidence (>0.8) dependencies
- [ ] gitnexus_cypher({query: <processes query>, repo: "PreDecide2"}) to check affected execution flows
- [ ] gitnexus_detect_changes({repo: "PreDecide2"}) for pre-commit check
- [ ] Assess risk level and report to user
```

## Understanding Output

| Depth | Risk Level       | Meaning                  |
| ----- | ---------------- | ------------------------ |
| d=1   | **WILL BREAK**   | Direct callers/importers |
| d=2   | LIKELY AFFECTED  | Indirect dependencies    |
| d=3   | MAY NEED TESTING | Transitive effects       |

## Risk Assessment

| Affected                       | Risk     |
| ------------------------------ | -------- |
| <5 symbols, few processes      | LOW      |
| 5-15 symbols, 2-5 processes    | MEDIUM   |
| >15 symbols or many processes  | HIGH     |
| Critical path (auth, payments) | CRITICAL |

## Tools

**gitnexus_impact** — the primary tool for symbol blast radius:

```
gitnexus_impact({
  target: "validateUser",
  direction: "upstream",
  minConfidence: 0.8,
  maxDepth: 3,
  repo: "PreDecide2"
})

→ d=1 (WILL BREAK):
  - loginHandler (src/auth/login.ts:42) [CALLS, 100%]
  - apiMiddleware (src/api/middleware.ts:15) [CALLS, 100%]

→ d=2 (LIKELY AFFECTED):
  - authRouter (src/routes/auth.ts:22) [CALLS, 95%]
```

**gitnexus_detect_changes** — git-diff based impact analysis:

```
gitnexus_detect_changes({repo: "PreDecide2", scope: "staged"})

→ Changed: 5 symbols in 3 files
→ Affected: LoginFlow, TokenRefresh, APIMiddlewarePipeline
→ Risk: MEDIUM
```

## Example: "What breaks if I change validateUser?"

```
1. gitnexus_impact({target: "validateUser", direction: "upstream", repo: "PreDecide2"})
   → d=1: loginHandler, apiMiddleware (WILL BREAK)
   → d=2: authRouter, sessionManager (LIKELY AFFECTED)

2. gitnexus_cypher({query: "MATCH (p:Process) RETURN p.heuristicLabel, p.stepCount ORDER BY p.stepCount DESC", repo: "PreDecide2"})
   → LoginFlow and TokenRefresh touch validateUser

3. Risk: 2 direct callers, 2 processes = MEDIUM
```

## Compliance Checklist

- [ ] `repo: "PreDecide2"` passed in every gitnexus_* call: (YES/NO)
- [ ] No gitnexus_* invocation went through the bash tool: (YES/NO)
- [ ] Risk level reported to user before proceeding: (YES/NO)