---
name: gitnexus-debugging
description: |
  TRIGGER: User is debugging a bug, tracing an error, or asking why something fails.

  ACTION: Load this skill and trace execution flows to find root cause.

  Use for: Error tracing, bug root cause analysis, investigating unexpected behavior.

  Out of scope: General architecture questions → use gitnexus-exploring.

  Examples:
  - user: "Why is X failing?" → query error text, context on suspect symbol
  - user: "Where does this error come from?" → trace call chain to throw site
  - user: "Trace this bug" → follow execution flow and identify root cause
---

# Debugging with GitNexus

## MANDATORY RULES

1. Always pass `repo: "PreDecide2"` in every gitnexus_* tool call.
2. Never invoke gitnexus_* tools via the bash tool — they are MCP tools, not CLI commands.

## When to Use

- "Why is this function failing?"
- "Trace where this error comes from"
- "Who calls this method?"
- "This endpoint returns 500"
- Investigating bugs, errors, or unexpected behavior

## Workflow

```
1. gitnexus_query({query: "<error or symptom>", repo: "PreDecide2"})            → Find related execution flows
2. gitnexus_context({name: "<suspect>", repo: "PreDecide2"})                    → See callers/callees/processes
3. gitnexus_cypher({query: "MATCH (s)-[r:CodeRelation {type: 'STEP_IN_PROCESS'}]->(p:Process) WHERE p.heuristicLabel = 'Name' RETURN s.name, r.step ORDER BY r.step", repo: "PreDecide2"})  → Trace execution flow
4. gitnexus_cypher({query: "MATCH path...", repo: "PreDecide2"})                → Custom traces if needed
```

> If the index is stale → run bash command `gitnexus analyze`.

## Checklist

```
- [ ] Understand the symptom (error message, unexpected behavior)
- [ ] gitnexus_query({query: <symptom>, repo: "PreDecide2"}) for error text or related code
- [ ] Identify the suspect function from returned processes
- [ ] gitnexus_context({name: <suspect>, repo: "PreDecide2"}) to see callers and callees
- [ ] gitnexus_cypher({query: <STEP_IN_PROCESS>, repo: "PreDecide2"}) for execution traces if applicable
- [ ] gitnexus_cypher({query: <custom>, repo: "PreDecide2"}) for custom call chain traces if needed
- [ ] Read source files to confirm root cause
```

## Debugging Patterns

| Symptom              | GitNexus Approach (tools)                                          |
| -------------------- | ------------------------------------------------------------------ |
| Error message        | `gitnexus_query` for error text → `context` on throw sites         |
| Wrong return value   | `context` on the function → trace callees for data flow            |
| Intermittent failure | `context` → look for external calls, async deps                    |
| Performance issue    | `context` → find symbols with many callers (hot paths)             |
| Recent regression    | `detect_changes` to see what your changes affect                   |

## Tools

**gitnexus_query** — find code related to error:

```
gitnexus_query({query: "payment validation error", repo: "PreDecide2"})
→ Processes: CheckoutFlow, ErrorHandling
→ Symbols: validatePayment, handlePaymentError, PaymentException
```

**gitnexus_context** — full context for a suspect:

```
gitnexus_context({name: "validatePayment", repo: "PreDecide2"})
→ Incoming calls: processCheckout, webhookHandler
→ Outgoing calls: verifyCard, fetchRates (external API!)
→ Processes: CheckoutFlow (step 3/7)
```

**gitnexus_cypher** — custom call chain traces:

```cypher
MATCH path = (a)-[:CodeRelation {type: 'CALLS'}*1..2]->(b:Function {name: "validatePayment"})
RETURN [n IN nodes(path) | n.name] AS chain
```

## Example: "Payment endpoint returns 500 intermittently"

```
1. gitnexus_query({query: "payment error handling", repo: "PreDecide2"})
   → Processes: CheckoutFlow, ErrorHandling
   → Symbols: validatePayment, handlePaymentError

2. gitnexus_context({name: "validatePayment", repo: "PreDecide2"})
   → Outgoing calls: verifyCard, fetchRates (external API!)

3. gitnexus_cypher({query: "MATCH (s)-[r:CodeRelation {type: 'STEP_IN_PROCESS'}]->(p:Process) WHERE p.heuristicLabel = 'CheckoutFlow' RETURN s.name, r.step ORDER BY r.step", repo: "PreDecide2"})
   → Step 3: validatePayment → calls fetchRates (external)

4. Root cause: fetchRates calls external API without proper timeout
```

## Compliance Checklist

- [ ] `repo: "PreDecide2"` passed in every gitnexus_* call: (YES/NO)
- [ ] No gitnexus_* invocation went through the bash tool: (YES/NO)