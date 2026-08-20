plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tracking.familyorbit"
    compileSdk { version = release(37) }
    val configuredApiUrl = providers.gradleProperty("FAMILY_ORBIT_API_URL").orNull
	val firebaseAppId = providers.gradleProperty("FIREBASE_PARENT_APP_ID").orElse("")
	val firebaseApiKey = providers.gradleProperty("FIREBASE_API_KEY").orElse("")
	val firebaseProjectId = providers.gradleProperty("FIREBASE_PROJECT_ID").orElse("")
	val firebaseSenderId = providers.gradleProperty("FIREBASE_SENDER_ID").orElse("")

    defaultConfig {
        applicationId = "com.tracking.familyorbit"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["MAPS_API_KEY"] = providers.gradleProperty("MAPS_API_KEY").orElse("").get()
		buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseAppId.get()}\"")
		buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseApiKey.get()}\"")
		buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseProjectId.get()}\"")
		buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebaseSenderId.get()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", "\"${configuredApiUrl ?: "http://10.0.2.2:4000/api/v1"}\"")
            manifestPlaceholders["USES_CLEARTEXT_TRAFFIC"] = "true"
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"${configuredApiUrl ?: "https://family.example.jp/api/v1"}\"")
            manifestPlaceholders["USES_CLEARTEXT_TRAFFIC"] = "false"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.google.maps.compose)
	implementation(platform(libs.firebase.bom))
	implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
