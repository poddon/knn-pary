using System.Diagnostics;

namespace KnnDomashka;

static class Program
{
    public const string MutexName = "KNNDomashka.single.v1";
    public const string ShowEvent = "KNNDomashka.show.v1";
    public static readonly string Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KNNDomashka");
    public static readonly string InstallDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "KNN domashka");
    public static string InstalledExe => Path.Combine(InstallDir, "KNN-domashka.exe");
    public static string ExePath => Environment.ProcessPath ?? Application.ExecutablePath;

    [STAThread]
    static void Main(string[] args)
    {
        Directory.CreateDirectory(Home);
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        if (!args.Contains("--portable") && !IsInstalled() && Install(args)) return;

        using var mutex = new Mutex(true, MutexName, out bool first);
        if (!first)
        {
            try { using var ev = EventWaitHandle.OpenExisting(ShowEvent); ev.Set(); } catch { }
            return;
        }
        Application.Run(new App(args));
        GC.KeepAlive(mutex);
    }

    static bool IsInstalled() => string.Equals(Path.GetFullPath(ExePath), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase);

    /// <summary>Copies itself to %LOCALAPPDATA%\Programs, adds shortcuts and starts the installed copy.</summary>
    static bool Install(string[] args)
    {
        try
        {
            Directory.CreateDirectory(InstallDir);
            foreach (var p in Process.GetProcessesByName("KNN-domashka"))
                if (p.Id != Environment.ProcessId) { try { p.Kill(); p.WaitForExit(3000); } catch { } }
            Exception last = null;
            for (int i = 0; i < 20; i++)
            {
                try { File.Copy(ExePath, InstalledExe, true); last = null; break; }
                catch (Exception e) { last = e; Thread.Sleep(500); }
            }
            if (last != null) { Log("install: " + last.Message); return false; }
            Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "КНН домашка.lnk"));
            Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "КНН домашка.lnk"));
            var psi = new ProcessStartInfo(InstalledExe) { UseShellExecute = false, WorkingDirectory = InstallDir };
            foreach (var a in args) psi.ArgumentList.Add(a);
            Process.Start(psi);
            return true;
        }
        catch (Exception e) { Log("install: " + e); return false; }
    }

    static void Shortcut(string lnk)
    {
        try
        {
            var t = Type.GetTypeFromProgID("WScript.Shell");
            if (t == null) return;
            dynamic shell = Activator.CreateInstance(t);
            dynamic s = shell.CreateShortcut(lnk);
            s.TargetPath = InstalledExe;
            s.WorkingDirectory = InstallDir;
            s.IconLocation = InstalledExe + ",0";
            s.Description = "КНН домашка: задания и конспекты";
            s.Save();
        }
        catch (Exception e) { Log("shortcut: " + e.Message); }
    }

    public static void Log(string msg)
    {
        try
        {
            var p = Path.Combine(Home, "log.txt");
            if (File.Exists(p) && new FileInfo(p).Length > 512 * 1024) File.Delete(p);
            File.AppendAllText(p, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + msg + Environment.NewLine);
        }
        catch { }
    }
}
