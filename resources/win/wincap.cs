// WII-UU window capture helper for Windows: streams part of a window's own picture as raw frames.
//
//   wiiuu-wincap.exe <title regex> <x> <y> <w> <h> <fps>
//     x y w h: the part of the window's client area, as fractions (0..1)
//   stdout: one line "<width> <height>", then frames of width*height*4 bytes (BGRA), until the
//   window closes or changes size (exit code 3), or stdin closes.
//
// It asks Windows' compositor for the window's own picture (PrintWindow with PW_RENDERFULLCONTENT),
// like the Alt+Tab thumbnails, so it works for GPU-drawn windows (RetroArch) on any monitor, a
// virtual one included, where copying the screen gets black or stale pictures.
// Compiled on the PC with the C# compiler of the .NET Framework (csc.exe); C# 5.
using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

static class WiiuuWinCap {
    delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr c);
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    struct RECT { public int L, T, R, B; }
    const uint PW_CLIENTONLY = 1, PW_RENDERFULLCONTENT = 2;

    static string Exe(IntPtr h) {
        uint pid;
        GetWindowThreadProcessId(h, out pid);
        try { return Process.GetProcessById((int) pid).ProcessName; } catch { return ""; }
    }

    static IntPtr Find(string re) {
        IntPtr found = IntPtr.Zero;
        long best = 0;
        EnumWindows(delegate (IntPtr h, IntPtr l) {
            if (!IsWindowVisible(h)) return true;
            StringBuilder sb = new StringBuilder(512);
            GetWindowText(h, sb, 512);
            if (sb.Length == 0 || !Regex.IsMatch(sb.ToString() + " [" + Exe(h) + "]", re, RegexOptions.IgnoreCase)) return true;
            RECT r;
            GetClientRect(h, out r);
            long area = (long) (r.R - r.L) * (r.B - r.T);
            if (area > best) { best = area; found = h; }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    static int Main(string[] a) {
        if (a.Length < 6) { Console.Error.WriteLine("usage: wiiuu-wincap <title regex> <x> <y> <w> <h> <fps>"); return 1; }
        try { if (!SetProcessDpiAwarenessContext(new IntPtr(-4))) SetProcessDPIAware(); } catch (EntryPointNotFoundException) { SetProcessDPIAware(); }
        System.Globalization.CultureInfo inv = System.Globalization.CultureInfo.InvariantCulture;
        double fx = double.Parse(a[1], inv), fy = double.Parse(a[2], inv), fw = double.Parse(a[3], inv), fh = double.Parse(a[4], inv);
        int fps = Math.Max(1, int.Parse(a[5], inv));
        IntPtr h = Find(a[0]);
        if (h == IntPtr.Zero) { Console.Error.WriteLine("no window matches " + a[0]); return 2; }
        RECT cr;
        GetClientRect(h, out cr);
        int cw = cr.R - cr.L, ch = cr.B - cr.T;
        if (cw < 16 || ch < 16) return 3;
        int x0 = Math.Max(0, (int) Math.Round(fx * cw)), y0 = Math.Max(0, (int) Math.Round(fy * ch));
        int w = Math.Min(cw - x0, (int) Math.Round(fw * cw)) & ~1, hh = Math.Min(ch - y0, (int) Math.Round(fh * ch)) & ~1;
        if (w < 2 || hh < 2) return 3;

        Stream stdout = Console.OpenStandardOutput();
        byte[] head = Encoding.ASCII.GetBytes(w + " " + hh + "\n");
        stdout.Write(head, 0, head.Length);
        stdout.Flush();
        // stop when WII-UU goes away (stdin closes)
        Thread watch = new Thread(delegate () { try { Console.OpenStandardInput().Read(new byte[1], 0, 1); } catch { } Environment.Exit(0); });
        watch.IsBackground = true;
        watch.Start();

        Bitmap full = new Bitmap(cw, ch, PixelFormat.Format32bppArgb);
        byte[] frame = new byte[w * hh * 4];
        long interval = 10000000L / fps, next = DateTime.UtcNow.Ticks;
        while (IsWindow(h)) {
            RECT now;
            GetClientRect(h, out now);
            if (now.R - now.L != cw || now.B - now.T != ch) return 3;          // resized: WII-UU starts again
            using (Graphics g = Graphics.FromImage(full)) {
                IntPtr hdc = g.GetHdc();
                try { PrintWindow(h, hdc, PW_CLIENTONLY | PW_RENDERFULLCONTENT); } finally { g.ReleaseHdc(hdc); }
            }
            BitmapData bd = full.LockBits(new Rectangle(x0, y0, w, hh), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            try {
                for (int y = 0; y < hh; y++) Marshal.Copy(new IntPtr(bd.Scan0.ToInt64() + (long) y * bd.Stride), frame, y * w * 4, w * 4);
            } finally { full.UnlockBits(bd); }
            try { stdout.Write(frame, 0, frame.Length); stdout.Flush(); } catch (IOException) { return 0; }
            next += interval;
            long wait = next - DateTime.UtcNow.Ticks;
            if (wait > 0) Thread.Sleep((int) (wait / 10000));
            else next = DateTime.UtcNow.Ticks;
        }
        return 3;
    }
}
