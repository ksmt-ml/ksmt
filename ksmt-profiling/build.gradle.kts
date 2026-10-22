plugins {
    id("io.ksmt.ksmt-base")
}

dependencies {
    api(project(":ksmt-core"))

    // cvc5 is the primary real solver; it is used only by the opt-in overhead benchmark.
    testImplementation(project(":ksmt-cvc5:ksmt-cvc5-core"))
    testImplementation(project(":ksmt-cvc5:ksmt-cvc5-native"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            addKsmtPom()
            addSourcesAndJavadoc(project)
            signKsmtPublication(project)
        }
    }
}
