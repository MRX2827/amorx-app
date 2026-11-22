import SwiftUI
import AVKit

extension Color {
    static let amorx = Color(red: 0.22, green: 0.43, blue: 1)
    static let surface = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.12, green: 0.13, blue: 0.17, alpha: 1) : UIColor(red: 0.96, green: 0.97, blue: 0.99, alpha: 1) })
}
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.91 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.65), value: configuration.isPressed)
    }
}
struct RoundButton: View {
    let icon: String
    var active = false
    var large = false
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            MXIcon(icon, size: large ? 32 : 25)
                .foregroundColor(large ? .white : active ? .amorx : .primary)
                .frame(width: large ? 66 : 44, height: large ? 66 : 44)
                .background(large ? Color.amorx : active ? Color.amorx.opacity(0.16) : Color.primary.opacity(0.06))
                .clipShape(Circle())
        }.buttonStyle(PressStyle()).animation(.easeInOut(duration: 0.2), value: active)
    }
}
struct Artwork: View {
    let source: String?
    var size: CGFloat = 52
    var radius: CGFloat = 12
    var expressive = false
    var body: some View {
        ZStack {
            LinearGradient(colors: [.amorx.opacity(0.6), .purple.opacity(0.3)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Image(systemName: "music.note").foregroundColor(.white.opacity(0.8))
            if let source = source, let data = Data(base64Encoded: source), let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFill()
            } else if let source = source, let url = URL(string: source) {
                AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Color.clear }
            }
        }.frame(width: size, height: size).clipShape(ExpressiveArtworkShape(expressive: expressive, radius: radius))
    }
}
struct WaveParticles: View {
    @Environment(\.amorxLayout) private var layout
    var playing = true
    var height: CGFloat = 180
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        TimelineView(.animation(minimumInterval: layout.compact ? 1 / 20 : 1 / 30, paused: !playing || reduceMotion)) { timeline in
            Canvas { context, size in
                let time = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate * 0.6
                for row in 0..<(layout.compact ? 8 : 16) {
                    let columns = layout.compact ? 48 : 72
                    for index in 0..<columns {
                        let x = Double(index) / Double(columns - 1)
                        let y = sin(x * 9 + time + Double(row) * 0.15) * 0.19 + cos(x * 17 - time * 0.7) * 0.08 + Double(row) * 0.009
                        let point = CGPoint(x: size.width * x, y: size.height * (0.4 + y))
                        context.fill(Path(ellipseIn: CGRect(x: point.x, y: point.y, width: 1.7, height: 1.7)), with: .color(.amorx.opacity(0.22 + Double(row % 4) * 0.13)))
                    }
                }
            }
        }.frame(height: height).clipped().accessibilityHidden(true)
    }
}
struct MusicRow: View {
    let track: Track
    let tracks: [Track]
    @EnvironmentObject var player: Playback
    @EnvironmentObject var library: Library
    @EnvironmentObject var cloud: Cloud
    @ObservedObject var downloads = Downloads.shared
    var body: some View {
        HStack(spacing: 12) {
            Button { player.play(track, in: tracks) } label: {
                HStack(spacing: 12) {
                    Artwork(source: track.artwork, expressive: true)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text(track.artist).font(.caption).foregroundColor(.secondary).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            if player.current?.id == track.id { Image(systemName: player.playing ? "waveform" : "pause.fill").foregroundColor(.amorx) }
            Menu {
                Button(library.favorites.contains(track) ? "Убрать из любимого" : "В любимые") { library.like(track) }
                Button("В плейлист AmorX") { library.add([track]) }
                if downloads.local(track) != nil { Button("Удалить скачанный файл", role: .destructive) { downloads.remove(track) } }
                else { Button("Скачать") { Task { await downloads.download(track) } } }
                Button("Поделиться треком") { UIPasteboard.general.string = "https://www.youtube.com/watch?v=\(track.id)" }
                Button("Опубликовать историю") { NotificationCenter.default.post(name: .songStory, object: track) }
            } label: { Image(systemName: "ellipsis").frame(width: 32, height: 44) }
            if let progress = downloads.pending[track.id] { ProgressView(value: progress).frame(width: 28) }
            else if downloads.local(track) != nil { Image(systemName: "checkmark.circle.fill").foregroundColor(.amorx).font(.caption) }
        }.padding(.vertical, 5)
    }
}
extension Notification.Name { static let songStory = Notification.Name("amorx.songStory") }

struct MiniPlayerBar: View {
    @EnvironmentObject var player: Playback
    @Environment(\.amorxLayout) private var layout
    var expand: () -> Void
    var body: some View {
        if let track = player.current {
            HStack(spacing: layout.compact ? 4 : 10) {
                Button(action: expand) {
                    HStack { Artwork(source: track.artwork, size: layout.compact ? 38 : 52, expressive: true); VStack(alignment: .leading) { Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1); Text(player.loading ? "Загрузка…" : track.artist).font(.caption).foregroundColor(.secondary).lineLimit(1) }.frame(maxWidth: .infinity, alignment: .leading) }
                }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading).layoutPriority(1)
                RoundButton(icon: "shuffle", active: player.shuffle) { player.toggleShuffle() }
                MorphPlayButton(playing: player.playing, loading: player.loading, size: layout.compact ? 46 : 54) { player.toggle() }
                RoundButton(icon: "forward.end.fill") { player.next() }
            }.padding(layout.compact ? 6 : 10).background(Color.surface).clipShape(Capsule()).overlay(alignment: .bottom) {
                GeometryReader { geometry in
                    Capsule().fill(Color.amorx).frame(width: geometry.size.width * min(1, player.position / max(1, player.duration)), height: 2)
                }.frame(height: 2).padding(.horizontal, 18)
            }.padding(.horizontal, 10).padding(.vertical, 5).layoutProbe("mini.player")
        }
    }
}
struct FullPlayer: View {
    @EnvironmentObject var player: Playback
    @EnvironmentObject var library: Library
    @Environment(\.dismiss) private var dismiss
    @State private var showQueue = false
    var body: some View {
        GeometryReader { geometry in
            let layout = PhoneLayout(width: geometry.size.width, height: geometry.size.height)
            let landscape = layout.width > layout.height && layout.short
            ZStack {
                Color(UIColor.systemBackground).ignoresSafeArea()
                if let art = player.current?.artwork, let url = URL(string: art) {
                    AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Color.amorx.opacity(0.3) }
                        .frame(width: geometry.size.width, height: geometry.size.height).blur(radius: 60).opacity(0.25).clipped().allowsHitTesting(false)
                }
                ScrollView {
                    VStack(spacing: landscape ? 8 : layout.compact ? 10 : 20) {
                        HStack {
                            RoundButton(icon: "chevron.down") { dismiss() }
                            Spacer(minLength: 4)
                            Text("СЕЙЧАС ИГРАЕТ").font(.caption.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
                            Spacer(minLength: 4)
                            RoundButton(icon: "list.bullet") { showQueue = true }
                        }
                        if let track = player.current {
                            if landscape {
                                HStack(spacing: 16) {
                                    cover(track, size: min(180, layout.height - 100))
                                    VStack(spacing: 4) { information(track, compact: true, lines: 1); progress; controls(compact: true); volume }
                                }
                            } else {
                                cover(track, size: layout.artwork)
                                information(track, compact: layout.compact, lines: 2)
                                progress; controls(compact: layout.compact); volume
                            }
                        }
                    }.padding(layout.inset).frame(maxWidth: landscape ? 900 : 600).frame(maxWidth: .infinity)
                }
            }.frame(width: geometry.size.width, height: geometry.size.height).clipped()
        }.sheet(isPresented: $showQueue) { NavigationView { List { ForEach(player.queue) { track in MusicRow(track: track, tracks: player.queue) } }.navigationTitle("Очередь").toolbar { Button("Готово") { showQueue = false } } } }
    }
    private func cover(_ track: Track, size: CGFloat) -> some View {
        Artwork(source: track.artwork, size: size, radius: 28, expressive: true)
            .scaleEffect(player.playing ? 1 : 0.94).animation(.spring(response: 0.45, dampingFraction: 0.8), value: player.playing)
    }
    private func information(_ track: Track, compact: Bool, lines: Int) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(track.title).font(compact ? .headline : .title2.bold()).lineLimit(lines)
                Text(track.artist).font(.subheadline).foregroundColor(.secondary).lineLimit(1)
            }.frame(maxWidth: .infinity, alignment: .leading)
            RoundButton(icon: library.favorites.contains(where: { $0.id == track.id }) ? "heart.fill" : "heart", active: library.favorites.contains(where: { $0.id == track.id })) { library.like(track) }
        }
    }
    private var progress: some View {
        VStack(spacing: 0) {
            Slider(value: Binding(get: { min(max(0, player.position), max(1, player.duration)) }, set: { player.seek($0) }), in: 0...max(1, player.duration)).tint(.amorx)
            HStack { Text(timeLabel(player.position)); Spacer(); Text(player.loading ? "Загрузка…" : timeLabel(player.duration)) }.font(.caption.monospacedDigit()).foregroundColor(.secondary)
            if let error = player.issue { Text(error).font(.caption).foregroundColor(.red) }
        }
    }
    private func controls(compact: Bool) -> some View {
        HStack(spacing: 4) {
            RoundButton(icon: "shuffle", active: player.shuffle) { player.toggleShuffle() }
            Spacer(minLength: 0)
            RoundButton(icon: "backward.end.fill") { player.previous() }
            MorphPlayButton(playing: player.playing, loading: player.loading, size: compact ? 64 : 82) { player.toggle() }
            RoundButton(icon: "forward.end.fill") { player.next() }
            Spacer(minLength: 0)
            RoundButton(icon: "repeat.1", active: player.repeatOne) { player.repeatOne.toggle() }
        }.layoutProbe("player.controls")
    }
    private var volume: some View {
        HStack { Image(systemName: "speaker.fill"); Slider(value: $player.volume, in: 0...1); Image(systemName: "speaker.wave.3.fill") }.foregroundColor(.secondary)
    }
}

