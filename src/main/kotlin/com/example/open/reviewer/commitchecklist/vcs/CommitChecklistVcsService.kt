package com.example.open.reviewer.commitchecklist.vcs

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CommitChecklistVcsService(private val project: Project) {
    private val logger = Logger.getInstance(CommitChecklistVcsService::class.java)
    @Volatile private var cachedPlatformDetection: CachedPlatformDetection? = null

    fun resolveGitRepositoryRoot(): Path? {
        val manager = ProjectLevelVcsManager.getInstance(project)
        val gitRoots =
            manager.allVcsRoots
                .filter { it.vcs?.name.equals(GIT_VCS_NAME, ignoreCase = true) }
                .map { Paths.get(it.path.path).normalize() }

        val projectPath = resolveProjectPath()
        return GitRootSelector.selectPreferredRoot(gitRoots, projectPath)
    }

    fun isChecklistFeatureEnabled(): Boolean = resolveGitRepositoryRoot() != null

    fun resolvePrimaryRemote(gitRoot: Path? = resolveGitRepositoryRoot()): GitRemote? {
        val resolvedRoot = gitRoot ?: return null
        val output = runGitRemoteVerbose(resolvedRoot) ?: return null
        val remotes = GitRemoteParser.parse(output)
        val selected = GitRemoteSelector.selectPrimary(remotes)

        if (selected == null) {
            logger.info("Commit checklist remote detection: no git remote found for root=$resolvedRoot")
        } else {
            logger.info(
                "Commit checklist remote detection: selected remote name=${selected.name} url=${selected.url} root=$resolvedRoot",
            )
        }

        return selected
    }

    fun detectHostingPlatform(gitRoot: Path? = resolveGitRepositoryRoot()): HostingPlatform {
        val resolvedRoot = gitRoot ?: return HostingPlatform.UNKNOWN
        val remoteUrl = resolvePrimaryRemote(resolvedRoot)?.url
        val hasGithubDirectory = Files.isDirectory(resolvedRoot.resolve(".github"))
        val hasGitlabDirectory = Files.isDirectory(resolvedRoot.resolve(".gitlab"))

        val cached = cachedPlatformDetection
        if (
            cached != null &&
            cached.rootPath == resolvedRoot &&
            cached.remoteUrl == remoteUrl &&
            cached.hasGithubDirectory == hasGithubDirectory &&
            cached.hasGitlabDirectory == hasGitlabDirectory
        ) {
            return cached.platform
        }

        val detected =
            HostingPlatformDetector.detect(
                remoteUrl = remoteUrl,
                hasGithubDirectory = hasGithubDirectory,
                hasGitlabDirectory = hasGitlabDirectory,
            )
        cachedPlatformDetection =
            CachedPlatformDetection(
                rootPath = resolvedRoot,
                remoteUrl = remoteUrl,
                hasGithubDirectory = hasGithubDirectory,
                hasGitlabDirectory = hasGitlabDirectory,
                platform = detected,
            )

        logger.info(
            "Commit checklist platform detection: platform=$detected remoteUrl=${remoteUrl ?: "<none>"} " +
                "hasGithubDirectory=$hasGithubDirectory hasGitlabDirectory=$hasGitlabDirectory root=$resolvedRoot",
        )
        return detected
    }

    private fun resolveProjectPath(): Path? {
        val basePath = project.basePath
        return if (basePath.isNullOrBlank()) null else Paths.get(basePath).normalize()
    }

    private fun runGitRemoteVerbose(gitRoot: Path): String? {
        return runCatching {
            val process =
                ProcessBuilder("git", "-C", gitRoot.toString(), "remote", "-v")
                    .redirectErrorStream(true)
                    .start()

            val finished = process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn("Commit checklist remote detection: timed out running git remote -v for root=$gitRoot")
                return null
            }

            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse {
            logger.warn("Commit checklist remote detection: failed for root=$gitRoot", it)
            null
        }
    }

    companion object {
        private const val GIT_VCS_NAME = "Git"
        private const val GIT_COMMAND_TIMEOUT_SECONDS = 5L

        fun getInstance(project: Project): CommitChecklistVcsService = project.getService(CommitChecklistVcsService::class.java)
    }

    private data class CachedPlatformDetection(
        val rootPath: Path,
        val remoteUrl: String?,
        val hasGithubDirectory: Boolean,
        val hasGitlabDirectory: Boolean,
        val platform: HostingPlatform,
    )
}
