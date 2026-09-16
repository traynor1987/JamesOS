package uk.co.james.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import uk.co.james.BuildConfig
import uk.co.james.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppUpdate(val code: Long,val name: String,val apk: String,val checksum: String)
class ApkUpdater(private val context: Context) {
    private val credentials=UpdateCredentials(context)
    private fun connection(url:String,asset:Boolean=false):HttpURLConnection {
        val repo=UpdatePolicy.repository(BuildConfig.UPDATE_REPOSITORY)
        val token=credentials.read()
        var current=url
        repeat(6) {
            UpdatePolicy.https(current)
            val c=(URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects=false;connectTimeout=15000;readTimeout=20000
                setRequestProperty("User-Agent","James-Android")
                setRequestProperty("Accept",if(asset)"application/octet-stream"else "application/vnd.github+json")
                if(UpdatePolicy.canAuthorize(current,repo)) {
                    setRequestProperty("X-GitHub-Api-Version","2022-11-28")
                    if(token.isNotBlank())setRequestProperty("Authorization","Bearer $token")
                }
            }
            val code=c.responseCode
            if(code !in listOf(301,302,303,307,308))return c
            val location=c.getHeaderField("Location");c.disconnect()
            require(!location.isNullOrBlank()) {"Update server returned an invalid redirect."}
            current=URL(URL(current),location).toString()
        }
        error("Update server redirected too many times.")
    }
    private fun responseError(code:Int):String=when(code) {
        401,403 -> "GitHub access was denied or rate-limited. Check the saved token, its expiry and Contents read permission."
        404 -> "GitHub could not provide the release. If the repository is private, set up GitHub access with Contents: Read-only, or open Releases in your browser."
        else -> "Update server returned $code. Try again later."
    }
    private fun text(url:String,asset:Boolean=false):String {
        val c=connection(url,asset)
        try {require(c.responseCode==200){responseError(c.responseCode)}
            return c.inputStream.bufferedReader().use {reader->val out=StringBuilder();val buffer=CharArray(4096);while(true){val n=reader.read(buffer);if(n<0)break;out.append(buffer,0,n);require(out.length<=1024*1024){"Update metadata is too large."}};out.toString()}
        }finally{c.disconnect()}
    }
    private fun latestPublicReleaseTag(repo:String):String {
        val url="https://github.com/${UpdatePolicy.repository(repo)}/releases/latest"
        val c=(URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=false;connectTimeout=15000;readTimeout=20000
            setRequestProperty("User-Agent","James-Android")
        }
        try {
            require(c.responseCode in listOf(301,302,303,307,308)){"GitHub could not provide the latest public release."}
            return UpdatePolicy.releaseTag(repo,c.getHeaderField("Location")?:"")?:error("GitHub returned an invalid public release redirect.")
        } finally {c.disconnect()}
    }
    private fun update(version:Long,name:String,asset:(String)->String):AppUpdate? {
        if(version<=BuildConfig.VERSION_CODE)return null
        val checksum=text(asset("james.apk.sha256"),true).trim().split(Regex("\\s+")).first()
        require(checksum.matches(Regex("[0-9a-fA-F]{64}")))
        return AppUpdate(version,name,asset("james.apk"),checksum.lowercase())
    }
    private fun apiUpdate(repo:String):AppUpdate? {
        val release=json.parseToJsonElement(text("https://api.github.com/repos/$repo/releases/latest")).jsonObject
        fun asset(name:String):String {
            val row=release.array("assets").map {it.jsonObject}.firstOrNull {it.text("name")==name}?:error("Release is missing $name")
            return UpdatePolicy.asset(repo,row["id"]?.jsonPrimitive?.longOrNull?:error("Invalid release asset ID."))
        }
        val meta=json.parseToJsonElement(text(asset("james-version.json"),true)).jsonObject
        return update(meta.number("versionCode").toLong(),meta.text("versionName"),::asset)
    }
    private fun publicReleaseUpdate(repo:String):AppUpdate? {
        val tag=latestPublicReleaseTag(repo)
        fun asset(name:String)=UpdatePolicy.publicAsset(repo,tag,name)
        val meta=json.parseToJsonElement(text(asset("james-version.json"),true)).jsonObject
        return update(meta.number("versionCode").toLong(),meta.text("versionName"),::asset)
    }
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val repo=BuildConfig.UPDATE_REPOSITORY
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "The release repository is not configured in this build." }
        runCatching {apiUpdate(repo)}.getOrElse {apiFailure->runCatching {publicReleaseUpdate(repo)}.getOrElse {throw apiFailure}}
    }
    suspend fun download(update: AppUpdate,onProgress: (Int)->Unit): File = withContext(Dispatchers.IO) {
        val dir=File(context.cacheDir,"updates").apply {mkdirs()};val part=File(dir,"james.part");val target=File(dir,"james.apk");val c=connection(update.apk,true)
        try {require(c.responseCode==200){responseError(c.responseCode)};val total=c.contentLengthLong;require(total<=200L*1024*1024){"APK is too large."};var read=0L;val digest=MessageDigest.getInstance("SHA-256")
            c.inputStream.use {input->part.outputStream().use {out->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;read+=n;require(read<=200L*1024*1024);out.write(buffer,0,n);digest.update(buffer,0,n);if(total>0)onProgress((read*100/total).toInt())}}}
            require(digest.digest().joinToString(""){"%02x".format(it)}==update.checksum){"APK checksum did not match. Download rejected."}
            val pm=context.packageManager;val archive=pm.getPackageArchiveInfo(part.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)?:error("Invalid APK.")
            val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
            require(archive.packageName==context.packageName && archive.longVersionCode==update.code){"APK identity or version does not match."}
            val old=installed.signingInfo?.apkContentsSigners?.map {sha(it.toByteArray())}?.toSet().orEmpty()
            val new=archive.signingInfo?.apkContentsSigners?.map {sha(it.toByteArray())}?.toSet().orEmpty()
            require(old.isNotEmpty() && old==new){"APK signing certificate changed. Update rejected."}
            if(target.exists())target.delete();check(part.renameTo(target));onProgress(100);target
        } catch(e: Exception){part.delete();throw e}finally {c.disconnect()}
    }
    fun installIntent(file: File): Intent {require(file.canonicalFile.parentFile==File(context.cacheDir,"updates").canonicalFile);return Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context,context.packageName+".files",file),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)}
}
