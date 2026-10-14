package jp.eldia.hotel.core

import androidx.compose.ui.graphics.Color

/** 部屋（設備管理・特別清掃で使う。id は "L301" / "M601" の形） */
data class Room(val id: String, val num: String, val floor: String, val type: String)

data class CommonArea(val id: String, val name: String, val floor: String, val hotel: Hotel)

data class ExteriorLocation(val id: String, val name: String)

/** 設備管理の定数（iOS版 FacilityConst・Web版 lib/constants.ts と同じ） */
object FacilityConst {
    val areas = listOf("部屋", "トイレ", "洗面", "風呂")
    val areaIcons = mapOf("部屋" to "🛏", "トイレ" to "🚽", "洗面" to "🪥", "風呂" to "🛁")
    val areaBadgeColors = mapOf(
        "部屋" to Color(0xFFE53935), "風呂" to Color(0xFF1E88E5), "洗面" to Color(0xFF00ACC1), "トイレ" to Color(0xFF8E24AA),
    )
    val commonSubareas = listOf("廊下・ホール", "共用施設", "非常設備")
    /** 2F（共用部＆リネン）だけにあるエリア */
    val secondFloorSubareas = listOf("リネン", "フロント")
    val exteriorSubareas = mapOf(
        "EXT-PARKING" to listOf("駐車スペース", "出入口", "精算機", "照明設備", "防犯カメラ"),
        "EXT-FOUNTAIN" to listOf("噴水本体", "配管設備", "照明設備", "ポンプ設備"),
        "EXT-ENTRANCE" to listOf("外壁", "正面玄関", "看板・サイン", "外灯", "フェンス"),
    )

    /** 共用部の表示名（2F はリネン室・フロントがあるので「共用部＆リネン」） */
    private fun commonName(floor: String) = if (floor == "2F") "$floor 共用部＆リネン" else "$floor 共用部"
    val luxuryCommon = listOf("2F", "3F", "4F", "5F").map { CommonArea("L-COM-$it", commonName(it), it, Hotel.LUXURY) }
    val modernCommon = listOf("2F", "3F", "4F", "5F", "6F").map { CommonArea("M-COM-$it", commonName(it), it, Hotel.MODERN) }
    val exteriors = listOf(
        ExteriorLocation("EXT-PARKING", "駐車場・駐輪場"),
        ExteriorLocation("EXT-FOUNTAIN", "噴水・水景"),
        ExteriorLocation("EXT-ENTRANCE", "外壁・エントランス"),
    )

    val ranks = listOf("プレジデント", "ロイヤル", "デラックスA", "デラックスB", "デラックスC",
        "スタンダードA", "スタンダードB", "サービスA", "サービスB")
    val rankColors = mapOf(
        "プレジデント" to Color(0xFFD4A843), "ロイヤル" to Color(0xFFFF7066),
        "デラックスA" to Color(0xFFD8B000), "デラックスB" to Color(0xFF5DBA5D), "デラックスC" to Color(0xFF4FC3F7),
        "スタンダードA" to Color(0xFFA48FFF), "スタンダードB" to Color(0xFF7FAE7F),
        "サービスA" to Color(0xFF8090A0), "サービスB" to Color(0xFF6A7A8A),
    )

    private fun r(prefix: String, list: List<Pair<String, String>>) =
        list.map { (num, type) -> Room(prefix + num, num, "${num.first()}F", type) }

    val luxuryRooms: List<Room> = r("L", listOf(
        "201" to "サービスB", "202" to "サービスB", "203" to "サービスB", "205" to "サービスB",
        "206" to "サービスB", "207" to "サービスB", "208" to "サービスB", "210" to "サービスB",
        "211" to "ロイヤル", "212" to "デラックスB", "215" to "デラックスA",
        "301" to "サービスB", "302" to "サービスB", "303" to "サービスB", "305" to "サービスB",
        "306" to "サービスB", "307" to "サービスB", "308" to "サービスB", "310" to "サービスB",
        "311" to "スタンダードB", "312" to "デラックスB", "313" to "サービスA", "315" to "デラックスA",
        "317" to "デラックスB", "318" to "スタンダードA", "320" to "デラックスB",
        "401" to "サービスA", "403" to "サービスA", "405" to "スタンダードB", "406" to "スタンダードA",
        "407" to "スタンダードA", "408" to "スタンダードB", "410" to "サービスA", "411" to "サービスA",
        "412" to "デラックスB", "413" to "デラックスA", "415" to "デラックスB", "416" to "デラックスA",
        "417" to "ロイヤル", "418" to "デラックスB", "420" to "スタンダードA", "421" to "デラックスA",
        "501" to "プレジデント", "502" to "プレジデント", "503" to "ロイヤル", "505" to "デラックスA",
        "506" to "デラックスB", "507" to "デラックスB", "508" to "スタンダードA", "510" to "ロイヤル",
        "511" to "スタンダードB",
    ))

