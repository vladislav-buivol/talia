import type { VRM } from "@pixiv/three-vrm";
import { AnimationMixer, Euler, LoopOnce, LoopRepeat, MathUtils, Quaternion, type AnimationAction, type AnimationClip } from "three";

export type AvatarMode = "idle" | "thinking" | "speaking";

export class AvatarMotionController {
  private readonly mixer: AnimationMixer;
  private readonly head;
  private readonly leftArm;
  private readonly rightArm;
  private readonly animatedHead = new Quaternion();
  private readonly reaction = new Euler();
  private readonly reactionRotation = new Quaternion();
  private idleClip?: AnimationClip;
  private action?: AnimationAction;
  private actionKind?: "idle" | "clip" | "pose";
  private elapsed = 0;
  private nodStarted = -Infinity;
  mode: AvatarMode = "idle";

  constructor(private readonly model: VRM) {
    this.mixer = new AnimationMixer(model.scene);
    this.head = model.humanoid.getNormalizedBoneNode("head");
    this.leftArm = model.humanoid.getNormalizedBoneNode("leftUpperArm");
    this.rightArm = model.humanoid.getNormalizedBoneNode("rightUpperArm");
    this.applyRestPose();
  }

  nod() {
    this.nodStarted = this.elapsed;
  }

  play(clip: AnimationClip, holdPose: boolean) {
    this.startAction(clip, holdPose ? "pose" : "clip");
  }

  setDefaultAnimation(clip: AnimationClip) {
    this.idleClip = clip;
    if (!this.action || this.actionKind === "idle") this.startAction(clip, "idle");
  }

  stop() {
    if (this.actionKind === "idle") return;
    if (this.idleClip) this.startAction(this.idleClip, "idle");
    else this.clearAction();
  }

  dispose() {
    this.clearAction();
  }

  private startAction(clip: AnimationClip, kind: "idle" | "clip" | "pose") {
    this.clearAction();
    const loop = kind === "idle";
    const action = this.mixer.clipAction(clip).setLoop(loop ? LoopRepeat : LoopOnce, loop ? Infinity : 1);
    action.clampWhenFinished = !loop;
    action.play();
    action.paused = kind === "pose";
    this.action = action;
    this.actionKind = kind;
    this.mixer.update(0);
    if (this.head) this.animatedHead.copy(this.head.quaternion);
  }

  private clearAction() {
    if (!this.action) return;
    const clip = this.action.getClip();
    this.action.stop();
    this.mixer.uncacheAction(clip);
    this.action = undefined;
    this.actionKind = undefined;
    this.applyRestPose();
  }

  update(dt: number): boolean {
    this.elapsed += dt;
    if (this.action && this.actionKind !== "idle") {
      this.mixer.update(dt);
      if (this.actionKind === "pose" || this.action.isRunning()) return false;
      this.stop();
      return true;
    }

    // Restore the clip's head rotation before adding this frame's reaction.
    this.head?.quaternion.copy(this.animatedHead);
    this.mixer.update(dt);
    if (this.head) {
      this.animatedHead.copy(this.head.quaternion);
      const nodProgress = MathUtils.clamp((this.elapsed - this.nodStarted) / 0.48, 0, 1);
      const nodAngle = Math.sin(nodProgress * Math.PI) * 0.23;
      const thinking = this.mode === "thinking";
      const amplitude = this.mode === "speaking" ? 0.035 : 0.012;
      const pitch = (thinking ? -0.07 : 0) + Math.sin(this.elapsed * 2) * amplitude;
      this.reaction.x = MathUtils.damp(this.reaction.x, pitch + nodAngle, 16, dt);
      this.reaction.z = MathUtils.damp(this.reaction.z, thinking ? 0.065 : 0, 8, dt);
      this.head.quaternion.multiply(this.reactionRotation.setFromEuler(this.reaction));
    }
    return false;
  }

  private applyRestPose() {
    this.model.humanoid.resetNormalizedPose();
    if (this.leftArm) this.leftArm.rotation.z = -1.12;
    if (this.rightArm) this.rightArm.rotation.z = 1.12;
    if (this.head) this.animatedHead.copy(this.head.quaternion);
    this.reaction.set(0, 0, 0);
    this.nodStarted = -Infinity;
  }
}
