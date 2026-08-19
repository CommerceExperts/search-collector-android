plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "io.searchhub.collector.workmanager"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    api(project(":library"))
    implementation(libs.androidx.work.runtime.ktx)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.searchhub"
                artifactId = "search-collector-android-workmanager"
                version = project.property("VERSION") as String
            }
        }
        repositories {
            maven {
                name = "nexus"
                val isSnapshot = (project.property("VERSION") as String).endsWith("SNAPSHOT")
                url = uri(
                    if (isSnapshot)
                        "https://nexus.commerce-experts.com/content/repositories/searchhub-public-snapshots/"
                    else
                        "https://nexus.commerce-experts.com/content/repositories/searchhub-external/"
                )
                credentials {
                    username = System.getenv("NEXUS_USERNAME")
                    password = System.getenv("NEXUS_PASSWORD")
                }
            }
        }
    }
}
