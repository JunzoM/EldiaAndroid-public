package jp.eldia.hotel.core

/**
 * 接続先の定数。iOS版・Web版（facility-manager）と同じ値を使う。
 * anon key は公開前提のキー（RLS でアクセス制御）なのでアプリに埋め込んでよい。
 */
object AppConfig {
    /** 共通プロジェクト（cleaning-log）: 設備管理・清掃・特別清掃・水槽・冷蔵庫・スタッフ名簿・ログイン */
    const val SUPABASE_URL = "https://mqapkwlxlunyvsuhiujb.supabase.co"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1xYXBrd2x4bHVueXZzdWhpdWpiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzNTQyMzUsImV4cCI6MjA4ODkzMDIzNX0.3BCcAtsfMHHXemd-AYYfdIhLoWyZJ1crRl5jNrsReWk"

    /** 設備管理 Web 版（招待メールのリダイレクト先など） */
    const val FACILITY_WEB_ORIGIN = "https://facility-manager-phi.vercel.app"
}
