# Keep the JNI adapter classes whose fully-qualified names are baked into the
# pinned libbyedpi.so (Java_io_github_romanvht_byedpi_core_... symbols). R8 must
# not rename these classes or their native methods.
-keep class io.github.romanvht.byedpi.core.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
