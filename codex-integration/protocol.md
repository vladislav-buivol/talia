# Talia chat WebSocket protocol

## Connection and message format

Start Codex App Server at the URL configured by `talia.codex.url`, and start Talia with the `codex` Spring profile. Connect a WebSocket client such as Postman to:

```text
ws://localhost:8080/ws/chat
```

Send each request as a JSON text frame with a `type` and an `action`. Use `GET` to read data, `POST` to perform chat actions, and `DELETE` to permanently delete a saved dialog. The server validates that the type matches the action. These types are fields in the WebSocket message.

In Java, construct the request for the specific operation. The request object determines its type and action when serialized:

```java
ChatRequest dialogs = new ChatRequest.GetDialogsRequest(10);
ChatRequest reader = new ChatRequest.ConnectAsReaderRequest();
ChatRequest close = new ChatRequest.CloseChatRequest();
ChatRequest delete = new ChatRequest.DeleteChatRequest(threadId);
```

Responses and events are JSON text frames with:

- `type` — the response or event name.
- `availableAction` — actions available to this client in its current state.
- `data` — operation data when applicable.

Other participants can receive events while a request is in progress. Each event includes the recipient's current `availableAction`. Requests do not carry a correlation ID. The example IDs below are placeholders.

Immediately after connecting, the server sends:

```json
{"type":"connected","availableAction":["startNewDialog","getDialogs","continueDialog"]}
```

## Typical flows

**New dialog** — connect, send `startNewDialog`, receive `session.joined`, then use `sendMessage`. The creator is the writer.

**Existing dialog** — send `getDialogs`, select an ID with `continueDialog`, then send `connectAsReader` or `connectAsWriter`. A writer can join when that dialog has no writer. A dialog ID may also be passed directly to either connect action.

**Shared dialog** — one writer and multiple readers can join the same thread. All receive new user messages and streamed assistant text. A reader who joins during a turn receives subsequent deltas. A writer can transfer access to an attached reader. When the writer leaves or disconnects, the earliest remaining client becomes the writer.

`availableAction` is state-dependent. A writer cannot start another turn, change the model, or finish the chat while a turn is active. Reader and writer availability returned in a dialog list is a snapshot and can change before a client joins.

## Actions

The responses below illustrate one possible state. The actual action list reflects the current client and session.

### startNewDialog

Create a thread and join as writer. `model` is optional. If omitted, Codex chooses its default. The current adapter accepts `gpt-5.6-luna` and `gpt-5.6-sol`.

```json
{"type":"POST","action":"startNewDialog","model":"gpt-5.6-luna"}
```

```json
{"type":"session.joined","availableAction":["getHistory","closeChat","sendMessage","changeModel","finishChat","deleteChat","closeChatAndDelete","finishChatAndDelete"],"data":{"threadId":"thread-1","clientId":"client-a","writerId":"client-a","turnId":null}}
```

### getDialogs

List recent dialogs. `limit` is required and must be an integer between 1 and 100. Pass the returned `nextCursor` to fetch the next page.

```json
{"type":"GET","action":"getDialogs","limit":2}
```

```json
{"type":"dialogs","availableAction":["startNewDialog","getDialogs","continueDialog"],"data":{"dialogs":[{"threadId":"thread-1","name":"Example","preview":"Hello","availableAction":["connectAsReader","connectAsWriter","deleteChat"]}],"nextCursor":"next-page"}}
```

```json
{"type":"GET","action":"getDialogs","limit":2,"cursor":"next-page"}
```

A dialog with an active writer lists only `connectAsReader`. A null dialog name or preview can be omitted. The last page omits `nextCursor`.

### continueDialog

Select an existing dialog by ID. This returns the available connection roles.

```json
{"type":"POST","action":"continueDialog","threadId":"thread-1"}
```

```json
{"type":"dialog.selected","availableAction":["startNewDialog","getDialogs","continueDialog","connectAsReader","connectAsWriter","deleteChat"],"data":{"threadId":"thread-1"}}
```

### connectAsReader

Join the selected dialog without write access. Alternatively include `threadId` in the request.

```json
{"type":"POST","action":"connectAsReader"}
```

```json
{"type":"session.joined","availableAction":["getHistory","closeChat"],"data":{"threadId":"thread-1","clientId":"client-b","writerId":"client-a","turnId":null}}
```

If no writer is present, the reader's available actions include `connectAsWriter`. Existing participants receive `session.updated` with `joinedClientId`.

### connectAsWriter

Join a selected dialog as writer, or claim the vacant writer role after joining as a reader.

```json
{"type":"POST","action":"connectAsWriter","threadId":"thread-1"}
```

```json
{"type":"session.joined","availableAction":["getHistory","closeChat","sendMessage","changeModel","finishChat","deleteChat","closeChatAndDelete","finishChatAndDelete"],"data":{"threadId":"thread-1","clientId":"client-a","writerId":"client-a","turnId":null}}
```

When an attached reader claims a vacant writer role, participants receive `writer.changed` instead. A writer slot already occupied produces `writer_unavailable`.

### getHistory

