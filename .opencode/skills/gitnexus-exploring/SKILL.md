---
name: gitnexus-exploring
description: |
  TRIGGER: User asks how code works, wants architecture overview, or traces execution flows.

  ACTION: Load this skill and use gitnexus_query to find relevant processes and symbols.

  Use for: Understanding unfamiliar code, tracing call chains, exploring project structure.

  Out of scope: Debugging failures → use gitnexus-debugging.

  Examples:
  - user: "How does X work?" → query for processes, then context on key symbols
  - user: "Show me the auth flow" → trace STEP_IN_PROCESS for auth processes
  - user: "What calls this function?" → show incoming calls with context
---

# Exploring Codebases with GitNexus

## MANDATORY RULES

1. Always pass `repo: "PreDecide2"` in every gitnexus_* tool call.
2. Never invoke gitnexus_* tools via the bash tool — they are MCP tools, not CLI commands.

## When to Use

- "How does authentication work?"
- "What's the project structure?"
- "Show me the main components"
- "Where is the database logic?"
- Understanding code you haven't seen before

## Workflow

```
1. gitnexus_list_repos()                                                                   → Discover indexed repos
2. gitnexus_query({query: "project overview", repo: "PreDecide2"})                         → Codebase overview, check staleness
3. gitnexus_query({query: "<what you want to understand>", repo: "PreDecide2"})            → Find related execution flows
4. gitnexus_context({name: "<symbol>", repo: "PreDecide2"})                                → Deep dive on specific symbol
5. gitnexus_cypher({query: "MATCH (s)-[r:CodeRelation {type: 'STEP_IN_PROCESS'}]->(p:Process) WHERE p.heuristicLabel = 'Name' RETURN s.name, r.step ORDER BY r.step", repo: "PreDecide2"})  → Trace full execution flow
```

> If the index is stale → run bash command `gitnexus analyze`.

## Checklist

```
- [ ] gitnexus_list_repos() or gitnexus_query({query: "project overview", repo: "PreDecide2"}) for overview
- [ ] gitnexus_query({query: <concept>, repo: "PreDecide2"}) for the concept you want to understand
- [ ] Review returned processes (execution flows)
- [ ] gitnexus_context({name, repo: "PreDecide2"}) on key symbols for callers/callees
- [ ] gitnexus_cypher({query: <STEP_IN_PROCESS>, repo: "PreDecide2"}) for full execution traces
- [ ] Read source files for implementation details
```

## Resources

| What you need                  | MCP Tool / Query                                                                                                                                       |
|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Stats, staleness check         | `gitnexus_list_repos()` or `gitnexus_query({query: "project overview", repo: "PreDecide2"})`                                                             |
| All functional areas           | `gitnexus_cypher({query: "MATCH (c:Community) RETURN c.heuristicLabel, c.symbolCount, c.cohesion ORDER BY c.symbolCount DESC", repo: "PreDecide2"})`     |
| Area members                   | `gitnexus_cypher({query: "MATCH (f)-[:CodeRelation {type: 'MEMBER_OF'}]->(c:Community) WHERE c.heuristicLabel = 'Name' RETURN f.name, f.filePath", repo: "PreDecide2"})` |
| Step-by-step execution trace   | `gitnexus_cypher({query: "MATCH (s)-[r:CodeRelation {type: 'STEP_IN_PROCESS'}]->(p:Process) WHERE p.heuristicLabel = 'Name' RETURN s.name, r.step ORDER BY r.step", repo: "PreDecide2"})` |

## Tools

**gitnexus_query** — find execution flows related to a concept:

```
gitnexus_query({query: "payment processing", repo: "PreDecide2"})
→ Processes: CheckoutFlow, RefundFlow, WebhookHandler
→ Symbols grouped by flow with file locations
```

**gitnexus_context** — 360-degree view of a symbol:

```
gitnexus_context({name: "validateUser", repo: "PreDecide2"})
→ Incoming calls: loginHandler, apiMiddleware
→ Outgoing calls: checkToken, getUserById
→ Processes: LoginFlow (step 2/5), TokenRefresh (step 1/3)
```

## Example: "How does payment processing work?"

```
1. gitnexus_query({query: "project overview", repo: "PreDecide2"})
   → 918 symbols, 45 processes

2. gitnexus_query({query: "payment processing", repo: "PreDecide2"})
   → CheckoutFlow: processPayment → validateCard → chargeStripe
   → RefundFlow: initiateRefund → calculateRefund → processRefund

3. gitnexus_context({name: "processPayment", repo: "PreDecide2"})
   → Incoming: checkoutHandler, webhookHandler
   → Outgoing: validateCard, chargeStripe, saveTransaction

4. Read src/payments/processor.ts for implementation details
```

## Compliance Checklist

- [ ] `repo: "PreDecide2"` passed in every gitnexus_* call: (YES/NO)
- [ ] No gitnexus_* invocation went through the bash tool: (YES/NO)