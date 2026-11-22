import Foundation
import Combine
import Security

struct CloudSession: Codable {
    let access: String, refresh: String, uid: String, email: String
    let expires: Double
    init(_ row: [String: Any]) throws {
        guard let access = row["access_token"] as? String, let refresh = row["refresh_token"] as? String,
              let user = row["user"] as? [String: Any], let uid = user["id"] as? String else { throw AppIssue("Сервер не вернул аккаунт") }
        self.access = access; self.refresh = refresh; self.uid = uid; email = user["email"] as? String ?? ""
        expires = row["expires_at"] as? Double ?? Date().timeIntervalSince1970 + (row["expires_in"] as? Double ?? 3600)
    }
}

enum SessionVault {
    static let service = "com.amorx.ios.session"
    static func read() -> CloudSession? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(CloudSession.self, from: data)
    }
    static func store(_ session: CloudSession?) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        guard let session = session else { SecItemDelete(query as CFDictionary); return }
        let data = try JSONEncoder().encode(session)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = query; add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw AppIssue("Не удалось сохранить вход") }; return
        }
        guard status == errSecSuccess else { throw AppIssue("Не удалось сохранить вход") }
    }
}

@MainActor final class Cloud: ObservableObject {
    static let base = "https://nxnshkyvpsvckwxquqxm.supabase.co"
    static let key = "sb_publishable_tBZRKUt1QsjbfAjXGtkPSA_39rbXY61"
    @Published private(set) var session = SessionVault.read()
    @Published var profile: Person?
    @Published var profilePublic = true
    @Published var issue: String?
    @Published var posts: [FeedPost] = []
    @Published var stories: [Story] = []
    @Published var people: [Person] = []
    @Published var favoriteSongs: [Track] = []
    var uid: String? { session?.uid }
    var name: String { profile?.name ?? "AmorX" }
    private var revision = UUID()
    private var recentBackup: (uid: String, row: [String: Any]?, date: Date)?
    private var refreshing: Task<CloudSession, Error>?
    private let network: URLSession = {
        let config = URLSessionConfiguration.default; config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 180
        return URLSession(configuration: config)
    }()

