import SwiftUI
import AVKit
import Combine

struct HomeScreen: View {
    @EnvironmentObject var cloud: Cloud
    @State private var chats = false
    @State private var people = false
    @State private var publish = false
    @State private var storyPublish = false
    @State private var selectedStory: Story?
    @State private var loading = false
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 18) {
                HStack {
                    Text("Главное").font(.system(size: 30, weight: .bold))
                    Spacer()
                    RoundButton(icon: "person.2") { people = true }
                    RoundButton(icon: "bubble.left.and.bubble.right") { chats = true }
                    RoundButton(icon: "plus") { publish = true }
                }.padding(.horizontal, 18).padding(.top, 8)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        Button { storyPublish = true } label: {
                            VStack { ZStack(alignment: .bottomTrailing) { Artwork(source: cloud.profile?.avatar, size: 64, radius: 32); Image(systemName: "plus.circle.fill").foregroundColor(.amorx).background(Color.white.clipShape(Circle())) }; Text("Ваша история").font(.caption) }
                        }.buttonStyle(PressScale())
                        ForEach(Array(Set(cloud.stories.map(\.owner))).sorted(), id: \.self) { owner in
                            Button { selectedStory = cloud.stories.first { $0.owner == owner } } label: {
                                VStack(spacing: 6) {
                                    Artwork(source: cloud.people.first { $0.id == owner }?.avatar, size: 64, radius: 32)
                                        .padding(3).overlay(Circle().stroke(LinearGradient(colors: [.amorx, .purple, .pink], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 2))
                                    Text(cloud.people.first { $0.id == owner }?.name ?? "AmorX").font(.caption).lineLimit(1).frame(width: 74)
                                }
                            }.buttonStyle(PressScale())
                        }
                    }.padding(.horizontal, 18)
                }
                if loading && cloud.posts.isEmpty { ProgressView().padding(30) }
                if !loading && cloud.posts.isEmpty { VStack(spacing: 12) { Image(systemName: "person.2.wave.2").font(.largeTitle).foregroundColor(.amorx); Text("Лента ваших людей").font(.title2.bold()); Text("Публикуйте фото и видео, добавляйте друзей и подписывайтесь на людей.").multilineTextAlignment(.center).foregroundColor(.secondary); Button("Найти людей") { people = true }.buttonStyle(.borderedProminent) }.padding(30) }
                ForEach($cloud.posts) { $post in PostCard(post: $post) }
                if cloud.posts.count >= 20 { Button("Загрузить ещё") { cloud.run { try await cloud.refreshFeed(before: cloud.posts.last) } }.padding() }
            }.frame(maxWidth: 740).frame(maxWidth: .infinity)
        }.navigationBarHidden(true)
            .task { await refresh() }.refreshable { await refresh() }
            .sheet(isPresented: $chats) { ChatsScreen() }
            .sheet(isPresented: $people) { PeopleScreen() }
            .sheet(isPresented: $publish) { PublishScreen(story: false) }
            .sheet(isPresented: $storyPublish) { PublishScreen(story: true) }
            .sheet(item: $selectedStory) { story in StoryViewer(stories: cloud.stories.filter { $0.owner == story.owner }, first: 0) }
    }
    private func refresh() async {
        loading = true
        do { try await cloud.refreshFeed(); try await cloud.refreshStories() }
        catch { if !Task.isCancelled { cloud.issue = error.localizedDescription } }
        loading = false
    }
}

