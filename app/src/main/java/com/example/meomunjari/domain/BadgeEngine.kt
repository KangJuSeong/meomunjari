package com.example.meomunjari.domain

import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.UserData

enum class BadgeType(val title: String, val condition: String, val target: Int) {
    PIONEER("개척자", "새 스팟을 처음 등록", 1),
    EXPLORER("탐험가", "다른 사람의 스팟 10곳 방문", 10),
    FOUR_SEASONS("사계절", "봄·여름·가을·겨울 모두 차박", 4),
    REGION("지역 정복", "한 도(道)의 스팟 5곳 방문", 5),
    THEME_MASTER("테마 마스터", "같은 해시태그 스팟 5곳 방문", 5),
    RECORDER("기록왕", "실측 리포트 10회 기여 (첫 계절 기록은 2배)", 10),
    MENTOR("멘토", "내 세팅을 다른 사람이 10회 불러옴", 10),
}

data class BadgeProgress(
    val type: BadgeType,
    val current: Int,
    /** "강원도", "#별보기 → 별 헌터" 처럼 진행 대상 설명. */
    val detail: String = "",
) {
    val earned: Boolean get() = current >= type.target
    val ratio: Float get() = (current.toFloat() / type.target).coerceIn(0f, 1f)
}

enum class Grade(val label: String, val minPoints: Int) {
    NEWBIE("차박 새내기", 0),
    CAMPER("차박러", 100),
    EXPERT("차박 고수", 300),
    KING("차박왕", 700),
    ;

    val next: Grade? get() = entries.getOrNull(ordinal + 1)
}

data class MemberStatus(val points: Int, val grade: Grade, val badges: List<BadgeProgress>) {
    val earnedCount: Int get() = badges.count { it.earned }

    fun progressToNext(): Float {
        val next = grade.next ?: return 1f
        return ((points - grade.minPoints).toFloat() / (next.minPoints - grade.minPoints)).coerceIn(0f, 1f)
    }
}

/** 사용자 활동 전체로 뱃지 진행도·포인트·등급을 다시 계산한다(이벤트마다 호출). */
object BadgeEngine {
    const val POINT_REGISTER = 30
    const val POINT_VISIT = 10
    const val POINT_REPORT = 15
    const val POINT_REVIEW = 5
    const val POINT_BADGE = 20

    private val themeTitles = mapOf(
        "#별보기" to "별 헌터",
        "#바다뷰" to "바다 수집가",
        "#일출맛집" to "일출 사냥꾼",
        "#반려견동반" to "댕댕이 단짝",
        "#산속" to "산골 은둔자",
    )

    fun evaluate(user: UserData, spots: List<Spot>): MemberStatus {
        val byId = spots.associateBy { it.id }
        val visitedIds = user.visits.map { it.spotId }.distinct()
        val visitedSpots = visitedIds.mapNotNull { byId[it] }

        val othersVisited = visitedSpots.count { it.registeredBy != user.nickname }
        val seasons = user.visits.map { ReportAggregator.season(it.month) }.distinct()
        val regionBest = visitedSpots.groupBy { it.region }.maxByOrNull { it.value.size }
        // 가장 많이 다녀온 테마. 같으면 그 스팟들에 더 많이 붙은(대표성 높은) 태그.
        val themeBest = visitedSpots
            .flatMap { spot -> spot.tags.map { Triple(it.tag, spot.id, it.count) } }
            .groupBy { it.first }
            .map { (tag, list) -> Triple(tag, list.map { it.second }.distinct().size, list.sumOf { it.third }) }
            .maxWithOrNull(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
            ?.let { it.first to it.second }

        val badges = listOf(
            BadgeProgress(BadgeType.PIONEER, user.registeredSpotIds.size),
            BadgeProgress(BadgeType.EXPLORER, othersVisited),
            BadgeProgress(BadgeType.FOUR_SEASONS, seasons.size, seasons.joinToString("·") { it.label }),
            BadgeProgress(BadgeType.REGION, regionBest?.value?.size ?: 0, regionBest?.key.orEmpty()),
            BadgeProgress(
                BadgeType.THEME_MASTER,
                themeBest?.second ?: 0,
                themeBest?.first?.let { "$it → ${themeTitles[it] ?: "${it.removePrefix("#")} 마스터"}" }.orEmpty(),
            ),
            BadgeProgress(BadgeType.RECORDER, user.reportCredits),
            BadgeProgress(BadgeType.MENTOR, user.presetLoadsReceived),
        )

        val points = user.registeredSpotIds.size * POINT_REGISTER +
            user.visits.size * POINT_VISIT +
            user.reportCount * POINT_REPORT +
            user.reviewCount * POINT_REVIEW +
            badges.count { it.earned } * POINT_BADGE

        return MemberStatus(points, gradeFor(points), badges)
    }

    fun gradeFor(points: Int): Grade = Grade.entries.last { points >= it.minPoints }
}
