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
  const int DM_POSITION = 0x20, DM_PELSWIDTH = 0x80000, DM_PELSHEIGHT = 0x100000;
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

  // width/height 0 detaches the display from the desktop
  public static int Set(string name, int x, int y, int w, int h) {
    var dm = New();
    dm.Fields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT;
    dm.PosX = x; dm.PosY = y; dm.PelsWidth = w; dm.PelsHeight = h;
    int r = ChangeDisplaySettingsEx(name, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
    int r2 = ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
    return r != 0 ? r : r2;
  }
}
'@
[void][WiiuuDisplay]::SetProcessDPIAware()
switch ($Action) {
  "list"   { [WiiuuDisplay]::List() }
  "attach" { "result " + [WiiuuDisplay]::Set($Device, $X, $Y, $W, $H) }
  "detach" { "result " + [WiiuuDisplay]::Set($Device, 0, 0, 0, 0) }
}
