import CryptoKit
import dnssd
import Foundation

/// Announces a running wireless server on the local network (mDNS/DNS-SD,
/// "_sharpscreen._tcp") so a paired tablet finds this Mac again after its
/// address changed, at home, at the office (issue #17).
///
/// The tablet sends its pairing token in the clear when it connects, so it
/// must not connect to just any announcer. Each IPv4 address in the TXT
/// record carries a proof, HMAC-SHA256 keyed with the token over the address
/// and port: only the Mac holding the token can sign its own address, and the
/// tablet checks the address it resolved against it. The token never leaves
/// the Mac.
///
/// TXT: v=1, a=ip=proof,ip=proof,... (proof: first 12 bytes, base64url).
final class HostAdvertiser {
    static let serviceType = "_sharpscreen._tcp"
    static let maxAddresses = 6

    private let token: Data
    private let port: UInt16
    private var ref: DNSServiceRef?
    private var advertised: [String] = []

    init(token: Data, port: UInt16) {
        self.token = token
        self.port = port
    }

    deinit { stop() }

    /// The proof for one address (also computed by the tablet).
    static func proof(token: Data, ip: String, port: UInt16) -> String {
        let message = Data("SharpScreen host v1|\(ip)|\(port)".utf8)
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: token))
        return Data(mac.prefix(12)).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// TXT record bytes: length-prefixed "key=value" strings.
    static func txtRecord(token: Data, port: UInt16, addresses: [String]) -> Data {
        let list = addresses.prefix(maxAddresses).map { "\($0)=\(proof(token: token, ip: $0, port: port))" }
        var txt = Data()
        for entry in ["v=1", "a=" + list.joined(separator: ",")] {
            let bytes = Data(entry.utf8)
            txt.append(UInt8(min(bytes.count, 255)))
            txt.append(bytes.prefix(255))
        }
        return txt
    }

    func start(name: String) {
        stop()
        advertised = LANAddressResolver.allIPv4()
        let txt = Self.txtRecord(token: token, port: port, addresses: advertised)
        var newRef: DNSServiceRef?
        let err = txt.withUnsafeBytes { raw in
            DNSServiceRegister(&newRef, 0, 0, name, Self.serviceType, nil, nil,
                               port.bigEndian, UInt16(raw.count), raw.baseAddress, nil, nil)
        }
        guard err == kDNSServiceErr_NoError, let newRef else {
            debugLog("Wireless: could not announce on the local network (\(err))")
            return
        }
        ref = newRef
        debugLog("Wireless: announced as \"\(name)\" (\(Self.serviceType), port \(port), \(advertised.count) addresses)")
    }

    /// Re-sign when the Mac's addresses changed (moved to another network).
    func refreshIfAddressesChanged() {
        guard let ref else { return }
        let now = LANAddressResolver.allIPv4()
        guard now != advertised else { return }
        advertised = now
        let txt = Self.txtRecord(token: token, port: port, addresses: now)
        _ = txt.withUnsafeBytes { raw in
            DNSServiceUpdateRecord(ref, nil, 0, UInt16(raw.count), raw.baseAddress, 0)
        }
        debugLog("Wireless: addresses changed, announcement updated (\(now.count) addresses)")
    }

    func stop() {
        if let ref { DNSServiceRefDeallocate(ref) }
        ref = nil
    }
}
