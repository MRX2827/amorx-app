import Foundation
import Combine
import AVFoundation
import MediaPlayer
import YouTubeKit
import UIKit

actor MusicSearch {
    static func stream(_ track: Track) async throws -> URL {
        try await AudioLinkCache.shared.link(for: track.id) {
            let streams = try await YouTube(videoID: track.id).audioStreams
            guard let best = streams.filterAudioOnly().filter({ $0.isNativelyPlayable }).highestAudioBitrateStream() else { throw AppIssue("Для этой песни нет доступного аудиопотока") }
            return best.url
        }
    }
    static func search(_ query: String) async throws -> [Track] {
        guard var components = URLComponents(string: "https://www.youtube.com/results") else { return [] }
        components.queryItems = [URLQueryItem(name: "search_query", value: query + " music"), URLQueryItem(name: "hl", value: "ru")]
        var request = URLRequest(url: components.url!, cachePolicy: .useProtocolCachePolicy, timeoutInterval: 20); request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200, let html = String(data: data, encoding: .utf8),
              let start = html.range(of: "var ytInitialData = ")?.upperBound,
              let end = html.range(of: ";</script>", range: start..<html.endIndex)?.lowerBound,
              let json = String(html[start..<end]).data(using: .utf8) else { throw AppIssue("Поиск временно недоступен. Повторите позже") }
        let root = try JSONSerialization.jsonObject(with: json)
        var results: [Track] = []
        func text(_ value: Any?) -> String {
            guard let object = value as? [String: Any] else { return "" }
            return object["simpleText"] as? String ?? (object["runs"] as? [[String: Any]] ?? []).compactMap { $0["text"] as? String }.joined()
        }
        func walk(_ value: Any) {
            if let object = value as? [String: Any] {
                if let video = object["videoRenderer"] as? [String: Any], let id = video["videoId"] as? String {
                    let seconds = text(video["lengthText"]).split(separator: ":").reduce(0) { $0 * 60 + (Int($1) ?? 0) }
                    let thumbnail = ((video["thumbnail"] as? [String: Any])?["thumbnails"] as? [[String: Any]])?.last?["url"] as? String
                    results.append(Track(id: id, title: text(video["title"]), artist: text(video["ownerText"]), duration: Double(seconds), artwork: thumbnail))
                }
                for child in object.values { walk(child) }
            } else if let array = value as? [Any] { array.forEach(walk) }
        }
        walk(root)
        return Array(Track.unique(results).prefix(40))
    }
}

