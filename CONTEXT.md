# Spring-to-Helidon Migration

This context defines how the project describes safe, incremental migration from Spring Boot to
Helidon MP.

## Language

**Assessment**:
A read-only inventory of migration-relevant source and build usage, including support levels and
the reasons a transformation is refused.
_Avoid_: Complete migration checklist, readiness certification

**Canonical migration**:
The default generic migration that contains only transformations whose supported boundaries are
designed to preserve behavior without application-specific policy.
_Avoid_: One-click migration, aggressive migration

**Bounded migration**:
An automated transformation for a precisely defined subset of a source construct, with unsupported
forms preserved and identified for review.
_Avoid_: Best-effort conversion

**Architecture-dependent migration**:
An opt-in or configurable transformation that requires an explicit target policy, runtime choice,
or application contract before source can be changed safely.
_Avoid_: Automatic migration

**Recipe family**:
An independently shippable group of bounded or architecture-dependent migrations for one Spring
feature area, tracked as a single roadmap issue with explicit supported and refused subsets.
_Avoid_: Catch-all migration issue, annotation-by-annotation backlog

**Refusal**:
The deliberate preservation of a source construct when the active recipe cannot establish that its
supported migration boundary is satisfied.
_Avoid_: Failure, skipped accidentally

**Spring residue**:
Spring source, configuration, build, or runtime behavior that remains after a migration phase and
must be resolved before Spring can be removed.
_Avoid_: Dead dependency

**Migration finalizer**:
An opt-in final phase that removes proven-unused Spring build/runtime elements and applies selected
Helidon packaging only after its readiness preconditions are satisfied.
_Avoid_: Cleanup recipe
