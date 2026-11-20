type Playback = {
  request: AbortController;
  source?: AudioBufferSourceNode;
  analyser?: AnalyserNode;
};

export class AudioPlayback {
  readonly analyser = { current: null as AnalyserNode | null };
  private context?: AudioContext;
  private current?: Playback;

  async enable() {
    const audio = this.context ?? new AudioContext();
    this.context = audio;
    await audio.resume();
    return this.context === audio;
  }

  begin() {
    this.stop();
    const request = new AbortController();
    this.current = { request };
    return request.signal;
  }

  async play(data: ArrayBuffer, signal: AbortSignal, onEnd: () => void) {
    const audio = this.context;
    const playback = this.current;
    if (signal.aborted || !audio || playback?.request.signal !== signal) return false;
    const buffer = await audio.decodeAudioData(data);
    if (this.current !== playback) return false;

    const source = audio.createBufferSource();
    const analyser = audio.createAnalyser();
    analyser.fftSize = 2048;
    source.buffer = buffer;
    source.connect(analyser);
    analyser.connect(audio.destination);
    playback.analyser = analyser;
    source.onended = () => {
      source.disconnect();
      analyser.disconnect();
      if (this.current !== playback) return;
      this.current = undefined;
      this.analyser.current = null;
      onEnd();
    };
    try {
      source.start();
    } catch (error) {
      source.disconnect();
      throw error;
    }
    playback.source = source;
    this.analyser.current = analyser;
    return true;
  }

  stop() {
    const playback = this.current;
    this.current = undefined;
    this.analyser.current = null;
    if (!playback) return;
    playback.request.abort();
    if (playback.source) {
      playback.source.onended = null;
      playback.source.stop();
      playback.source.disconnect();
    }
    playback.analyser?.disconnect();
  }

  dispose() {
    this.stop();
    const audio = this.context;
    this.context = undefined;
    if (audio) void audio.close();
  }
}
