# ProGuard / R8 rules for IptvApp
# Task 24: dependency-specific rules added below (kotlinx.serialization, Retrofit).
#
# Room, Hilt, Media3, and OkHttp are DELIBERATELY NOT given hand-written rules here:
# they each ship their own consumer-proguard-rules.pro bundled inside their AAR,
# applied automatically by AGP at build time. Hand-writing rules for them would be
# redundant at best and risk masking/conflicting with their official rules at worst.

# ── Kotlin ────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# ── kotlinx.serialization ───────────────────────────────────────────────────
# Official rules published in the kotlinx.serialization README ("Android and R8/
# ProGuard rules for library consumers"), needed because R8 would otherwise
# strip/rename the generated $$serializer / Companion members that serializer()
# lookup relies on for every @Serializable class. Covers both the network DTOs
# in data/remote/dto/ and the @Serializable AppRoute navigation routes.
-keepattributes *Annotation*, InnerClasses

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <1>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` for serializable objects (used by @Serializable data object
# routes in navigation/AppRoute.kt).
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Extra explicit safety net for the two packages that actually use @Serializable in this
# app, on top of the generic rules above.
-keep @kotlinx.serialization.Serializable class com.bobot.iptvapp.data.remote.dto.** { *; }
-keep @kotlinx.serialization.Serializable class com.bobot.iptvapp.navigation.** { *; }

# ── Retrofit ─────────────────────────────────────────────────────────────
# Standard, widely-published Retrofit R8 rules (see Retrofit's own proguard-rules.pro).
# Retrofit relies on generic type information at runtime (Call<T>, Response<T>, generic
# return types of the API interface) which R8 can strip without these keeps.
-keepattributes Signature, Exceptions
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation
-keep class com.bobot.iptvapp.data.remote.XtreamApi { *; }

# ── Keep line numbers for crash reports ───────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
