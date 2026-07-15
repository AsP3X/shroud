import SwiftUI

/// Shimmer placeholder used while encryption phrase words are being revealed.
struct ShimmerPlaceholder: View {
    var height: CGFloat = 14
    var width: CGFloat? = 88

    @State private var phase: CGFloat = -1

    var body: some View {
        RoundedRectangle(cornerRadius: 6, style: .continuous)
            .fill(Theme.backgroundGrouped)
            .frame(width: width, height: height)
            .overlay {
                GeometryReader { geometry in
                    LinearGradient(
                        colors: [
                            Theme.backgroundGrouped,
                            Theme.background,
                            Theme.backgroundGrouped,
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: geometry.size.width * 1.8)
                    .offset(x: geometry.size.width * phase)
                }
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            }
            .onAppear {
                // Human: Faster shimmer sweep so placeholders feel energetic while pairs unlock.
                withAnimation(.linear(duration: 0.85).repeatForever(autoreverses: false)) {
                    phase = 1
                }
            }
    }
}
