import Foundation

/// Read-side abstraction the screen uses to pull data. Hand any conforming
/// implementation to `RecentsScreen` to swap in real persistence.
public protocol DayStatsRepo {
    func load() -> RecentsDayStats
}

/// Default implementation — returns the bundled mock payload.
public struct MockDayStatsRepo: DayStatsRepo {
    public init() {}
    public func load() -> RecentsDayStats { MockData.dayStats }
}
