plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// On GitHub Actions, turn Kotlin compiler errors ("e: file://...") into annotations so they are visible without the raw log.
if (System.getenv("GITHUB_ACTIONS") == "true") {
    println("::add-matcher::" + file("../.github/kotlin-matcher.json").absolutePath)
}
