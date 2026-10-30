# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT-MAP.md`** at the repo root: it points at one `CONTEXT.md` per context. Read each one relevant to the topic. This repo is multi-context — a Gradle multi-module plugin where each `contexts/meowo-*` module is one bounded context.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in. Context-scoped decisions live in `contexts/meowo-<name>/docs/adr/`.
- **`contexts/meowo-<name>/README.md`**: the per-context business rules. These are the closest thing this repo has to a context description today, and they are written in Chinese.
- **`docs/minimax/README.md`**: the repo's own development knowledge base — architecture red lines, lifecycle and threading model, data invariants, and per-file change recipes. Read it before changing anything non-trivial.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

This repo is multi-context. The layout is `contexts/`, not `src/`:

```
/
├── CONTEXT-MAP.md                      ← maps each context to its CONTEXT.md
├── docs/adr/                           ← system-wide decisions
├── api/                                ← cross-context contracts (EconomyAPI, UIAPI, FocusAPI, …)
└── contexts/
    ├── meowo-economy/
    │   ├── CONTEXT.md
    │   ├── README.md                   ← business rules (already exists)
    │   └── docs/adr/                   ← context-specific decisions
    └── meowo-chat/
        ├── CONTEXT.md
        └── README.md
```

A context is one `contexts/meowo-*` Gradle module. The module list lives in `settings.gradle.kts` — that is the single source of truth for which contexts exist, and `verifyArchitecture` derives the same list from it to enforce that no context imports another context's packages.

Within a context, code is layered as `domain` / `application` / `infrastructure` / `interfaces` when it needs the separation; smaller contexts keep a flat package. `domain` is the pure layer — it must not import Bukkit, Paper, TabooLib, Adventure, `com.meowo.platform`, or Gson. `verifyArchitecture` enforces this.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_
