# Codex WebSocket communication

This document describes the Codex WebSocket implementation in `src/main/java/com/chatbot/talia/ws/codex`, including how outgoing requests are matched to incoming replies.

## The pieces

| Class | Responsibility in the current code |
| --- | --- |
| [`CodexWebSocketClient`](../src/main/java/com/chatbot/talia/ws/codex/client/CodexWebSocketClient.java) | Creates the connection and uses the dispatcher to register requests before sending them. |
| [`CodexWebSocketConnection`](../src/main/java/com/chatbot/talia/ws/codex/client/CodexWebSocketConnection.java) | Opens the outbound WebSocket, queues text sends, sends pings, and owns the sender executor and HTTP client. |
| [`SendTask`](../src/main/java/com/chatbot/talia/ws/codex/client/SendTask.java) | Sends one text message on the executor and completes a future when the send finishes or fails. |
| [`CodexWebSocketListener`](../src/main/java/com/chatbot/talia/ws/codex/listener/CodexWebSocketListener.java) | Assembles WebSocket text fragments and forwards complete messages, close events, and errors to its dispatcher. |
| [`CodexMessageDispatcher`](../src/main/java/com/chatbot/talia/ws/codex/client/CodexMessageDispatcher.java) | Tracks pending requests for one connection, completes their futures, and routes other messages through the handler registry. |
| [`CodexMessageCodec`](../src/main/java/com/chatbot/talia/ws/codex/protocol/CodexMessageCodec.java) | Uses Jackson's `ObjectMapper` to encode outgoing messages and decode incoming notifications, server requests, and responses. |
| [`JsonRpcRequest<P>` and `CodexRequest<P>`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/JsonRpcRequest.java) | Define the `id`, `method`, and typed `params` exposed by a request. `CodexRequest` extends that interface. |
| [`InitializeRequest`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/InitializeRequest.java) and [`CodexMethod`](../src/main/java/com/chatbot/talia/ws/codex/protocol/request/CodexMethod.java) | Represent the initialization request and its protocol method name. |
| [`CodexChatWebSocketConfiguration`](../src/main/java/com/chatbot/talia/ws/codex/config/CodexChatWebSocketConfiguration.java) | Under the `codex` profile, registers the incoming `/ws/chat` handler. |

There are two different WebSocket connections in the application. `/ws/chat` accepts a connection **from** a client such as Postman. `CodexWebSocketConnection` opens a connection **to** the local Codex app server. The current [`CodexChatWebSocketHandler`](../src/main/java/com/chatbot/talia/ws/codex/handler/CodexChatWebSocketHandler.java) prints incoming client messages; it does not yet forward them to Codex.

## Opening the outbound connection

With the `codex` Spring profile active, Spring constructs `CodexWebSocketClient` with `CodexMessageCodec`, `CodexMessageHandlerRegistry`, and the address from [`application-codex.properties`](../src/main/resources/application-codex.properties). Each client creates its own dispatcher and listener, so callbacks and pending request IDs belong to one connection.

Calling `client.connect()` calls `connection.open()`. The connection uses the JDK `HttpClient` WebSocket builder to start the connection and waits up to five seconds for it to open. The listener's `onOpen()` delegates to the default listener implementation, which requests the first incoming event. A successful WebSocket connection alone does not complete the Codex initialization handshake.

The standalone `main()` creates and connects a client, performs the initialization handshake, then starts a thread and sends a turn. The try-with-resources block closes the client when `main()` exits.

## Building and sending a request

`initialize(id, name, title, version)` builds an `InitializeRequest` and calls `send(request, InitializeResponse.class)`. The request's `method()` returns the string `initialize` using `CodexMethod.INITIALIZE.value()`. Jackson includes that method in the serialized JSON through `@JsonProperty`.

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
    ├─ registers the pending request with CodexMessageDispatcher
    ├─ CodexMessageCodec.encode(request)
    ▼
CodexWebSocketConnection.send(json)
    ├─ creates a send future
    └─ submits SendTask to the single sender executor
           │
           ▼
       SendTask.run()
           └─ socket.sendText(json, true)
```

The dispatcher stores each `PendingRequest<?>` by ID in a `ConcurrentHashMap`. `putIfAbsent` rejects an ID already waiting for a response. Each registered future has a ten-second timeout and removes its map entry on completion. The JSON conversion occurs after registration, so an exception during serialization currently leaves the entry until its timeout.

The connection creates a single-thread executor whose worker is a virtual thread. Multiple callers can submit sends, and `SendTask.run()` waits for one `sendText` operation to finish before that worker starts the next task. The executor currently uses an unbounded queue. Ping is sent directly through `sendPing`, outside that text-send queue.

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

`send(request, responseType)` wraps the response class and future in a `PendingRequest` and registers it with the dispatcher before sending. `putIfAbsent` prevents two concurrent requests from waiting under the same ID.

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
CompletableFuture<InitializeResponse> response = client.send(request, InitializeResponse.class);
InitializeResponse result = response.get(10, TimeUnit.SECONDS);
```

The first line returns a future; the second can block the calling thread. In a WebSocket listener callback, blocking to wait for a reply can prevent the listener from processing the event needed to complete it. Also, a local timeout or cancellation does not establish whether Codex received or executed a request.

## Receiving and matching messages

`CodexWebSocketListener.onText` joins fragments until `last` is `true`, then calls `dispatcher.onTextMessage(json)`. The dispatcher completes matched responses and uses the handler registry for notifications, server-initiated requests, and unmatched responses.

`onClose` and `onError` forward their causes to the dispatcher, which completes pending futures exceptionally. An explicit `client.close()` also fails any requests still pending after the connection closes.

## Initialization and the next steps

The Codex handshake is `initialize` request, successful response, then `initialized` notification. The standalone example performs that handshake before starting a thread and sending a turn.

Next steps include routing turn events by their IDs and bounding the send queue.

The response map and sender queue are the beginning of concurrent request support. They do not yet establish end-to-end thread safety, response routing, or safe reconnection.
