const predictionOrder = 12;
const envelopeStart = 180;
const envelopeStep = 35;
const envelopeBins = Math.ceil((3200 - envelopeStart) / envelopeStep);

/** Estimates the two strongest low-frequency vocal tract resonances. */
export class VowelFormants {
  private readonly frame = new Float64Array(360);
  private readonly lags = new Float64Array(predictionOrder + 1);
  private readonly coefficients = new Float64Array(predictionOrder + 1);
  private readonly previous = new Float64Array(predictionOrder + 1);
  private readonly envelope = new Float64Array(envelopeBins);

  detect(samples: Float32Array, sampleRate: number): readonly [number, number] | null {
    const stride = Math.max(1, Math.round(sampleRate / 8500));
    const rate = sampleRate / stride;
    const count = Math.min(this.frame.length, Math.floor(samples.length / stride), Math.floor(rate * 0.036));
    if (count < predictionOrder * 3) return null;

    let offset = samples.length - count * stride;
    for (let i = 0; i < count; i++) {
      let sum = 0;
      for (let step = 0; step < stride; step++) sum += samples[offset++];
      this.frame[i] = sum / stride;
    }
    if (!this.isVoiced(count, rate)) return null;

    // Pre-emphasize and window the frame before linear predictive analysis.
    let last = this.frame[0];
    for (let i = 0; i < count; i++) {
      const raw = this.frame[i];
      const window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (count - 1));
      this.frame[i] = (raw - 0.94 * last) * window;
      last = raw;
    }
    this.estimateCoefficients(count);

    for (let bin = 0; bin < this.envelope.length; bin++) {
      const radians = 2 * Math.PI * (envelopeStart + bin * envelopeStep) / rate;
      let real = 1;
      let imaginary = 0;
      for (let i = 1; i <= predictionOrder; i++) {
        real += this.coefficients[i] * Math.cos(radians * i);
        imaginary += this.coefficients[i] * Math.sin(radians * i);
      }
      this.envelope[bin] = 1 / Math.max(0.000001, real * real + imaginary * imaginary);
    }

    const first = this.findPeak(230, 1100);
    const second = first && this.findPeak(first + 230, 3150);
    return first && second ? [first, second] : null;
  }

  private isVoiced(count: number, rate: number) {
    const shortest = Math.floor(rate / 470);
    const longest = Math.min(Math.floor(rate / 75), Math.floor(count / 2));
    let best = 0;
    for (let lag = shortest; lag <= longest; lag++) {
      let correlation = 0;
      let energy = 0;
      for (let i = lag; i < count; i++) {
        const a = this.frame[i];
        const b = this.frame[i - lag];
        correlation += a * b;
        energy += a * a + b * b;
      }
      if (energy > 0) best = Math.max(best, 2 * correlation / energy);
    }
    return best >= 0.58;
  }

  private estimateCoefficients(count: number) {
    for (let lag = 0; lag <= predictionOrder; lag++) {
      let sum = 0;
      for (let i = lag; i < count; i++) sum += this.frame[i] * this.frame[i - lag];
      this.lags[lag] = sum;
    }
    this.coefficients.fill(0);
    let error = this.lags[0] * 1.00001;
    for (let order = 1; order <= predictionOrder && error > 1e-12; order++) {
      let residual = this.lags[order];
      for (let i = 1; i < order; i++) residual += this.coefficients[i] * this.lags[order - i];
      const reflection = Math.max(-0.99, Math.min(0.99, -residual / error));
      this.previous.set(this.coefficients);
      for (let i = 1; i < order; i++) {
        this.coefficients[i] = this.previous[i] + reflection * this.previous[order - i];
      }
      this.coefficients[order] = reflection;
      error *= 1 - reflection * reflection;
    }
  }

  private findPeak(minHz: number, maxHz: number) {
    const start = Math.max(1, Math.ceil((minHz - envelopeStart) / envelopeStep));
    const end = Math.min(this.envelope.length - 2, Math.floor((maxHz - envelopeStart) / envelopeStep));
    let strongest = 0;
    for (let bin = start; bin <= end; bin++) {
      if (this.isPeak(bin)) strongest = Math.max(strongest, this.envelope[bin]);
    }
    // Ignore small ripples before the resonance, particularly on front vowels.
    for (let bin = start; bin <= end; bin++) {
      if (this.isPeak(bin) && this.envelope[bin] >= strongest * 0.09) {
        return envelopeStart + bin * envelopeStep;
      }
    }
    return 0;
  }

  private isPeak(bin: number) {
    const value = this.envelope[bin];
    return value > this.envelope[bin - 1] && value >= this.envelope[bin + 1];
  }
}
