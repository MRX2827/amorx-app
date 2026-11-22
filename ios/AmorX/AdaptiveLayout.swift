import SwiftUI

private struct PhoneLayoutKey: EnvironmentKey {
    static let defaultValue = PhoneLayout(width: 390, height: 844)
}
extension EnvironmentValues {
    var amorxLayout: PhoneLayout {
        get { self[PhoneLayoutKey.self] }
        set { self[PhoneLayoutKey.self] = newValue }
    }
}
struct AdaptiveRoot: View {
    var body: some View {
        GeometryReader { geometry in
            let size = LayoutPreview.size ?? geometry.size
            Group {
                if LayoutPreview.screen == "player" { FullPlayer() }
                else if LayoutPreview.screen == "restore" { RestoreScreen(plan: LayoutPreview.plan) }
                else { RootScreen() }
            }
            .environment(\.amorxLayout, PhoneLayout(width: size.width, height: size.height))
            .frame(width: size.width, height: size.height)
            .coordinateSpace(name: "amorx-layout")
            .layoutProbe("__viewport")
            .onPreferenceChange(LayoutFrames.self) { LayoutPreview.record($0, size: size) }
        }
    }
}
struct AdaptiveActions<Content: View>: View {
    @Environment(\.amorxLayout) private var layout
    @Environment(\.dynamicTypeSize) private var textSize
    private let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        Group {
            if layout.narrow || textSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 10) { content }
            } else { HStack(spacing: 10) { content } }
        }
    }
}
struct ScreenHeading: View {
    let title: String
    @Environment(\.amorxLayout) private var layout
    init(_ title: String) { self.title = title }
    var body: some View {
        Text(title).font(.custom("Roboto-Bold", size: layout.compact ? 25 : 34, relativeTo: .largeTitle))
            .fixedSize(horizontal: false, vertical: true).layoutPriority(1)
    }
}
