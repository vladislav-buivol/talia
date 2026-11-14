# Codex WebSocket communication

This document describes the Codex WebSocket implementation in `src/main/java/com/chatbot/talia/ws/adapter/codex`, including how outgoing requests are matched to incoming replies.

For the client-facing message flow and examples for every action, see [Talia chat WebSocket protocol](protocol.md).

## Chat UI

Start Codex App Server and Talia with the `codex` profile. Then, in a separate
terminal, run:

```powershell
cd frontend
npm install
npm run dev
```

Run `npm install` on the first start or after dependencies change. On Windows,
you can instead run `codex-integration\start-ui.bat` from the project root.
Open the address shown by Vite (normally http://localhost:5173), check the
WebSocket URL (default `ws://localhost:8080/ws/chat`), and click **Connect to
Talia**. See the [frontend README](../frontend/README.md) for prerequisites.

The UI lists and opens chats, shows history and streaming messages, and offers
reader and writer actions according to the server's current response. It also
supports model selection, writer transfer, and chat closing or deletion. The
model dropdown shows models supported by the backend's `CodexModel` enum.

## The pieces

| Class | Responsibility in the current code |
| --- | --- |
| [`CodexClient`](../src/main/java/com/chatbot/talia/ws/adapter/codex/client/CodexClient.java) | Opens a session and provides conversation, history, and turn operations. |
| [`CodexWebSocketClient`](../src/main/java/com/chatbot/talia/ws/adapter/codex/client/CodexWebSocketClient.java) | Owns the RPC session, runs the handshake, and registers typed requests before sending them. |
| [`CodexRpcSchema`](../src/main/java/com/chatbot/talia/ws/adapter/codex/client/CodexRpcSchema.java) | Reads the RPC method catalog from a Codex-generated schema file. |
| [`CodexWebSocketConnection`](../src/main/java/com/chatbot/talia/ws/adapter/codex/client/CodexWebSocketConnection.java) | Opens the outbound WebSocket, queues text sends, sends pings, and owns the sender executor and HTTP client. |
| [`SendTask`](../src/main/java/com/chatbot/talia/ws/adapter/codex/client/SendTask.java) | Sends one text message on the executor and completes a future when the send finishes or fails. |
| [`CodexWebSocketListener`](../src/main/java/com/chatbot/talia/ws/adapter/codex/listener/CodexWebSocketListener.java) | Assembles WebSocket text fragments for the dispatcher and forwards close and error events to the connection. |
| [`CodexMessageDispatcher`](../src/main/java/com/chatbot/talia/ws/adapter/codex/client/CodexMessageDispatcher.java) | Tracks pending requests for one connection, completes their futures, and routes other messages through the handler registry. |
| [`CodexMessageCodec`](../src/main/java/com/chatbot/talia/ws/adapter/codex/protocol/CodexMessageCodec.java) | Uses Jackson's `ObjectMapper` to encode outgoing messages and decode incoming notifications, server requests, and responses. |
| [`JsonRpcRequest<P>` and `CodexRequest<P>`](../src/main/java/com/chatbot/talia/ws/adapter/codex/protocol/request/JsonRpcRequest.java) | Define the `id`, `method`, and typed `params` exposed by a request. `CodexRequest` extends that interface. |
| [`InitializeRequest`](../src/main/java/com/chatbot/talia/ws/adapter/codex/protocol/request/InitializeRequest.java) and [`CodexMethod`](../src/main/java/com/chatbot/talia/ws/adapter/codex/protocol/request/CodexMethod.java) | Represent the initialization request and its protocol method name. |
| [`CodexChatWebSocketConfiguration`](../src/main/java/com/chatbot/talia/ws/adapter/codex/config/CodexChatWebSocketConfiguration.java) | Under the `codex` profile, registers the incoming `/ws/chat` handler. |
| [`ChatWebSocketHandler`](../src/main/java/com/chatbot/talia/ws/core/websocket/ChatWebSocketHandler.java) | Reads Talia chat commands and writes responses and streaming events to each socket. |
| [`ChatService`](../src/main/java/com/chatbot/talia/ws/core/ChatService.java) and [`ChatSession`](../src/main/java/com/chatbot/talia/ws/core/ChatSession.java) | Manage the menu, chat membership, writer ownership, and available actions. |
| [`CodexChatBackend`](../src/main/java/com/chatbot/talia/ws/adapter/codex/CodexChatBackend.java) | Implements `ChatBackend` with `CodexClient` and manages connections to Codex. |

With Talia running under the `codex` profile, connect Postman to `ws://localhost:8080/ws/chat`. Talia returns:

```json
{"type":"connected","availableAction":["startNewDialog","getDialogs","continueDialog","getModels"]}
```

Send each command as a WebSocket text frame. Responses carry the event `type`, the client's current `availableAction` list, and optional `data`.

| Client JSON | Behavior |
| --- | --- |
| `{"type":"POST","action":"startNewDialog"}` | Creates a Codex thread and joins as its writer. The optional `model` field selects a model. |
| `{"type":"GET","action":"getDialogs","limit":10}` | Lists recent dialogs in `data.dialogs` with their IDs, names, previews, and available roles. Pass `data.nextCursor` as `cursor` for the next page. |
| `{"type":"GET","action":"getModels"}` | Lists model IDs supported by the configured backend. |
| `{"type":"POST","action":"continueDialog","threadId":"<id>"}` | Selects a dialog and returns the available roles. |
| `{"type":"POST","action":"connectAsReader"}` or `{"type":"POST","action":"connectAsWriter"}` | Joins the selected dialog. Either command also accepts an explicit `threadId`. The writer role requires a vacant writer slot. |
| `{"type":"GET","action":"getHistory","limit":20}` | Returns recent messages in chronological order as role and text pairs. Limits range from 1 to 100. `getDialogs` requires a limit and `getHistory` defaults to 50. |
| `{"type":"POST","action":"sendMessage","text":"Hello"}` | Starts a turn as the writer. Participants receive `user.text`, `assistant.text.delta`, and a terminal `assistant.text.done` or `error` with a shared `turnId`. New readers receive subsequent deltas. |
| `{"type":"POST","action":"changeModel","model":"gpt-5.6-sol"}` | Selects the model for the writer's next turn in the current thread. |
| `{"type":"POST","action":"transferWriter","clientId":"<reader-client-id>"}` | Transfers write access to a connected reader. The participants receive `writer.changed`, and the current turn continues. |
| `{"type":"POST","action":"closeChat"}` | Leaves the chat and returns this client to the menu on the same WebSocket. |
| `{"type":"POST","action":"finishChat"}` | The writer ends the idle shared session and returns all participants to their menus. The Codex thread and its history stay available. |
| `{"type":"DELETE","action":"deleteChat","threadId":"<id>"}` | Permanently deletes a stored dialog from the menu or as its current idle writer. |
| `{"type":"DELETE","action":"closeChatAndDelete"}` or `{"type":"DELETE","action":"finishChatAndDelete"}` | The idle writer deletes the current dialog and returns all participants to their menus. |

The `session.joined` event includes `threadId`, `clientId`, `writerId`, and the active `turnId`, if any. When another client joins, the existing participants receive its `joinedClientId` in `session.updated`. When a writer leaves or disconnects, the earliest remaining client becomes the writer. The endpoint is intended for local use. Clients with access to the endpoint and thread ID can join.

## Opening the outbound connection

With the `codex` Spring profile active, inject `CodexClient`. Spring wires its RPC session using the address from [`application-codex.properties`](../src/main/resources/application-codex.properties). For standalone use, call `CodexClient.create(serverUrl)`. Each session has its own dispatcher, listener, and pending requests.

The chat backend uses `talia.codex.client.name`, `talia.codex.client.title`, and `talia.codex.client.version` for the Codex handshake. Set these properties alongside `talia.codex.url` in the Codex profile.

`client.connect(name, title, version)` opens the WebSocket, sends `initialize`, waits for the reply, and sends `initialized`. Its future completes after the notification is sent. Application requests and notifications fail until the handshake completes. Each client can initialize once.

The standalone [`CodexWebSocketDemo`](../src/test/java/com/chatbot/talia/ws/adapter/codex/client/CodexWebSocketDemo.java) runs from the test source set. Pass the server URL, client name, client title, and client version as program arguments, for example `ws://127.0.0.1:4222 talia Talia 0.1.0`. After initialization it checks the 10 most recently updated, non-archived conversations for a first user message of `hello from intellij idea`. It resumes the newest exact match or starts a new thread, then sends the demo turn and closes the connection.

## Building and sending a request

The handshake builds an `InitializeRequest` with a client-assigned ID. The request's `method()` returns the string `initialize` using `CodexMethod.INITIALIZE.value()`. Jackson includes that method in the serialized JSON through `@JsonProperty`.

The resulting request has this shape:

```json
{
  "id": 0,
  "method": "initialize",
  "params": {
    "clientInfo": {
      "name": "talia",
      "title": "Talia",
      "version": "0.1.0"
    }
  }
}
```

The Codex app server uses JSON-RPC-style messages without the `jsonrpc` field on the wire. See the [app-server protocol documentation](https://learn.chatgpt.com/docs/app-server#protocol).

`client.rpc().send(request, responseType)` sends a typed request on the same session used by the conversation API.

```text
CodexRequest
    │
    ▼
CodexWebSocketClient.send()
    ├─ copies the request with a fresh ID
    ├─ CodexMessageCodec.encode(request)
    ├─ registers the pending request with CodexMessageDispatcher
    ▼
CodexWebSocketConnection.send(json)
    ├─ creates a send future
    └─ submits SendTask to the single sender executor
           │
           ▼
       SendTask.run()
           └─ socket.sendText(json, true)
```

The client allocates increasing request IDs for the lifetime of the connection. It encodes each request before registration, so encoding failures complete the returned future without creating a pending entry. Each registered future has a ten-second timeout and removes its map entry on completion.

The connection uses one virtual thread to send text messages in order. It accepts up to 64 queued messages while another send is active. When the queue fills, new text sends fail with `RejectedExecutionException`. Ping is sent directly through `sendPing`.

## Two futures, two results

The send path uses two futures for different operations:

1. **Send future** — `CompletableFuture<Void>` returned by `CodexWebSocketConnection.send`. `SendTask` completes it when `sendText` finishes. The client passes a send failure to the response future.
2. **Response future** — `CompletableFuture<R>` returned by `CodexWebSocketClient.send`. The dispatcher completes it with a matching response of type `R` or completes it exceptionally on an error.

Completing a send future does not mean that Codex has accepted the request or generated a response. A response future can also time out while its send task is queued or after its request was sent. A timeout does not cancel a request on the server.

### Understanding `CompletableFuture`

A `CompletableFuture<T>` is an object representing an outcome that may become available later. `T` is the type of the successful result. A future begins pending and eventually completes successfully with a value, completes exceptionally with an error, or is cancelled. Once completed, later attempts to complete the same future do not replace the outcome.

Here is a small independent Java example:

```java
CompletableFuture<String> answer = new CompletableFuture<>(); // pending

answer.complete("Hello"); // successful completion

String text = answer.get(); // returns "Hello"
```

An error can be supplied with `answer.completeExceptionally(error)`. In that case `get()` throws an `ExecutionException` whose cause is the supplied error. `get()` blocks the calling thread while the future is pending; `get(10, TimeUnit.SECONDS)` limits how long that particular call waits. A timed-out `get` does not itself complete or cancel the future. In contrast, the `orTimeout` call in this project completes the response future exceptionally if it is still pending when the deadline expires.

#### 1. Create a response future — `CodexWebSocketClient`

`send(request, responseType)` assigns an ID to a copy of the request and registers the response class and future in a `PendingRequest` before sending. Each call gets a distinct ID, including when callers reuse a request object.

`send` returns this response future to its caller. Returning a pending future gives the caller an object to wait on or attach further work to; it does not wait for Codex by itself.

#### 2. Submit the text — `CodexWebSocketConnection` and `SendTask`

`CodexWebSocketConnection.send` creates a **different** future and hands it to `SendTask`:

```java
CompletableFuture<Void> result = new CompletableFuture<>();
sender.execute(new SendTask(message, result, webSocket));
return result;
```

`Void` means that successful completion carries no useful result value. The executor runs `SendTask` on its sender virtual thread. This excerpt is from `SendTask.run`:

```java
socket.sendText(message, true).get(10, TimeUnit.SECONDS);
result.complete(null);
```

The JDK's `sendText` returns its own future. `SendTask` waits for that operation on the **sender thread**, then completes the `result` future returned by `CodexWebSocketConnection.send`. The caller that submitted the task does not execute this `get`. The task completes `result` exceptionally if it catches an execution error, interruption, or timeout.

The client uses `whenComplete` on the send future to propagate a send failure to the response future. On a successful send, the response future waits for the server's reply.

#### 3. Complete the response — `CodexMessageDispatcher`

The dispatcher looks up the pending request during decoding. The codec uses its stored response class to deserialize a successful reply. The dispatcher completes the future from that same entry, even if the map changes after decoding. A matched server error or decoding failure completes the future exceptionally. The response handler logs unmatched errors and ignores unmatched successes.

#### 4. Clean up and wait

The dispatcher sets a ten-second deadline and removes the entry when its future completes, fails, or is cancelled. The two-argument `remove` removes the entry only if the ID still maps to that pending request.

A caller can wait like this.

```java
CompletableFuture<Void> connected = client.connect("talia", "Talia", "0.1.0");
connected.get(15, TimeUnit.SECONDS);
```

The first line returns a future. The second can block the calling thread. In a WebSocket listener callback, blocking to wait for a reply can prevent the listener from processing the event needed to complete it. A local timeout or cancellation leaves the server outcome unknown.

## Receiving and matching messages

`CodexWebSocketListener.onText` joins fragments until `last` is `true`, then calls `dispatcher.onTextMessage(json)`. The dispatcher completes matched responses and uses the handler registry for notifications, server-initiated requests, and unmatched responses.

Shutdown rejects new sends and starts closing the transport before notifying pending callers. `close()` initiates shutdown. `closeAsync()` waits for Java to write the Close frame and release local resources. Codex App Server can end TCP after receiving that frame, so local shutdown completes independently of a reciprocal Close frame. The two-second timeout covers the frame write. A write or cleanup failure completes the close future exceptionally. Create a new client to connect again after shutdown.

## Conversation operations

- `listConversations(limit, cursor)` returns a page of conversation summaries ordered by most recent update. Pass the page's `nextCursor` for the next page. `listConversations(limit)` starts at the first page.
- `getConversationHistory(threadId, messageLimit, order)` reads up to the requested number of user and assistant messages. `getConversationHistory(conversationCount, messageLimit, order)` reads histories for the most recently updated conversations. Use `ConversationHistory.Order.OLDEST_FIRST` or `NEWEST_FIRST`.
- `openConversation(threadId, messageLimit, order)` resumes a conversation and returns its history in the selected order. `resumeConversation(threadId)` returns the resumed ID. The history methods wait for server replies, so call them outside WebSocket callbacks.
- `startConversation(model)` returns the new thread ID. `finishConversation(threadId)` returns the unsubscribe status, stops local tracking for the current turn, and preserves the stored conversation.
- `CodexRpcSchema.getAvailableActions(Path.of("target/codex-schema/ClientRequest.json"))` reads the App Server RPC request methods from a Codex-generated schema. Generate it with `codex app-server generate-json-schema --out target/codex-schema` using the same Codex version as the server. The list describes the protocol for that build. Calling a method can still depend on server configuration, session state, and permissions.

## Running a turn

After connecting, pass the conversation ID and prompt to `runTurn`.

```java
String threadId = client.startConversation(CodexModel.GPT_5_6_LUNA).get();
CompletableFuture<CodexTurnResult> turn = client.runTurn(threadId, "Hello", System.out::print);
CodexTurnResult result = turn.get(2, TimeUnit.MINUTES);
```

The client delivers text deltas on the WebSocket listener thread. Keep the text callback short and avoid waiting for a WebSocket reply inside it. The future carries the final server status and optional error message. A failed or interrupted server turn still has a terminal result. A start error or connection loss completes the future exceptionally. The API tracks one turn per Codex thread at a time. Cancelling the future ends local tracking. Interrupting the server turn requires a separate request.
