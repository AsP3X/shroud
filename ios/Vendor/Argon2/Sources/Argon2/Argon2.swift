import Argon2C
import Foundation

public enum Argon2Error: Error {
    case failed(Int32)
}

public enum Argon2 {
    /// Raw argon2id. `memoryKiB` is kibibytes, matching the C `m_cost`.
    public static func argon2id(
        password: Data,
        salt: Data,
        iterations: UInt32,
        memoryKiB: UInt32,
        parallelism: UInt32,
        outputLength: Int
    ) throws -> Data {
        precondition(outputLength > 0)
        var output = Data(count: outputLength)
        let status: Int32 = output.withUnsafeMutableBytes { out in
            password.withUnsafeBytes { pwd in
                salt.withUnsafeBytes { saltBytes in
                    guard let outBase = out.baseAddress,
                          let pwdBase = pwd.baseAddress,
                          let saltBase = saltBytes.baseAddress
                    else {
                        return Int32(-1)
                    }
                    return argon2id_hash_raw(
                        iterations,
                        memoryKiB,
                        parallelism,
                        pwdBase,
                        pwd.count,
                        saltBase,
                        saltBytes.count,
                        outBase,
                        out.count
                    )
                }
            }
        }
        guard status == 0 else { throw Argon2Error.failed(status) }
        return output
    }
}
