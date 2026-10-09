// swift-tools-version:5.9
import PackageDescription

// PHC's reference argon2id (see LICENSE). The optimized build is not vendored: parallelism
// is 1, and ARGON2_NO_THREADS keeps the reference core off pthreads.
let package = Package(
    name: "Argon2",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "Argon2", targets: ["Argon2"]),
    ],
    targets: [
        .target(
            name: "Argon2C",
            path: "Sources/Argon2C",
            publicHeadersPath: "include",
            cSettings: [
                .headerSearchPath("include"),
                .define("ARGON2_NO_THREADS"),
            ]
        ),
        .target(
            name: "Argon2",
            dependencies: ["Argon2C"],
            path: "Sources/Argon2"
        ),
    ]
)
