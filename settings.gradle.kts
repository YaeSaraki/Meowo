pluginManagement {
    repositories { gradlePluginPortal(); maven("https://repo.tabooproject.org/repository/releases/") }
}
rootProject.name = "Meowo"
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.tabooproject.org/repository/releases/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        // VaultAPI is only published here; it is compileOnly, so nothing is bundled from it.
        maven("https://jitpack.io/")
    }
}
include("api", "shared", "platform", "ui", "core")
include("contexts:meowo-teleport", "contexts:meowo-economy", "contexts:meowo-economy:domain", "contexts:meowo-menu", "contexts:meowo-render", "contexts:meowo-spawn", "contexts:meowo-sit", "contexts:meowo-hat", "contexts:meowo-shadowbox", "contexts:meowo-nametag", "contexts:meowo-mine", "contexts:meowo-chat")

include("contexts:meowo-home", "contexts:meowo-agents", "contexts:meowo-avatar")
include("contexts:meowo-invsee", "contexts:meowo-pomodoro")
include("contexts:meowo-shop")
