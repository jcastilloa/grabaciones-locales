plugins {
    id("com.android.library")
}
java {
    // Make those value match the app module one
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

android {
    namespace = "org.jcastillo.grabaciones.hidden_api_stubs"
    // Make this match our app module one
    compileSdk = 37
}