struct SignedMedia: View {
    @EnvironmentObject var cloud: Cloud
    let bucket: String
    let path: String
    var video = false
    @State private var url: URL?
    @State private var mediaPlayer: AVPlayer?
    @State private var error: String?
    var body: some View {
        ZStack {
            Color.primary.opacity(0.04)
            if let url = url {
                if video { VideoPlayer(player: mediaPlayer) }
                else { AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFit() }
                    else if phase.error != nil { Image(systemName: "photo.badge.exclamationmark") }
                    else { ProgressView() }
                } }
            } else if let error = error { Text(error).font(.caption).foregroundColor(.secondary).padding() }
            else { ProgressView() }
        }.aspectRatio(1, contentMode: .fit).frame(maxHeight: 650)
            .onDisappear { mediaPlayer?.pause() }
            .task(id: path) { do { let result = try await cloud.mediaURL(bucket: bucket, path: path); url = result; if video { mediaPlayer = AVPlayer(url: result) } } catch { self.error = error.localizedDescription } }
    }
}
struct PostCard: View {
    @Binding var post: FeedPost
    @EnvironmentObject var cloud: Cloud
    @State private var comments = false
    @State private var busy = false
    @State private var person: Person?
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button { cloud.run { person = try await cloud.profiles(ids: [post.authorID]).first } } label: { HStack { Artwork(source: cloud.people.first { $0.id == post.authorID }?.avatar, size: 36, radius: 18); Text(post.author).font(.subheadline.bold()) } }.buttonStyle(.plain)
                Spacer()
                if post.authorID == cloud.uid { Menu { Button("Удалить публикацию", role: .destructive) { cloud.run { try await cloud.remove("feed_posts", query: ["id": "eq.\(post.id)", "author_id": "eq.\(post.authorID)"]); cloud.posts.removeAll { $0.id == post.id } } } } label: { Image(systemName: "ellipsis") } }
            }
            SignedMedia(bucket: "feed-media", path: post.path, video: post.type.hasPrefix("video/"))
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            HStack(spacing: 20) {
                Button {
                    guard !busy else { return }; busy = true
                    Task { do { let liked = post.liked; try await cloud.like(post); post.liked = !liked; post.likes += liked ? -1 : 1 } catch { cloud.issue = error.localizedDescription }; busy = false }
                } label: { Label("\(post.likes)", systemImage: post.liked ? "heart.fill" : "heart").foregroundColor(post.liked ? .pink : .primary) }.buttonStyle(PressScale())
                Button { comments = true } label: { Label("\(post.comments)", systemImage: "bubble.right") }.buttonStyle(PressScale())
                Spacer()
            }.font(.title3)
            if !post.caption.isEmpty { Text(post.caption).font(.body) }
        }.padding(16).background(Color.primary.opacity(0.025)).clipShape(RoundedRectangle(cornerRadius: 28)).padding(.horizontal, 12)
            .sheet(isPresented: $comments) { CommentsScreen(post: post) { post.comments += 1 } }
            .sheet(item: $person) { NavigationView { PersonScreen(person: $0) } }
    }
}
struct CommentsScreen: View {
    let post: FeedPost
    var sent: () -> Void
    @EnvironmentObject var cloud: Cloud
    @Environment(\.dismiss) var dismiss
    @State private var rows: [[String: Any]] = []
    @State private var text = ""
    @State private var busy = false
    var body: some View {
        NavigationView {
            VStack {
                ScrollView { LazyVStack(alignment: .leading, spacing: 16) { ForEach(rows.indices, id: \.self) { index in VStack(alignment: .leading, spacing: 5) { Text(rows[index]["author_name"] as? String ?? "AmorX").bold(); Text(rows[index]["body"] as? String ?? "") }.frame(maxWidth: .infinity, alignment: .leading) } }.padding() }
                HStack { TextField("Комментарий", text: $text).textFieldStyle(.roundedBorder); Button { send() } label: { Image(systemName: "paperplane.fill") }.disabled(busy || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }.padding()
            }.navigationTitle("Комментарии").toolbar { Button("Готово") { dismiss() } }.task { cloud.run { rows = try await cloud.comments(post) } }
        }
    }
    private func send() {
        busy = true
        Task { do { try await cloud.comment(post, body: text); text = ""; sent(); rows = try await cloud.comments(post) } catch { cloud.issue = error.localizedDescription }; busy = false }
    }
}
struct PublishScreen: View {
    let story: Bool
    @EnvironmentObject var cloud: Cloud
    @Environment(\.dismiss) var dismiss
    @State private var picker = false
    @State private var media: MediaChoice?
    @State private var caption = ""
    @State private var busy = false
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    if let media = media {
                        if media.video { VideoPlayer(player: AVPlayer(url: media.url)).frame(height: 320) }
                        else if let image = UIImage(contentsOfFile: media.url.path) { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 420).cornerRadius(24) }
                    }
                    Button { picker = true } label: { Label(story ? "Выбрать видео" : "Выбрать фото или видео", systemImage: "photo.on.rectangle.angled") }.buttonStyle(.bordered)
                    TextField("Подпись", text: $caption).textFieldStyle(.roundedBorder)
                    if story { Text("История исчезнет через 24 часа. Песню можно добавить из меню трека.").font(.caption).foregroundColor(.secondary) }
                    Button(busy ? "Публикуем…" : "Опубликовать") {
                        guard let media = media else { return }; busy = true
                        Task { do { let file = try await MediaTools.prepare(media, story: story); defer { try? FileManager.default.removeItem(at: file) }; try await cloud.publish(file: file, video: media.video, caption: caption, story: story); try await cloud.refreshFeed(); try await cloud.refreshStories(); dismiss() } catch { cloud.issue = error.localizedDescription }; busy = false }
                    }.buttonStyle(.borderedProminent).disabled(media == nil || busy || cloud.profile == nil)
                }.padding(24)
            }.navigationTitle(story ? "Новая история" : "Новая публикация").toolbar { Button("Закрыть") { dismiss() }.disabled(busy) }
        }.sheet(isPresented: $picker) { MediaPicker(videoOnly: story) { media = $0 } }
    }
}

