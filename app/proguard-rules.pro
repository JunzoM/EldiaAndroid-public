# 配布用ビルド（R8）の設定

# アプリ自身のクラスは残す（Supabase との JSON 変換・画面の状態保存で名前を使うため）
-keep class jp.eldia.hotel.** { *; }

# kotlinx.serialization（@Serializable の変換器を残す）
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# supabase-kt / Ktor（実行時に使わない環境向けクラスの警告を消す）
-keep class io.github.jan.supabase.** { *; }
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit（文字認識）
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# エラー時の行番号を残す（不具合の調査用）
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
