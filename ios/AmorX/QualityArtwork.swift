import SwiftUI
import CryptoKit

/// Upgrade only known thumbnail hosts and keep the original as a fallback.

actor QualityArtworkStore {
    static let shared = QualityArtworkStore()
    private var pending: [String: Task<Data?, Never>] = [:]
    private var memory: [String: Data] = [:]
    private var order: [String] = []
    private func file(_ source: String) -> URL {
        let name = SHA256.hash(data: Data(source.utf8)).map { String(format: "%02x", $0) }.joined()
        return FileManager.musicRoot.appendingPathComponent("Artwork", isDirectory: true).appendingPathComponent(name + ".jpg")
    }
    func data(_ source: String, persist: Bool = false) async -> Data? {
        let destination = file(source)
        if let saved = try? Data(contentsOf: destination) { return saved }
        if let saved = memory[source] { if persist { save(saved, destination) }; return saved }
        let task: Task<Data?, Never>
        if let existing = pending[source] { task = existing }
        else {
            task = Task {
                for candidate in ArtworkQuality.candidates(source) {
                    guard let url = URL(string: candidate) else { continue }
                    var request = URLRequest(url: url); request.timeoutInterval = 6
                    guard let (bytes, response) = try? await URLSession.shared.data(for: request), (response as? HTTPURLResponse)?.statusCode == 200, bytes.count <= 12 * 1024 * 1024,
                          let image = UIImage(data: bytes) else { continue }
                    if candidate != source && image.size.width < 480 { continue }
                    return bytes
                }
                return nil
            }
            pending[source] = task
        }
        let result = await task.value; pending.removeValue(forKey: source)
        if let bytes = result {
            memory[source] = bytes; order.removeAll { $0 == source }; order.append(source)
            while order.count > 6 { memory.removeValue(forKey: order.removeFirst()) }
            if persist { save(bytes, destination) }
        }
        return result
    }
    private func save(_ data: Data, _ url: URL) {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }
}
struct QualityArtworkImage: View {
    let source: String
    @State private var image: UIImage?
    var body: some View {
        Group { if let image = image { Image(uiImage: image).resizable().scaledToFill() } else { Color.clear } }
            .task(id: source) {
                image = nil
                let bytes = await QualityArtworkStore.shared.data(source)
                guard !Task.isCancelled else { return }; image = bytes.flatMap(UIImage.init(data:))
            }
    }
}
