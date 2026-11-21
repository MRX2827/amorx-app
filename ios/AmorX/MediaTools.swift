import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import AVFoundation

struct MediaChoice: Identifiable {
    let id = UUID()
    let url: URL
    let video: Bool
}
struct MediaPicker: UIViewControllerRepresentable {
    var videoOnly = false
    var photoOnly = false
    var selected: (MediaChoice) -> Void
    @Environment(\.dismiss) private var dismiss
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(); config.selectionLimit = 1
        config.filter = videoOnly ? .videos : photoOnly ? .images : .any(of: [.images, .videos])
        let picker = PHPickerViewController(configuration: config); picker.delegate = context.coordinator; return picker
    }
    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: MediaPicker
        init(parent: MediaPicker) { self.parent = parent }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            parent.dismiss(); guard let provider = results.first?.itemProvider else { return }
            let video = provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
            provider.loadFileRepresentation(forTypeIdentifier: video ? UTType.movie.identifier : UTType.image.identifier) { url, _ in
                guard let url = url else { return }
                let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + url.pathExtension)
                do { try FileManager.default.copyItem(at: url, to: target); DispatchQueue.main.async { self.parent.selected(MediaChoice(url: target, video: video)) } }
                catch { }
            }
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

enum MediaTools {
    static func prepare(_ media: MediaChoice, story: Bool) async throws -> URL {
        if !media.video {
            guard let image = UIImage(contentsOfFile: media.url.path) else { throw AppIssue("Не удалось открыть фотографию") }
            let scale = min(1, 1920 / max(image.size.width, image.size.height))
            let data = UIGraphicsImageRenderer(size: CGSize(width: image.size.width * scale, height: image.size.height * scale)).jpegData(withCompressionQuality: 0.82) { _ in
                image.draw(in: CGRect(origin: .zero, size: CGSize(width: image.size.width * scale, height: image.size.height * scale)))
            }
            let output = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
            try data.write(to: output); return output
        }
        return try await export(media.url, preset: AVAssetExportPreset1280x720, type: .mp4, start: 0, seconds: story ? 30 : 120)
    }
    static func audioClip(_ url: URL, start: Double, seconds: Double) async throws -> URL {
        try await export(url, preset: AVAssetExportPresetAppleM4A, type: .m4a, start: start, seconds: min(30, max(5, seconds)))
    }
    static func export(_ url: URL, preset: String, type: AVFileType, start: Double, seconds: Double) async throws -> URL {
        let asset = AVURLAsset(url: url)
        let duration = try await asset.load(.duration).seconds
        guard duration.isFinite, duration > start else { throw AppIssue("Неверная длительность медиа") }
        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else { throw AppIssue("Этот формат не поддерживается") }
        let output = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + (type == .m4a ? ".m4a" : ".mp4"))
        session.outputURL = output; session.outputFileType = type
        session.timeRange = CMTimeRange(start: CMTime(seconds: start, preferredTimescale: 600), duration: CMTime(seconds: min(seconds, duration - start), preferredTimescale: 600))
        session.shouldOptimizeForNetworkUse = true
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                session.exportAsynchronously {
                    if session.status == .completed { continuation.resume() }
                    else { continuation.resume(throwing: session.error ?? AppIssue("Не удалось обработать медиа")) }
                }
            }
        }, onCancel: { session.cancelExport() })
        return output
    }
}
