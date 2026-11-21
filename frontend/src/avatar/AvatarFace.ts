import type { VRM } from "@pixiv/three-vrm";
import { MathUtils } from "three";
import type { SpeechSegment } from "../voice/SpeechClient";
import { LipSync, mouthShapes } from "./LipSync";

const emotionWeights = {
  happy: 0.76, sad: 0.54, surprised: 0.63, relaxed: 0.62, angry: 0.48,
};

export class AvatarFace {
  emotion: SpeechSegment["emotion"] = "neutral";
  private readonly lipSync = new LipSync();
  private elapsed = 0;
  private readonly animationWeights = new Map<string, number>();
  private readonly speechWeights = new Map<string, number>();

  constructor(private readonly expressions: VRM["expressionManager"]) {}

  restoreAnimatedExpressions() {
    for (const [name, weight] of this.animationWeights) this.expressions?.setValue(name, weight);
    this.animationWeights.clear();
  }

  private setExpression(name: string, weight: number) {
    const current = this.expressions?.getValue(name);
    if (current == null) return;
    this.animationWeights.set(name, current);
    this.expressions?.setValue(name, weight);
  }

  update(dt: number, analyser: AnalyserNode | null) {
    if (!this.expressions) return;
    this.elapsed += dt;
    const blinkTime = (this.elapsed + 1) % 4.3;
    this.expressions.setValue("blink", blinkTime < 0.17 ? Math.sin(blinkTime / 0.17 * Math.PI) : 0);
    for (const [name, strength] of Object.entries(emotionWeights)) {
      const target = this.emotion === name ? strength : this.emotion === "concerned" && name === "sad" ? 0.30 : 0;
      const weight = MathUtils.damp(this.speechWeights.get(name) ?? 0, target, 10, dt);
      this.speechWeights.set(name, weight);
      if (weight > 0.002) this.setExpression(name, Math.max(this.expressions.getValue(name) ?? 0, weight));
    }
    const mouthMoving = this.lipSync.update(dt, analyser);
    if (analyser || mouthMoving) {
      // Audio owns the mouth while the mixer continues to animate the avatar.
      for (const name of mouthShapes) {
        this.setExpression(name, this.lipSync.weights[name]);
      }
    }
  }
}
