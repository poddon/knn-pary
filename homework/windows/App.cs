using System.Diagnostics;
using System.Text.Json;
using Microsoft.Win32;

namespace KnnDomashka;

public class Reminder
{
    public long at { get; set; }
    public string title { get; set; } = "";
    public string text { get; set; } = "";
}

public class Settings
{
    public List<string> folders { get; set; } = new();
}

/// <summary>Tray app: keeps the window, the folder list and the deadline reminders.</summary>
class App : ApplicationContext
{
    public static App I { get; private set; }
    public readonly NotifyIcon Tray;
    public readonly Icon AppIcon;
    public Settings Settings = new();
    List<Reminder> reminders = new();
    readonly HashSet<long> fired = new();
    readonly System.Windows.Forms.Timer tick = new() { Interval = 20_000 };
    readonly Control invoker = new();
    MainForm main;

    static string SettingsPath => Path.Combine(Program.Home, "settings.json");
    static string RemindersPath => Path.Combine(Program.Home, "reminders.json");

    public App(string[] args)
    {
        I = this;
        invoker.CreateControl();
        _ = invoker.Handle;
        using (var s = typeof(App).Assembly.GetManifestResourceStream("app.ico")) AppIcon = new Icon(s);
        Tray = new NotifyIcon { Icon = new Icon(AppIcon, SystemInformation.SmallIconSize), Text = "КНН домашка", Visible = true };
        var m = new ContextMenuStrip();
        m.Items.Add("Открыть «КНН домашку»", null, (s, e) => ShowMain());
        var auto = new ToolStripMenuItem("Запускать вместе с Windows");
        auto.Click += (s, e) => SetAutostart(!Autostart);
        m.Items.Add(auto);
        m.Items.Add("Выход", null, (s, e) => Quit());
        m.Opening += (s, e) => auto.Checked = Autostart;
        Tray.ContextMenuStrip = m;
        Tray.DoubleClick += (s, e) => ShowMain();
        Tray.BalloonTipClicked += (s, e) => ShowMain();

        LoadSettings();
        if (Settings.folders.Count == 0) GuessFolder();
        try { if (File.Exists(RemindersPath)) reminders = JsonSerializer.Deserialize<List<Reminder>>(File.ReadAllText(RemindersPath)) ?? new(); } catch { }
        var flag = Path.Combine(Program.Home, "setup.v1");
        if (!File.Exists(flag)) { SetAutostart(true); try { File.WriteAllText(flag, DateTime.Now.ToString("o")); } catch { } }

        tick.Tick += (s, e) => Tick();
        tick.Start();
        var show = new EventWaitHandle(false, EventResetMode.AutoReset, Program.ShowEvent);
        new Thread(() => { while (show.WaitOne()) invoker.BeginInvoke(new Action(ShowMain)); }) { IsBackground = true }.Start();
        if (!args.Contains("--tray")) ShowMain();
        Tick();
    }

    void LoadSettings()
    {
        try { if (File.Exists(SettingsPath)) Settings = JsonSerializer.Deserialize<Settings>(File.ReadAllText(SettingsPath)) ?? new(); } catch { }
        Settings.folders ??= new();
    }

    public void SaveSettings()
    {
        try { File.WriteAllText(SettingsPath, JsonSerializer.Serialize(Settings)); } catch (Exception e) { Program.Log("settings: " + e.Message); }
    }

    /// <summary>The MAX desktop app saves files to Downloads (often in a MAX subfolder).</summary>
    void GuessFolder()
    {
        string dl = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        foreach (var name in new[] { "MAX", "Max", "max", "КНН" })
        {
            var p = Path.Combine(dl, name);
            if (Directory.Exists(p)) { Settings.folders.Add(p); SaveSettings(); return; }
        }
    }

    public void SetReminders(List<Reminder> list)
    {
        reminders = list ?? new();
        try { File.WriteAllText(RemindersPath, JsonSerializer.Serialize(reminders)); } catch { }
    }

    void Tick()
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        foreach (var r in reminders)
        {
            if (fired.Contains(r.at) || now < r.at || now > r.at + 6 * 3600_000L) continue;
            fired.Add(r.at);
            Tray.ShowBalloonTip(20000, r.title, string.IsNullOrWhiteSpace(r.text) ? " " : r.text, ToolTipIcon.Info);
        }
    }

    public void ShowMain()
    {
        if (main == null || main.IsDisposed) main = new MainForm();
        if (!main.Visible) main.Show();
        if (main.WindowState == FormWindowState.Minimized) main.WindowState = FormWindowState.Normal;
        main.Activate();
    }

    public void Notify(string title, string text) => Tray.ShowBalloonTip(8000, title, text, ToolTipIcon.Info);

    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    static bool Autostart { get { using var k = Registry.CurrentUser.OpenSubKey(RunKey); return k?.GetValue("KNN domashka") != null; } }
    static void SetAutostart(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(RunKey);
            if (on) k.SetValue("KNN domashka", "\"" + Program.ExePath + "\" --tray"); else k.DeleteValue("KNN domashka", false);
        }
        catch (Exception e) { Program.Log("autostart: " + e.Message); }
    }

    public static void OpenShell(string path)
    {
        try { Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); } catch (Exception e) { Program.Log("open: " + e.Message); }
    }

    void Quit()
    {
        tick.Stop();
        Tray.Visible = false;
        main?.CloseForReal();
        ExitThread();
    }
}
