// WII-UU window capture helper for Windows: streams part of a window's own picture as raw frames.
//
//   wiiuu-wincap.exe <title regex> <x> <y> <w> <h> <fps>
//     x y w h: the part of the window's client area, as fractions (0..1)
//   stdout: one line "<width> <height>", then frames of width*height*4 bytes (BGRA), until the
//   window closes or changes size (exit code 3), or stdin closes.
//
//   wiiuu-wincap.exe --show <left> <top> <width> <height> <title regex> <x> <y> <w> <h> <fps>
//     shows that part instead, as large as fits, on black, in a borderless always-on-top window
//     covering left,top,width,height (real pixels: the TV) that never takes the focus.
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
using System.Windows.Forms;

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

    // ---- --show: a window on the TV -----------------------------------------------------------
    sealed class Tv : Form {
        readonly object gate = new object();
        Bitmap shown;
        public Tv(Rectangle where) {
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.Manual;
            Bounds = where;
            BackColor = Color.Black;
            TopMost = true;
            ShowInTaskbar = false;
            DoubleBuffered = true;
            Text = "WII-UU TV";
        }
        protected override bool ShowWithoutActivation { get { return true; } }
        protected override CreateParams CreateParams {
            get {
                CreateParams cp = base.CreateParams;
                cp.ExStyle |= 0x08000000 | 0x00000080 | 0x00000008;       // no activate, tool window, topmost
                return cp;
            }
        }
        public void Put(Bitmap b) {
            Bitmap old;
            lock (gate) { old = shown; shown = b; }
            if (old != null) old.Dispose();
            try { BeginInvoke(new MethodInvoker(Invalidate)); } catch (InvalidOperationException) { }
        }
        protected override void OnPaintBackground(PaintEventArgs e) { }
        protected override void OnPaint(PaintEventArgs e) {
            Graphics g = e.Graphics;
            lock (gate) {
                if (shown == null) { g.Clear(Color.Black); return; }
                double k = Math.Min(ClientSize.Width / (double) shown.Width, ClientSize.Height / (double) shown.Height);
                int w = (int) Math.Round(shown.Width * k), h = (int) Math.Round(shown.Height * k);
                int x = (ClientSize.Width - w) / 2, y = (ClientSize.Height - h) / 2;
                using (SolidBrush black = new SolidBrush(Color.Black)) {
                    g.FillRectangle(black, 0, 0, ClientSize.Width, y);
                    g.FillRectangle(black, 0, y + h, ClientSize.Width, ClientSize.Height - y - h);
                    g.FillRectangle(black, 0, y, x, h);
                    g.FillRectangle(black, x + w, y, ClientSize.Width - x - w, h);
                }
                g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
                g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.Half;
                g.DrawImage(shown, x, y, w, h);
            }
        }
    }

    static int Show(string[] a) {
        System.Globalization.CultureInfo inv = System.Globalization.CultureInfo.InvariantCulture;
        Rectangle where = new Rectangle(int.Parse(a[1], inv), int.Parse(a[2], inv), int.Parse(a[3], inv), int.Parse(a[4], inv));
        string re = a[5];
        double fx = double.Parse(a[6], inv), fy = double.Parse(a[7], inv), fw = double.Parse(a[8], inv), fh = double.Parse(a[9], inv);
        int fps = Math.Max(1, int.Parse(a[10], inv));
        Tv tv = new Tv(where);
        Thread grab = new Thread(delegate () {
            long interval = 10000000L / fps;
            while (true) {
                IntPtr h = Find(re);
                if (h == IntPtr.Zero) { Thread.Sleep(300); continue; }
                RECT cr;
                GetClientRect(h, out cr);
                int cw = cr.R - cr.L, ch = cr.B - cr.T;
                if (cw < 16 || ch < 16) { Thread.Sleep(300); continue; }
                Rectangle part = new Rectangle((int) Math.Round(fx * cw), (int) Math.Round(fy * ch),
                        Math.Max(2, (int) Math.Round(fw * cw)), Math.Max(2, (int) Math.Round(fh * ch)));
                part.Intersect(new Rectangle(0, 0, cw, ch));
                using (Bitmap full = new Bitmap(cw, ch, PixelFormat.Format32bppArgb)) {
                    long next = DateTime.UtcNow.Ticks;
                    while (IsWindow(h)) {
                        RECT now;
                        GetClientRect(h, out now);
                        if (now.R - now.L != cw || now.B - now.T != ch) break;
                        using (Graphics g = Graphics.FromImage(full)) {
                            IntPtr hdc = g.GetHdc();
                            try { PrintWindow(h, hdc, PW_CLIENTONLY | PW_RENDERFULLCONTENT); } finally { g.ReleaseHdc(hdc); }
                        }
                        tv.Put(full.Clone(part, PixelFormat.Format32bppArgb));
                        next += interval;
                        long wait = next - DateTime.UtcNow.Ticks;
                        if (wait > 0) Thread.Sleep((int) (wait / 10000)); else next = DateTime.UtcNow.Ticks;
                    }
                }
            }
        });
        grab.IsBackground = true;
        // stop when WII-UU goes away (stdin closes)
        Thread watch = new Thread(delegate () { try { Console.OpenStandardInput().Read(new byte[1], 0, 1); } catch { } Environment.Exit(0); });
        watch.IsBackground = true;
        tv.Shown += delegate { grab.Start(); watch.Start(); };
        Application.Run(tv);
        return 0;
    }

    static int Main(string[] a) {
        if (a.Length >= 11 && a[0] == "--show") {
            try { if (!SetProcessDpiAwarenessContext(new IntPtr(-4))) SetProcessDPIAware(); } catch (EntryPointNotFoundException) { SetProcessDPIAware(); }
            return Show(a);
        }
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
