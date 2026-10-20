package dev.t2u.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * adb command channel (decision D-02). Exported, but the manifest guards it with
 * android.permission.DUMP, which only the adb shell holds (threat T-01-01); `am broadcast` is
 * ordered, so the reply travels back as the broadcast's result data.
 *
 * Built-in commands: ping, dump-state, stop, payloads. Anything else goes to the feature whose
 * `commands` contain it.
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val args = readArgs(intent)
        val cmd = args["cmd"].orEmpty()
        val cmdArgs = args - "cmd"

        pool.execute {
            var reply: CommandReply
            val t0 = SystemClock.elapsedRealtime()
            try {
                val future = pool.submit<CommandReply> { dispatch(context, cmd, cmdArgs) }
                reply = try {
                    future.get(REPLY_DEADLINE_MS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    CommandReply(false, """{"error":"timeout"}""")
                }
            } catch (t: Throwable) {
                reply = CommandReply(false, errorJson("${t.javaClass.simpleName}: ${t.message}"))
            }
            ServiceHub.service?.evidence?.event(
                "cmd-done",
                mapOf("name" to cmd, "ok" to reply.ok, "ms" to SystemClock.elapsedRealtime() - t0),
            )
            try {
                pending.setResultData(reply.json)
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readArgs(intent: Intent): Map<String, String> {
        val extras = intent.extras ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in extras.keySet()) {
            val v = extras.get(key)
            if (v is String) out[key] = v
        }
        return out
    }

    private fun dispatch(context: Context, cmd: String, args: Map<String, String>): CommandReply {
        val service = ServiceHub.service ?: return CommandReply(false, """{"error":"service not running"}""")
        service.evidence.event(
            "cmd",
            mapOf("name" to cmd, "args" to args.mapValues { it.value.take(ARG_CAP) }),
        )
        return when (cmd) {
            "ping" -> CommandReply(true, """{"ok":true}""")
            "dump-state" -> CommandReply(true, service.currentState().toString())
            "stop" -> {
                service.stopSelf()
                CommandReply(true, """{"ok":true,"stopping":true}""")
            }
            "payloads" -> {
                startPayloads(service)
                CommandReply(true, """{"started":true}""")
            }
            else -> {
                val feature = service.features.firstOrNull { cmd in it.commands }
                    ?: return CommandReply(false, """{"error":"unknown command"}""")
                try {
                    feature.handle(cmd, args)
                } catch (t: Throwable) {
                    service.evidence.event(
                        "feature-error",
                        mapOf("where" to "${feature.name}:$cmd", "error" to t.javaClass.name, "message" to t.message),
                    )
                    CommandReply(false, errorJson("${t.javaClass.simpleName}: ${t.message}"))
                }
            }
        }
    }

    private fun startPayloads(service: BridgeService) {
        service.scope.launch {
            val out = File(service.evidenceDir, "payloads.json")
            val tmp = File(service.evidenceDir, "payloads.json.tmp")
            val json = try {
                val paths = NativePayloads.ensure(service.applicationContext, service.evidence)
                paths.toJson().toString()
            } catch (t: Throwable) {
                errorJson("${t.javaClass.name}: ${t.message}")
            }
            tmp.writeText(json)
            tmp.renameTo(out)
        }
    }

    private fun errorJson(message: String): String =
        """{"error":${JsonPrimitive(message)}}"""

    companion object {
        private const val REPLY_DEADLINE_MS = 7_000L
        private const val ARG_CAP = 256
        private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "t2u-cmd").apply { isDaemon = true }
        }
    }
}