@MainActor final class Playback: ObservableObject {
    @Published private(set) var current: Track?
    @Published private(set) var queue: [Track] = []
    @Published private(set) var playing = false
    @Published private(set) var loading = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var shuffle = false
    @Published var repeatOne = false
    @Published var issue: String?
    @Published var volume: Float = 1 { didSet { audio.volume = volume } }
    let audio = AVPlayer()
    private var observer: Any?
    private var interruptionObserver: NSObjectProtocol?
    private var resumeAfterInterruption = false
    private var artworkTask: Task<Void, Never>?
    private var artwork: MPMediaItemArtwork?
    private var status: NSKeyValueObservation?
    private var ended: NSObjectProtocol?
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    private var load: Task<Void, Never>?
    private var generation = UUID()
    private var prefetch: Task<Void, Never>?
    private var order = ShuffleCycle()
    var didPlay: ((Track) -> Void)?
    init() {
        audio.automaticallyWaitsToMinimizeStalling = true
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        observer = audio.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.2, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self = self else { return }
                self.position = time.seconds.isFinite ? time.seconds : 0
                if let length = self.audio.currentItem?.duration.seconds, length.isFinite, length > 0 { self.duration = length }
                self.nowPlaying()
            }
        }
        ended = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] note in
            Task { @MainActor in
                guard let self = self, note.object as? AVPlayerItem === self.audio.currentItem else { return }
                if self.repeatOne, let current = self.current { self.start(current) } else { self.next() }
            }
        }
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let kind = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            Task { @MainActor in
                guard let self = self else { return }
                if kind == .began { self.resumeAfterInterruption = self.playing; self.setPlaying(false) }
                else {
                    let options = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
                    if self.resumeAfterInterruption && options.contains(.shouldResume) { try? AVAudioSession.sharedInstance().setActive(true); self.setPlaying(true) }
                    self.resumeAfterInterruption = false
                }
            }
        }
        let commands = MPRemoteCommandCenter.shared()
        remoteTargets.append((commands.playCommand, commands.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.setPlaying(true) }; return .success }))
        remoteTargets.append((commands.pauseCommand, commands.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.setPlaying(false) }; return .success }))
        remoteTargets.append((commands.nextTrackCommand, commands.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.next() }; return .success }))
        remoteTargets.append((commands.previousTrackCommand, commands.previousTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.previous() }; return .success }))
        remoteTargets.append((commands.changePlaybackPositionCommand, commands.changePlaybackPositionCommand.addTarget { [weak self] event in
            if let event = event as? MPChangePlaybackPositionCommandEvent { Task { @MainActor in self?.seek(event.positionTime) }; return .success }; return .commandFailed
        }))
        commands.changeShuffleModeCommand.isEnabled = true
        remoteTargets.append((commands.changeShuffleModeCommand, commands.changeShuffleModeCommand.addTarget { [weak self] event in
            if let event = event as? MPChangeShuffleModeCommandEvent { Task { @MainActor in self?.setShuffle(event.shuffleType != .off) }; return .success }; return .commandFailed
        }))
        commands.changeRepeatModeCommand.isEnabled = true
        remoteTargets.append((commands.changeRepeatModeCommand, commands.changeRepeatModeCommand.addTarget { [weak self] event in
            if let event = event as? MPChangeRepeatModeCommandEvent { Task { @MainActor in self?.repeatOne = event.repeatType == .one }; return .success }; return .commandFailed
        }))
    }
    #if targetEnvironment(simulator)
    func prepareLayoutPreview(_ track: Track, queue: [Track]) {
        current = track; self.queue = queue; duration = track.duration; position = 42; loading = false; playing = false
    }
    #endif
    func clear() {
        load?.cancel(); prefetch?.cancel(); artworkTask?.cancel(); artwork = nil; generation = UUID(); status = nil; audio.pause(); audio.replaceCurrentItem(with: nil)
        current = nil; queue = []; playing = false; loading = false; position = 0; duration = 0; issue = nil
        order = ShuffleCycle(); MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
    func play(_ track: Track, in tracks: [Track]? = nil) {
        let list = Track.unique(tracks ?? queue)
        if !list.isEmpty, list.map(\.id) != queue.map(\.id) { queue = list; order.reset(ids: queue.map(\.id), current: track.id) }
        if queue.isEmpty { queue = [track]; order.reset(ids: [track.id], current: track.id) }
        order.select(track.id)
        if current?.id == track.id, issue == nil { setPlaying(true); return }
        start(track)
    }
    func playShuffled(_ tracks: [Track]) {
        let list = Track.unique(tracks); guard !list.isEmpty else { return }
        if Set(list.map(\.id)) == Set(queue.map(\.id)), current != nil { setShuffle(true); setPlaying(true); return }
        shuffle = true; repeatOne = false; queue = list
        guard let first = list.randomElement() else { return }
        order.reset(ids: list.map(\.id), current: first.id); start(first)
    }
    func setShuffle(_ enabled: Bool) {
        if shuffle != enabled { shuffle = enabled; if enabled { repeatOne = false } }
        MPRemoteCommandCenter.shared().changeShuffleModeCommand.currentShuffleType = enabled ? .items : .off
    }
    func toggleShuffle() { setShuffle(!shuffle) }
    func toggle() { if issue != nil, let current = current { start(current) } else { setPlaying(!playing) } }
    func setPlaying(_ value: Bool) {
        playing = value
        if value { if !loading { audio.play() } } else { audio.pause() }
        nowPlaying()
    }
    func seek(_ seconds: Double) { audio.seek(to: CMTime(seconds: min(max(0, seconds), max(1, duration)), preferredTimescale: 600)); position = seconds }
    func next() {
        guard !queue.isEmpty else { return }
        let id = shuffle ? order.next(ids: queue.map(\.id), current: current?.id) : queue[((queue.firstIndex(where: { $0.id == current?.id }) ?? -1) + 1) % queue.count].id
        if let track = queue.first(where: { $0.id == id }) { order.select(track.id); start(track) }
    }
    func previous() {
        guard !queue.isEmpty else { return }
        if position > 3 { seek(0); return }
        if shuffle { if let id = order.previous(ids: queue.map(\.id)), let track = queue.first(where: { $0.id == id }) { start(track) } else { seek(0) } }
        else { let index = queue.firstIndex(where: { $0.id == current?.id }) ?? 0; let track = queue[(index + queue.count - 1) % queue.count]; order.select(track.id); start(track) }
    }
    private func start(_ track: Track) {
        load?.cancel(); prefetch?.cancel(); let request = UUID(); generation = request
        artworkTask?.cancel(); artwork = nil
        if let source = track.artwork, let imageURL = URL(string: source), imageURL.scheme == "https" {
            artworkTask = Task {
                if let result = try? await URLSession.shared.data(from: imageURL), let image = UIImage(data: result.0), generation == request {
                    artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }; nowPlaying()
                }
            }
        }
        current = track; position = 0; duration = track.duration; loading = true; issue = nil; playing = true; audio.pause()
        load = Task {
            do {
                let url: URL
                if let local = Downloads.shared.local(track) { url = local } else { url = try await MusicSearch.stream(track) }
                try Task.checkCancellation(); guard generation == request else { return }
                try AVAudioSession.sharedInstance().setActive(true)
                let item = AVPlayerItem(url: url)
                item.preferredForwardBufferDuration = 3
                audio.replaceCurrentItem(with: item)
                status = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
                    Task { @MainActor in
                        guard let self = self, self.generation == request else { return }
                        if item.status == .failed { self.issue = item.error?.localizedDescription ?? "Не удалось воспроизвести трек"; self.loading = false; self.playing = false }
                        if item.status == .readyToPlay { self.loading = false; if self.playing { self.audio.playImmediately(atRate: 1); self.prepareNext() } }
                    }
                }
                didPlay?(track); nowPlaying()
                try await Task.sleep(nanoseconds: 30_000_000_000)
                if generation == request, loading { throw AppIssue("Трек не отвечает. Повторите запуск или выберите следующий") }
            } catch is CancellationError { }
            catch { if generation == request { issue = error.localizedDescription; loading = false; playing = false } }
        }
    }
    private func prepareNext() {
        guard queue.count > 1 else { return }
        var copy = order
        let id = shuffle ? copy.next(ids: queue.map(\.id), current: current?.id) : queue[((queue.firstIndex { $0.id == current?.id } ?? 0) + 1) % queue.count].id
        guard let next = queue.first(where: { $0.id == id }), Downloads.shared.local(next) == nil else { return }
        prefetch?.cancel()
        prefetch = Task(priority: .utility) { _ = try? await MusicSearch.stream(next) }
    }
    private func nowPlaying() {
        guard let current = current else { return }
        var info: [String: Any] = [MPMediaItemPropertyTitle: current.title, MPMediaItemPropertyArtist: current.artist,
            MPMediaItemPropertyPlaybackDuration: duration, MPNowPlayingInfoPropertyElapsedPlaybackTime: position, MPNowPlayingInfoPropertyPlaybackRate: playing ? 1 : 0]
        if let artwork = artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
