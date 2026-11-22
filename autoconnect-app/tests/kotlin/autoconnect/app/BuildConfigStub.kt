package autoconnect.app

/**
 * Test-only stand-in for the Gradle-generated BuildConfig (the harness
 * compiles the app sources with plain kotlinc, outside Gradle). Lives
 * under tests/ — never part of the app build.
 */
object BuildConfig {
    const val BACKEND_BASE_URL: String = "https://backend.invalid"
    const val VERSION_NAME: String = "test"
    const val VERSION_CODE: Int = 0
    const val CORE_VERSION_PIN: String = "test"
    const val CORE_TAG_PIN: String = "test"
}
