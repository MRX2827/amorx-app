import SwiftUI
import UniformTypeIdentifiers
import AVKit

struct RootScreen: View {
    @EnvironmentObject var cloud: Cloud
    @EnvironmentObject var player: Playback
    @EnvironmentObject var library: Library
    @ObservedObject private var downloads = Downloads.shared
    @Environment(\.horizontalSizeClass) private var width
    @State private var page = 0
    @State private var fullPlayer = false
    @State private var restore: MusicBackupPlan?
    @State private var songStory: Track?
    var body: some View {
        Group {
            if cloud.session == nil { LoginScreen() }
            else {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        if width == .regular {
                            VStack(spacing: 20) { Text("AmorX").font(.title.bold()); navButtons; Spacer() }.padding().frame(width: 140)
                        }
                        NavigationView {
                            Group {
                                switch page {
                                case 0: HomeScreen()
                                case 1: LibraryScreen(initialSection: 1)
                                case 2: OnlineScreen()
                                case 3: LibraryScreen(initialSection: 0)
                                default: AccountScreen(restore: loadRestore)
                                }
                            }.navigationBarHidden(true)
                        }.navigationViewStyle(.stack)
                    }
                    MiniPlayerBar { withAnimation(.spring()) { fullPlayer = true } }
                    if width != .regular { bottomNavigation }
                }
            }
        }
        .task(id: cloud.uid) {
            fullPlayer = false; restore = nil; songStory = nil; page = 0
            player.clear(); library.changeUser(cloud.uid)
            guard cloud.uid != nil else { return }
            do {
                try await cloud.loadProfile()
                try Task.checkCancellation(); library.favorites = Track.unique(library.favorites + cloud.favoriteSongs); library.save()
                if let payload = try await cloud.backupPayload() {
                    try Task.checkCancellation()
                    let plan = try MusicBackupPlan(payload); library.add(plan.playlist)
                    if !plan.downloads.isEmpty || !plan.playlist.isEmpty { restore = plan }
                }
            } catch { if !Task.isCancelled { cloud.issue = error.localizedDescription } }
        }
        .fullScreenCover(isPresented: $fullPlayer) { FullPlayer() }
        .sheet(item: $restore) { RestoreScreen(plan: $0) }
        .sheet(item: $songStory) { SongStoryScreen(track: $0) }
        .onReceive(NotificationCenter.default.publisher(for: .songStory)) { note in
            guard let track = note.object as? Track else { return }
            if fullPlayer { fullPlayer = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { songStory = track } }
            else { songStory = track }
        }
        .onReceive(downloads.$issue) { issue in if let issue = issue { cloud.issue = issue; downloads.issue = nil } }
        .onReceive(Downloads.shared.$issue) { error in
            if let error = error { cloud.issue = error; Downloads.shared.issue = nil }
        }
        .onOpenURL { url in cloud.run { library.add(try await cloud.importPlaylist(url.absoluteString)) } }
        .alert("AmorX", isPresented: Binding(get: { cloud.issue != nil }, set: { if !$0 { cloud.issue = nil } })) {
            Button("Понятно", role: .cancel) { cloud.issue = nil }
        } message: { Text(cloud.issue ?? "") }
    }
    private var navButtons: some View {
        ForEach(Array([("house.fill", "Главное"), ("square.stack", "Библиотека"), ("globe", "Онлайн"), ("arrow.down.circle.fill", "Скачанное"), ("person.crop.circle", "Профиль")].enumerated()), id: \.offset) { index, item in
            Button { withAnimation(.easeInOut(duration: 0.2)) { page = index } } label: {
                VStack(spacing: 4) { Image(systemName: item.0).font(.system(size: 20)); Text(item.1).font(.system(size: 10, weight: .semibold)) }
                    .foregroundColor(page == index ? .amorx : .secondary).frame(maxWidth: .infinity).padding(.vertical, 5)
            }.buttonStyle(PressScale())
        }
    }
    private var bottomNavigation: some View {
        HStack(spacing: 12) {
            HStack(spacing: 3) {
                ForEach(0..<4) { index in
                    Button { withAnimation(.spring(response: 0.4, dampingFraction: 0.78)) { page = index } } label: {
                        HStack(spacing: 7) {
                            MXIcon(["house.fill", "square.stack", "globe", "arrow.down.circle.fill"][index], size: 25)
                            if page == index { Text(["Главное", "Библиотека", "Онлайн", "Скачанное"][index]).font(.custom("Roboto-Medium", size: 12)).lineLimit(1).transition(.opacity.combined(with: .scale(scale: 0.8))) }
                        }.foregroundColor(page == index ? .amorx : .secondary).padding(.horizontal, page == index ? 13 : 8).frame(maxWidth: .infinity, minHeight: 50)
                            .background(page == index ? Color.amorx.opacity(0.17) : .clear).clipShape(Capsule())
                    }.buttonStyle(PressStyle()).accessibilityLabel(["Главное", "Библиотека", "Онлайн", "Скачанное"][index])
                }
            }.padding(6).background(Color.surface).clipShape(Capsule())
            Button { withAnimation(.spring()) { page = 4 } } label: {
                MXIcon("person.crop.circle", size: 27).foregroundColor(.white).frame(width: 58, height: 58).background(Color.amorx).clipShape(Circle())
            }.buttonStyle(PressStyle()).accessibilityLabel("Профиль")
        }.padding(.horizontal, 12).padding(.top, 4).padding(.bottom, 6)
    }
    private func loadRestore() { cloud.run { if let data = try await cloud.backupPayload() { restore = try MusicBackupPlan(data) } else { cloud.issue = "В облаке пока нет копии" } } }
}
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label.scaleEffect(configuration.isPressed ? 0.94 : 1).animation(.spring(response: 0.25, dampingFraction: 0.65), value: configuration.isPressed) }
}

