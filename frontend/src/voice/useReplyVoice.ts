import { useCallback, useEffect, useRef, useState } from "react";
import { AudioPlayback } from "./AudioPlayback";
import { replyAcknowledgement, spokenLanguage } from "./replyAcknowledgement";
import {
  requestPlan, requestSpeech, requestVoiceSettings, uploadVoice, type InterruptedSpeech,
  type PlannedSegment, type SpeechSegment, type VoiceId,
} from "./SpeechClient";
import type { ChatMessage } from "../protocol";

export type ReplyVoice = ReturnType<typeof useReplyVoice>;

const storageKey = "talia.voice";
const speechTempo: Record<SpeechSegment["emotion"], number> = {
  neutral: 1, happy: 1.05, surprised: 1.04, relaxed: 0.98, concerned: 0.96, sad: 0.94, angry: 1.04,
};

type QueuedSegment = {
  segment: PlannedSegment;
  buffer?: AudioBuffer;
  offset?: number;
};

type SpeechRun = { queue: QueuedSegment[]; index: number; preview: boolean };
type Interruption = { context: InterruptedSpeech; queue: QueuedSegment[] };
type Progress = { elapsed: number; duration: number; index: number; total: number };
type AudioResult = { buffer: AudioBuffer } | { error: unknown };

