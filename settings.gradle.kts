pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // HighCapable 官方备用仓库（Sonatype 故障时兜底；本机访问不稳，需要时手动打开）
        // maven("https://raw.githubusercontent.com/HighCapable/maven-repository/main/repository/releases")
    }
}

rootProject.name = "HyperVolume"
include(":app")
