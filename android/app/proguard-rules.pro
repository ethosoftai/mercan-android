# JNI entry points and the callback the native side looks up by name.
-keepclasseswithmembernames class * { native <methods>; }
-keep class ai.mercan.app.MercanNative { *; }
-keep class ai.mercan.app.MercanNative$Callback { *; }
# Keep line numbers out of the mapping-less release (smaller, less informative stack dumps).
-renamesourcefileattribute SourceFile