struct MXIcon: View {
    let name: String
    var size: CGFloat = 24
    init(_ name: String, size: CGFloat = 24) { self.name = name; self.size = size }
    private var materialName: String? {
        ["house.fill": "home", "square.stack": "library_music", "globe": "public", "arrow.down.circle.fill": "download", "person.crop.circle": "person", "shuffle": "shuffle", "repeat.1": "repeat_one", "repeat": "repeat", "play.fill": "play_arrow", "pause.fill": "pause", "forward.end.fill": "skip_next", "backward.end.fill": "skip_previous", "chevron.down": "keyboard_arrow_down", "list.bullet": "queue_music", "gearshape": "settings", "magnifyingglass": "search"][name]
    }
    var body: some View {
        Group {
            if let materialName = materialName { Text(materialName).font(.custom("MaterialSymbolsOutlined-Regular", size: size)).fixedSize() }
            else { Image(systemName: name).font(.system(size: size, weight: .medium)) }
        }.accessibilityHidden(true)
    }
}
// Cubic pairs are exported from the same AndroidX Morph used in the Android player.
struct AndroidMorphShape: Shape {
    var progress: CGFloat
    var animatableData: CGFloat { get { progress } set { progress = newValue } }
    func path(in rect: CGRect) -> Path {
        let t = min(1, max(0, progress))
        let curves = zip(AndroidCurves.circle, AndroidCurves.cookie).map { a, b in zip(a, b).map { $0 + ($1 - $0) * t } }
        return curvesPath(curves, in: rect)
    }
}
func curvesPath(_ curves: [[CGFloat]], in rect: CGRect) -> Path {
    var path = Path()
    func point(_ c: [CGFloat], _ index: Int) -> CGPoint { CGPoint(x: rect.minX + c[index] * rect.width, y: rect.minY + c[index + 1] * rect.height) }
    if let first = curves.first { path.move(to: point(first, 0)) }
    for curve in curves { path.addCurve(to: point(curve, 6), control1: point(curve, 2), control2: point(curve, 4)) }
    path.closeSubpath(); return path
}
struct ExpressiveArtworkShape: Shape {
    var expressive: Bool
    var radius: CGFloat
    func path(in rect: CGRect) -> Path {
        if expressive { return curvesPath(rect.width > 100 ? AndroidCurves.bun : AndroidCurves.ghost, in: rect) }
        return RoundedRectangle(cornerRadius: radius, style: .continuous).path(in: rect)
    }
}
struct MorphPlayButton: View {
    let playing: Bool
    var loading = false
    var size: CGFloat = 72
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Button(action: action) {
            TimelineView(.animation(minimumInterval: 1 / 30, paused: !playing || reduceMotion)) { time in
                ZStack {
                    AndroidMorphShape(progress: playing && !loading ? 1 : 0)
                        .fill(Color.amorx)
                        .rotationEffect(.degrees(playing && !reduceMotion ? time.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 15) * 24 : 0))
                        .animation(.spring(response: 0.48, dampingFraction: 0.65), value: playing)
                    if loading { ProgressView().tint(.white) }
                    else { MXIcon(playing ? "pause.fill" : "play.fill", size: size * 0.43).foregroundColor(.white).id(playing).transition(.opacity.combined(with: .scale(scale: 0.7))) }
                }.frame(width: size, height: size)
            }
        }.buttonStyle(PressStyle()).accessibilityLabel(playing ? "Пауза" : "Играть")
    }
}
