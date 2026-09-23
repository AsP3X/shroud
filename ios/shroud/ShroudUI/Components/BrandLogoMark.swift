import SwiftUI

/// Brand gradient logo mark used on Welcome and auth hero sections.
struct BrandLogoMark: View {
    var size: CGFloat = 72

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                .fill(Theme.brandGradient)
                .frame(width: size, height: size)
            Image("BrandMark")
                .resizable()
                .scaledToFit()
                .frame(width: size * 0.6, height: size * 0.6)
                .foregroundStyle(Color.white)
        }
    }
}
