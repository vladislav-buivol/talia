import { useEffect, useRef, useState, type RefObject } from "react";
import { VRMUtils, type VRM } from "@pixiv/three-vrm";
import { defaultAnimationPath, type AvatarMotion } from "./AvatarAnimationCatalog";
import { AvatarFace } from "./AvatarFace";
import { AvatarMotionController, type AvatarMode } from "./AvatarMotionController";
import { AvatarScene } from "./AvatarScene";
import { createTaliaDance } from "./TaliaDance";
import { VrmAssetLoader } from "./VrmAssetLoader";

type AvatarProps = {
  mode: AvatarMode;
  nod: number;
  analyser: RefObject<AnalyserNode | null>;
  motionRequest: { motion: AvatarMotion } | null;
  onMotionEnd: () => void;
};

type AvatarRuntime = {
  scene: AvatarScene;
  loader: VrmAssetLoader;
  model: VRM;
  controller: AvatarMotionController;
};

export default function TaliaAvatar({ mode, nod, analyser, motionRequest, onMotionEnd }: AvatarProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const runtime = useRef<AvatarRuntime | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "unavailable">("loading");
  const [motionError, setMotionError] = useState("");
  const [loadingMotion, setLoadingMotion] = useState(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    const viewport = canvas?.parentElement;
    if (!canvas || !viewport) return;

    let scene: AvatarScene;
    try {
      scene = new AvatarScene(canvas, viewport);
    } catch (error) {
      console.error("Could not create avatar scene:", error);
      setStatus("unavailable");
      return;
    }

    const loader = new VrmAssetLoader();
    const loading = new AbortController();
    let model: VRM | undefined;

    async function start() {
      const avatar = await loader.loadAvatar(loading.signal);
      model = avatar;
      const controller = new AvatarMotionController(avatar);
      const face = new AvatarFace(avatar.expressionManager);
      avatar.update(0);
      scene.attach(avatar);
      runtime.current = { scene, loader, model: avatar, controller };
      let previous = performance.now();
      scene.start(now => {
        const dt = Math.min(Math.max((now - previous) / 1000, 0), 0.05);
        previous = now;
        if (controller.update(dt)) onMotionEnd();
        face.update(dt, analyser.current);
        avatar.update(dt);
        face.restoreAnimatedMouth();
      });
      setStatus("ready");
      void loader.loadMotion(defaultAnimationPath, avatar, loading.signal)
        .then(clip => {
          if (!loading.signal.aborted) controller.setDefaultAnimation(clip);
        })
        .catch(error => {
          if (!loading.signal.aborted) console.error("Could not load Catwalk Idle:", error);
        });
    }

    void start().catch(error => {
      if (loading.signal.aborted) return;
      console.error("Could not load Talia avatar:", error);
      setStatus("unavailable");
    });

    return () => {
      loading.abort();
      runtime.current?.controller.dispose();
      runtime.current = null;
      scene.dispose();
      if (model) VRMUtils.deepDispose(model.scene);
    };
  }, [analyser, onMotionEnd]);

  useEffect(() => {
    if (runtime.current) runtime.current.controller.mode = mode;
  }, [mode, status]);

  useEffect(() => {
    if (nod) runtime.current?.controller.nod();
  }, [nod, status]);

  useEffect(() => {
    const avatar = runtime.current;
    if (!avatar || status !== "ready" || !motionRequest) {
      setLoadingMotion(false);
      return;
    }

    const { motion } = motionRequest;
    const loading = new AbortController();
    setLoadingMotion(true);
    setMotionError("");

    async function play({ scene, loader, model, controller }: AvatarRuntime) {
      const clip = motion.kind === "dance"
        ? createTaliaDance(model)
        : await loader.loadMotion(motion.path, model, loading.signal);
      if (loading.signal.aborted) return;
      controller.play(clip, motion.kind === "pose");
      scene.resetView(true);
      setLoadingMotion(false);
    }

    void play(avatar).catch(error => {
      if (loading.signal.aborted) return;
      console.error("Could not play avatar motion:", error);
      setLoadingMotion(false);
      setMotionError("Could not play this animation.");
      onMotionEnd();
    });

    return () => {
      loading.abort();
      avatar.controller.stop();
    };
  }, [motionRequest, onMotionEnd, status]);

  return (
    <>
      <div className="avatar-viewport" data-avatar-status={status}>
        <canvas ref={canvasRef} role="img" aria-label="Talia's 3D avatar" />
        {status !== "ready" && <p role="status">{status === "loading" ? "Loading Talia…" : "3D avatar unavailable"}</p>}
      </div>
      <div className="avatar-view-controls">
        <span>Drag to rotate · Right-drag to pan · Scroll to zoom</span>
        <button type="button" className="tool-button" disabled={status !== "ready"} onClick={() => runtime.current?.scene.resetView(Boolean(motionRequest))}>
          Reset view
        </button>
      </div>
      {loadingMotion && <p className="talia-voice-status" role="status">Loading animation…</p>}
      {motionError && <p className="talia-voice-status" role="status">{motionError}</p>}
    </>
  );
}
