import Foundation
import Combine

extension FileManager {
    static let musicRoot: URL = {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Music", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()
}

@MainActor final class Library: ObservableObject {
    @Published var playlist: [Track] = []
    @Published var favorites: [Track] = []
    @Published var recent: [Track] = []
    @Published var code: String?
    @Published var theme = UserDefaults.standard.string(forKey: "theme") ?? "system"
    private var user = "local"
    var didLike: ((Track, Bool) -> Void)?
    struct Saved: Codable { var playlist: [Track] = []; var favorites: [Track] = []; var recent: [Track] = []; var code: String? }
    private var file: URL { FileManager.musicRoot.appendingPathComponent("library-\(user).json") }
    func changeUser(_ uid: String?) {
        user = uid ?? "local"
        let value = (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode(Saved.self, from: $0) } ?? Saved()
        playlist = value.playlist; favorites = value.favorites; recent = value.recent; code = value.code
    }
    func save() {
        do { try JSONEncoder().encode(Saved(playlist: playlist, favorites: favorites, recent: recent, code: code)).write(to: file, options: .atomic) }
        catch { print("AmorX library write failed") }
    }
    func add(_ songs: [Track]) { playlist = Array(Track.unique(playlist + songs).prefix(1000)); save() }
    func like(_ track: Track) { if favorites.contains(where: { $0.id == track.id }) { favorites.removeAll { $0.id == track.id } } else { favorites.insert(track, at: 0) }; save(); didLike?(track, favorites.contains { $0.id == track.id }) }
    func remember(_ track: Track) { recent = Array(([track] + recent.filter { $0.id != track.id }).prefix(30)); save() }
}

final class Downloads: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = Downloads()
    @Published private(set) var tracks: [Track] = []
    @Published private(set) var pending: [String: Double] = [:]
    @Published var issue: String?
    var backgroundCompletion: (() -> Void)?
    private(set) var lastFailure: String?
    private(set) var usedForegroundFallback = false
    private struct Transfer: Codable {
        let track: Track
        let url: URL
        var offset: Int64 = 0
        var total: Int64? = nil
        var attempts = 0
        var foreground: Bool? = nil
    }
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "com.amorx.ios.downloads")
        config.isDiscretionary = false; config.sessionSendsLaunchEvents = true; config.waitsForConnectivity = true
        config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 300
        config.httpMaximumConnectionsPerHost = 3
        return URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }()
    // Recover downloads while the app is running if the system transfer daemon
    // cannot start a background task. Keep the same range/file validation path.
    private lazy var foregroundSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 300
        config.httpMaximumConnectionsPerHost = 3
        return URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }()
    private var localFiles: [String: URL] = [:]
    private var index: URL { FileManager.musicRoot.appendingPathComponent("downloads.json") }
    override init() {
        super.init()
        let files = (try? FileManager.default.contentsOfDirectory(at: FileManager.musicRoot, includingPropertiesForKeys: nil)) ?? []
        for file in files where ["m4a", "mp3", "aac", "wav", "aiff", "caf", "flac"].contains(file.pathExtension.lowercased()) {
            let id = file.deletingPathExtension().lastPathComponent
            if safe(id) { localFiles[id] = file }
        }
        tracks = ((try? Data(contentsOf: index)).flatMap { try? JSONDecoder().decode([Track].self, from: $0) } ?? []).filter { local($0) != nil }
    }
    func reconnect() {
        session.getAllTasks { tasks in DispatchQueue.main.async {
            for task in tasks { if let work = self.transfer(task) { self.pending[work.track.id] = work.total.map { Double(work.offset) / Double(max(1, $0)) } ?? 0 } }
        } }
    }
    private func safe(_ id: String) -> Bool { id.range(of: "^[A-Za-z0-9_-]{1,80}$", options: .regularExpression) != nil }
    private func part(_ track: Track) -> URL { FileManager.musicRoot.appendingPathComponent(track.id + ".part") }
    func local(_ track: Track) -> URL? { localFiles[track.id] }
    @MainActor func download(_ track: Track) async {
        guard local(track) == nil, pending[track.id] == nil, safe(track.id) else { return }
        pending[track.id] = 0
        do {
            let url = try await MusicSearch.stream(track)
            try? FileManager.default.removeItem(at: part(track))
            try enqueue(Transfer(track: track, url: url))
        } catch { fail(track, error) }
    }
    #if targetEnvironment(simulator)
    @MainActor func verifyFixture(_ track: Track, url: URL) async -> URL? {
        guard LayoutPreview.isActive, url.host == "127.0.0.1" else { return nil }
        pending[track.id] = 0; lastFailure = nil; usedForegroundFallback = false
        do { try enqueue(Transfer(track: track, url: url)) } catch { fail(track, error) }
        let deadline = Date().addingTimeInterval(80)
        while pending[track.id] != nil && Date() < deadline { try? await Task.sleep(nanoseconds: 100_000_000) }
        return local(track)
    }
    #endif
    private func enqueue(_ work: Transfer) throws {
        var request = URLRequest(url: work.url, timeoutInterval: 30)
        request.setValue(AudioByteRange.request(offset: work.offset, total: work.total), forHTTPHeaderField: "Range")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let transport = work.foreground == true ? foregroundSession : session
        let task = transport.downloadTask(with: request)
        task.priority = URLSessionTask.highPriority
        task.taskDescription = String(decoding: try JSONEncoder().encode(work), as: UTF8.self)
        task.resume()
    }
    @MainActor func downloadAll(_ list: [Track]) async {
        for track in Track.unique(list) {
            while pending.count >= 2 { if Task.isCancelled { return }; try? await Task.sleep(nanoseconds: 300_000_000) }
            if Task.isCancelled { return }
            await download(track)
        }
    }
    func remove(_ track: Track) {
        if let url = local(track) { try? FileManager.default.removeItem(at: url) }
        localFiles.removeValue(forKey: track.id)
        tracks.removeAll { $0.id == track.id }; save()
    }
    func importAudio(_ url: URL) throws -> Track {
        let allowed = url.startAccessingSecurityScopedResource(); defer { if allowed { url.stopAccessingSecurityScopedResource() } }
        let id = "local_" + UUID().uuidString.lowercased()
        let track = Track(id: id, title: url.deletingPathExtension().lastPathComponent, artist: "Локальный файл")
        let suffix = url.pathExtension.lowercased()
        guard ["m4a", "mp3", "aac", "wav", "aiff", "caf", "flac"].contains(suffix) else { throw AppIssue("Этот формат аудио не поддерживается") }
        let destination = FileManager.musicRoot.appendingPathComponent(id + "." + suffix)
        try FileManager.default.copyItem(at: url, to: destination)
        localFiles[track.id] = destination
        tracks.insert(track, at: 0); save(); return track
    }
    private func save() { do { try JSONEncoder().encode(tracks).write(to: index, options: .atomic) } catch { issue = "Не удалось сохранить список скачанного" } }
    private func transfer(_ task: URLSessionTask) -> Transfer? {
        guard let data = task.taskDescription?.data(using: .utf8) else { return nil }
        if let value = try? JSONDecoder().decode(Transfer.self, from: data) { return value }
        // Adopt downloads already running when upgrading from 1327.
        if let track = try? JSONDecoder().decode(Track.self, from: data), let url = task.originalRequest?.url { return Transfer(track: track, url: url) }
        return nil
    }
    private func fail(_ track: Track, _ error: Error) {
        lastFailure = String(describing: error)
        pending.removeValue(forKey: track.id); try? FileManager.default.removeItem(at: part(track))
        issue = "«\(track.title)»: \(error.localizedDescription)"
    }
    private func finish(_ work: Transfer, file: URL) throws {
        let target = FileManager.musicRoot.appendingPathComponent(work.track.id + ".m4a")
        if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
        try FileManager.default.moveItem(at: file, to: target)
        if file != part(work.track) { try? FileManager.default.removeItem(at: part(work.track)) }
        localFiles[work.track.id] = target
        tracks = Track.unique(tracks + [work.track]); save(); pending.removeValue(forKey: work.track.id)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let work = transfer(downloadTask) else { return }
        let response = downloadTask.response as? HTTPURLResponse
        let total = work.total ?? response?.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last.flatMap { Int64($0) }
        if let total = total, total > 0 { pending[work.track.id] = min(1, Double(work.offset + totalBytesWritten) / Double(total)) }
        else if response?.statusCode == 200, totalBytesExpectedToWrite > 0 { pending[work.track.id] = Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard var work = transfer(downloadTask), safe(work.track.id) else { return }
        guard let response = downloadTask.response as? HTTPURLResponse else { fail(work.track, AppIssue("Нет ответа сервера")); return }
        if [403, 410].contains(response.statusCode), work.attempts == 0 {
            work.attempts += 1; work.offset = 0; work.total = nil
            let retry = work
            Task { @MainActor in
                await AudioLinkCache.shared.invalidate(retry.track.id)
                do {
                    let url = try await MusicSearch.stream(retry.track)
                    try? FileManager.default.removeItem(at: self.part(retry.track))
                    var fresh = Transfer(track: retry.track, url: url); fresh.attempts = retry.attempts; fresh.foreground = retry.foreground
                    try self.enqueue(fresh)
                } catch { self.fail(retry.track, error) }
            }
            return
        }
        do {
            let length = Int64(try location.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
            guard length > 0 else { throw AppIssue("Сервер вернул пустой аудиофайл") }
            if response.statusCode == 200 { try finish(work, file: location); return }
            guard response.statusCode == 206,
                  let range = AudioByteRange(response.value(forHTTPHeaderField: "Content-Range"), offset: work.offset, received: length) else { throw AppIssue("Не удалось скачать аудио (\(response.statusCode))") }
            let target = part(work.track)
            if work.offset == 0 { try? FileManager.default.removeItem(at: target); try FileManager.default.moveItem(at: location, to: target) }
            else {
                let actual = Int64(try target.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
                guard actual == work.offset, work.total == range.total else { throw AppIssue("Загрузка прервалась. Повторите скачивание") }
                let input = try FileHandle(forReadingFrom: location), output = try FileHandle(forWritingTo: target)
                defer { try? input.close(); try? output.close() }
                try output.seekToEnd()
                while let bytes = try input.read(upToCount: 64 * 1024), !bytes.isEmpty { try output.write(contentsOf: bytes) }
                try output.synchronize()
            }
            if range.complete { try finish(work, file: target) }
            else { work.offset = range.end + 1; work.total = range.total; work.attempts = 0; try enqueue(work) }
        } catch { fail(work.track, error) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error = error, var work = transfer(task) else { return }
        let failure = error as NSError
        if session.configuration.identifier != nil, failure.domain == NSURLErrorDomain, failure.code == NSURLErrorUnknown {
            // Changing transport is separate from the one network retry/link refresh.
            work.foreground = true; usedForegroundFallback = true
            do { try enqueue(work) } catch { fail(work.track, error) }
        } else if work.attempts < 1, failure.code != NSURLErrorCancelled {
            work.attempts += 1
            do { try enqueue(work) } catch { fail(work.track, error) }
        } else { fail(work.track, error) }
    }
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let done = backgroundCompletion; backgroundCompletion = nil; done?()
    }
}
