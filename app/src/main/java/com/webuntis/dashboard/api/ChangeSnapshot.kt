package com.webuntis.dashboard.api

/**
 * Lightweight "what did we already notify about" state for [PlanChangeCheckWorker],
 * persisted as JSON in [SessionManager.lastNotifiedSnapshot]. Deliberately minimal — just
 * enough to detect NEW changes on the next check, nothing sensitive or bulky.
 */
data class ChangeSnapshot(
    // "yyyyMMdd-HHmm-SubjectShortName" -> "cancelled" | "subst" | "roomchange" | "normal"
    val lessonStatus: Map<String, String> = emptyMap(),
    val messageIds: Set<Int> = emptySet(),
    val homeworkIds: Set<Int> = emptySet(),
    val classbookIds: Set<Int> = emptySet()
)
