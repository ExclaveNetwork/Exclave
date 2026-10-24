import com.google.protobuf.gradle.*

plugins {
    id("com.android.library")
    alias(libs.plugins.protobuf)
}

setupCommon()

dependencies {
    protobuf(project(":library:proto"))

    api(libs.protobuf.java)
}
android {
    namespace = "com.v2ray.core"
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.34.1"
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("java")
            }
        }
    }
}
