package com.webuntis.dashboard.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

// ─── AUTH ─────────────────────────────────────────────────────────────────────

data class LoginResponse(val data: LoginData?)
data class LoginData(val sessionId: String?, val person: Person?, val schoolyearData: SchoolyearData?)
data class Person(val id: Int, val name: String?, val longName: String?, val type: Int)
data class SchoolyearData(val klasse: Klasse?)
data class Klasse(val id: Int, val name: String?)

data class AuthResult(
    val sessionId: String,
    val personType: Int?,
    val personId: Int?,
    // The official WebUntis JSON-RPC "authenticate" response names this field "klasseId",
    // not "classId" — without this alias it always deserializes to null/0, which silently
    // breaks the class-timetable feature (no class id ever gets stored).
    @SerializedName(value = "classId", alternate = ["klasseId"])
    val classId: Int?,
    val personName: String?
)

// ─── JSON-RPC ─────────────────────────────────────────────────────────────────

data class JsonRpcResponse<T>(val result: T?, val error: JsonRpcError?)
data class JsonRpcError(val code: Int, val message: String)

// ─── TIMETABLE JSON-RPC ───────────────────────────────────────────────────────

data class Lesson(
    val id: Int,
    val date: Int,
    val startTime: Int,
    val endTime: Int,
    val kl: List<NamedItem>?,
    val te: List<TeacherItem>?,
    val su: List<SubjectItem>?,
    val ro: List<NamedItem>?,
    val code: String?,
    val lstype: String?,
    val info: String?,
    val substText: String?,
    // Enriched by CalendarEntryDetail v2
    val teachingContent: String? = null,
    val removedTeachers: List<String>? = null,    // teachers with status REMOVED
    val substitutedTeachers: List<String>? = null, // teachers with status SUBSTITUTION
    // Directly from v1 gridEntry — no detail call needed
    val notesForAll: String? = null,
    val replacedSubject: String? = null,
    // True when this entry was filled in from the class plan in COMBINED mode (i.e. it is not
    // part of the logged-in person's own personal timetable). UI uses this to label/style it.
    val isFromClassPlan: Boolean = false
) {
    val isCancelled: Boolean get() = code == "cancelled" || lstype == "cancel"
    val isSubstitution: Boolean get() = code == "irregular" || lstype == "subst"
    val isExam: Boolean get() = lstype == "exam"
    val isExtra: Boolean get() = lstype == "add"
    val subjectName: String get() = su?.firstOrNull()?.name ?: su?.firstOrNull()?.longname ?: "–"
    val teacherNames: String get() = te?.mapNotNull { it.name }?.joinToString(", ") ?: ""
    val roomNames: String get() = ro?.mapNotNull { it.name }?.joinToString(", ") ?: ""

    /** Long name when available, falls back to short name — used with showLongNames setting. */
    val subjectLongName: String
        get() = su?.firstOrNull()?.let { it.longname?.takeIf(String::isNotBlank) ?: it.name } ?: "–"
    val teacherLongNames: String
        get() = te?.mapNotNull { t ->
            (t.longname?.takeIf(String::isNotBlank) ?: t.name)
        }?.joinToString(", ") ?: ""
    val roomLongNames: String
        get() = ro?.mapNotNull { r ->
            (r.longname?.takeIf(String::isNotBlank) ?: r.name)
        }?.joinToString(", ") ?: ""

    /** Returns the appropriate name depending on the showLongNames preference. When [long] is
     *  true and [withShortInParens] is also true, appends the abbreviation, e.g. "Mathematik (M)". */
    fun displaySubject(long: Boolean, withShortInParens: Boolean = false): String {
        val short = subjectName
        val longN = subjectLongName
        if (!long) return short
        return if (withShortInParens && short.isNotBlank() && short != "–" && short != longN) "$longN ($short)" else longN
    }

    fun displayTeachers(long: Boolean, withShortInParens: Boolean = false): String {
        val items = te ?: return ""
        return items.mapNotNull { t ->
            val short = t.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val longN = t.longname?.takeIf(String::isNotBlank) ?: short
            if (!long) short
            else if (withShortInParens && short != longN) "$longN ($short)" else longN
        }.joinToString(", ")
    }

    fun displayRooms(long: Boolean, withShortInParens: Boolean = false): String {
        val items = ro ?: return ""
        return items.mapNotNull { r ->
            val short = r.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val longN = r.longname?.takeIf(String::isNotBlank) ?: short
            if (!long) short
            else if (withShortInParens && short != longN) "$longN ($short)" else longN
        }.joinToString(", ")
    }
    val startTimeFormatted: String get() = formatTime(startTime)
    val endTimeFormatted: String get() = formatTime(endTime)
    private fun formatTime(t: Int): String {
        val s = t.toString().padStart(4, '0')
        return "${s.substring(0, 2)}:${s.substring(2)}"
    }
}

