import { useCallback, useState } from "react";
import type { ReplyVoice } from "../voice/useReplyVoice";
import { animations, originalDance, poses, type AvatarMotion } from "./AvatarAnimationCatalog";
import TaliaAvatar from "./TaliaAvatar";

const motionOptions = [...animations, ...poses];

export default function AvatarPanel({ thinking, chatNod, voice }: {
  thinking: boolean;
  chatNod: number;
  voice: ReplyVoice;
}) {
  const [testThinking, setTestThinking] = useState(false);
  const [testNod, setTestNod] = useState(0);
  const [selectedMotion, setSelectedMotion] = useState(0);
  const [request, setRequest] = useState<{ motion: AvatarMotion } | null>(null);
  const stopMotion = useCallback(() => setRequest(null), []);
  const speaking = voice.voiceStatus === "speaking";
  const preparing = voice.voiceStatus === "preparing";
  const mode = speaking ? "speaking" : thinking || testThinking || preparing ? "thinking" : "idle";

  return (
    <aside className="talia-panel" aria-label="Talia avatar and voice">
      <div className="talia-panel-header">
        <strong>Talia</strong>
        <button type="button" className="button subtle" aria-pressed={voice.voiceEnabled} onClick={voice.toggleVoice}>
          {voice.voiceEnabled ? "Voice on" : "Enable voice"}
        </button>
      </div>
      <TaliaAvatar
        mode={mode}
        nod={chatNod + testNod}
        analyser={voice.analyser}
        motionRequest={request}
        onMotionEnd={stopMotion}
      />
      {(mode !== "idle" || voice.voiceError) && (
        <p className="talia-voice-status" role="status">
          {voice.voiceError || (speaking ? "Speaking…" : preparing ? "Preparing audio…" : "Thinking…")}
        </p>
      )}
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
          <button type="button" className="tool-button" onClick={() => setRequest({ motion: originalDance })}>Dance</button>
          <label className="tool-button avatar-audio-picker">
            Play audio
            <input type="file" accept="audio/*" onChange={event => {
              const file = event.currentTarget.files?.[0];
              event.currentTarget.value = "";
              if (file) void voice.playFile(file);
            }} />
          </label>
          {voice.voiceStatus !== "idle" && <button type="button" className="tool-button" onClick={voice.stop}>Stop audio</button>}
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
          <button type="button" className="tool-button" onClick={() => setRequest({ motion: motionOptions[selectedMotion].motion })}>Play</button>
          {request && <button type="button" className="tool-button" onClick={stopMotion}>Stop motion</button>}
        </div>
      </details>
    </aside>
  );
}
