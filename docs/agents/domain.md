# Domain docs

How engineering skills should consume this repository’s domain documentation.

## Before exploring, read these

- `CONTEXT.md` at the repository root.
- `docs/adr/` for decisions affecting the area being changed.

If these files do not exist, proceed silently. `/domain-modeling`, normally reached through
`/grill-with-docs`, creates them when terminology or durable decisions are resolved.

## Layout

This is a single-context repository:

```
/
├── CONTEXT.md
├── docs/adr/
└── src/
```

## Use the glossary’s vocabulary

Use terms as defined in `CONTEXT.md` in issues, specifications, tests, and implementation.
If a required concept is missing, reconsider whether new terminology is necessary or record
the gap for `/domain-modeling`.

## Flag ADR conflicts

If proposed work contradicts an existing ADR, identify that conflict explicitly instead of
silently overriding the decision.
