package dev.vanutp.libcampusnet

fun weekdayToInt(weekday: String) = when (weekday) {
    "Monday" -> 0
    "Tuesday" -> 1
    "Wednesday" -> 2
    "Thursday" -> 3
    "Friday" -> 4
    "Saturday" -> 5
    "Sunday" -> 6
    else -> throw IllegalArgumentException("Invalid weekday: $weekday")
}
