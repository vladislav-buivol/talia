type Playback = {
  source: AudioBufferSourceNode;
  analyser: AnalyserNode;
  startedAt: number;
  offset: number;
  rate: number;
  finish: (completed: boolean) => void;
};

export class AudioPlayback {
  readonly analyser = { current: null as AnalyserNode | null };
  private context?: AudioContext;
  private request?: AbortController;
  private current?: Playback;

  async enable() {
    const audio = this.context ?? new AudioContext();
    this.context = audio;
    await audio.resume();
    return this.context === audio;
  }

  begin() {
    this.stop();
    this.request = new AbortController();
    return this.request.signal;
  }

  async decode(data: ArrayBuffer) {
    if (!this.context) throw new Error("Audio is disabled");
    return this.context.decodeAudioData(data);
  }

  position() {
    const playback = this.current;
    const audio = this.context;
    if (!playback || !audio) return 0;
    const output = audio.getOutputTimestamp?.();
    const clock = output?.performanceTime ? output.contextTime ?? audio.currentTime : audio.currentTime;
    return playback.offset + Math.max(0, Math.min(
      (clock - playback.startedAt) * playback.rate, (playback.source.buffer?.duration ?? 0) - playback.offset,
    ));
  }

  play(buffer: AudioBuffer, signal: AbortSignal, offset = 0, rate = 1): Promise<boolean> {
    const audio = this.context;
    if (!audio || signal.aborted || this.request?.signal !== signal) return Promise.resolve(false);
    if (offset >= buffer.duration) return Promise.resolve(true);

    const source = audio.createBufferSource();
    const analyser = audio.createAnalyser();
    analyser.fftSize = 2048;
    source.buffer = buffer;
    source.playbackRate.value = rate;
    source.connect(analyser);
    analyser.connect(audio.destination);
    return new Promise((resolve, reject) => {
      const finish = (completed: boolean) => {
        source.onended = null;
        source.disconnect();
        analyser.disconnect();
        this.current = undefined;
        this.analyser.current = null;
        resolve(completed);
      };
      const startedAt = audio.currentTime;
      source.onended = () => finish(true);
      try {
        source.start(startedAt, offset);
        this.current = { source, analyser, startedAt, offset, rate, finish };
        this.analyser.current = analyser;
      } catch (error) {
        source.onended = null;
        source.disconnect();
        analyser.disconnect();
        reject(error);
      }
    });
  }

  stop() {
    this.request?.abort();
    this.request = undefined;
    const playback = this.current;
    if (playback) {
      playback.source.stop();
      playback.finish(false);
    }
  }

  dispose() {
    this.stop();
    const audio = this.context;
    this.context = undefined;
    if (audio) void audio.close();
  }
}
