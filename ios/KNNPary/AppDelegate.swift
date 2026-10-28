import UIKit
import AVFoundation
import UserNotifications

@main
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
  var window: UIWindow?
  let main = MainViewController()

  func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    // Keeps the pair's sound playing when the screen is locked.
    try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP])
    UNUserNotificationCenter.current().delegate = self
    window = UIWindow(frame: UIScreen.main.bounds)
    window?.rootViewController = main
    window?.makeKeyAndVisible()
    if let url = launchOptions?[.url] as? URL { main.openPdf(url) }
    return true
  }

  /// A PDF shared from MAX or Files ("Поделиться" → "КНН пары").
  func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
    main.openPdf(url)
    return true
  }

  /// The app is open when a pair starts: join right away.
  func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                              withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
    let info = notification.request.content.userInfo
    if Plan.auto, let url = info["url"] as? String {
      main.join(url: url, title: info["joinTitle"] as? String ?? "Пара", end: info["end"] as? Double ?? 0, auto: true)
    }
    completionHandler([.banner, .list, .sound])
  }

  /// The student tapped the notification: open the pair and join.
  func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                              withCompletionHandler completionHandler: @escaping () -> Void) {
    let info = response.notification.request.content.userInfo
    if let url = info["url"] as? String {
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
        self.main.join(url: url, title: info["joinTitle"] as? String ?? "Пара", end: info["end"] as? Double ?? 0, auto: true)
      }
    }
    completionHandler()
  }
}
