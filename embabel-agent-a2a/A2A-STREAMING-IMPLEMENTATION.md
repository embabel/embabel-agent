# A2A Streaming Implementation Summary

## Overview

This document summarizes the implementation of comprehensive A2A (Agent-to-Agent) protocol streaming support in the embabel-agent-a2a module. The implementation adds full Server-Sent Events (SSE) streaming capabilities according to the A2A protocol specification v0.2.5.

## Key Features Implemented

1. **Streaming capability advertisement** - Agent card now declares streaming support
2. **Server-Sent Events (SSE)** - Full SSE implementation for real-time task updates
3. **Task state management** - Tracks task lifecycle and event history
4. **Task resubscription** - Allows clients to resume streaming after connection interruption
5. **Event replay** - Replays historical events when clients reconnect

## Files Created

### 1. TaskStateManager.kt (New)
**Purpose**: Manages task state and event history for streaming operations

**Key Features**:
- Tracks active and completed tasks with metadata (taskId, contextId, streamId)
- Records all streaming events per task for replay capability
- Automatically transitions tasks to terminal states (COMPLETED, FAILED, CANCELED)
- Supports cleanup of old completed tasks

**Key Methods**:
- `registerTask()` - Registers a new task with its stream
- `recordEvent()` - Records events for task history
- `getTaskEvents()` - Retrieves all events for a task
- `resubscribeToTask()` - Updates stream ID and enables reconnection

### 2. ResubscribeTaskRequest.kt (New)
**Purpose**: Custom JSON-RPC request class for task resubscription

**Note**: The A2A SDK v0.2.5 does not include a ResubscribeTaskRequest class, so this is a custom implementation following the JSON-RPC 2.0 format. It will be compatible with future SDK versions.

**Structure**:
```kotlin
data class ResubscribeTaskRequest(
    val jsonrpc: String = "2.0",
    val id: Any?,
    val method: String = "tasks/resubscribe",
    val params: TaskIdParams
)
```

## Files Modified

### 1. EmbabelServerGoalsAgentCardHandler.kt
**Change**: Enabled streaming capability in agent card

**Before**:
```kotlin
.streaming(false) // TODO are they planning to support streaming?
```

**After**:
```kotlin
.streaming(true)
```

### 2. A2AStreamingHandler.kt
**Changes**: Enhanced to support task tracking and event replay

**Key Updates**:
- Added `TaskStateManager` dependency injection
- Enhanced `createStream()` to register tasks with state manager
- Added `resubscribeToTask()` method for reconnection support
- Updated `sendStreamEvent()` to:
  - Record all events in task state manager
  - Wrap ALL events in `SendStreamingMessageResponse` (critical for protocol compliance)

**Protocol Compliance Fix**:
```kotlin
// All events must be wrapped in SendStreamingMessageResponse per A2A protocol
val response = SendStreamingMessageResponse(
    "2.0",
    streamId,
    event,
    null
)
```

Previously, only some events were wrapped, causing validation errors on the client side.

### 3. AutonomyA2ARequestHandler.kt
**Changes**: Added resubscription handling and improved task tracking

**Key Additions**:
- `handleCustomStreamingRequest()` - Handles streaming methods not in SDK
- `handleTaskResubscribe()` - Processes resubscription requests
- Enhanced `handleMessageStream()` to track task IDs throughout execution

**Important Note**: Uses `params.id` (not `params.taskId`) to access task identifier from TaskIdParams.

### 4. A2AEndpointRegistrar.kt
**Changes**: Added routing for tasks/resubscribe method

**Key Addition**:
```kotlin
ResubscribeTaskRequest.METHOD -> {
    // For resubscribe requests (custom implementation), handle separately
    if (agentCardHandler is AutonomyA2ARequestHandler) {
        agentCardHandler.handleCustomStreamingRequest(method, requestMap, objectMapper)
    } else {
        throw UnsupportedOperationException(...)
    }
}
```

## A2A Protocol Compliance

### JSON-RPC 2.0 Response Format

All SSE events MUST be wrapped in `SendStreamingMessageResponse` with this structure:

```json
{
  "jsonrpc": "2.0",
  "id": "streamId",
  "result": {
    /* The actual event (Message, Task, TaskStatusUpdateEvent, etc.) */
  }
}
```

**Critical**: Unwrapped events will cause Pydantic validation errors on the client side.

### Supported Methods

1. **message/stream** (Standard A2A)
   - Sends messages with streaming responses
   - Creates new task with event tracking
   - Returns SSE stream with real-time updates

