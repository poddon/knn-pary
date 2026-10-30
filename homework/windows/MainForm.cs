using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace KnnDomashka;

/// <summary>The window: the shared page served from https://domashka.local, files from the chosen folders.</summary>
class MainForm : Form
{
    const string Origin = "https://domashka.local";
    readonly WebView2 web = new() { Dock = DockStyle.Fill };
    readonly List<FileSystemWatcher> watchers = new();
    readonly System.Windows.Forms.Timer debounce = new() { Interval = 1500 };
    bool real, hintShown;
    static readonly string Inbox = Path.Combine(Program.Home, "inbox");
    static readonly string[] Exts = { ".jpg", ".jpeg", ".png", ".webp", ".gif", ".pdf", ".docx", ".txt" };

    public MainForm()
    {
        Text = "КНН домашка";
        Icon = App.I.AppIcon;
        AutoScaleMode = AutoScaleMode.None;
        float k = DeviceDpi / 96f;
        var wa = Screen.PrimaryScreen.WorkingArea;
        Size = new Size(Math.Min((int)(760 * k), wa.Width), Math.Min((int)(900 * k), wa.Height - 20));
        MinimumSize = new Size((int)(380 * k), (int)(500 * k));
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(0xF1, 0xF5, 0xF3);
        Controls.Add(web);
        debounce.Tick += (s, e) => { debounce.Stop(); Emit("files-changed", null); };
        Load += async (s, e) => await Init();
    }

