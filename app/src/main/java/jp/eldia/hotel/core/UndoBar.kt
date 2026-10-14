package jp.eldia.hotel.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 保存のあと数秒だけ「元に戻す」を出す（画面下のスナックバー。表示は MainScreen）。
 * undo はエラーメッセージを返す（成功なら null）
 */
object UndoBar {
    class Event(val message: String, val undo: suspend () -> String?)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** 「元に戻す」を表示する時間（ミリ秒） */
    const val SHOW_MILLIS = 5_000L

    fun offer(message: String, undo: suspend () -> String?) {
        _events.tryEmit(Event(message, undo))
    }
}
