import SwiftUI

struct AccountScreen: View {
    @Environment(\.amorxLayout) private var layout
    @EnvironmentObject var cloud: Cloud
    @EnvironmentObject var library: Library
    @ObservedObject var downloads = Downloads.shared
    var restore: () -> Void
    @State private var edit = false
    @State private var settings = false
    @State private var posts: [FeedPost] = []
    @State private var counts: [String: Any] = [:]
    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                HStack { ScreenHeading(cloud.name); Spacer(); RoundButton(icon: "gearshape") { settings = true } }
                AdaptiveActions {
                    Artwork(source: cloud.profile?.avatar, size: 104, radius: 52)
                    HStack(spacing: layout.narrow ? 10 : 20) {
                        counter(posts.count, "публикаций"); counter(counts["followers"] as? Int ?? 0, "подписчиков"); counter(counts["following"] as? Int ?? 0, "подписок")
                    }.frame(maxWidth: .infinity)
                }
                if let bio = cloud.profile?.bio, !bio.isEmpty { Text(bio).frame(maxWidth: .infinity, alignment: .leading) }
                Button("Редактировать профиль") { edit = true }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                AdaptiveActions {
                    Button("Восстановить музыку", action: restore).buttonStyle(.borderedProminent)
                    Button("Сохранить копию") { cloud.run { try await cloud.saveBackup(downloads: downloads.tracks.filter { !$0.id.hasPrefix("local_") }, playlist: library.playlist, code: library.code); cloud.issue = "Копия музыки сохранена в облако" } }.buttonStyle(.bordered)
                }
                ForEach($posts) { $post in PostCard(post: $post) }
            }.padding(20).frame(maxWidth: 740).frame(maxWidth: .infinity)
        }.navigationBarHidden(true)
            .task { cloud.run { guard let uid = cloud.uid else { return }; counts = try await cloud.rpc("feed_profile_counts", ["p_user": uid]).first ?? [:]; posts = try await cloud.rpc("feed_page", ["p_author": uid]).prefix(20).map(FeedPost.init) } }
            .sheet(isPresented: $edit) { EditProfileScreen() }
            .sheet(isPresented: $settings) { SettingsScreen() }
    }
    private func counter(_ count: Int, _ label: String) -> some View { VStack(spacing: 5) { Text("\(count)").font(.title3.bold()); Text(label).font(.system(size: 10)).foregroundColor(.secondary) } }
}
struct EditProfileScreen: View {
    @EnvironmentObject var cloud: Cloud
    @Environment(\.dismiss) var dismiss
    @State private var name = ""
    @State private var bio = ""
    @State private var isPublic = true
    @State private var avatar: Data?
    @State private var picker = false
    @State private var busy = false
    var body: some View {
        NavigationView {
            Form {
                Section { Artwork(source: avatar?.base64EncodedString() ?? cloud.profile?.avatar, size: 100, radius: 50); Button("Изменить аватарку") { picker = true } }
                Section { TextField("Имя", text: $name); TextField("О себе", text: $bio); Toggle("Публичный профиль", isOn: $isPublic) }
                Button(busy ? "Сохраняем…" : "Сохранить") {
                    busy = true
                    Task { do { try await cloud.saveProfile(name: name, bio: bio, avatar: avatar, isPublic: isPublic); dismiss() } catch { cloud.issue = error.localizedDescription }; busy = false }
                }.disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
            }.navigationTitle("Ваш профиль").toolbar { Button("Отмена") { dismiss() } }
        }.onAppear { name = cloud.profile?.name ?? ""; bio = cloud.profile?.bio ?? ""; isPublic = cloud.profilePublic }
            .sheet(isPresented: $picker) { MediaPicker(photoOnly: true) { choice in
                guard let image = UIImage(contentsOfFile: choice.url.path) else { return }
                let size = CGSize(width: 256, height: 256)
                avatar = UIGraphicsImageRenderer(size: size).jpegData(withCompressionQuality: 0.75) { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            } }
    }
}
struct SettingsScreen: View {
    @EnvironmentObject var cloud: Cloud
    @EnvironmentObject var library: Library
    @EnvironmentObject var player: Playback
    @Environment(\.dismiss) var dismiss
    @State private var signingOut = false
    @State private var update: String?
    var body: some View {
        NavigationView {
            Form {
                Section("Оформление") { Picker("Тема", selection: $library.theme) { Text("Как в системе").tag("system"); Text("Светлая").tag("light"); Text("Тёмная").tag("dark") }.onChange(of: library.theme) { UserDefaults.standard.set($0, forKey: "theme") } }
                Section("Аккаунт") { Text(cloud.session?.email ?? ""); Button("Выйти", role: .destructive) { signingOut = true } }
                Section("AmorX") {
                    Text("iOS 15+ · 5.5.499.1329")
                    Button("Проверить обновления") {
                        cloud.run {
                            let (data, response) = try await URLSession.shared.data(from: URL(string: "https://mrx2827.github.io/amorxsyte/ios-update.json")!)
                            guard (response as? HTTPURLResponse)?.statusCode == 200, let row = try JSONSerialization.jsonObject(with: data) as? [String: Any], let version = row["version"] as? String else { cloud.issue = "Первая iOS-версия. Канал обновлений появится после публикации"; return }
                            update = version
                        }
                    }
                    if let update = update { Text("На сайте: \(update)") }
                    Link("Сайт и новые IPA", destination: URL(string: "https://mrx2827.github.io/amorxsyte/")!)
                    Link("Открытый исходный код · GPLv3", destination: URL(string: "https://github.com/MRX2827/amorx-app")!)
                }
            }.navigationTitle("Настройки").toolbar { Button("Готово") { dismiss() } }
        }.alert("Выйти из аккаунта?", isPresented: $signingOut) { Button("Выйти", role: .destructive) { do { player.setPlaying(false); try cloud.signOut(); dismiss() } catch { cloud.issue = error.localizedDescription } }; Button("Отмена", role: .cancel) {} }
    }
}
struct RestoreScreen: View {
    let plan: MusicBackupPlan
    @Environment(\.amorxLayout) private var layout
    @EnvironmentObject var library: Library
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var downloads = Downloads.shared
    @State private var includeDownloads = true
    @State private var includePlaylist = true
    private var selected: [Track] { Track.unique((includeDownloads ? plan.downloads : []) + (includePlaylist ? plan.playlist : [])) }
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: layout.spacing) {
                    WaveParticles(playing: true, height: layout.compact ? 60 : 130)
                    ScreenHeading("Вернуть вашу музыку?").multilineTextAlignment(.center)
                    Text("Все скачанные песни и один плейлист AmorX. Готовые песни появятся в «Скачанном».").foregroundColor(.secondary).multilineTextAlignment(.center)
                    Toggle("Скачанные · \(plan.downloads.count)", isOn: $includeDownloads)
                    Toggle("Плейлист AmorX · \(plan.playlist.count)", isOn: $includePlaylist)
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(selected) { track in HStack {
                            Artwork(source: track.artwork, size: 36)
                            VStack(alignment: .leading) { Text(track.title).lineLimit(1); Text(track.artist).font(.caption).foregroundColor(.secondary) }
                            Spacer()
                            if downloads.local(track) != nil { Image(systemName: "checkmark.circle.fill").foregroundColor(.amorx) }
                        } }
                    }
                }.padding(layout.inset)
            }.safeAreaInset(edge: .bottom) {
                HStack {
                    Button("Не сейчас") { dismiss() }.frame(minHeight: 44)
                    Spacer(minLength: 4)
                    Button("Да, скачать") {
                        if includePlaylist { library.add(plan.playlist); library.code = plan.code ?? library.code; library.save() }
                        let songs = selected; Task { await downloads.downloadAll(songs) }; dismiss()
                    }.buttonStyle(.borderedProminent).disabled(!includeDownloads && !includePlaylist)
                }.padding(.horizontal, layout.inset).padding(.vertical, 8).background(Color.surface).layoutProbe("restore.actions")
            }.navigationBarHidden(true)
        }.navigationViewStyle(.stack)
    }
}
