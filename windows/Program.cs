using System.IO.Pipes;
using System.Text;

namespace KnnPary;

static class Program
{
    public const string PipeName = "KNNPary.pipe.v1";
    public const string MutexName = "KNNPary.single.v1";
    public static readonly string Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KNNPary");
    public static readonly string InstallDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "KNN pary");
    public static string InstalledExe => Path.Combine(InstallDir, "KNN-pary.exe");
    public static string ExePath => Environment.ProcessPath ?? Application.ExecutablePath;

    [STAThread]
    static void Main(string[] args)
    {
        Directory.CreateDirectory(Home);
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        // A downloaded copy installs itself into the user's programs folder and starts from there.
        if (!args.Contains("--portable") && !IsInstalled() && Installer.Run(args)) return;

        using var mutex = new Mutex(true, MutexName, out bool first);
        if (!first)
        {
            Send(args.Length == 0 ? new[] { "--show" } : args);
            return;
        }
        Application.Run(new App(args));
        GC.KeepAlive(mutex);
    }

    public static bool IsInstalled() =>
        string.Equals(Path.GetFullPath(ExePath), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase);

    /// <summary>Passes arguments to the copy that is already running.</summary>
    public static bool Send(string[] args)
    {
        try
        {
            using var c = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
            c.Connect(3000);
            var bytes = Encoding.UTF8.GetBytes(string.Join("\n", args));
            c.Write(bytes, 0, bytes.Length);
            c.Flush();
            return true;
        }
        catch { return false; }
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
