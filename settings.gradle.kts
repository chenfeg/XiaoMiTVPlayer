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
        // ijkplayer 0.8.8 停在 jcenter（已停服），走阿里云归档镜像
        maven("https://maven.aliyun.com/repository/jcenter")
    }
}

rootProject.name = "TVPlayer"
include(":app")
