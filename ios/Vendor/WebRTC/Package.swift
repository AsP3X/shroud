// swift-tools-version:5.9
import PackageDescription

// The binary lives in the repo. Fetching it from GitHub during an Xcode resolve has
// come back as a truncated archive, which Xcode then reports as a missing WebRTC product.
let package = Package(
    name: "WebRTC",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "WebRTC", targets: ["WebRTC"]),
    ],
    targets: [
        .binaryTarget(name: "WebRTC", path: "WebRTC.xcframework.zip"),
    ]
)
