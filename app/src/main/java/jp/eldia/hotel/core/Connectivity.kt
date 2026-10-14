package jp.eldia.hotel.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 電波の有無とログインの状態を見張る（送れる状態に戻ったら未送信分を送るため）。
 * online は「電波がある」かつ「ログインが有効（更新待ちでない）」のとき true。
 * ログインの更新に失敗していた間にたまった記録も、更新できた時点で送られる。
 * init は何度呼んでもよい（最初の1回だけ登録する）。
 */
object Connectivity {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    @Volatile private var network = true
    @Volatile private var signedIn = true

    private fun update() { _online.value = network && signedIn }

    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            // ログインの状態（更新に失敗している間は送らない。更新できたら送る）
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                SB.main.auth.sessionStatus.collect { s ->
                    signedIn = s is SessionStatus.Authenticated
                    update()
                }
            }
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
            // 現在の状態
            network = cm.activeNetwork?.let { n ->
                cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } ?: false
            update()
            try {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) { network = true; update() }
                    override fun onLost(n: Network) { network = false; update() }
                    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                        network = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        update()
                    }
                })
            } catch (e: Exception) {
                // 登録できない端末では常にオンライン扱い（従来どおり画面を開いたときに送る）
                network = true
                update()
            }
        }
    }
}
