# Talia chat UI

This directory contains the React and TypeScript frontend. It communicates with
Talia through the WebSocket core protocol; it does not connect to Codex directly.

## Run locally

1. Install Node.js (20.19+ or 22.12+ recommended).
2. Start Codex App Server and the Talia Spring application with the `codex`
   profile. By default, Talia expects Codex at `ws://127.0.0.1:4222` and serves
   the chat endpoint at `ws://localhost:8080/ws/chat`.
3. In a separate terminal, run:

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

   Alternatively, use `pnpm install` and `pnpm dev` if you have pnpm installed.
   On Windows you can instead run `codex-integration\2_start-ui.bat` from the
   project root; it installs dependencies if needed and starts the UI.
4. Open the local address printed by Vite (normally http://localhost:5173) and
   click **Connect to Talia**.

Enter the Talia WebSocket URL in the connection card. The default is
`ws://localhost:8080/ws/chat`. The browser connects directly to that URL.
Talia permits the default local Vite origins; if your UI runs from another
origin, configure `talia.chat.allowed-origins` in the Codex profile.
Available actions and their changes come from the server's core protocol responses.
New chats use `gpt-5.6-luna`. Open **Logs** to see the destinations of sent
messages, WebSocket replies and their chat and turn IDs.

To check the TypeScript and build the frontend, run `npm run build` (or
`pnpm run build`).

## Avatar and voice

The React component at `src/avatar/TaliaAvatar.tsx` renders
`public/models/talia.vrm` with Three.js. It blinks in idle, shows a thinking
pose and follows the speech plan for facial expressions and gestures.

Drag the avatar viewport to orbit, right-drag to pan, and scroll to zoom.
On touch screens, drag to orbit and use two fingers to pan or zoom.
**Reset view** restores the initial framing for the current activity.
**Expand** enlarges the avatar within the page. **Pop out** opens a separate,
resizable window that you can move around the desktop. Use **Dock avatar**
to return the avatar to the page.

Try asking Talia to dance or do a backflip. The reply planner can start a full-body
animation even when voice is muted. **Stop animation** returns to the idle pose.
Catwalk Idle loops by default and resumes after an animation. Open **Test avatar**
to preview the original **Dance** or choose a VRMA clip or pose and click
**Play**. Clips play once and poses hold until you click **Stop motion** or
start another movement. Lip movements follow the audio level and blend five
vowel shapes based on the sound.

On Windows, run [3_start-omnivoice.bat](../codex-integration/3_start-omnivoice.bat)
to start the local TTS process. It uses the cached model and the Python at
`%USERPROFILE%\miniconda3\envs\omnivoice\python.exe` by default. Set
`TALIA_TTS_PYTHON` to another Python executable if your environment is elsewhere,
or set `HF_HUB_OFFLINE=0` before running the script if the model needs to download.

You can also start the local TTS process from the repository root in an
environment with OmniVoice, PyTorch, SoundFile, FastAPI and Uvicorn installed.
Use the [OmniVoice installation instructions](https://github.com/k2-fsa/OmniVoice#installation)
for the PyTorch build that matches your GPU.

```powershell
conda activate omnivoice
python tts/serve.py
```

The process listens on `127.0.0.1:8001`. Vite forwards `/api/tts` there
locally. You can set `TALIA_TTS_MODEL` to the path of an existing model
snapshot, or let OmniVoice load `k2-fsa/OmniVoice` from its cache or
Hugging Face. Voice is on by default. The browser activates audio when you
first interact with the page, such as when you connect to Talia. Once the
reply is complete, its audio plays through the browser. **Default voice** uses
the bundled voice recording. Use **Upload WAV / MP3** to choose your own
recording of clear speech. Uploaded recordings stay available until the
speech service restarts. You can also preconfigure your recording and its
transcript with `TALIA_VOICE_AUDIO` and `TALIA_VOICE_TEXT`.

The local `codex` CLI runs one ephemeral conversation per reply. It prepares
speakable phrases, detects the language and selects animations, expressions,
gestures and [OmniVoice tags](../omnivoice_ui_setup/omnivoice_emotion_tags.txt).
The speech service strips URLs before synthesis. If the planner is unavailable,
Talia reads cleaned text in the default language with neutral reactions.

Talia acknowledges new messages with a nod and a brief spoken cue while the
reply is in progress. Progress messages from Codex update the status below the
conversation. Open **Test audio**, enter text and click **Play test** to hear
the selected voice even when chat audio is muted.

**Stop audio** or sending a new message interrupts playback immediately. The
player tracks the current phrase and elapsed audio time. The next speech plan
can decide to continue the old recording from that position after the new reply.
Click **Voice on** to mute and clear the interrupted recording.
