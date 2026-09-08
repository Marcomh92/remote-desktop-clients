---
name: gitnexus-pr-review
description: |
  TRIGGER: User wants to review a PR, assess merge risk, or check test coverage.

  ACTION: Load this skill and analyze PR diff with gitnexus_detect_changes and impact.

  Use for: PR review, merge safety analysis, missing test detection.

  Out of scope: Writing code fixes for PR issues, general code review without git context.

  Examples:
  - user: "Review this PR" → map diff to affected flows, assess risk
  - user: "Is it safe to merge?" → run impact analysis on changed symbols
  - user: "What does PR #42 change?" → detect_changes and summarize blast radius
---

# PR Review with GitNexus

## MANDATORY RULES

1. Always pass `repo: "PreDecide2"` in every gitnexus_* tool call.
2. Never invoke gitnexus_* tools via the bash tool — they are MCP tools, not CLI commands.
3. HIGH or CRITICAL risk must be flagged in the review.

## When to Use

- "Review this PR"
- "What does PR #42 change?"
- "Is this safe to merge?"
- "What's the blast radius of this PR?"
- "Are there missing tests for this PR?"
- Reviewing someone else's code changes before merge

## Workflow

```
1. gh pr diff <number>                                                                                       → Get the raw diff (bash OK; not a gitnexus tool)
2. gitnexus_detect_changes({scope: "compare", base_ref: "main", repo: "PreDecide2"})                         → Map diff to affected flows
3. For each changed symbol:
   gitnexus_impact({target: "<symbol>", direction: "upstream", repo: "PreDecide2"})                          → Blast radius per change
4. gitnexus_context({name: "<key symbol>", repo: "PreDecide2"})                                              → Understand callers/callees
5. gitnexus_cypher({query: "MATCH (p:Process) RETURN p.heuristicLabel, p.stepCount ORDER BY p.stepCount DESC", repo: "PreDecide2"})  → Check affected execution flows
6. Summarize findings with risk assessment
```

> If the index is stale → run bash command `gitnexus analyze`.

## Checklist

```
- [ ] Fetch PR diff (gh pr diff or git diff base...head) — bash OK
- [ ] gitnexus_detect_changes({scope: "compare", base_ref, repo: "PreDecide2"}) to map changes to affected execution flows
- [ ] gitnexus_impact on each non-trivial changed symbol
- [ ] Review d=1 items (WILL BREAK) — are callers updated?
- [ ] gitnexus_context({name, repo: "PreDecide2"}) on key changed symbols to understand full picture
- [ ] gitnexus_cypher with STEP_IN_PROCESS query to trace affected execution flows
- [ ] Check if affected processes have test coverage
- [ ] Assess overall risk level
- [ ] Write review summary with findings
```

## Review Dimensions

| Dimension            | How GitNexus Helps                                                                |
| -------------------- | --------------------------------------------------------------------------------- |
| **Correctness**      | `context` shows callers — are they all compatible with the change?                |
| **Blast radius**     | `impact` shows d=1/d=2/d=3 dependents — anything missed?                         |
| **Completeness**     | `detect_changes` shows all affected flows — are they all handled?                 |
| **Test coverage**    | `impact({includeTests: true})` shows which tests touch changed code               |
| **Breaking changes** | d=1 upstream items that aren't updated in the PR = potential breakage            |

## Risk Assessment

| Signal                                          | Risk                       |
| ----------------------------------------------- | -------------------------- |
| Changes touch <3 symbols, 0-1 processes         | LOW                        |
| Changes touch 3-10 symbols, 2-5 processes       | MEDIUM                     |
| Changes touch >10 symbols or many processes     | HIGH                       |
| Changes touch auth, payments, or data integrity | CRITICAL                   |
| d=1 callers exist outside the PR diff           | Potential breakage — flag  |

## Tools

**gitnexus_detect_changes** — map PR diff to affected execution flows:

```
gitnexus_detect_changes({scope: "compare", base_ref: "main", repo: "PreDecide2"})

→ Changed: 8 symbols in 4 files
→ Affected processes: CheckoutFlow, RefundFlow, WebhookHandler
→ Risk: MEDIUM
```

**gitnexus_impact** — blast radius per changed symbol:

```
gitnexus_impact({target: "validatePayment", direction: "upstream", repo: "PreDecide2"})

→ d=1 (WILL BREAK):
  - processCheckout (src/checkout.ts:42) [CALLS, 100%]
  - webhookHandler (src/webhooks.ts:15) [CALLS, 100%]

→ d=2 (LIKELY AFFECTED):
  - checkoutRouter (src/routes/checkout.ts:22) [CALLS, 95%]
```

**gitnexus_impact with tests** — check test coverage:

```
gitnexus_impact({target: "validatePayment", direction: "upstream", includeTests: true, repo: "PreDecide2"})

→ Tests that cover this symbol:
  - validatePayment.test.ts [direct]
  - checkout.integration.test.ts [via processCheckout]
```

**gitnexus_context** — understand a changed symbol's role:

```
gitnexus_context({name: "validatePayment", repo: "PreDecide2"})

→ Incoming calls: processCheckout, webhookHandler
→ Outgoing calls: verifyCard, fetchRates
→ Processes: CheckoutFlow (step 3/7), RefundFlow (step 1/5)
```

## Example: "Review PR #42"

```
1. gh pr diff 42 > /tmp/pr42.diff  (bash OK — not a gitnexus tool)
   → 4 files changed: payments.ts, checkout.ts, types.ts, utils.ts

2. gitnexus_detect_changes({scope: "compare", base_ref: "main", repo: "PreDecide2"})
   → Changed symbols: validatePayment, PaymentInput, formatAmount
   → Affected processes: CheckoutFlow, RefundFlow
   → Risk: MEDIUM

3. gitnexus_impact({target: "validatePayment", direction: "upstream", repo: "PreDecide2"})
   → d=1: processCheckout, webhookHandler (WILL BREAK)
   → webhookHandler is NOT in the PR diff — potential breakage!

4. gitnexus_impact({target: "PaymentInput", direction: "upstream", repo: "PreDecide2"})
   → d=1: validatePayment (in PR), createPayment (NOT in PR)
   → createPayment uses the old PaymentInput shape — breaking change!

5. gitnexus_context({name: "formatAmount", repo: "PreDecide2"})
   → Called by 12 functions — but change is backwards-compatible (added optional param)

6. Review summary:
   - MEDIUM risk — 3 changed symbols affect 2 execution flows
   - BUG: webhookHandler calls validatePayment but isn't updated for new signature
   - BUG: createPayment depends on PaymentInput type which changed
   - OK: formatAmount change is backwards-compatible
   - Tests: checkout.test.ts covers processCheckout path, but no webhook test
```

## Review Output Format

Structure your review as:

```markdown
## PR Review: <title>

**Risk: LOW / MEDIUM / HIGH / CRITICAL**

### Changes Summary
- <N> symbols changed across <M> files
- <P> execution flows affected

### Findings
1. **[severity]** Description of finding
   - Evidence from GitNexus tools
   - Affected callers/flows

### Missing Coverage
- Callers not updated in PR: ...
- Untested flows: ...

### Recommendation
APPROVE / REQUEST CHANGES / NEEDS DISCUSSION
```

## Compliance Checklist

- [ ] `repo: "PreDecide2"` passed in every gitnexus_* call: (YES/NO)
- [ ] No gitnexus_* invocation went through the bash tool: (YES/NO)
- [ ] Risk level reported in review summary: (YES/NO)