plugins {
    id("com.android.application")
}
android {
    namespace = "dev.rectifydata.holdframe"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.rectifydata.holdframe"
        minSdk = 31
        targetSdk = 35
        versionCode = providers.gradleProperty("buildNumber").orElse("9000").get().toInt()
        versionName = providers.gradleProperty("releaseVersion").orElse("0.9.0").get()
    }
    sourceSets.getByName("main").java.srcDir("../core/src/main/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}
