using System.Diagnostics;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace KnnPary;

/// <summary>The schedule window: the same page as the web version, connected to the tray app.</summary>
class MainForm : Form
{
    readonly WebView2 web = new() { Dock = DockStyle.Fill };
    bool real, ready, hintShown;
    string pendingPdf;

    public MainForm()
    {
        Text = "КНН пары";
        Icon = App.I.AppIcon;
        AutoScaleMode = AutoScaleMode.None;
        float k = DeviceDpi / 96f;
        var wa = Screen.PrimaryScreen.WorkingArea;
        Size = new Size(Math.Min((int)(470 * k), wa.Width), Math.Min((int)(860 * k), wa.Height - 20));
        MinimumSize = new Size((int)(360 * k), (int)(480 * k));
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(0xF2, 0xF4, 0xF9);
        Controls.Add(web);
        Load += async (s, e) => await Init();
    }

    async Task Init()
    {
        try
        {
            await web.EnsureCoreWebView2Async(await App.Env());
        }
        catch (Exception e)
        {
            Program.Log("webview: " + e);
            if (MessageBox.Show("Для работы нужен компонент Microsoft Edge WebView2 (обычно он уже есть в Windows 10 и 11).\n\nСкачать его сейчас?",
                    "КНН пары", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
                App.Open("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
            return;
        }

        var c = web.CoreWebView2;
        string dir = Path.Combine(Program.Home, "web");
        Directory.CreateDirectory(dir);
        using (var s = typeof(MainForm).Assembly.GetManifestResourceStream("index.html"))
        using (var f = File.Create(Path.Combine(dir, "index.html")))
            s.CopyTo(f);

        c.SetVirtualHostNameToFolderMapping("knn.local", dir, CoreWebView2HostResourceAccessKind.Allow);
        c.Settings.IsStatusBarEnabled = false;
        c.Settings.AreDefaultContextMenusEnabled = false;
        c.WebMessageReceived += (s, e) => App.I.OnPageMessage(e.WebMessageAsJson);
        c.NewWindowRequested += (s, e) => { e.Handled = true; App.Open(e.Uri); };
        c.NavigationStarting += (s, e) =>
        {
            if (!e.Uri.StartsWith("https://knn.local/", StringComparison.OrdinalIgnoreCase)) { e.Cancel = true; App.Open(e.Uri); }
        };
        c.NavigationCompleted += (s, e) =>
        {
            ready = true;
            if (pendingPdf != null) { var p = pendingPdf; pendingPdf = null; SendPdf(p); }
        };
        c.Navigate("https://knn.local/index.html");
    }

    public void OpenPdf(string path)
    {
        if (ready) SendPdf(path); else pendingPdf = path;
    }

    async void SendPdf(string path)
    {
        try
        {
            string b64 = Convert.ToBase64String(await File.ReadAllBytesAsync(path));
            await web.CoreWebView2.ExecuteScriptAsync("window.knnOpenPdfBase64('" + b64 + "')");
        }
        catch (Exception e) { Program.Log("pdf: " + e.Message); }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!real && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            if (!hintShown)
            {
                hintShown = true;
                App.I.Notify("«КНН пары» работает у часов", "Окно скрыто, на пары зайду сам. Открыть снова: значок у часов.", null);
            }
            return;
        }
        base.OnFormClosing(e);
    }

    public void CloseForReal()
    {
        real = true;
        Close();
    }
}
