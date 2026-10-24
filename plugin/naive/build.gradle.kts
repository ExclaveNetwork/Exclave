plugins {
    id("com.android.application")
}

android {
    defaultConfig {
        applicationId = "io.nekohasekai.sagernet.plugin.naive"
    }
    namespace = "io.nekohasekai.sagernet.plugin.naive"
}

setupPlugin("naive")