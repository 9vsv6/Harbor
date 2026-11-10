# kotlinx.serialization keeps generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.harbor.android.** {
    *** Companion;
}
-keepclasseswithmembers class app.harbor.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Named only in the manifest's Cast meta-data, which R8 doesn't follow.
-keep class app.harbor.android.player.CastOptionsProvider { *; }

# NewPipeExtractor (trailers) runs YouTube's player JavaScript through Rhino, which reaches
# into its own classes by reflection and names desktop-only Java APIs it never uses here.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.tools.**
-dontwarn jdk.dynalink.**

# harbor-core over JNI: the native methods and the class that holds them.
-keep class app.harbor.android.data.HarborCore { native <methods>; }

# Cloudstream extensions (.cs3) are loaded at run time and link against these by name, so none of
# their classes or members may be removed or renamed: Cloudstream's library and the app-class
# stand-ins (com.lagradost.*), and every library an extension may call through them.
-keep class com.lagradost.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.fleeksoft.ksoup.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class me.xdrop.fuzzywuzzy.** { *; }
-keep class dev.whyoleg.cryptography.** { *; }
-keep class io.ktor.** { *; }
-keep class androidx.preference.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.fragment.** { *; }
-keep class com.google.android.material.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, KotlinMetadata
-dontwarn com.lagradost.**
-dontwarn com.fasterxml.jackson.**
-dontwarn dev.whyoleg.cryptography.**
-dontwarn io.ktor.**
-dontwarn org.jspecify.annotations.**

# Subtitle sync entry points are looked up by their JNI names.
-keep class app.harbor.android.player.AudioSync { native <methods>; }
-keep class app.harbor.android.player.WhisperNative { native <methods>; }