2. **tasks/resubscribe** (Custom Implementation)
   - Resumes streaming for existing tasks
   - Replays all historical events
   - Closes stream automatically if task is already completed

### Event Types

All events are subclasses of `StreamingEventKind`:
- `Message` - User and agent messages
- `Task` - Complete task objects with results
- `TaskStatusUpdateEvent` - Status changes (WORKING, COMPLETED, FAILED)
- `TaskArtifactUpdateEvent` - Artifact updates during task execution

## Implementation Details

### Task Lifecycle

1. **Task Creation**:
   ```kotlin
   val taskId = ensureTaskId(params.message.taskId)
   val contextId = ensureContextId(params.message.contextId)
   streamingHandler.createStream(streamId, taskId, contextId)
   taskStateManager.registerTask(taskId, contextId, streamId)
   ```

2. **Event Recording**:
   ```kotlin
   streamingHandler.sendStreamEvent(streamId, event, taskId)
   // Internally records to taskStateManager
   ```

3. **Task Completion**:
   - Tasks automatically move to completed state when terminal state detected
   - Stream is closed after final event sent
   - Task history retained for resubscription

4. **Resubscription**:
   ```kotlin
   streamingHandler.resubscribeToTask(taskId, newStreamId)
   // Creates new stream, replays all events, closes if completed
   ```

### State Management

**Initial State**: `TaskState.WORKING`
- Note: SDK v0.2.5 doesn't include `TaskState.PENDING`

**Terminal States**:
- `TaskState.COMPLETED` - Task finished successfully
- `TaskState.FAILED` - Task encountered error
- `TaskState.CANCELED` - Task was cancelled

## SDK Limitations and Workarounds

### 1. No ResubscribeTaskRequest in SDK
**Workaround**: Created custom implementation following JSON-RPC 2.0 format

### 2. TaskIdParams Structure
**Issue**: Field name is `id`, not `taskId`
**Solution**: Access via `params.id`

### 3. No PENDING State
**Workaround**: Use `TaskState.WORKING` as initial state

### 4. Sealed Java Class Extension
**Issue**: Cannot extend `StreamingJSONRPCRequest` from Kotlin
**Solution**: Created standalone data class with same structure

## Testing Considerations

### Manual Testing Checklist

- ✓ Agent card shows `streaming: true`
- ✓ Client receives initial TaskStatusUpdateEvent
- ✓ No validation errors on any event type
- ✓ Messages are delivered in correct format
- ✓ Task results include artifacts
- ✓ Errors are properly propagated
- ✓ Stream closes after task completion

### Example Client Response

Successful streaming event:
```json
{
  "jsonrpc": "2.0",
  "id": "758f857a-d741-41a9-9ee8-84758b5609b3",
  "result": {
    "contextId": "96da8743-a313-4f98-8ba2-e3b024e41293",
    "final": false,
    "kind": "status-update",
    "status": {
      "message": {
        "contextId": "96da8743-a313-4f98-8ba2-e3b024e41293",
        "kind": "message",
        "messageId": "051b16f1-c2ba-4e37-9bd9-0c09d31ba04c",
        "parts": [{"kind": "text", "text": "Task started..."}],
        "role": "agent"
      },
      "state": "working",
      "timestamp": "2025-11-20T12:45:03.038917"
    },
    "taskId": "7fe57e33-6f88-4db8-9c16-e14e6f067098"
  }
}
```

## Known Issues and Future Improvements

### Known Issues
None currently identified.

### Future Improvements
1. Implement `tasks/cancel` handler (currently TODO)
2. Implement `tasks/get` handler (currently TODO)
3. Add task state persistence for server restarts
4. Implement task state transition history tracking
5. Add configurable task retention policy
6. Consider implementing push notifications capability

## References

- [A2A Protocol Specification](https://a2a-protocol.org/latest/specification/)
- A2A Java SDK v0.2.5 (io.github.a2asdk:a2a-java-sdk-spec)
- JSON-RPC 2.0 Specification
- W3C Server-Sent Events Specification

## Commit History

1. **Initial Implementation** (commit 15731e3)
   - Added all core streaming functionality
   - Created TaskStateManager and ResubscribeTaskRequest
   - Fixed compilation errors and SDK compatibility issues

2. **SSE Protocol Compliance Fix** (commit 130909e)
   - Removed invalid "connected" event

3. **Event Wrapping Fix** (commit e6846a1)
   - Wrapped all events in SendStreamingMessageResponse
   - Fixed validation errors for Message and Task events

## Contributors

Implementation completed via automated assistant with human oversight and testing.
