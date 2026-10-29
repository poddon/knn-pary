using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace KnnPary;

/// <summary>A pair: opens the VKS room, fills in the student's name, joins and picks "listen only".</summary>
class JoinForm : Form
{
    readonly WebView2 web = new() { Dock = DockStyle.Fill };
    readonly Label status = new()
    {
        Dock = DockStyle.Fill,
        ForeColor = Color.White,
        TextAlign = ContentAlignment.MiddleLeft,
        Font = new Font("Segoe UI", 10f),
        AutoEllipsis = true,
        Padding = new Padding(12, 0, 0, 0),
    };
    readonly System.Windows.Forms.Timer closeTimer = new() { Interval = 20_000 };
    readonly System.Windows.Forms.Timer retry = new() { Interval = 15_000 };
    string url, fio = "", title = "Пара";
    long endMs;
    bool inited;

    protected override bool ShowWithoutActivation => true;

    public JoinForm()
    {
        Text = "Пара — КНН пары";
        Icon = App.I.AppIcon;
        AutoScaleMode = AutoScaleMode.None;
        float k = DeviceDpi / 96f;
        var wa = Screen.PrimaryScreen.WorkingArea;
        Size = new Size(Math.Min((int)(1100 * k), wa.Width), Math.Min((int)(760 * k), wa.Height));
        StartPosition = FormStartPosition.CenterScreen;

        var bar = new Panel { Dock = DockStyle.Top, Height = (int)(44 * k), BackColor = Color.FromArgb(0x1E, 0x4F, 0xD8) };
        var leave = Btn("Выйти с пары", k);
        leave.Click += (s, e) => Close();
        var browser = Btn("В браузере", k);
        browser.Click += (s, e) => App.Open(url);
        bar.Controls.Add(status);
        bar.Controls.Add(browser);
        bar.Controls.Add(leave);
        Controls.Add(web);
        Controls.Add(bar);

        closeTimer.Tick += (s, e) =>
        {
            if (endMs > 0 && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() > endMs + 2 * 60_000L) Close();
        };
        closeTimer.Start();
        retry.Tick += (s, e) =>
        {
            retry.Stop();
            if (url != null && web.CoreWebView2 != null) web.CoreWebView2.Navigate(url);
        };
    }

    static Button Btn(string text, float k)
    {
        var b = new Button
        {
            Text = text,
            Dock = DockStyle.Right,
            Width = (int)(130 * k),
            FlatStyle = FlatStyle.Flat,
            ForeColor = Color.White,
            BackColor = Color.FromArgb(0x35, 0x62, 0xE0),
            Font = new Font("Segoe UI", 9.5f),
        };
        b.FlatAppearance.BorderSize = 0;
        return b;
    }

    public async void Go(string u, string t, string name, long end)
    {
        title = string.IsNullOrEmpty(t) ? "Пара" : t;
        fio = name ?? "";
        endMs = end;
        Text = title + " — КНН пары";
        status.Text = title + (end > 0
            ? " · выйду сам в " + DateTimeOffset.FromUnixTimeMilliseconds(end + 2 * 60_000L).ToLocalTime().ToString("HH:mm")
            : "");
        bool same = u == url;
        url = u;

        if (!inited)
        {
            inited = true;
            try { await web.EnsureCoreWebView2Async(await App.Env()); }
            catch (Exception e)
            {
                Program.Log("join webview: " + e.Message);
                App.Open(url);
                Close();
                return;
            }
            var c = web.CoreWebView2;
            c.Settings.IsStatusBarEnabled = false;
            c.PermissionRequested += (s, e) =>
            {
                e.State = e.PermissionKind == CoreWebView2PermissionKind.Notifications
                    ? CoreWebView2PermissionState.Deny
                    : CoreWebView2PermissionState.Allow;
            };
            c.NewWindowRequested += (s, e) => { e.Handled = true; App.Open(e.Uri); };
            c.NavigationCompleted += async (s, e) =>
            {
                if (!e.IsSuccess)
                {
                    if (e.WebErrorStatus == CoreWebView2WebErrorStatus.OperationCanceled) return;
                    status.Text = "Нет связи с сайтом пар, пробую снова…";
                    Program.Log("join: navigation failed " + e.WebErrorStatus);
                    retry.Start();
                    return;
                }
                status.Text = title + (endMs > 0
                    ? " · выйду сам в " + DateTimeOffset.FromUnixTimeMilliseconds(endMs + 2 * 60_000L).ToLocalTime().ToString("HH:mm")
                    : "");
                try { await c.ExecuteScriptAsync(Scripts.Join(fio)); }
                catch (Exception ex) { Program.Log("join script: " + ex.Message); }
            };
            c.Navigate(url);
            return;
        }
        if (!same && web.CoreWebView2 != null) web.CoreWebView2.Navigate(url);
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        closeTimer.Stop();
        retry.Stop();
        try { web.Dispose(); } catch { }
        base.OnFormClosed(e);
    }
}

static class Scripts
{
    /// <summary>Fills the name on the room's join page, presses join, then picks "listen only" in the conference.</summary>
    public static string Join(string name) => Autofill.Replace("__NAME__", JsonSerializer.Serialize(name ?? "")) + ListenOnly;

    const string Autofill = """
(function(n){if(!n||window.__knn||location.href.indexOf('html5client')>=0)return;window.__knn=1;
function setv(e,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);
e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
function vis(e){return e.offsetParent!==null;}
var k=0,t=setInterval(function(){if(++k>80){clearInterval(t);return;}
var all=[].slice.call(document.querySelectorAll('input')).filter(vis);
var inp=document.querySelector('input#name,input[name=name],input[autocomplete=name]');
if(!inp||!vis(inp)){inp=all.filter(function(e){var s=(e.id+' '+e.name+' '+(e.placeholder||'')).toLowerCase();
return (e.type==='text'||!e.type)&&!/code|код|pass|парол/.test(s);})[0];}
if(!inp)return;clearInterval(t);
if(!inp.value)setv(inp,n);
var other=all.some(function(e){return e!==inp&&(e.type==='text'||e.type==='password'||!e.type)&&!e.value;});
var cons=all.some(function(e){return e.type==='checkbox'&&!e.checked;});
if(other||cons)return;
var b=(inp.form&&inp.form.querySelector('button[type=submit],input[type=submit]'))||
[].slice.call(document.querySelectorAll('button')).filter(function(b){return vis(b)&&/присоедин|войти|join/i.test(b.textContent);})[0];
if(b)setTimeout(function(){b.click();},400);
},400);})(__NAME__);
""";

    const string ListenOnly = """
(function(){if(window.__knnListen)return;window.__knnListen=1;
function vis(e){return e&&e.offsetParent!==null;}
var k=0,t=setInterval(function(){if(++k>400){clearInterval(t);return;}
var b=document.querySelector('[data-test=listenOnlyBtn],[data-test=listenOnlyButton]');
if(!vis(b)){b=[].slice.call(document.querySelectorAll('button,[role=button]')).filter(function(e){
var s=((e.getAttribute('aria-label')||'')+' '+e.textContent).toLowerCase();
return vis(e)&&/только слушать|listen only|слушать/.test(s);})[0];}
if(b){clearInterval(t);b.click();}
},500);})();
""";
}
