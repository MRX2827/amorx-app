import SwiftUI
import CryptoKit

/// Offline, simulator-only layout fixtures. The device IPA cannot activate these fixtures.
@MainActor enum LayoutPreview {
    static var screen: String {
        #if targetEnvironment(simulator)
        return argument("--layout-screen") ?? ""
        #else
        return ""
        #endif
    }
    static var isActive: Bool { !screen.isEmpty }
    static var page: Int { screen == "library" ? 1 : screen == "profile" ? 4 : 0 }
    static var size: CGSize? {
        #if targetEnvironment(simulator)
        if let w = argument("--layout-width").flatMap(Double.init), let h = argument("--layout-height").flatMap(Double.init) { return CGSize(width: w, height: h) }
        #endif
        return nil
    }
    private static func argument(_ key: String) -> String? {
        let values = ProcessInfo.processInfo.arguments
        guard let index = values.firstIndex(of: key), index + 1 < values.count else { return nil }; return values[index + 1]
    }
    static var plan: MusicBackupPlan { try! MusicBackupPlan(["amorx_online": ["downloads": songs.map(\.cloudJSON), "amorx_playlist": ["tracks": songs.map(\.cloudJSON)]]]) }
    static var songs: [Track] {
        #if targetEnvironment(simulator)
        return (0..<12).map { Track(id: String(format: "%011d", $0), title: "Трек с длинным названием для проверки экрана \($0 + 1)", artist: "Исполнитель", duration: 210) }
        #else
        return []
        #endif
    }
    static func prepare(cloud: Cloud, player: Playback, library: Library) {
        #if targetEnvironment(simulator)
        guard isActive else { return }
        if let raw = argument("--download-url"), let url = URL(string: raw) {
            Task { @MainActor in
                let track = Track(id: "fixture_" + UUID().uuidString, title: "Download fixture", artist: "CI")
                let file = await Downloads.shared.verifyFixture(track, url: url)
                let bytes = file.flatMap { try? Data(contentsOf: $0) }
                let result: [String: Any] = ["downloaded": bytes != nil, "indexed": Downloads.shared.tracks.contains(where: { $0.id == track.id }), "size": bytes?.count ?? 0,
                    "failure": Downloads.shared.lastFailure ?? "", "foreground_fallback": Downloads.shared.usedForegroundFallback, "sha256": bytes.map { SHA256.hash(data: $0).map { String(format: "%02x", $0) }.joined() } ?? ""]
                let output = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("download-verification.json")
                if let data = try? JSONSerialization.data(withJSONObject: result) { try? data.write(to: output, options: .atomic) }
            }
        }
        library.playlist = songs; cloud.profile = Person(["id": "preview", "username": "AmorX", "bio": "Музыка и друзья"])
        if let first = songs.first { player.prepareLayoutPreview(first, queue: songs) }
        #endif
    }
    static func markReady() {
        #if targetEnvironment(simulator)
        let value: [String: Any] = ["pid": Int(ProcessInfo.processInfo.processIdentifier), "screen": screen, "time": Date().timeIntervalSince1970]
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        if let bytes = try? JSONSerialization.data(withJSONObject: value) { try? bytes.write(to: folder.appendingPathComponent("startup-ready.json"), options: .atomic) }
        #endif
    }
    static func record(_ frames: [String: CGRect], size: CGSize) {
        #if targetEnvironment(simulator)
        guard isActive else { return }
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let observed: [String: Any] = ["screen": screen, "frames": frames.mapValues { [Double($0.minX), Double($0.minY), Double($0.width), Double($0.height)] }]
        if let bytes = try? JSONSerialization.data(withJSONObject: observed) { try? bytes.write(to: folder.appendingPathComponent("layout-observed.json"), options: .atomic) }
        guard let viewport = frames["__viewport"] else { return }
        // NavigationView hosts some content in another hosting controller. Measure every
        // probe in window coordinates and subtract the actual viewport's window origin.
        let relative = frames.filter { $0.key != "__viewport" }.mapValues {
            $0.offsetBy(dx: -viewport.minX, dy: -viewport.minY)
        }
        var required: [String]
        switch screen {
        case "login": required = ["login.submit"]
        case "library": required = ["library.actions", "mini.player"]
        case "profile": required = ["mini.player"]
        case "player": required = ["player.controls"]
        case "restore": required = ["restore.actions"]
        default: required = []
        }
        if ["library", "profile"].contains(screen), !(size.width > size.height && size.height < 400) { required.append("bottom.navigation") }
        guard !required.isEmpty, required.allSatisfy({ relative[$0] != nil }) else { return }
        let sticky = ["bottom.navigation", "mini.player", "player.controls", "restore.actions"]
        let invalid = relative.filter { name, rect in
            rect.minX < -1 || rect.maxX > size.width + 1 || (sticky.contains(name) && (rect.minY < -1 || rect.maxY > size.height + 1))
        }
        let data: [String: Any] = ["screen": screen, "width": Double(size.width), "height": Double(size.height),
            "violations": Array(invalid.keys), "frames": relative.mapValues { ["x": Double($0.minX), "y": Double($0.minY), "width": Double($0.width), "height": Double($0.height)] }]
        if let bytes = try? JSONSerialization.data(withJSONObject: data, options: .sortedKeys) {
            let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? bytes.write(to: folder.appendingPathComponent("layout-verification.json"), options: .atomic)
        }
        #endif
    }
}
struct LayoutFrames: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) { value.merge(nextValue(), uniquingKeysWith: { _, b in b }) }
}
extension View {
    @ViewBuilder func layoutProbe(_ name: String) -> some View {
        #if targetEnvironment(simulator)
        if LayoutPreview.isActive { self.background(GeometryReader { geometry in Color.clear.preference(key: LayoutFrames.self, value: [name: geometry.frame(in: .global)]) }) }
        else { self }
        #else
        self
        #endif
    }
}
