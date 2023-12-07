pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("com.gradle.enterprise") version "3.16"
    id("org.danilopianini.gradle-pre-commit-git-hooks") version "1.1.16"
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}

gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
        publishOnFailure()
    }
}

gitHooks {
    commitMsg { conventionalCommits() }
    preCommit {
        tasks("detektAll")
    }
    createHooks()
}

rootProject.name = "collektive"

includeBuild("plugin")
include("dsl", "alchemist-incarnation-collektive")
