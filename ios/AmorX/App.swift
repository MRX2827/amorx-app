import SwiftUI
import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    func applicationDidBecomeActive(_ application: UIApplication) { Downloads.shared.setForeground(true) }
    func applicationDidEnterBackground(_ application: UIApplication) {
        let token = application.beginBackgroundTask(withName: "AmorX download handoff", expirationHandler: nil)
        Downloads.shared.setForeground(false)
        DispatchQueue.main.async { if token != .invalid { application.endBackgroundTask(token) } }
    }
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        Downloads.shared.backgroundCompletion = completionHandler
        Downloads.shared.reconnect()
    }
}
@main @MainActor struct AmorXApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var cloud = Cloud()
    @StateObject private var player = Playback()
    @StateObject private var library = Library()
    var body: some Scene {
        WindowGroup {
            AdaptiveRoot().environmentObject(cloud).environmentObject(player).environmentObject(library)
                .font(.custom("Roboto-Regular", size: 16, relativeTo: .body))
                .tint(.amorx)
                .preferredColorScheme(library.theme == "dark" ? .dark : library.theme == "light" ? .light : nil)
                .onAppear { Downloads.shared.setForeground(UIApplication.shared.applicationState != .background); LayoutPreview.markReady(); LayoutPreview.prepare(cloud: cloud, player: player, library: library); Downloads.shared.reconnect(); player.didPlay = { library.remember($0) }; library.didLike = { track, liked in cloud.run { try await cloud.setFavorite(track, liked: liked) } } }
        }
    }
}
