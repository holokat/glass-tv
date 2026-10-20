import Foundation
import Security
import Network

/// A short-lived, in-memory identity authenticated by the existing TV pairing.
/// The private key never enters Keychain or a filesystem file.
struct MovieTLSIdentity {
    let certificate: SecCertificate
    let secIdentity: sec_identity_t
    private let key: SecKey
    private let identity: SecIdentity

    static func make(forIPv4 address: String) throws -> MovieTLSIdentity {
        let components = address.split(separator: ".", omittingEmptySubsequences: false)
        guard components.count == 4 else { throw IdentityError.invalidAddress }
        let numbers = components.compactMap { UInt8($0) }
        guard numbers.count == 4 else { throw IdentityError.invalidAddress }
        var failure: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey([
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecAttrIsPermanent: false
        ] as CFDictionary, &failure),
              let publicKey = SecKeyCopyPublicKey(key),
              let publicBytes = SecKeyCopyExternalRepresentation(publicKey, &failure) else {
            throw IdentityError.creationFailed
        }

        let algorithm = DER.sequence(DER.oid([1, 2, 840, 10045, 4, 3, 2]))
        let name = DER.sequence(DER.set(DER.sequence(
            DER.oid([2, 5, 4, 3]), DER.tag(0x0c, Data("Glass TV Movies".utf8))
        )))
        let publicInfo = DER.sequence(
            DER.sequence(DER.oid([1, 2, 840, 10045, 2, 1]), DER.oid([1, 2, 840, 10045, 3, 1, 7])),
            DER.bitString(publicBytes as Data)
        )
        var serial = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, serial.count, &serial) == errSecSuccess else {
            throw IdentityError.creationFailed
        }
        let now = Date()
        let extensions = DER.tag(0xa3, DER.sequence(
            DER.ext([2, 5, 29, 19], critical: true, content: DER.sequence()),
            DER.ext([2, 5, 29, 15], critical: true, content: DER.bitString(Data([0x80]), unusedBits: 7)),
            DER.ext([2, 5, 29, 37], content: DER.sequence(DER.oid([1, 3, 6, 1, 5, 5, 7, 3, 1]))),
            DER.ext([2, 5, 29, 17], content: DER.sequence(DER.tag(0x87, Data(numbers))))
        ))
        let tbs = DER.sequence(
            DER.tag(0xa0, DER.integer(Data([2]))),
            DER.integer(Data(serial)), algorithm, name,
            DER.sequence(DER.utcTime(now.addingTimeInterval(-300)), DER.utcTime(now.addingTimeInterval(86_400))),
            name, publicInfo, extensions
        )
        guard let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, tbs as CFData, &failure),
              SecKeyVerifySignature(publicKey, .ecdsaSignatureMessageX962SHA256, tbs as CFData, signature, &failure) else {
            throw IdentityError.creationFailed
        }
        let certificateBytes = DER.sequence(tbs, algorithm, DER.bitString(signature as Data))
        guard let certificate = SecCertificateCreateWithData(nil, certificateBytes as CFData),
              let identity = SecIdentityCreate(nil, certificate, key),
              let tlsIdentity = sec_identity_create(identity) else {
            throw IdentityError.creationFailed
        }
        return MovieTLSIdentity(certificate: certificate, secIdentity: tlsIdentity, key: key, identity: identity)
    }

    enum IdentityError: LocalizedError {
        case invalidAddress, creationFailed
        var errorDescription: String? {
            switch self {
            case .invalidAddress: return "The movie stream requires a local IPv4 address."
            case .creationFailed: return "Could not create the secure movie stream identity."
            }
        }
    }
}

private enum DER {
    static func tag(_ tag: UInt8, _ data: Data) -> Data {
        var result = Data([tag])
        if data.count < 128 { result.append(UInt8(data.count)) }
        else {
            var bytes: [UInt8] = []
            var length = data.count
            repeat { bytes.insert(UInt8(length & 0xff), at: 0); length >>= 8 } while length > 0
            result.append(0x80 | UInt8(bytes.count)); result.append(contentsOf: bytes)
        }
        result.append(data)
        return result
    }
    static func sequence(_ values: Data...) -> Data { tag(0x30, values.reduce(into: Data()) { $0.append($1) }) }
    static func set(_ value: Data) -> Data { tag(0x31, value) }
    static func integer(_ bytes: Data) -> Data {
        var positive = Array(bytes)
        while positive.count > 1 && positive.first == 0 { positive.removeFirst() }
        if positive.isEmpty { positive = [0] }
        if positive[0] & 0x80 != 0 { positive.insert(0, at: 0) }
        return tag(0x02, Data(positive))
    }
    static func bitString(_ value: Data, unusedBits: UInt8 = 0) -> Data { tag(0x03, Data([unusedBits]) + value) }
    static func oid(_ numbers: [UInt64]) -> Data {
        precondition(numbers.count >= 2)
        var bytes = Data()
        for number in [numbers[0] * 40 + numbers[1]] + numbers.dropFirst(2) {
            var value = number
            var encoded = [UInt8(value & 0x7f)]
            value >>= 7
            while value > 0 { encoded.insert(UInt8(value & 0x7f) | 0x80, at: 0); value >>= 7 }
            bytes.append(contentsOf: encoded)
        }
        return tag(0x06, bytes)
    }
    static func utcTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        return tag(0x17, Data(formatter.string(from: date).utf8))
    }
    static func ext(_ oidNumbers: [UInt64], critical: Bool = false, content: Data) -> Data {
        let criticalFlag = critical ? tag(0x01, Data([0xff])) : Data()
        return sequence(oid(oidNumbers), criticalFlag, tag(0x04, content))
    }
}
