package com.codexrm.mobile

/** Projects delivered with the Android app, including its native library and web assets. */
internal object OpenSourceLicenses {
    const val REPOSITORY = "https://github.com/CyL-Cly/CodexRM"
    private const val APACHE = "Apache-2.0"
    private const val MIT = "MIT"
    private const val BSD = "BSD-3-Clause"
    private const val ASSET_DIR = "licenses/"

    data class Project(val name: String, val url: String, val license: String, val assets: List<String>)

    private fun project(name: String, url: String, license: String, vararg files: String) =
        Project(name, url, license, files.map { ASSET_DIR + it })

    private fun apache(name: String, url: String, vararg notices: String) =
        project(name, url, APACHE, "Apache-2.0.txt", *notices)

    private fun go(name: String, url: String) = project(name, url, BSD, "go.txt")

    val projects = listOf(
        apache("AndroidX / Jetpack Compose", "https://github.com/androidx/androidx"),
        apache("Compose Multiplatform", "https://github.com/JetBrains/compose-multiplatform"),
        apache("JetBrains AndroidX", "https://github.com/JetBrains/compose-multiplatform-core"),
        apache("miuix", "https://github.com/compose-miuix-ui/miuix"),
        project("MaterialKolor", "https://github.com/jordond/MaterialKolor", "MIT / Apache-2.0",
            "materialkolor.txt", "material-color-utilities.txt"),
        apache("Kotlin 标准库", "https://github.com/JetBrains/kotlin"),
        apache("kotlinx.coroutines", "https://github.com/Kotlin/kotlinx.coroutines"),
        apache("kotlinx.serialization", "https://github.com/Kotlin/kotlinx.serialization"),
        apache("JetBrains Java Annotations", "https://github.com/JetBrains/java-annotations"),
        apache("JSpecify", "https://github.com/jspecify/jspecify"),
        apache("Poko", "https://github.com/drewhamilton/Poko"),
        apache("Guava ListenableFuture", "https://github.com/google/guava"),
        apache("OkHttp", "https://github.com/square/okhttp", "okhttp-notice.txt"),
        apache("Okio", "https://github.com/square/okio"),
        apache("ZXing", "https://github.com/zxing/zxing"),
        apache("Markwon", "https://github.com/noties/Markwon"),
        project("commonmark-java", "https://github.com/commonmark/commonmark-java", BSD, "commonmark.txt"),
        project("JLaTeXMath Android", "https://github.com/noties/jlatexmath-android/tree/v0.2.0",
            "GPL-2.0-or-later（含链接例外及字体许可）", "jlatexmath-notice.txt", "GPL-2.0.txt", "jlatexmath-fonts.txt"),
        Project("Mermaid", "https://github.com/mermaid-js/mermaid", "MIT（含打包依赖许可）",
            listOf("mermaid/LICENSE", ASSET_DIR + "mermaid-notices.txt")),
        project("quic-go", "https://github.com/quic-go/quic-go", MIT, "quic-go.txt"),
        go("Go 运行时和标准库", "https://github.com/golang/go"),
        go("golang.org/x/crypto", "https://github.com/golang/crypto"),
        go("golang.org/x/net", "https://github.com/golang/net"),
        go("golang.org/x/sys", "https://github.com/golang/sys"),
    )
}
