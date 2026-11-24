plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "live.passo.smsgateway"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "live.passo.smsgateway"
    minSdk = 26
    targetSdk = 35
    versionCode = 5
    versionName = "0.2.3"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}