/** One subject as offered by the class plan for the COMBINED-mode overlay picker.
 *  [shortName] is what's actually stored/matched against (Lesson.subjectName uses the same
 *  abbreviation), [longName] is only for display since abbreviations can be ambiguous. */
data class ClassSubjectOption(val shortName: String, val longName: String) {
    /** "Langname (Kürzel)", or just the abbreviation if no distinct long name is known. */
    val displayLabel: String
        get() = if (longName.isNotBlank() && longName != shortName) "$longName ($shortName)" else shortName
}

data class NamedItem(val id: Int?, val name: String?, val longname: String?)
data class TeacherItem(val id: Int?, val name: String?, val orgname: String?, val longname: String?)
data class SubjectItem(val id: Int?, val name: String?, val longname: String?)

// ─── TIMETABLE REST v1 ────────────────────────────────────────────────────────

data class TimetableV1Response(
    val days: List<TimetableV1Day>?,
    val errors: List<Any>?
) {
    fun toLessons(): List<Lesson> = days?.flatMap { day ->
        val dateStr = day.date ?: return@flatMap emptyList()
        val dateInt = dateStr.replace("-", "").toIntOrNull() ?: return@flatMap emptyList()
        (day.gridEntries ?: emptyList()).map { entry -> entry.toLesson(dateInt) }
    } ?: emptyList()
}

data class TimetableV1Day(
    val date: String?,
    val gridEntries: List<TimetableV1Entry>?,
    val dayEntries: List<Any>?
)

data class TimetableV1Entry(
    val ids: List<Int>?,
    val duration: TimetableV1Duration?,
    val type: String?,
    val status: String?,
    val position1: List<TimetableV1Position>?,
    val position2: List<TimetableV1Position>?,
    val position3: List<TimetableV1Position>?,
    val position4: List<TimetableV1Position>?,
    val lessonInfo: String?,
    val substitutionText: String?,
    val lessonText: String?,
    val color: String?,
    val notesAll: String?,          // NOTES_FOR_ALL text, present when icons contains "NOTES"
    val icons: List<String>?        // e.g. ["NOTES"], ["HOMEWORK"]
) {
    fun toLesson(dateInt: Int): Lesson {
        val startT = duration?.start?.drop(11)?.take(5)?.replace(":", "")?.toIntOrNull() ?: 0
        val endT   = duration?.end?.drop(11)?.take(5)?.replace(":", "")?.toIntOrNull() ?: 0

        val teachers = (position1 ?: emptyList()).map { pos ->
            TeacherItem(
                id       = null,
                name     = pos.current?.shortName,
                orgname  = pos.removed?.longName?.takeIf(String::isNotBlank)
                           ?: pos.removed?.shortName,
                longname = pos.current?.longName
            )
        }.filter { it.name != null }

        val subjects = (position2 ?: emptyList()).mapNotNull { it.current }
            .filter { it.type == "SUBJECT" }
            .map { SubjectItem(null, it.shortName, it.longName) }

        val allPos = listOfNotNull(position1, position2, position3, position4)
            .flatten().mapNotNull { it.current }

        val rooms   = allPos.filter { it.type == "ROOM" }
            .map { NamedItem(null, it.shortName, it.longName) }
        val classes = allPos.filter { it.type == "CLASS" }
            .map { NamedItem(null, it.shortName, it.longName) }

        val isCancelled = status == "CANCELLED"
        val isChanged   = status == "CHANGED"
        val isExam      = type == "EXAM"
        val code   = if (isCancelled) "cancelled" else if (isChanged) "irregular" else null
        val lstype = if (isExam) "exam" else if (isCancelled) "cancel" else if (isChanged) "subst" else "ls"

        return Lesson(
            id        = ids?.firstOrNull() ?: 0,
            date      = dateInt,
            startTime = startT,
            endTime   = endT,
            kl        = classes.ifEmpty { null },
            te        = teachers.ifEmpty { null },
            su        = subjects.ifEmpty { null },
            ro        = rooms.ifEmpty { null },
            code      = code,
            lstype    = lstype,
            info      = lessonInfo?.takeIf { it.isNotBlank() },
            substText = substitutionText?.takeIf { it.isNotBlank() },
            // notesAll from the v1 gridEntry is directly available here —
            // no detail API call required for this field.
            notesForAll = notesAll?.takeIf { it.isNotBlank() }
        )
    }
}

