package dev.vanutp.libcampusnet

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.io.path.*

suspend fun main() {
    val dataDir = Path("data")
    dataDir.createDirectories()
    val campusnetUsername = System.getenv("CNET_USERNAME")
    val campusnetPassword = System.getenv("CNET_PASSWORD")
    val dsfCredsPath = dataDir.resolve("dsf-creds.json")
    val cnetCredsPath = dataDir.resolve("cnet-creds.json")
    val cachePath = dataDir.resolve("cache.json")
    val calOutPath = dataDir.resolve("calendar.ics")

    val json = Json {
        prettyPrint = true
    }

    // Load stored data
    val dsfCreds = dsfCredsPath
        .takeIf { it.exists() }
        ?.readText()
        ?.let { json.decodeFromString<DsfSessionCredentials>(it) }
    val cnetCreds = cnetCredsPath
        .takeIf { it.exists() }
        ?.readText()
        ?.let { json.decodeFromString<CnetSessionCredentials>(it) }
    val cache = cachePath
        .takeIf { it.exists() }
        ?.readText()
        ?.let { json.decodeFromString<InMemoryCache>(it) }
        ?: InMemoryCache()

    // Create client
    val client = CNetClient.create(
        LoginCredentials(campusnetUsername, campusnetPassword),
        cache,
        dsfCreds,
        cnetCreds,
    )

    // Use the client
    println(client.getUserInfo())
    client.fetchCourses()
    val cal = client.getCalendar(
        LocalDate(2025, 2, 10),
        true,
        LocalDate(2025, 2, 3),
    )
    calOutPath.writeText(cal.toString())

    // Save data
    dsfCredsPath.writeText(json.encodeToString(client.getDsfCredentials()))
    cnetCredsPath.writeText(json.encodeToString(client.getCnetCredentials()))
    cachePath.writeText(json.encodeToString(cache))
}