    val modernRooms: List<Room> = r("M", listOf(
        "202" to "サービスB", "203" to "サービスB", "205" to "デラックスC", "206" to "スタンダードA",
        "207" to "スタンダードA",
        "301" to "サービスB", "302" to "サービスB", "303" to "サービスA", "305" to "サービスB",
        "306" to "サービスB", "307" to "サービスB", "308" to "サービスB", "310" to "サービスB",
        "311" to "サービスA", "312" to "スタンダードB",
        "401" to "スタンダードB", "403" to "スタンダードB", "405" to "スタンダードA", "406" to "スタンダードB",
        "407" to "サービスA", "408" to "サービスA", "410" to "サービスA", "411" to "サービスA",
        "412" to "デラックスC", "413" to "スタンダードA",
        "501" to "プレジデント", "502" to "プレジデント", "503" to "デラックスA", "505" to "デラックスB",
        "506" to "デラックスB", "507" to "デラックスC", "508" to "ロイヤル",
        "601" to "ロイヤル", "602" to "ロイヤル", "603" to "プレジデント",
    ))

    fun rooms(h: Hotel): List<Room> = if (h == Hotel.LUXURY) luxuryRooms else modernRooms
    fun commons(h: Hotel): List<CommonArea> = if (h == Hotel.LUXURY) luxuryCommon else modernCommon

    /** 昇順のフロア一覧（部屋一覧は固定なので最初に 1 回だけ作る） */
    fun floors(h: Hotel): List<String> = if (h == Hotel.LUXURY) luxuryFloors else modernFloors

    /** 指定フロアの部屋（部屋一覧の並び順のまま） */
    fun rooms(h: Hotel, floor: String): List<Room> =
        (if (h == Hotel.LUXURY) luxuryRoomsByFloor else modernRoomsByFloor)[floor].orEmpty()

    private val luxuryFloors by lazy { luxuryRooms.map { it.floor }.distinct().sorted() }
    private val modernFloors by lazy { modernRooms.map { it.floor }.distinct().sorted() }
    private val luxuryRoomsByFloor by lazy { luxuryRooms.groupBy { it.floor } }
    private val modernRoomsByFloor by lazy { modernRooms.groupBy { it.floor } }

    fun areas(roomId: String): List<String> = when {
        roomId.endsWith("-COM-2F") -> commonSubareas + secondFloorSubareas
        roomId.contains("-COM-") -> commonSubareas
        roomId.startsWith("EXT-") -> exteriorSubareas[roomId] ?: emptyList()
        else -> areas
    }

    fun locationName(roomId: String): String =
        (luxuryCommon + modernCommon).firstOrNull { it.id == roomId }?.name
            ?: exteriors.firstOrNull { it.id == roomId }?.name
            ?: ""

    fun roomNum(roomId: String, h: Hotel): String = rooms(h).firstOrNull { it.id == roomId }?.num ?: "?"

    /** 特別清掃の作業内容（app_settings の eldia_cleaning_tasks が無いときの既定） */
    val defaultCleaningTasks = listOf(
        "布団・シーツ交換", "枕カバー", "タオル全交換", "バスマット交換",
        "ユニットバス全洗", "トイレ消毒", "洗面台清掃", "鏡清掃",
        "エアコンフィルター", "冷蔵庫清掃", "カーペット清掃",
        "テレビ・リモコン", "窓・カーテン", "備品補充確認",
    )
}
