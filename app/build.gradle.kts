plugins {
  id("com.android.application")
}

android {
  namespace = "com.n.watchremote"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.n.watchremote"
    minSdk = 26
    targetSdk = 30
    versionCode = 5
    versionName = "0.2.0"
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
}