struct PeopleScreen: View {
    @EnvironmentObject var cloud: Cloud
    @Environment(\.dismiss) var dismiss
    @State private var query = ""
    @State private var people: [Person] = []
    var body: some View {
        NavigationView {
            List(people.filter { $0.id != cloud.uid }) { person in NavigationLink { PersonScreen(person: person) } label: { HStack { Artwork(source: person.avatar, size: 52, radius: 26); VStack(alignment: .leading, spacing: 4) { Text(person.name).bold(); Text(person.bio).font(.caption).foregroundColor(.secondary).lineLimit(1) } } } }
                .listStyle(.plain).searchable(text: $query, prompt: "Имя пользователя").navigationTitle("Люди").toolbar { Button("Готово") { dismiss() } }
                .task(id: query) {
                    do { try await Task.sleep(nanoseconds: 300_000_000); people = try await cloud.findPeople(query) }
                    catch { if !Task.isCancelled { cloud.issue = error.localizedDescription } }
                }
        }
    }
}
struct PersonScreen: View {
    let person: Person
    @EnvironmentObject var cloud: Cloud
    @State private var following = false
    @State private var counts: [String: Any] = [:]
    @State private var posts: [FeedPost] = []
    @State private var friend: [String: Any]?
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                HStack(spacing: 24) { Artwork(source: person.avatar, size: 94, radius: 47); VStack(alignment: .leading, spacing: 8) { Text(person.name).font(.title.bold()); HStack { VStack { Text("\(counts["followers"] as? Int ?? 0)").bold(); Text("подписчиков").font(.caption) }; VStack { Text("\(counts["following"] as? Int ?? 0)").bold(); Text("подписок").font(.caption) } } } }
                if !person.bio.isEmpty { Text(person.bio).frame(maxWidth: .infinity, alignment: .leading) }
                HStack {
                    Button(following ? "Вы подписаны" : "Подписаться") { cloud.run { try await cloud.follow(person.id, enabled: !following); following.toggle() } }.buttonStyle(.borderedProminent)
                    NavigationLink { ThreadScreen(peer: person) } label: { Text("Сообщение") }.buttonStyle(.bordered)
                }
                Button(friendTitle) { cloud.run { try await friendAction(); try await loadFriend() } }.buttonStyle(.bordered)
                ForEach($posts) { $post in PostCard(post: $post) }
            }.padding(20).frame(maxWidth: 740).frame(maxWidth: .infinity)
        }.navigationTitle(person.name).navigationBarTitleDisplayMode(.inline)
            .task { cloud.run { following = try await cloud.following(person.id); counts = try await cloud.rpc("feed_profile_counts", ["p_user": person.id]).first ?? [:]; posts = try await cloud.rpc("feed_page", ["p_author": person.id]).prefix(20).map(FeedPost.init); try await loadFriend() } }
    }
    private var friendTitle: String {
        guard let friend = friend else { return "Добавить в друзья" }
        if friend["status"] as? String == "accepted" { return "Удалить из друзей" }
        return friend["addressee_id"] as? String == cloud.uid ? "Принять дружбу" : "Отменить запрос в друзья"
    }
    private func loadFriend() async throws {
        guard let uid = cloud.uid else { return }
        friend = try await cloud.rows("friendships", query: ["or": "(and(requester_id.eq.\(uid),addressee_id.eq.\(person.id)),and(requester_id.eq.\(person.id),addressee_id.eq.\(uid)))", "limit": "1"]).first
    }
    private func friendAction() async throws {
        guard let uid = cloud.uid else { return }
        if let friend = friend {
            if friend["status"] as? String != "accepted", friend["addressee_id"] as? String == uid { _ = try await cloud.rpc("accept_friend", ["p_requester": person.id]) }
            else { try await cloud.remove("friendships", query: ["requester_id": "eq.\(friend["requester_id"] as? String ?? "")", "addressee_id": "eq.\(friend["addressee_id"] as? String ?? "")"]) }
        } else { try await cloud.write("friendships", ["requester_id": uid, "addressee_id": person.id, "status": "pending"]) }
    }
}

