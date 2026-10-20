import Foundation

enum WeatherProviderError: LocalizedError {
    case invalidSearch
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .invalidSearch: "Enter a city name to search."
        case .invalidResponse: "Weather data was unavailable."
        }
    }
}

struct WeatherProvider {
    func search(_ query: String) async throws -> [WeatherSearchResult] {
        let name = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { throw WeatherProviderError.invalidSearch }

        var components = URLComponents(string: "https://geocoding-api.open-meteo.com/v1/search")!
        components.queryItems = [
            URLQueryItem(name: "name", value: name),
            URLQueryItem(name: "count", value: "8"),
            URLQueryItem(name: "language", value: "en"),
            URLQueryItem(name: "format", value: "json")
        ]
        let (data, response) = try await URLSession.shared.data(from: components.url!)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw WeatherProviderError.invalidResponse
        }

        let decoded = try JSONDecoder().decode(GeocodeResponse.self, from: data)
        return (decoded.results ?? []).compactMap { result in
            guard result.latitude.isFinite, result.longitude.isFinite else { return nil }
            return WeatherSearchResult(
                id: "geo-\(result.id)",
                name: result.name,
                country: result.country ?? "",
                administrativeArea: result.admin1,
                latitude: result.latitude,
                longitude: result.longitude
            )
        }
    }

    func preview(for location: WeatherLocation, unit: WeatherUnit) async throws -> WeatherPreview {
        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        components.queryItems = [
            URLQueryItem(name: "latitude", value: "\(location.latitude)"),
            URLQueryItem(name: "longitude", value: "\(location.longitude)"),
            URLQueryItem(name: "current", value: "temperature_2m,weather_code,is_day"),
            URLQueryItem(name: "hourly", value: "temperature_2m,weather_code,is_day"),
            URLQueryItem(name: "daily", value: "weather_code,temperature_2m_max,temperature_2m_min"),
            URLQueryItem(name: "temperature_unit", value: unit.rawValue),
            URLQueryItem(name: "timezone", value: "auto"),
            URLQueryItem(name: "timeformat", value: "unixtime"),
            URLQueryItem(name: "forecast_days", value: "5")
        ]
        let (data, response) = try await URLSession.shared.data(from: components.url!)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw WeatherProviderError.invalidResponse
        }

        let decoded = try JSONDecoder().decode(ForecastResponse.self, from: data)
        let updated = decoded.current?.time.map(Date.init(timeIntervalSince1970:))
        let hourly = decoded.hourly?.readings(after: updated).prefix(5) ?? []
        let daily = decoded.daily?.readings.prefix(5) ?? []
        return WeatherPreview(
            temperature: decoded.current?.temperature2m,
            weatherCode: decoded.current?.weatherCode,
            isDay: decoded.current?.isDay.map { $0 == 1 },
            updated: updated,
            timezone: decoded.timezone,
            unit: decoded.currentUnits?.temperature2m ?? (unit == .celsius ? "°C" : "°F"),
            hourly: Array(hourly),
            daily: Array(daily)
        )
    }
}

private struct GeocodeResponse: Decodable {
    let results: [GeocodeResult]?
}

private struct GeocodeResult: Decodable {
    let id: Int
    let name: String
    let country: String?
    let admin1: String?
    let latitude: Double
    let longitude: Double
}

private struct ForecastResponse: Decodable {
    let timezone: String?
    let current: Current?
    let currentUnits: CurrentUnits?
    let hourly: Hourly?
    let daily: Daily?

    struct Current: Decodable {
        let time: TimeInterval?
        let temperature2m: Double?
        let weatherCode: Int?
        let isDay: Int?

        enum CodingKeys: String, CodingKey {
            case time
            case temperature2m = "temperature_2m"
            case weatherCode = "weather_code"
            case isDay = "is_day"
        }
    }

    struct CurrentUnits: Decodable {
        let temperature2m: String?
        enum CodingKeys: String, CodingKey { case temperature2m = "temperature_2m" }
    }

    struct Hourly: Decodable {
        let time: [TimeInterval]
        let temperature2m: [Double?]
        let weatherCode: [Int?]
        let isDay: [Int?]

        enum CodingKeys: String, CodingKey {
            case time
            case temperature2m = "temperature_2m"
            case weatherCode = "weather_code"
            case isDay = "is_day"
        }

        func readings(after current: Date?) -> [WeatherHourly] {
            time.enumerated().compactMap { index, timestamp in
                let date = Date(timeIntervalSince1970: timestamp)
                guard current == nil || date >= current! else { return nil }
                return WeatherHourly(
                    time: date,
                    temperature: temperature2m[safe: index] ?? nil,
                    weatherCode: weatherCode[safe: index] ?? nil,
                    isDay: isDay[safe: index].map { $0 == 1 }
                )
            }
        }
    }

    struct Daily: Decodable {
        let time: [TimeInterval]
        let weatherCode: [Int?]
        let high: [Double?]
        let low: [Double?]

        enum CodingKeys: String, CodingKey {
            case time
            case weatherCode = "weather_code"
            case high = "temperature_2m_max"
            case low = "temperature_2m_min"
        }

        var readings: [WeatherDaily] {
            time.enumerated().map { index, timestamp in
                WeatherDaily(
                    time: Date(timeIntervalSince1970: timestamp),
                    weatherCode: weatherCode[safe: index] ?? nil,
                    high: high[safe: index] ?? nil,
                    low: low[safe: index] ?? nil
                )
            }
        }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