Read the most recent messages in chronological order. `limit` is optional, defaults to 50, and must be between 1 and 100. Both readers and writers can request history.

```json
{"type":"GET","action":"getHistory","limit":10}
```

```json
{"type":"history","availableAction":["getHistory","closeChat"],"data":{"threadId":"thread-1","messages":[{"role":"USER","text":"Hello"},{"role":"ASSISTANT","text":"Hi!"}]}}
```

### sendMessage

The writer sends a nonblank message. The resulting `user.text`, `assistant.text.delta`, and terminal events are broadcast to participants. The server-generated `turnId` links events for this turn.

```json
{"type":"POST","action":"sendMessage","text":"Hello"}
```

```json
{"type":"user.text","availableAction":["getHistory","closeChat"],"data":{"threadId":"thread-1","turnId":"talia-turn-id","text":"Hello"}}
{"type":"assistant.text.delta","availableAction":["getHistory","closeChat"],"data":{"threadId":"thread-1","turnId":"talia-turn-id","text":"Hi"}}
{"type":"assistant.text.delta","availableAction":["getHistory","closeChat"],"data":{"threadId":"thread-1","turnId":"talia-turn-id","text":"!"}}
{"type":"assistant.text.done","availableAction":["getHistory","closeChat","sendMessage","changeModel","finishChat","deleteChat","closeChatAndDelete","finishChatAndDelete"],"data":{"threadId":"thread-1","turnId":"talia-turn-id","codexTurnId":"codex-turn-id","status":"completed","errorMessage":null}}
```

The terminal status comes from Codex. A turn can complete with a failed or interrupted status. A request or connection failure produces an `error` event with code `turn_failed`.

### changeModel

Select the model for subsequent turns. This action is available to the writer while idle.

```json
{"type":"POST","action":"changeModel","model":"gpt-5.6-sol"}
```

```json
{"type":"model.changed","availableAction":["getHistory","closeChat","sendMessage","changeModel","finishChat","deleteChat","closeChatAndDelete","finishChatAndDelete"],"data":{"threadId":"thread-1","model":"gpt-5.6-sol"}}
```

### transferWriter

The writer transfers access to a connected client. A participant's `clientId` appears in its `session.joined` event and in the `session.updated` event sent to existing participants when it joins.

```json
{"type":"POST","action":"transferWriter","clientId":"client-b"}
```

```json
{"type":"writer.changed","availableAction":["getHistory","closeChat","sendMessage","changeModel","finishChat","deleteChat","closeChatAndDelete","finishChatAndDelete","transferWriter"],"data":{"threadId":"thread-1","writerId":"client-b"}}
```

The response above is for the new writer. Other participants receive their own action lists. Transfer can occur during a turn, which continues streaming to participants.

### closeChat

Leave the shared session and return this client to the menu while keeping the WebSocket connected.

```json
{"type":"POST","action":"closeChat"}
```

```json
{"type":"chat.closed","availableAction":["startNewDialog","getDialogs","continueDialog"],"data":{"threadId":"thread-1"}}
```

If the writer leaves, remaining participants receive `writer.changed`. If a reader leaves, they receive `session.updated` with `leftClientId`.

### finishChat

The idle writer ends the shared session. All attached participants return to the menu. The stored conversation remains available.

```json
{"type":"POST","action":"finishChat"}
```

```json
{"type":"chat.finished","availableAction":["startNewDialog","getDialogs","continueDialog"],"data":{"threadId":"thread-1"}}
```

### deleteChat

Permanently delete a saved dialog. The idle writer can delete the current dialog. In the menu, supply a `threadId` or select the dialog first with `continueDialog`. Menu deletion is available for dialogs without an open Talia session.

```json
{"type":"DELETE","action":"deleteChat","threadId":"thread-1"}
```

Codex deletes the dialog history and any spawned descendant threads. On success Talia sends `chat.deleted`. If participants are attached, they all return to the menu.

```json
{"type":"chat.deleted","availableAction":["startNewDialog","getDialogs","continueDialog"],"data":{"threadId":"thread-1"}}
```

### closeChatAndDelete

The idle writer permanently deletes the current dialog and leaves the chat. Deleting the shared dialog returns all participants to the menu with `chat.deleted`.

```json
{"type":"DELETE","action":"closeChatAndDelete"}
```

### finishChatAndDelete

The idle writer permanently deletes the current dialog and ends the shared session. All participants receive `chat.deleted` and return to the menu.

```json
{"type":"DELETE","action":"finishChatAndDelete"}
```

## Errors

Errors use the same response envelope. For example, a reader attempting to send a message receives:

```json
{"type":"error","availableAction":["getHistory","closeChat"],"data":{"threadId":"thread-1","code":"read_only","message":"Only the current writer can perform this action"}}
```

Other error codes include `invalid_request`, `invalid_thread`, `invalid_limit`, `invalid_text`, `invalid_model`, `action_unavailable`, `writer_unavailable`, `unknown_client`, `turn_in_progress`, `history_failed`, `turn_failed`, `delete_failed`, and `codex_unavailable`. Menu-level errors contain a code and message without a thread ID.