export function useReplyVoice(onPlanRequested: () => void) {
  const [player] = useState(() => new AudioPlayback());
  const [cuePlayer] = useState(() => new AudioPlayback());
  const [analyser] = useState(() => ({ get current() { return player.analyser.current ?? cuePlayer.analyser.current; } }));
  const cueTimer = useRef<number | undefined>(undefined);
  const cachedCues = useRef(new Map<string, AudioBuffer>());
  const [cuePlaying, setCuePlaying] = useState(false);
  const enabled = useRef(true);
  const planning = useRef<AbortController | undefined>(undefined);
  const active = useRef<SpeechRun | undefined>(undefined);
  const interrupted = useRef<Interruption | undefined>(undefined);
  const selectedVoice = useRef<VoiceId>("default");
  const [voiceId, setVoiceId] = useState(selectedVoice.current);
  const [customName, setCustomName] = useState<string | null>(null);
  const [outdatedService, setOutdatedService] = useState(false);
  const [uploading, setUploading] = useState(false);
  const uploadInProgress = useRef(false);
  const [voiceEnabled, setVoiceEnabled] = useState(enabled.current);
  const [voiceStatus, setVoiceStatus] = useState<"idle" | "preparing" | "speaking">("idle");
  const [voiceError, setVoiceError] = useState("");
  const [reaction, setReaction] = useState<SpeechSegment | null>(null);
  const [animation, setAnimation] = useState<{ id: string } | null>(null);
  const [progress, setProgress] = useState<Progress | null>(null);

  const cancelCueTimer = useCallback(() => {
    window.clearTimeout(cueTimer.current);
    cueTimer.current = undefined;
  }, []);

  const stopCue = useCallback(() => {
    cancelCueTimer();
    cuePlayer.stop();
    setCuePlaying(false);
  }, [cancelCueTimer, cuePlayer]);

  const resetAudio = useCallback((clearInterruption = true) => {
    active.current = undefined;
    if (clearInterruption) interrupted.current = undefined;
    player.stop();
    setVoiceStatus("idle");
    setReaction(null);
    setProgress(null);
  }, [player]);

  const stopAnimation = useCallback(() => setAnimation(null), []);

  const reset = useCallback(() => {
    planning.current?.abort();
    stopAnimation();
    stopCue();
    resetAudio();
  }, [resetAudio, stopAnimation, stopCue]);

  function stopSpeech() {
    const run = active.current;
    if (run && run.index < run.queue.length) {
      const current = run.queue[run.index];
      const elapsed = Math.max(player.position(), current.offset ?? 0);
      const duration = current.buffer?.duration ?? 0;
      const next = duration > 0 && elapsed >= duration ? run.index + 1 : run.index;
      const queue = run.queue.slice(next);
      if (queue.length && !run.preview) {
        if (next === run.index) queue[0] = { ...current, offset: elapsed };
        interrupted.current = {
          queue,
          context: {
            heard: run.queue.slice(0, next).slice(-16).map(entry => entry.segment.text.slice(0, 600)),
            current: queue[0].segment.text.slice(0, 600),
            played_seconds: queue[0].offset ?? 0,
            duration_seconds: queue[0].buffer?.duration ?? 0,
            remaining: queue.slice(1, 17).map(entry => entry.segment.text.slice(0, 600)),
          },
        };
      }
      setProgress(duration ? { elapsed, duration, index: run.index, total: run.queue.length } : null);
    }
    active.current = undefined;
    player.stop();
    setVoiceStatus("idle");
    setReaction(null);
  }

  function stop() {
    stopCue();
    stopSpeech();
  }

  function stopReply() {
    planning.current?.abort();
    stopAnimation();
    stop();
  }

  useEffect(() => () => {
    planning.current?.abort();
    cancelCueTimer();
    cuePlayer.dispose();
    player.dispose();
  }, [cancelCueTimer, cuePlayer, player]);

  useEffect(() => {
    if (!voiceEnabled) return;

    function removeListeners() {
      window.removeEventListener("click", unlock);
      window.removeEventListener("keydown", unlock);
    }

    function unlock() {
      if (!enabled.current) return;
      void Promise.all([player.enable(), cuePlayer.enable()]).then(([ready, cueReady]) => {
        if (ready && cueReady) removeListeners();
      }).catch(() => {
        // Try again on the next interaction if the browser blocked audio.
      });
    }

    window.addEventListener("click", unlock);
    window.addEventListener("keydown", unlock);
    return removeListeners;
  }, [cuePlayer, player, voiceEnabled]);

  useEffect(() => {
    const load = new AbortController();
    void requestVoiceSettings(load.signal).then(settings => {
      if (load.signal.aborted || uploadInProgress.current) return;
      cachedCues.current.clear();
      setOutdatedService(settings === null);
      setCustomName(settings?.customName ?? null);
      const id = settings?.customName && localStorage.getItem(storageKey) === "custom" ? "custom" : "default";
      selectedVoice.current = id;
      setVoiceId(id);
    }).catch(() => {});
    return () => load.abort();
  }, [voiceEnabled]);

  useEffect(() => {
    if (voiceStatus !== "speaking") return;
    const timer = window.setInterval(() => {
      const run = active.current;
      const duration = run?.queue[run.index]?.buffer?.duration;
      if (run && duration) setProgress({ elapsed: player.position(), duration, index: run.index, total: run.queue.length });
    }, 100);
    return () => window.clearInterval(timer);
  }, [player, voiceStatus]);

  function selectVoice(id: VoiceId) {
    if (selectedVoice.current !== id) {
      stopCue();
      resetAudio();
    }
    selectedVoice.current = id;
    setVoiceId(id);
    localStorage.setItem(storageKey, id);
  }

  async function saveCustomVoice(file: File) {
    if (uploadInProgress.current) return;
    uploadInProgress.current = true;
    setUploading(true);
    setVoiceError("");
    try {
      const settings = await uploadVoice(file);
      cachedCues.current.clear();
      resetAudio();
      setOutdatedService(false);
      setCustomName(settings.customName);
      selectVoice("custom");
    } catch (error) {
      setVoiceError(error instanceof Error ? error.message : "Could not save the recording.");
    } finally {
      uploadInProgress.current = false;
      setUploading(false);
    }
  }

  async function toggleVoice() {
    setVoiceError("");
    if (enabled.current) {
      enabled.current = false;
      setVoiceEnabled(false);
      stopCue();
      resetAudio();
      return;
    }
    try {
      const [ready] = await Promise.all([player.enable(), cuePlayer.enable()]);
      if (ready) {
        enabled.current = true;
        setVoiceEnabled(true);
      }
    } catch {
      setVoiceError("Could not enable audio in this browser.");
    }
  }

  async function playCue(text: string, language: string, cache = false) {
    cancelCueTimer();
    if (!enabled.current || !text.trim()) return;
    const signal = cuePlayer.begin();
    const voice = selectedVoice.current;
    const key = `${voice}:${language}:${text}`;
    try {
      if (!await cuePlayer.enable() || signal.aborted) return;
      let buffer = cache ? cachedCues.current.get(key) : undefined;
      if (!buffer) {
        const bytes = await requestSpeech({ text, language, voice }, signal);
        if (signal.aborted) return;
        buffer = await cuePlayer.decode(bytes);
        if (signal.aborted) return;
        if (cache) cachedCues.current.set(key, buffer);
      }
      setCuePlaying(true);
      await cuePlayer.play(buffer, signal);
    } catch (error) {
      if (!signal.aborted) console.debug("Could not play acknowledgement:", error);
    } finally {
      if (!signal.aborted) setCuePlaying(false);
    }
  }

  function acknowledge(text: string) {
    if (!enabled.current) return;
    const cue = replyAcknowledgement(text);
    cueTimer.current = window.setTimeout(() => void playCue(cue.text, cue.language, true), 250);
  }

  function updateProgress(text: string) {
    if (cueTimer.current !== undefined) void playCue(text, spokenLanguage(text));
  }

  async function speak(text: string, context: readonly ChatMessage[], preview = false) {
    if (!text.trim()) return;
    planning.current?.abort();
    stopAnimation();
    cancelCueTimer();
    if (preview) stopCue();
    stopSpeech();
    const previewAudio = preview ? player.enable().catch(() => false) : undefined;
    const planRequest = new AbortController();
    planning.current = planRequest;
    const previous = interrupted.current;
    const signal = player.begin();
    setVoiceError("");
    if (enabled.current || preview) setVoiceStatus("preparing");
    setProgress(null);

    async function prepare(entry: QueuedSegment): Promise<AudioResult> {
      try {
        const buffer = entry.buffer ?? await player.decode(await requestSpeech(entry.segment, signal));
        return { buffer };
      } catch (error) {
        return { error };
      }
    }

    try {
      onPlanRequested();
      const plan = await requestPlan(text, context, preview ? undefined : previous?.context, planRequest.signal);
      if (planRequest.signal.aborted) return;
      if (plan.animation && !preview) setAnimation({ id: plan.animation });
      if ((!enabled.current && !preview) || signal.aborted) return;

      const { language, profile } = plan;
      const voice = selectedVoice.current;
      const queue: QueuedSegment[] = plan.segments.map(segment => ({ segment: { ...segment, language, profile, voice } }));
      if (!preview && previous && plan.resume_interrupted) queue.push(...previous.queue);
      if (!preview) interrupted.current = undefined;
      if (!queue.length) {
        resetAudio(!preview);
        if (preview) setVoiceError("Enter text that can be spoken.");
        return;
      }
      setVoiceStatus("preparing");
      const ready = await (previewAudio ?? player.enable());
      if (signal.aborted) return;
      if (!ready) throw new Error("Could not enable audio in this browser");
      const run = { queue, index: 0, preview };
      active.current = run;
      let nextAudio: Promise<AudioResult> | undefined = prepare(queue[0]);
      for (const entry of queue) {
        const result = await (nextAudio ?? prepare(entry));
        if (signal.aborted) return;
        if ("error" in result) throw result.error;
        entry.buffer = result.buffer;
        if (run.index === 0) stopCue();
        setReaction(entry.segment);
        setProgress({ elapsed: entry.offset ?? 0, duration: entry.buffer.duration, index: run.index, total: queue.length });
        setVoiceStatus("speaking");
        const playback = player.play(entry.buffer, signal, entry.offset, speechTempo[entry.segment.emotion]);
        const next = queue[run.index + 1];
        nextAudio = next && prepare(next);
        if (!await playback || signal.aborted) return;
        run.index += 1;
        if (next) {
          setReaction(null);
          setVoiceStatus("preparing");
        }
      }
      resetAudio(!preview);
    } catch (error) {
      if (planRequest.signal.aborted || signal.aborted) return;
      console.error("Could not play speech:", error);
      stop();
      if (enabled.current || preview) setVoiceError("Could not play audio. Check that the speech service is running.");
    } finally {
      if (planning.current === planRequest) planning.current = undefined;
    }
  }

  return {
    voiceId, customName, selectVoice, uploading, outdatedService, saveCustomVoice, voiceEnabled, voiceStatus, voiceError, toggleVoice,
    speak, acknowledge, updateProgress, cuePlaying, stop, stopReply, reset, reaction, animation, stopAnimation, progress, analyser,
  };
}
