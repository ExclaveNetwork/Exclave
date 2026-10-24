plugins {
    id("com.android.application")
}

android {
    defaultConfig {
        applicationId = "com.github.dyhkwong.sagernet.plugin.shadowquic"
    }
    namespace = "io.nekohasekai.sagernet.plugin.shadowquic"
}

setupPlugin("shadowquic")