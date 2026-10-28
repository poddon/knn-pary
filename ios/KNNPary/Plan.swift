import Foundation
import UserNotifications

/// Schedules a notification at the start of each pair of the chosen group.
enum Plan {
  static let lead: TimeInterval = 60 // seconds before the pair starts

  static var fio: String {
    get { UserDefaults.standard.string(forKey: "fio") ?? "" }
    set { UserDefaults.standard.set(newValue, forKey: "fio") }
  }

  static var auto: Bool {
    get { UserDefaults.standard.object(forKey: "auto") as? Bool ?? true }
    set { UserDefaults.standard.set(newValue, forKey: "auto") }
  }

  static func apply(_ msg: [String: Any]) {
    if let f = msg["fio"] as? String { fio = f }
    if let a = msg["auto"] as? Bool { auto = a }
    let items = msg["items"] as? [[String: Any]] ?? []
    let center = UNUserNotificationCenter.current()
    center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
      center.removeAllPendingNotificationRequests()
      guard granted else { return }
      let now = Date().timeIntervalSince1970 * 1000
      var count = 0
      for item in items.sorted(by: { ($0["start"] as? Double ?? 0) < ($1["start"] as? Double ?? 0) }) {
        guard count < 60, let start = item["start"] as? Double, let end = item["end"] as? Double else { continue }
        if end < now { continue }
        var fire = Date(timeIntervalSince1970: start / 1000 - lead)
        if fire.timeIntervalSinceNow < 2 { fire = Date().addingTimeInterval(3) }
        let content = UNMutableNotificationContent()
        content.title = item["title"] as? String ?? "Пара"
        content.body = item["body"] as? String ?? ""
        content.sound = .default
        var info: [String: Any] = ["end": end, "joinTitle": item["joinTitle"] as? String ?? "Пара"]
        if let url = item["url"] as? String { info["url"] = url }
        content.userInfo = info
        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fire)
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        center.add(UNNotificationRequest(identifier: "pair-\(count)", content: content, trigger: trigger))
        count += 1
      }
    }
  }
}
