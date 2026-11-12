# Codex WebSocket communication

This document describes the current implementation in `src/main/java/com/chatbot/talia/ws/codex`. It is a learning implementation in progress. The outgoing request path is present, while the listener is not yet connected to the client's response-handling methods.

## The pieces

| Class | Responsibility in the current code |
| --- | --- |
| [`CodexWebSocketClient`](../src/main/java/com/chatbot/talia/ws/codex/client/CodexWebSocketClient.java) | Creates a connection, converts Codex request objects to JSON, and keeps a map of futures waiting for responses. |
| [`CodexWebSocketConnection`](../src/main/java/com/chatbot/talia/ws/codex/client/CodexWebSocketConnection.java) | Opens the outbound WebSocket, queues text sends, sends pings, and owns the sender executor and HTTP client. |
| [`SendTask`](../src/main/java/com/chatbot/talia/ws/codex/client/SendTask.java) | Sends one text message on the executor and completes a future when the send finishes or fails. |
| [`CodexWebSocketListener`](../src/main/java/com/chatbot/talia/ws/codex/listener/CodexWebSocketListener.java) | Receives WebSocket callbacks, prints incoming events, and forwards close and error events to its client when configured with `setClient`. |
| [`JsonConverter`](../src/main/java/com/chatbot/talia/ws/codex/protocol/JsonConverter.java) | Uses Jackson's `ObjectMapper` to convert objects to JSON and JSON to a requested Java class. |
| [`JsonRpcRequest<P>` and `CodexRequest<P>`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/JsonRpcRequest.java) | Define the `id`, `method`, and typed `params` exposed by a request. `CodexRequest` extends that interface. |
| [`InitializeRequest`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/InitializeRequest.java) and [`CodexMethod`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/CodexMethod.java) | Represent the initialization request and its protocol method name. |
| [`CodexChatWebSocketConfiguration`](../src/main/java/com/chatbot/talia/ws/codex/config/CodexChatWebSocketConfiguration.java) | Under the `codex` profile, registers the incoming `/ws/chat` handler and a listener bean. |

There are two different WebSocket connections in the application. `/ws/chat` accepts a connection **from** a client such as Postman. `CodexWebSocketConnection` opens a connection **to** the local Codex app server. The current [`CodexChatWebSocketHandler`](../src/main/java/com/chatbot/talia/ws/codex/handler/CodexChatWebSocketHandler.java) prints incoming client messages; it does not yet forward them to Codex.

## Opening the outbound connection

With the `codex` Spring profile active, Spring constructs `CodexWebSocketClient` with `JsonConverter`, a `WebSocket.Listener`, and the address from [`application-codex.properties`](../src/main/resources/application-codex.properties). The client creates a `CodexWebSocketConnection` with that address and listener.

Calling `client.connect()` calls `connection.open()`. The connection uses the JDK `HttpClient` WebSocket builder to start the connection and waits up to five seconds for it to open. The listener's `onOpen()` delegates to the default listener implementation, which requests the first incoming event. A successful WebSocket connection alone does not complete the Codex initialization handshake.

The class also contains an in-progress standalone `main()` that creates two clients and listeners, connects them, and sends an initialization request on each. The Spring component constructor itself does not call `connect()`. At the time of writing, `main()` calls `client.close()`, but that method has not yet been added to `CodexWebSocketClient`, so the example does not compile unchanged.

## Building and sending a request

`initialize()` creates an `InitializeRequest` with the hardcoded ID `0` and client information, then calls `send(request)`. The request's `method()` returns the string `initialize` using `CodexMethod.INITIALIZE.value()`. Jackson includes that method in the serialized JSON through `@JsonProperty`.

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

For `client.send(request)`, the intended path through the existing methods is:

