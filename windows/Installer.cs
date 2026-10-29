using System.Diagnostics;

namespace KnnPary;

static class Installer
{
    /// <summary>
    /// Copies this exe to %LOCALAPPDATA%\Programs\KNN pary, adds Desktop and Start menu shortcuts
    /// and starts the installed copy. Returns true when the installed copy was started.
    /// </summary>
    public static bool Run(string[] args)
    {
        try
        {
            Directory.CreateDirectory(Program.InstallDir);
            if (Mutex.TryOpenExisting(Program.MutexName, out var m))
            {
                m.Dispose();
                Program.Send(new[] { "--quit" });
            }
            Exception last = null;
            for (int i = 0; i < 20; i++)
            {
                try { File.Copy(Program.ExePath, Program.InstalledExe, true); last = null; break; }
                catch (Exception e) { last = e; Thread.Sleep(500); }
            }
            if (last != null) { Program.Log("install: copy failed: " + last.Message); return false; }

            Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "КНН пары.lnk"));
            Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "КНН пары.lnk"));

            var psi = new ProcessStartInfo(Program.InstalledExe) { UseShellExecute = false, WorkingDirectory = Program.InstallDir };
            foreach (var a in args) psi.ArgumentList.Add(a);
            Process.Start(psi);
            Program.Log("installed to " + Program.InstalledExe);
            return true;
        }
        catch (Exception e)
        {
            Program.Log("install failed: " + e);
            return false;
        }
    }

    static void Shortcut(string lnk)
    {
        try
        {
            var t = Type.GetTypeFromProgID("WScript.Shell");
            if (t == null) return;
            dynamic shell = Activator.CreateInstance(t);
            dynamic s = shell.CreateShortcut(lnk);
            s.TargetPath = Program.InstalledExe;
            s.WorkingDirectory = Program.InstallDir;
            s.IconLocation = Program.InstalledExe + ",0";
            s.Description = "КНН пары: расписание и автовход на пары";
            s.Save();
        }
        catch (Exception e) { Program.Log("shortcut: " + e.Message); }
    }
}
