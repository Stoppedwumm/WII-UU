// WII-UU window capture helper for Windows: streams part of a window's own picture as raw frames.
//
//   wiiuu-wincap.exe <title regex> <x> <y> <w> <h> <fps> [<max width>]
//     x y w h: the part of the window's client area, as fractions (0..1)
//   stdout: one line "<width> <height>", then frames of width*height*3 bytes (BGR, top row first),
//   scaled down here to at most <max width> so the pipe and WII-UU carry no more than the phone
//   gets, until the window closes or changes size (exit code 3), or stdin closes.
//   stdin: taps on that part, one per line: "t <1 down | 2 move | 0 up> <x> <y>" (fractions of the part),
//   done as mouse clicks there, in the window's own coordinates (whatever the monitors' scaling).
//
//   wiiuu-wincap.exe --list
//     prints the visible titled windows, one per line: left, top, width, height of the client area
//     (real pixels) and "title [program]", tab-separated. (Far cheaper than asking PowerShell.)
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
    [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern void mouse_event(uint f, uint x, uint y, uint d, UIntPtr e);
    struct POINT { public int X, Y; }
    [DllImport("user32.dll")] static extern IntPtr GetWindowDpiAwarenessContext(IntPtr h);
    [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr c);

    // Measure and capture a window in its own DPI terms. A window that isn't DPI-aware (RetroArch) is
    // drawn at 100 % and stretched by Windows on a scaled monitor; asked for in real pixels, its
    // picture comes out magnified (by the monitor's scaling) and cut off.
    static void AsWindowSees(IntPtr h) {
        try { SetThreadDpiAwarenessContext(GetWindowDpiAwarenessContext(h)); } catch (EntryPointNotFoundException) { }
    }
    struct RECT { public int L, T, R, B; }
    const uint PW_CLIENTONLY = 1, PW_RENDERFULLCONTENT = 2;

    static readonly System.Collections.Generic.Dictionary<uint, string> exes = new System.Collections.Generic.Dictionary<uint, string>();

    static string Exe(IntPtr h) {
        uint pid;
        GetWindowThreadProcessId(h, out pid);
        lock (exes) {
            string name;
            if (exes.TryGetValue(pid, out name)) return name;
            try { name = Process.GetProcessById((int) pid).ProcessName; } catch { name = ""; }
            exes[pid] = name;
            return name;
        }
    }

    static int List() {
        StringBuilder out_ = new StringBuilder();
        EnumWindows(delegate (IntPtr h, IntPtr l) {
            if (!IsWindowVisible(h)) return true;
            StringBuilder sb = new StringBuilder(512);
            GetWindowText(h, sb, 512);
            if (sb.Length == 0) return true;
            RECT r;
            GetClientRect(h, out r);
            POINT p = new POINT();
            ClientToScreen(h, ref p);
            out_.Append(p.X).Append('\t').Append(p.Y).Append('\t').Append(r.R - r.L).Append('\t').Append(r.B - r.T)
                .Append('\t').Append(sb.ToString().Replace('\t', ' ').Replace('\n', ' ')).Append(" [").Append(Exe(h)).Append("]\n");
            return true;
        }, IntPtr.Zero);
        Stream o = Console.OpenStandardOutput();
        byte[] b = Encoding.UTF8.GetBytes(out_.ToString());
        o.Write(b, 0, b.Length);
        o.Flush();
        return 0;
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
                g.CompositingMode = System.Drawing.Drawing2D.CompositingMode.SourceCopy;
                g.CompositingQuality = System.Drawing.Drawing2D.CompositingQuality.HighSpeed;
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
                AsWindowSees(h);
                RECT cr;
                GetClientRect(h, out cr);
                int cw = cr.R - cr.L, ch = cr.B - cr.T;
                if (cw < 16 || ch < 16) { Thread.Sleep(300); continue; }
                Rectangle part = new Rectangle((int) Math.Round(fx * cw), (int) Math.Round(fy * ch),
                        Math.Max(2, (int) Math.Round(fw * cw)), Math.Max(2, (int) Math.Round(fh * ch)));
                part.Intersect(new Rectangle(0, 0, cw, ch));
                using (Bitmap full = new Bitmap(cw, ch, PixelFormat.Format32bppRgb))
                using (Graphics g = Graphics.FromImage(full)) {
                    long next = DateTime.UtcNow.Ticks;
                    while (IsWindow(h)) {
                        RECT now;
                        GetClientRect(h, out now);
                        if (now.R - now.L != cw || now.B - now.T != ch) break;
                        IntPtr hdc = g.GetHdc();
                        try { PrintWindow(h, hdc, PW_CLIENTONLY | PW_RENDERFULLCONTENT); } finally { g.ReleaseHdc(hdc); }
                        // premultiplied: the layout GDI+ draws fastest
                        tv.Put(full.Clone(part, PixelFormat.Format32bppPArgb));
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
        if (a.Length == 1 && a[0] == "--list") {
            try { if (!SetProcessDpiAwarenessContext(new IntPtr(-4))) SetProcessDPIAware(); } catch (EntryPointNotFoundException) { SetProcessDPIAware(); }
            return List();
        }
        if (a.Length >= 11 && a[0] == "--show") {
            try { if (!SetProcessDpiAwarenessContext(new IntPtr(-4))) SetProcessDPIAware(); } catch (EntryPointNotFoundException) { SetProcessDPIAware(); }
            return Show(a);
        }
        if (a.Length < 6) { Console.Error.WriteLine("usage: wiiuu-wincap <title regex> <x> <y> <w> <h> <fps>"); return 1; }
        try { if (!SetProcessDpiAwarenessContext(new IntPtr(-4))) SetProcessDPIAware(); } catch (EntryPointNotFoundException) { SetProcessDPIAware(); }
        System.Globalization.CultureInfo inv = System.Globalization.CultureInfo.InvariantCulture;
        double fx = double.Parse(a[1], inv), fy = double.Parse(a[2], inv), fw = double.Parse(a[3], inv), fh = double.Parse(a[4], inv);
        int fps = Math.Max(1, int.Parse(a[5], inv));
        int maxW = a.Length >= 7 ? Math.Max(16, int.Parse(a[6], inv)) : int.MaxValue;
        IntPtr h = Find(a[0]);
        if (h == IntPtr.Zero) { Console.Error.WriteLine("no window matches " + a[0]); return 2; }
        AsWindowSees(h);
        RECT cr;
        GetClientRect(h, out cr);
        int cw = cr.R - cr.L, ch = cr.B - cr.T;
        if (cw < 16 || ch < 16) return 3;
        int x0 = Math.Max(0, (int) Math.Round(fx * cw)), y0 = Math.Max(0, (int) Math.Round(fy * ch));
        int w = Math.Min(cw - x0, (int) Math.Round(fw * cw)) & ~1, hh = Math.Min(ch - y0, (int) Math.Round(fh * ch)) & ~1;
        if (w < 2 || hh < 2) return 3;
        // what is sent: the part, scaled down to the phone's width
        int ow = w, oh = hh;
        if (ow > maxW) { ow = maxW & ~1; oh = Math.Max(2, (int) Math.Round(hh * (ow / (double) w)) & ~1); }

        Stream stdout = Console.OpenStandardOutput();
        byte[] head = Encoding.ASCII.GetBytes(ow + " " + oh + "\n");
        stdout.Write(head, 0, head.Length);
        stdout.Flush();
        // taps from WII-UU; stop when it goes away (stdin closes)
        IntPtr win = h;
        int px = x0, py = y0, pw = w, ph = hh;
        Thread taps = new Thread(delegate () {
            AsWindowSees(win);                       // this thread measures in the window's terms too
            try {
                TextReader rd = Console.In;
                string line;
                bool down = false;
                while ((line = rd.ReadLine()) != null) {
                    string[] t = line.Trim().Split(' ');
                    if (t.Length != 4 || t[0] != "t") continue;
                    int state = int.Parse(t[1], inv);
                    double tx = Math.Max(0, Math.Min(1, double.Parse(t[2], inv))), ty = Math.Max(0, Math.Min(1, double.Parse(t[3], inv)));
                    POINT pt = new POINT();
                    pt.X = px + (int) Math.Round(tx * (pw - 1));
                    pt.Y = py + (int) Math.Round(ty * (ph - 1));
                    ClientToScreen(win, ref pt);
                    SetCursorPos(pt.X, pt.Y);
                    if (state == 1 && !down) { mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero); down = true; }       // left down
                    else if (state == 0 && down) { mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero); down = false; }  // left up
                }
            } catch { }
            Environment.Exit(0);
        });
        taps.IsBackground = true;
        taps.Start();

        // allocated once: a frame costs no garbage
        Bitmap full = new Bitmap(cw, ch, PixelFormat.Format32bppRgb);
        Graphics fullG = Graphics.FromImage(full);
        Bitmap small = new Bitmap(ow, oh, PixelFormat.Format24bppRgb);
        Graphics smallG = Graphics.FromImage(small);
        smallG.CompositingMode = System.Drawing.Drawing2D.CompositingMode.SourceCopy;
        smallG.CompositingQuality = System.Drawing.Drawing2D.CompositingQuality.HighSpeed;
        smallG.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.Half;
        // a big step down needs the prefiltered kind, or small print on the touch screen flickers
        smallG.InterpolationMode = w > 2 * ow ? System.Drawing.Drawing2D.InterpolationMode.HighQualityBilinear
                : System.Drawing.Drawing2D.InterpolationMode.Bilinear;
        Rectangle src = new Rectangle(x0, y0, w, hh), dst = new Rectangle(0, 0, ow, oh);
        byte[] frame = new byte[ow * oh * 3];
        long interval = 10000000L / fps, next = DateTime.UtcNow.Ticks;
        while (IsWindow(h)) {
            RECT now;
            GetClientRect(h, out now);
            if (now.R - now.L != cw || now.B - now.T != ch) return 3;          // resized: WII-UU starts again
            IntPtr hdc = fullG.GetHdc();
            try { PrintWindow(h, hdc, PW_CLIENTONLY | PW_RENDERFULLCONTENT); } finally { fullG.ReleaseHdc(hdc); }
            smallG.DrawImage(full, dst, src, GraphicsUnit.Pixel);
            BitmapData bd = small.LockBits(dst, ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
            try {
                for (int y = 0; y < oh; y++) Marshal.Copy(new IntPtr(bd.Scan0.ToInt64() + (long) y * bd.Stride), frame, y * ow * 3, ow * 3);
            } finally { small.UnlockBits(bd); }
            try { stdout.Write(frame, 0, frame.Length); stdout.Flush(); } catch (IOException) { return 0; }
            next += interval;
            long wait = next - DateTime.UtcNow.Ticks;
            if (wait > 0) Thread.Sleep((int) (wait / 10000));
            else next = DateTime.UtcNow.Ticks;
        }
        return 3;
    }
}