```text
CodexRequest
    │
    ▼
CodexWebSocketClient.send()
    ├─ registers a response future in pendingRequests[id]
    ├─ JsonConverter.toJson(request)
    ▼
CodexWebSocketConnection.send(json)
    ├─ creates a send future
    └─ submits SendTask to the single sender executor
           │
           ▼
       SendTask.run()
           └─ socket.sendText(json, true)
```

`pendingRequests` is a `ConcurrentHashMap<Long, CompletableFuture<JsonNode>>`. `putIfAbsent` rejects an ID already waiting for a response. Each registered response has a ten-second timeout; a completion callback removes its map entry when it finishes. The JSON conversion occurs after registration, so an exception during serialization currently leaves the entry until its timeout.

The connection creates a single-thread executor whose worker is a virtual thread. Multiple callers can submit sends, and `SendTask.run()` waits for one `sendText` operation to finish before that worker starts the next task. The executor currently uses an unbounded queue. Ping is sent directly through `sendPing`, outside that text-send queue.

## Two futures, two results

The send path uses two futures for different operations:

1. **Send future** — `CompletableFuture<Void>` returned by `CodexWebSocketConnection.send`. `SendTask` completes it when `sendText` finishes. A failure is passed to the response future by `handleSendResult`.
2. **Response future** — `CompletableFuture<JsonNode>` returned by `CodexWebSocketClient.send`. It is intended to complete when `onMessage` receives a matching JSON-RPC result. An `error` completes it exceptionally.

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

The following is an excerpt from `CodexWebSocketClient.send`:

```java
Long id = request.id();
CompletableFuture<JsonNode> response = new CompletableFuture<>();

CompletableFuture<JsonNode> existing =
        pendingRequests.putIfAbsent(id, response);

if (existing != null) {
    return CompletableFuture.failedFuture(
            new IllegalArgumentException("Request ID is already in use: " + id)
    );
}
```

For a request with ID `7`, the map now contains `7 → response`. The future exists before the send starts, so the client can find it even if a reply arrives quickly. `putIfAbsent` also prevents two concurrent requests from waiting under the same ID. `ConcurrentHashMap` allows the caller and response-handling code to access the map from different threads.

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

The client connects a failed send to the response future. This excerpt belongs to `CodexWebSocketClient.send`:

```java
CompletableFuture<Void> sending = connection.send(json);
sending.whenComplete((value, error) -> handleSendResult(response, error));
```

`whenComplete` registers a callback that runs when `sending` finishes. The current `handleSendResult` completes `response` exceptionally when `error` is not `null`. When sending succeeds, it leaves `response` pending because the server's reply still has to arrive. A lambda is a short way to provide this callback; a named class implementing `BiConsumer<Void, Throwable>` can express the same behavior.

#### 3. Complete the response — `CodexWebSocketClient`

When a complete response is passed to `onMessage`, the client looks up its ID. This is an excerpt from that method:

```java
long id = idNode.asLong();
CompletableFuture<JsonNode> response = pendingRequests.get(id);

if (response == null) {
    return;
}

if (message.has("error")) {
    response.completeExceptionally(
            new IllegalStateException("Codex error: " + message.get("error"))
    );
} else if (message.has("result")) {
    response.complete(message.get("result"));
}
```

For example, a response with ID `7` looks up the future stored under `7`. Calling `complete(...)` makes its result available to the caller. A response for an unknown ID, including a response arriving after its map entry was removed, is ignored by the current method.

**Current limitation:** the listener does not yet forward complete text messages to `onMessage`, so this completion step does not currently happen automatically. The code above describes what `onMessage` does when it is called.

#### 4. Clean up and wait — `CodexWebSocketClient` and its caller

These lines in `CodexWebSocketClient.send` set the response deadline and remove the map entry on completion, failure, cancellation, or timeout:

```java
response.orTimeout(10, TimeUnit.SECONDS);
response.whenComplete((value, error) -> pendingRequests.remove(id, response));
```

