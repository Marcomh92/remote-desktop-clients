---
name: gitnexus-guide
description: |
  TRIGGER: User asks about GitNexus tools, graph schema, MCP resources, or workflows.

  ACTION: Load this skill for quick reference on all GitNexus MCP capabilities.

  Use for: Tool reference, schema lookup, workflow guidance.

  Out of scope: Actual code analysis tasks → use gitnexus-exploring, gitnexus-debugging, gitnexus-impact-analysis, gitnexus-pr-review, or gitnexus-refactoring.

  Examples:
  - user: "What GitNexus tools are available?" → show tools and resources table
  - user: "How do I query the knowledge graph?" → show cypher examples
  - user: "How do I rebuild the index?" → point to AGENTS.md escape hatch
---

# GitNexus Guide

Quick reference for all GitNexus MCP tools, resources, and the knowledge graph schema.

## MANDATORY RULES

1. Always pass `repo: "PreDecide2"` in every gitnexus_* tool call.
2. Never invoke gitnexus_* tools via the bash tool — they are MCP tools, not CLI commands.
3. The only bash exception is `gitnexus analyze --force` for rebuilding a stale index. See AGENTS.md "Index maintenance".

## Always Start Here

For any task involving code understanding, debugging, impact analysis, or refactoring:

1. Run `gitnexus_list_repos()` — discover indexed repositories
2. Run `gitnexus_query({query: "project overview", repo: "PreDecide2"})` — codebase overview + check index freshness
3. Load the relevant skill using the `use_skill` tool (see table below)
4. Follow the skill's workflow and checklist

> If the index is stale → run bash command `gitnexus analyze`.

## Skills

| Task                                         | Skill to read       |
| -------------------------------------------- | ------------------- |
| Understand architecture / "How does X work?" | `gitnexus-exploring`         |
| Blast radius / "What breaks if I change X?"  | `gitnexus-impact-analysis`   |
| Trace bugs / "Why is X failing?"             | `gitnexus-debugging`         |
| Rename / extract / split / refactor          | `gitnexus-refactoring`       |
| PR review / merge safety                     | `gitnexus-pr-review`         |
| Tools, resources, schema reference           | `gitnexus-guide` (this file) |

## Tools Reference

| Tool             | What it gives you                                                        | Requires `repo` |
| ---------------- | ------------------------------------------------------------------------ | --------------- |
| `query`          | Process-grouped code intelligence — execution flows related to a concept | yes             |
| `context`        | 360-degree symbol view — categorized refs, processes it participates in  | yes             |
| `impact`         | Symbol blast radius — what breaks at depth 1/2/3 with confidence         | yes             |
| `detect_changes` | Git-diff impact — what do your current changes affect                    | yes             |
| `rename`         | Multi-file coordinated rename with confidence-tagged edits               | optional        |
| `cypher`         | Raw graph queries (read schema first via `cypher`)                       | yes             |
| `list_repos`     | Discover indexed repos                                                   | no              |

## Resources Reference

| What you need          | MCP Tool                                                                                                                                                                                  |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Stats, staleness check | `gitnexus_list_repos()` or `gitnexus_query({query: "project overview", repo: "PreDecide2"})`                                                                                              |
| All functional areas   | `gitnexus_cypher({query: "MATCH (c:Community) RETURN c.heuristicLabel, c.symbolCount, c.cohesion ORDER BY c.symbolCount DESC", repo: "PreDecide2"})`                                      |
| Area members           | `gitnexus_cypher({query: "MATCH (f)-[:CodeRelation {type: 'MEMBER_OF'}]->(c:Community) WHERE c.heuristicLabel = 'Name' RETURN f.name, f.filePath", repo: "PreDecide2"})`                  |
| All execution flows    | `gitnexus_cypher({query: "MATCH (p:Process) RETURN p.heuristicLabel, p.stepCount, p.processType ORDER BY p.stepCount DESC", repo: "PreDecide2"})`                                         |
| Step-by-step trace     | `gitnexus_cypher({query: "MATCH (s)-[r:CodeRelation {type: 'STEP_IN_PROCESS'}]->(p:Process) WHERE p.heuristicLabel = 'Name' RETURN s.name, r.step ORDER BY r.step", repo: "PreDecide2"})` |
| Graph schema           | `gitnexus_cypher({query: "CALL db.schema.visualization()", repo: "PreDecide2"})`                                                                                                          |

## Graph Schema

**Nodes:** File, Function, Class, Interface, Method, Community, Process
**Edges (via CodeRelation.type):** CALLS, IMPORTS, EXTENDS, IMPLEMENTS, DEFINES, MEMBER_OF, STEP_IN_PROCESS

```cypher
MATCH (caller)-[:CodeRelation {type: 'CALLS'}]->(f:Function {name: "myFunc"})
RETURN caller.name, caller.filePath
```

## Compliance Checklist

- [ ] `repo: "PreDecide2"` passed in every gitnexus_* call: (YES/NO)
- [ ] No gitnexus_* invocation went through the bash tool: (YES/NO)
- [ ] Correct skill loaded for the task (exploring/debugging/impact/pr-review/refactoring): (YES/NO)