package app.releaf.mobile.features.notepad.recents.data

import app.releaf.mobile.features.notepad.recents.model.RecentsDayStats

/**
 * Source of truth for the Recents screen. The host app's repository should
 * implement this interface against its real persistence; the screen only ever
 * sees a [RecentsDayStats].
 */
interface DayStatsRepo {
    fun getDayStats(): RecentsDayStats
}

/** Default impl backed by [MockData] for previews and standalone runs. */
class MockDayStatsRepo : DayStatsRepo {
    override fun getDayStats(): RecentsDayStats = MockData.dayStats
}
