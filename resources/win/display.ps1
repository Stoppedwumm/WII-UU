# WII-UU: lists displays and switches one on or off (Windows display settings; no admin needed).
#   display.ps1 -Action list                          one line per display adapter:
#       name <tab> adapter <tab> attached|detached <tab> primary|"" <tab> x,y,w,h (if attached) <tab> modes "WxH WxH ..." <tab> device id <tab> monitor
#   display.ps1 -Action attach -Device \\.\DISPLAY3 -X 1920 -Y 0 -W 2560 -H 1440
#   display.ps1 -Action detach -Device \\.\DISPLAY3
# Positions and sizes are real pixels. Used for split DS/3DS screens (a virtual display the TV doesn't show).
param([string]$Action = "list", [string]$Device = "", [int]$X = 0, [int]$Y = 0, [int]$W = 0, [int]$H = 0)
Add-Type @'
using System; using System.Collections.Generic; using System.Runtime.InteropServices;
public static class WiiuuDisplay {
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  public struct DD { public int cb;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string Name;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Str; public int Flags;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Id;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Key; }
  // DEVMODEW, display variant
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  public struct DM {
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string DeviceName;
    public short SpecVersion, DriverVersion, Size, DriverExtra; public int Fields;
    public int PosX, PosY, Orientation, FixedOutput;
    public short Color, Duplex, YResolution, TTOption, Collate;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string FormName;
    public short LogPixels; public int BitsPerPel, PelsWidth, PelsHeight, DisplayFlags, DisplayFrequency;
    public int ICMMethod, ICMIntent, MediaType, DitherType, Reserved1, Reserved2, PanningWidth, PanningHeight; }
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern bool EnumDisplayDevices(string d, int i, ref DD dd, int f);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern bool EnumDisplaySettings(string d, int m, ref DM dm);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string d, ref DM dm, IntPtr h, int f, IntPtr p);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string d, IntPtr dm, IntPtr h, int f, IntPtr p);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  const int DM_POSITION = 0x20, DM_BITSPERPEL = 0x40000, DM_PELSWIDTH = 0x80000, DM_PELSHEIGHT = 0x100000, DM_DISPLAYFREQUENCY = 0x400000;
  const int CDS_UPDATEREGISTRY = 0x1, CDS_NORESET = 0x10000000;

  static DM New() { var dm = new DM(); dm.Size = (short) Marshal.SizeOf(typeof(DM)); return dm; }

  public static void List() {
    var dd = new DD(); dd.cb = Marshal.SizeOf(dd);
    for (int i = 0; EnumDisplayDevices(null, i, ref dd, 0); i++) {
      bool attached = (dd.Flags & 1) != 0, primary = (dd.Flags & 4) != 0;
      var cur = New();
      string rect = attached && EnumDisplaySettings(dd.Name, -1, ref cur) ? cur.PosX + "," + cur.PosY + "," + cur.PelsWidth + "," + cur.PelsHeight : "";
      var modes = new SortedSet<string>();
      var m = New();
      for (int k = 0; EnumDisplaySettings(dd.Name, k, ref m); k++) { modes.Add(m.PelsWidth + "x" + m.PelsHeight); m = New(); }
      var mon = new DD(); mon.cb = Marshal.SizeOf(mon);
      string monitor = EnumDisplayDevices(dd.Name, 0, ref mon, 0) ? mon.Str + " " + mon.Id : "";
      Console.WriteLine(dd.Name + "\t" + dd.Str + "\t" + (attached ? "attached" : "detached") + "\t" + (primary ? "primary" : "") + "\t" + rect + "\t" + string.Join(" ", modes) + "\t" + dd.Id + "\t" + monitor);
      dd.cb = Marshal.SizeOf(dd);
    }
  }

  static int Apply(string name, ref DM dm) {
    int r = ChangeDisplaySettingsEx(name, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
    int r2 = ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
    return r != 0 ? r : r2;
  }

  public static bool Attached(string name) {
    var dd = new DD(); dd.cb = Marshal.SizeOf(dd);
    for (int i = 0; EnumDisplayDevices(null, i, ref dd, 0); i++) {
      if (dd.Name == name) return (dd.Flags & 1) != 0;
      dd.cb = Marshal.SizeOf(dd);
    }
    return false;
  }

  static int Place(string name, int x, int y, int w, int h, bool full) {
    var dm = New();
    dm.Fields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | (full ? DM_BITSPERPEL | DM_DISPLAYFREQUENCY : 0);
    dm.PosX = x; dm.PosY = y; dm.PelsWidth = w; dm.PelsHeight = h; dm.BitsPerPel = 32; dm.DisplayFrequency = 60;
    return Apply(name, ref dm);
  }

  // ---- the modern display API (QueryDisplayConfig / SetDisplayConfig), for drivers the old one can't switch on
  [DllImport("user32.dll")] static extern int GetDisplayConfigBufferSizes(uint flags, out uint paths, out uint modes);
  [DllImport("user32.dll")] static extern int QueryDisplayConfig(uint flags, ref uint numPaths, byte[] paths, ref uint numModes, byte[] modes, IntPtr topology);
  [DllImport("user32.dll")] static extern int SetDisplayConfig(uint numPaths, byte[] paths, uint numModes, byte[] modes, uint flags);
  [DllImport("user32.dll")] static extern int DisplayConfigGetDeviceInfo(byte[] packet);
  const uint QDC_ALL_PATHS = 1, QDC_ONLY_ACTIVE_PATHS = 2;
  const uint SDC_TOPOLOGY_EXTEND = 0x4, SDC_USE_SUPPLIED_DISPLAY_CONFIG = 0x20, SDC_APPLY = 0x80, SDC_SAVE_TO_DATABASE = 0x200, SDC_ALLOW_CHANGES = 0x400;
  const int PATH = 72, MODE = 64;            // sizeof DISPLAYCONFIG_PATH_INFO / DISPLAYCONFIG_MODE_INFO
  const uint ACTIVE = 1, INVALID = 0xffffffff;

  // the GDI name (\\.\DISPLAYn) of a path's source: adapter LUID at +0, source id at +8
  static string SourceName(byte[] paths, int i) {
    var p = new byte[20 + 64];
    BitConverter.GetBytes(1).CopyTo(p, 0);                   // DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME
    BitConverter.GetBytes(p.Length).CopyTo(p, 4);
    Array.Copy(paths, i * PATH, p, 8, 8);                    // adapter LUID
    Array.Copy(paths, i * PATH + 8, p, 16, 4);               // source id
    if (DisplayConfigGetDeviceInfo(p) != 0) return "";
    return System.Text.Encoding.Unicode.GetString(p, 20, 64).TrimEnd('\0');
  }

  static bool Query(uint flags, out byte[] paths, out uint np, out byte[] modes, out uint nm) {
    paths = null; modes = null; np = 0; nm = 0;
    if (GetDisplayConfigBufferSizes(flags, out np, out nm) != 0) return false;
    paths = new byte[np * PATH]; modes = new byte[nm * MODE];
    return QueryDisplayConfig(flags, ref np, paths, ref nm, modes, IntPtr.Zero) == 0;
  }

  static uint U(byte[] b, int at) { return BitConverter.ToUInt32(b, at); }
  static void Put(byte[] b, int at, uint v) { BitConverter.GetBytes(v).CopyTo(b, at); }

  // switches on the first possible path of this source, keeping every active path as it is
  static int EnablePath(string name) {
    byte[] all, allModes; uint na, nma;
    if (!Query(QDC_ALL_PATHS, out all, out na, out allModes, out nma)) return -100;
    byte[] act, modes; uint n, nm;
    if (!Query(QDC_ONLY_ACTIVE_PATHS, out act, out n, out modes, out nm)) return -101;
    for (int i = 0; i < na; i++) {
      bool active = (U(all, i * PATH + 68) & ACTIVE) != 0, available = U(all, i * PATH + 60) != 0;
      if (active || !available || SourceName(all, i) != name) continue;
      var paths = new byte[(n + 1) * PATH];
      Array.Copy(act, paths, n * PATH);
      Array.Copy(all, i * PATH, paths, n * PATH, PATH);
      int at = (int) n * PATH;
      Put(paths, at + 12, INVALID);                           // source mode: let Windows choose
      Put(paths, at + 32, INVALID);                           // target mode
      Put(paths, at + 68, ACTIVE);
      return SetDisplayConfig(n + 1, paths, nm, modes, SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES | SDC_SAVE_TO_DATABASE);
    }
    return -102;                                              // no free path for it
  }

  // switches off this source's path, keeping every other active path
  static int DisablePath(string name) {
    byte[] act, modes; uint n, nm;
    if (!Query(QDC_ONLY_ACTIVE_PATHS, out act, out n, out modes, out nm)) return -101;
    var keep = new List<byte[]>();
    for (int i = 0; i < n; i++) {
      if (SourceName(act, i) == name) continue;
      var one = new byte[PATH]; Array.Copy(act, i * PATH, one, 0, PATH); keep.Add(one);
    }
    if (keep.Count == n) return -102;
    var paths = new byte[keep.Count * PATH];
    for (int i = 0; i < keep.Count; i++) keep[i].CopyTo(paths, i * PATH);
    return SetDisplayConfig((uint) keep.Count, paths, nm, modes, SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES | SDC_SAVE_TO_DATABASE);
  }

  // switches the display on at x,y with w x h; tries one way after the other and says what each did
  public static string Attach(string name, int x, int y, int w, int h) {
    var log = new List<string>();
    log.Add("old api " + Place(name, x, y, w, h, true));
    if (!Attached(name)) {
      var reg = New();
      if (EnumDisplaySettings(name, -2, ref reg) && reg.PelsWidth > 0) {
        reg.Fields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT; reg.PosX = x; reg.PosY = y;
        log.Add("old api, saved size " + Apply(name, ref reg));
      }
    }
    if (!Attached(name)) log.Add("display config " + EnablePath(name));
    if (!Attached(name)) log.Add("extend " + SetDisplayConfig(0, null, 0, null, SDC_APPLY | SDC_TOPOLOGY_EXTEND));
    if (Attached(name)) log.Add("place " + Place(name, x, y, w, h, false));
    return string.Join(", ", log) + (Attached(name) ? " -> on" : " -> still off");
  }

  public static string Detach(string name) {
    var log = new List<string>();
    log.Add("old api " + Place(name, 0, 0, 0, 0, false));
    if (Attached(name)) log.Add("display config " + DisablePath(name));
    return string.Join(", ", log) + (Attached(name) ? " -> still on" : " -> off");
  }
}
'@
[void][WiiuuDisplay]::SetProcessDPIAware()
switch ($Action) {
  "list"   { [WiiuuDisplay]::List() }
  "attach" { "result " + [WiiuuDisplay]::Attach($Device, $X, $Y, $W, $H) }
  "detach" { "result " + [WiiuuDisplay]::Detach($Device) }
}
