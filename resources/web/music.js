// WII-UU's menu music, made on the phone: a line-by-line port of MenuMusic.java, Tunes.java and
// ExtendedMix.java (src/wiiuu/ui), so the phone plays the same tracks as the PC without them being
// sent. Runs as a Web Worker: post {id} ("korobeiniki-house", "wiiuu", "mix:3"), get back
// {id, pcm} (Int16Array, interleaved stereo at 44.1 kHz) or {id, error}.
// Float32Array and Math.fround stand in for Java's float arithmetic, and JavaRandom for
// java.util.Random, so the result matches the PC's to within rounding.
"use strict";

const RATE = 44100;
const F = Math.fround;

// ---- java.util.Random (nextDouble only), exact ----------------------------------------------

class JavaRandom {
  constructor(seed) {
    // (seed ^ 0x5DEECE66D) & (2^48 - 1), kept as two 24-bit halves so every step stays exact
    const lo = (seed % 16777216) ^ 0xECE66D, hi = (Math.floor(seed / 16777216) % 16777216) ^ 0x5DE;
    this.lo = lo & 0xFFFFFF; this.hi = hi & 0xFFFFFF;
  }
  next(bits) {
    // state = state * 0x5DEECE66D + 0xB (mod 2^48)
    const p0 = this.lo * 0xECE66D + 0xB;
    const carry = Math.floor(p0 / 16777216);
    const lo = p0 - carry * 16777216;
    const p1 = this.hi * 0xECE66D + this.lo * 0x5DE + carry;
    this.hi = p1 % 16777216; this.lo = lo;
    return Math.floor((this.hi * 16777216 + this.lo) / 2 ** (48 - bits));
  }
  nextDouble() { return (this.next(26) * 134217728 + this.next(27)) / 9007199254740992; }
}

// ---- shared ----------------------------------------------------------------------------------

/** Adds at frame i, wrapping past the end so loops are seamless (float arithmetic, like Java). */
function add(buf, i, v) { buf[i % buf.length] += F(v); }
const trunc = Math.trunc;

// ---- MenuMusic: WII-UU's own tune ------------------------------------------------------------

const MM = (() => {
  const BPM = 100, BEAT = 60 / BPM, BARS = 16, SWING = 0.07;
  const CHORDS = [
    [[41, 57, 60, 64]], [[45, 55, 60, 64]], [[46, 57, 62, 65]], [[48, 58, 65, 67], [48, 58, 64, 67]],
    [[41, 57, 60, 64]], [[50, 57, 60, 65]], [[43, 53, 58, 62]], [[48, 58, 62, 64]],
    [[46, 57, 62, 65]], [[46, 55, 61, 65]], [[45, 55, 60, 64]], [[50, 60, 63, 66]],
    [[43, 53, 58, 62]], [[48, 58, 64, 67]], [[41, 57, 60, 64]], [[43, 53, 58, 62], [48, 58, 64, 67]],
  ];
  const MELODY = [
    [1, 0, 69, .5], [1, .5, 72, .5], [1, 1, 76, 1], [1, 2.5, 74, .5], [1, 3, 72, 1],
    [2, 0, 76, 1.5], [2, 1.5, 79, .5], [2, 2, 76, .5], [2, 2.5, 72, 1.5],
    [3, 0, 74, .5], [3, .5, 77, .5], [3, 1, 81, 1.5], [3, 2.5, 79, .5], [3, 3, 77, 1],
    [4, 0, 79, 2], [4, 2.5, 76, .5], [4, 3, 77, .5], [4, 3.5, 79, .5],
    [5, 0, 81, 1], [5, 1, 79, .5], [5, 1.5, 77, .5], [5, 2, 76, 1], [5, 3, 72, 1],
    [6, 0, 74, .5], [6, .5, 77, 1], [6, 2, 69, .5], [6, 2.5, 72, .5], [6, 3, 74, 1],
    [7, 0, 70, .5], [7, .5, 74, .5], [7, 1, 77, 1], [7, 2, 76, .5], [7, 2.5, 74, .5], [7, 3, 72, 1],
    [8, 0, 74, 1], [8, 1, 76, .5], [8, 1.5, 79, 2.5],
    [9, .5, 77, .5], [9, 1, 81, .5], [9, 1.5, 84, 1], [9, 2.5, 81, .5], [9, 3, 77, 1],
    [10, 0, 79, 1], [10, 1, 77, .5], [10, 1.5, 73, 1.5], [10, 3, 70, 1],
    [11, 0, 72, .5], [11, .5, 76, .5], [11, 1, 79, 1], [11, 2, 81, .5], [11, 2.5, 79, .5], [11, 3, 76, 1],
    [12, 0, 78, 1], [12, 1, 75, .5], [12, 1.5, 72, .5], [12, 2, 69, 1.5],
    [13, 0, 70, .5], [13, .5, 74, .5], [13, 1, 77, .5], [13, 1.5, 81, 1], [13, 2.5, 79, .5], [13, 3, 77, 1],
    [14, 0, 76, 1.5], [14, 1.5, 79, .5], [14, 2, 82, 1], [14, 3, 81, .5], [14, 3.5, 79, .5],
    [15, 0, 81, 1], [15, 1, 77, .5], [15, 1.5, 72, .5], [15, 2, 76, 2],
    [16, 1, 74, .5], [16, 1.5, 76, .5], [16, 2, 77, .5], [16, 2.5, 79, .5], [16, 3, 76, .5], [16, 3.5, 72, .5],
  ];
  const COMP = [[0, .9], [1.5, .45], [2.5, .45], [3.5, .4]];

  function time(bar, beat) {
    const frac = beat - Math.floor(beat);
    if (Math.abs(frac - 0.5) < 1e-6) beat += SWING;
    return (bar * 4 + beat) * BEAT;
  }
  function midi(note) { return 440 * Math.pow(2, (note - 69) / 12); }
  function release(t, len, rel) { return t <= len ? 1 : Math.max(0, 1 - (t - len) / rel); }

  function electricPiano(l, r, at, len, f, vol) {
    const start = trunc(at * RATE), n = trunc((len + 0.35) * RATE);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const env = Math.min(1, t / 0.004) * Math.exp(-t * 1.8) * release(t, len, 0.25);
      const index = 1.6 * Math.exp(-t * 7) + 0.3;
      let s = Math.sin(2 * Math.PI * f * t + index * Math.sin(2 * Math.PI * f * t))
          + 0.12 * Math.exp(-t * 12) * Math.sin(2 * Math.PI * f * 14 * t);
      const pan = 0.5 + 0.25 * Math.sin(2 * Math.PI * 1.1 * (at + t));
      s *= env * vol;
      add(l, start + i, s * (1 - pan) * 1.4);
      add(r, start + i, s * pan * 1.4);
    }
  }
  function bass(l, r, at, len, f) {
    const start = trunc(at * RATE), n = trunc((len + 0.12) * RATE);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const env = Math.min(1, t / 0.006) * Math.exp(-t * 1.6) * release(t, len, 0.08);
      let s = Math.sin(2 * Math.PI * f * t) + 0.35 * Math.exp(-t * 5) * Math.sin(4 * Math.PI * f * t)
          + 0.08 * Math.sin(6 * Math.PI * f * t);
      s *= env * 0.2;
      add(l, start + i, s);
      add(r, start + i, s);
    }
  }
  function vibes(l, r, at, len, f) {
    const start = trunc(at * RATE), n = trunc((len + 0.6) * RATE);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const env = Math.min(1, t / 0.003) * Math.exp(-t * 1.1) * release(t, len, 0.45);
      const trem = 1 + 0.22 * Math.sin(2 * Math.PI * 5.2 * t);
      let s = Math.sin(2 * Math.PI * f * t) + 0.3 * Math.exp(-t * 9) * Math.sin(2 * Math.PI * f * 4 * t)
          + 0.08 * Math.exp(-t * 20) * Math.sin(2 * Math.PI * f * 9.9 * t);
      s *= env * trem * 0.13;
      add(l, start + i, s * 0.42);
      add(r, start + i, s * 0.58);
    }
  }
  function shaker(l, r, at, vol, noise) {
    const start = trunc(at * RATE), n = trunc(0.07 * RATE);
    let prev = 0;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const w = noise.nextDouble() * 2 - 1, hp = w - prev;
      prev = w;
      const env = Math.min(1, t / 0.01) * Math.exp(-t * 55);
      add(l, start + i, hp * env * vol * 0.6);
      add(r, start + i, hp * env * vol * 0.35);
    }
  }
  function rim(l, r, at, noise) {
    const start = trunc(at * RATE), n = trunc(0.05 * RATE);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const env = Math.exp(-t * 110);
      const s = (Math.sin(2 * Math.PI * 1750 * t) * 0.6 + (noise.nextDouble() * 2 - 1) * 0.4) * env * 0.05;
      add(l, start + i, s * 0.45);
      add(r, start + i, s * 0.55);
    }
  }
  function kick(l, r, at) {
    const start = trunc(at * RATE), n = trunc(0.25 * RATE);
    let phase = 0;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const f = 48 + 70 * Math.exp(-t * 30);
      phase += 2 * Math.PI * f / RATE;
      const s = Math.sin(phase) * Math.exp(-t * 14) * 0.16;
      add(l, start + i, s);
      add(r, start + i, s);
    }
  }

  const F075 = F(0.75), F025 = F(0.25), F08 = F(0.8), F05 = F(0.5), F022 = F(0.22);
  function room(input, out, spread) {
    const combs = [1557, 1617, 1491, 1422], passes = [225, 556];
    const n = input.length;
    for (const c of combs) {
      const d = c + spread;
      const buf = new Float32Array(d);
      let store = 0, p = 0;
      for (let pass = 0; pass < 2; pass++) {
        for (let i = 0; i < n; i++) {
          const y = buf[p];
          store = F(F(y * F075) + F(store * F025));
          buf[p] = input[i] + F(store * F08);
          if (++p >= d) p = 0;
          if (pass === 1) out[i] += F(y * F025);
        }
      }
    }
    for (const a of passes) {
      const d = a + spread;
      const buf = new Float32Array(d);
      let p = 0;
      const src = out.slice();
      for (let pass = 0; pass < 2; pass++) {
        for (let i = 0; i < n; i++) {
          const b = buf[p];
          const y = F(-src[i] + b);
          buf[p] = src[i] + F(b * F05);
          if (++p >= d) p = 0;
          if (pass === 1) out[i] = y;
        }
      }
    }
  }
  function reverb(l, r) {
    const n = l.length;
    const wetL = new Float32Array(n), wetR = new Float32Array(n);
    room(l, wetL, 0);
    room(r, wetR, 23);
    for (let i = 0; i < n; i++) {
      l[i] += F(wetL[i] * F022);
      r[i] += F(wetR[i] * F022);
    }
  }

  function render() {
    const frames = trunc(Math.round(BARS * 4 * BEAT * RATE));
    const left = new Float32Array(frames), right = new Float32Array(frames);
    const noise = new JavaRandom(7);
    for (let bar = 0; bar < BARS; bar++) {
      const chords = CHORDS[bar];
      for (const hit of COMP) {
        const chord = chords[chords.length > 1 && hit[0] >= 2 ? 1 : 0];
        for (let k = 1; k < chord.length; k++) {
          electricPiano(left, right, time(bar, hit[0]) + k * 0.008, hit[1] * BEAT, midi(chord[k]), hit[0] === 0 ? 0.075 : 0.055);
        }
      }
      for (let half = 0; half < chords.length; half++) {
        const root = chords[half][0];
        const from = half * 2.0;
        const whole = chords.length === 1;
        bass(left, right, time(bar, from), (whole ? 1.4 : 0.9) * BEAT, midi(root));
        if (whole) {
          bass(left, right, time(bar, 1.5), 0.45 * BEAT, midi(root + 7));
          bass(left, right, time(bar, 2), 1.3 * BEAT, midi(root));
        } else {
          bass(left, right, time(bar, from + 1), 0.45 * BEAT, midi(root + 7));
        }
      }
      const nextRoot = CHORDS[(bar + 1) % BARS][0][0];
      const lastRoot = chords[chords.length - 1][0];
      bass(left, right, time(bar, 3.5), 0.45 * BEAT, midi(nextRoot + (nextRoot > lastRoot ? -1 : 1)));
      for (let e = 0; e < 8; e++) shaker(left, right, time(bar, e * 0.5), e % 2 === 1 ? 0.05 : 0.03, noise);
      rim(left, right, time(bar, 1), noise);
      rim(left, right, time(bar, 3), noise);
      kick(left, right, time(bar, 0));
      kick(left, right, time(bar, 2.5));
    }
    for (const n of MELODY) vibes(left, right, time(n[0] - 1, n[1]), n[3] * BEAT, midi(n[2]));
    reverb(left, right);
    return toPcm(left, right, F(0.8));
  }

  return { MELODY, render, reverb, electricPiano, bass, vibes, shaker, rim, kick };
})();

