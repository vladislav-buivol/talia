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
   On Windows you can instead run `codex-integration\start-ui.bat` from the
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
