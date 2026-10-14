package jp.eldia.hotel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import jp.eldia.hotel.core.KnownUser
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.core.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 共通ログインの状態（iOS版の FacilityAuth に相当） */
sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: UserInfo) : AuthState
}

/** ログイン中ユーザーの担当者名（user_metadata.full_name。未設定なら null） */
val UserInfo.fullName: String?
    get() = userMetadata?.get("full_name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** 表示名（full_name → メールの @ より前） */
val UserInfo.displayName: String
    get() = fullName ?: email?.substringBefore("@").orEmpty()

class AuthViewModel : ViewModel() {
    private val auth = SB.main.auth

    val state: StateFlow<AuthState> = auth.sessionStatus
        .map { s ->
            when (s) {
                is SessionStatus.Authenticated -> {
                    val u = s.session.user ?: auth.currentUserOrNull() ?: KnownUser.user
                    if (u != null) { KnownUser.user = u; AuthState.SignedIn(u) } else AuthState.SignedOut
                }
                is SessionStatus.NotAuthenticated -> {
                    KnownUser.user = null
                    AuthState.SignedOut
                }
                // 電波が悪くてログインの更新に失敗している間（SDK が数秒おきに再試行する）。
                // この間 SDK の currentUserOrNull() は null になるので、最後に確認できたユーザー
                // （なければ端末に保存されたログイン）でログイン中のままにする。ログイン画面に戻さない
                is SessionStatus.RefreshFailure -> {
                    val u = KnownUser.user ?: runCatching { auth.sessionManager.loadSession()?.user }.getOrNull()
                    if (u != null) { KnownUser.user = u; AuthState.SignedIn(u) } else AuthState.SignedOut
                }
                else -> AuthState.Loading
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)

    // ログイン中ユーザーごとの ViewModel 置き場（発注・設備などの画面の ViewModel はここに入る）。
    // 画面の回転では残し、ログアウトや別ユーザーのログインで捨てる（前の人の権限・データを持ち越さない）
    private val userStore = ViewModelStore()
    private var storeUserId: String? = null

    init {
        viewModelScope.launch {
            state.collect {
                if (it is AuthState.SignedOut) clearUserStore()
                // ログインできたら、端末に残っている不具合の記録を送る
                if (it is AuthState.SignedIn) jp.eldia.hotel.core.CrashReporter.flush()
            }
        }
    }

    /** このユーザー用の ViewModelStore。ユーザーが変わったら中身を捨ててから返す */
    fun viewModelStoreFor(userId: String): ViewModelStore {
        if (userId != storeUserId) {
            userStore.clear()
            storeUserId = userId
        }
        return userStore
    }

    private fun clearUserStore() {
        userStore.clear()
        storeUserId = null
    }

    override fun onCleared() {
        clearUserStore()
        super.onCleared()
    }

    /** @return エラーメッセージ（成功時 null） */
    suspend fun signIn(email: String, password: String): String? = try {
        auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    suspend fun signOut() {
        runCatching { auth.signOut() }
    }

    /** 担当者名（スタッフ名簿の名前にもなる）を変える。@return エラーメッセージ（成功時 null） */
    suspend fun updateDisplayName(name: String): String? = try {
        auth.updateUser {
            data = buildJsonObject { put("full_name", name.trim()) }
        }
        null
    } catch (e: Exception) {
        e.userMessage()
    }
}
