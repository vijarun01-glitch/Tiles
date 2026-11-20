package com.matte.launcher

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Main-thread snapshot. Notification contents are never retained. */
internal object TileNotificationCounts {
    private var counts: Map<String, Int> = emptyMap()
    private val observers = LinkedHashSet<() -> Unit>()

    fun count(packageName: String) = counts[packageName] ?: 0
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun remove(observer: () -> Unit) { observers.remove(observer) }

    fun replace(next: Map<String, Int>) {
        if (next == counts) return
        counts = next.toMap()
        observers.toList().forEach { it() }
    }
}

class TileNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private val refresh = Runnable { if (connected) refreshCounts() }

    override fun onListenerConnected() {
        connected = true
        refresh.run()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = scheduleRefresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = scheduleRefresh()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) = scheduleRefresh()

    override fun onListenerDisconnected() = clearCounts()

    override fun onDestroy() {
        clearCounts()
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        if (!connected) return
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    private fun clearCounts() {
        connected = false
        handler.removeCallbacks(refresh)
        TileNotificationCounts.replace(emptyMap())
    }

    private fun refreshCounts() {
        val active = try { activeNotifications.orEmpty() } catch (_: SecurityException) { emptyArray() }
        val rankingMap = currentRanking ?: return
        val ranking = Ranking()
        val counts = HashMap<String, Int>()
        val summaries = HashMap<Pair<String, String>, Int>()
        val groupsWithChildren = HashSet<Pair<String, String>>()
        val user = Process.myUserHandle()
        fun add(pkg: String, number: Int) {
            counts[pkg] = ((counts[pkg] ?: 0) + number.coerceIn(1, 100)).coerceAtMost(100)
        }

        for (entry in active) {
            if (entry.user != user || entry.isOngoing || entry.packageName == packageName) continue
            val notification = entry.notification
            if (!rankingMap.getRanking(entry.key, ranking) || !ranking.canShowBadge()) continue
            val group = entry.packageName to entry.groupKey
            if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
                summaries[group] = maxOf(summaries[group] ?: 0, notification.number.coerceAtLeast(1))
            } else {
                groupsWithChildren.add(group)
                add(entry.packageName, notification.number)
            }
        }
        for ((group, number) in summaries) {
            if (group !in groupsWithChildren) add(group.first, number)
        }
        TileNotificationCounts.replace(counts)
    }
}
