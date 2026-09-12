plugins {
    id("jbsa.foundation")
}

tasks.named("verify") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
