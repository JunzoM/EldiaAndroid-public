package jp.eldia.hotel.core

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 他の画面からタブの中の表示を指定するための受け渡し（ホーム → 清掃タブの「水槽」など）。
 * 受け取った側は反映したら null（false）に戻す。
 */
object TabRequests {
    /** 開く下のタブ（AppTab の name）。ランチャーのショートカットなど画面の外から指定する */
    val mainTab = MutableStateFlow<String?>(null)

    /** 清掃タブで選ぶ区分（CleaningSegment の name） */
    val cleaningSegment = MutableStateFlow<String?>(null)

    /** 発注タブで選ぶ区分（OrderSegment の name） */
    val orderSegment = MutableStateFlow<String?>(null)

    /** 設備タブで不具合の登録（部屋の選択）を開く */
    val facilityNew = MutableStateFlow(false)

    /** 設備タブで開く不具合（通知のタップ） */
    val facilityIssue = MutableStateFlow<Long?>(null)
}
