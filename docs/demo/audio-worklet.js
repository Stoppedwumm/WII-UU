// Stereo ring buffer, bounded latency, linear sample-rate conversion.
class ChoccyAudio extends AudioWorkletProcessor {
  constructor() {
    super();
    this.l = new Float32Array(131072);
    this.r = new Float32Array(131072);
    this.read = 0;
    this.write = 0;
    this.ratio = 1;
    this.port.onmessage = ({ data }) => {
      if (data.clear) {
        this.read = this.write = 0;
        return;
      }
      this.ratio = data.rate / sampleRate;
      const pcm = data.pcm;
      for (let i = 0; i < pcm.length; i += 2) {
        const at = this.write++ % this.l.length;
        this.l[at] = pcm[i] / 32768;
        this.r[at] = pcm[i + 1] / 32768;
      }
      if (this.write - this.read > data.rate * 0.2)
        this.read = this.write - data.rate * 0.06;
    };
  }
  process(_inputs, outputs) {
    const [l, r] = outputs[0];
    if (!l || !r) return true;
    for (let i = 0; i < l.length; i++) {
      if (this.read + 1 >= this.write) {
        l[i] = r[i] = 0;
        continue;
      }
      const a = Math.floor(this.read) % this.l.length,
        b = (a + 1) % this.l.length,
        t = this.read - Math.floor(this.read);
      l[i] = this.l[a] * (1 - t) + this.l[b] * t;
      r[i] = this.r[a] * (1 - t) + this.r[b] * t;
      this.read += this.ratio;
    }
    return true;
  }
}
registerProcessor("choccy-audio", ChoccyAudio);
