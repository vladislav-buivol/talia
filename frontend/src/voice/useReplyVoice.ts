import { useCallback, useEffect, useRef, useState } from "react";
import { AudioPlayback } from "./AudioPlayback";
import { requestSpeech } from "./OmniVoiceClient";
import type { ChatMessage } from "../protocol";

export type ReplyVoice = ReturnType<typeof useReplyVoice>;

export function useReplyVoice() {
  const [player] = useState(() => new AudioPlayback());
  const enabled = useRef(false);
  const [voiceEnabled, setVoiceEnabled] = useState(false);
  const [voiceStatus, setVoiceStatus] = useState<"idle" | "preparing" | "speaking">("idle");
  const [voiceError, setVoiceError] = useState("");

  const stop = useCallback(() => {
    player.stop();
    setVoiceStatus("idle");
  }, [player]);

  useEffect(() => () => player.dispose(), [player]);

  async function toggleVoice() {
    setVoiceError("");
    if (enabled.current) {
      enabled.current = false;
      setVoiceEnabled(false);
      stop();
      return;
    }

    try {
      if (await player.enable()) {
        enabled.current = true;
        setVoiceEnabled(true);
      }
    } catch {
      setVoiceError("Could not enable audio in this browser.");
    }
  }

  async function play(read: (signal: AbortSignal) => Promise<ArrayBuffer>, errorMessage: string) {
    const signal = player.begin();
    setVoiceError("");
    setVoiceStatus("preparing");
    try {
      const buffer = await read(signal);
      if (await player.play(buffer, signal, () => setVoiceStatus("idle"))) {
        setVoiceStatus("speaking");
      }
    } catch (error) {
      if (signal.aborted) return;
      stop();
      console.error("Could not play audio:", error);
      setVoiceError(errorMessage);
    }
  }

  function speak(text: string, context: readonly ChatMessage[]) {
    if (!enabled.current || !text.trim()) return;
    return play(signal => requestSpeech(text, context, signal), "Voice unavailable. Check that the OmniVoice service is running.");
  }

  function playFile(file: File) {
    return play(async () => {
      await player.enable();
      return file.arrayBuffer();
    }, "Could not play this audio file.");
  }

  return { voiceEnabled, voiceStatus, voiceError, toggleVoice, speak, playFile, stop, analyser: player.analyser };
}
