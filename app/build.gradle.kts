plugins {
    id("osmo.android.application")
    id("osmo.android.compose")
}

android {
    namespace = "com.alliot.osmo.demo.app"

    defaultConfig {
        applicationId = "com.alliot.osmo.demo"
        versionCode = 1
        versionName = "0.1.0"
    }

    bundle {
        abi {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        language {
            enableSplit = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":feature-control"))
    implementation(project(":feature-gps"))
    implementation(project(":core-session"))
    implementation(project(":core-ble"))
    implementation(project(":core-protocol"))
    implementation(project(":core-media"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
