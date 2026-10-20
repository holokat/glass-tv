import AppKit
import SwiftUI

// Native controls and truthful artwork for the Weather page.
struct WeatherDestinationArtwork: View {
    let location: WeatherLocation
    let artworkVariant: Int
    let corner: CGFloat

    init(location: WeatherLocation, artworkVariant: Int? = nil, corner: CGFloat = 10) {
        self.location = location
        self.artworkVariant = artworkVariant ?? location.artworkVariant
        self.corner = corner
    }

    var body: some View {
        GeometryReader { geometry in
            Group {
                if let image = WeatherArtwork.image(for: location, variant: artworkVariant) {
                    Image(nsImage: image).resizable().scaledToFill()
                } else {
                    Color.clear.overlay {
                        Icon("weather_cloud", fallback: "Weather")
                            .frame(width: 28, height: 28)
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .accessibilityLabel(WeatherArtwork.caption(for: location))
        .accessibilityHint("Illustrative artwork only. It does not describe current conditions.")
    }
}

struct WeatherStyleChoice: View {
    let style: WeatherStyle
    let selected: Bool
    let location: WeatherLocation?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 7) {
                sample.frame(height: 58).frame(maxWidth: .infinity)
                Text(style.title)
                    .font(.caption.weight(.medium))
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(selected ? Color.accentColor.opacity(0.14) : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(selected ? Color.accentColor : Color.clear, lineWidth: selected ? 1.5 : 0)
            }
        }
        .nativeGlassButton()
        .accessibilityLabel("\(style.title) widget style")
        .accessibilityValue(selected ? "Selected" : "Not selected")
    }

    @ViewBuilder private var sample: some View {
        switch style {
        case .minimal:
            VStack(alignment: .leading, spacing: 3) {
                Text("Location").font(.caption2)
                HStack(spacing: 4) {
                    Icon("weather_sun", fallback: "Weather").frame(width: 15, height: 15)
                    Text("—").font(.caption)
                }
                Divider().opacity(0.45)
                HStack(spacing: 0) {
                    ForEach(0..<5, id: \.self) { _ in
                        Text("—").font(.caption2).frame(maxWidth: .infinity)
                    }
                }
            }
            .padding(7)
        case .compact:
            HStack(spacing: 6) {
                Icon("weather_sun", fallback: "Weather").frame(width: 22, height: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text("—").font(.title3)
                    Text("—  H —  L —").font(.caption2)
                }
            }
            .padding(8)
        case .scenic:
            ZStack(alignment: .bottomLeading) {
                if let location {
                    WeatherDestinationArtwork(location: location, artworkVariant: location.artworkVariant)
                } else {
                    Color.clear
                }
                Text("—")
                    .font(.title2)
                    .padding(7)
            }
        case .icon:
            HStack(spacing: 5) {
                Icon("weather_sun", fallback: "Weather")
                    .frame(width: 22, height: 22)
                Text("—")
                    .font(.title3)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .padding(8)
        }
    }
}

struct WeatherPreviewCanvas: View {
    let location: WeatherLocation?
    let preview: WeatherPreview?
    let settings: WeatherSettings
    let isLoading: Bool
    let onCycleArtwork: (() -> Void)?
    var locations: [WeatherLocation] = []
    var previews: [String: WeatherPreview] = [:]
    var loadingLocationIDs: Set<String> = []
    var portraitGap: CGFloat = 12

    private var portraitLocations: [WeatherLocation] {
        let source = locations.isEmpty ? [location].compactMap { $0 } : locations
        if settings.showAllLocations { return source.filter(\.enabled) }
        return location.map { [$0] } ?? source.first(where: \.enabled).map { [$0] } ?? []
    }

    var body: some View {
        if settings.usesPortraitCards {
            portraitCanvas
        } else if settings.style == .icon {
            inlineCanvas
        } else {
            standardCanvas
        }
    }

    @ViewBuilder private var portraitCanvas: some View {
        if portraitLocations.isEmpty {
            statusMessage("No location selected")
        } else {
            HStack(alignment: .top, spacing: portraitGap) {
                ForEach(portraitLocations) { city in
                    WeatherPortraitCard(
                        location: city,
                        preview: previews[city.id] ?? (city.id == location?.id ? preview : nil),
                        isLoading: loadingLocationIDs.contains(city.id) || (city.id == location?.id && isLoading),
                        showTemperature: settings.showTemperature,
                        style: settings.style,
                        contentScale: CGFloat(settings.effectiveContentScale)
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityLabel("TV portrait weather widget preview")
        }
    }

    private var standardCanvas: some View {
        ZStack(alignment: .bottomLeading) {
            if settings.style == .scenic, let location {
                WeatherDestinationArtwork(location: location, artworkVariant: location.artworkVariant)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .overlay(Color.black.opacity(0.2))
            }

            if isLoading {
                statusMessage("Fetching the forecast…", loading: true)

            } else if let location, let preview {
                WeatherWidgetLayout(location: location, preview: preview, settings: settings)
                    .padding(16)
            } else if location != nil {
                statusMessage("Forecast unavailable")
            } else {
                statusMessage("No location selected")
            }
        }
        .overlay(alignment: .topTrailing) {
            if settings.style == .scenic, let location, let onCycleArtwork {
                Button(action: onCycleArtwork) {
                    Icon("refresh", fallback: "Refresh artwork")
                        .frame(width: 17, height: 17)
                        .frame(width: 22, height: 22)
                }
                .nativeGlassButton(controlSize: .small)
                .disabled(!WeatherArtwork.canCycleArtwork(for: location))
                .help("Show another destination artwork")
                .accessibilityLabel("Show another destination artwork")
                .accessibilityHint(WeatherArtwork.canCycleArtwork(for: location) ? "Changes the artwork for this location." : "Another bundled artwork is unavailable.")
                .padding(12)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("TV weather widget preview")
    }

    @ViewBuilder private var inlineCanvas: some View {
        if isLoading {
            inlineStatusMessage("Fetching the forecast…", loading: true)
        } else if let location, let preview, preview.temperature != nil, preview.weatherCode != nil {
            WeatherWidgetLayout(location: location, preview: preview, settings: settings)
        } else if location != nil {
            inlineStatusMessage("Forecast unavailable")
        } else {
            inlineStatusMessage("No location selected")
        }
    }

    @ViewBuilder private func statusMessage(_ text: String, loading: Bool = false) -> some View {
        HStack(spacing: 10) {
            if loading { ProgressView().controlSize(.small) }
            Text(text).font(.headline)
        }
        .padding(14)
        .background(.black.opacity(0.25), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(16)
    }

    private func inlineStatusMessage(_ text: String, loading: Bool = false) -> some View {
        HStack(spacing: 8) {
            if loading { ProgressView().controlSize(.small) }
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

private struct WeatherPortraitCard: View {
    let location: WeatherLocation
    let preview: WeatherPreview?
    let isLoading: Bool
    let showTemperature: Bool
    let style: WeatherStyle
    let contentScale: CGFloat

    @ViewBuilder
    var body: some View {
        if style == .scenic {
            card
        } else {
            card.nativeGlass(corner: 16)
        }
    }

    private var card: some View {
        ZStack(alignment: .bottomLeading) {
            if style == .scenic {
                WeatherDestinationArtwork(location: location, artworkVariant: location.artworkVariant, corner: 16)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .overlay(Color.black.opacity(0.28))
            } else {
                Color.clear
            }

            if isLoading {
                ProgressView("Fetching the forecast…")
                    .controlSize(.small)
                    .padding(12)
            } else if let preview {
                forecast(preview)
            } else {
                unavailable
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Weather for \(location.name)")
    }

    @ViewBuilder
    private func forecast(_ preview: WeatherPreview) -> some View {
        switch style {
        case .minimal:
            VStack(alignment: .leading, spacing: 10) {
                cityName
                Divider().opacity(0.55)
                Spacer(minLength: 0)
                if showTemperature { temperature(preview, size: 38) }
                condition(preview)
                if showTemperature { highLow(preview) }
            }
            .padding(12)
        case .compact:
            VStack(alignment: .leading, spacing: 9) {
                cityName
                Spacer(minLength: 0)
                HStack(alignment: .center, spacing: 8) {
                    WeatherGlyph(code: preview.weatherCode, isDay: preview.isDay)
                        .frame(width: scaled(30), height: scaled(30))
                    if showTemperature { temperature(preview, size: 34) }
                }
                condition(preview)
                if showTemperature { highLow(preview) }
            }
            .padding(12)
        case .scenic:
            VStack(alignment: .leading, spacing: 6) {
                cityName
                Spacer(minLength: 0)
                if showTemperature { temperature(preview, size: 38) }
                condition(preview)
                if showTemperature { highLow(preview) }
            }
            .padding(12)
        case .icon:
            EmptyView()
        }
    }

    private var unavailable: some View {
        VStack(alignment: .leading, spacing: 6) {
            cityName
            Spacer(minLength: 0)
            Text("Forecast unavailable")
                .font(.caption)
                .foregroundStyle(style == .scenic ? .white.opacity(0.86) : .secondary)
        }
        .padding(12)
    }

    private func temperature(_ preview: WeatherPreview, size: CGFloat) -> some View {
        Text(weatherTemperature(preview.temperature, unit: preview.unit))
            .font(.system(size: scaled(size), weight: .light))
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.65)
    }

    private func condition(_ preview: WeatherPreview) -> some View {
        HStack(spacing: scaled(6)) {
            WeatherGlyph(code: preview.weatherCode, isDay: preview.isDay)
                .frame(width: scaled(20), height: scaled(20))
            Text(weatherCondition(preview.weatherCode))
                .font(.system(size: scaled(11), weight: .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    private func highLow(_ preview: WeatherPreview) -> some View {
        Text("H: \(weatherTemperature(preview.daily.first?.high, unit: preview.unit))  L: \(weatherTemperature(preview.daily.first?.low, unit: preview.unit))")
            .font(.system(size: scaled(10)))
            .foregroundStyle(style == .scenic ? .white.opacity(0.86) : .secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.65)
    }

    private var cityName: some View {
        Text(location.name)
            .font(.system(size: scaled(16), weight: .light))
            .lineLimit(1)
            .minimumScaleFactor(0.65)
    }

    private func scaled(_ value: CGFloat) -> CGFloat { value * contentScale }
}

private struct WeatherWidgetLayout: View {
    let location: WeatherLocation
    let preview: WeatherPreview
    let settings: WeatherSettings
    private var contentScale: CGFloat { CGFloat(settings.effectiveContentScale) }

    var body: some View {
        switch settings.style {
        case .minimal: minimal
        case .compact: compact
        case .scenic: scenic
        case .icon: icon
        }
    }

    private var icon: some View {
        HStack(spacing: 6) {
            WeatherGlyph(code: preview.weatherCode, isDay: preview.isDay)
                .frame(width: 40, height: 40)
            Text(weatherTemperature(preview.temperature, unit: preview.unit))
                .font(.system(size: 38, weight: .light))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .fixedSize()
        .anchorPreference(key: LayoutContentBoundsPreference.self, value: .bounds) { $0 }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var minimal: some View {
        ViewThatFits(in: .vertical) {
            minimalContent(
                glyphSize: scaled(72),
                temperatureBaseSize: 76,
                conditionFontSize: scaled(18),
                rangeFontSize: scaled(16),
                spacing: 20,
                bodySpacing: scaled(3),
                forecastScale: contentScale
            )
            // The launcher keeps Minimal at a fixed 264 pt (or 384 pt with the
            // forecast). Preserve the larger title and condition type, then make
            // room by tightening the temperature and symbol before clipping.
            minimalContent(
                glyphSize: min(scaled(72), 96),
                temperatureBaseSize: fittedMinimalTemperatureBaseSize,
                conditionFontSize: min(scaled(18), 26),
                rangeFontSize: min(scaled(16), 22),
                spacing: 8,
                bodySpacing: 2,
                forecastScale: min(contentScale, 1.25)
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func minimalContent(
        glyphSize: CGFloat,
        temperatureBaseSize: CGFloat,
        conditionFontSize: CGFloat,
        rangeFontSize: CGFloat,
        spacing: CGFloat,
        bodySpacing: CGFloat,
        forecastScale: CGFloat
    ) -> some View {
        VStack(alignment: .leading, spacing: spacing) {
            locationHeader
            HStack(alignment: .top, spacing: min(scaled(18), 18)) {
                WeatherGlyph(code: preview.weatherCode, isDay: preview.isDay)
                    .frame(width: glyphSize, height: glyphSize)
                VStack(alignment: .leading, spacing: bodySpacing) {
                    temperatureText(size: temperatureBaseSize)
                    Text(weatherCondition(preview.weatherCode))
                        .font(.system(size: conditionFontSize))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    if settings.showTemperature {
                        highLow
                            .font(.system(size: rangeFontSize))
                            .lineLimit(1)
                            .minimumScaleFactor(0.65)
                    }
                }
            }
            if settings.showDaily, !preview.daily.isEmpty {
                Divider().opacity(0.55)
                WeatherDayForecast(days: Array(preview.daily.prefix(5)), unit: preview.unit, timezone: timezone, showTemperature: settings.showTemperature, contentScale: forecastScale)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var compact: some View {
        ViewThatFits(in: .vertical) {
            compactContent(
                glyphSize: scaled(56),
                temperatureBaseSize: 64,
                spacing: scaled(12),
                bodySpacing: scaled(4),
                detailFontSize: scaled(15)
            )
            // Compact's 184 pt launcher frame has 152 pt after padding. This
            // version retains the scaled city and condition labels while fitting
            // the temperature and glyph inside that actual card height.
            compactContent(
                glyphSize: min(scaled(56), 72),
                temperatureBaseSize: fittedCompactTemperatureBaseSize,
                spacing: 4,
                bodySpacing: 2,
                detailFontSize: min(scaled(15), 24)
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func compactContent(
        glyphSize: CGFloat,
        temperatureBaseSize: CGFloat,
        spacing: CGFloat,
        bodySpacing: CGFloat,
        detailFontSize: CGFloat
    ) -> some View {
        VStack(alignment: .leading, spacing: spacing) {
            locationHeader
            HStack(spacing: min(scaled(12), 12)) {
                WeatherGlyph(code: preview.weatherCode, isDay: preview.isDay)
                    .frame(width: glyphSize, height: glyphSize)
                VStack(alignment: .leading, spacing: bodySpacing) {
                    temperatureText(size: temperatureBaseSize)
                    HStack(spacing: min(scaled(7), 7)) {
                        Text(weatherCondition(preview.weatherCode))
                        if settings.showTemperature { highLow }
                    }
                    .font(.system(size: detailFontSize))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var scenic: some View {
        VStack(alignment: .leading, spacing: 0) {
            locationHeader
            Spacer(minLength: 0)
            temperatureText(size: 76)
            Spacer(minLength: 0)
            HStack(alignment: .bottom, spacing: scaled(12)) {
                HStack(spacing: scaled(8)) {
                    WeatherGlyph(code: preview.weatherCode, isDay: preview.isDay)
                        .frame(width: scaled(30), height: scaled(30))
                    Text(weatherCondition(preview.weatherCode))
                        .font(.system(size: scaled(14), weight: .medium))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Spacer(minLength: 0)
                VStack(alignment: .trailing, spacing: scaled(4)) {
                    if settings.showTemperature {
                        highLow
                            .font(.system(size: scaled(14)))
                            .lineLimit(1)
                            .minimumScaleFactor(0.65)
                    }
                    if let updated = preview.updated {
                        Text(updatedTime(updated))
                            .font(.system(size: scaled(10)))
                            .multilineTextAlignment(.trailing)
                            .lineLimit(1)
                            .minimumScaleFactor(0.65)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var locationHeader: some View {
        Text(location.name)
            .font(.system(size: scaled(26), weight: .light))
            .lineLimit(1)
            .minimumScaleFactor(0.65)
    }

    @ViewBuilder private func temperatureText(size: CGFloat) -> some View {
        if settings.showTemperature {
            Text(weatherTemperature(preview.temperature, unit: preview.unit))
                .font(.system(size: scaled(size), weight: .light))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.68)
        }
    }

    /// These are base font sizes because `temperatureText` applies contentScale.
    /// They hold the dense fallback within the launcher card after larger labels
    /// and condition glyphs have claimed their required space.
    private var fittedCompactTemperatureBaseSize: CGFloat {
        CGFloat(WeatherContentSizing.compactFallbackTemperatureSize(scale: settings.effectiveContentScale)) / contentScale
    }

    private var fittedMinimalTemperatureBaseSize: CGFloat {
        CGFloat(WeatherContentSizing.minimalFallbackTemperatureSize(scale: settings.effectiveContentScale)) / contentScale
    }

    private var highLow: some View {
        Text("H: \(weatherTemperature(preview.daily.first?.high, unit: preview.unit))  L: \(weatherTemperature(preview.daily.first?.low, unit: preview.unit))")
            .foregroundStyle(.white.opacity(0.86))
    }

    private var timezone: TimeZone {
        preview.timezone.flatMap(TimeZone.init(identifier:)) ?? .current
    }

    private func updatedTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeZone = timezone
        formatter.setLocalizedDateFormatFromTemplate("EEE MMM d h:mm a")
        return "Updated \(formatter.string(from: date))"
    }

    private func scaled(_ value: CGFloat) -> CGFloat { value * contentScale }
}

private struct WeatherDayForecast: View {
    let days: [WeatherDaily]
    let unit: String
    let timezone: TimeZone
    let showTemperature: Bool
    let contentScale: CGFloat

    var body: some View {
        HStack(spacing: 0) {
            ForEach(days) { day in
                VStack(spacing: contentScale * 5) {
                    Text(weekday(day.time))
                        .font(.system(size: contentScale * 13))
                        .lineLimit(1)
                        .minimumScaleFactor(0.65)
                    WeatherGlyph(code: day.weatherCode, isDay: true)
                        .frame(width: contentScale * 25, height: contentScale * 25)
                    if showTemperature {
                        Text("\(weatherTemperature(day.high, unit: unit))  \(weatherTemperature(day.low, unit: unit))")
                            .font(.system(size: contentScale * 13))
                            .lineLimit(1).minimumScaleFactor(0.55)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func weekday(_ date: Date) -> String {
        var calendar = Calendar.current
        calendar.timeZone = timezone
        if calendar.isDateInToday(date) { return "Today" }
        let formatter = DateFormatter()
        formatter.timeZone = timezone
        formatter.dateFormat = "EEE"
        return formatter.string(from: date)
    }
}

struct WeatherToggleRow: View {
    let title: String
    let isOn: Binding<Bool>

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Toggle(title, isOn: isOn).labelsHidden().toggleStyle(.switch)
        }
    }
}

struct WeatherPickerRow<Content: View, SelectionValue: Hashable>: View {
    let title: String
    let selection: Binding<SelectionValue>
    @ViewBuilder let content: () -> Content

    var body: some View {
        HStack {
            Text(title)
            Spacer(minLength: 12)
            Picker(title, selection: selection, content: content)
                .labelsHidden()
                .frame(width: 132)
        }
    }
}

struct WeatherGlyph: View {
    let code: Int?
    let isDay: Bool?

    var body: some View { Icon(iconName, fallback: "Weather") }

    private var iconName: String {
        guard let code else { return isDay == false ? "weather_moon" : "weather_sun" }
        switch code {
        case 51...67, 80...82, 95...99: return "weather_rain"
        case 1...3, 45, 48, 71...77, 85, 86: return "weather_cloud"
        default: return isDay == false ? "weather_moon" : "weather_sun"
        }
    }
}

struct WeatherIconLabel: View {
    let icon: String
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Icon(icon, fallback: text).frame(width: 17, height: 17)
            Text(text)
        }
    }
}

private func weatherTemperature(_ value: Double?, unit: String) -> String {
    guard let value else { return "—" }
    return "\(value.formatted(.number.precision(.fractionLength(0))))\(unit)"
}

private func weatherCondition(_ code: Int?) -> String {
    guard let code else { return "Unavailable" }
    switch code {
    case 0: return "Clear"
    case 1: return "Mainly clear"
    case 2: return "Partly cloudy"
    case 3: return "Overcast"
    case 45, 48: return "Fog"
    case 51...67, 80...82: return "Rain"
    case 71...77, 85, 86: return "Snow"
    case 95...99: return "Thunderstorm"
    default: return "Unavailable"
    }
}
