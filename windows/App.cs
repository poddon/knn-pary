using System.Diagnostics;
using System.Globalization;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;

namespace KnnPary;

public class PlanItem
{
    public long start { get; set; }
    public long end { get; set; }
    public string title { get; set; } = "";
    public string body { get; set; } = "";
    public string url { get; set; }
    public string joinTitle { get; set; } = "";
}

public class PlanData
{
    public string fio { get; set; } = "";
    public string group { get; set; } = "";
    public bool auto { get; set; } = true;
    public List<PlanItem> items { get; set; } = new();
}

/// <summary>Tray app: keeps the pair plan, wakes the computer, notifies and joins pairs by itself.</summary>
class App : ApplicationContext
{
    public static App I { get; private set; }
    public readonly NotifyIcon Tray;
    public readonly Icon AppIcon;
    public PlanData Plan = new();

    static readonly JsonSerializerOptions JsonOpt = new() { PropertyNameCaseInsensitive = true };
    static Task<CoreWebView2Environment> env;
    readonly Control invoker = new();
    readonly System.Windows.Forms.Timer tick = new() { Interval = 10_000 };
    readonly System.Windows.Forms.Timer sharedTick = new() { Interval = 15 * 60_000 };
    List<PlanItem> sharedItems = new();
    readonly HashSet<string> notified = new(), joined = new();
    MainForm main;
    JoinForm join;
    PlanItem balloonItem;
    bool exiting, awake;
    string scheduledHash = "";

    static string PlanPath => Path.Combine(Program.Home, "plan.json");
    static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public static Task<CoreWebView2Environment> Env() =>
        env ??= CoreWebView2Environment.CreateAsync(null, Path.Combine(Program.Home, "WebView2"),
            new CoreWebView2EnvironmentOptions("--autoplay-policy=no-user-gesture-required"));

    public App(string[] args)
    {
        I = this;
        invoker.CreateControl();
        _ = invoker.Handle;
        using (var s = typeof(App).Assembly.GetManifestResourceStream("app.ico")) AppIcon = new Icon(s);
        Tray = new NotifyIcon { Icon = new Icon(AppIcon, SystemInformation.SmallIconSize), Text = "КНН пары", Visible = true };
        Tray.ContextMenuStrip = BuildMenu();
        Tray.DoubleClick += (s, e) => ShowMain();
        Tray.BalloonTipClicked += (s, e) =>
        {
            if (balloonItem?.url != null) Join(balloonItem, false);
            else ShowMain();
        };

        LoadPlan();
        if (Autostart) SetAutostart(true);
        tick.Tick += (s, e) => Tick();
        tick.Start();
        sharedItems = SharedSchedule.Items(Plan.group);
        sharedTick.Tick += (s, e) => SyncShared();
        sharedTick.Start();
        SyncShared();
        StartPipe();
        FirstRun();
        Handle(args, true);
        ScheduleWakes();
    }

    // ---------- commands from the command line, the second copy and the wake task ----------

    void Handle(string[] args, bool startup)
    {
        if (args.Contains("--quit")) { Exit(); return; }
        string pdf = args.FirstOrDefault(a => a.EndsWith(".pdf", StringComparison.OrdinalIgnoreCase) && File.Exists(a));
        if (pdf != null) OpenPdf(pdf);
        if (args.Contains("--wake")) { Program.Log("woken by task"); Tick(); SyncShared(); }
        if (args.Contains("--show") || (startup && args.Length == 0)) ShowMain();
    }

    void StartPipe()
    {
        var t = new Thread(() =>
        {
            while (!exiting)
            {
                try
                {
                    using var s = new NamedPipeServerStream(Program.PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte);
                    s.WaitForConnection();
                    using var r = new StreamReader(s, Encoding.UTF8);
                    var args = r.ReadToEnd().Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
                    invoker.BeginInvoke(new Action(() => Handle(args, false)));
                }
                catch (Exception e)
                {
                    Program.Log("pipe: " + e.Message);
                    Thread.Sleep(1000);
                }
            }
        })
        { IsBackground = true };
        t.Start();
    }

    // ---------- plan ----------

    void LoadPlan()
    {
        try
        {
            if (File.Exists(PlanPath)) Plan = JsonSerializer.Deserialize<PlanData>(File.ReadAllText(PlanPath), JsonOpt) ?? new();
        }
        catch (Exception e) { Program.Log("plan load: " + e.Message); }
        Plan.items ??= new();
    }

    /// <summary>Pairs from the page plus pairs from the schedule the admin published.</summary>
    IEnumerable<PlanItem> Items => Plan.items.Concat(sharedItems.Where(s => !Plan.items.Any(p => p.start == s.start)));

