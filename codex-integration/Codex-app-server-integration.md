## Codex App Server Integration

Talia uses **Codex App Server** as its AI backend, authenticated through a ChatGPT Plus subscription without requiring a separate OpenAI API key.

### Architecture

```text
Postman / Talia Client
        ↕ WebSocket
Spring Boot (:8080/ws/chat)
        ↕
TaliaWebSocketHandler
        ↕
CodexWebSocketClient
        ↕ WebSocket + JSON-RPC
Codex App Server (:4222)
        ↕
OpenAI / Codex
```

### Communication

- **Transport:** Persistent, bidirectional WebSocket connections.
- **Protocol:** Codex uses JSON-RPC, not the OpenAI Chat Completions API.
- **Initialization:** `initialize` → `initialized` → `thread/start`.
- **Requests:** `turn/start` starts model inference; `turn/steer` can provide additional input during an active turn.
- **Streaming:** `item/agentMessage/delta` delivers incremental text; `turn/completed` signals completion.
- **Conversation:** A persistent Codex thread retains context across turns.
- **Concurrency:** Current MVP supports one active Codex turn at a time.

### Running locally

1. Authenticate using `codex login`.
2. Start Codex App Server:

   ```bash
   codex app-server --listen ws://127.0.0.1:4222
   ```

3. Start Spring Boot with the `codex` profile.
4. Connect Postman to `ws://localhost:8080/ws/chat` and send a text message.

Configuration is stored in `application-codex.properties`. Spring AI model providers and ChatClient auto-configuration are disabled in this profile.

**Important:** Codex WebSocket transport is experimental. Spring currently establishes the connection at startup and fails to start if Codex is unavailable.

### Planned Improvements

- **Live input:** Accumulate events and trigger Codex after 3 seconds of inactivity.
- **Autonomous responses:** Codex decides whether to respond or remain silent.
- **Live speech:** Stream generated text into a local TTS engine and return audio over WebSocket.
- **Architecture:** Separate WebSocket transport, Codex communication, conversation orchestration, and speech synthesis.

### Codex CLI — Model Selection

**Interactive mode:**
```bash
codex
/model    # Select model and reasoning effort
/status   # Show current configuration
```

**Specify model on startup:**
```bash
codex -m <model-name>
codex exec -m <model-name> "Your prompt"
```

**Set default model** in `~/.codex/config.toml` (Windows: `%USERPROFILE%\.codex\config.toml`):

```toml
model = "gpt-5.6-sol"
model_reasoning_effort = "high"
```

**Priority:** CLI `-m` overrides the default configuration.

Documentation: [Codex CLI Reference](https://developers.openai.com/codex/cli/reference)