    private func perform(_ path: String, method: String = "GET", body: Any? = nil, token: String? = nil,
                         query: [String: String] = [:], prefer: String? = nil, data: Data? = nil, mime: String? = nil) async throws -> Any {
        guard var url = URLComponents(string: Self.base + path) else { throw AppIssue("Неверный адрес") }
        url.queryItems = query.sorted(by: { $0.key < $1.key }).map { URLQueryItem(name: $0.key, value: $0.value) }
        guard let endpoint = url.url else { throw AppIssue("Неверный запрос") }
        var request = URLRequest(url: endpoint); request.httpMethod = method
        request.setValue(Self.key, forHTTPHeaderField: "apikey")
        if let token = token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let prefer = prefer { request.setValue(prefer, forHTTPHeaderField: "Prefer") }
        if let data = data { request.httpBody = data; request.setValue(mime ?? "application/octet-stream", forHTTPHeaderField: "Content-Type") }
        else if let body = body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (payload, response) = try await network.data(for: request)
        guard let reply = response as? HTTPURLResponse else { throw AppIssue("Нет ответа сервера") }
        guard (200..<300).contains(reply.statusCode) else {
            let error = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any]
            throw AppIssue(error?["msg"] as? String ?? error?["message"] as? String ?? error?["error_description"] as? String ?? "Ошибка сервера (\(reply.statusCode))")
        }
        return payload.isEmpty ? [] : (try? JSONSerialization.jsonObject(with: payload)) ?? []
    }
    private func token() async throws -> String {
        guard let current = session else { throw AppIssue("Войдите в аккаунт") }
        if current.expires > Date().timeIntervalSince1970 + 60 { return current.access }
        if let refreshing = refreshing { return try await refreshing.value.access }
        let generation = revision
        let task = Task { () throws -> CloudSession in
            let result = try await perform("/auth/v1/token", method: "POST", body: ["refresh_token": current.refresh], query: ["grant_type": "refresh_token"])
            return try CloudSession(result as? [String: Any] ?? [:])
        }
        refreshing = task
        defer { if revision == generation { refreshing = nil } }
        let next = try await task.value
        guard revision == generation, session?.uid == current.uid else { throw AppIssue("Аккаунт изменился") }
        try SessionVault.store(next); session = next
        return next.access
    }
    func request(_ path: String, method: String = "GET", body: Any? = nil, query: [String: String] = [:], prefer: String? = nil, data: Data? = nil, mime: String? = nil) async throws -> Any {
        let generation = revision; let access = try await token()
        guard revision == generation else { throw AppIssue("Аккаунт изменился") }
        let result = try await perform(path, method: method, body: body, token: access, query: query, prefer: prefer, data: data, mime: mime)
        guard revision == generation else { throw AppIssue("Аккаунт изменился") }
        return result
    }
    func rows(_ table: String, query: [String: String] = [:]) async throws -> [[String: Any]] { try await request("/rest/v1/" + table, query: query) as? [[String: Any]] ?? [] }
    func rpc(_ name: String, _ body: [String: Any] = [:]) async throws -> [[String: Any]] { try await request("/rest/v1/rpc/" + name, method: "POST", body: body) as? [[String: Any]] ?? [] }
    func write(_ table: String, _ body: [String: Any], query: [String: String] = [:], prefer: String = "return=minimal") async throws {
        _ = try await request("/rest/v1/" + table, method: "POST", body: body, query: query, prefer: prefer)
    }
    func remove(_ table: String, query: [String: String]) async throws { _ = try await request("/rest/v1/" + table, method: "DELETE", query: query) }
    func authenticate(email: String, password: String, register: Bool = false, code: String? = nil) async throws -> Bool {
        var result: Any
        if let code = code { result = try await perform("/auth/v1/verify", method: "POST", body: ["type": "email", "email": email, "token": code]) }
        else if register { result = try await perform("/auth/v1/signup", method: "POST", body: ["email": email, "password": password]) }
        else { result = try await perform("/auth/v1/token", method: "POST", body: ["email": email, "password": password], query: ["grant_type": "password"]) }
        let row = result as? [String: Any] ?? [:]
        if register && row["access_token"] == nil { return false }
        let next = try CloudSession(row); try SessionVault.store(next)
        revision = UUID(); refreshing?.cancel(); refreshing = nil
        recentBackup = nil; profile = nil; posts = []; stories = []; people = []; favoriteSongs = []; session = next
        return true
    }
    func signOut() throws {
        try SessionVault.store(nil); refreshing?.cancel(); refreshing = nil; revision = UUID()
        recentBackup = nil; session = nil; profile = nil; posts = []; stories = []; people = []; favoriteSongs = []
    }
    func loadProfile() async throws {
        guard let id = uid else { return }
        let result = try await rows("profiles", query: ["id": "eq.\(id)", "select": "id,username,bio,avatar_b64,is_public,updated_at,favorite_songs", "limit": "1"])
        try Task.checkCancellation(); guard uid == id else { return }
        let row = result.first
        var chosen = row.map(Person.init)
        var isPublic = row?["is_public"] as? Bool ?? false
        if row == nil || !isPublic {
            if let backup = try await backupRow(), let raw = backup["payload"] as? String, let data = raw.data(using: .utf8),
               let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], let saved = root["amorx_profile"] as? [String: Any] {
                let savedIsPublic = saved["public"] as? Bool ?? false
                let backupDate = Self.date(backup["updated_at"] as? String)
                let profileDate = Self.date(row?["updated_at"] as? String)
                if row == nil || (!savedIsPublic && backupDate != nil && profileDate != nil && backupDate! > profileDate!) {
                    chosen = Person(["id": id, "username": saved["name"] ?? "AmorX", "bio": saved["bio"] ?? "", "avatar_b64": saved["avatar"] ?? NSNull()]); isPublic = savedIsPublic
                }
            }
        }
        try Task.checkCancellation(); guard uid == id else { return }
        if chosen == nil {
            let fresh: [String: Any] = ["id": id, "username": "AmorX-" + String(id.prefix(6)), "bio": "", "is_public": false]
            try await write("profiles", fresh, query: ["on_conflict": "id"], prefer: "resolution=ignore-duplicates,return=minimal")
            chosen = Person(fresh); isPublic = false
        }
        profile = chosen; profilePublic = isPublic
        favoriteSongs = (row?["favorite_songs"] as? [[String: Any]] ?? []).compactMap(Track.from)
    }
    private static func timestamp() -> String {
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date())
    }
    private static func date(_ text: String?) -> Date? {
        guard let text = text else { return nil }
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: text) ?? ISO8601DateFormatter().date(from: text)
    }
    func setFavorite(_ track: Track, liked: Bool) async throws {
        guard let uid = uid, !track.id.hasPrefix("local_") else { return }
        let row = try await rows("profiles", query: ["id": "eq.\(uid)", "select": "favorite_songs", "limit": "1"]).first
        var songs = (row?["favorite_songs"] as? [[String: Any]] ?? []).compactMap(Track.from)
        songs.removeAll { $0.id == track.id }; if liked { songs.insert(track, at: 0) }
        _ = try await request("/rest/v1/profiles", method: "PATCH", body: ["favorite_songs": songs.map(\.cloudJSON)], query: ["id": "eq.\(uid)"])
        favoriteSongs = songs
    }
    func saveProfile(name: String, bio: String, avatar: Data?, isPublic: Bool = true) async throws {
        guard let uid = uid else { throw AppIssue("Войдите в аккаунт") }
        var row: [String: Any] = ["id": uid, "username": String(name.prefix(80)), "bio": String(bio.prefix(500)), "is_public": isPublic, "updated_at": Self.timestamp()]
        if let avatar = avatar?.base64EncodedString() ?? profile?.avatar { row["avatar_b64"] = avatar } else { row["avatar_b64"] = NSNull() }
        try await write("profiles", row, query: ["on_conflict": "id"], prefer: "resolution=merge-duplicates,return=minimal")
        profile = Person(row); profilePublic = isPublic
    }
    func run(_ action: @escaping @MainActor () async throws -> Void) {
        Task { do { try await action() } catch is CancellationError { } catch { issue = error.localizedDescription } }
    }
    func refreshFeed(before: FeedPost? = nil, author: String? = nil) async throws {
        var body: [String: Any] = [:]
        if let before = before { body["p_before"] = before.created; body["p_before_id"] = before.id }
        if let author = author { body["p_author"] = author }
        let result = try await rpc("feed_page", body).prefix(20).map(FeedPost.init)
        if before != nil { let ids = Set(posts.map(\.id)); posts += result.filter { !ids.contains($0.id) } }
        else { posts = result }
    }
    func refreshStories() async throws {
        stories = try await rows("stories", query: ["select": "id,owner_id,kind,video_path,track,caption,created_at,expires_at", "expires_at": "gt.\(ISO8601DateFormatter().string(from: Date()))", "order": "created_at.asc", "limit": "200"]).map(Story.init)
        let ids = Set(stories.map(\.owner)); if !ids.isEmpty { people = try await profiles(ids: Array(ids)) }
    }
    func profiles(ids: [String]) async throws -> [Person] {
        guard !ids.isEmpty else { return [] }
        return try await rows("profiles", query: ["select": "id,username,bio,avatar_b64", "id": "in.(\(ids.joined(separator: ",")))"]).map(Person.init)
    }
    func findPeople(_ name: String) async throws -> [Person] {
        let clean = name.filter { $0.isLetter || $0.isNumber || $0 == "_" || $0 == " " }
        return try await rows("profiles", query: ["select": "id,username,bio,avatar_b64", "is_public": "eq.true", "username": "ilike.*\(clean)*", "limit": "50"]).map(Person.init)
    }
    func following(_ id: String) async throws -> Bool {
        guard let uid = uid else { return false }
        return !(try await rows("feed_follows", query: ["follower_id": "eq.\(uid)", "author_id": "eq.\(id)", "limit": "1"])).isEmpty
    }
    func follow(_ id: String, enabled: Bool) async throws {
        guard let uid = uid else { return }
        if enabled { try await write("feed_follows", ["follower_id": uid, "author_id": id]) }
        else { try await remove("feed_follows", query: ["follower_id": "eq.\(uid)", "author_id": "eq.\(id)"]) }
    }
    func like(_ post: FeedPost) async throws {
        guard let uid = uid else { return }
        if post.liked { try await remove("feed_likes", query: ["post_id": "eq.\(post.id)", "user_id": "eq.\(uid)"]) }
        else { try await write("feed_likes", ["post_id": post.id, "user_id": uid]) }
    }
    func mediaURL(bucket: String, path: String, publicFile: Bool = false) async throws -> URL {
        guard !path.contains(".."), !path.hasPrefix("/") else { throw AppIssue("Неверный файл") }
        if publicFile { return URL(string: Self.base + "/storage/v1/object/public/\(bucket)/\(path)")! }
        let result = try await request("/storage/v1/object/sign/\(bucket)/\(path)", method: "POST", body: ["expiresIn": 3600]) as? [String: Any]
        guard let raw = result?["signedURL"] as? String else { throw AppIssue("Медиа недоступно") }
        let address = raw.hasPrefix("https://") ? raw : Self.base + (raw.hasPrefix("/storage/v1/") ? "" : "/storage/v1") + raw
        guard let url = URL(string: address), url.host == URL(string: Self.base)?.host else { throw AppIssue("Неверная ссылка") }; return url
    }
    func upload(bucket: String, path: String, file: URL, mime: String) async throws {
        let size = (try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
        guard size > 0, size <= 40 * 1024 * 1024 else { throw AppIssue("Медиа должно быть не больше 40 МБ") }
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        _ = try await request("/storage/v1/object/\(bucket)/\(path)", method: "POST", data: data, mime: mime)
    }
    func publish(file: URL, video: Bool, caption: String, story: Bool = false) async throws {
        guard let uid = uid else { return }
        let id = UUID().uuidString.lowercased(), path = "\(uid)/\(id).\(video ? "mp4" : "jpg")"
        try await upload(bucket: story ? "stories" : "feed-media", path: path, file: file, mime: video ? "video/mp4" : "image/jpeg")
        do {
            if story { try await write("stories", ["owner_id": uid, "kind": "video", "video_path": path, "caption": String(caption.prefix(500))]) }
            else { try await write("feed_posts", ["id": id, "author_id": uid, "author_name": name, "caption": String(caption.prefix(1000)), "media_type": video ? "video/mp4" : "image/jpeg", "media_path": path]) }
        } catch {
            _ = try? await request("/storage/v1/object/\(story ? "stories" : "feed-media")", method: "DELETE", body: ["prefixes": [path]])
            throw error
        }
    }
    func postSongStory(track: Track, file: URL, start: Double, end: Double, caption: String) async throws {
        guard let uid = uid else { return }
        let path = "\(uid)/\(UUID().uuidString.lowercased()).m4a"
        try await upload(bucket: "story-audio", path: path, file: file, mime: "audio/mp4")
        var metadata = track.cloudJSON; metadata["start"] = Int(start * 1000); metadata["end"] = Int(end * 1000); metadata["audio_path"] = path
        try await write("stories", ["owner_id": uid, "kind": "song", "track": metadata, "caption": String(caption.prefix(500))])
    }
    func comments(_ post: FeedPost) async throws -> [[String: Any]] { try await rows("feed_comments", query: ["post_id": "eq.\(post.id)", "select": "id,author_name,body,created_at", "order": "created_at.desc", "limit": "100"]) }
    func comment(_ post: FeedPost, body: String) async throws {
        guard let uid = uid, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        try await write("feed_comments", ["id": UUID().uuidString.lowercased(), "post_id": post.id, "user_id": uid, "author_name": name, "body": String(body.prefix(500))])
    }
    func thread(_ peer: Person) async throws -> [Message] {
        guard let uid = uid else { return [] }
        return try await rows("messages", query: ["select": "*", "or": "(and(sender_id.eq.\(uid),recipient_id.eq.\(peer.id)),and(sender_id.eq.\(peer.id),recipient_id.eq.\(uid)))", "order": "created_at.desc", "limit": "200"]).map(Message.init).reversed()
    }
    func conversations() async throws -> [Person] {
        guard let uid = uid else { return [] }
        let messages = try await rows("messages", query: ["select": "sender_id,recipient_id", "order": "created_at.desc", "limit": "300"])
        let ids = messages.compactMap { row -> String? in let sender = row["sender_id"] as? String; return sender == uid ? row["recipient_id"] as? String : sender }
        return try await profiles(ids: Array(Set(ids)))
    }
    func send(_ peer: Person, body: String, track: Track? = nil) async throws {
        guard let uid = uid, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || track != nil else { return }
        var row: [String: Any] = ["sender_id": uid, "recipient_id": peer.id, "body": String(body.prefix(4000))]
        if let track = track { row["track"] = track.cloudJSON }
        try await write("messages", row)
    }
    func backupRow(fresh: Bool = false) async throws -> [String: Any]? {
        guard let uid = uid else { return nil }
        if !fresh, let cached = recentBackup, cached.uid == uid, Date().timeIntervalSince(cached.date) < 5 { return cached.row }
        let row = try await rows("backups", query: ["select": "user_id,payload,updated_at", "user_id": "eq.\(uid)", "limit": "1"]).first
        recentBackup = (uid, row, Date()); return row
    }
    func backupPayload() async throws -> [String: Any]? {
        guard let row = try await backupRow() else { return nil }
        guard let raw = row["payload"] as? String, let data = raw.data(using: .utf8), let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw AppIssue("Не удалось прочитать облачную копию") }
        return root
    }
    func saveBackup(downloads: [Track], playlist: [Track], code: String?) async throws {
        guard let uid = uid else { return }
        for _ in 0..<3 {
            let existing = try await backupRow(fresh: true)
            var root: [String: Any] = [:]
            if let existing = existing {
                guard let raw = existing["payload"] as? String, let data = raw.data(using: .utf8),
                      let parsed = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw AppIssue("Облачная копия повреждена. Сохранение отменено") }
                root = parsed
            }
            root = MusicBackupPlan.merging(root, downloads: downloads, playlist: playlist, code: code)
            let online = root["amorx_online"] as? [String: Any] ?? [:]
            let main = online["amorx_playlist"] as? [String: Any] ?? [:]
            guard (main["tracks"] as? [[String: Any]] ?? []).count <= 1000 else { throw AppIssue("В плейлисте AmorX может быть до 1000 песен") }
            let raw = String(decoding: try JSONSerialization.data(withJSONObject: root), as: UTF8.self)
            let body: [String: Any] = ["user_id": uid, "payload": raw, "updated_at": Self.timestamp()]
            let rows: [[String: Any]]
            if let timestamp = existing?["updated_at"] as? String {
                rows = try await request("/rest/v1/backups", method: "PATCH", body: body, query: ["user_id": "eq.\(uid)", "updated_at": "eq.\(timestamp)"], prefer: "return=representation") as? [[String: Any]] ?? []
            } else {
                rows = try await request("/rest/v1/backups", method: "POST", body: body, query: ["on_conflict": "user_id"], prefer: "resolution=ignore-duplicates,return=representation") as? [[String: Any]] ?? []
            }
            if !rows.isEmpty { recentBackup = nil; return }
        }
        throw AppIssue("Копия изменена на другом устройстве. Повторите сохранение")
    }
    func sharePlaylist(_ tracks: [Track], code: String?) async throws -> String {
        guard let uid = uid, !tracks.isEmpty else { throw AppIssue("Плейлист пуст") }
        guard tracks.count <= 1000 else { throw AppIssue("В ссылке может быть до 1000 песен") }
        let body: [String: Any] = ["title": "Плейлист AmorX", "tracks": tracks.map(\.cloudJSON)]
        var result: [[String: Any]] = []
        if let code = code { result = try await request("/rest/v1/shared_playlists", method: "PATCH", body: body, query: ["code": "eq.\(code)", "owner_id": "eq.\(uid)"], prefer: "return=representation") as? [[String: Any]] ?? [] }
        if result.isEmpty { var row = body; row["owner_id"] = uid; result = try await request("/rest/v1/shared_playlists", method: "POST", body: row, prefer: "return=representation") as? [[String: Any]] ?? [] }
        guard let code = result.first?["code"] as? String else { throw AppIssue("Не удалось получить ссылку") }; return code
    }
    func importPlaylist(_ text: String) async throws -> [Track] {
        let pattern = "(?:playlist/|^)([a-fA-F0-9]{8})(?:$|[?#])"
        guard let regex = try? NSRegularExpression(pattern: pattern), let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), let range = Range(match.range(at: 1), in: text) else { throw AppIssue("Вставьте ссылку на плейлист AmorX") }
        let rows = try await rpc("get_shared_playlist", ["p_code": String(text[range]).lowercased()])
        guard let list = rows.first?["tracks"] as? [[String: Any]] else { throw AppIssue("Плейлист не найден") }; return Track.unique(list.compactMap(Track.from))
    }
}
