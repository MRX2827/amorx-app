import SwiftUI
import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
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
            RootScreen().environmentObject(cloud).environmentObject(player).environmentObject(library)
                .font(.custom("Roboto-Regular", size: 16, relativeTo: .body))
                .tint(.amorx)
                .preferredColorScheme(library.theme == "dark" ? .dark : library.theme == "light" ? .light : nil)
                .onAppear { Downloads.shared.reconnect(); player.didPlay = { library.remember($0) }; library.didLike = { track, liked in cloud.run { try await cloud.setFavorite(track, liked: liked) } } }
        }
    }
}