/** Normalises to {@code level} and interleaves, like MenuMusic.render and Tunes.finish. */
function toPcm(l, r, level) {
  const frames = l.length;
  let peak = F(1e-6);
  for (let i = 0; i < frames; i++) peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
  const gain = F(level / peak);
  const out = new Int16Array(frames * 2);
  for (let i = 0; i < frames; i++) {
    out[i * 2] = trunc(F(Math.max(-1, Math.min(1, F(l[i] * gain))) * 32767));
    out[i * 2 + 1] = trunc(F(Math.max(-1, Math.min(1, F(r[i] * gain))) * 32767));
  }
  return out;
}

// ---- Tunes: public-domain melodies in 8-bit, WII-UU remix, Future House and Color House ------

const TUNES = (() => {
  const REMIX = "-remix", HOUSE = "-house", COLOR = "-color";

  const KORO_A = `
    E5/1 B4/.5 C5/.5 D5/1 C5/.5 B4/.5  A4/1 A4/.5 C5/.5 E5/1 D5/.5 C5/.5
    B4/1.5 C5/.5 D5/1 E5/1  C5/1 A4/1 A4/1 r/1
    r/.5 D5/1 F5/.5 A5/1 G5/.5 F5/.5  E5/1.5 C5/.5 E5/1 D5/.5 C5/.5
    B4/1 B4/.5 C5/.5 D5/1 E5/1  C5/1 A4/1 A4/1 r/1
    `;
  const KORO_CHORDS = "E Am E Am Dm C E7 Am ";
  const KING_A = `
    B3/.5 C#4/.5 D4/.5 E4/.5 F#4/.5 D4/.5 F#4/1  F4/.5 C#4/.5 F4/1 E4/.5 C4/.5 E4/1
    B3/.5 C#4/.5 D4/.5 E4/.5 F#4/.5 D4/.5 F#4/.5 B4/.5  A4/.5 F#4/.5 D4/.5 F#4/.5 A4/2
    `;
  const KING_B = `
    F#4/.5 G#4/.5 A#4/.5 B4/.5 C#5/.5 A#4/.5 C#5/1  D5/.5 A#4/.5 D5/1 C#5/.5 A#4/.5 C#5/1
    F#4/.5 G#4/.5 A#4/.5 B4/.5 C#5/.5 A#4/.5 C#5/1  D5/.5 A#4/.5 D5/1 C#5/2
    `;
  const KING_CHORDS_A = "Bm F#|C Bm D ";
  const KING_CHORDS_B = "F# Bm|F# F# Bm|F# ";
  const JOY_1 = "F#5/1 F#5/1 G5/1 A5/1  A5/1 G5/1 F#5/1 E5/1  D5/1 D5/1 E5/1 F#5/1 ";
  const JOY_2 = `
    E5/1 E5/1 F#5/1 D5/1  E5/1 F#5/.5 G5/.5 F#5/1 D5/1  E5/1 F#5/.5 G5/.5 F#5/1 E5/1  D5/1 E5/1 A4/2
    `;
  const ELISE_A1 = `
    E5/.25 D#5/.25 E5/.25 B4/.25 D5/.25 C5/.25  A4/.5 r/.25 C4/.25 E4/.25 A4/.25
    B4/.5 r/.25 E4/.25 G#4/.25 B4/.25  C5/.5 r/.25 E4/.25 E5/.25 D#5/.25
    E5/.25 D#5/.25 E5/.25 B4/.25 D5/.25 C5/.25  A4/.5 r/.25 C4/.25 E4/.25 A4/.25
    B4/.5 r/.25 E4/.25 C5/.25 B4/.25
    `;
  const ELISE_A = ELISE_A1 + "A4/.5 r/.5 E5/.25 D#5/.25 ";
  const ELISE_B = `
    E5/.75 G4/.25 F5/.25 E5/.25  D5/.75 F4/.25 E5/.25 D5/.25
    C5/.75 E4/.25 D5/.25 C5/.25  B4/.5 r/.25 E4/.25 E5/.25 r/.25
    r/.25 E5/.25 E6/.25 r/.25 r/.25 D#5/.25  E5/.25 r/.25 r/.25 D#5/.25 E5/.25 D#5/.25
    `;
  const ELISE_CHORDS_A = "Am E|Am Am E|Am ";
  const TURCA = `
    C5/.5 r/.5 D5/.25 C5/.25 B4/.25 C5/.25  E5/.5 r/.5 F5/.25 E5/.25 D#5/.25 E5/.25
    B5/.25 A5/.25 G#5/.25 A5/.25 B5/.25 A5/.25 G#5/.25 A5/.25  C6/1 A5/.5 C6/.5
    B5/.5 A5/.5 G5/.5 A5/.5  B5/.5 A5/.5 G5/.5 A5/.5
    B5/.5 A5/.5 G5/.5 F#5/.5  E5/1 B4/.25 A4/.25 G#4/.25 A4/.25
    `;
  const GREEN = `
    C5/2 D5/1  E5/1.5 F5/.5 E5/1  D5/2 B4/1  G4/1.5 A4/.5 B4/1
    C5/2 A4/1  A4/1.5 G#4/.5 A4/1  B4/2 G#4/1  E4/2 A4/1
    C5/2 D5/1  E5/1.5 F5/.5 E5/1  D5/2 B4/1  G4/1.5 A4/.5 B4/1
    C5/1.5 B4/.5 A4/1  G#4/1.5 F#4/.5 G#4/1  A4/2 A4/1
    `;
  const GREEN_CHORDS = "Am C G Em Am E E Am Am C G Em Am E Am ";
  const CANON = `
    F#5/2 E5/2  D5/2 C#5/2  B4/2 A4/2  B4/2 C#5/2
    D5/2 C#5/2  B4/2 A4/2  G4/2 F#4/2  G4/2 E4/2
    D5/1 F#5/1 A5/1 G5/1  F#5/1 D5/1 F#5/1 E5/1  D5/1 B4/1 D5/1 A4/1  G4/1 B4/1 A4/1 G4/1
    F#5/.5 E5/.5 D5/.5 E5/.5 F#5/.5 E5/.5 D5/.5 C#5/.5  B4/.5 C#5/.5 D5/.5 C#5/.5 B4/.5 A4/.5 C#5/.5 A4/.5
    G4/.5 B4/.5 D5/.5 B4/.5 A4/.5 F#4/.5 A4/.5 D5/.5  G4/.5 B4/.5 D5/.5 G5/.5 E5/.5 A5/.5 C#5/.5 E5/.5
    `;
  const CANON_CHORDS = "D|A Bm|F#m G|D G|A ";

  const tune = (id, beatsPerBar, bpm, remixBpm, melody, chords, drums, doubleFromBar) =>
    ({ id, beatsPerBar, bpm, remixBpm, melody, chords, drums, doubleFromBar });
  const LIST = [
    tune("korobeiniki", 4, 150, 104, KORO_A + KORO_A, KORO_CHORDS + KORO_CHORDS, "x-h-s-h-x-hxs-h-", 8),
    tune("mountainking", 4, 138, 100, KING_A + KING_A + KING_B + up(KING_A),
         KING_CHORDS_A + KING_CHORDS_A + KING_CHORDS_B + KING_CHORDS_A, "k-h-s-h-k-h-s-hh", 4),
    tune("odetojoy", 4, 132, 96,
         JOY_1 + "F#5/1.5 E5/.5 E5/2 " + JOY_1 + "E5/1.5 D5/.5 D5/2 " + JOY_2 + JOY_1 + "E5/1.5 D5/.5 D5/2 ",
         "D A7 D D|A D A7 D A|D A|D A|D A|D Bm|A D A7 D A|D ", "k-h-s-h-k-k-s-h-", 8),
    tune("furelise", 3, 72, 62, ELISE_A + ELISE_A1 + "A4/.5 r/.25 B4/.25 C5/.25 D5/.25 " + ELISE_B + ELISE_A,
         ELISE_CHORDS_A + ELISE_CHORDS_A + "C|G Am|E E " + ELISE_CHORDS_A, "k-hhs-", 4),
    tune("turkishmarch", 4, 116, 88, TURCA + TURCA, "Am E|Am Em B7|Em Am E|Am Em B7|Em ", "x-h-s-h-x-h-s-hh", 4),
    tune("greensleeves", 3, 120, 84, GREEN + GREEN, GREEN_CHORDS + GREEN_CHORDS, "k-h-h-k-hhs-", 15),
    tune("canon", 4, 100, 76, CANON, CANON_CHORDS.repeat(4), "k-h-s-h-k-k-s-h-", 8),
  ];
  const THEME = tune("wiiuu", 4, 100, 100, themeMelody(),
                     "F Am Bb C|C7 F Dm Gm C7 Bb Bbm Am D7 Gm C7 F Gm|C7", "--------", 99);

  function themeMelody() {
    const names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
    let sb = "", at = 0;
    const m = MM.MELODY;
    for (let i = 0; i < m.length; i++) {
      const start = (m[i][0] - 1) * 4 + m[i][1];
      if (start > at + 1e-9) sb += "r/" + (start - at) + " ";
      let end = start + m[i][3];
      if (i + 1 < m.length) end = Math.min(end, (m[i + 1][0] - 1) * 4 + m[i + 1][1]);
      const note = m[i][2];
      sb += names[note % 12] + (Math.trunc(note / 12) - 1) + "/" + (end - start) + " ";
      at = end;
    }
    if (at < 64) sb += "r/" + (64 - at);
    return sb;
  }

  function find(base) {
    for (const t of LIST) if (t.id === base) return t;
    return null;
  }

  function render(id) {
    let style = "";
    for (const suffix of [REMIX, HOUSE, COLOR]) if (id.endsWith(suffix)) style = suffix;
    const base = id.substring(0, id.length - style.length);
    let t = base === THEME.id && (style === HOUSE || style === COLOR) ? THEME : null;
    t = find(base) || t;
    if (!t) return null;
    switch (style) {
      case REMIX: return remix(t);
      case HOUSE: return master(houseParts(t, HOUSE_BPM));
      case COLOR: return master(colorParts(t, COLOR_BPM));
      default: return chip(t);
    }
  }

  // ---- notation

  function tokens(s) { return s.trim().split(/\s+/); }
  function melody(s) {
    const out = [];
    let at = 0;
    for (const tok of tokens(s)) {
      const p = tok.split("/");
      const len = parseFloat(p[1]);
      if (p[0] !== "r") out.push({ beat: at, midi: midi(p[0]), len });
      at += len;
    }
    return out;
  }
  function length(m) {
    let at = 0;
    for (const tok of tokens(m)) at += parseFloat(tok.split("/")[1]);
    return at;
  }
  function midi(name) {
    let i = 1, n = "C D EF G A B".indexOf(name.charAt(0));
    if (name.charAt(i) === "#") { n++; i++; }
    else if (name.charAt(i) === "b") { n--; i++; }
    return 12 * (parseInt(name.substring(i), 10) + 1) + n;
  }
  function up(m) {
    let sb = "";
    for (const tok of tokens(m)) {
      const p = tok.split("/");
      if (p[0] === "r") { sb += tok + " "; continue; }
      const d = p[0].length - 1;
      sb += p[0].substring(0, d) + (parseInt(p[0].substring(d), 10) + 1) + "/" + p[1] + " ";
    }
    return sb;
  }
  function chord(name) {
    let i = 1, root = "C D EF G A B".indexOf(name.charAt(0));
    if (name.length > 1 && name.charAt(1) === "#") { root++; i++; }
    else if (name.length > 1 && name.charAt(1) === "b") { root--; i++; }
    const q = name.substring(i);
    const iv = q === "m" ? [0, 3, 7] : q === "7" ? [0, 4, 7, 10] : q === "m7" ? [0, 3, 7, 10] : [0, 4, 7];
    return [(root + 12) % 12, ...iv];
  }
  function chordAt(bar, beat, beatsPerBar) {
    const halves = bar.split("|");
    return chord(halves[halves.length > 1 && beat >= beatsPerBar / 2 - 1e-9 ? 1 : 0]);
  }
  function bars(t) {
    const b = tokens(t.chords);
    if (Math.abs(length(t.melody) - b.length * t.beatsPerBar) > 1e-6) throw new Error(t.id + ": melody and chords differ in length");
    return b;
  }
  function hz(m) { return 440 * Math.pow(2, (m - 69) / 12); }
  function finish(l, r, level) {
    MM.reverb(l, r);
    return toPcm(l, r, level);
  }

  // ---- 8-bit

  function chip(t) {
    const beat = 60 / t.bpm;
    const bpb = t.beatsPerBar, steps = bpb * 2;
    const b_ = bars(t);
    const frames = trunc(Math.round(b_.length * bpb * beat * RATE));
    const l = new Float32Array(frames), r = new Float32Array(frames);
    const noise = new JavaRandom(11);
    for (const n of melody(t.melody)) {
      const at = n.beat * beat, len = n.len * beat * 0.92;
      pulse(l, r, at, len, hz(n.midi), 0.25, 0.16, 0.42, true);
      if (n.beat >= t.doubleFromBar * bpb - 1e-9) pulse(l, r, at, len, hz(n.midi - 12), 0.5, 0.06, 0.6, false);
    }
    const drumBars = Math.max(1, trunc(t.drums.length / steps));
    for (let b = 0; b < b_.length; b++) {
      for (let step = 0; step < steps; step++) {
        const c = chordAt(b_[b], step * 0.5, bpb);
        const at = (b * bpb + step * 0.5) * beat;
        const tones = c.length - 1;
        const order = tones === 4 ? [0, 1, 2, 3, 2, 1] : [0, 1, 2, 1];
        const note = 60 + (c[0] >= 7 ? c[0] - 12 : c[0]) + c[1 + order[step % order.length]];
        pulse(l, r, at, 0.5 * beat * 0.6, hz(note), 0.125, 0.045, 0.7, false);
        if (step % 2 === 0) {
          const root = 36 + c[0], beatNo = step / 2;
          const bn = beatNo === 0 ? root : beatNo % 2 === 0 ? root + c[3] : root + 12;
          triangle(l, r, at, beat * 0.8, hz(bn), 0.22);
        }
        switch (t.drums.charAt((b % drumBars) * steps + step)) {
          case "k": kick(l, r, at); break;
          case "s": snare(l, r, at, noise); break;
          case "h": hat(l, r, at, noise); break;
          case "x": kick(l, r, at); hat(l, r, at, noise); break;
          default: break;
        }
      }
    }
    return finish(l, r, F(0.75));
  }

  function pulse(l, r, at, len, f, duty, vol, pan, vibrato) {
    const start = trunc(at * RATE), n = trunc((len + 0.04) * RATE);
    let phase = 0;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const ff = vibrato && t > 0.18 ? f * (1 + 0.006 * Math.sin(2 * Math.PI * 5.5 * t)) : f;
      const dt = ff / RATE;
      let s = (phase < duty ? 1 : -1) + blep(phase, dt) - blep((phase - duty + 1) % 1, dt) - (2 * duty - 1);
      phase += dt;
      if (phase >= 1) phase -= 1;
      const env = Math.min(1, t / 0.004) * (0.75 + 0.25 * Math.exp(-t * 8)) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.04));
      s *= env * vol;
      add(l, start + i, s * (1 - pan) * 2);
      add(r, start + i, s * pan * 2);
    }
  }
  function blep(t, dt) {
    if (t < dt) { t /= dt; return t + t - t * t - 1; }
    if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; }
    return 0;
  }
  function triangle(l, r, at, len, f, vol) {
    const start = trunc(at * RATE), n = trunc((len + 0.03) * RATE);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const p = (f * t) % 1;
      let s = 4 * Math.abs(p - 0.5) - 1;
      const env = Math.min(1, t / 0.003) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.03));
      s *= env * vol;
      add(l, start + i, s);
      add(r, start + i, s);
    }
  }
  function kick(l, r, at) {
    const start = trunc(at * RATE), n = trunc(0.18 * RATE);
    let phase = 0;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      phase += 2 * Math.PI * (50 + 110 * Math.exp(-t * 35)) / RATE;
      const s = Math.sin(phase) * Math.exp(-t * 18) * 0.3;
      add(l, start + i, s);
      add(r, start + i, s);
    }
  }
  function snare(l, r, at, noise) {
    const start = trunc(at * RATE), n = trunc(0.16 * RATE);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const s = ((noise.nextDouble() * 2 - 1) * Math.exp(-t * 22) + Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t * 30) * 0.5) * 0.12;
      add(l, start + i, s);
      add(r, start + i, s);
    }
  }
  function hat(l, r, at, noise) {
    const start = trunc(at * RATE), n = trunc(0.04 * RATE);
    let prev = 0;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const w = noise.nextDouble() * 2 - 1, hp = w - prev;
      prev = w;
      const s = hp * Math.exp(-t * 90) * 0.035;
      add(l, start + i, s * 0.4);
      add(r, start + i, s * 0.6);
    }
  }

  // ---- WII-UU remix

  function remix(t) {
    const beat = 60 / t.remixBpm;
    const bpb = t.beatsPerBar;
    const b_ = bars(t);
    const frames = trunc(Math.round(b_.length * bpb * beat * RATE));
    const l = new Float32Array(frames), r = new Float32Array(frames);
    const noise = new JavaRandom(7);
    const notes = melody(t.melody);
    let sixteenths = false, sum = 0;
    for (const n of notes) {
      const frac = n.beat - Math.floor(n.beat);
      sixteenths = sixteenths || Math.abs(frac - 0.25) < 1e-6 || Math.abs(frac - 0.75) < 1e-6;
      sum += n.midi;
    }
    const swing = sixteenths ? 0 : 0.07;
    const shift = 12 * Math.round((76 - sum / Math.max(1, notes.length)) / 12);
    for (const n of notes) MM.vibes(l, r, time(n.beat, beat, swing), n.len * beat, hz(n.midi + shift));

    for (let b = 0; b < b_.length; b++) {
      const bar = b * bpb;
      for (let hit = 0; hit < bpb; hit += hit === 0 ? 1.5 : 1) {
        const v = voicing(chordAt(b_[b], hit, bpb));
        const len = hit === 0 ? 0.9 : 0.45;
        for (let k = 0; k < v.length; k++) {
          MM.electricPiano(l, r, time(bar + hit, beat, swing) + k * 0.008, len * beat, hz(v[k]), hit === 0 ? 0.075 : 0.055);
        }
      }
      for (let k = 0; k < bpb; k++) {
        const c = chordAt(b_[b], k, bpb);
        const root = bassRoot(c[0]);
        const changes = k === 0 || chordAt(b_[b], k - 1, bpb)[0] !== c[0] || (bpb === 4 && k === 2);
        MM.bass(l, r, time(bar + k, beat, swing), (k === bpb - 1 ? 0.45 : 0.9) * beat, hz(changes ? root : root + 7));
      }
      const next = bassRoot(chordAt(b_[(b + 1) % b_.length], 0, bpb)[0]);
      const last = bassRoot(chordAt(b_[b], bpb - 0.5, bpb)[0]);
      MM.bass(l, r, time(bar + bpb - 0.5, beat, swing), 0.45 * beat, hz(next + (next > last ? -1 : 1)));
      for (let e = 0; e < bpb * 2; e++) MM.shaker(l, r, time(bar + e * 0.5, beat, swing), e % 2 === 1 ? 0.05 : 0.03, noise);
      for (let k = 1; k < bpb; k += bpb === 3 ? 1 : 2) MM.rim(l, r, time(bar + k, beat, swing), noise);
      MM.kick(l, r, time(bar, beat, swing));
      if (bpb === 4) MM.kick(l, r, time(bar + 2.5, beat, swing));
    }
    return finish(l, r, F(0.8));
  }
  function time(beats, beat, swing) {
    const frac = beats - Math.floor(beats);
    if (Math.abs(frac - 0.5) < 1e-6) beats += swing;
    return beats * beat;
  }
  function bassRoot(pc) { const n = 36 + pc; return n < 40 ? n + 12 : n; }
  function voicing(c) {
    const minor = c[2] === 3, seventh = c.length > 4;
    const iv = [c[2], c[3], seventh ? c[4] : minor ? 10 : 11];
    const out = [];
    for (let k = 0; k < 3; k++) {
      let n = 48 + c[0] + iv[k];
      while (n < 55) n += 12;
      while (n > 66) n -= 12;
      out.push(n);
    }
    return out.sort((a, b) => a - b);
  }

  // ---- Future House remix

  const HOUSE_BPM = 128;
  const BOUNCE = [0, 3, 6, 10, 13];
  const patch = (saws, detuneCents, attack, decay, sustain, release, q, envAmount, envDecay, drive, width, bend) =>
    ({ saws, detuneCents, attack, decay, sustain, release, q, envAmount, envDecay, drive, width, bend });
  const PLUCK = patch(2, 12, 0.002, 0.18, 0.25, 0.08, 0.9, 1.5, 12, 1.5, 0.6, 0);
  const LEAD = patch(3, 18, 0.003, 0.3, 0.55, 0.06, 0.8, 1.8, 9, 2.2, 0.8, 0);
  const STAB = patch(2, 10, 0.001, 0.08, 0.2, 0.04, 1.0, 2.0, 25, 1.2, 0.5, 0);
  const MID_BASS = patch(2, 8, 0.002, 0.12, 0.45, 0.03, 2.5, 4.0, 30, 3.0, 0.3, 3);

  function newParts(frames) {
    return { busL: new Float32Array(frames), busR: new Float32Array(frames), dryL: new Float32Array(frames),
             dryR: new Float32Array(frames), drumL: new Float32Array(frames), drumR: new Float32Array(frames) };
  }

  function houseParts(t, bpm) {
    const beat = 60 / bpm, bar4 = 4 * beat, step = beat / 4;
    const bpb = t.beatsPerBar;
    const stretch = 4 / bpb;
    const b_ = bars(t);
    const n = b_.length;
    const build = Math.max(4, Math.min(8, trunc(n / 4) * 2));
    const frames = trunc(Math.round((build + n) * bar4 * RATE));
    const P = newParts(frames);
    const { busL, busR, dryL, dryR, drumL, drumR } = P;
    const noise = new JavaRandom(5);
    const drop = build * bar4;
    const opening = (s) => 400 * Math.pow(20, Math.min(1, s / drop));
    const notes = melody(t.melody);
    let sum = 0;
    for (const note of notes) sum += note.midi;
    const shift = 12 * Math.round((74 - sum / Math.max(1, notes.length)) / 12);

    for (let b = 0; b < build; b++) {
      const bar = b * bar4;
      for (let s = 0; s < 16; s++) {
        const at = bar + s * step;
        const c = chordAt(b_[b], s / 4 / stretch, bpb);
        if (BOUNCE.includes(s)) for (const note of chordNotes(c)) synth(busL, busR, at, beat * 0.4, note, 0.16, PLUCK, opening);
        MM.shaker(drumL, drumR, at, s % 2 === 1 ? 0.025 : 0.012, noise);
        if ((s === 6 || s === 14) && b % 2 === 1) chop(busL, busR, at, beat * 0.45, 72 + (c[0] > 7 ? c[0] - 12 : c[0]) + c[s === 6 ? 3 : 2], s === 6 ? 0 : 1, 0.12);
      }
      if (b >= build / 2 && b < build - 2) {
        houseSnare(drumL, drumR, bar + beat, 0.3, noise);
        houseSnare(drumL, drumR, bar + 3 * beat, 0.3, noise);
      }
    }
    buildFx(busL, busR, drumL, drumR, build, beat, noise);
    for (const note of notes) {
      if (note.beat >= build * bpb) break;
      const len = Math.min(note.len, build * bpb - note.beat);
      synth(busL, busR, note.beat * stretch * beat, len * stretch * beat * 0.95, note.midi + shift, 0.26, PLUCK, (s) => opening(s) * 1.6);
    }

    for (let b = 0; b < n; b++) {
      const bar = drop + b * bar4;
      if (b % 8 === 0) crash(drumL, drumR, bar, noise);
      for (let k = 0; k < 4; k++) {
        houseKick(drumL, drumR, bar + k * beat);
        if (k % 2 === 1) clap(drumL, drumR, bar + k * beat, noise);
        ride(drumL, drumR, bar + k * beat, noise);
        hat(drumL, drumR, bar + k * beat + beat / 2, noise);
      }
      for (let s = 0; s < 16; s++) {
        const at = bar + s * step;
        MM.shaker(drumL, drumR, at, s % 2 === 1 ? 0.03 : 0.015, noise);
        if (s % 4 !== 2) continue;
        const c = chordAt(b_[b], s / 4 / stretch, bpb);
        const root = bassRoot(c[0]);
        sub(dryL, dryR, at, beat * 0.45, hz(root - 12), 0.2);
        synth(dryL, dryR, at, beat * 0.32, root + (s === 6 || s === 14 ? 12 : 0), 0.24, MID_BASS, () => 380);
        for (const note of chordNotes(c)) synth(busL, busR, at, beat * 0.2, note + 12, 0.06, STAB, () => 4200);
      }
      if (b % 2 === 1) {
        const c = chordAt(b_[b], 3.5 / stretch, bpb);
        chop(busL, busR, bar + 14 * step, beat * 0.3, 72 + (c[0] > 7 ? c[0] - 12 : c[0]) + c[3], 2, 0.08);
      }
    }
    for (const note of notes) {
      const at = drop + note.beat * stretch * beat, len = note.len * stretch * beat * 0.95;
      synth(busL, busR, at, len, note.midi + shift, 0.34, LEAD, () => 3400);
      if (note.beat >= n * bpb / 2) synth(busL, busR, at, len, note.midi + shift + 12, 0.12, LEAD, () => 4000);
    }
    return Object.assign(P, { drop, beat, bars: b_, build });
  }

  function buildFx(busL, busR, drumL, drumR, build, beat, noise) {
    const bar4 = 4 * beat, step = beat / 4, drop = build * bar4;
    for (let k = 0; k < 8; k++) houseSnare(drumL, drumR, drop - 2 * bar4 + k * beat / 2, 0.15 + 0.02 * k, noise);
    for (let k = 0; k < 8; k++) houseSnare(drumL, drumR, drop - bar4 + k * step, 0.32 + 0.02 * k, noise);
    for (let k = 0; k < 16; k++) houseSnare(drumL, drumR, drop - bar4 / 2 + k * step / 2, 0.48 + 0.02 * k, noise);
    const riser = Math.min(4, trunc(build / 2));
    noiseSweep(busL, busR, drop - riser * bar4, riser * bar4, 400, 11000, 0, 0.28, noise);
    noiseSweep(busL, busR, 0, bar4, 9000, 200, 0.22, 0, noise);
  }

  function master(p, buildFrom, buildTo, dropEnd) {
    if (buildFrom === undefined) { buildFrom = 0; buildTo = p.drop; dropEnd = p.busL.length / RATE; }
    const { busL, busR, dryL, dryR, drumL, drumR } = p;
    const beat = p.beat;
    MM.reverb(busL, busR);
    const frames = busL.length;
    const l = new Float32Array(frames), r = new Float32Array(frames);
    const dropFrom = trunc(buildTo * RATE), dropTo = Math.min(frames, trunc(dropEnd * RATE));
    let dropPower = 0;
    for (let i = 0; i < frames; i++) {
      const s = i / RATE;
      let duck = 1;
      if (s < buildFrom || s >= buildTo) {
        const x = Math.max(0, 1 - (s % beat) / 0.16);
        duck = 1 - 0.78 * x * x;
      }
      l[i] = F(busL[i] + dryL[i]) * duck + drumL[i];
      r[i] = F(busR[i] + dryR[i]) * duck + drumR[i];
      if (i >= dropFrom && i < dropTo) dropPower += F(F(l[i] * l[i]) + F(r[i] * r[i]));
    }
    const drive = 0.45 / Math.sqrt(dropPower / Math.max(1, 2.0 * (dropTo - dropFrom))), level = 0.42;
    const out = new Int16Array(frames * 2);
    for (let i = 0; i < frames; i++) {
      out[i * 2] = trunc(Math.tanh(drive * l[i]) * level * 32767);
      out[i * 2 + 1] = trunc(Math.tanh(drive * r[i]) * level * 32767);
    }
    return out;
  }

  // ---- extended versions, for the Extended Mix

  const EXTENDED_BARS = 8;

  function extended(id, bpm) {
    const house = id.endsWith(HOUSE);
    if (!house && !id.endsWith(COLOR)) return null;
    const base = id.substring(0, id.length - (house ? HOUSE : COLOR).length);
    const t = find(base) || (base === THEME.id ? THEME : null);
    if (!t) return null;
    const core = house ? houseParts(t, bpm) : colorParts(t, bpm);
    const beat = core.beat, bar4 = 4 * beat, step = beat / 4, pad = EXTENDED_BARS * bar4;
    const b_ = core.bars;
    const bpb = t.beatsPerBar, coreBars = core.build + b_.length;
    const offset = trunc(Math.round(pad * RATE)), len = core.busL.length;
    const frames = offset + len + offset;
    const names = ["busL", "busR", "dryL", "dryR", "drumL", "drumR"];
    const parts = names.map((k) => { const a = new Float32Array(frames); a.set(core[k], offset); return a; });
    const noise = new JavaRandom(13);
    const outro = pad + coreBars * bar4;
    for (let b = 0; b < EXTENDED_BARS; b++) {
      for (let side = 0; side < 2; side++) {
        const intro = side === 0;
        const bar = (intro ? 0 : outro) + b * bar4;
        for (let k = 0; k < 4; k++) {
          houseKick(parts[4], parts[5], bar + k * beat);
          if (k % 2 === 1 && (intro ? b >= 2 : b < EXTENDED_BARS - 2)) clap(parts[4], parts[5], bar + k * beat, noise);
        }
        for (let s = 0; s < 16; s++) hat16(parts[4], parts[5], bar + s * step, s, 0.8, noise);
        if (intro ? b < EXTENDED_BARS / 2 : b >= EXTENDED_BARS / 2) continue;
        const chordBar = intro ? b_[0] : b_[b_.length - 1];
        for (let s = 2; s < 16; s += 4) {
          const root = bassRoot(chordAt(chordBar, s / 4 * bpb / 4, bpb)[0]);
          const at = bar + s * step;
          sub(parts[2], parts[3], at, beat * 0.45, hz(root - 12), 0.2);
          if (house) synth(parts[2], parts[3], at, beat * 0.32, root + (s === 6 || s === 14 ? 12 : 0), 0.24, MID_BASS, () => 380);
          else yoyBass(parts[2], parts[3], at, step * 1.6, root + (s === 6 || s === 14 ? 12 : 0), 0.2);
        }
      }
    }
    const all = { busL: parts[0], busR: parts[1], dryL: parts[2], dryR: parts[3], drumL: parts[4], drumR: parts[5],
                  drop: pad + core.drop, beat, bars: b_, build: core.build };
    return master(all, pad, pad + core.drop, outro);
  }

  function chordNotes(c) { const v = voicing(c); return [48 + c[0], v[0], v[1], v[2]]; }

  class Svf {
    constructor() { this.s1 = 0; this.s2 = 0; this.bp = 0; this.hp = 0; }
    run(x, g, k) {
      const a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
      const v3 = x - this.s2, v1 = a1 * this.s1 + a2 * v3, v2 = this.s2 + a2 * this.s1 + a3 * v3;
      this.s1 = 2 * v1 - this.s1;
      this.s2 = 2 * v2 - this.s2;
      this.bp = v1;
      this.hp = x - k * v1 - v2;
      return v2;
    }
    static g(f) { return Math.tan(Math.PI * Math.min(f, RATE * 0.45) / RATE); }
  }

  function synth(l, r, at, len, m, vol, p, cutoff) {
    const start = trunc(at * RATE), n = trunc((len + p.release) * RATE);
    const saws = p.saws;
    const phase = [], ratio = [], pan = [];
    for (let k = 0; k < saws; k++) {
      const spread = saws === 1 ? 0 : k / (saws - 1) * 2 - 1;
      ratio.push(Math.pow(2, spread * p.detuneCents / 1200));
      pan.push(0.5 + spread * p.width / 2);
      phase.push(k / saws);
    }
    const fl = new Svf(), fr = new Svf();
    const k = 1 / p.q, norm = Math.tanh(p.drive);
    let g = 0, f = hz(m);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      if ((i & 15) === 0) {
        g = Svf.g(cutoff(at + t) * (1 + p.envAmount * Math.exp(-t * p.envDecay)));
        if (p.bend !== 0) f = hz(m + p.bend * Math.exp(-t * 60));
      }
      let sl = 0, sr = 0;
      for (let j = 0; j < saws; j++) {
        const dt = f * ratio[j] / RATE;
        const v = 2 * phase[j] - 1 - blep(phase[j], dt);
        phase[j] += dt;
        if (phase[j] >= 1) phase[j] -= 1;
        sl += v * (1 - pan[j]);
        sr += v * pan[j];
      }
      const env = (t < p.attack ? t / p.attack : p.sustain + (1 - p.sustain) * Math.exp(-(t - p.attack) / p.decay))
          * (t <= len ? 1 : Math.max(0, 1 - (t - len) / p.release));
      const yl = Math.tanh(p.drive * fl.run(sl, g, k) / saws) / norm;
      const yr = Math.tanh(p.drive * fr.run(sr, g, k) / saws) / norm;
      add(l, start + i, yl * env * vol);
      add(r, start + i, yr * env * vol);
    }
  }

  const VOWELS = [[730, 1090], [570, 840], [300, 2250]];
  function chop(l, r, at, len, m, vowel, vol) {
    const start = trunc(at * RATE), n = trunc((len + 0.03) * RATE);
    const a = new Svf(), b = new Svf();
    const ga = Svf.g(VOWELS[vowel][0]), gb = Svf.g(VOWELS[vowel][1]), k = 1 / 6;
    let phase = 0;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const dt = hz(m - Math.exp(-t * 40)) / RATE;
      const v = 2 * phase - 1 - blep(phase, dt);
      phase += dt;
      if (phase >= 1) phase -= 1;
      a.run(v, ga, k);
      b.run(v, gb, k);
      const y = (a.bp + 0.6 * b.bp) * Math.min(1, t / 0.005) * Math.exp(-t * 5) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.03));
      add(l, start + i, y * vol * (vowel === 1 ? 0.65 : 0.35));
      add(r, start + i, y * vol * (vowel === 1 ? 0.35 : 0.65));
    }
  }
  function noiseSweep(l, r, at, len, fromHz, toHz, v0, v1, noise) {
    const start = trunc(at * RATE), n = trunc(len * RATE);
    const fl = new Svf(), fr = new Svf();
    let g = 0;
    for (let i = 0; i < n; i++) {
      const p = i / n;
      if ((i & 15) === 0) g = Svf.g(fromHz * Math.pow(toHz / fromHz, p));
      fl.run(noise.nextDouble() * 2 - 1, g, 1 / 1.4);
      fr.run(noise.nextDouble() * 2 - 1, g, 1 / 1.4);
      const vol = v0 + (v1 - v0) * p * p;
      add(l, start + i, fl.bp * vol);
      add(r, start + i, fr.bp * vol);
    }
  }
  function houseKick(l, r, at) {
    const start = trunc(at * RATE), n = trunc(0.32 * RATE);
    let phase = 0;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      phase += 2 * Math.PI * (46 + 140 * Math.exp(-t * 28)) / RATE;
      let s = Math.sin(phase) * Math.exp(-t * 7);
      if (t < 0.003) s += (1 - t / 0.003) * 0.4 * Math.sin(2 * Math.PI * 3000 * t);
      s = Math.tanh(1.5 * s) * 0.32;
      add(l, start + i, s);
      add(r, start + i, s);
    }
  }
  function clap(l, r, at, noise) {
    const start = trunc(at * RATE), n = trunc(0.22 * RATE);
    const fl = new Svf(), fr = new Svf();
    const g = Svf.g(1400);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      let env = 0;
      for (const o of [0, 0.008, 0.016]) if (t >= o) env += Math.exp(-(t - o) * 300);
      if (t >= 0.024) env += 0.6 * Math.exp(-(t - 0.024) * 16);
      fl.run(noise.nextDouble() * 2 - 1, g, 1 / 1.2);
      fr.run(noise.nextDouble() * 2 - 1, g, 1 / 1.2);
      add(l, start + i, fl.bp * env * 0.45);
      add(r, start + i, fr.bp * env * 0.45);
    }
  }
  function houseSnare(l, r, at, vol, noise) {
    const start = trunc(at * RATE), n = trunc(0.14 * RATE);
    const f = new Svf();
    const g = Svf.g(2200);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      f.run(noise.nextDouble() * 2 - 1, g, 1 / 0.8);
      const s = (f.bp * 1.6 * Math.exp(-t * 25) + Math.sin(2 * Math.PI * 200 * t) * Math.exp(-t * 35) * 0.5) * vol * 0.5;
      add(l, start + i, s);
      add(r, start + i, s);
    }
  }
  function ride(l, r, at, noise) {
    const start = trunc(at * RATE), n = trunc(0.5 * RATE);
    const partials = [3150, 4620, 6280, 7930];
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      let s = 0;
      for (const f of partials) s += Math.sin(2 * Math.PI * f * t);
      s = (s * 0.25 + (noise.nextDouble() * 2 - 1) * 0.3) * Math.exp(-t * 5) * 0.022;
      add(l, start + i, s * 0.6);
      add(r, start + i, s * 0.4);
    }
  }
  function crash(l, r, at, noise) {
    const start = trunc(at * RATE), n = trunc(2.0 * RATE);
    const fl = new Svf(), fr = new Svf();
    const g = Svf.g(4500);
    for (let i = 0; i < n; i++) {
      const t = i / RATE, env = Math.exp(-t * 1.8) * 0.1;
      fl.run(noise.nextDouble() * 2 - 1, g, 1 / 0.7);
      fr.run(noise.nextDouble() * 2 - 1, g, 1 / 0.7);
      add(l, start + i, fl.hp * env);
      add(r, start + i, fr.hp * env);
    }
  }
  function sub(l, r, at, len, f, vol) {
    const start = trunc(at * RATE), n = trunc((len + 0.03) * RATE);
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const s = Math.sin(2 * Math.PI * f * t) * Math.min(1, t / 0.005) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.03)) * vol;
      add(l, start + i, s);
      add(r, start + i, s);
    }
  }

  // ---- Color House remix

  const COLOR_BPM = 126;
  const BASS_STEPS = [2, 3, 7, 10, 11, 14];
  const BASS_UP = [0, 12, 0, 0, 12, 7];

  function colorParts(t, bpm) {
    const beat = 60 / bpm, bar4 = 4 * beat, step = beat / 4;
    const bpb = t.beatsPerBar;
    const stretch = 4 / bpb;
    const b_ = bars(t);
    const n = b_.length;
    const build = Math.max(4, Math.min(8, trunc(n / 4) * 2));
    const frames = trunc(Math.round((build + n) * bar4 * RATE));
    const P = newParts(frames);
    const { busL, busR, dryL, dryR, drumL, drumR } = P;
    const noise = new JavaRandom(9);
    const drop = build * bar4;
    const opening = (s) => 500 * Math.pow(16, Math.min(1, s / drop));
    const notes = melody(t.melody);
    let sum = 0;
    for (const note of notes) sum += note.midi;
    const shift = 12 * Math.round((74 - sum / Math.max(1, notes.length)) / 12);

    for (let b = 0; b < build; b++) {
      const bar = b * bar4;
      for (let s = 0; s < 16; s++) {
        const at = bar + s * step;
        const c = chordAt(b_[b], s / 4 / stretch, bpb);
        if (BOUNCE.includes(s)) colorChord(busL, busR, at, beat * 0.5, c, 0.2, opening(at), noise);
        if (b >= build / 2) hat16(drumL, drumR, at, s, 0.5 * (b + 1) / build, noise);
      }
      if (b >= build / 2 && b < build - 2) {
        houseSnare(drumL, drumR, bar + beat, 0.3, noise);
        houseSnare(drumL, drumR, bar + 3 * beat, 0.3, noise);
      }
    }
    buildFx(busL, busR, drumL, drumR, build, beat, noise);
    for (const note of notes) {
      if (note.beat >= build * bpb) break;
      const len = Math.min(note.len, build * bpb - note.beat);
      const at = note.beat * stretch * beat;
      colorLead(busL, busR, at, len * stretch * beat * 0.95, note.midi + shift, chordNotes(chordOf(b_, note.beat, bpb)), 0.22, opening(at) * 1.5);
    }

    for (let b = 0; b < n; b++) {
      const bar = drop + b * bar4;
      if (b % 8 === 0) crash(drumL, drumR, bar, noise);
      for (let k = 0; k < 4; k++) {
        houseKick(drumL, drumR, bar + k * beat);
        if (k % 2 === 1) {
          clap(drumL, drumR, bar + k * beat, noise);
          houseSnare(drumL, drumR, bar + k * beat, 0.15, noise);
        }
      }
      for (let s = 0; s < 16; s++) {
        const at = bar + s * step;
        hat16(drumL, drumR, at, s, 1, noise);
        if (s === 7 || s === 15) MM.rim(drumL, drumR, at, noise);
        const c = chordAt(b_[b], s / 4 / stretch, bpb);
        if (BOUNCE.includes(s)) colorChord(busL, busR, at, beat * 0.45, c, 0.12, 6500, noise);
        for (let k = 0; k < BASS_STEPS.length; k++) {
          if (BASS_STEPS[k] !== s) continue;
          const root = bassRoot(c[0]);
          sub(dryL, dryR, at, step * 1.6, hz(root - 12), 0.2);
          yoyBass(dryL, dryR, at, step * 1.6, root + BASS_UP[k], 0.2);
        }
      }
    }
    for (const note of notes) {
      const at = drop + note.beat * stretch * beat, len = note.len * stretch * beat * 0.95;
      colorLead(busL, busR, at, len, note.midi + shift, chordNotes(chordOf(b_, note.beat, bpb)), 0.3, 5000);
    }
    return Object.assign(P, { drop, beat, bars: b_, build });
  }

  function chordOf(b_, beat, bpb) {
    const b = Math.min(b_.length - 1, Math.floor(beat / bpb + 1e-9));
    return chordAt(b_[b], beat - b * bpb, bpb);
  }

  class Resonators {
    constructor(notes, feedback) {
      this.feedback = feedback;
      this.lines = []; this.delay = []; this.damp = []; this.pos = [];
      for (const n of notes) {
        const d = RATE / hz(n);
        this.delay.push(d);
        this.lines.push(new Float32Array(trunc(d) + 3));
        this.damp.push(0);
        this.pos.push(0);
      }
    }
    run(x, out) {
      out[0] = out[1] = 0;
      for (let k = 0; k < this.lines.length; k++) {
        const line = this.lines[k];
        const len = line.length;
        let back = this.pos[k] - this.delay[k];
        while (back < 0) back += len;
        const i0 = trunc(back);
        const frac = back - i0, d = line[i0 % len] * (1 - frac) + line[(i0 + 1) % len] * frac;
        this.damp[k] += 0.6 * (d - this.damp[k]);
        const y = x + this.feedback * this.damp[k];
        line[this.pos[k]] = y;
        this.pos[k] = (this.pos[k] + 1) % len;
        out[k % 2] += y;
      }
      return out;
    }
  }

  function colorNotes(c) {
    const base = chordNotes(c), out = [];
    for (const n of base) out.push(n + 12, n + 24);
    return out;
  }
  function colorChord(l, r, at, len, c, vol, cutoff, noise) {
    const start = trunc(at * RATE), n = trunc((len + 0.05) * RATE);
    const res = new Resonators(colorNotes(c), 0.985);
    const fl = new Svf(), fr = new Svf();
    const g = Svf.g(cutoff), k = 1 / 0.8;
    const y = [0, 0];
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const x = (noise.nextDouble() * 2 - 1) * Math.exp(-t * 250) * 0.3;
      res.run(x, y);
      const env = t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.05);
      add(l, start + i, Math.tanh(1.5 * fl.run(y[0], g, k)) * env * vol);
      add(r, start + i, Math.tanh(1.5 * fr.run(y[1], g, k)) * env * vol);
    }
  }

  const GLIDE = [[450, 800], [730, 1090], [530, 1840]];
  function colorLead(l, r, at, len, m, chordN, vol, cutoff) {
    const start = trunc(at * RATE), n = trunc((len + 0.25) * RATE);
    const tuned = chordN.map((x) => x + 12);
    tuned.push(Math.round(m));
    const res = new Resonators(tuned, 0.97);
    const f1 = new Svf(), f2 = new Svf(), lp = new Svf();
    const phase = [0, 0.33, 0.67], ratio = [Math.pow(2, -15 / 1200), 1, Math.pow(2, 15 / 1200)];
    const f = hz(m), g = Svf.g(cutoff);
    let g1 = 0, g2 = 0;
    const y = [0, 0];
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      if ((i & 15) === 0) {
        const mm = Math.min(1, t / Math.max(0.05, len)) * 2;
        const v = Math.min(1, trunc(mm));
        const w = mm - v;
        g1 = Svf.g(GLIDE[v][0] + (GLIDE[v + 1][0] - GLIDE[v][0]) * w);
        g2 = Svf.g(GLIDE[v][1] + (GLIDE[v + 1][1] - GLIDE[v][1]) * w);
      }
      let saw = 0;
      for (let j = 0; j < 3; j++) {
        const dt = f * ratio[j] / RATE;
        saw += 2 * phase[j] - 1 - blep(phase[j], dt);
        phase[j] += dt;
        if (phase[j] >= 1) phase[j] -= 1;
      }
      const env = Math.min(1, t / 0.005) * (0.7 + 0.3 * Math.exp(-t * 6)) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.06));
      const x = saw / 3 * env;
      res.run(x * 0.25, y);
      const colored = 0.35 * x + 0.65 * (y[0] + y[1]) * 0.25;
      f1.run(colored, g1, 1 / 5);
      f2.run(colored, g2, 1 / 5);
      const out = Math.tanh(2 * lp.run(0.4 * colored + f1.bp + 0.7 * f2.bp, g, 1 / 0.8));
      const tail = t <= len + 0.2 ? 1 : Math.max(0, 1 - (t - len - 0.2) / 0.05);
      add(l, start + i, out * vol * tail * 0.55);
      add(r, start + i, out * vol * tail * 0.45);
    }
  }
  function yoyBass(l, r, at, len, m, vol) {
    const start = trunc(at * RATE), n = trunc((len + 0.02) * RATE);
    const f1 = new Svf(), f2 = new Svf();
    const f = hz(m), r2 = Math.pow(2, 8 / 1200);
    let p1 = 0, p2 = 0.5;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const mm = 1 - Math.exp(-t * 18);
      const dt1 = f / RATE, dt2 = f * r2 / RATE;
      const saw = 2 * p1 - 1 - blep(p1, dt1) + 2 * p2 - 1 - blep(p2, dt2);
      p1 += dt1;
      if (p1 >= 1) p1 -= 1;
      p2 += dt2;
      if (p2 >= 1) p2 -= 1;
      f1.run(saw, Svf.g(400 - 120 * mm), 1 / 4);
      f2.run(saw, Svf.g(800 + 1400 * mm), 1 / 4);
      const env = Math.min(1, t / 0.002) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.02));
      const yv = Math.tanh(2.5 * (f1.bp + 0.6 * f2.bp + 0.15 * saw)) * env * vol;
      add(l, start + i, yv);
      add(r, start + i, yv);
    }
  }
  function hat16(l, r, at, step, vol, noise) {
    const accent = [0.45, 0.2, 1, 0.3];
    const start = trunc(at * RATE), n = trunc((step % 4 === 2 ? 0.12 : 0.04) * RATE);
    const f = new Svf();
    const g = Svf.g(8000), decay = step % 4 === 2 ? 25 : 80, pan = step % 2 === 0 ? 0.4 : 0.6;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      f.run(noise.nextDouble() * 2 - 1, g, 1 / 0.7);
      const s = f.hp * Math.exp(-t * decay) * 0.06 * accent[step % 4] * vol;
      add(l, start + i, s * (1 - pan) * 2);
      add(r, start + i, s * pan * 2);
    }
  }

  return { render, extended, EXTENDED_BARS };
})();

