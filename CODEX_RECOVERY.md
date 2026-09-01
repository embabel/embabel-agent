# Codex Recovery Notes

Last updated: 2026-08-31T00:00:00-04:00

## Current Scope

- Agent process persistence and HITL restore work in `embabel-agent-api`.
- JDBC/cache backends are not framework implementations in this scope; JDBC is
  test-only E2E infrastructure.
- Do not run Maven from Codex. The user runs builds/tests.

## Implemented

- Backend-neutral persistence SPI:
  - `AgentProcessSnapshotStore`
  - `AgentProcessCheckpointPolicy.shouldStoreSnapshot(...)`
  - `SerializedAgentProcessSnapshot`
  - `StoredSnapshotMetadata`
- Blackboard persistence SPI:
  - `BlackboardEntrySerializer`
  - `supportsSerialization(...)`
  - `supportsDeserialization(...)`
  - serialization/deserialization context DTOs
- Internal persistence support:
  - snapshot factory/model
  - snapshot serializer/restorer
  - blackboard snapshot/restore support
  - persistent repository decorator
  - `WaitForCheckpointPolicy`
  - `LifecycleCheckpointPolicy`
- HITL JDBC E2E test:
  - profile-gated with `test`, `waitfor-persistent-jdbc`
  - test-only H2/JDBC snapshot store
  - uses `InMemoryAgentProcessRepository` as the current pluggable
    `runtimeRepository`
  - simulates runtime repository loss and restores from JDBC snapshot
  - stores terminal snapshot version `2`
- Docs:
  - persistence README with Mermaid class diagram
  - AsciiDoc persistence section

## Recent Review Decisions

- Use `runtimeRepository`, not `liveRepository`.
- Keep `runtimeRepository` as `AgentProcessRepository` so it can later be
  backed by in-memory or JDBC implementations.
- Use `shouldStoreSnapshot`, not `shouldCheckpoint`.
- Use serializer method names `supportsSerialization` and
  `supportsDeserialization`, not overloaded `supports(...)`.
- Use DTO terminology for serialized blackboard values.
- Completed/terminal snapshots must have `pendingAwaitableId == null`.
- ByteArray payload DTOs need explicit structural `equals`/`hashCode`.
- Avoid `Instant` equality forms that IntelliJ flags as identity-sensitive;
  current snapshot equality uses `compareTo(...) == 0`.
- Use `InternalAgentStateApi` as the combined opt-in marker for internal agent
  process and blackboard state access.
- Agent process snapshot factory KDoc should say it supports persistence
  boundaries while checkpoint policy decides invocation.

## Applied Review Cleanup

- Renamed `InternalAgentRuntimeStateApi` to `InternalAgentStateApi`.
- Changed factory error wording to
  `"Cannot create snapshot for WAITING agent process ..."`.
- Replaced "blind creates" in `InMemoryAgentProcessSnapshotStore` KDoc with
  "create-only saves".
- Refactored `InMemoryBlackboardSnapshotter` to avoid Pair `first`/`second` in
  `entriesByReference`; it now uses a named `EntrySnapshotReference` helper and
  comments explaining binding entry-id alias preservation.
- Added targeted comments to `AgentProcessSnapshotRestorer` explaining:
  - agent lookup by `snapshot.agentName`
  - blackboard restore before process construction
  - process implementation restorer selection by saved class name
- Reworded `JacksonAgentProcessStateSerializer` KDoc to describe the returned
  `SerializedAgentProcessSnapshot` directly instead of using "envelope".
- Documented that `InMemoryAgentProcessSnapshotStore` has no TTL, eviction, or
  size bound and is intended for tests/local development.

## Major Future TODOs

- Review `AgentProcessSnapshotStore` versioning API. `PersistentAgentProcessRepository`
  currently computes the next version internally, so application developers
  using the repository are not exposed to `nextVersion = (existing?.version ?: 0) + 1`.
  Still decide whether the store SPI should own optimistic version increment or
  expose a clearer compare-and-set contract for direct store callers and custom
  repository code.
- Replace restrictive direct `InMemoryBlackboard` coupling with a future
  `BlackboardSnapshotter` SPI. Current factory can only snapshot/restore
  `InMemoryBlackboard` because the public `Blackboard` contract does not expose
  ordered entries, bindings, hidden entries, protected keys, or binding-to-entry
  links.
- Add user-facing autoconfiguration after code review:
  - namespace: `embabel.agent.platform.persistence.*`
  - user provides `AgentProcessSnapshotStore`
  - optional user-provided `BlackboardEntrySerializer` beans
  - autoconfig decorates/configures effective `AgentProcessRepository`
  - add an E2E test proving property-based configuration
- Add a JDBC `AgentProcessRepository` implementation later for production cases
  requiring runtime state and snapshots in one database transaction.
- E2E currently documents that transactional runtime+snapshot semantics become
  possible only when the runtime `AgentProcessRepository` is also backed by a
  persistent store participating in the same transaction.
- Known limitation: current snapshot support is limited to `WAITING` and
  finished/terminal states. It does not snapshot after every action boundary.
  Action-level checkpointing would require a new policy plus factory/restore
  support for non-terminal, non-waiting states.

## Caution

- Do not make further edits while the user says they are building/reviewing
  unless they explicitly approve changes.
- Never run Maven from Codex in this thread.
