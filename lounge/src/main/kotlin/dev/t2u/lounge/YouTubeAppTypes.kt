package dev.t2u.lounge

/**
 * What the app needs to know about its speaker. [dataStore] holds the DIAL pid and the two sessions' identities;
 * without one nothing survives a restart. The policies default to the yt-cast-receiver defaults.
 */
data class AppOptions(
    val screenName: String,
    val brand: String,
    val model: String,
    val dataStore: DataStore?,
    val mutePolicy: MutePolicy = MutePolicy.AUTO,
    val resetPlayerOnDisconnect: ResetPlayerOnDisconnectPolicy = ResetPlayerOnDisconnectPolicy.ALL_DISCONNECTED,
)

/** The app could not start; the cause carries the detail. */
class AppException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class AppState { STOPPED, STARTING, RUNNING, STOPPING }