data class TimetableV1Duration(val start: String?, val end: String?)
data class TimetableV1Position(val current: TimetableV1Element?, val removed: TimetableV1Element?)
data class TimetableV1Element(
    val type: String?, val status: String?,
    val shortName: String?, val longName: String?, val displayName: String?
)

// ─── CALENDAR ENTRY DETAIL (v2) ───────────────────────────────────────────────

data class CalendarEntryDetailResponse(val calendarEntries: List<CalendarEntryDetail>?)

data class CalendarEntryClass(
    val id: Int?,
    val shortName: String?,
    val longName: String?,
    val displayName: String?,
    val hasTimetable: Boolean?   // true, false
)

data class CalendarEntryDetailTeacher(
    val id: Int?,
    val shortName: String?,
    val longName: String?,
    val displayName: String?,
    val status: String?   // REGULAR, REMOVED, SUBSTITUTION
)

data class CalendarEntryDetailRoom(
    val id: Int?,
    val shortName: String?,
    val longName: String?,
    val displayName: String?,
    val status: String?   // REGULAR, ADDED, REMOVED
)

data class CalendarEntryDetailSubject(
    val id: Int?,
    val shortName: String?,
    val longName: String?,
    val displayName: String?
)

data class CalendarEntryDetail(
    val id: Int?,
    val lessonInfo: String?,
    val substText: String?,
    val notesAll: String?,
    val notesStaff: String?,
    val teachingContent: String?,
    val startDateTime: String?,
    val endDateTime: String?,
    val status: String?,          // TAKING_PLACE, CANCELLED, etc.
    val type: String?,            // NORMAL_TEACHING_PERIOD, SUBSTITUTION, CANCELLATION, etc.
    val color: String?,
    val klasses: List<CalendarEntryClass>?,
    val teachers: List<CalendarEntryDetailTeacher>?,
    val rooms: List<CalendarEntryDetailRoom>?,
    val subject: CalendarEntryDetailSubject?
) {
    val classes: List<Int?>
        get() = klasses?.mapNotNull { it.id ?: it.id } ?: emptyList()

    val removedTeachers: List<String>
        get() = teachers?.filter { it.status == "REMOVED" }
    ?.mapNotNull { it.longName ?: it.shortName } ?: emptyList()

    val substitutedTeachers: List<String>
        get() = teachers?.filter { it.status == "SUBSTITUTION" }
                        ?.mapNotNull { it.longName ?: it.shortName } ?: emptyList()

    val activeTeachers: List<String>
        get() = teachers?.filter { it.status != "REMOVED" }
                        ?.mapNotNull { it.longName ?: it.shortName } ?: emptyList()

    val isCancelled: Boolean get() = status == "CANCELLED" || type == "CANCELLATION"
    val isSubstitution: Boolean get() = type == "SUBSTITUTION" || substitutedTeachers.isNotEmpty()
    val isChanged: Boolean get() = type == "CHANGED_TEACHING_PERIOD"
}

// ─── HOMEWORK ─────────────────────────────────────────────────────────────────

data class HomeworkResponse(val data: HomeworkData?)
data class HomeworkData(
    val homeworks: List<Homework>?,
    val records: List<HomeworkRecord>?,
    val lessons: List<HomeworkLesson>?,
    val teachers: List<HomeworkTeacher>?
)
data class HomeworkRecord(val homeworkId: Int?, val teacherId: Int?, val elementIds: List<Int>?)
data class HomeworkTeacher(val id: Int?, val name: String?)
data class HomeworkLesson(val id: Int?, val subject: String?, val lessonType: String?)

