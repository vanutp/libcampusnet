package dev.vanutp.libcampusnet

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
data class UserInfo(
    val matriculationNumber: String,
    val firstName: String,
    val middleName: String?,
    val lastName: String,
    val major: String,
    val username: String,
    val dateOfBirth: LocalDate,
    val country: String,
)

typealias CourseId = String
typealias SemesterId = String

const val UNKNOWN_SEMESTER = "unknown"

@Serializable
data class Course(
    val id1: String,
    val id2: String,
    val code: CourseId,
    val name: String,
    val instructor: String? = null,
    val description: String? = null,
    val credits: Double? = null,
    // TODO: fetch these from the course list
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)

interface ICache {
    var userInfo: UserInfo?
    val courses: MutableMap<SemesterId, MutableMap<CourseId, Course>>
    val courseToSemesterMap: MutableMap<CourseId, SemesterId>

    suspend fun save() {}

    fun getCourse(courseId: CourseId): Course? {
        val semesterId = courseToSemesterMap[courseId] ?: return null
        return courses[semesterId]?.get(courseId)
    }
}

@Serializable
data class InMemoryCache(
    override var userInfo: UserInfo? = null,
    override val courses: MutableMap<SemesterId, MutableMap<CourseId, Course>> = mutableMapOf(UNKNOWN_SEMESTER to mutableMapOf()),
    override val courseToSemesterMap: MutableMap<CourseId, SemesterId> = mutableMapOf(),
) : ICache
