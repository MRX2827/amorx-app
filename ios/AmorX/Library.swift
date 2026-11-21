import Foundation
import Combine
import UIKit

extension FileManager {
    static var musicRoot: URL {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Music", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
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
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "com.amorx.ios.downloads")
        config.isDiscretionary = false; config.sessionSendsLaunchEvents = true; config.waitsForConnectivity = true
        return URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }()
    private var index: URL { FileManager.musicRoot.appendingPathComponent("downloads.json") }
    override init() {
        super.init()
        tracks = ((try? Data(contentsOf: index)).flatMap { try? JSONDecoder().decode([Track].self, from: $0) } ?? []).filter { local($0) != nil }
    }
    func reconnect() {
        session.getAllTasks { tasks in
            DispatchQueue.main.async {
                for task in tasks {
                    if let text = task.taskDescription, let data = text.data(using: .utf8), let track = try? JSONDecoder().decode(Track.self, from: data) { self.pending[track.id] = 0 }
                }
            }
        }
    }
    private func safe(_ id: String) -> Bool { id.range(of: "^[A-Za-z0-9_-]{1,80}$", options: .regularExpression) != nil }
    func local(_ track: Track) -> URL? {
        guard safe(track.id) else { return nil }
        for suffix in ["m4a", "mp3", "aac", "wav", "aiff", "caf", "flac"] {
            let file = FileManager.musicRoot.appendingPathComponent(track.id + "." + suffix)
            if FileManager.default.fileExists(atPath: file.path) { return file }
        }
        return nil
    }
    @MainActor func download(_ track: Track) async {
        guard local(track) == nil, pending[track.id] == nil, safe(track.id) else { return }
        pending[track.id] = 0
        do {
            let url = try await MusicSearch.stream(track)
            let task = session.downloadTask(with: url)
            task.taskDescription = String(decoding: try JSONEncoder().encode(track), as: UTF8.self)
            task.resume()
        } catch { pending.removeValue(forKey: track.id); issue = error.localizedDescription }
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
        tracks.removeAll { $0.id == track.id }; save()
    }
    func importAudio(_ url: URL) throws -> Track {
        let allowed = url.startAccessingSecurityScopedResource(); defer { if allowed { url.stopAccessingSecurityScopedResource() } }
        let id = "local_" + UUID().uuidString.lowercased()
        let track = Track(id: id, title: url.deletingPathExtension().lastPathComponent, artist: "Локальный файл")
        let suffix = url.pathExtension.lowercased()
        guard ["m4a", "mp3", "aac", "wav", "aiff", "caf", "flac"].contains(suffix) else { throw AppIssue("Этот формат аудио не поддерживается") }
        try FileManager.default.copyItem(at: url, to: FileManager.musicRoot.appendingPathComponent(id + "." + suffix))
        tracks.insert(track, at: 0); save(); return track
    }
    private func save() { do { try JSONEncoder().encode(tracks).write(to: index, options: .atomic) } catch { issue = "Не удалось сохранить список скачанного" } }
    private func track(_ task: URLSessionTask) -> Track? { task.taskDescription?.data(using: .utf8).flatMap { try? JSONDecoder().decode(Track.self, from: $0) } }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if let track = track(downloadTask), totalBytesExpectedToWrite > 0 { pending[track.id] = Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let track = track(downloadTask), safe(track.id) else { return }
        defer { pending.removeValue(forKey: track.id) }
        guard let response = downloadTask.response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else { issue = "Не удалось скачать «\(track.title)»"; return }
        do {
            let target = FileManager.musicRoot.appendingPathComponent(track.id + ".m4a")
            if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
            try FileManager.default.moveItem(at: location, to: target)
            tracks = Track.unique(tracks + [track]); save()
        } catch { issue = error.localizedDescription }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error, let track = track(task) { pending.removeValue(forKey: track.id); issue = error.localizedDescription }
    }
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let done = backgroundCompletion; backgroundCompletion = nil; done?()
    }
}
