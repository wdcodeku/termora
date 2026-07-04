package app.termora

import com.formdev.flatlaf.util.SystemInfo
import com.jthemedetecor.util.OsInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.apache.commons.lang3.time.DateUtils
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.*
import kotlin.math.ln
import kotlin.math.pow


object Application {
    private lateinit var baseDataDir: File


    val ohMyJson = Json {
        ignoreUnknownKeys = true
        // 默认值不输出
        encodeDefaults = false
    }


    val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(60))
            .readTimeout(Duration.ofSeconds(60))
            .addInterceptor(
                HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
                    private val log = LoggerFactory.getLogger(HttpLoggingInterceptor::class.java)
                    override fun log(message: String) {
                        if (log.isDebugEnabled) log.debug(message)
                    }
                }).setLevel(HttpLoggingInterceptor.Level.BASIC)
            )
            .build()
    }

    fun getDefaultShell(): String {
        if (SystemInfo.isWindows) {
            return "cmd.exe"
        } else {
            val shell = System.getenv("SHELL")
            if (shell != null && shell.isNotBlank()) {
                return shell
            }
        }
        return "/bin/bash"
    }

    fun getTemporaryDir(): File {
        val temporaryDir = File(getBaseDataDir(), "temporary")
        FileUtils.forceMkdir(temporaryDir)
        return temporaryDir
    }

    fun createSubTemporaryDir(prefix: String = getName()): Path {
        return Files.createTempDirectory(getTemporaryDir().toPath(), prefix)
    }

    fun getBaseDataDir(): File {
        if (::baseDataDir.isInitialized) {
            return baseDataDir
        }

        // 从启动参数取
        var baseDataDir = System.getProperty("${getName()}.base-data-dir".lowercase())

        // 取不到从环境取
        if (StringUtils.isBlank(baseDataDir)) {
            baseDataDir = System.getenv("${getName()}_BASE_DATA_DIR".uppercase())
        }

        // 便携模式（默认开启）：从软件根目录读取/存储配置
        // 若软件根目录不可写（例如安装在 Program Files），自动回退到用户主目录
        if (StringUtils.isBlank(baseDataDir) && isPortableConfigEnabled()) {
            val portableDir = getPortableDataDir()
            if (portableDir != null) {
                baseDataDir = portableDir.absolutePath
            }
        }

        // Windows 并且是绿色版，那么判断所在目录是否有 data 目录
        if (SystemInfo.isWindows && getLayout() == AppLayout.Zip && StringUtils.isBlank(baseDataDir)) {
            val appPath = getAppPath()
            if (StringUtils.isNotBlank(appPath)) {
                val file = File(appPath).parentFile
                if (file.exists()) {
                    val dataFile = File(file, "data")
                    if (dataFile.exists()) {
                        baseDataDir = dataFile.absolutePath
                    }
                }
            }
        }

        val dir = if (StringUtils.isNotBlank(baseDataDir)) {
            File(baseDataDir)
        } else {
            File(SystemUtils.getUserHome(), ".${getName()}".lowercase())
        }


        FileUtils.forceMkdir(dir)
        Application.baseDataDir = dir

        return dir
    }

    /**
     * 便携模式开关标记文件，存放在软件根目录。
     * 约定：文件存在表示“关闭便携模式”（使用用户主目录）；不存在表示“开启便携模式”（默认）。
     * 这样在默认开启便携模式时，不会向用户主目录写入任何文件。
     */
    private fun getPortableDisabledMarkerFile(): File? {
        val root = getAppRootDir() ?: return null
        return File(root, ".portable-disabled")
    }

    /**
     * 是否启用便携模式（从软件根目录读取配置）。默认开启。
     */
    fun isPortableConfigEnabled(): Boolean {
        val marker = getPortableDisabledMarkerFile() ?: return true
        return marker.exists().not()
    }

    /**
     * 设置是否启用便携模式（下次启动生效）。仅写入软件根目录，不会污染用户主目录。
     */
    fun setPortableConfigEnabled(enabled: Boolean) {
        val marker = getPortableDisabledMarkerFile() ?: return
        try {
            if (enabled) {
                FileUtils.deleteQuietly(marker)
            } else {
                FileUtils.forceMkdir(marker.parentFile)
                marker.writeText("disabled")
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * 便携模式下的数据目录：软件根目录下的 data 目录。
     * 仅在能够解析软件路径且目录确实可写时返回，否则返回 null（回退到用户主目录）。
     */
    private fun getPortableDataDir(): File? {
        val root = getAppRootDir() ?: return null
        val dataDir = File(root, "data")
        return try {
            FileUtils.forceMkdir(dataDir)
            // 实际写入探测：Windows 上 Files.isWritable 对可写目录也可能误判为不可写
            val probe = File(dataDir, ".write-test-${System.nanoTime()}")
            probe.writeText("ok")
            FileUtils.deleteQuietly(probe)
            // 首次启用便携模式时，把用户主目录下的旧配置迁移过来，避免数据“丢失”
            migrateLegacyDataIfNeeded(dataDir)
            dataDir
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析软件根目录（可执行文件所在目录）。
     */
    private fun getAppRootDir(): File? {
        // 优先使用 jpackage 提供的可执行文件路径
        val appPath = getAppPath()
        if (StringUtils.isNotBlank(appPath)) {
            File(appPath).parentFile?.let { if (it.isDirectory) return it }
        }
        // 回退：使用当前进程可执行文件路径（开发环境通常是 java，可写性探测会失败从而回退主目录）
        return try {
            val command = ProcessHandle.current().info().command().orElse(null) ?: return null
            // 开发环境下命令是 java(.exe)，不应被当作软件根目录
            val file = File(command)
            val name = file.name.lowercase()
            if (name == "java" || name == "java.exe" || name == "javaw.exe") return null
            file.parentFile?.takeIf { it.isDirectory }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将用户主目录（~/.termora）下的旧配置迁移到便携目录（仅当便携目录尚无数据库时执行一次）。
     */
    private fun migrateLegacyDataIfNeeded(dataDir: File) {
        try {
            val portableDb = File(dataDir, "config/termora.db")
            if (portableDb.exists()) return

            val legacyDir = File(SystemUtils.getUserHome(), ".${getName()}".lowercase())
            val legacyDb = File(legacyDir, "config/termora.db")
            if (legacyDir.isDirectory.not() || legacyDb.exists().not()) return
            if (legacyDir.canonicalFile == dataDir.canonicalFile) return

            // 拷贝旧配置，排除临时目录
            FileUtils.copyDirectory(legacyDir, dataDir, java.io.FileFilter { f -> f.name != "temporary" })
        } catch (e: Exception) {
            // 迁移失败不应阻断启动
        }
    }

    fun getVersion(): String {
        var version = System.getProperty("app-version")

        if (version.isNullOrBlank()) {
            version = System.getProperty("jpackage.app-version")
        }

        if (version.isNullOrBlank()) {
            if (getAppPath().isBlank()) {
                val versionFile = File("VERSION")
                if (versionFile.exists() && versionFile.isFile) {
                    version = versionFile.readText().trim()
                }
            }

            if (version.isNullOrBlank()) {
                version = "unknown"
            }
        }

        return version
    }

    fun getLayout(): AppLayout {
        if (SystemInfo.isMacOS) return AppLayout.App

        val layout = System.getProperty("jpackage.app-layout")
        if (SystemInfo.isLinux) {
            if ("deb" == layout) {
                return AppLayout.Deb
            } else if ("tar.gz" == layout) {
                return AppLayout.TarGz
            }
        }

        if (SystemInfo.isWindows) {
            if ("exe" == layout) {
                return AppLayout.Exe
            } else if ("zip" == layout) {
                return AppLayout.Zip
            } else if ("appx" == layout) {
                return AppLayout.Appx
            }
        }

        return if (SystemInfo.isWindows) AppLayout.Exe else AppLayout.AppImage

    }

    /**
     * 未知版本通常是开发版本
     */
    fun isUnknownVersion(): Boolean {
        return getVersion().contains("unknown")
    }

    fun getUserAgent(): String {
        return "${getName()}/${getVersion()}(${Locale.getDefault()}); ${SystemUtils.OS_NAME}/${SystemUtils.OS_VERSION}(${SystemUtils.OS_ARCH}); ${SystemUtils.JAVA_VM_NAME}/${SystemUtils.JAVA_VERSION}"
    }

    /**
     * 是否是测试版
     */
    fun isBetaVersion(): Boolean {
        return getVersion().contains("beta")
    }

    fun getReleaseDate(): Date {
        val releaseDate = System.getProperty("release-date")
        if (releaseDate.isNullOrBlank()) {
            return Date()
        }
        return runCatching { DateUtils.parseDate(releaseDate, "yyyy-MM-dd") }.getOrNull() ?: Date()
    }

    fun getAppPath(): String {
        return StringUtils.defaultString(System.getProperty("jpackage.app-path"))
    }

    fun getName(): String {
        return "Termora"
    }

    fun browse(uri: URI, async: Boolean = true) {
        // https://github.com/TermoraDev/termora/issues/178
        if (SystemInfo.isWindows && uri.scheme == "file") {
            if (async) {
                swingCoroutineScope.launch(Dispatchers.IO) { tryBrowse(uri) }
            } else {
                tryBrowse(uri)
            }
        } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri)
        } else if (async) {
            swingCoroutineScope.launch(Dispatchers.IO) { tryBrowse(uri) }
        } else {
            tryBrowse(uri)
        }
    }

    private fun tryBrowse(uri: URI) {
        if (SystemInfo.isWindows) {
            ProcessBuilder("explorer", uri.toString()).start()
        } else if (SystemInfo.isMacOS) {
            ProcessBuilder("open", uri.toString()).start()
        } else if (SystemInfo.isLinux && OsInfo.isGnome()) {
            ProcessBuilder("xdg-open", uri.toString()).start()
        }
    }

    fun browseInFolder(file: File) {
        if (SystemInfo.isWindows) {
            ProcessBuilder("explorer", "/select," + file.absolutePath).start()
        } else if (SystemInfo.isMacOS) {
            ProcessBuilder("open", "-R", file.absolutePath).start()
        } else if (Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            Desktop.getDesktop().browseFileDirectory(file)
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    val value = bytes / 1024.0.pow(exp.toDouble())

    return String.format("%.2f%s", value, units[exp])
}

fun fromSftpPermissions(sftpPermissions: Int): Set<PosixFilePermission> {
    val result = mutableSetOf<PosixFilePermission>()

    // 将十进制权限转换为八进制字符串
    val octalPermissions = sftpPermissions.toString(8)

    // 仅取后三位权限部分
    if (octalPermissions.length < 3) {
        return result
    }

    val permissionBits = octalPermissions.takeLast(3)

    // 解析每一部分的权限
    val owner = permissionBits[0].digitToInt()
    val group = permissionBits[1].digitToInt()
    val others = permissionBits[2].digitToInt()

    // 处理所有者权限
    if ((owner and 4) != 0) result.add(PosixFilePermission.OWNER_READ)
    if ((owner and 2) != 0) result.add(PosixFilePermission.OWNER_WRITE)
    if ((owner and 1) != 0) result.add(PosixFilePermission.OWNER_EXECUTE)

    // 处理组权限
    if ((group and 4) != 0) result.add(PosixFilePermission.GROUP_READ)
    if ((group and 2) != 0) result.add(PosixFilePermission.GROUP_WRITE)
    if ((group and 1) != 0) result.add(PosixFilePermission.GROUP_EXECUTE)

    // 处理其他用户权限
    if ((others and 4) != 0) result.add(PosixFilePermission.OTHERS_READ)
    if ((others and 2) != 0) result.add(PosixFilePermission.OTHERS_WRITE)
    if ((others and 1) != 0) result.add(PosixFilePermission.OTHERS_EXECUTE)

    return result
}

fun formatSeconds(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60


    return when {
        days > 0 -> I18n.getString(
            "termora.transport.jobs.table.estimated-time-days-format",
            days,
            hours,
            minutes,
            remainingSeconds
        )

        hours > 0 -> I18n.getString(
            "termora.transport.jobs.table.estimated-time-hours-format",
            hours,
            minutes,
            remainingSeconds
        )

        minutes > 0 -> I18n.getString(
            "termora.transport.jobs.table.estimated-time-minutes-format",
            minutes,
            remainingSeconds
        )

        else -> I18n.getString(
            "termora.transport.jobs.table.estimated-time-seconds-format",
            remainingSeconds
        )
    }
}

