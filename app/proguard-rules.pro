# 入口类只被 META-INF/xposed/java_init.list 按名字引用，R8 看不见，得手动留。
-keep class * extends io.github.libxposed.api.XposedModule { <init>(); }
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
