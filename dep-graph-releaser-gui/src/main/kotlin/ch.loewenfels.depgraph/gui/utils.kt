package ch.loewenfels.depgraph.gui

import kotlin.browser.document
import kotlin.browser.window
import kotlin.js.Promise

const val SECOND = 1000
const val HALF_A_SECOND = 500


fun <T> sleep(ms: Int, action: () -> T): Promise<T> {
    val p: Promise<Any> = Promise { resolve, _ -> window.setTimeout(resolve, ms) }
    return p.then { action() }
}

// It's workaround for KT-19672 since we can fix it properly until KT-11265 isn't fixed.
inline fun <T> Promise<Promise<T>>.unwrapPromise(): Promise<T> = this.unsafeCast<Promise<T>>()

inline fun <T> Promise<Promise<Promise<T>>>.unwrap2Promise(): Promise<T> = this.unsafeCast<Promise<T>>()
inline fun <T> Promise<Promise<Promise<Promise<T>>>>.unwrap3Promise(): Promise<T> = this.unsafeCast<Promise<T>>()

fun changeCursorToProgress() {
    document.bodyNonNull.style.cursor = "progress"
}

fun changeCursorBackToNormal() {
    document.bodyNonNull.style.cursor = "default"
}

fun <T, S> Promise<T>.finally(action: (T?) -> S): Promise<S> {
    return this
        .then { action(it) }
        .catch { t -> action(null); throw t }
}

fun randomPublishId(): String = uuidv4().replace("-", "").take(15)

//copied from https://stackoverflow.com/questions/105034/create-guid-uuid-in-javascript
private fun uuidv4(): String {
    val uuid = js(
        """'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });"""
    )
    return uuid as String
}