    async Task Init()
    {
        try
        {
            var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Program.Home, "WebView2"));
            await web.EnsureCoreWebView2Async(env);
        }
        catch (Exception e)
        {
            Program.Log("webview: " + e);
            if (MessageBox.Show("Нужен компонент Microsoft Edge WebView2 (обычно он уже есть в Windows 10 и 11).\n\nСкачать его?", "КНН домашка",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
                App.OpenShell("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
            return;
        }
        var c = web.CoreWebView2;
        c.Settings.IsStatusBarEnabled = false;
        c.Settings.AreDefaultContextMenusEnabled = true;
        c.AddWebResourceRequestedFilter(Origin + "/*", CoreWebView2WebResourceContext.All);
        c.WebResourceRequested += Serve;
        c.WebMessageReceived += (s, e) => OnMessage(e.WebMessageAsJson);
        c.NewWindowRequested += (s, e) => { e.Handled = true; App.OpenShell(e.Uri); };
        c.NavigationStarting += (s, e) => { if (!e.Uri.StartsWith(Origin, StringComparison.OrdinalIgnoreCase)) { e.Cancel = true; App.OpenShell(e.Uri); } };
        Watch();
        c.Navigate(Origin + "/index.html");
    }

    // ---------- local server ----------
    void Serve(object sender, CoreWebView2WebResourceRequestedEventArgs e)
    {
        var uri = new Uri(e.Request.Uri);
        var env = web.CoreWebView2.Environment;
        try
        {
            string p = uri.AbsolutePath;
            Stream body = null; string mime = "text/html; charset=utf-8";
            if (p == "/" || p == "/index.html") body = typeof(MainForm).Assembly.GetManifestResourceStream("index.html");
            else if (p.StartsWith("/f/"))
            {
                string path = Decode(p[3..]);
                if (Allowed(path) && File.Exists(path)) { body = File.OpenRead(path); mime = Mime(path); }
            }
            if (body == null) { e.Response = env.CreateWebResourceResponse(null, 404, "Not Found", ""); return; }
            e.Response = env.CreateWebResourceResponse(body, 200, "OK", "Content-Type: " + mime + "\r\nCache-Control: no-store");
        }
        catch (Exception ex)
        {
            Program.Log("serve: " + ex.Message);
            e.Response = env.CreateWebResourceResponse(null, 500, "Error", "");
        }
    }

    static string Encode(string path) => Convert.ToBase64String(Encoding.UTF8.GetBytes(path)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    static string Decode(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/');
        s += new string('=', (4 - s.Length % 4) % 4);
        return Encoding.UTF8.GetString(Convert.FromBase64String(s));
    }

    static bool Allowed(string path)
    {
        var full = Path.GetFullPath(path);
        if (full.StartsWith(Path.GetFullPath(Inbox) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return true;
        return App.I.Settings.folders.Any(f => full.StartsWith(Path.GetFullPath(f).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase));
    }

    static string Mime(string p) => Path.GetExtension(p).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg",
        ".png" => "image/png",
        ".webp" => "image/webp",
        ".gif" => "image/gif",
        ".pdf" => "application/pdf",
        ".docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    };

    // ---------- bridge ----------
    void OnMessage(string json)
    {
        string id = null;
        try
        {
            var m = JsonNode.Parse(json);
            id = (string)m["id"];
            string method = (string)m["method"];
            var args = m["args"] as JsonObject ?? new JsonObject();
            JsonNode result = null;
            switch (method)
            {
                case "hello": result = new JsonObject { ["platform"] = "windows" }; break;
                case "folders": result = Folders(); break;
                case "list": Reply(id, null, true); _ = Task.Run(() => List()).ContinueWith(t => BeginInvoke(() => Reply(id, t.Result))); return;
                case "pickFolder": BeginInvoke(PickFolder); break;
                case "addFiles": BeginInvoke(AddFiles); break;
                case "removeFolder":
                    App.I.Settings.folders.RemoveAll(f => f == (string)args["id"]);
                    App.I.SaveSettings(); Watch(); result = true; break;
                case "open":
                    {
                        string fid = (string)args["id"];
                        if (fid != null && Allowed(fid) && File.Exists(fid)) App.OpenShell(fid);
                        break;
                    }
                case "setReminders":
                    App.I.SetReminders(args["items"]?.Deserialize<List<Reminder>>() ?? new()); result = true; break;
            }
            Reply(id, result);
        }
        catch (Exception e)
        {
            Program.Log("message: " + e.Message);
            if (id != null) Reply(id, null);
        }
    }

    void Reply(string id, JsonNode result, bool skip = false)
    {
        if (skip || IsDisposed || web.CoreWebView2 == null) return;
        web.CoreWebView2.PostWebMessageAsJson(new JsonObject { ["id"] = id, ["result"] = result?.DeepClone() }.ToJsonString());
    }

    void Emit(string name, JsonNode data)
    {
        if (IsDisposed || web.CoreWebView2 == null) return;
        web.CoreWebView2.PostWebMessageAsJson(new JsonObject { ["event"] = name, ["data"] = data?.DeepClone() }.ToJsonString());
    }

    static JsonArray Folders()
    {
        var a = new JsonArray();
        foreach (var f in App.I.Settings.folders) a.Add(new JsonObject { ["id"] = f, ["label"] = Pretty(f) });
        return a;
    }

    static string Pretty(string f)
    {
        string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return f.StartsWith(home, StringComparison.OrdinalIgnoreCase) ? f[(home.Length + 1)..] : f;
    }

    static JsonNode List()
    {
        var a = new JsonArray();
        int budget = 2000;
        Directory.CreateDirectory(Inbox);
        foreach (var root in App.I.Settings.folders.Append(Inbox))
        {
            if (!Directory.Exists(root)) continue;
            var opt = new EnumerationOptions { RecurseSubdirectories = true, MaxRecursionDepth = 3, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.Hidden | FileAttributes.System };
            IEnumerable<string> files;
            try { files = Directory.EnumerateFiles(root, "*", opt); } catch { continue; }
            foreach (var path in files)
            {
                if (budget-- <= 0) break;
                if (!Exts.Contains(Path.GetExtension(path).ToLowerInvariant())) continue;
                try
                {
                    var fi = new FileInfo(path);
                    string name = fi.Name;
                    if (root == Inbox) name = System.Text.RegularExpressions.Regex.Replace(name, @"^\d+_", "");
                    a.Add(new JsonObject
                    {
                        ["id"] = path, ["name"] = name, ["mime"] = Mime(path).Split(';')[0], ["size"] = fi.Length,
                        ["mtime"] = new DateTimeOffset(fi.LastWriteTimeUtc).ToUnixTimeMilliseconds(), ["url"] = Origin + "/f/" + Encode(path),
                    });
                }
                catch { }
            }
        }
        return a;
    }

    void PickFolder()
    {
        using var d = new FolderBrowserDialog
        {
            Description = "Папка, куда MAX сохраняет файлы (обычно «Загрузки» или «Загрузки\\MAX»)",
            UseDescriptionForTitle = true,
            InitialDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads"),
        };
        if (d.ShowDialog(this) != DialogResult.OK) return;
        if (!App.I.Settings.folders.Contains(d.SelectedPath)) App.I.Settings.folders.Add(d.SelectedPath);
        App.I.SaveSettings();
        Watch();
        Emit("folders", Folders());
    }

    void AddFiles()
    {
        using var d = new OpenFileDialog { Multiselect = true, Title = "Добавить задания", Filter = "Задания|*.jpg;*.jpeg;*.png;*.webp;*.pdf;*.docx;*.txt" };
        if (d.ShowDialog(this) != DialogResult.OK) return;
        Directory.CreateDirectory(Inbox);
        string first = null;
        foreach (var f in d.FileNames)
        {
            try
            {
                string dest = Path.Combine(Inbox, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + "_" + Path.GetFileName(f));
                File.Copy(f, dest, true);
                first ??= dest;
            }
            catch (Exception e) { Program.Log("add: " + e.Message); }
        }
        Emit("files-changed", null);
        if (first != null) Emit("show-file", first);
    }

    /// <summary>New files in the folders refresh the list by themselves.</summary>
    void Watch()
    {
        foreach (var w in watchers) w.Dispose();
        watchers.Clear();
        foreach (var f in App.I.Settings.folders)
        {
            if (!Directory.Exists(f)) continue;
            try
            {
                var w = new FileSystemWatcher(f) { IncludeSubdirectories = true, EnableRaisingEvents = true, NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite };
                FileSystemEventHandler h = (s, e) =>
                {
                    if (!Exts.Contains(Path.GetExtension(e.FullPath).ToLowerInvariant())) return;
                    BeginInvoke(() => { debounce.Stop(); debounce.Start(); });
                };
                w.Created += h; w.Renamed += (s, e) => h(s, e); w.Deleted += h;
                watchers.Add(w);
            }
            catch (Exception e) { Program.Log("watch: " + e.Message); }
        }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!real && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            if (!hintShown) { hintShown = true; App.I.Notify("«КНН домашка» работает у часов", "Напомню о сроках сдачи. Открыть снова: значок у часов."); }
            return;
        }
        foreach (var w in watchers) w.Dispose();
        base.OnFormClosing(e);
    }

    public void CloseForReal() { real = true; Close(); }
}