struct ChatsScreen: View {
    @EnvironmentObject var cloud: Cloud
    @Environment(\.dismiss) var dismiss
    @State private var peers: [Person] = []
    @State private var last: [String: String] = [:]
    @State private var people = false
    var body: some View {
        NavigationView {
            List {
                if peers.isEmpty { Text("Начните общение через профиль друга").foregroundColor(.secondary) }
                ForEach(peers) { person in NavigationLink { ThreadScreen(peer: person) } label: { HStack { Artwork(source: person.avatar, size: 54, radius: 27); VStack(alignment: .leading, spacing: 4) { Text(person.name).bold(); Text(last[person.id] ?? "").font(.subheadline).foregroundColor(.secondary).lineLimit(1) } } } }
            }.listStyle(.plain).navigationTitle("Сообщения").toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("Готово") { dismiss() } }; ToolbarItem(placement: .navigationBarTrailing) { Button { people = true } label: { Image(systemName: "square.and.pencil") } } }
                .task {
                    do {
                        guard let uid = cloud.uid else { return }
                        let rows = try await cloud.rows("messages", query: ["select": "*", "order": "created_at.desc", "limit": "300"])
                        var ids: [String] = []
                        for row in rows {
                            let id = row["sender_id"] as? String == uid ? row["recipient_id"] as? String : row["sender_id"] as? String
                            if let id = id, !ids.contains(id) { ids.append(id); last[id] = row["body"] as? String ?? "Песня" }
                        }
                        let found = try await cloud.profiles(ids: ids); peers = ids.map { id in found.first { $0.id == id } ?? Person(["id": id, "username": "Пользователь"]) }
                    } catch { cloud.issue = error.localizedDescription }
                }
        }.sheet(isPresented: $people) { PeopleScreen() }
    }
}
struct ThreadScreen: View {
    let peer: Person
    @EnvironmentObject var cloud: Cloud
    @EnvironmentObject var player: Playback
    @State private var messages: [Message] = []
    @State private var text = ""
    @State private var busy = false
    var body: some View {
        VStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(messages) { message in
                            HStack {
                                if message.sender == cloud.uid { Spacer(minLength: 50) }
                                VStack(alignment: .leading, spacing: 8) {
                                    if !message.text.isEmpty { Text(message.text) }
                                    if let track = message.track { Button { player.play(track) } label: { HStack { Artwork(source: track.artwork, size: 40); VStack(alignment: .leading) { Text(track.title).bold().lineLimit(2); Text(track.artist).font(.caption) }; Image(systemName: "play.fill") } } }
                                }.padding(14).background(message.sender == cloud.uid ? Color.amorx.opacity(0.15) : Color.primary.opacity(0.06)).cornerRadius(22)
                                if message.sender != cloud.uid { Spacer(minLength: 50) }
                            }.id(message.id)
                        }
                    }.padding()
                }.onChange(of: messages.count) { _ in if let last = messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } } }
            }
            HStack {
                if player.current != nil { Button { send(track: player.current) } label: { Image(systemName: "music.note") }.disabled(busy) }
                TextField("Сообщение", text: $text).textFieldStyle(.roundedBorder)
                Button { send() } label: { Image(systemName: "paperplane.fill") }.disabled(busy || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }.padding()
        }.navigationTitle(peer.name).navigationBarTitleDisplayMode(.inline)
            .task {
                do {
                    while !Task.isCancelled {
                        messages = try await cloud.thread(peer)
                        if let uid = cloud.uid { _ = try await cloud.request("/rest/v1/messages", method: "PATCH", body: ["read_at": ISO8601DateFormatter().string(from: Date())], query: ["recipient_id": "eq.\(uid)", "sender_id": "eq.\(peer.id)", "read_at": "is.null"]) }
                        try await Task.sleep(nanoseconds: 2_500_000_000)
                    }
                } catch { if !Task.isCancelled { cloud.issue = error.localizedDescription } }
            }
    }
    private func send(track: Track? = nil) {
        guard !busy else { return }; busy = true
        Task { do { try await cloud.send(peer, body: text, track: track); text = ""; messages = try await cloud.thread(peer) } catch { cloud.issue = error.localizedDescription }; busy = false }
    }
}