    async void SyncShared(bool manual = false)
    {
        bool changed = await SharedSchedule.Download();
        if (manual)
            Notify("КНН пары", !SharedSchedule.LastOk ? "Нет связи с сервером расписания" : changed ? "Расписание обновлено" : "Новых изменений нет, расписание актуально", null);
        var items = SharedSchedule.Items(Plan.group);
        if (!changed && items.Count == sharedItems.Count) return;
        sharedItems = items;
        ScheduleWakes();
        Tick();
        if (changed) main?.Reload();
    }

    void SetPlan(PlanData p)
    {
        Plan = p ?? new();
        Plan.items ??= new();
        Plan.group ??= "";
        sharedItems = SharedSchedule.Items(Plan.group);
        try { File.WriteAllText(PlanPath, JsonSerializer.Serialize(Plan)); }
        catch (Exception e) { Program.Log("plan save: " + e.Message); }
        ScheduleWakes();
        Tick();
    }

    void ScheduleWakes()
    {
        long now = Now;
        var starts = Plan.auto
            ? Items.Where(i => i.url != null && i.start - Wake.LeadMinutes * 60_000L > now + 30_000).Select(i => i.start).Distinct().OrderBy(x => x).Take(40).ToList()
            : new List<long>();
        string hash = string.Join(",", starts) + "|" + Program.ExePath;
        if (hash == scheduledHash) return;
        scheduledHash = hash;
        Task.Run(() => Wake.Replace(starts));
    }

    public void OnPageMessage(string json)
    {
        try
        {
            using var d = JsonDocument.Parse(json);
            var r = d.RootElement;
            string type = r.TryGetProperty("type", out var t) ? t.GetString() : "";
            if (type == "plan")
            {
                SetPlan(JsonSerializer.Deserialize<PlanData>(json, JsonOpt));
            }
            else if (type == "join")
            {
                if (r.TryGetProperty("fio", out var f) && f.ValueKind == JsonValueKind.String) Plan.fio = f.GetString();
                string title = r.TryGetProperty("title", out var tt) && tt.ValueKind == JsonValueKind.String ? tt.GetString() : "Пара";
                JoinUrl(r.GetProperty("url").GetString(), title, 0);
            }
        }
        catch (Exception e) { Program.Log("page message: " + e.Message); }
    }

    // ---------- timing ----------

    void Tick()
    {
        long now = Now;
        bool needAwake = join != null && !join.IsDisposed;
        bool canJoin = Plan.auto && !string.IsNullOrEmpty(Plan.fio);
        foreach (var it in Items.OrderBy(i => i.start))
        {
            string key = it.start + "|" + it.url;
            if (canJoin && it.url != null && now >= it.start - 10 * 60_000L && now < it.end) needAwake = true;

            if (!notified.Contains(key) && now >= it.start - Wake.LeadMinutes * 60_000L && now < it.start + 10 * 60_000L)
            {
                notified.Add(key);
                string body = (it.body ?? "").Replace(" · нажми, чтобы войти", "");
                if (canJoin && it.url != null) body += " · захожу сам";
                else if (it.url != null) body += " · нажми, чтобы войти";
                Notify(it.title, body, it);
            }

            if (canJoin && it.url != null && !joined.Contains(key) && now >= it.start - 60_000L && now < it.end - 5 * 60_000L)
            {
                joined.Add(key);
                Program.Log("auto join: " + it.title);
                Join(it, true);
            }
        }
        KeepAwake(needAwake);
    }

    [DllImport("kernel32.dll")]
    static extern uint SetThreadExecutionState(uint flags);
    const uint ES_CONTINUOUS = 0x80000000, ES_SYSTEM_REQUIRED = 0x00000001;

    /// <summary>Stops Windows from going back to sleep while a pair is near or in progress.</summary>
    void KeepAwake(bool on)
    {
        if (on == awake) return;
        awake = on;
        SetThreadExecutionState(on ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED : ES_CONTINUOUS);
        Program.Log(on ? "keep awake: on" : "keep awake: off");
    }

    // ---------- windows ----------

    public void Join(PlanItem it, bool auto) => JoinUrl(it.url, string.IsNullOrEmpty(it.joinTitle) ? it.title : it.joinTitle, auto ? it.end : 0);

    public void JoinUrl(string url, string title, long endMs)
    {
        if (string.IsNullOrEmpty(url)) return;
        if (join == null || join.IsDisposed)
        {
            join = new JoinForm();
            join.FormClosed += (s, e) => { join = null; Tick(); };
        }
        join.Go(url, title, Plan.fio, endMs);
        if (!join.Visible) join.Show();
        if (endMs == 0) join.Activate();
        Tick();
    }

    public void ShowMain()
    {
        if (main == null || main.IsDisposed) main = new MainForm();
        if (!main.Visible) main.Show();
        if (main.WindowState == FormWindowState.Minimized) main.WindowState = FormWindowState.Normal;
        main.Activate();
    }

