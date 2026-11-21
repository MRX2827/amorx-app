import Foundation

public struct Track: Codable, Hashable, Identifiable, Sendable {
    public var id: String
    public var title: String
    public var artist: String
    public var duration: Double
    public var artwork: String?
    public init(id: String, title: String, artist: String, duration: Double = 0, artwork: String? = nil) {
        self.id = Track.identity(id); self.title = title; self.artist = artist; self.duration = max(0, duration); self.artwork = artwork
    }
    public static func identity(_ value: String) -> String {
        for prefix in ["online_", "offline_"] where value.hasPrefix(prefix) { return String(value.dropFirst(prefix.count)) }
        return value
    }
    public static func from(_ row: [String: Any]) -> Track? {
        var raw = row["id"] as? String
        for key in ["videoUrl", "uri"] {
            if let text = row[key] as? String, let url = URLComponents(string: text) {
                if ["youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com"].contains(url.host ?? "") {
                    raw = url.queryItems?.first(where: { $0.name == "v" })?.value ?? raw
                } else if url.host == "youtu.be" { raw = url.path.split(separator: "/").first.map(String.init) ?? raw }
            }
        }
        guard let id = raw.map(identity), id.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil else { return nil }
        let ms = ["dur", "durMs", "duration", "durationMs"].compactMap { row[$0] as? NSNumber }.first?.doubleValue ?? 0
        let artwork = ["art", "artwork", "artworkUri"].compactMap { row[$0] as? String }.first(where: { URL(string: $0)?.scheme == "https" })
        return Track(id: id, title: row["title"] as? String ?? "Без названия", artist: row["artist"] as? String ?? "", duration: ms / 1000, artwork: artwork)
    }
    public var cloudJSON: [String: Any] {
        var row: [String: Any] = ["id": id, "title": title, "artist": artist, "dur": Int(duration * 1000), "videoUrl": "https://www.youtube.com/watch?v=\(id)"]
        if let art = artwork, URL(string: art)?.scheme == "https" { row["art"] = art; row["artwork"] = art }
        return row
    }
    public static func unique(_ tracks: [Track]) -> [Track] {
        var seen = Set<String>(); return tracks.filter { seen.insert(identity($0.id)).inserted }
    }
}

public struct MusicBackupPlan: Identifiable {
    public let id = UUID()
    public let downloads: [Track]
    public let playlist: [Track]
    public let code: String?
    public init(_ root: [String: Any]) throws {
        let online = root["amorx_online"] as? [String: Any] ?? [:]
        downloads = Track.unique((online["downloads"] as? [[String: Any]] ?? []).compactMap(Track.from))
        let own = online["amorx_playlist"] as? [String: Any] ?? [:]
        var tracks = (own["tracks"] as? [[String: Any]] ?? []).compactMap(Track.from)
        let raw = root["playlists_data"]
        let data = (raw as? String)?.data(using: .utf8)
        let lists: [[String: Any]]
        if let data = data { lists = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] ?? [] }
        else { lists = raw as? [[String: Any]] ?? [] }
        for list in lists where ["amorx", "плейлист amorx"].contains((list["name"] as? String ?? "").lowercased().trimmingCharacters(in: .whitespaces)) {
            tracks += (list["songs"] as? [[String: Any]] ?? []).compactMap(Track.from)
        }
        playlist = Track.unique(tracks); code = own["code"] as? String
    }
    public static func merging(_ root: [String: Any], downloads: [Track], playlist: [Track], code: String?) -> [String: Any] {
        var copy = root
        if copy["preferences"] == nil { copy["preferences"] = [String: Any]() }
        if copy["preferences_types"] == nil { copy["preferences_types"] = [String: String]() }
        if copy["backup_version"] == nil { copy["backup_version"] = 4 }
        copy["timestamp"] = Int(Date().timeIntervalSince1970 * 1000)
        var online = copy["amorx_online"] as? [String: Any] ?? [:]
        let oldDownloads = (online["downloads"] as? [[String: Any]] ?? []).compactMap(Track.from)
        online["downloads"] = Track.unique(oldDownloads + downloads).map(\.cloudJSON)
        var main = online["amorx_playlist"] as? [String: Any] ?? [:]
        let oldPlaylist = (main["tracks"] as? [[String: Any]] ?? []).compactMap(Track.from)
        main["tracks"] = Track.unique(oldPlaylist + playlist).map(\.cloudJSON)
        if let code = code { main["code"] = code }
        online["amorx_playlist"] = main; copy["amorx_online"] = online
        return copy
    }
}

