// Generated from engine/src/wiiuu/engine/GameScreen.java; do not edit.
function rows(h, fn) { for (let y = 0; y < h; y++) fn(y); }
    export function dist(yuvA, yuvB) {
        let dy = Math.abs((yuvA >> 16 & 0xff) - (yuvB >> 16 & 0xff));
        let du = Math.abs((yuvA >> 8 & 0xff) - (yuvB >> 8 & 0xff));
        let dv = Math.abs((yuvA & 0xff) - (yuvB & 0xff));
        return 48 * dy + 7 * du + 6 * dv;
    }

    export function toYuv(rgb) {
        let r = rgb >> 16 & 0xff, g = rgb >> 8 & 0xff, b = rgb & 0xff;
        let y = Math.trunc((299 * r + 587 * g + 114 * b) / 1000);
        let u = Math.trunc(128 + (-169 * r - 331 * g + 500 * b) / 1000);
        let v = Math.trunc(128 + (500 * r - 419 * g - 81 * b) / 1000);
        return y << 16 | Math.max(0, Math.min(255, u)) << 8 | Math.max(0, Math.min(255, v));
    }

    
    export function mix(a, b, wb) {
        let wa = 16 - wb;
        let r = ((a >> 16 & 0xff) * wa + (b >> 16 & 0xff) * wb) >> 4;
        let g = ((a >> 8 & 0xff) * wa + (b >> 8 & 0xff) * wb) >> 4;
        let bl = ((a & 0xff) * wa + (b & 0xff) * wb) >> 4;
        return r << 16 | g << 8 | bl;
    }

    
    export function xbr2(input, w, h, out) {
        let yuv = new Uint32Array(w * h);
        rows(h, y => {
            for (let x = 0, i = y * w; x < w; x++, i++) yuv[i] = toYuv(input[i]);
        });
        let ow = w * 2;
        rows(h, y => {
            let px = new Uint32Array(4);
            for (let x = 0; x < w; x++) {
                let e = input[y * w + x];
                px[0] = px[1] = px[2] = px[3] = e;
                
                corner(input, yuv, w, h, x, y, 1, 1, px, 3, 2, 1);
                corner(input, yuv, w, h, x, y, 1, -1, px, 2, 3, 0);
                corner(input, yuv, w, h, x, y, -1, 1, px, 1, 0, 3);
                corner(input, yuv, w, h, x, y, -1, -1, px, 0, 1, 2);
                let o = y * 2 * ow + x * 2;
                out[o] = px[0];
                out[o + 1] = px[1];
                out[o + ow] = px[2];
                out[o + ow + 1] = px[3];
            }
        });
    }

    export function at(a, w, h, x, y) {
        x = x < 0 ? 0 : x >= w ? w - 1 : x;
        y = y < 0 ? 0 : y >= h ? h - 1 : y;
        return a[y * w + x];
    }

    
    export function corner(rgb, yuv, w, h, x, y, sy, sx, px,
                               me, side, up) {
        let E = at(yuv, w, h, x, y), F = at(yuv, w, h, x + sx, y), H = at(yuv, w, h, x, y + sy);
        let I = at(yuv, w, h, x + sx, y + sy), C = at(yuv, w, h, x + sx, y - sy), G = at(yuv, w, h, x - sx, y + sy);
        let D = at(yuv, w, h, x - sx, y), B = at(yuv, w, h, x, y - sy);
        let F4 = at(yuv, w, h, x + 2 * sx, y), I4 = at(yuv, w, h, x + 2 * sx, y + sy);
        let H5 = at(yuv, w, h, x, y + 2 * sy), I5 = at(yuv, w, h, x + sx, y + 2 * sy);
        if (E == F || E == H) return;                      
        let across = dist(E, C) + dist(E, G) + dist(I, F4) + dist(I, H5) + 4 * dist(H, F);
        let along = dist(H, D) + dist(H, I5) + dist(F, I4) + dist(F, B) + 4 * dist(E, I);
        if (across >= along) return;
        let newColor = dist(E, F) <= dist(E, H) ? at(rgb, w, h, x + sx, y) : at(rgb, w, h, x, y + sy);
        let dFG = dist(F, G), dHC = dist(H, C);
        let shallow = 2 * dFG <= dHC && E != G && D != G;
        let steep = dFG >= 2 * dHC && E != C && B != C;
        let e = rgb[y * w + x];
        if (shallow && steep) {
            px[me] = mix(e, newColor, 13);
            px[side] = mix(px[side], newColor, 4);
            px[up] = mix(px[up], newColor, 4);
        } else if (shallow) {
            px[me] = mix(e, newColor, 12);
            px[side] = mix(px[side], newColor, 4);
        } else if (steep) {
            px[me] = mix(e, newColor, 12);
            px[up] = mix(px[up], newColor, 4);
        } else {
            px[me] = mix(e, newColor, 8);
        }
    }

    

    
    export function crt(input, w, h, n, out) {
        let ow = w * n;
        let line = n == 4 ? [15, 16, 13, 6] : [16, 9];         
        rows(h, y => {
            for (let x = 0; x < w; x++) {
                let e = input[y * w + x], l = input[y * w + (x > 0 ? x - 1 : x)], r = input[y * w + (x < w - 1 ? x + 1 : x)];
                for (let c = 0; c < n; c++) {
                    
                    let px = c == 0 ? mix(e, l, 4) : c == n - 1 ? mix(e, r, 4) : e;
                    let mask = (x * n + c) % 3;                 
                    let pr = px >> 16 & 0xff, pg = px >> 8 & 0xff, pb = px & 0xff;
                    pr = pr * (mask == 0 ? 19 : 15) >> 4;
                    pg = pg * (mask == 1 ? 19 : 15) >> 4;
                    pb = pb * (mask == 2 ? 19 : 15) >> 4;
                    for (let rr = 0; rr < n; rr++) {
                        let k = line[rr];
                        let o = (y * n + rr) * ow + x * n + c;
                        out[o] = Math.min(255, pr * k >> 4) << 16 | Math.min(255, pg * k >> 4) << 8 | Math.min(255, pb * k >> 4);
                    }
                }
            }
        });
    }

    
    export function scale2x(input, w, h, out) {
        let ow = w * 2;
        for (let y = 0; y < h; y++) {
            let up = (y > 0 ? y - 1 : y) * w, row = y * w, dn = (y < h - 1 ? y + 1 : y) * w;
            for (let x = 0; x < w; x++) {
                let l = x > 0 ? x - 1 : x, r = x < w - 1 ? x + 1 : x;
                let B = input[up + x], D = input[row + l], E = input[row + x], F = input[row + r], H = input[dn + x];
                let e0 = E, e1 = E, e2 = E, e3 = E;
                if (B != H && D != F) {
                    if (D == B) e0 = D;
                    if (B == F) e1 = F;
                    if (D == H) e2 = D;
                    if (H == F) e3 = F;
                }
                let o = y * 2 * ow + x * 2;
                out[o] = e0;
                out[o + 1] = e1;
                out[o + ow] = e2;
                out[o + ow + 1] = e3;
            }
        }
    }

    
    export function scale3x(input, w, h, out) {
        let ow = w * 3;
        for (let y = 0; y < h; y++) {
            let up = (y > 0 ? y - 1 : y) * w, row = y * w, dn = (y < h - 1 ? y + 1 : y) * w;
            for (let x = 0; x < w; x++) {
                let l = x > 0 ? x - 1 : x, r = x < w - 1 ? x + 1 : x;
                let A = input[up + l], B = input[up + x], C = input[up + r];
                let D = input[row + l], E = input[row + x], F = input[row + r];
                let G = input[dn + l], H = input[dn + x], I = input[dn + r];
                let e0 = E, e1 = E, e2 = E, e3 = E, e4 = E, e5 = E, e6 = E, e7 = E, e8 = E;
                if (B != H && D != F) {
                    if (D == B) e0 = D;
                    if (D == B && E != C || B == F && E != A) e1 = B;
                    if (B == F) e2 = F;
                    if (D == B && E != G || D == H && E != A) e3 = D;
                    if (B == F && E != I || H == F && E != C) e5 = F;
                    if (D == H) e6 = D;
                    if (D == H && E != I || H == F && E != G) e7 = H;
                    if (H == F) e8 = F;
                }
                let o = y * 3 * ow + x * 3;
                out[o] = e0;
                out[o + 1] = e1;
                out[o + 2] = e2;
                out[o + ow] = e3;
                out[o + ow + 1] = e4;
                out[o + ow + 2] = e5;
                out[o + 2 * ow] = e6;
                out[o + 2 * ow + 1] = e7;
                out[o + 2 * ow + 2] = e8;
            }
        }
    }