data class HomeworkAttachment(
    val id: String?,
    val name: String?,
    val uploadedFileName: String?,  // original filename
    val contentType: String?,       // MIME type e.g. "application/pdf"
    val size: Long?                 // bytes
)

data class Homework(
    val id: Int,
    val lessonId: Int?,
    val date: Int?,
    val dueDate: Int?,
    val subject: String?,
    val text: String?,
    val remark: String?,
    // Homework attachments are served via api/homeworks/{id}/attachments —
    // NOT the storage-attachment/S3 path used for messages.
    val attachments: List<HomeworkAttachment>? = null
) {
    val hasAttachments: Boolean get() = !attachments.isNullOrEmpty()
    val displayText: String get() = text ?: remark ?: "–"
    val dueDateFormatted: String? get() = dueDate?.let { formatDate(it) }
    private fun formatDate(d: Int): String {
        val s = d.toString()
        return if (s.length == 8) "${s.substring(6)}. ${monthName(s.substring(4,6).toInt())} ${s.substring(0,4)}"
        else s
    }
    private fun monthName(m: Int) = listOf("","Jan","Feb","Mär","Apr","Mai","Jun",
        "Jul","Aug","Sep","Okt","Nov","Dez").getOrElse(m) { "?" }
}

// ─── CLASSBOOK ────────────────────────────────────────────────────────────────

data class ClassbookRow(
    val id: Int, val elementName: String?, val subjectName: String?,
    val creatorName: String?, val createDate: Int?, val createTime: Int?,
    val eventReasonName: String?, val categoryName: String?,
    val text: String?, val elemType: String?
) {
    fun toClassbookEntry() = ClassbookEntry(
        id = id, date = createDate,
        subject = subjectName,
        elementName = elementName,          // long/display name of the element (lesson/subject)
        category = categoryName, entryType = elemType, text = text,
        remark = null, teacher = creatorName, teacherName = creatorName,
        reasonText = eventReasonName, type = elemType, typeName = categoryName, lessonObj = null
    )
}

data class ClassbookResponse(val data: ClassbookData?)
data class ClassbookData(val classRegEntries: List<ClassbookEntry>?)

data class ClassbookEntry(
    val id: Int, val date: Int?, val subject: String?,
    val elementName: String? = null,       // preferred display name when available
    @SerializedName("category")    val category: String?,
    @SerializedName("entryType")   val entryType: String?,
    val text: String?, val remark: String?,
    @SerializedName("teacher")     val teacher: String?,
    @SerializedName("teacherName") val teacherName: String?,
    @SerializedName("reasonText")  val reasonText: String?,
    @SerializedName("type")        val type: String?,
    @SerializedName("typeName")    val typeName: String?,
    @SerializedName("lesson")      val lessonObj: Any?
) {
    val displayText: String     get() = text ?: reasonText ?: remark ?: "–"
    val displayCategory: String get() = typeName ?: category ?: entryType ?: type ?: "Eintrag"
    val displayTeacher: String  get() = teacherName ?: teacher ?: ""
    /** elementName when filled, then subject (shortName). */
    val displaySubjectOrElement: String
        get() = elementName?.takeIf(String::isNotBlank) ?: subject ?: "–"
    val dateFormatted: String?  get() = date?.let {
        val s = it.toString()
        if (s.length == 8) "${s.substring(6)}.${s.substring(4,6)}.${s.substring(0,4)}" else s
    }
}

// ─── EVENTS ───────────────────────────────────────────────────────────────────

data class EventsResponse(val data: EventsData?)
data class EventsData(
    val events: List<SchoolEvent>?,
    @SerializedName("exams") val exams: List<SchoolEvent>?
)

// ── School Year ───────────────────────────────────────────────────────────────
data class SchoolYearDateRange(val start: String, val end: String)
data class SchoolYearInfo(val id: Int, val name: String, val dateRange: SchoolYearDateRange)

