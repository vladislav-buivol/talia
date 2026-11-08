# Talia development plan

## Goal

Build a conversational assistant that accepts text and, later, microphone input; streams Codex responses; speaks them with OmniVoice; and responds promptly when either participant interrupts. Start with a single local user, then expand only after the end-to-end experience works.

## Current state

- Spring connects to a Codex app server through a WebSocket client and streams response deltas.
- A WebSocket endpoint exists at `/ws/chat`, and a separate HTTP chat controller exists.
- The current WebSocket client holds one shared Codex thread and allows one active turn. It does not yet provide per-user isolation or cancellation.
- The HTTP controller uses a separate Codex CLI process; verify its behavior and profile wiring before treating it as the main conversation interface.
- Speech synthesis, microphone input, playback and interruption handling still need to be built and verified.

## 1. Establish a reliable text conversation

- [ ] Choose `/ws/chat` as the primary interactive transport; retain HTTP only if a simple non-streaming endpoint is useful.
- [ ] Define JSON messages for `user.text`, `assistant.text.delta`, `assistant.text.done`, `turn.cancel`, `turn.cancelled` and `error`. Include `sessionId` and `turnId` where applicable.
- [ ] Route events by session and turn. Start with one user, but avoid sharing one conversation between unrelated clients.
- [ ] Handle disconnects, timeouts, duplicate requests and cleanup. Decide whether conversation history survives reconnects.
- [ ] Build a minimal client to submit text and display streamed responses and errors.

**Done when:** a user can have several consecutive text turns, see output as it arrives, and recover cleanly from a disconnected client or server.

## 2. Speak Codex responses

- [ ] Confirm OmniVoice's actual interface, deployment requirements, supported languages, audio format, latency and whether it supports streaming or cancellation. Run a small standalone synthesis test before choosing an integration.
- [ ] Add a `SpeechSynthesizer` abstraction and an OmniVoice adapter. Keep configuration for endpoint/process, voice, language and timeouts outside source code.
- [ ] Buffer streamed text into short, meaningful speech segments, normally ending at sentence or clause boundaries. Flush a sufficiently long fragment after a configurable maximum wait; flush the remainder when the turn ends. Avoid synthesizing individual tokens.
- [ ] Synthesize segments in a bounded pipeline and play them in order. Support a small playback buffer, backpressure and graceful TTS failure while retaining the text response.
- [ ] Start with all user-facing assistant text eligible for speech. If the assistant later needs private or non-spoken content, use a structured event field such as `speak: true` or `false`; do not rely on model-generated start/end tags as a security boundary. If tags are necessary, define and test a streaming parser for split, missing and malformed tags.

**Done when:** the first speech segment starts before a long response finishes, audio stays in the correct order, and text remains usable if synthesis fails.

## 3. Make stop and interruption reliable

- [ ] Add a visible Stop control. On activation, stop audio locally immediately and clear queued audio; do not wait for a server acknowledgement.
- [ ] Send a cancellation event to the server, request cancellation of the active Codex turn using the supported server protocol, and cancel or discard pending synthesis work. Verify the actual cancellation API before implementation.
- [ ] Tag text and audio with session, turn and segment identifiers. Ignore late events from cancelled turns so old speech cannot resume after Stop or a new user message.
- [ ] Define cancellation behavior during generation, synthesis, queued playback and disconnect; make repeated Stop requests harmless.
- [ ] Set measurable responsiveness targets after a baseline test: record stop-click to local silence, time to first text, time to first audio and end-of-user-speech to first audio. Test under slow generation and TTS as well as normal conditions.

**Done when:** Stop silences playback immediately from the user's perspective, and no stale audio plays afterward, including when a new turn starts immediately.

## 4. Add voice input and user barge-in

- [ ] Capture microphone audio with explicit user permission and send it to a speech-to-text component. Select and test an STT implementation separately from OmniVoice, which is used for output.
- [ ] Add voice activity detection (VAD) and end-of-utterance detection. Tune silence duration and confidence thresholds with real recordings; use configurable initial values rather than assuming a universal threshold.
- [ ] When genuine user speech starts during assistant playback, stop playback locally and cancel the old turn. Use echo cancellation or another tested approach to prevent the assistant's own voice from falsely triggering interruption.
- [ ] Show listening, transcription, thinking and speaking states. Let the user correct a transcript or switch back to text when recognition fails.
- [ ] Decide what conversation history records after interruption: preserve the user's next message and distinguish generated text from what was actually played.

**Done when:** a user can speak a request, hear a response, interrupt it by speaking, and receive a response to the new request without old audio returning.

## 5. Allow the assistant to interrupt the user

This is a separate, optional feature from user barge-in. First make interruption by the user dependable.

- [ ] Define when interruption is welcome, such as a long pause or a user-enabled conversational mode, and when it is not. Provide an off switch and a conservative default.
- [ ] Design how partial transcripts, VAD and timing signals reach the decision component. Decide whether a lightweight rule or a model makes the decision; measure its latency and false positives.
- [ ] When the assistant takes the floor, visibly indicate the transition and handle overlapping microphone input without losing it. Let the user immediately regain the floor.
- [ ] Test with pauses, hesitation, background noise and deliberate interruptions; tune thresholds from observations.

**Done when:** the assistant can take a turn in explicitly enabled situations without routinely cutting the user off, and the user can stop it immediately.

## 6. End-to-end hardening

- [ ] Test ordering, cancellation races, late audio, reconnects, long responses, speech recognition errors and unavailable Codex or OmniVoice services.
- [ ] Measure first-text latency, first-audio latency, interruption-to-silence and recognition quality on the target machine. Record test conditions and tune buffering based on results.
- [ ] Document local startup, configuration, component dependencies and troubleshooting. Avoid logging sensitive conversation or audio by default.
- [ ] Revisit session isolation, authentication, resource limits and transport security before exposing the service beyond the local machine.

## Suggested first milestone

Complete steps 1–3 with typed input and a Stop button. This validates the Codex → text → OmniVoice → playback pipeline and its cancellation behavior before adding the extra complexity of microphone input and turn-taking.
