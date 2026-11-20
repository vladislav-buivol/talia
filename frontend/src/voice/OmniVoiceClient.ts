import type { ChatMessage } from "../protocol";

export async function requestSpeech(
  text: string, messages: readonly ChatMessage[], signal: AbortSignal,
): Promise<ArrayBuffer> {
  const context = messages.slice(-8).map(({ role, text: message }) => ({ role, text: message.slice(0, 2000) }));
  const response = await fetch("/api/tts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text, context }),
    signal,
  });
  if (!response.ok || !response.headers.get("Content-Type")?.startsWith("audio/wav")) {
    throw new Error("OmniVoice unavailable");
  }
  return response.arrayBuffer();
}
