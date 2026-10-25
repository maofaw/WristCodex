plugins {
  id("com.android.application")
}

android {
  namespace = "com.n.watchremote"
  compileSdk = 35

  defaultConfig {
    applicationId = providers.gradleProperty("qaApplicationId").getOrElse("com.n.watchremote")
    minSdk = 26
    targetSdk = 30
    versionCode = 15
    versionName = "0.6.3-rc1"
    testInstrumentationRunner = "com.n.watchremote.CloudVoiceInstrumentation"
    manifestPlaceholders["appLabel"] = if (providers.gradleProperty("qaApplicationId").isPresent) "腕上遥控·完成提醒测试" else "腕上遥控"
  }
  // Personal sideload app intentionally targets Android 11 for watch compatibility.
  lint { disable += "ExpiredTargetSdkVersion" }
  buildTypes {
    release {
      isMinifyEnabled = false
      isShrinkResources = false
      // Release APKs must be signed with your own private signing key.
    }
  }
  buildFeatures {
    buildConfig = false
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
