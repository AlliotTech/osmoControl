plugins {
    id("osmo.jvm.library")
}

dependencies {
    implementation(project(":core-protocol"))

    testImplementation(libs.junit4)
}
