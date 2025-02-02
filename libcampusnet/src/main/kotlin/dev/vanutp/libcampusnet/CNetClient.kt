package dev.vanutp.libcampusnet

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toJavaLocalDate
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.transform.recurrence.Frequency
import net.fortuna.ical4j.util.RandomUidGenerator
import org.slf4j.LoggerFactory
import java.time.temporal.Temporal
import java.util.*


enum class AuthStatus {
    NO_AUTH,
    DSF_AUTH,
    CNET_AUTH,
}

class CNetClient private constructor(
    private val loginCreds: LoginCredentials,
    private val cache: ICache,
) {
    companion object {
        const val DSF_ADDR = "https://campusnet-dsf.constructor.university"
        const val CNET_ADDR = "https://campusnet.constructor.university"
        const val CNET_ENTRYPOINT = "$CNET_ADDR/scripts/mgrqispi.dll"
        val DATE_FMT = LocalDate.Format {
            dayOfMonth()
            char('.')
            monthNumber()
            char('.')
            year()
        }
        val TIME_ZONE = "Europe/Berlin"

        suspend fun create(
            loginCreds: LoginCredentials,
            cache: ICache,
            initialSessionCreds: DsfSessionCredentials? = null,
            initialCnetCreds: CnetSessionCredentials? = null,
        ): CNetClient {
            if (initialCnetCreds != null && initialSessionCreds == null) {
                throw IllegalArgumentException("initialCnetCreds can only be provided together with initialSessionCreds")
            }
            val client = CNetClient(loginCreds, cache)
            if (initialSessionCreds != null) {
                client.cookies.addCookie(DSF_ADDR, Cookie("idsrv", initialSessionCreds.idsrv))
                client.cookies.addCookie(DSF_ADDR, Cookie("idsrvC1", initialSessionCreds.idsrvC1))
                client.cookies.addCookie(DSF_ADDR, Cookie("idsrvC2", initialSessionCreds.idsrvC2))
                client.authStatus = AuthStatus.DSF_AUTH
            }
            if (initialCnetCreds != null) {
                client.cnetSid = initialCnetCreds.sid
                client.cookies.addCookie(CNET_ENTRYPOINT, Cookie("cnsc", initialCnetCreds.cnsc))
                client.authStatus = AuthStatus.CNET_AUTH
            }
            client.ensureSession()
            return client
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    private var cnetSid: String? = null

    private val cookies = AcceptAllCookiesStorage()
    private val client = HttpClient(CIO) {
        install(HttpCookies) {
            storage = cookies
        }
        defaultRequest {
            url(CNET_ADDR)
        }
        followRedirects = false
    }
    private var authStatus: AuthStatus = AuthStatus.NO_AUTH

    private suspend fun getStartPageRedirect(): String {
        val loginRedirect1 = client.get("/")
        val loginRedirect1Target =
            Regex("<meta http-equiv=\"refresh\" content=\"0; URL=(.*?)\" />").find(loginRedirect1.bodyAsText(Charsets.UTF_16))!!.groupValues[1]
        logger.info("[redirect] start 1 -> $loginRedirect1Target")

        val loginRedirect2 = client.get(loginRedirect1Target)
        val loginRedirect2Target =
            Regex("window\\.location\\.href = '([^']*?)'").find(loginRedirect2.bodyAsText())!!.groupValues[1]
        logger.info("[redirect] start 2 -> $loginRedirect2Target")

        val loginRedirect3 = client.get(loginRedirect2Target)
        val loginRedirect3Soup = Ksoup.parse(loginRedirect3.bodyAsText())
        val loginRedirect3Target = loginRedirect3Soup.selectFirst("#logIn_btn")!!.attr("href")
        logger.info("[redirect] start 3 -> $loginRedirect3Target")

        return loginRedirect3Target
    }

    private suspend fun doFinalRedirects(cnetLogincheckAddr: Url) {
        val redirect1 = client.get(cnetLogincheckAddr)
        val redirect1Target = redirect1.headers["Refresh"]!!.removePrefix("0; URL=")
        logger.info("[redirect] final 1 -> $redirect1Target")

        val redirect2 = client.get(redirect1Target)
        val redirect2Target =
            Url(CNET_ADDR + Regex("window\\.location\\.href = '([^']*?)'").find(redirect2.bodyAsText())!!.groupValues[1])
        logger.info("[redirect] final 2 -> $redirect2Target")

        cnetSid = redirect2Target.parameters["ARGUMENTS"]!!.split(',')[0]

        authStatus = AuthStatus.CNET_AUTH
    }

    private suspend fun login(dsfLoginUrl: String? = null) {
        val resolvedDsfLoginUrl = dsfLoginUrl ?: getStartPageRedirect()

        val redirect1 = client.get(resolvedDsfLoginUrl)
        val redirect1Target = Url(redirect1.headers[HttpHeaders.Location]!!)
        logger.info("[redirect] login 1 -> $redirect1Target")

        val redirect2 = client.get(redirect1Target)
        val loginFormToken =
            Ksoup.parse(redirect2.bodyAsText()).selectFirst("input[name=__RequestVerificationToken]")!!.value()

        val redirect3: HttpResponse = client.submitForm(
            url = redirect1Target.toString(),
            formParameters = parameters {
                append("ReturnUrl", redirect1Target.parameters["ReturnUrl"]!!)
                append("CancelUrl", "")
                append("Username", loginCreds.username)
                append("Password", loginCreds.password)
                append("__RequestVerificationToken", loginFormToken)
                append("RememberLogin", "true")
                append("button", "login")
            }
        )
        val redirect3Target = Url(DSF_ADDR + redirect3.headers[HttpHeaders.Location]!!)
        logger.info("[redirect] login 3 -> $redirect3Target")

        val redirect4 = client.get(redirect3Target)
        val redirect4Target = Url(redirect4.headers[HttpHeaders.Location]!!)
        logger.info("[redirect] login 4 -> $redirect4Target")

        doFinalRedirects(redirect4Target)
    }

    private suspend fun refresh(dsfLoginUrl: String? = null) {
        val resolvedDsfLoginUrl = dsfLoginUrl ?: getStartPageRedirect()
        val redirect = client.get(resolvedDsfLoginUrl)
        if (redirect.status.value != 302) {
            login(resolvedDsfLoginUrl)
            return
        }
        val redirectTarget = Url(redirect.headers[HttpHeaders.Location]!!)
        logger.info("[redirect] refresh -> $redirectTarget")

        doFinalRedirects(redirectTarget)
    }

    private suspend fun ensureSession() {
        when (authStatus) {
            AuthStatus.NO_AUTH -> login()
            AuthStatus.DSF_AUTH -> refresh()
            AuthStatus.CNET_AUTH -> return
        }
    }

    suspend fun getDsfCredentials(): DsfSessionCredentials {
        if (authStatus == AuthStatus.NO_AUTH) {
            login()
        }
        val dsfCookies = cookies.get(Url(DSF_ADDR))
        return DsfSessionCredentials(
            dsfCookies["idsrv"]!!.value,
            dsfCookies["idsrvC1"]!!.value,
            dsfCookies["idsrvC2"]!!.value,
        )
    }

    suspend fun getCnetCredentials(): CnetSessionCredentials {
        ensureSession()
        val cnetCookies = cookies.get(Url(CNET_ENTRYPOINT))
        return CnetSessionCredentials(
            cnetSid!!,
            cnetCookies["cnsc"]!!.value,
        )
    }

    private suspend fun cnetRequest(prg: String, args: List<String>, isRetry: Boolean = false): Document {
        val resp = client.get(CNET_ENTRYPOINT) {
            parameter("APPNAME", "CampusNet")
            parameter("PRGNAME", prg)
            parameter("ARGUMENTS", "$cnetSid," + args.joinToString(","))
        }
        val respText = resp.bodyAsText()
        val soup = Ksoup.parse(respText)
        // TODO: use soup to check for errors
        if (respText.contains("<h1>Access denied</h1>")) {
            if (isRetry) {
                throw IllegalStateException("Got access denied from cnet")
            }
            val dsfLoginUrl = soup.selectFirst("#logIn_btn")!!.attr("href")
            refresh(dsfLoginUrl)
            return cnetRequest(prg, args, true)
        }
        return soup
    }

    suspend fun getUserInfo(): UserInfo {
        cache.userInfo?.let { return it }
        ensureSession()
        val soup = cnetRequest("PERSADDRESS", listOf("-N000068", "-A"))
        val data = mutableMapOf<String, String>()
        val infoTable = soup.selectFirst("table.persaddrTbl")!!
        for (row in infoTable.select("tr")) {
            val cells = row.select("td")
            if (cells.size < 2) {
                continue
            }
            val key = cells[0].text().trim()
            if (key == "") {
                continue
            }
            val value = cells[1].text().trim()
            data[key] = value
        }
        return UserInfo(
            matriculationNumber = data["Matriculation number"]!!,
            firstName = data["First name"]!!,
            middleName = data["Middle name"]!!.takeIf { it != "" },
            lastName = data["Last name"]!!,
            major = data["Studies"]!!,
            username = data["Username"]!!.lowercase(),
            dateOfBirth = LocalDate.parse(data["Date of birth"]!!, DATE_FMT),
            country = data["First citizenship"]!!,
        ).also {
            cache.userInfo = it
            cache.save()
        }
    }

    private fun parseCourseLink(link: String): Pair<String, String> {
        val courseUrlArgs = Url(link).parameters["ARGUMENTS"]!!.split(',')
        val id1 = courseUrlArgs[3].drop(2)
        val id2 = courseUrlArgs[4].drop(2)
        return Pair(id1, id2)
    }

    suspend fun fetchCourses() {
        val unknownCoursesMap = cache.courses.getOrPut(UNKNOWN_SEMESTER) { mutableMapOf() }
        val initialCoursesPage = cnetRequest("PROFCOURSES", listOf("-N000092"))
        val semesterElements = initialCoursesPage.select("#semester > option")
        for (semesterElement in semesterElements) {
            val cnetSemesterId = semesterElement.attr("value")
            if (cnetSemesterId == "999") {
                // "All" option
                continue
            }
            val semesterName = semesterElement.text().trim()
            val semesterId = semesterName.split(' ').let { it[1] + it[0][0] }
            val semesterPage = if (semesterElement.hasAttr("selected")) {
                initialCoursesPage
            } else {
                cnetRequest("PROFCOURSES", listOf("-N000092", "-N$cnetSemesterId"))
            }
            val semesterCoursesMap = cache.courses.getOrPut(semesterId) { mutableMapOf() }
            val courseRows = semesterPage.select(".rw-table tr.tbdata")
            for (courseRow in courseRows) {
                val code = courseRow.selectFirst(".rw-profc-courseno")!!.text().trim()
                val linkEl = courseRow.selectFirst(".rw-profc-coursename > .link")
                val name = linkEl!!.text().trim()
                val (id1, id2) = parseCourseLink(linkEl.attr("href"))
                val credits = courseRow.selectFirst(".rw-profc-credits")!!.text().trim().toDoubleOrNull() ?: 0.0
                // TODO: merge with existing course if it exists
                if (cache.courseToSemesterMap.getOrDefault(code, UNKNOWN_SEMESTER) != UNKNOWN_SEMESTER) {
                    continue
                }
                unknownCoursesMap.remove(code)
                semesterCoursesMap[code] = Course(id1, id2, code, name, credits = credits)
                cache.courseToSemesterMap[code] = semesterId
            }
        }
        val regStatusPage = cnetRequest("MYREGISTRATIONS", listOf("-N000095", "-N0"))
        val regStatusTables = regStatusPage.select(".tb750")
        for (table in regStatusTables) {
            val title = table.selectFirst("thead > tr.tbhead > td")!!.text().trim()
            if (title != "Pending Registrations" && title != "Accepted Registrations") {
                continue
            }
            val courseLinks = table.select("tbody > tr > .dl-inner a[name=eventLink]")
            for (linkEl in courseLinks) {
                val (id1, id2) = parseCourseLink(linkEl.attr("href"))
                val (code, name) = linkEl.text().trim().split(' ', limit = 2).let { Pair(it[0], it[1]) }
                if (cache.courseToSemesterMap.getOrDefault(code, UNKNOWN_SEMESTER) != UNKNOWN_SEMESTER) {
                    continue
                }
                unknownCoursesMap[code] = Course(id1, id2, code, name)
                cache.courseToSemesterMap[code] = UNKNOWN_SEMESTER
            }
        }
        cache.save()
    }

    suspend fun getCalendar(week: LocalDate, repeat: Boolean = true, pivot: LocalDate? = null): Calendar {
        val effectivePivot = pivot ?: week
        ensureSession()
        val args = listOf(
            "-N000093",
            "-A${week.format(DATE_FMT)}",
            "-A",
            "-N1",
            "-N0",
            "-N0",
        )
        val soup = cnetRequest("SCHEDULER", args)
        val rows = soup.select("#weekTableRoomplan > table tr")

        val cal = Calendar()
            .withProdId("-//libcampusnet//iCal4j 1.0//EN")
            .withDefaults()
            .fluentTarget
        val tz = TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone(TIME_ZONE)
        val vtz = tz.vTimeZone
        val ug = RandomUidGenerator()
        val pivotDay = GregorianCalendar.from(effectivePivot.toJavaLocalDate().atStartOfDay(tz.toZoneId()))

        for (row in rows) {
            val firstCell = row.firstElementChild()
            if (firstCell == null || firstCell.hasClass("tbcontrol") || firstCell.hasClass("fulltime")) {
                continue
            }
            if (!firstCell.hasClass("time")) {
                logger.warn("Unexpected row: ${row.outerHtml()}")
                continue
            }
            val weekdayCells = row.select("td")
            for ((i, weekdayCell) in weekdayCells.withIndex()) {
                if (!weekdayCell.hasClass("appointment")) {
                    continue
                }

                val timeText = weekdayCell.selectFirst(".timePeriod > i")!!.text().trim()
                val (startTime, endTime) = timeText.split(" - ")
                    .let { Pair(LocalTime.parse(it[0]), LocalTime.parse(it[1])) }
                val day = pivotDay.clone() as GregorianCalendar
                day.add(java.util.Calendar.DAY_OF_MONTH, i)
                val startDt = day.clone() as GregorianCalendar
                startDt.set(java.util.Calendar.HOUR_OF_DAY, startTime.hour)
                startDt.set(java.util.Calendar.MINUTE, startTime.minute)
                val endDt = day.clone() as GregorianCalendar
                endDt.set(java.util.Calendar.HOUR_OF_DAY, endTime.hour)
                endDt.set(java.util.Calendar.MINUTE, endTime.minute)

                val courseCode = weekdayCell.selectFirst(">a.link")!!.text().trim()
                val course = cache.getCourse(courseCode)
                val eventName = course?.let { "${it.name} (${it.code})" } ?: courseCode

                val event = VEvent(startDt.toZonedDateTime(), endDt.toZonedDateTime(), eventName)
                event.add<VEvent>(ug.generateUid())
                event.add<VEvent>(vtz.timeZoneId)

                val locationEl = weekdayCell.selectFirst(".timePeriod > .arrow")
                locationEl?.let {
                    event.add<VEvent>(Location(it.text().trim()))
                }

                if (repeat) {
                    event.add<VEvent>(RRule<Temporal>(Frequency.WEEKLY))
                }

                cal.add<Calendar>(event)
            }
        }

        return cal
    }
}