data class SchoolEvent(
    val id: Int, val subject: String?, val title: String?,
    val text: String?, val remark: String?,
    val date: Int?, val startTime: Int?, val endTime: Int?,
    @SerializedName("eventType") val eventType: String?,
    @SerializedName("examType")  val examType: String?,
    val isExam: Boolean = false,
    // Long form of [subject], when known — used to show "Fach: Mathematik (M)" alongside the title.
    val subjectLongName: String? = null
) {
    val displayTitle: String get() = title ?: subject ?: examType ?: "–"
    val displayText: String  get() = text ?: remark ?: ""
    /** "Mathematik (M)" when both forms are known and differ, else whichever form is available. */
    val subjectDisplay: String get() = when {
        !subjectLongName.isNullOrBlank() && !subject.isNullOrBlank() && subjectLongName != subject ->
            "$subjectLongName ($subject)"
        !subjectLongName.isNullOrBlank() -> subjectLongName
        else -> subject ?: ""
    }
    val dateLabel: String get() = date?.let { d ->
        val s = d.toString()
        if (s.length == 8) "${s.substring(6)}.${s.substring(4,6)}.${s.substring(0,4)}" else s
    } ?: ""
    val timeLabel: String get() {
        val s = startTime?.let { formatTime(it) } ?: return ""
        val e = endTime?.let   { formatTime(it) } ?: return s
        return "$s – $e"
    }
    private fun formatTime(t: Int): String {
        val s = t.toString().padStart(4, '0')
        return "${s.substring(0,2)}:${s.substring(2)}"
    }
}

data class ExamRpcEntry(
    val id: Int?, val date: Int?, val startTime: Int?, val endTime: Int?,
    val klasseIds: List<Int>?, val subjectId: Int?,
    val subject: String?, val name: String?, val examType: String?
) {
    fun toSchoolEvent(): SchoolEvent? {
        val d = date ?: return null
        return SchoolEvent(
            id = id ?: 0, subject = subject ?: name, title = name,
            text = null, remark = null, date = d,
            startTime = startTime, endTime = endTime,
            eventType = examType ?: "EXAM", examType = examType ?: "EXAM", isExam = true
        )
    }
}

// ─── SESSION ──────────────────────────────────────────────────────────────────

// Response of GET api/rest/view/v1/timetable/filter — used to reliably resolve the
// logged-in person's own class element id via the "preSelected" field.
data class TimetableFilterResponse(
    val resourceType: String?,
    val preSelected: TimetableFilterElement?
)
data class TimetableFilterElement(
    val id: Int?,
    val shortName: String?,
    val longName: String?,
    val displayName: String?
)

// Short/long name lookup built from cached timetable data, used to enrich places that only get
// a short subject/teacher code from their own endpoint (homework, events, messages) with the
// long form as well — matching the "Langform (Kürzel)" style used elsewhere in the app.
data class NameCatalog(
    val subjectLongByShort: Map<String, String> = emptyMap(),
    val teacherLongByShort: Map<String, String> = emptyMap()
) {
    /** "Mathematik (M)" if [raw] (assumed short) is a known subject with a different long name, else [raw] as-is. */
    fun subjectDisplay(raw: String?): String {
        val short = raw?.trim().orEmpty()
        if (short.isBlank()) return ""
        val long = subjectLongByShort[short]
        return if (long != null && long != short) "$long ($short)" else short
    }

    /** Same idea for a teacher name — tries [raw] as a short code first, then as a long name
     *  (some endpoints already return the long form), falling back to [raw] unchanged. */
    fun teacherDisplay(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isBlank()) return ""
        teacherLongByShort[name]?.let { long -> if (long != name) return "$long ($name)" }
        teacherLongByShort.entries.firstOrNull { it.value == name }?.let { (short, _) ->
            if (short != name) return "$name ($short)"
        }
        return name
    }
}

data class SessionData(
    val server: String, val schoolname: String, val username: String,
    val sessionId: String, val personId: Int, val classId: Int,
    val personName: String, val personType: Int = 0
) {
    /** Human-readable account type derived from personType */
    val accountTypeLabel: String get() = when (personType) {
        2    -> "Lehrer"
        5    -> "Schüler"
        12   -> "Eltern"
        else -> "Unbekannt"
    }
    val isParent:  Boolean get() = personType == 12
    val isStudent: Boolean get() = personType == 5
    val isTeacher: Boolean get() = personType == 2
}

// ─── UI STATE ─────────────────────────────────────────────────────────────────

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