    void OpenPdf(string path)
    {
        ShowMain();
        main.OpenPdf(path);
    }

    void PickPdf()
    {
        using var d = new OpenFileDialog { Filter = "Расписание PDF|*.pdf", Title = "Выбери PDF с расписанием" };
        if (d.ShowDialog() == DialogResult.OK) OpenPdf(d.FileName);
    }

    public void Notify(string title, string text, PlanItem item)
    {
        balloonItem = item;
        Tray.ShowBalloonTip(15000, string.IsNullOrWhiteSpace(title) ? "КНН пары" : title, string.IsNullOrWhiteSpace(text) ? " " : text, ToolTipIcon.Info);
    }

    public static void Open(string url)
    {
        try { if (!string.IsNullOrEmpty(url)) Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception e) { Program.Log("open: " + e.Message); }
    }

    // ---------- tray menu, autostart, power ----------

    ContextMenuStrip BuildMenu()
    {
        var m = new ContextMenuStrip();
        var next = new ToolStripMenuItem("Ближайшая пара") { Enabled = false };
        m.Items.Add(next);
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Открыть «КНН пары»", null, (s, e) => ShowMain());
        m.Items.Add("Загрузить PDF расписания…", null, (s, e) => PickPdf());
        m.Items.Add("Обновить расписание", null, (s, e) => SyncShared(true));
        m.Items.Add(new ToolStripSeparator());
        var auto = new ToolStripMenuItem("Запускать вместе с Windows");
        auto.Click += (s, e) => SetAutostart(!Autostart);
        m.Items.Add(auto);
        m.Items.Add("Разрешить ноутбуку просыпаться перед парами", null, (s, e) => AllowWake());
        m.Items.Add("Открыть журнал", null, (s, e) => Open(Program.Home));
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Выход", null, (s, e) => Exit());
        m.Opening += (s, e) => { auto.Checked = Autostart; next.Text = NextText(); };
        return m;
    }

    string NextText()
    {
        long now = Now;
        var n = Items.Where(i => i.end > now).OrderBy(i => i.start).FirstOrDefault();
        if (n == null) return "Пар нет: загрузи PDF с расписанием";
        var t = DateTimeOffset.FromUnixTimeMilliseconds(n.start).ToLocalTime();
        string what = string.IsNullOrEmpty(n.joinTitle) ? n.title : n.joinTitle;
        if (what.Length > 40) what = what[..40] + "…";
        return "Ближайшая: " + t.ToString("ddd dd.MM HH:mm", new CultureInfo("ru-RU")) + " · " + what;
    }

    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static bool Autostart
    {
        get
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue("KNN pary") != null;
        }
    }

    public static void SetAutostart(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(RunKey);
            if (on) k.SetValue("KNN pary", "\"" + Program.ExePath + "\" --tray");
            else k.DeleteValue("KNN pary", false);
        }
        catch (Exception e) { Program.Log("autostart: " + e.Message); }
    }

    /// <summary>Turns on "Allow wake timers" for battery and mains (asks for administrator confirmation).</summary>
    public static void AllowWake()
    {
        try
        {
            Process.Start(new ProcessStartInfo("cmd.exe",
                "/c powercfg /setacvalueindex SCHEME_CURRENT SUB_SLEEP RTCWAKE 1 & powercfg /setdcvalueindex SCHEME_CURRENT SUB_SLEEP RTCWAKE 1 & powercfg /setactive SCHEME_CURRENT")
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            });
            Program.Log("wake timers: enabled");
        }
        catch (Exception e) { Program.Log("wake timers: " + e.Message); }
    }

    void FirstRun()
    {
        var flag = Path.Combine(Program.Home, "setup.v1");
        if (File.Exists(flag)) return;
        try { File.WriteAllText(flag, DateTime.Now.ToString("o")); } catch { }
        SetAutostart(true);
        invoker.BeginInvoke(new Action(() =>
        {
            var r = MessageBox.Show(
                "Чтобы ноутбук сам просыпался перед парами, в Windows нужно включить «таймеры пробуждения».\n\n" +
                "Включить сейчас? Windows попросит подтверждение.\n\n" +
                "Это можно сделать и позже: значок «КНН пары» у часов → «Разрешить ноутбуку просыпаться перед парами».",
                "КНН пары", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (r == DialogResult.Yes) AllowWake();
        }));
    }

    public void Exit()
    {
        exiting = true;
        tick.Stop();
        sharedTick.Stop();
        KeepAwake(false);
        Tray.Visible = false;
        if (main != null && !main.IsDisposed) main.CloseForReal();
        if (join != null && !join.IsDisposed) join.Close();
        ExitThread();
    }
}
