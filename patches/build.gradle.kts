group = "com.feurstagram"

patches {
    about {
        name = "HMX Insta Patches"
        description = "HMX Insta: a distraction-free Instagram with a downloader and a ghost mode, with runtime toggles and an optional permanent lock."
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