// ─── MESSAGES ─────────────────────────────────────────────────────────────────

data class MessagesResponse(
    val incomingMessages: List<Message>?,
    val readConfirmationMessages: List<Message>?
)

data class Attachment(
    val id: String?,   // UUID e.g. "a0ca8e68-bbd8-4aa6-8c69-4858677f8938"
    val name: String?
)

data class AttachmentStorageHeader(val key: String?, val value: String?)

data class AttachmentStorageUrl(
    val downloadUrl: String?,
    val additionalHeaders: List<AttachmentStorageHeader>?
)

/** A single entry in the reply thread, from GET /messages/{id} replyHistory array. */
data class ReplyMessage(
    val id: Int?,
    val subject: String?,
    val content: String?,
    val sender: MessageSender?,
    val sentDateTime: String?,
    val storageAttachments: List<Attachment>? = null
) {
    val sentDateFormatted: String? get() {
        if (sentDateTime.isNullOrBlank()) return null
        val parts = sentDateTime.split("T")
        val date = parts.getOrNull(0)?.split("-")?.reversed()?.joinToString(".") ?: return sentDateTime
        val time = parts.getOrNull(1)?.take(5) ?: ""
        return if (time.isNotEmpty()) "$date, $time Uhr" else date
    }
}

data class Message(
    val id: Int,
    val subject: String?,
    val contentPreview: String?,
    val content: String? = null,           // full body from GET /messages/{id}
    val sender: MessageSender?,
    val sentDateTime: String?,
    val hasAttachments: Boolean?,
    val isMessageRead: Boolean?,
    val isReplyAllowed: Boolean?,
    val isReply: Boolean?,
    /** "INBOX", "SENT", "DRAFT" — set by repository, not from API */
    val storedIn: String? = null,
    /** Recipients for sent/draft messages */
    val recipientPersons: List<MessageRecipient>? = null,
    @com.google.gson.annotations.SerializedName("_accountLabel")
    val accountLabel: String? = null,
    @com.google.gson.annotations.SerializedName("_attachments")
    val attachmentList: List<Attachment>? = null,
    val replyHistory: List<ReplyMessage>? = null,  // populated by getMessageWithAttachments
    /** Draft-specific: attachments stored on the server (from GET /messages/drafts/{id}) */
    val storageAttachments: List<Attachment>? = null
) {
    /** All attachments — prefer storageAttachments (draft detail) over attachmentList (inbox detail) */
    val attachments: List<Attachment> get() =
        storageAttachments?.takeIf { it.isNotEmpty() } ?: attachmentList ?: emptyList()
    val allAttachmentNames: Set<String> get() = attachments.mapNotNull { it.name }.toSet()
    val label: String get() = accountLabel ?: ""
    val isSent:  Boolean get() = storedIn == "SENT"
    val isDraft: Boolean get() = storedIn == "DRAFT"
    val sentDateFormatted: String get() {
        if (sentDateTime.isNullOrBlank()) return ""
        val parts = sentDateTime.split("T")
        val date = parts.getOrNull(0)?.split("-")?.reversed()?.joinToString(".") ?: ""
        val time = parts.getOrNull(1)?.take(5) ?: ""
        // Always show time when available
        return if (time.isNotEmpty()) "$date, $time Uhr" else date
    }
    /** ISO string used for correct descending sort (date + time). Falls back to empty. */
    val sentDateTimeForSorting: String get() = sentDateTime ?: ""
}

data class MessageRecipient(
    val personId: Int?,
    val displayName: String?,
    val userId: Int?,
    val imageUrl: String? = null
)

data class MessageSender(
    val displayName: String?,
    val userId: Int?,
    val imageUrl: String?
)

data class MessagesStatusResponse(val unreadMessagesCount: Int)

// ─── SAVE DRAFT ───────────────────────────────────────────────────────────────

data class SaveDraftRequest(
    val subject: String,
    val content: String,
    val recipientOption: String = "TEACHER",   // API requires this field
    val copyToStudent: Boolean = false,
    val requestConfirmation: Boolean = false,
    val forbidReply: Boolean = false,
    val oneDriveAttachments: List<Any> = emptyList(),
    val hasAttachments: Boolean = false,
    val attachmentIdsToDelete: List<String> = emptyList()
)

