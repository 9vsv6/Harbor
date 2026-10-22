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