// ---- ExtendedMix ------------------------------------------------------------------------------

const MIX = (() => {
  const BPM = 128;
  const ORDER = [
    "wiiuu-house", "korobeiniki-color", "mountainking-house", "odetojoy-color",
    "furelise-house", "turkishmarch-color", "greensleeves-house", "canon-color",
    "wiiuu-color", "korobeiniki-house", "mountainking-color", "odetojoy-house",
    "furelise-color", "turkishmarch-house", "greensleeves-color", "canon-house"];
  const cache = new Map();          // extended versions already made: segment i+1 reuses i's "next"

  function track(i) {
    const id = ORDER[((i % ORDER.length) + ORDER.length) % ORDER.length];
    if (!cache.has(id)) {
      if (cache.size >= 2) cache.delete(cache.keys().next().value);
      cache.set(id, TUNES.extended(id, BPM));
    }
    return cache.get(id);
  }

  function segment(i) {
    const n = ORDER.length;
    i = ((i % n) + n) % n;
    const cur = track(i), next = track(i + 1);
    const blend = trunc(Math.round(TUNES.EXTENDED_BARS * 4 * 60 / BPM * RATE));
    const frames = cur.length / 2 - blend;
    const out = cur.slice(blend * 2, blend * 2 + frames * 2);
    const from = frames - blend;
    for (let f = 0; f < blend; f++) {
      const x = (f + 0.5) / blend, a = Math.cos(x * Math.PI / 2), b = Math.sin(x * Math.PI / 2);
      for (let c = 0; c < 2; c++) {
        const o = (from + f) * 2 + c;
        out[o] = Math.max(-32768, Math.min(32767, Math.round(out[o] * a + next[f * 2 + c] * b)));
      }
    }
    return out;
  }
  return { segment };
})();

/** Makes a track by id, as the PC does; null for one the phone can't make. */
function makeTrack(id) {
  if (id === "wiiuu") return MM.render();
  if (id.startsWith("mix:")) return MIX.segment(parseInt(id.substring(4), 10));
  return TUNES.render(id);
}

if (typeof self !== "undefined" && typeof self.postMessage === "function" && typeof window === "undefined") {
  self.onmessage = (e) => {
    const id = e.data && e.data.id;
    try {
      const pcm = makeTrack(id);
      if (!pcm) self.postMessage({ id, error: "unknown track" });
      else self.postMessage({ id, pcm }, [pcm.buffer]);
    } catch (err) {
      self.postMessage({ id, error: String(err) });
    }
  };
}
if (typeof module !== "undefined") module.exports = { makeTrack, JavaRandom };
