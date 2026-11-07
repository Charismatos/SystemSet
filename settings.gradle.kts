pluginManagement {
    /**
      * The dependencyResolutionManagement.repositories
      * block is where you configure the repositories and dependencies used by
      * all modules in your project, such as libraries that you are using to
      * create your application. However, you should configure module-specific
      * dependencies in each module-level build.gradle file. For new projects,
      * Android Studio includes Google's Maven repository and the Maven Central
      * Repository by default, but it does not configure any dependencies (unless
      * you select a template that requires some).
      */

    repositories {
        gradlePluginPortal()
        google()       /* Directs Gradle to fetch official Android build plugins from Google's repository */
        mavenCentral() /* Fetches general software dependencies from the global Maven Central repository */
    }
}

// Fixed typo: Corrected 'depedencyResolutionManagement' to include the missing 'n' -> 'dependencyResolutionManagement'
dependencyResolutionManagement {
    /**
      * The dependencyResolutionManagement.repositories
      * block is where you configure the repositories and dependencies used by
      * all modules in your project, such as libraries that you are using to
      * create your application. However, you should configure module-specific
      * dependencies in each module-level build.gradle file. For new projects,
      * Android Studio includes Google's Maven repository and the Maven Central
      * Repository by default, but it does not configure any dependencies (unless
      * you select a template that requires some).
      */
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) /* Enforces centralized repository management across all modules */
    repositories {
        google()       /* Centralized source for Android libraries */
        mavenCentral() /* Centralized source for third-party Kotlin/Java libraries */
    }
}

rootProject.name = "SystemSet" /* Sets the root project workspace name in IDEs like VS Code */
include(":app")                  /* Includes the main 'app' module in the multi-module build path */
