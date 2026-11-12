group = "com.feurstagram"

patches {
    about {
        name = "Feurstagram Patches"
        description = "Distraction-free Instagram: blocks the feed, stories, explore, reels, ads and suggestions, with a runtime toggle and an optional permanent lock."
        source = "https://github.com/bharti1233/Hmxgram.git"
        author = "Jean Herail and HMX Insta+ contributors"
        contact = "na"
        website = "https://github.com/bharti1233/Hmxgram"
        license = "GPLv3"
    }
}

dependencies {
    // Provides app.morphe.util.* helpers (DOM, bytecode, references) used by the patches.
    implementation(libs.morphe.patches.library)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
