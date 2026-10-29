using System.Diagnostics;
using System.Globalization;
using System.Security;
using System.Text;

namespace KnnPary;

/// <summary>Windows Task Scheduler tasks that wake the computer from sleep a few minutes before each pair.</summary>
static class Wake
{
    public const int LeadMinutes = 3;
    static readonly object Gate = new();
    static string ListPath => Path.Combine(Program.Home, "wake-tasks.txt");

    public static void Replace(List<long> startsMs)
    {
        lock (Gate)
        {
            try
            {
                if (File.Exists(ListPath))
                    foreach (var n in File.ReadAllLines(ListPath))
                        if (!string.IsNullOrWhiteSpace(n)) Schtasks($"/Delete /TN \"{n}\" /F");

                var made = new List<string>();
                string user = Environment.UserDomainName + "\\" + Environment.UserName;
                string file = Path.Combine(Program.Home, "task.xml");
                foreach (var s in startsMs)
                {
                    var at = DateTimeOffset.FromUnixTimeMilliseconds(s).ToLocalTime().DateTime.AddMinutes(-LeadMinutes);
                    string name = "KNN pary " + at.ToString("yyyy-MM-dd HH-mm", CultureInfo.InvariantCulture);
                    File.WriteAllText(file, Xml(at, user), Encoding.Unicode);
                    if (Schtasks($"/Create /TN \"{name}\" /XML \"{file}\" /F") == 0) made.Add(name);
                }
                File.WriteAllLines(ListPath, made);
                Program.Log($"wake tasks: {made.Count} of {startsMs.Count}");
            }
            catch (Exception e) { Program.Log("wake: " + e); }
        }
    }

    static int Schtasks(string args)
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("schtasks.exe", args)
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            });
            var o = p.StandardOutput.ReadToEndAsync();
            var e = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(20000)) { p.Kill(); return -1; }
            if (p.ExitCode != 0) Program.Log("schtasks " + args + " -> " + p.ExitCode + " " + e.Result.Trim());
            return p.ExitCode;
        }
        catch (Exception ex) { Program.Log("schtasks: " + ex.Message); return -1; }
    }

    static string Xml(DateTime at, string user)
    {
        static string F(DateTime d) => d.ToString("yyyy-MM-dd'T'HH:mm:ss", CultureInfo.InvariantCulture);
        static string E(string s) => SecurityElement.Escape(s);
        return $"""
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>КНН пары: разбудить компьютер и зайти на пару</Description>
  </RegistrationInfo>
  <Triggers>
    <TimeTrigger>
      <StartBoundary>{F(at)}</StartBoundary>
      <EndBoundary>{F(at.AddHours(2))}</EndBoundary>
      <Enabled>true</Enabled>
    </TimeTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>{E(user)}</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>true</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <DeleteExpiredTaskAfter>PT1H</DeleteExpiredTaskAfter>
    <Priority>7</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>{E(Program.ExePath)}</Command>
      <Arguments>--wake</Arguments>
    </Exec>
  </Actions>
</Task>
""";
    }
}
