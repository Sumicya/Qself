import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem
import java.io.File
import java.nio.file.Paths

object Common {
    /**
     * The history was squashed for the repository reset, so the raw commit
     * count no longer orders against upstream releases (v1.6.1 = r3000+).
     * Base the code above that line and keep growing with each commit, so
     * installs over upstream builds are upgrades, never downgrades.
     */
    private const val VERSION_CODE_BASE = 3000

    private fun commitCount(rootProject: Project): Int? {
        val headFile = File(rootProject.projectDir, ".git" + File.separator + "HEAD")
        if (!headFile.exists()) {
            println("WARN: .git/HEAD does NOT exist")
            return null
        }
        return FileRepository(rootProject.file(".git")).use { repo ->
            val refId = repo.resolve("HEAD")
            Git(repo).log().add(refId).call().count()
        }
    }

    fun getBuildVersionCode(project: Project): Int {
        return VERSION_CODE_BASE + (commitCount(project.rootProject) ?: 1)
    }

    fun getGitHeadRefsSuffix(project: Project): String {
        val rootProject = project.rootProject
        val headFile = File(rootProject.projectDir, ".git" + File.separator + "HEAD")
        return if (headFile.exists()) {
            FileRepository(rootProject.file(".git")).use { repo ->
                val refId = repo.resolve("HEAD")
                val commitCount = VERSION_CODE_BASE + Git(repo).log().add(refId).call().count()
                ".r" + commitCount + "." + refId.name.substring(0, 7)
            }
        } else {
            println("WARN: .git/HEAD does NOT exist")
            ".standalone"
        }
    }

    fun getExtraSuffix(): String {
        return if (Version.minSdk < 24) ".lvs" else ""
    }

    fun getBuildVersionName(project: Project): String {
        return Version.versionName + getGitHeadRefsSuffix(project) + getExtraSuffix()
    }

    fun findInPath(executable: String): String? {
        val pathEnv = System.getenv("PATH")
        return pathEnv.split(File.pathSeparator).map { folder ->
            Paths.get("${folder}${File.separator}${executable}${if (OperatingSystem.current().isWindows) ".exe" else ""}")
                .toFile()
        }.firstOrNull { path ->
            path.exists()
        }?.absolutePath
    }

    fun getBuildIdSuffix(): String {
        return try {
            val ciBuildId = System.getenv()["APPCENTER_BUILD_ID"]
            if (ciBuildId != null) ".$ciBuildId"
            else ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun getTimeStamp(): Int {
        return (System.currentTimeMillis() / 1000L).toInt()
    }
}
