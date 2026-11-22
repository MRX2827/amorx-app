import SwiftUI
import AVKit

struct StoryViewer: View {
    let stories: [Story]
    @State var first: Int
    @EnvironmentObject var cloud: Cloud
    @EnvironmentObject var music: Playback
    @Environment(\.dismiss) var dismiss
    @State private var progress: Double = 0
    @State private var ready = false
    @State private var viewers = false
    @State private var liked = false
    @State private var resumeMusic = false
    private let timer = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()
    private var story: Story? { stories.indices.contains(first) ? stories[first] : nil }
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let story = story {
                StoryPlayback(story: story) { ready = true }
                    .onTapGesture { advance() }
                VStack {
                    HStack(spacing: 4) { ForEach(stories.indices, id: \.self) { index in GeometryReader { geometry in Capsule().fill(Color.white.opacity(0.3)).overlay(alignment: .leading) { Capsule().fill(Color.white).frame(width: geometry.size.width * (index < first ? 1 : index == first ? min(1, progress) : 0)) } }.frame(height: 3) } }
                    HStack {
                        Artwork(source: cloud.people.first { $0.id == story.owner }?.avatar, size: 36, radius: 18)
                        Text(cloud.people.first { $0.id == story.owner }?.name ?? cloud.name).bold()
                        Spacer()
                        if story.owner == cloud.uid {
                            Menu { Button("Удалить историю", role: .destructive) { cloud.run { try await cloud.remove("stories", query: ["id": "eq.\(story.id)", "owner_id": "eq.\(story.owner)"]); try await cloud.refreshStories(); dismiss() } } } label: { Image(systemName: "ellipsis").padding(8) }
                        }
                        Button { dismiss() } label: { Image(systemName: "xmark").padding(8) }
                    }
                    Spacer()
                    Text(story.caption).frame(maxWidth: .infinity, alignment: .leading)
                    HStack {
                        if story.owner == cloud.uid { Button { viewers = true } label: { Label("Кто посмотрел", systemImage: "eye") } }
                        else { Button { cloud.run { guard let uid = cloud.uid else { return }; if liked { try await cloud.remove("story_likes", query: ["story_id": "eq.\(story.id)", "user_id": "eq.\(uid)"]) } else { try await cloud.write("story_likes", ["story_id": story.id, "user_id": uid]) }; liked.toggle() } } label: { Image(systemName: liked ? "heart.fill" : "heart").foregroundColor(liked ? .pink : .white).font(.title) } }
                        Spacer()
                        Button { advance() } label: { Image(systemName: "chevron.right") }
                    }.padding(.vertical)
                }.foregroundColor(.white).padding(20)
                    .gesture(DragGesture().onEnded { value in if value.translation.height < -65 && story.owner == cloud.uid { viewers = true }; if value.translation.width < -70 { advance() }; if value.translation.width > 70 && first > 0 { first -= 1 } })
            }
        }.onAppear { resumeMusic = music.playing; music.setPlaying(false) }
            .onDisappear { if resumeMusic { music.setPlaying(true) } }
            .onReceive(timer) { _ in if ready && !viewers, let story = story { progress += 0.05 / max(5, story.track == nil ? 30 : story.end - story.start); if progress >= 1 { advance() } } }
            .task(id: story?.id) {
                ready = false; progress = 0; liked = false
                guard let story = story, let uid = cloud.uid else { return }
                cloud.run {
                    if story.owner != uid { try await cloud.write("story_views", ["story_id": story.id, "viewer_id": uid], query: ["on_conflict": "story_id,viewer_id"], prefer: "resolution=ignore-duplicates,return=minimal") }
                    liked = !(try await cloud.rows("story_likes", query: ["story_id": "eq.\(story.id)", "user_id": "eq.\(uid)", "limit": "1"])).isEmpty
                }
            }
            .sheet(isPresented: $viewers) { if let story = story { ViewersScreen(story: story) } }
    }
    private func advance() { if first + 1 < stories.count { first += 1 } else { dismiss() } }
}
struct StoryPlayback: View {
    @Environment(\.amorxLayout) private var layout
    let story: Story
    var onReady: () -> Void
    @EnvironmentObject var cloud: Cloud
    @State private var player: AVPlayer?
    @State private var failed: String?
    var body: some View {
        ZStack {
            if let track = story.track {
                VStack(spacing: 12) { Artwork(source: track.artwork, size: min(280, layout.width - 40, layout.height * 0.38), radius: 32); Text(track.title).font(layout.compact ? .headline : .title.bold()).lineLimit(2).multilineTextAlignment(.center); Text(track.artist).foregroundColor(.gray).lineLimit(1); WaveParticles(height: layout.compact ? 60 : 150) }.padding().foregroundColor(.white)
            } else if let player = player { VideoPlayer(player: player).ignoresSafeArea() }
            if player == nil && failed == nil { ProgressView().tint(.white) }
            if let failed = failed { Text(failed).foregroundColor(.white).padding() }
        }.task(id: story.id) {
            player?.pause(); player = nil; failed = nil
            do {
                let url: URL
                if let audio = story.audio { url = try await cloud.mediaURL(bucket: "story-audio", path: audio) }
                else if let video = story.video { url = try await cloud.mediaURL(bucket: "stories", path: video, publicFile: true) }
                else if let track = story.track { url = try await MusicSearch.stream(track) }
                else { throw AppIssue("История недоступна") }
                let value = AVPlayer(url: url); player = value
                for _ in 0..<100 {
                    try Task.checkCancellation()
                    if value.currentItem?.status == .failed { throw value.currentItem?.error ?? AppIssue("Медиа не открывается") }
                    if value.currentItem?.status == .readyToPlay { break }
                    try await Task.sleep(nanoseconds: 100_000_000)
                }
                guard value.currentItem?.status == .readyToPlay else { throw AppIssue("История долго загружается. Попробуйте позже") }
                if story.audio == nil, story.track != nil { await value.seek(to: CMTime(seconds: story.start, preferredTimescale: 600)) }
                value.play(); onReady()
            } catch { if !Task.isCancelled { failed = error.localizedDescription } }
        }.onDisappear { player?.pause() }
    }
}
struct ViewersScreen: View {
    let story: Story
    @EnvironmentObject var cloud: Cloud
    @Environment(\.dismiss) var dismiss
    @State private var people: [Person] = []
    var body: some View {
        NavigationView {
            List { if people.isEmpty { Text("Пока нет просмотров").foregroundColor(.secondary) }; ForEach(people) { person in HStack { Artwork(source: person.avatar, size: 42, radius: 21); Text(person.name) } } }
                .navigationTitle("Посмотрели · \(people.count)").toolbar { Button("Готово") { dismiss() } }
                .task { cloud.run { let views = try await cloud.rows("story_views", query: ["select": "viewer_id,viewed_at", "story_id": "eq.\(story.id)", "order": "viewed_at.desc", "limit": "500"]); people = try await cloud.profiles(ids: views.compactMap { $0["viewer_id"] as? String }) } }
        }
    }
}
struct SongStoryScreen: View {
    @Environment(\.amorxLayout) private var layout
    let track: Track
    @EnvironmentObject var cloud: Cloud
    @EnvironmentObject var music: Playback
    @Environment(\.dismiss) var dismiss
    @State private var start: Double = 0
    @State private var length: Double = 15
    @State private var caption = ""
    @State private var busy = false
    @State private var clip: URL?
    @State private var preview: AVPlayer?
    @State private var wasPlaying = false
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    Artwork(source: track.artwork, size: min(240, layout.width - 2 * layout.inset), radius: 32)
                    Text(track.title).font(.title2.bold()).multilineTextAlignment(.center); Text(track.artist).foregroundColor(.secondary)
                    Text("Фрагмент: \(timeLabel(start)) — \(timeLabel(start + length))")
                    Slider(value: $start, in: 0...max(1, track.duration - length)).onChange(of: start) { _ in reset() }
                    Picker("Длительность", selection: $length) { Text("15 секунд").tag(15.0); Text("30 секунд").tag(30.0) }.pickerStyle(.segmented).onChange(of: length) { _ in reset() }
                    TextField("Подпись", text: $caption).textFieldStyle(.roundedBorder)
                    AdaptiveActions {
                        Button(busy ? "Готовим…" : "Послушать фрагмент") { prepare(publish: false) }.buttonStyle(.bordered)
                        Button("В историю") { prepare(publish: true) }.buttonStyle(.borderedProminent)
                    }.disabled(busy)
                    Text("В историю загружается сам аудиофрагмент. Друзьям не нужно ждать YouTube.").font(.caption).foregroundColor(.secondary)
                }.padding(layout.inset)
            }.navigationTitle("Песня в историю").toolbar { Button("Закрыть") { dismiss() }.disabled(busy) }
        }.onAppear { wasPlaying = music.playing; music.setPlaying(false) }.onDisappear { preview?.pause(); if let clip = clip { try? FileManager.default.removeItem(at: clip) }; if wasPlaying { music.setPlaying(true) } }
    }
    private func reset() { preview?.pause(); if let clip = clip { try? FileManager.default.removeItem(at: clip) }; clip = nil }
    private func prepare(publish: Bool) {
        guard !busy else { return }; busy = true; preview?.pause()
        Task {
            do {
                if !publish {
                    let url: URL
                    if let local = Downloads.shared.local(track) { url = local } else { url = try await MusicSearch.stream(track) }
                    let item = AVPlayerItem(url: url); item.preferredForwardBufferDuration = 2
                    item.forwardPlaybackEndTime = CMTime(seconds: start + length, preferredTimescale: 600)
                    let value = AVPlayer(playerItem: item); value.automaticallyWaitsToMinimizeStalling = true; preview = value
                    for _ in 0..<100 {
                        try Task.checkCancellation()
                        if item.status == .readyToPlay { break }
                        if item.status == .failed { throw item.error ?? AppIssue("Не удалось открыть фрагмент") }
                        try await Task.sleep(nanoseconds: 100_000_000)
                    }
                    guard item.status == .readyToPlay else { throw AppIssue("Фрагмент долго загружается. Повторите попытку") }
                    await value.seek(to: CMTime(seconds: start, preferredTimescale: 600)); value.playImmediately(atRate: 1); busy = false; return
                }
                let ready: URL
                if let clip = clip { ready = clip }
                else {
                    let source: URL
                    var temporary: URL?
                    if let local = Downloads.shared.local(track) { source = local }
                    else { let remote = try await MusicSearch.stream(track); let (file, response) = try await URLSession.shared.download(from: remote); guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AppIssue("Не удалось загрузить песню") }; source = file; temporary = file }
                    defer { if let temporary = temporary { try? FileManager.default.removeItem(at: temporary) } }
                    ready = try await MediaTools.audioClip(source, start: start, seconds: length); clip = ready
                }
                if publish { try await cloud.postSongStory(track: track, file: ready, start: start, end: start + length, caption: caption); try await cloud.refreshStories(); dismiss() }
                else { preview = AVPlayer(url: ready); preview?.play() }
            } catch { cloud.issue = error.localizedDescription }
            busy = false
        }
    }
}
