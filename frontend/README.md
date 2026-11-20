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

To check the TypeScript and build the frontend, run `npm run build` (or
`pnpm run build`).

## Avatar and voice

The React component at `src/avatar/TaliaAvatar.tsx` renders
`public/models/talia.vrm` with Three.js. It blinks in idle, nods when the
chat confirms a new user message, and changes its head movement between
thinking and speaking.

Drag the avatar viewport to orbit, right-drag to pan, and scroll to zoom.
On touch screens, drag to orbit and use two fingers to pan or zoom.
**Reset view** restores the initial framing for the current activity.

Catwalk Idle loops by default and resumes after a preview. Open **Test avatar**
to preview the original **Dance** or choose a VRMA clip or pose and click
**Play**. Clips play once and poses hold until you click **Stop motion** or
start another movement. Select **Play audio** during a preview to test lip sync
with a local audio file. Lip movements follow the audio level and blend five
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
Hugging Face. In the avatar panel, click **Enable voice** and send a message.
Once the reply is complete, its audio plays through the browser. Click
**Voice on** again to mute and halt local playback.

The service uses `voice.wav` in the project root as its voice reference.
Its spoken words are stored in `tts/voice.txt`. To change voices, replace
the reference and transcript together, then restart the service. The paths can
also be configured with `TALIA_VOICE_AUDIO` and `TALIA_VOICE_TEXT`.

For each spoken reply, the TTS service uses the local `codex` CLI to run a
separate, ephemeral conversation with the reply and up to eight recent chat
messages. The result selects the spoken language and supported voice attributes.
The defaults are `female`, `young adult`, `moderate pitch` and automatic style.
For English, OmniVoice uses the selected accent or defaults to `russian accent`.
For Chinese it can use a supported regional dialect. An unknown language or an
unavailable Codex classifier falls back to Russian.
