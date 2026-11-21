import XCTest
@testable import AmorXCore

final class CoreTests: XCTestCase {
    func testShuffleVisitsEveryTrackBeforeRepeatingAndCrossesCycleWithoutDuplicate() {
        let ids = (0..<299).map(String.init)
        var order = ShuffleCycle(); order.reset(ids: ids, current: ids[0])
        var heard: Set<String> = [ids[0]]; var current = ids[0]
        for _ in 1..<ids.count {
            let next = order.next(ids: ids, current: current)!
            XCTAssertTrue(heard.insert(next).inserted); current = next
        }
        XCTAssertEqual(heard.count, ids.count)
        let first = order.next(ids: ids, current: current)!
        XCTAssertNotEqual(first, current)
        heard = [first]; current = first
        for _ in 1..<ids.count {
            let next = order.next(ids: ids, current: current)!
            XCTAssertTrue(heard.insert(next).inserted); current = next
        }
    }
    func testShuffleHistoryAndQueueChanges() {
        var order = ShuffleCycle(); order.reset(ids: ["a", "b", "c"], current: "a")
        let b = order.next(ids: ["a", "b", "c"], current: "a")!
        XCTAssertEqual(order.previous(ids: ["a", "b", "c"]), "a")
        XCTAssertEqual(order.next(ids: ["a", "b", "c"], current: "a"), b)
        XCTAssertEqual(order.next(ids: ["new"], current: b), "new")
        XCTAssertNil(order.next(ids: [], current: "new"))
    }
    func testRestoreMergesOnlyCanonicalAmorXPlaylistAndAllDownloads() throws {
        let a = Track(id: "abcdefghijk", title: "A", artist: "artist", duration: 123)
        let b = Track(id: "12345678901", title: "B", artist: "artist")
        let legacy: [[String: Any]] = [
            ["name": "Плейлист AmorX", "songs": [a.cloudJSON, b.cloudJSON]],
            ["name": "AmorX", "songs": [a.cloudJSON]],
            ["name": "Other playlist", "songs": [["id": "NOTSELECTED", "title": "Excluded"]]]
        ]
        let root: [String: Any] = ["amorx_online": ["downloads": [a.cloudJSON, a.cloudJSON], "amorx_playlist": ["tracks": [a.cloudJSON]]], "playlists_data": String(decoding: try JSONSerialization.data(withJSONObject: legacy), as: UTF8.self)]
        let plan = try MusicBackupPlan(root)
        XCTAssertEqual(plan.downloads.map(\.id), [a.id])
        XCTAssertEqual(plan.playlist.map(\.id), [a.id, b.id])
        XCTAssertEqual(plan.playlist[0].duration, 123)
    }
    func testBackupPreservesAndroidPreferencesPrivateProfileAndExistingSongs() throws {
        let a = Track(id: "abcdefghijk", title: "A", artist: "Artist")
        let b = Track(id: "12345678901", title: "B", artist: "Artist")
        let root: [String: Any] = ["preferences": ["theme": "dark"], "amorx_profile": ["name": "Original", "avatar": "base64"], "playlists_data": "[]", "amorx_online": ["downloads": [a.cloudJSON], "amorx_playlist": ["tracks": [a.cloudJSON], "code": "1234abcd"]]]
        let merged = MusicBackupPlan.merging(root, downloads: [b], playlist: [b, a], code: nil)
        let plan = try MusicBackupPlan(merged)
        XCTAssertEqual(plan.downloads.map(\.id), [a.id, b.id]); XCTAssertEqual(plan.playlist.map(\.id), [a.id, b.id])
        XCTAssertEqual(plan.code, "1234abcd")
        XCTAssertEqual((merged["preferences"] as? [String: String])?["theme"], "dark")
        XCTAssertEqual((merged["amorx_profile"] as? [String: String])?["name"], "Original")
    }
    func testRemoteIdentityAndUnsafeArtworkValidation() {
        let a = Track.from(["id": "online_abcdefghijk", "duration": 150000, "artwork": "file:///private/avatar.jpg"])
        XCTAssertEqual(a?.id, "abcdefghijk"); XCTAssertEqual(a?.duration, 150); XCTAssertNil(a?.artwork)
        XCTAssertNil(Track.from(["id": "../private/file"]))
        XCTAssertEqual(Track.unique([Track(id: "abcdefghijk", title: "A", artist: ""), Track(id: "online_abcdefghijk", title: "B", artist: "")]).count, 1)
    }
}
