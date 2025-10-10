rootProject.name = "CertoraProver"
include("GeneralUtils", "SMTLIBUtils", "Shared", "Typechecker", "PrefixGen", "ASTExtraction", "KspGeneration", "DetektRules", "TestUtils")
project(":GeneralUtils").projectDir = file("lib/GeneralUtils")
project(":SMTLIBUtils").projectDir = file("lib/SMTLIBUtils")
project(":Shared").projectDir = file("lib/Shared")
project(":KspGeneration").projectDir = file("lib/KspGeneration")
project(":DetektRules").projectDir = file("lib/DetektRules")
project(":TestUtils").projectDir = file("lib/TestUtils")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
