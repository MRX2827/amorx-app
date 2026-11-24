// swift-tools-version:5.9
import PackageDescription
let package = Package(name: "YouTubeKit", platforms: [.iOS(.v15), .macOS(.v12)], products: [.library(name: "YouTubeKit", targets: ["YouTubeKit"])], targets: [.target(name: "YouTubeKit", resources: [.process("Resources")])])