// ─── TEACHERS ────────────────────────────────────────────────────────────────

// Used for recipient picker — maps the /messages/recipients/static/persons response
data class RecipientGroup(
    val type: String?,       // "CLASS_TEACHERS", "TEACHERS", "OTHERS"
    val persons: List<RecipientPerson>?
)

data class RecipientPerson(
    val userId: Int,
    val displayName: String?,
    val imageUrl: String?,
    val tags: List<String>? = null
) {
    /** Non-empty tags joined with ", " — e.g. "KL, GL" */
    val tagLabel: String get() = tags?.filter { it.isNotBlank() }?.joinToString(", ") ?: ""
    /** Display name with optional tag hint, e.g. "Mö (KL, GL)" */
    val fullDisplayName: String get() = when {
        tagLabel.isNotBlank() -> "${displayName ?: "?"} ($tagLabel)"
        else -> displayName ?: "?"
    }
}

// Legacy — kept so existing code compiles
data class Teacher(
    val id: Int,
    val name: String?,
    val longName: String?,
    val title: String? = null,
    val active: Boolean? = true
) {
    val displayName: String get() = when {
        !longName.isNullOrBlank() && !name.isNullOrBlank() -> "$longName ($name)"
        !longName.isNullOrBlank() -> longName!!
        !name.isNullOrBlank()     -> name!!
        else -> "Unbekannt"
    }
}



// ─── ABSENCES ─────────────────────────────────────────────────────────────────

data class AbsencesResponse(val data: AbsencesData?)
data class AbsencesData(val absences: List<Absence>?)

data class Absence(
    val id: Int,
    val startDate: Int?, val endDate: Int?,
    val startTime: Int?, val endTime: Int?,
    val reason: String?, val text: String?,
    val studentName: String?,
    val excuseStatus: String?,
    val isExcused: Boolean?,
    val createdUser: String?, val updatedUser: String?,
    val canEdit: Boolean? = false,
    val reasonId: Int? = 0
) {
    val dateLabel: String get() {
        val s = startDate?.toString() ?: return ""
        val e = endDate?.toString() ?: s
        val fmt = { d: String ->
            if (d.length == 8) "${d.substring(6)}.${d.substring(4,6)}.${d.substring(0,4)}" else d
        }
        return if (s == e) fmt(s) else "${fmt(s)} – ${fmt(e)}"
    }
    val timeLabel: String get() {
        fun fmt(t: Int) = t.toString().padStart(4,'0').let { "${it.take(2)}:${it.drop(2)}" }
        val s = startTime?.let { fmt(it) } ?: return ""
        val e = endTime?.let   { fmt(it) } ?: return s
        return "$s – $e"
    }
    val isFullDay: Boolean get() = startTime == 800 && (endTime == 1600 || endTime == 2000)
}
// ─── ABSENCES META ────────────────────────────────────────────────────────────

data class AbsenceReason(
    @SerializedName("id")   val id: Int,
    @SerializedName("name") val name: String
)

data class AbsencesMetaData(
    @SerializedName("canReportAbsence")     val canReportAbsence: Boolean? = null,
    @SerializedName("absenceReasons")       val absenceReasons: List<AbsenceReason>? = null,
    @SerializedName("defaultAbsenceReason") val defaultAbsenceReason: Int? = null,
    @SerializedName("defaultDate")          val defaultDate: Int? = null,
    @SerializedName("defaultStartTime")     val defaultStartTime: Int? = null,
    @SerializedName("defaultEndTime")       val defaultEndTime: Int? = null
)

data class AbsencesMetaResponse(
    @SerializedName("data") val data: AbsencesMetaData? = null
)

// ─── TIMEGRID ─────────────────────────────────────────────────────────────────

@Keep
data class TimegridRow(
    @SerializedName("period")    val period: Int = 0,
    @SerializedName("startTime") val startTime: Int = 0,
    @SerializedName("endTime")   val endTime: Int = 0,
    @SerializedName("description") val description: String? = null
)

@Keep
data class TimegridData(
    @SerializedName("schoolyearId") val schoolyearId: Int = 0,
    @SerializedName("rows") val rows: List<TimegridRow> = emptyList()
)

@Keep
data class TimegridResponse(
    @SerializedName("data") val data: TimegridData? = null
)
