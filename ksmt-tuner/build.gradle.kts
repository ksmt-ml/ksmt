plugins {
    id("io.ksmt.ksmt-base")
}

dependencies {
    implementation(project(":ksmt-core"))
    // ONNX Runtime for fast inference of the tuning model.
    // Plain (non-GPU) artifact; the module intentionally uses the base JVM target
    // (Java 8), which is supported by this ONNX Runtime version.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")

    testImplementation(project(":ksmt-z3"))
    testImplementation(project(":ksmt-cvc5"))
    testImplementation("org.junit.jupiter", "junit-jupiter-params", "5.8.2")
}