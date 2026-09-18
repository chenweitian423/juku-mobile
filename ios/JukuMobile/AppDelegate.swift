import UIKit

/// 应用入口。不使用 Scene 生命周期 —— 单一窗口场景下 AppDelegate 方式更少迁移包袱，
/// 也避免 Info.plist 里额外的 scene manifest 配置。
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)

        let navigation = UINavigationController(rootViewController: RootViewController())
        navigation.navigationBar.barStyle = .black
        navigation.navigationBar.tintColor = UIColor(red: 0.94, green: 0.35, blue: 0.16, alpha: 1)
        navigation.navigationBar.isTranslucent = false

        window.rootViewController = navigation
        window.backgroundColor = UIColor(red: 0.059, green: 0.067, blue: 0.082, alpha: 1)
        window.makeKeyAndVisible()
        self.window = window

        return true
    }
}
