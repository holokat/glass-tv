import SwiftUI

/// Canonical native macOS design tokens. See ../STYLEGUIDE.md.
enum AppDesign {
    static let pageInset: CGFloat = 28
    static let pageTopInset: CGFloat = 24
    static let sectionGap: CGFloat = 20
    static let buttonRadius: CGFloat = 8
    static let titleFont = Font.system(size: 30, weight: .semibold)
    static let subtitleFont = Font.system(size: 15)
}

struct PageHeading: View {
    let title: String
    let subtitle: String?

    init(_ title: String, subtitle: String? = nil) {
        self.title = title
        self.subtitle = subtitle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(AppDesign.titleFont).accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle).font(AppDesign.subtitleFont).foregroundStyle(.secondary)
            }
        }
    }
}

extension View {
    @ViewBuilder
    func transparentWindowContainer() -> some View {
        if #available(macOS 15.0, *) {
            containerBackground(.clear, for: .window)
        } else {
            self
        }
    }

    @ViewBuilder
    func nativeGlassGroup() -> some View {
        if #available(macOS 26.0, *) {
            GlassEffectContainer { self }
        } else {
            self
        }
    }

    @ViewBuilder
    func nativeGlass(corner: CGFloat) -> some View {
        if #available(macOS 26.0, *) {
            glassEffect(.regular, in: RoundedRectangle(cornerRadius: corner, style: .continuous))
        } else {
            background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: corner, style: .continuous))
        }
    }
    /// All app action buttons use the same Apple-rendered shape and treatment.
    @ViewBuilder
    func nativeGlassButton(controlSize: ControlSize = .regular) -> some View {
        if #available(macOS 26.0, *) {
            buttonStyle(.glass)
                .buttonBorderShape(.roundedRectangle(radius: AppDesign.buttonRadius))
                .controlSize(controlSize)
        } else {
            buttonStyle(.bordered)
                .buttonBorderShape(.roundedRectangle)
                .controlSize(controlSize)
        }
    }
}