struct LoginScreen: View {
    @EnvironmentObject var cloud: Cloud
    @State private var email = ""
    @State private var password = ""
    @State private var code = ""
    @State private var register = false
    @State private var verify = false
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Text("AmorX").font(.system(size: 44, weight: .bold, design: .rounded)).foregroundColor(.amorx)
                WaveParticles().frame(height: 160)
                Text(verify ? "Подтвердите почту" : register ? "Ваша музыка.\nВаши люди." : "С возвращением").font(.largeTitle.bold())
                Text("Музыка, истории и общение — в одном месте.").foregroundColor(.secondary)
                TextField("Почта", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabledCompat().padding().background(Color.primary.opacity(0.05)).cornerRadius(16)
                if verify { TextField("Код из письма", text: $code).keyboardType(.numberPad).padding().background(Color.primary.opacity(0.05)).cornerRadius(16) }
                else { SecureField("Пароль", text: $password).padding().background(Color.primary.opacity(0.05)).cornerRadius(16) }
                if let error = error { Text(error).font(.caption).foregroundColor(.red) }
                Button {
                    busy = true; error = nil
                    Task {
                        do {
                            let done = try await cloud.authenticate(email: email.trimmingCharacters(in: .whitespaces), password: password, register: register, code: verify ? code : nil)
                            if !done { verify = true }
                        } catch { self.error = error.localizedDescription }
                        busy = false
                    }
                } label: { HStack { if busy { ProgressView().tint(.white) }; Text(verify ? "Подтвердить" : register ? "Создать аккаунт" : "Войти").bold() }.frame(maxWidth: .infinity).padding() }
                    .buttonStyle(.borderedProminent).disabled(busy || email.isEmpty || (verify ? code.count < 6 : password.count < 6))
                if !verify { Button(register ? "Уже есть аккаунт? Войти" : "Создать аккаунт") { register.toggle() } }
            }.padding(28).frame(maxWidth: 500).frame(maxWidth: .infinity)
        }
    }
}
extension View { func autocorrectionDisabledCompat() -> some View { self.disableAutocorrection(true) } }

