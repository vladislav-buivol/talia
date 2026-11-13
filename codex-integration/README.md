# Codex WebSocket communication

This document describes the Codex WebSocket implementation in `src/main/java/com/chatbot/talia/ws/codex`, including how outgoing requests are matched to incoming replies.

## The pieces

| Class | Responsibility in the current code |
| --- | --- |
| [`CodexWebSocketClient`](../src/main/java/com/chatbot/talia/ws/codex/client/CodexWebSocketClient.java) | Creates the connection and uses the dispatcher to register requests before sending them. |
| [`CodexWebSocketConnection`](../src/main/java/com/chatbot/talia/ws/codex/client/CodexWebSocketConnection.java) | Opens the outbound WebSocket, queues text sends, sends pings, and owns the sender executor and HTTP client. |
| [`SendTask`](../src/main/java/com/chatbot/talia/ws/codex/client/SendTask.java) | Sends one text message on the executor and completes a future when the send finishes or fails. |
| [`CodexWebSocketListener`](../src/main/java/com/chatbot/talia/ws/codex/listener/CodexWebSocketListener.java) | Assembles WebSocket text fragments for the dispatcher and forwards close and error events to the connection. |
| [`CodexMessageDispatcher`](../src/main/java/com/chatbot/talia/ws/codex/client/CodexMessageDispatcher.java) | Tracks pending requests for one connection, completes their futures, and routes other messages through the handler registry. |
| [`CodexMessageCodec`](../src/main/java/com/chatbot/talia/ws/codex/protocol/CodexMessageCodec.java) | Uses Jackson's `ObjectMapper` to encode outgoing messages and decode incoming notifications, server requests, and responses. |
| [`JsonRpcRequest<P>` and `CodexRequest<P>`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/JsonRpcRequest.java) | Define the `id`, `method`, and typed `params` exposed by a request. `CodexRequest` extends that interface. |
| [`InitializeRequest`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/InitializeRequest.java) and [`CodexMethod`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/CodexMethod.java) | Represent the initialization request and its protocol method name. |
| [`CodexChatWebSocketConfiguration`](../src/main/java/com/chatbot/talia/ws/codex/config/CodexChatWebSocketConfiguration.java) | Under the `codex` profile, registers the incoming `/ws/chat` handler. |

There are two different WebSocket connections in the application. `/ws/chat` accepts a connection **from** a client such as Postman. `CodexWebSocketConnection` opens a connection **to** the local Codex app server. The current [`CodexChatWebSocketHandler`](../src/main/java/com/chatbot/talia/ws/codex/handler/CodexChatWebSocketHandler.java) prints incoming client messages; it does not yet forward them to Codex.

## Opening the outbound connection

With the `codex` Spring profile active, Spring constructs `CodexWebSocketClient` with `CodexMessageCodec`, `CodexMessageHandlerRegistry`, and the address from [`application-codex.properties`](../src/main/resources/application-codex.properties). Each client creates its own dispatcher and listener, so callbacks and pending request IDs belong to one connection.

`client.connectAndInitialize(name, title, version)` opens the WebSocket, sends `initialize`, waits for the reply, and sends `initialized`. Its future completes after the notification is sent. Application requests and notifications fail until the handshake completes. Each client can initialize once.

The standalone [`CodexWebSocketDemo`](../src/test/java/com/chatbot/talia/ws/codex/client/CodexWebSocketDemo.java) runs from the test source set. After initialization it checks the 10 most recently updated, non-archived conversations for a first user message of `hello from intellij idea`. It resumes the newest exact match or starts a new thread, then sends the demo turn and closes the connection.

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

`client.send(request, responseType)` follows this path.

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
CompletableFuture<InitializeResponse> response = client.connectAndInitialize("talia", "Talia", "0.1.0");
InitializeResponse result = response.get(15, TimeUnit.SECONDS);
```

The first line returns a future; the second can block the calling thread. In a WebSocket listener callback, blocking to wait for a reply can prevent the listener from processing the event needed to complete it. Also, a local timeout or cancellation does not establish whether Codex received or executed a request.

## Receiving and matching messages

`CodexWebSocketListener.onText` joins fragments until `last` is `true`, then calls `dispatcher.onTextMessage(json)`. The dispatcher completes matched responses and uses the handler registry for notifications, server-initiated requests, and unmatched responses.

Shutdown rejects new sends and starts closing the transport before notifying pending callers. `close()` initiates shutdown. `closeAsync()` waits for Java to write the Close frame and release local resources. Codex App Server can end TCP after receiving that frame, so local shutdown completes independently of a reciprocal Close frame. The two-second timeout covers the frame write. A write or cleanup failure completes the close future exceptionally. Create a new client to connect again after shutdown.

## Conversation operations

- `listConversations(limit, cursor)` returns a page of non-archived conversations ordered by most recent update. Pass the response's `nextCursor` for the next page. `listConversations(limit)` starts at the first page.
- `getConversationHistory(threadId, messageLimit, order)` reads up to the requested number of user and assistant messages. `getConversationHistory(conversationCount, messageLimit, order)` reads histories for the most recently updated conversations. Use `ConversationHistory.Order.OLDEST_FIRST` or `NEWEST_FIRST`.
- `openConversation(threadId, messageLimit, order)` resumes a conversation and returns its history in the selected order. The history methods wait for server replies, so call them outside WebSocket callbacks.
- `startConversation(model)` starts a thread with a `CodexModel`. `finishConversation(threadId)` unsubscribes this connection, stops local tracking for the current turn, and preserves the stored conversation.
- `getAvailableActions(Path.of("target/codex-schema/ClientRequest.json"))` reads the App Server RPC request methods from a Codex-generated schema. Generate it with `codex app-server generate-json-schema --out target/codex-schema` using the same Codex version as the server. The list describes the protocol for that build. Calling a method can still depend on server configuration, session state, and permissions.

## Running a turn

After initialization and `thread/start`, pass the thread ID and text to `runTurn`.

```java
TurnStartRequest request = TurnStartRequest.create(threadId, "Hello");
CompletableFuture<CodexTurnResult> turn = client.runTurn(request, System.out::print);
CodexTurnResult result = turn.get(2, TimeUnit.MINUTES);
```

The client delivers text deltas on the WebSocket listener thread. Keep the text callback short and avoid waiting for a WebSocket reply inside it. The future carries the final server status and optional error message. A failed or interrupted server turn still has a terminal result. A start error or connection loss completes the future exceptionally. The API tracks one turn per Codex thread at a time. Cancelling the future ends local tracking. Interrupting the server turn requires a separate request.
