# JLaTeXMath registers environment macros using class and method names.
# R8 cannot discover executeMacro through MacroInfo's reflective lookup.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}
# Predefined formula XML also looks up these APIs and constants by name.
-keepclassmembers class org.scilab.forge.jlatexmath.TeXFormula { public *; }
-keepclassmembers class org.scilab.forge.jlatexmath.TeXConstants { public static <fields>; }
-keepclassmembers class ru.noties.jlatexmath.awt.Color { public static <fields>; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.conscrypt.**
-keep class com.codexrm.mobile.NativeP2P { *; }
