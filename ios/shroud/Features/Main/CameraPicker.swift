import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Still photo or movie captured with the system camera.
enum CameraCapture {
    case photo(UIImage)
    case movie(PickedMovie)
}

/// UIImagePickerController wrapper. The person can switch the shutter between photo and video.
struct CameraPicker: UIViewControllerRepresentable {
    var onFinish: (CameraCapture?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        picker.allowsEditing = false
        picker.videoQuality = .typeHigh
        let wanted: [String] = [UTType.image.identifier, UTType.movie.identifier]
        if let available = UIImagePickerController.availableMediaTypes(for: .camera) {
            let types = wanted.filter { available.contains($0) }
            picker.mediaTypes = types.isEmpty ? wanted : types
        } else {
            picker.mediaTypes = wanted
        }
        if picker.mediaTypes.contains(UTType.image.identifier) {
            picker.cameraCaptureMode = .photo
        } else if picker.mediaTypes.contains(UTType.movie.identifier) {
            picker.cameraCaptureMode = .video
        }
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let onFinish: (CameraCapture?) -> Void

        init(onFinish: @escaping (CameraCapture?) -> Void) {
            self.onFinish = onFinish
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            let mediaType = info[.mediaType] as? String
            if mediaType == UTType.movie.identifier
                || mediaType == UTType.mpeg4Movie.identifier
                || mediaType == "public.movie"
            {
                guard let source = info[.mediaURL] as? URL,
                      let movie = Self.copyMovie(from: source)
                else {
                    onFinish(nil)
                    return
                }
                onFinish(.movie(movie))
                return
            }
            let image = info[.originalImage] as? UIImage
            onFinish(image.map { .photo($0) })
        }

        /// PhotosPicker copies library movies so export survives picker teardown; camera tmp files need the same.
        private static func copyMovie(from source: URL) -> PickedMovie? {
            let ext = source.pathExtension.isEmpty ? "mov" : source.pathExtension
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("shroud-cam-\(UUID().uuidString).\(ext)")
            try? FileManager.default.removeItem(at: dest)
            do {
                try FileManager.default.copyItem(at: source, to: dest)
                return PickedMovie(url: dest)
            } catch {
                return nil
            }
        }
    }
}
