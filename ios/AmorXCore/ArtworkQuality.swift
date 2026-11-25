import Foundation

public enum ArtworkQuality {
    public static func candidates(_ source: String) -> [String] {
        guard let url = URL(string: source), let host = url.host?.lowercased(), ["https", "http"].contains(url.scheme ?? "") else { return [source] }
        if ["i.ytimg.com", "img.youtube.com"].contains(host) {
            let parts = url.pathComponents
            if parts.count >= 4, ["vi", "vi_webp"].contains(parts[1]), parts[2].range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil {
                return ["maxresdefault", "sddefault", "hqdefault"].map { "https://i.ytimg.com/vi/\(parts[2])/\($0).jpg" } + [source]
            }
        }
        if host.hasSuffix(".googleusercontent.com") || host == "lh3.ggpht.com" {
            let large = source.replacingOccurrences(of: "=w[0-9]+-h[0-9]+", with: "=w1200-h1200", options: .regularExpression).replacingOccurrences(of: "=s[0-9]+", with: "=s1200", options: .regularExpression)
            return large == source ? [source] : [large, source]
        }
        return [source]
    }
}
