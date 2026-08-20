import SwiftUI

enum OrbitTheme {
    static let navy = Color(red: 7 / 255, green: 26 / 255, blue: 39 / 255)
    static let surface = Color(red: 16 / 255, green: 42 / 255, blue: 56 / 255)
    static let raised = Color(red: 24 / 255, green: 56 / 255, blue: 71 / 255)
    static let lime = Color(red: 201 / 255, green: 1, blue: 74 / 255)
    static let mint = Color(red: 114 / 255, green: 232 / 255, blue: 192 / 255)
    static let muted = Color(red: 159 / 255, green: 179 / 255, blue: 188 / 255)
    static let danger = Color(red: 1, green: 139 / 255, blue: 125 / 255)
}

struct OrbitPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.bold))
            .foregroundStyle(isEnabled ? OrbitTheme.navy : OrbitTheme.muted)
            .padding(.horizontal, 18)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(
                isEnabled
                    ? OrbitTheme.lime.opacity(configuration.isPressed ? 0.82 : 1)
                    : OrbitTheme.raised,
                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
            )
            .overlay {
                if !isEnabled {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(OrbitTheme.muted.opacity(0.35))
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

extension View {
    func orbitCard() -> some View {
        self
            .padding(18)
            .background(OrbitTheme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}