struct SearchScreen: View {
    @EnvironmentObject var cloud: Cloud
    @State private var query = ""
    @State private var results: [Track] = []
    @State private var busy = false
    @State private var request = UUID()
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Найдите свою музыку").font(.largeTitle.bold()).padding(.top)
            HStack { Image(systemName: "magnifyingglass"); TextField("Песня или исполнитель", text: $query).submitLabel(.search).onSubmit(search); if busy { ProgressView() }; Button("Найти", action: search) }
                .padding().background(Color.primary.opacity(0.05)).cornerRadius(18)
            ScrollView { LazyVStack(spacing: 8) { ForEach(results) { MusicRow(track: $0, tracks: results) } }.padding(.vertical, 12) }
        }.padding(.horizontal)
    }
    private func search() {
        let text = query.trimmingCharacters(in: .whitespaces); guard !text.isEmpty else { return }
        let id = UUID(); request = id; busy = true
        Task {
            do { let tracks = try await MusicSearch.search(text); if request == id { results = tracks } }
            catch { if request == id { cloud.issue = error.localizedDescription } }
            if request == id { busy = false }
        }
    }
}

struct LibraryScreen: View {
    @EnvironmentObject var player: Playback
    @EnvironmentObject var library: Library
    @EnvironmentObject var cloud: Cloud
    @ObservedObject var downloads = Downloads.shared
    @State private var section: Int
    init(initialSection: Int = 0) { _section = State(initialValue: initialSection) }
    @State private var query = ""
    @State private var importFile = false
    @State private var importLink = false
    @State private var link = ""
    @State private var shareURL: URL?
    @State private var showShare = false
    @State private var busy = false
    private var songs: [Track] { (section == 0 ? downloads.tracks : section == 1 ? library.playlist : library.favorites).filter { query.isEmpty || ($0.title + " " + $0.artist).localizedCaseInsensitiveContains(query) } }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Text(section == 1 ? "Плейлист AmorX" : section == 2 ? "Любимое" : "Скачанное").font(.largeTitle.bold()); Spacer(); Menu {
                Button("Добавить все скачанные в плейлист") { library.add(downloads.tracks.filter { !$0.id.hasPrefix("local_") }) }
                Button("Открыть аудиофайл") { importFile = true }
                Button("Импортировать плейлист по ссылке") { importLink = true }
                Button("Поделиться плейлистом", action: share)
                Button("Сохранить копию для Android") { cloud.run { try await cloud.saveBackup(downloads: downloads.tracks, playlist: library.playlist, code: library.code); cloud.issue = "Копия музыки сохранена в аккаунте" } }
            } label: { Image(systemName: "ellipsis.circle").font(.title2) } }.padding(.top)
            Picker("Раздел", selection: $section) { Text("Скачанное").tag(0); Text("AmorX").tag(1); Text("Любимое").tag(2) }.pickerStyle(.segmented)
            HStack {
                Button("Играть") { if let track = songs.first { player.play(track, in: songs) } }.buttonStyle(.borderedProminent)
                Button { player.playShuffled(songs) } label: { Label("Вперемешку", systemImage: "shuffle") }.buttonStyle(.bordered)
                if section == 1 { Button { Task { await downloads.downloadAll(library.playlist) } } label: { Image(systemName: "arrow.down.circle") } }
                if busy { ProgressView() }
            }.disabled(songs.isEmpty || busy)
            if !downloads.pending.isEmpty { ProgressView("Загружается: \(downloads.pending.count)") }
            HStack { MXIcon("magnifyingglass"); TextField("Название или исполнитель", text: $query) }.padding(14).background(Color.surface).clipShape(Capsule())
            ScrollView { LazyVStack(spacing: 8) {
                if songs.isEmpty { Text(section == 0 ? "Скачивайте музыку из поиска для прослушивания без интернета." : "Добавьте песни через меню трека или восстановите их из облака.").foregroundColor(.secondary) }
                ForEach(songs) { track in MusicRow(track: track, tracks: songs).contextMenu { if section == 1 { Button("Убрать из плейлиста", role: .destructive) { library.playlist.removeAll { $0.id == track.id }; library.save() } } } }
            }.padding(.vertical, 10) }
        }.padding(.horizontal)
        .alert("Ссылка на плейлист", isPresented: $importLink) {
            // iOS 15 alerts cannot contain text fields. A dedicated import sheet follows below.
            Button("Вставить из буфера") { cloud.run { library.add(try await cloud.importPlaylist(UIPasteboard.general.string ?? "")) } }
            Button("Отмена", role: .cancel) {}
        } message: { Text("Скопируйте ссылку AmorX, затем нажмите «Вставить из буфера».") }
        .sheet(isPresented: $showShare) { if let shareURL = shareURL { ShareSheet(items: [shareURL]) } }
        .fileImporter(isPresented: $importFile, allowedContentTypes: [.audio]) { result in
            cloud.run { let url = try result.get(); let track = try downloads.importAudio(url); player.play(track, in: downloads.tracks) }
        }
    }
    private func share() {
        guard !busy else { return }; busy = true
        Task {
            do { let code = try await cloud.sharePlaylist(library.playlist.filter { !$0.id.hasPrefix("local_") }, code: library.code); library.code = code; library.save(); shareURL = URL(string: "https://mrx2827.github.io/amorxsyte/p.html#playlist/\(code)"); showShare = true }
            catch { cloud.issue = error.localizedDescription }
            busy = false
        }
    }
}

