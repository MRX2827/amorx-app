import Foundation

/// Coalesces playback/download requests and keeps signed links only until shortly before expiry.
public actor AudioLinkCache {
    public static let shared = AudioLinkCache()
    private struct Entry { let url: URL; let expires: Date }
    private var entries: [String: Entry] = [:]
    private var pending: [String: (UUID, Task<URL, Error>)] = [:]
    public init() {}
    public func invalidate(_ id: String) { entries.removeValue(forKey: id) }
    public func link(for id: String, load: @escaping @Sendable () async throws -> URL) async throws -> URL {
        try Task.checkCancellation()
        if let entry = entries[id], entry.expires > Date() { return entry.url }
        let ticket: UUID, task: Task<URL, Error>
        if let work = pending[id] { (ticket, task) = work }
        else { ticket = UUID(); task = Task { try await load() }; pending[id] = (ticket, task) }
        do {
            let url = try await task.value
            if pending[id]?.0 == ticket {
                pending.removeValue(forKey: id)
                let expires = Self.expiration(url, now: Date())
                entries = entries.filter { $0.value.expires > Date() }
                if entries.count >= 64, let first = entries.min(by: { $0.value.expires < $1.value.expires })?.key { entries.removeValue(forKey: first) }
                if expires > Date() { entries[id] = Entry(url: url, expires: expires) }
            }
            try Task.checkCancellation(); return url
        } catch { if pending[id]?.0 == ticket { pending.removeValue(forKey: id) }; throw error }
    }
    public static func expiration(_ url: URL, now: Date) -> Date {
        let limit = now.addingTimeInterval(20 * 60)
        guard let raw = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "expire" })?.value,
              let epoch = Double(raw) else { return limit }
        return min(limit, Date(timeIntervalSince1970: epoch - 60))
    }
}

public struct AudioByteRange: Equatable, Sendable {
    public let start: Int64, end: Int64, total: Int64
    public var count: Int64 { end - start + 1 }
    public var complete: Bool { end + 1 == total }
    public init?(_ header: String?, offset: Int64, received: Int64) {
        guard let header = header, header.hasPrefix("bytes ") else { return nil }
        let parts = header.dropFirst(6).split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        let bounds = parts[0].split(separator: "-", omittingEmptySubsequences: false)
        guard bounds.count == 2, let start = Int64(bounds[0]), let end = Int64(bounds[1]), let total = Int64(parts[1]),
              start == offset, start >= 0, end >= start, end < total, total <= 512 * 1024 * 1024,
              received == end - start + 1, received > 0 else { return nil }
        self.start = start; self.end = end; self.total = total
    }
    public static func request(offset: Int64, chunkSize: Int64 = 2 * 1024 * 1024, total: Int64? = nil) -> String {
        let end = min(offset + chunkSize - 1, (total ?? Int64.max) - 1)
        return "bytes=\(offset)-\(end)"
    }
}

public struct PhoneLayout: Equatable, Sendable {
    public let width: Double, height: Double
    public init(width: Double, height: Double) { self.width = max(0, width); self.height = max(0, height) }
    public var narrow: Bool { width < 360 }
    public var short: Bool { height < 600 }
    public var compact: Bool { narrow || short }
    public var inset: Double { compact ? 14 : 24 }
    public var spacing: Double { compact ? 12 : 20 }
    public var artwork: Double { max(80, min(width - 2 * inset, height * (compact ? 0.32 : 0.40), 380)) }
    public var showNavigationLabel: Bool { width >= 390 }
}
