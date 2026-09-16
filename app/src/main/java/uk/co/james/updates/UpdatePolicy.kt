package uk.co.james.updates
import java.net.URI

object UpdatePolicy {
    fun repository(value:String):String {require(value.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))){"Release repository is not configured."};return value}
    fun https(value:String):URI {
        val uri=URI(value)
        require(uri.scheme=="https" && uri.host!=null && uri.rawUserInfo==null && uri.port in listOf(-1,443)){"Unsafe update URL rejected."}
        return uri
    }
    fun canAuthorize(value:String,repo:String):Boolean {
        val uri=runCatching {https(value)}.getOrNull()?:return false
        return uri.host=="api.github.com" && uri.rawPath.startsWith("/repos/${repository(repo)}/") && !uri.rawPath.contains("..") && !uri.rawPath.contains('%')
    }
    fun asset(repo:String,id:Long):String {require(id>0);return "https://api.github.com/repos/${repository(repo)}/releases/assets/$id"}
    fun releaseTag(repo:String,location:String):String? {
        val uri=runCatching {URI(location)}.getOrNull()?:return null
        if(uri.isAbsolute&&(uri.scheme!="https"||uri.host!="github.com"||uri.rawUserInfo!=null||uri.port !in listOf(-1,443)))return null
        if(uri.rawQuery!=null||uri.rawFragment!=null)return null
        val prefix="/${repository(repo)}/releases/tag/"
        val path=uri.rawPath?:return null
        val tag=path.removePrefix(prefix)
        return tag.takeIf {path.startsWith(prefix)&&it.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))}
    }
    fun publicAsset(repo:String,tag:String,name:String):String {
        require(releaseTag(repo,"/${repository(repo)}/releases/tag/$tag")==tag)
        require(name.matches(Regex("[A-Za-z0-9_.-]+")))
        return "https://github.com/${repository(repo)}/releases/download/$tag/$name"
    }
}