struct WaveScreen: View {
    @EnvironmentObject var cloud: Cloud
    @EnvironmentObject var player: Playback
    @EnvironmentObject var library: Library
    @State private var artists = ""
    @State private var results: [Track] = []
    @State private var busy = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack { Text("Моя волна").font(.largeTitle.bold()); Spacer(); RoundButton(icon: player.playing ? "pause.fill" : "play.fill", large: true, action: start) }
                Text("Музыка ваших любимых исполнителей").foregroundColor(.secondary)
                WaveParticles(playing: player.playing || busy)
                TextField("Исполнители через запятую", text: $artists).textFieldStyle(.roundedBorder)
                Button(busy ? "Подбираем музыку…" : "Запустить волну", action: start).buttonStyle(.borderedProminent).disabled(busy)
                if !library.recent.isEmpty { Text("Быстрый выбор").font(.title2.bold()); ForEach(library.recent.prefix(6)) { MusicRow(track: $0, tracks: library.recent) } }
                if !results.isEmpty { Text("Для вас").font(.title2.bold()); ForEach(results) { MusicRow(track: $0, tracks: results) } }
            }.padding(24)
        }.onAppear { artists = UserDefaults.standard.string(forKey: "wave-" + (cloud.uid ?? "local")) ?? "" }
    }
    private func start() {
        if player.playing && artists.isEmpty { player.toggle(); return }
        let names = artists.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard !names.isEmpty else { cloud.issue = "Добавьте любимых исполнителей"; return }
        guard !busy else { return }; busy = true
        UserDefaults.standard.set(artists, forKey: "wave-" + (cloud.uid ?? "local"))
        Task {
            do { var songs: [Track] = []; for artist in names.prefix(6) { songs += try await MusicSearch.search(artist + " лучшие песни") }; results = Track.unique(songs); player.playShuffled(results) }
            catch { cloud.issue = error.localizedDescription }; busy = false
        }
    }
}

struct OnlineScreen: View {
    @State private var tab = 0
    var body: some View {
        VStack(spacing: 0) {
            Picker("Онлайн", selection: $tab) { Text("Моя волна").tag(0); Text("Поиск").tag(1) }.pickerStyle(.segmented).padding()
            if tab == 0 { WaveScreen() } else { SearchScreen() }
        }
    }
}