public struct ShuffleCycle {
    private var heard = Set<String>()
    private var bag: [String] = []
    private var history: [String] = []
    private var cursor = -1
    public init() {}
    public mutating func reset(ids: [String], current: String?) {
        heard = []; bag = Array(Set(ids)).shuffled(); history = []; cursor = -1
        if let current = current { select(current) }
    }
    public mutating func select(_ id: String) {
        heard.insert(id); bag.removeAll { $0 == id }
        if history.indices.contains(cursor), history[cursor] == id { return }
        if cursor + 1 < history.count { history.removeSubrange((cursor + 1)..<history.count) }
        history.append(id); cursor = history.count - 1
        if history.count > 4096 { history.removeFirst(); cursor -= 1 }
    }
    public mutating func next(ids: [String], current: String?) -> String? {
        let live = Set(ids); guard !live.isEmpty else { return nil }
        heard.formIntersection(live); bag.removeAll { !live.contains($0) }
        bag += live.subtracting(heard).subtracting(bag).shuffled()
        while cursor + 1 < history.count { cursor += 1; if live.contains(history[cursor]) { return history[cursor] } }
        if bag.isEmpty {
            heard = []; bag = Array(live).shuffled()
            if bag.count > 1, bag.first == current { bag.swapAt(0, Int.random(in: 1..<bag.count)) }
        }
        let result = bag.removeFirst(); select(result); return result
    }
    public mutating func previous(ids: [String]) -> String? {
        let live = Set(ids)
        while cursor > 0 { cursor -= 1; if live.contains(history[cursor]) { return history[cursor] } }
        return nil
    }
}

public func timeLabel(_ seconds: Double) -> String {
    let value = seconds.isFinite ? max(0, Int(seconds)) : 0
    return String(format: "%d:%02d", value / 60, value % 60)
}

struct Person: Identifiable, Hashable {
    let id: String
    var name: String
    var avatar: String?
    var bio: String
    init(_ row: [String: Any]) { id = row["id"] as? String ?? ""; name = row["username"] as? String ?? "Без имени"; avatar = row["avatar_b64"] as? String; bio = row["bio"] as? String ?? "" }
}
struct FeedPost: Identifiable {
    let id: String, authorID: String, author: String, caption: String, path: String, type: String, created: String
    var likes: Int, comments: Int, liked: Bool
    init(_ row: [String: Any]) {
        id = row["id"] as? String ?? ""; authorID = row["author_id"] as? String ?? ""; author = row["author_name"] as? String ?? "Без имени"
        caption = row["caption"] as? String ?? ""; path = row["media_path"] as? String ?? ""; type = row["media_type"] as? String ?? "image/jpeg"
        created = row["created_at"] as? String ?? ""; likes = row["likes"] as? Int ?? 0; comments = row["comments"] as? Int ?? 0; liked = row["liked"] as? Bool ?? false
    }
}
struct Story: Identifiable {
    let id: String, owner: String, caption: String
    let video: String?
    let track: Track?
    let audio: String?
    let start: Double
    let end: Double
    init(_ row: [String: Any]) {
        id = row["id"] as? String ?? ""; owner = row["owner_id"] as? String ?? ""; caption = row["caption"] as? String ?? ""
        video = row["video_path"] as? String
        let song = row["track"] as? [String: Any] ?? [:]; track = Track.from(song); audio = song["audio_path"] as? String
        start = (song["start"] as? Double ?? 0) / 1000; end = (song["end"] as? Double ?? 15000) / 1000
    }
}
struct Message: Identifiable {
    let id: String, sender: String, recipient: String, text: String
    let track: Track?
    init(_ row: [String: Any]) { id = row["id"] as? String ?? ""; sender = row["sender_id"] as? String ?? ""; recipient = row["recipient_id"] as? String ?? ""; text = row["body"] as? String ?? ""; track = (row["track"] as? [String: Any]).flatMap(Track.from) }
}
struct AppIssue: LocalizedError {
    let message: String
    var errorDescription: String? { message }
    init(_ message: String) { self.message = message }
}
