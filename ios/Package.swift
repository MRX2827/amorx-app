// swift-tools-version:5.9
import PackageDescription
let package = Package(name: "AmorXCore", platforms: [.iOS(.v15), .macOS(.v12)], products: [.library(name: "AmorXCore", targets: ["AmorXCore"])], targets: [.target(name: "AmorXCore", path: "AmorXCore"), .testTarget(name: "AmorXCoreTests", dependencies: ["AmorXCore"])])
