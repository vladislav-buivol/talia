import type { ChatMessage } from "../protocol";

export type SpeechSegment = {
  text: string;
  emotion: "neutral" | "happy" | "sad" | "surprised" | "concerned" | "relaxed" | "angry";
  motion: "none" | "nod" | "tilt" | "shake" | "bounce";
};

export type VoiceId = "default" | "custom";
type SpeechLanguage = { language?: string; profile?: { language: string | null } };
export type SpeechPlan = SpeechLanguage & { segments: SpeechSegment[]; resume_interrupted: boolean; animation?: string | null };
export type PlannedSegment = SpeechSegment & SpeechLanguage & { voice: VoiceId };
type VoiceSettings = { customName: string | null };
const voicePath = "/api/tts/voice";

export type InterruptedSpeech = {
  heard: string[];
  current: string;
  played_seconds: number;
  duration_seconds: number;
  remaining: string[];
};

export async function requestVoiceSettings(signal: AbortSignal): Promise<VoiceSettings | null> {
  const response = await fetch(voicePath, { signal });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error("Voice settings unavailable");
  return response.json();
}

export async function uploadVoice(file: File): Promise<VoiceSettings> {
  const response = await fetch(voicePath, {
    method: "PUT",
    headers: { "Content-Type": "application/octet-stream", "X-Voice-File": encodeURIComponent(file.name) },
    body: file,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(typeof error?.detail === "string" ? error.detail : "Could not save the recording.");
  }
  return response.json();
}

export async function requestPlan(
  text: string, messages: readonly ChatMessage[], interrupted: InterruptedSpeech | undefined, signal: AbortSignal,
): Promise<SpeechPlan> {
  const context = messages.slice(-8).map(({ role, text: message }) => ({ role, text: message.slice(0, 2000) }));
  const response = await post("/api/tts/plan", { text, context, interrupted }, signal);
  return response.json();
}

type SpeechInput = Pick<PlannedSegment, "text" | "voice" | "language" | "profile">;

export async function requestSpeech(segment: SpeechInput, signal: AbortSignal): Promise<ArrayBuffer> {
  const { text, voice, language, profile } = segment;
  const response = await post("/api/tts", {
    text, voice: voice === "custom" ? voice : undefined,
    language, profile: language === undefined ? profile : undefined,
  }, signal);
  if (!response.headers.get("Content-Type")?.startsWith("audio/wav")) throw new Error("Invalid audio response");
  return response.arrayBuffer();
}

async function post(path: string, body: object, signal: AbortSignal) {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok) throw new Error(`Speech request failed (${response.status})`);
  return response;
}
