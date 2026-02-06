# Chat Storage Architecture

## Current Architecture

```
   ┌─────────────────────┐
   │     agent-api       │
   │                     │
   │ User                │ ← identity interface
   │ StoredMessage       │ ← storage contract
   │ MessageRole         │
   │ Message (sealed)    │ ← rich implementation
   │ Conversation        │
   │ ConversationFactory │
   └──────────┬──────────┘
              │
              ▼
   ┌─────────────────────┐
   │     chat-store      │  (depends on agent-api)
   │                     │
   │ StoredUser          │ ← User + Drivine annotations
   │ StoredConversation  │ ← Neo4j implementation
   │ ChatSessionRepository│
   │ Spring autoconfigure│
   └─────────────────────┘
```

## Type Hierarchy

### User Identity
- `User` (agent-api) - base identity interface with id, displayName, username, email
- `StoredUser` (chat-store) - extends User with Drivine `@NodeFragment` for persistence
- `SimpleStoredUser` (chat-store) - ready-to-use implementation

### Messages
- `StoredMessage` (agent-api) - storage contract interface
- `Message` (agent-api) - sealed class implementing StoredMessage
- `MessageData` (chat-store) - Drivine node for persistence
- `SimpleStoredMessage` (chat-store) - GraphView with author/recipient relationships

## Benefits

- Simpler dependency graph
- agent-api is persistence-agnostic
- chat-store adds Drivine-specific annotations
- No type conversion needed between layers