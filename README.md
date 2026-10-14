# ELDIA 業務用（Android版）

神戸ホテル2施設（ラグジュアリー・モダン）の業務管理アプリの Android 版。
iOS版（`~/Downloads/EldiaHotel`、SwiftUI）と同じ機能を Kotlin + Jetpack Compose（Material 3）で作る。

## 構成

- 接続先：Supabase 共通プロジェクト（cleaning-log / `mqapkwlxlunyvsuhiujb`）だけ。ログインも同じ（設備管理のメール + パスワード）
- 対象：Android 10 以上（Pixel 9a 前後）
- 画面：下のタブ（ホーム／発注／清掃／設備／冷蔵庫）＋右上から設定。店舗は右上のメニューか各画面の切り替えで選ぶ
- 色：店舗のアクセント（luxury `#b8860b` / modern `#0277bd`）。背景は iOS版と同じ青みのグレー

## 進み具合

| 段階 | 内容 | 状態 |
| --- | --- | --- |
| 1 | 土台（ログイン・店舗切り替え・タブ・設定） | 済 |
| 2 | 清掃記録・特別清掃・水槽・設備管理・冷蔵庫 | これから |
| 3 | 発注・ホームのチェックリスト（発注データを Supabase へ移したあと） | これから |

## Mac での準備（初回だけ）

1. Android Studio をインストール：https://developer.android.com/studio から Mac 用（Apple シリコン）をダウンロードして、アプリケーションフォルダへ
2. 初回起動の案内（Setup Wizard）は「Standard」のまま進める（Android SDK などが入る）
3. 「Open」で `~/Downloads/EldiaAndroid` を開く。初回は必要な部品のダウンロードに数分かかる
4. 右上に「Upgrade」などの提案が出たら、そのまま受けて構わない

## Pixel で動かす

1. Pixel の「設定 → デバイス情報 → ビルド番号」を7回タップして開発者向けオプションを出す
2. 「設定 → システム → 開発者向けオプション → USB デバッグ」をオン
3. USB で Mac につなぎ、Pixel に出る確認で「許可」
4. Android Studio の上部で端末を選んで ▶（Run）

## プッシュ通知（新しい不具合・発注の承認待ち）を有効にする（初回だけ）

アプリ側・サーバー側（Edge Function `push-notify`・関数 `register_android_device`）は対応済み。Firebase の設定を2つ入れると届くようになる。

1. https://console.firebase.google.com で プロジェクトを作成（名前は任意。Google アナリティクスは不要）
2. 「Android アプリを追加」→ パッケージ名 `jp.eldia.hotel` → `google-services.json` をダウンロードし、このリポジトリの `app/` に置いて push（中身は秘密ではない）
3. Firebase の「プロジェクトの設定 → サービス アカウント → 新しい秘密鍵を生成」で JSON をダウンロード（**これは秘密。Git に入れない**）
4. Supabase（共通プロジェクト）の SQL Editor で登録：
   ```sql
   select vault.create_secret('<ダウンロードした JSON の中身をそのまま>', 'fcm_service_account');
   ```
5. アプリを入れ直してログイン → 通知を許可。設定 →「プッシュ通知」で種類ごとにオン／オフ
