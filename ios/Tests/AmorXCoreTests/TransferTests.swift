import XCTest
@testable import AmorXCore

final class TransferTests: XCTestCase {
    func testRangesRejectWrongOffsetsTruncationAndChangedLength() {
        XCTAssertEqual(AudioByteRange.request(offset: 0), "bytes=0-2097151")
        XCTAssertEqual(AudioByteRange.request(offset: 2097152, total: 3000000), "bytes=2097152-2999999")
        let first = AudioByteRange("bytes 0-2097151/3000000", offset: 0, received: 2097152)
        XCTAssertEqual(first?.count, 2097152); XCTAssertFalse(first!.complete)
        XCTAssertTrue(AudioByteRange("bytes 2097152-2999999/3000000", offset: 2097152, received: 902848)!.complete)
        XCTAssertNil(AudioByteRange("bytes 0-100/200", offset: 20, received: 101))
        XCTAssertNil(AudioByteRange("bytes 0-100/200", offset: 0, received: 100))
        XCTAssertNil(AudioByteRange("bytes 0-100/100", offset: 0, received: 101))
        XCTAssertNil(AudioByteRange("bytes 0-100/*", offset: 0, received: 101))
    }
    func testSignedLinkExpiryAndLimit() {
        let now = Date(timeIntervalSince1970: 1000)
        XCTAssertEqual(AudioLinkCache.expiration(URL(string: "https://example.com/audio?expire=1100")!, now: now), Date(timeIntervalSince1970: 1040))
        XCTAssertEqual(AudioLinkCache.expiration(URL(string: "https://example.com/audio?expire=100000")!, now: now), now.addingTimeInterval(1200))
    }
    func testPlaybackAndDownloadShareExtractionAndFailuresCanRetry() async throws {
        actor Calls { var count = 0; func call() -> Int { count += 1; return count } }
        let calls = Calls(), cache = AudioLinkCache()
        let url = URL(string: "https://example.com/audio")!
        let load: @Sendable () async throws -> URL = { _ = await calls.call(); try await Task.sleep(nanoseconds: 30_000_000); return url }
        async let a = cache.link(for: "song", load: load)
        async let b = cache.link(for: "song", load: load)
        let pair = try await (a, b); XCTAssertEqual(pair.0, pair.1)
        let afterBoth = await calls.count; XCTAssertEqual(afterBoth, 1)
        _ = try await cache.link(for: "song", load: load)
        let afterCached = await calls.count; XCTAssertEqual(afterCached, 1)
        await cache.invalidate("song"); _ = try await cache.link(for: "song", load: load)
        let afterInvalidation = await calls.count; XCTAssertEqual(afterInvalidation, 2)
        struct Failed: Error {}
        do { _ = try await cache.link(for: "retry") { throw Failed() }; XCTFail("Expected extraction failure") } catch is Failed { }
        _ = try await cache.link(for: "retry", load: load)
    }
    func testCompactPhonesRetainSpaceForAllPlayerControls() {
        for (w, h) in [(320.0,548.0), (568.0,300.0), (375.0,647.0), (393.0,759.0), (430.0,820.0), (768.0,950.0)] {
            let layout = PhoneLayout(width: w, height: h)
            XCTAssertLessThanOrEqual(layout.artwork + 2 * layout.inset, w)
            let playSize: Double = layout.compact ? 64 : 82
            let controlsWidth: Double = 176 + playSize + 16 + 2 * layout.inset
            XCTAssertLessThanOrEqual(controlsWidth, w)
        }
        XCTAssertTrue(PhoneLayout(width: 320, height: 548).compact)
        XCTAssertFalse(PhoneLayout(width: 320, height: 548).showNavigationLabel)
    }
}
