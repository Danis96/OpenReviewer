package com.example.open.reviewer.architecture.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ImportDependencyGraphBuilderTest {
    private val builder = ImportDependencyGraphBuilder()

    @Test
    fun `builds edges from internal imports`() {
        withTempRepo { root ->
            val foo =
                write(
                    root,
                    "src/main/kotlin/com/example/Foo.kt",
                    """
                    package com.example

                    import com.example.data.Bar

                    class Foo
                    """.trimIndent(),
                )
            val bar =
                write(
                    root,
                    "src/main/kotlin/com/example/data/Bar.kt",
                    """
                    package com.example.data

                    class Bar
                    """.trimIndent(),
                )

            val graph =
                builder.build(
                    root = root,
                    allFiles = listOf(foo.toString(), bar.toString()),
                    manifestEntrypoints = emptyList(),
                )

            assertEquals(2, graph.nodeCount)
            assertEquals(1, graph.edgeCount)
            assertTrue(graph.topNodes.isNotEmpty())
        }
    }

    @Test
    fun `tolerates unresolved imports and keeps resolved edges`() {
        withTempRepo { root ->
            val foo =
                write(
                    root,
                    "src/main/kotlin/com/example/Foo.kt",
                    """
                    package com.example

                    import com.example.data.Bar
                    import com.unknown.MissingThing

                    class Foo
                    """.trimIndent(),
                )
            val bar =
                write(
                    root,
                    "src/main/kotlin/com/example/data/Bar.kt",
                    """
                    package com.example.data

                    class Bar
                    """.trimIndent(),
                )

            val graph =
                builder.build(
                    root = root,
                    allFiles = listOf(foo.toString(), bar.toString()),
                    manifestEntrypoints = emptyList(),
                )

            assertEquals(2, graph.nodeCount)
            assertEquals(1, graph.edgeCount)
        }
    }

    @Test
    fun `builds ui to vm to repo edges from cheap type usage heuristics`() {
        withTempRepo { root ->
            val ui =
                write(
                    root,
                    "src/main/kotlin/com/example/ui/MainScreen.kt",
                    """
                    package com.example.ui

                    class MainScreen {
                        private val vm = MainViewModel()
                    }
                    """.trimIndent(),
                )
            val vm =
                write(
                    root,
                    "src/main/kotlin/com/example/vm/MainViewModel.kt",
                    """
                    package com.example.vm

                    class MainViewModel(private val repository: UserRepository)
                    """.trimIndent(),
                )
            val repo =
                write(
                    root,
                    "src/main/kotlin/com/example/data/UserRepository.kt",
                    """
                    package com.example.data

                    class UserRepository
                    """.trimIndent(),
                )

            val graph =
                builder.build(
                    root = root,
                    allFiles = listOf(ui.toString(), vm.toString(), repo.toString()),
                    manifestEntrypoints = emptyList(),
                )

            assertEquals(3, graph.nodeCount)
            assertEquals(2, graph.edgeCount)
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("import-dependency-graph-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun write(
        root: Path,
        relativePath: String,
        content: String,
    ): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText(content)
        return file
    }
}
