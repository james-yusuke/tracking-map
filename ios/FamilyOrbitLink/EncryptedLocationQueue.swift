import CryptoKit
import Foundation

final class EncryptedLocationQueue {
    private let keychain = KeychainStore()
    private let account = "child_location_queue_key"
    private let fileURL: URL
    private var values: [LocationUpload] = []

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        fileURL = base.appendingPathComponent("location-queue.bin")
        values = load()
        removeExpired()
    }

    func append(_ location: LocationUpload) {
        removeExpired()
        values.append(location)
        values = Array(values.suffix(3_000))
        persist()
    }

    func prefix(_ count: Int) -> [LocationUpload] { Array(values.prefix(count)) }

    func remove(ids: Set<UUID>) {
        values.removeAll { ids.contains($0.id) }
        persist()
    }

    func clear() {
        values.removeAll()
        persist()
    }

    private func removeExpired() {
        let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
        values.removeAll { $0.recordedAt < cutoff }
    }

    private func load() -> [LocationUpload] {
        guard let encrypted = try? Data(contentsOf: fileURL),
              let box = try? AES.GCM.SealedBox(combined: encrypted),
              let decrypted = try? AES.GCM.open(box, using: key()) else { return [] }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([LocationUpload].self, from: decrypted)) ?? []
    }

    private func persist() {
        guard !values.isEmpty else { try? FileManager.default.removeItem(at: fileURL); return }
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(values),
              let sealed = try? AES.GCM.seal(data, using: key()).combined else { return }
        try? sealed.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func key() -> SymmetricKey {
        if let data = keychain.data(for: account), data.count == 32 { return SymmetricKey(data: data) }
        let data = Data((0..<32).map { _ in UInt8.random(in: .min ... .max) })
        try? keychain.set(data, for: account)
        return SymmetricKey(data: data)
    }
}
