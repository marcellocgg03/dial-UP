package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/YouTubeCastReceiver.js (MIT, Copyright (c) Patrick Kan).
// Contracts only: the renderer (02-10) depends on these, the lounge app implements them (02-12).

/**
 * One cast target: a DIAL server plus a lounge session pair (YouTube and YouTube Music) for one
 * speaker, driving one [Player]. [status] is one of "stopped", "starting", "running", "stopping".
 */
interface CastReceiver {
    val status: String

    suspend fun start()

    suspend fun stop()

    /** Senders currently connected to this receiver. */
    fun connectedSenders(): List<Sender>

    /** Whether autoplay is switched on when a sender connects (src/renderer.js `autoplayOnConnect`). */
    fun enableAutoplayOnConnect(value: Boolean)

    /**
     * Registers the owner's callback. It is called once per receiver by its owner; the receiver calls it after it
     * stopped itself because a lounge session failed for good, so the owner can give the speaker a new receiver
     * The default does nothing, for receivers that never stop themselves.
     */
    fun setOnTerminate(callback: (Throwable) -> Unit) {}

    /**
     * Renaming from the settings screen: from now on [name] is this receiver's DIAL friendly name. The device description answers
     * with it at once and the device is advertised again (ssdp:alive where announcing is allowed). The lounge screen name keeps
     * the start-time name until the receiver's next start. The default does nothing.
     */
    suspend fun rename(name: String) {}
}

/**
 * What a renderer passes to create its receiver (src/renderer.js createReceiver options).
 * [name] is the DIAL friendly name shown in the sender's cast menu; [screenName] is the name of a
 * manually paired screen.
 */
data class ReceiverOptions(
    val dialPort: Int,
    val name: String,
    val screenName: String,
    val brand: String,
    val model: String,
    val dataStore: DataStore,
)

/** Builds a [CastReceiver] for one speaker; tests substitute a fake, 02-12 provides the real one. */
fun interface ReceiverFactory {
    fun create(player: Player, options: ReceiverOptions): CastReceiver
}
