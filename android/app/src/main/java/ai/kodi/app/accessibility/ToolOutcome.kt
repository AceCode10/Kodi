package ai.kodi.app.accessibility

/**
 * Result of running one device tool.
 *
 * [isError] is what the backend needs to know a tool actually failed. It used to be
 * hardcoded `false` on both transports, so a failed send looked identical to a
 * successful one: the agent reported success to the user, and the learning loop
 * (which only reflects on turns that erred) never ran.
 */
data class ToolOutcome(
    val content: String,
    val isError: Boolean,
)

/**
 * Thrown by [fail] to unwind a device tool handler with an error message.
 *
 * Deliberately a [Throwable] rather than an [Exception]: several handlers wrap their
 * work in `try { ... } catch (e: Exception)` and return a string from the catch, which
 * would otherwise swallow this and re-report the failure as a success.
 */
class ToolFailure(val reason: String) : Throwable(reason)

/** Abort the current device tool with [message], reported to the agent as an error. */
fun fail(message: String): Nothing = throw ToolFailure(message)