The two-argument `remove` removes the entry only if it still maps that ID to this particular future. The callback can run on the thread that completes the future, or immediately if it was already complete, so keep callbacks short and avoid waiting for another response inside them.

A caller can wait like this:

```java
CompletableFuture<JsonNode> response = client.send(request);
JsonNode result = response.get(10, TimeUnit.SECONDS);
```

The first line returns a future; the second can block the calling thread. In a WebSocket listener callback, blocking to wait for a reply can prevent the listener from processing the event needed to complete it. Also, a local timeout or cancellation does not establish whether Codex received or executed a request.

## Receiving and matching messages

The client has an `onMessage(String json)` method that parses JSON, checks the ID, and looks up the corresponding response future. A response with a `result` completes that future; one with an `error` completes it exceptionally. Messages containing `method` are currently printed as events. `onConnectionClosed(Throwable)` can complete pending futures exceptionally.

**The listener does not currently call `onMessage`.** Its `onText` prints the received fragment and delegates to the default implementation. It does not yet join fragments using `last` or forward complete messages. Consequently, a response printed by the listener does not currently complete `initialize()`; its response future will time out unless another caller invokes `onMessage` with the response. The listener does call `onConnectionClosed` from its close and error callbacks, provided `setClient` has been called first.

### Listener callbacks and connection close

`CodexWebSocketListener` implements the JDK's `WebSocket.Listener` interface. This is an example of the **Listener pattern**: the WebSocket API invokes listener methods when events occur. Each such invocation is a callback.

The current close flow has two calls when the listener has been configured with its client:

```text
WebSocket closes
    ↓
Java calls listener.onClose(webSocket, statusCode, reason)
    ↓
Listener calls client.onConnectionClosed(cause)
    ↓
Client completes its pending response futures exceptionally
```

The first call is a callback defined by `WebSocket.Listener`. The second is a direct call from the listener to the client that forwards the event. Passing a cause gives pending callers information about the close instead of making them wait for their individual response timeouts.

For example, the current `onClose` contains:

```java
IOException cause = new IOException("Codex connection closed. Status: " + statusCode);
client.onConnectionClosed(cause);
```

The listener's `setClient` supplies this reference, and `onError` also calls `onConnectionClosed`. The standalone example calls `setClient` before connecting. Spring currently creates a listener bean but does not call `setClient`, so close or error callbacks on that path can encounter a null client. If several sockets are opened, each listener should forward events to the client that owns that socket and its pending requests.

Responses, notifications, and server-initiated requests have different roles. A response has an ID and a `result` or `error`. A notification has a `method` and no ID. A server-initiated request has both a `method` and an ID, and may need a response. The current `onMessage` treats every message with a `method` as an event, so it does not yet respond to server-initiated requests.

## Initialization and the next steps

The Codex handshake is `initialize` request → successful response → `initialized` notification. The current `initialize()` sends the first request, but the response is not connected to the listener and the `initialized` notification is not yet sent. Only after the handshake can the client start a thread and send a turn. `MODEL` is currently a constant; the code does not yet select it with `thread/start`.

Useful next steps for the existing structure are:

1. Wire each connection's listener to its client, including in Spring, and forward complete text messages. Accumulate text fragments until `last` is `true`.
2. Wait for the matching `initialize` result, then send the `initialized` notification. Treat each new WebSocket connection as needing its own handshake.
3. Handle notifications and server-initiated requests separately. Route turn events using their thread and turn identifiers.
4. Bound the send queue and define what happens to queued and in-flight futures on timeout, cancellation, disconnect, and shutdown. A timed-out WebSocket send may still be pending; do not blindly send another message on that connection.
5. Add client-level lifecycle management. The connection has `close()`, but the current client does not expose it or arrange Spring shutdown. The ping payload helper also allocates eight bytes even though it accepts payloads up to 125 bytes.

The response map and sender queue are the beginning of concurrent request support. They do not yet establish end-to-end thread safety, response routing, or safe reconnection.
