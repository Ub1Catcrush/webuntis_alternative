package com.webuntis.dashboard.model

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
    val substText: String?
) {
    val isCancelled: Boolean get() = code == "cancelled" || lstype == "cancel"
    val isSubstitution: Boolean get() = code == "irregular" || lstype == "subst"
    val isExam: Boolean get() = lstype == "exam"
    val isExtra: Boolean get() = lstype == "add"
    val subjectName: String get() = su?.firstOrNull()?.name ?: su?.firstOrNull()?.longname ?: "–"
    val teacherNames: String get() = te?.mapNotNull { it.name }?.joinToString(", ") ?: ""
    val roomNames: String get() = ro?.mapNotNull { it.name }?.joinToString(", ") ?: ""
    val startTimeFormatted: String get() = formatTime(startTime)
    val endTimeFormatted: String get() = formatTime(endTime)
    private fun formatTime(t: Int): String {
        val s = t.toString().padStart(4, '0')
        return "${s.substring(0, 2)}:${s.substring(2)}"
    }
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
    val color: String?
) {
    fun toLesson(dateInt: Int): Lesson {
        val startT = duration?.start?.drop(11)?.take(5)?.replace(":", "")?.toIntOrNull() ?: 0
        val endT   = duration?.end?.drop(11)?.take(5)?.replace(":", "")?.toIntOrNull() ?: 0

        val teachers = (position1 ?: emptyList()).map { pos ->
            TeacherItem(
                id       = null,
                name     = pos.current?.shortName,
                orgname  = pos.removed?.shortName,
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
            substText = substitutionText?.takeIf { it.isNotBlank() }
        )
    }
}

data class TimetableV1Duration(val start: String?, val end: String?)
data class TimetableV1Position(val current: TimetableV1Element?, val removed: TimetableV1Element?)
data class TimetableV1Element(
    val type: String?, val status: String?,
    val shortName: String?, val longName: String?, val displayName: String?
)

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

data class Homework(
    val id: Int,
    val lessonId: Int?,
    val date: Int?,
    val dueDate: Int?,
    val subject: String?,
    val text: String?,
    val remark: String?,
    val attachments: List<Any>?
) {
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
        id = id, date = createDate, subject = subjectName,
        category = categoryName, entryType = elemType, text = text,
        remark = null, teacher = creatorName, teacherName = creatorName,
        reasonText = eventReasonName, type = elemType, typeName = categoryName, lessonObj = null
    )
}

data class ClassbookResponse(val data: ClassbookData?)
data class ClassbookData(val classRegEntries: List<ClassbookEntry>?)

data class ClassbookEntry(
    val id: Int, val date: Int?, val subject: String?,
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

data class SchoolEvent(
    val id: Int, val subject: String?, val title: String?,
    val text: String?, val remark: String?,
    val date: Int?, val startTime: Int?, val endTime: Int?,
    @SerializedName("eventType") val eventType: String?,
    @SerializedName("examType")  val examType: String?,
    val isExam: Boolean = false
) {
    val displayTitle: String get() = title ?: subject ?: examType ?: "–"
    val displayText: String  get() = text ?: remark ?: ""
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

data class SessionData(
    val server: String, val schoolname: String, val username: String,
    val sessionId: String, val personId: Int, val classId: Int,
    val personName: String, val personType: Int = 0
)

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

data class Message(
    val id: Int,
    val subject: String?,
    val contentPreview: String?,
    val sender: MessageSender?,
    val sentDateTime: String?,
    val hasAttachments: Boolean?,
    val isMessageRead: Boolean?,
    val isReplyAllowed: Boolean?,
    val isReply: Boolean?
) {
    val sentDateFormatted: String get() {
        if (sentDateTime.isNullOrBlank()) return ""
        val parts = sentDateTime.split("T")
        val date = parts.getOrNull(0)?.split("-")?.reversed()?.joinToString(".") ?: ""
        val time = parts.getOrNull(1)?.take(5) ?: ""
        return if (time.isNotEmpty()) "$date $time" else date
    }
}

data class MessageSender(
    val displayName: String?,
    val userId: Int?,
    val imageUrl: String?
)

data class MessagesStatusResponse(val unreadMessagesCount: Int)

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
    val createdUser: String?, val updatedUser: String?
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
