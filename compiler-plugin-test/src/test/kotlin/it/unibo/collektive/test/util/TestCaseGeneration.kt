package it.unibo.collektive.test.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

object TestCaseGeneration {
    @JvmStatic
    fun main(args: Array<String>) {
        generate()
    }

    data class TestCases(val suiteName: String, val cases: List<Case>, val parameters: Parameters)
    data class Case(val id: String, val code: String, val warning: Boolean, val message: String)
    data class Parameters(val aggregate: List<String>, val iterative: List<Iterative>)
    data class Iterative(val name: String, val code: String)

    val preamble = """
      import it.unibo.collektive.aggregate.api.Aggregate
      import it.unibo.collektive.Collektive.Companion.aggregate
    
    """.trimIndent()

    fun generate(
        yamlFilePath: String = "compiler-plugin-test/src/test/resources/yaml/TestingCases.yaml",
        outputResourceFolder: String = "compiler-plugin-test/src/test/resources/kotlin/g/",
        outputSpecFolder: String = "compiler-plugin-test/src/test/kotlin/it/unibo/collektive/test/",
        generateKotestSpec: Boolean = true,
    ) {
        val outputPackage = outputSpecFolder
            .substringAfter("kotlin/")
            .replace("/", ".")
            .dropLast(1)

        val jsonContent = File(yamlFilePath).readText()
        val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val data: TestCases = mapper.readValue(jsonContent)

        val outputSuitePath = File(outputSpecFolder + data.suiteName + ".kt")
        val className = outputSuitePath.name.toString().substringBefore(".kt")

        val allTests = StringBuilder()

        data.cases.forEach { case ->
            data.parameters.iterative.dropLast(1).forEach { iterative ->
                data.parameters.aggregate.dropLast(2).forEach { aggregate ->
                    val values = mapOf("iterative" to iterative.code, "aggregate" to aggregate)

                    val substitutedCode = CompileUtils.StringSubstitutor.replace(case.code, values)
                    val fileName =
                        (
                            case.id +
                                iterative.name.replaceFirstChar(Char::titlecase) +
                                "${aggregate.substringBefore("(").replaceFirstChar(Char::titlecase)}.kt"
                            )
                            .replace(" ", "")
                            .replace("(", "")
                            .replace(")", "")
                    generateKotlinFile(outputResourceFolder, fileName, "$preamble\n$substitutedCode")

                    if (generateKotestSpec) {
                        val adjustedValues = mapOf("aggregate" to aggregate.substringBefore("("))
                        val expectedWarning = CompileUtils.StringSubstitutor.replace(case.message, adjustedValues)
                        allTests.append(
                            generateTestCase(
                                case,
                                iterative.code,
                                aggregate,
                                className,
                                outputResourceFolder.substringAfter("resources") + fileName,
                                if (case.warning) "warning(\"$expectedWarning\")" else "noWarning",
                            ),
                        )
                    }
                }
            }
        }

        if (generateKotestSpec) {
            writeTestFile(className, outputSuitePath, allTests.toString(), outputPackage)
        }
    }

    private fun generateKotlinFile(directory: String, fileName: String, code: String) {
        val path = Paths.get("$directory$fileName")
        Files.createDirectories(path.parent)
        Files.write(path, code.toByteArray(), StandardOpenOption.CREATE)
        println("Generated file: $fileName")
    }

    private fun generateTestCase(
        case: Case,
        iterative: String,
        aggregate: String,
        className: String,
        relatedResourcePath: String,
        expectedWarning: String,
    ): String {
        return """
        "${case.id} with $iterative and $aggregate" - {
            val code = $className::class.java.getResource("$relatedResourcePath")!!.readText()
            KotlinTestingProgram("$className.kt", code) shouldCompileWith $expectedWarning
        }
        """
    }

    private fun writeTestFile(className: String, path: File, testContent: String, outputPackage: String) {
        val pathAsPath: Path = path.toPath().parent
        Files.createDirectories(pathAsPath)
        val content = """
        package $outputPackage
        import io.kotest.core.spec.style.FreeSpec    
        import it.unibo.collektive.test.util.CompileUtils.KotlinTestingProgram
        import it.unibo.collektive.test.util.CompileUtils.noWarning
        import it.unibo.collektive.test.util.CompileUtils.warning
        import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
        
        @OptIn(ExperimentalCompilerApi::class)
        class $className : FreeSpec({
          "Iterated use of aggregate functions should result in warnings if manual 'alignedOn' is missing" - {
            $testContent
          }
        })
        """.trimIndent()

        path.printWriter().use { out ->
            out.write(content)
        }
        println("All tests written to $path")
    }
}
