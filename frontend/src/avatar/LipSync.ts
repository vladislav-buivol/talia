import { MathUtils } from "three";
import { VowelFormants } from "./VowelFormants";

const vowelCenters = {
  aa: [760, 1450],
  ih: [310, 2300],
  ou: [305, 900],
  ee: [490, 2050],
  oh: [500, 1020],
} as const;

export const mouthShapes = ["aa", "ih", "ou", "ee", "oh"] as const;
type MouthShape = typeof mouthShapes[number];
type MouthWeights = Record<MouthShape, number>;

export class LipSync {
  readonly weights: MouthWeights = { aa: 0, ih: 0, ou: 0, ee: 0, oh: 0 };
  private readonly blend: MouthWeights = { aa: 1, ih: 0, ou: 0, ee: 0, oh: 0 };
  private readonly formants = new VowelFormants();
  private samples = new Float32Array(0);
  private peak = 0.04;

  update(dt: number, analyser: AnalyserNode | null) {
    let strength = 0;
    if (analyser) {
      if (this.samples.length !== analyser.fftSize) this.samples = new Float32Array(analyser.fftSize);
      analyser.getFloatTimeDomainData(this.samples);
      const rate = analyser.context.sampleRate;
      const volume = this.recentVolume(rate);
      this.peak = Math.max(0.038, volume, this.peak * Math.exp(-dt * 0.8));
      const gate = MathUtils.smoothstep(volume, 0.005, 0.015);
      const level = MathUtils.clamp((volume - 0.005) / this.peak, 0, 1);
      const vowel = gate > 0 ? this.formants.detect(this.samples, rate) : null;
      strength = (vowel ? 0.93 : 0.32) * gate * Math.sqrt(level);
      if (vowel) this.setVowelBlend(vowel);
    } else {
      this.peak = 0.04;
    }

    let active = false;
    for (const name of mouthShapes) {
      const target = strength * this.blend[name];
      const next = MathUtils.damp(this.weights[name], target, target > this.weights[name] ? 46 : 36, dt);
      this.weights[name] = next > 0.001 ? next : 0;
      active ||= this.weights[name] > 0;
    }
    return active;
  }

  private recentVolume(rate: number) {
    const count = Math.min(this.samples.length, Math.round(rate * 0.018));
    let energy = 0;
    for (let i = this.samples.length - count; i < this.samples.length; i++) {
      energy += this.samples[i] * this.samples[i];
    }
    return Math.sqrt(energy / count);
  }

  private setVowelBlend([first, second]: readonly [number, number]) {
    let total = 0;
    for (const name of mouthShapes) {
      const [centerFirst, centerSecond] = vowelCenters[name];
      const distance = ((first - centerFirst) / 135) ** 2 + ((second - centerSecond) / 385) ** 2;
      this.blend[name] = Math.exp(-distance);
      total += this.blend[name];
    }
    for (const name of mouthShapes) this.blend[name] /= total;
  }
}
