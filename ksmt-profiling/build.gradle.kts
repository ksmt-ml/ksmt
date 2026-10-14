plugins {
    id("io.ksmt.ksmt-base")
}

dependencies {
    api(project(":ksmt-core"))
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
