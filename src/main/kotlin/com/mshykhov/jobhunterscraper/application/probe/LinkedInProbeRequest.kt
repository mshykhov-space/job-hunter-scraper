package com.mshykhov.jobhunterscraper.application.probe

data class LinkedInProbeRequest(
    val category: String,
    val location: String,
    val remoteOnly: Boolean,
    val limit: Int,
    val hoursOld: Int,
) {
    companion object {
        fun parse(args: Array<String>): LinkedInProbeRequest {
            require(args.count { it == "--probe-linkedin" } == 1) { "Expected --probe-linkedin" }
            val values = mutableMapOf<String, String>()
            args.filterNot { it == "--probe-linkedin" }.forEach { argument ->
                val key = argument.substringBefore('=')
                require(
                    key in setOf("--probe.category", "--probe.location", "--probe.remote-only", "--probe.limit", "--probe.hours-old") &&
                        '=' in argument,
                ) {
                    "Unsupported probe argument"
                }
                require(values.putIfAbsent(key, argument.substringAfter('=')) == null) { "Duplicate probe argument" }
            }
            val category = values["--probe.category"]?.trim().orEmpty()
            val location = values["--probe.location"]?.trim().orEmpty()
            require(category.isNotBlank() && category.length <= 120) { "Invalid --probe.category" }
            require(location.isNotBlank() && location.length <= 120) { "Invalid --probe.location" }
            val remoteOnly =
                when (values["--probe.remote-only"] ?: "true") {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Invalid --probe.remote-only")
                }
            val limit = values["--probe.limit"]?.toIntOrNull() ?: if (values.containsKey("--probe.limit")) null else 5
            val hoursOld = values["--probe.hours-old"]?.toIntOrNull() ?: if (values.containsKey("--probe.hours-old")) null else 24
            require(limit != null && limit in 1..10) { "Invalid --probe.limit" }
            require(hoursOld != null && hoursOld in 1..168) { "Invalid --probe.hours-old" }
            return LinkedInProbeRequest(category, location, remoteOnly, limit, hoursOld)
        }
    }
}
