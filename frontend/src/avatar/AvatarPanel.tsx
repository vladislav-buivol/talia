import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { ReplyVoice } from "../voice/useReplyVoice";
import { animations, chatAnimation, originalDance, poses, type AvatarMotion } from "./AvatarAnimationCatalog";
import TaliaAvatar from "./TaliaAvatar";

const motionOptions = [...animations, ...poses];

export default function AvatarPanel({ thinking, addressed, voice }: {
  thinking: boolean;
  addressed: number;
  voice: ReplyVoice;
}) {
  const [testThinking, setTestThinking] = useState(false);
  const [testNod, setTestNod] = useState(0);
  const [selectedMotion, setSelectedMotion] = useState(0);
  const [request, setRequest] = useState<{ motion: AvatarMotion } | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [popupTarget, setPopupTarget] = useState<HTMLElement | null>(null);
  const [popupError, setPopupError] = useState("");
  const [testText, setTestText] = useState("");
  const popup = useRef<Window | null>(null);
  const { animation, stopAnimation } = voice;
  const chatMotion = useMemo(() => {
    const motion = animation && chatAnimation(animation.id);
    return motion ? { motion } : null;
  }, [animation]);
  const stopMotion = useCallback(() => {
    stopAnimation();
    setRequest(null);
  }, [stopAnimation]);

  function previewMotion(motion: AvatarMotion) {
    stopAnimation();
    setRequest({ motion });
  }
  const speaking = voice.voiceStatus === "speaking" || voice.cuePlaying;
  const preparing = voice.voiceStatus === "preparing";
  const mode = speaking ? "speaking" : thinking || testThinking || preparing ? "thinking" : "idle";

  useEffect(() => () => popup.current?.close(), []);

  useEffect(() => {
    if (!expanded) return;
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") setExpanded(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [expanded]);

  function returnAvatar() {
    const separate = popup.current;
    popup.current = null;
    setPopupTarget(null);
    separate?.close();
  }

  function openAvatarWindow() {
    const separate = window.open("", "_blank", "popup,width=460,height=640,resizable=yes");
    if (!separate) {
      setPopupError("Allow pop-ups for this page to open the avatar window.");
      return;
    }
    const page = separate.document;
    page.title = "Talia avatar";
    page.documentElement.lang = "en";
    for (const stylesheet of document.querySelectorAll<HTMLStyleElement | HTMLLinkElement>('style, link[rel="stylesheet"]')) {
      const copy = page.importNode(stylesheet, true);
      if (copy.tagName === "LINK") (copy as HTMLLinkElement).href = (stylesheet as HTMLLinkElement).href;
      page.head.append(copy);
    }
    const target = page.createElement("main");
    target.className = "avatar-popout";
    page.body.append(target);
    popup.current = separate;
    separate.addEventListener("pagehide", () => {
      if (popup.current === separate) returnAvatar();
    }, { once: true });
    setPopupError("");
    setExpanded(false);
    setPopupTarget(target);
  }

  const avatar = (
    <TaliaAvatar mode={mode} nod={testNod + addressed} analyser={voice.analyser} reaction={voice.reaction}
      motionRequest={chatMotion ?? request} onMotionEnd={stopMotion} />
  );

  return (
    <aside className={`talia-panel${expanded ? " avatar-expanded" : ""}`} aria-label="Talia avatar and voice">
      <div className="talia-panel-header">
        <strong>Talia</strong>
        <div className="avatar-window-actions">
          {chatMotion && <button type="button" className="tool-button" onClick={stopMotion}>Stop animation</button>}
          {popupTarget ? <button type="button" className="tool-button" onClick={returnAvatar}>Dock avatar</button> : <>
            <button type="button" className="tool-button" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
              {expanded ? "Restore size" : "Expand"}
            </button>
            <button type="button" className="tool-button" onClick={openAvatarWindow}>Pop out</button>
          </>}
        </div>
        <button type="button" className="button subtle" aria-pressed={voice.voiceEnabled} onClick={voice.toggleVoice}>
          {voice.voiceEnabled ? "Voice on" : "Enable voice"}
        </button>
      </div>
      {popupTarget ? createPortal(<>
        <div className="talia-panel-header"><strong>Talia</strong>
          {chatMotion && <button type="button" className="tool-button" onClick={stopMotion}>Stop animation</button>}
          <button type="button" className="tool-button" onClick={returnAvatar}>Dock avatar</button></div>
        {avatar}
      </>, popupTarget) : avatar}
      {popupError && <p role="status" className="talia-voice-status">{popupError}</p>}
      <div className="talia-voice-controls">
        <label>Voice
          <select value={voice.voiceId} disabled={voice.uploading}
            onChange={event => voice.selectVoice(event.target.value === "custom" ? "custom" : "default")}>
            <option value="default">Default voice</option>
            {voice.customName && <option value="custom">{voice.customName}</option>}
          </select>
        </label>
        <label className="tool-button avatar-audio-picker">
          {voice.uploading ? "Preparing recording…" : voice.customName ? "Replace WAV / MP3" : "Upload WAV / MP3"}
          <input type="file" accept=".wav,.mp3,audio/wav,audio/mpeg" disabled={voice.uploading || voice.outdatedService} onChange={event => {
            const file = event.currentTarget.files?.[0];
            event.currentTarget.value = "";
            if (file) void voice.saveCustomVoice(file);
          }} />
        </label>
        {(voice.voiceStatus !== "idle" || voice.cuePlaying) && <button type="button" className="tool-button" onClick={voice.stop}>Stop audio</button>}
        {voice.progress && (
          <label className="talia-speech-progress">
            <span>Phrase {voice.progress.index + 1}/{voice.progress.total} · {voice.progress.elapsed.toFixed(1)} / {voice.progress.duration.toFixed(1)} s</span>
            <progress max={voice.progress.duration} value={voice.progress.elapsed} />
          </label>
        )}
      </div>
      {voice.outdatedService && <p className="talia-voice-status" role="status">Restart the speech service and reload this page to upload a recording.</p>}
      {(mode !== "idle" || voice.voiceError) && (
        <p className="talia-voice-status" role="status">
          {voice.voiceError || (speaking ? "Speaking…" : preparing ? "Preparing audio…" : "Thinking…")}
        </p>
      )}
      <details className="avatar-test">
        <summary>Test audio</summary>
        <form className="audio-test-form" onSubmit={event => {
          event.preventDefault();
          void voice.speak(testText, [], true);
        }}>
          <label>Enter text
            <textarea value={testText} onChange={event => setTestText(event.target.value)} rows={3} maxLength={2000} />
          </label>
          <button type="submit" className="tool-button" disabled={!testText.trim()}>Play test</button>
        </form>
      </details>
      <details className="avatar-test">
        <summary>Test avatar</summary>
        <div className="avatar-test-actions">
          <button type="button" className="tool-button" onClick={() => {
            stopMotion();
            setTestNod(previous => previous + 1);
          }}>Nod</button>
          <button type="button" className="tool-button" aria-pressed={testThinking} onClick={() => {
            stopMotion();
            setTestThinking(previous => !previous);
          }}>
            {testThinking ? "Stop thinking" : "Thinking"}
          </button>
          <button type="button" className="tool-button" onClick={() => previewMotion(originalDance)}>Dance</button>
        </div>
        <div className="avatar-motion-picker">
          <select aria-label="Animation or pose" value={selectedMotion} onChange={event => setSelectedMotion(Number(event.target.value))}>
            <optgroup label="Animations">
              {animations.map(({ label }, index) => <option key={label} value={index}>{label}</option>)}
            </optgroup>
            <optgroup label="Poses">
              {poses.map(({ label }, index) => <option key={label} value={animations.length + index}>{label}</option>)}
            </optgroup>
          </select>
          <button type="button" className="tool-button" onClick={() => previewMotion(motionOptions[selectedMotion].motion)}>Play</button>
          {request && <button type="button" className="tool-button" onClick={stopMotion}>Stop motion</button>}
        </div>
      </details>
    </aside>
  );
}
