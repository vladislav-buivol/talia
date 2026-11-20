import type { VRM } from "@pixiv/three-vrm";
import { LipSync, mouthShapes } from "./LipSync";

export class AvatarFace {
  private readonly lipSync = new LipSync();
  private elapsed = 0;
  private readonly animatedMouth = new Map<string, number>();

  constructor(private readonly expressions: VRM["expressionManager"]) {}

  restoreAnimatedMouth() {
    for (const [name, weight] of this.animatedMouth) this.expressions?.setValue(name, weight);
    this.animatedMouth.clear();
  }

  update(dt: number, analyser: AnalyserNode | null) {
    if (!this.expressions) return;
    this.elapsed += dt;
    const blinkTime = (this.elapsed + 1) % 4.3;
    this.expressions.setValue("blink", blinkTime < 0.17 ? Math.sin(blinkTime / 0.17 * Math.PI) : 0);
    const mouthMoving = this.lipSync.update(dt, analyser);
    if (analyser || mouthMoving) {
      // Audio owns the mouth while the mixer continues to animate the avatar.
      for (const name of mouthShapes) {
        const weight = this.expressions.getValue(name);
        if (weight === null) continue;
        this.animatedMouth.set(name, weight);
        this.expressions.setValue(name, this.lipSync.weights[name]);
      }
    }
  }
}
