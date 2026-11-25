package amorx
import amorx.playback.ArtworkQuality
import org.junit.Assert.*
import org.junit.Test
class ArtworkQualityTest {
 @Test fun thumbnailOrderAndFallback() {
  val original = "https://i.ytimg.com/vi/kJQP7kiw5Fk/mqdefault.jpg"
  val list = ArtworkQuality.candidates(original)
  assertTrue(list.first().endsWith("maxresdefault.jpg"))
  assertEquals(original, list.last()); assertEquals(4, list.size)
 }
 @Test fun musicThumbnailAndLocalOriginal() {
  assertEquals("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj", ArtworkQuality.candidates("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj").first())
  for (original in listOf("file:/music/cover.jpg", "https://example.com/a.jpg?sig=123", "https://i.ytimg.com.evil.example/vi/kJQP7kiw5Fk/mqdefault.jpg")) assertEquals(listOf(original), ArtworkQuality.candidates(original))
 }
}
