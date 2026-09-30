import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Renders a QR code from a string payload (share link).
enum QRCodeImage {
    static func uiImage(from string: String, scale: CGFloat = 10) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

struct QRCodeView: View {
    let payload: String
    var size: CGFloat = 220

    var body: some View {
        Group {
            if let image = QRCodeImage.uiImage(from: payload) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size, height: size)
                    .padding(12)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            } else {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Theme.backgroundGrouped)
                    .frame(width: size, height: size)
                    .overlay {
                        Text("Could not create QR")
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                    }
            }
        }
        .accessibilityLabel("QR code")
    }
}
