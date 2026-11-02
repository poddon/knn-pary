using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace KnnPary;

/// <summary>The schedule the admin publishes at poddon.github.io/knn-pary/admin.html, turned into pairs of the student's group.</summary>
static class SharedSchedule
{
    const string Source = "https://raw.githubusercontent.com/poddon/knn-pary/data/schedule.json";
    const string Repo = "poddon/knn-pary";
    static readonly HttpClient Http = CreateHttp();

    static HttpClient CreateHttp()
    {
        var h = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        h.DefaultRequestHeaders.UserAgent.ParseAdd("KNN-pary/1.2");
        return h;
    }

    /// <summary>The newest published file: by commit (no CDN delay), or by branch when GitHub limits requests.</summary>
    static async Task<string> Fetch()
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, "https://api.github.com/repos/" + Repo + "/commits/data");
            req.Headers.Accept.ParseAdd("application/vnd.github.sha");
            using var res = await Http.SendAsync(req);
            res.EnsureSuccessStatusCode();
            string sha = (await res.Content.ReadAsStringAsync()).Trim();
            if (!Regex.IsMatch(sha, "^[0-9a-f]{40}$")) throw new Exception("bad sha");
            string text = await Http.GetStringAsync("https://raw.githubusercontent.com/" + Repo + "/" + sha + "/schedule.json");
            lastRef = sha;
            return text;
        }
        catch
        {
            lastRef = null;
            return await Http.GetStringAsync(Source + "?t=" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        }
    }

    static string lastRef;
    static string ExtraPath => Path.Combine(Program.Home, "teachers-extra.json");

    /// <summary>Teacher links the admin published. Returns true when they changed.</summary>
    static async Task<bool> DownloadTeachers()
    {
        try
        {
            string url = "https://raw.githubusercontent.com/" + Repo + "/" + (lastRef ?? "data") + "/teachers.json" + (lastRef == null ? "?t=" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() : "");
            using var res = await Http.GetAsync(url);
            if (!res.IsSuccessStatusCode) return false;
            using var d = JsonDocument.Parse(await res.Content.ReadAsStringAsync());
            string list = d.RootElement.TryGetProperty("teachers", out var t) ? t.GetRawText() : "[]";
            string old = File.Exists(ExtraPath) ? await File.ReadAllTextAsync(ExtraPath) : "[]";
            if (old == list) return false;
            await File.WriteAllTextAsync(ExtraPath, list);
            teachers = null;
            Program.Log("teacher links updated");
            return true;
        }
        catch (Exception e) { Program.Log("teacher links: " + e.Message); return false; }
    }

    public static DateTime LastCheck = DateTime.MinValue;
    public static bool LastOk = true;
    static string CachePath => Path.Combine(Program.Home, "shared.json");
    static List<string[]> teachers;

    /// <summary>Downloads the published schedule. Returns true when it differs from the cached copy.</summary>
    public static async Task<bool> Download()
    {
        try
        {
            string text = await Fetch();
            using (JsonDocument.Parse(text)) { }
            LastCheck = DateTime.Now; LastOk = true;
            bool teachersChanged = await DownloadTeachers();
            string old = File.Exists(CachePath) ? await File.ReadAllTextAsync(CachePath) : "";
            if (old == text) return teachersChanged;
            await File.WriteAllTextAsync(CachePath, text);
            Program.Log("shared schedule updated");
            return true;
        }
        catch (Exception e)
        {
            LastCheck = DateTime.Now; LastOk = false;
            Program.Log("shared schedule: " + e.Message);
            return false;
        }
    }

    public static List<PlanItem> Items(string group)
    {
        var items = new List<PlanItem>();
        if (string.IsNullOrWhiteSpace(group) || !File.Exists(CachePath)) return items;
        try
        {
            using var d = JsonDocument.Parse(File.ReadAllText(CachePath));
            if (!d.RootElement.TryGetProperty("sheets", out var sheets)) return items;
            string g = Norm(group);
            foreach (var sh in sheets.EnumerateArray())
            {
                if (!DateTime.TryParseExact(Str(sh, "date"), new[] { "dd.MM.yyyy", "d.M.yyyy", "dd.MM.yy" }, CultureInfo.InvariantCulture, DateTimeStyles.None, out var day)) continue;
                if (!sh.TryGetProperty("columns", out var cols)) continue;
                foreach (var col in cols.EnumerateArray())
                {
                    if (!col.TryGetProperty("groups", out var gs) || !gs.EnumerateArray().Any(x => Norm(x.GetString() ?? "") == g)) continue;
                    foreach (var l in col.GetProperty("lessons").EnumerateArray()) items.Add(Item(day, l));
                }
            }
        }
        catch (Exception e) { Program.Log("shared items: " + e.Message); }
        return items.Where(i => i != null).ToList();
    }

    static PlanItem Item(DateTime day, JsonElement l)
    {
        string time = Str(l, "time"), subject = Str(l, "subject");
        var m = Regex.Match(time, @"(\d{1,2})[:.](\d{2})\D+(\d{1,2})[:.](\d{2})");
        if (!m.Success) return null;
        int num = l.TryGetProperty("num", out var n) && n.ValueKind == JsonValueKind.Number ? n.GetInt32() : 0;
        var start = day.Date.AddHours(int.Parse(m.Groups[1].Value)).AddMinutes(int.Parse(m.Groups[2].Value));
        var end = day.Date.AddHours(int.Parse(m.Groups[3].Value)).AddMinutes(int.Parse(m.Groups[4].Value));
        string url = null;
        var names = new List<string>();
        if (l.TryGetProperty("teachers", out var ts))
            foreach (var t in ts.EnumerateArray())
            {
                string w = t.GetString() ?? "";
                var c = Lookup(w);
                if (c.Count == 1) { names.Add(c[0][0]); url ??= c[0][1]; }
                else names.Add(w);
            }
        string who = string.Join(", ", names);
        return new PlanItem
        {
            start = new DateTimeOffset(start).ToUnixTimeMilliseconds(),
            end = new DateTimeOffset(end).ToUnixTimeMilliseconds(),
            title = num + " пара в " + m.Groups[1].Value.PadLeft(2, '0') + ":" + m.Groups[2].Value + (subject.Length > 0 ? " · " + subject : ""),
            body = who.Length > 0 ? who + (url != null ? " · нажми, чтобы войти" : "") : "Открой расписание",
            url = url,
            joinTitle = subject.Length > 0 ? subject : who,
        };
    }

    static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    static string Norm(string g)
    {
        const string lat = "AKBCEMHPTXO", cyr = "АКВСЕМНРТХО";
        var sb = new StringBuilder();
        foreach (char ch in g.ToUpperInvariant())
        {
            if (char.IsWhiteSpace(ch)) continue;
            int i = lat.IndexOf(ch);
            sb.Append(i >= 0 ? cyr[i] : ch);
        }
        return sb.ToString();
    }

    static string Key(string s) => s.ToLowerInvariant().Replace('ё', 'е');

    static string Full(string s) => Key(Regex.Replace(s.Trim(), @"\s+", " "));

    /// <summary>Same rule as the apps: same name replaces; a full name replaces a surname-only entry; a surname changes the only teacher with it.</summary>
    static void MergeExtra(List<string[]> list)
    {
        try
        {
            if (!File.Exists(ExtraPath)) return;
            using var d = JsonDocument.Parse(File.ReadAllText(ExtraPath));
            foreach (var e in d.RootElement.EnumerateArray())
            {
                if (e.ValueKind != JsonValueKind.Array || e.GetArrayLength() < 2) continue;
                string name = Regex.Replace((e[0].GetString() ?? "").Trim(), @"\s+", " "), url = (e[1].GetString() ?? "").Trim();
                if (name.Length == 0 || !url.StartsWith("https://")) continue;
                string sur = Key(name.Split(' ')[0]);
                bool single = !name.Contains(' ');
                int idx = list.FindIndex(t => Full(t[0]) == Full(name));
                if (idx < 0 && !single) idx = list.FindIndex(t => !t[0].Trim().Contains(' ') && Full(t[0]) == sur);
                if (idx < 0 && single)
                {
                    var same = list.Where(t => Key(t[0].Split(' ')[0]) == sur).ToList();
                    if (same.Count == 1) idx = list.IndexOf(same[0]);
                }
                if (idx >= 0) list[idx] = new[] { single ? list[idx][0] : name, url };
                else list.Add(new[] { name, url });
            }
        }
        catch (Exception ex) { Program.Log("merge teachers: " + ex.Message); }
    }

    static List<string[]> Lookup(string written)
    {
        if (teachers == null)
        {
            teachers = new List<string[]>();
            using var s = typeof(SharedSchedule).Assembly.GetManifestResourceStream("teachers.tsv");
            using var r = new StreamReader(s, Encoding.UTF8);
            string line;
            while ((line = r.ReadLine()) != null)
            {
                var p = line.Split('\t');
                if (p.Length == 2) teachers.Add(p);
            }
            MergeExtra(teachers);
        }
        var w = written.Trim().Split(' ', 2, StringSplitOptions.RemoveEmptyEntries);
        if (w.Length == 0) return new List<string[]>();
        var c = teachers.Where(t => Key(t[0].Split(' ')[0]) == Key(w[0])).ToList();
        if (w.Length > 1 && c.Count > 1)
        {
            string ini = new string(w[1].Where(char.IsLetter).ToArray()).ToUpperInvariant();
            var f = c.Where(t =>
            {
                var n = t[0].Split(' ');
                string ti = (n.Length > 1 ? n[1][..1] : "") + (n.Length > 2 ? n[2][..1] : "");
                return ini.Length > 0 && ti.ToUpperInvariant().StartsWith(ini);
            }).ToList();
            if (f.Count > 0) return f;
        }
        return c;
    }
}
