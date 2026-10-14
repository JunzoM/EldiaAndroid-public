package jp.eldia.hotel.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外観設定 */
enum class Appearance(val rawValue: String, val label: String) {
    SYSTEM("system", "システムに合わせる"),
    LIGHT("light", "ライト"),
    DARK("dark", "ダーク"),
}

/** 端末に保存する設定（選択中の店舗・外観）。iOS版の AppState に相当 */
object AppPrefs {
    private lateinit var prefs: SharedPreferences

    private val _hotel = MutableStateFlow(Hotel.LUXURY)
    val hotel: StateFlow<Hotel> = _hotel.asStateFlow()

    private val _appearance = MutableStateFlow(Appearance.SYSTEM)
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("eldia", Context.MODE_PRIVATE)
        _hotel.value = Hotel.from(prefs.getString("eldia_hotel", null)) ?: Hotel.LUXURY
        _appearance.value = Appearance.entries.firstOrNull { it.rawValue == prefs.getString("appearance", null) }
            ?: Appearance.SYSTEM
    }

    fun setHotel(h: Hotel) {
        _hotel.value = h
        prefs.edit().putString("eldia_hotel", h.rawValue).apply()
    }

    fun setAppearance(a: Appearance) {
        _appearance.value = a
        prefs.edit().putString("appearance", a.rawValue).apply()
    }
}
