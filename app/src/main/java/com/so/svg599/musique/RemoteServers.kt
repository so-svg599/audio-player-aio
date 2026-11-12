package com.so.svg599.musique

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.source
import java.util.EnumSet

/** Configuration d'une connexion distante. Les secrets ne sont sérialisés que dans les préférences chiffrées. */
data class RemoteProfile(val id:String, val type:String, val name:String, val host:String, val share:String="", val port:Int=if(type=="ftp") 21 else 445, val username:String="", val password:String="", val domain:String="", val passive:Boolean=true, val ftps:Boolean=false)
data class RemoteEntry(val name:String,val path:String,val directory:Boolean,val size:Long=0)

object RemoteProfiles {
    private fun prefs(c:Context)=EncryptedSharedPreferences.create(c,"remote_profiles_secure",MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun all(c:Context):List<RemoteProfile> = runCatching { val a=JSONArray(prefs(c).getString("profiles","[]")); (0 until a.length()).map { val o=a.getJSONObject(it); RemoteProfile(o.getString("id"),o.getString("type"),o.getString("name"),o.getString("host"),o.optString("share"),o.optInt("port",21),o.optString("username"),o.optString("password"),o.optString("domain"),o.optBoolean("passive",true),o.optBoolean("ftps",false)) } }.getOrDefault(emptyList())
    fun save(c:Context,p:RemoteProfile) { val list=all(c).filterNot{it.id==p.id}+p; val a=JSONArray(); list.forEach { x-> a.put(JSONObject().put("id",x.id).put("type",x.type).put("name",x.name).put("host",x.host).put("share",x.share).put("port",x.port).put("username",x.username).put("password",x.password).put("domain",x.domain).put("passive",x.passive).put("ftps",x.ftps)) }; prefs(c).edit().putString("profiles",a.toString()).apply() }
    fun delete(c:Context,id:String) { val a=JSONArray(); all(c).filterNot{it.id==id}.forEach{x->a.put(JSONObject().put("id",x.id).put("type",x.type).put("name",x.name).put("host",x.host).put("share",x.share).put("port",x.port).put("username",x.username).put("password",x.password).put("domain",x.domain).put("passive",x.passive).put("ftps",x.ftps))}; prefs(c).edit().putString("profiles",a.toString()).apply() }
    fun get(c:Context,id:String)=all(c).firstOrNull{it.id==id}
}

object RemoteFiles {
    fun list(p:RemoteProfile,path:String):List<RemoteEntry> = if(p.type=="smb") listSmb(p,path) else listFtp(p,path)
    private fun listSmb(p:RemoteProfile,path:String):List<RemoteEntry> {
        return SMBClient().use { client -> client.connect(p.host,445).use { conn -> conn.authenticate(AuthenticationContext(p.username,p.password.toCharArray(),p.domain)).use { session -> (session.connectShare(p.share) as DiskShare).use { share ->
            val dir=path.trim('/')
            val entries=share.list(dir.ifEmpty { "\\" }).mapNotNull { f ->
                val n=f.fileName
                if(n=="."||n=="..") null else {
                    val child=listOf(dir,n).filter{it.isNotEmpty()}.joinToString("/")
                    val isDirectory = (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    RemoteEntry(n,child,isDirectory,f.endOfFile)
                }
            }
            entries.sortedWith(compareByDescending<RemoteEntry>{it.directory}.thenBy{it.name.lowercase()})
        } } } }
    }
    private fun listFtp(p:RemoteProfile,path:String):List<RemoteEntry> {
        val ftp=client(p); try { ftp.connect(p.host,p.port); if(!ftp.login(p.username,p.password)) throw IOException("Connexion FTP refusée"); if(p.ftps){(ftp as FTPSClient).execPBSZ(0);ftp.execPROT("P")}; if(p.passive)ftp.enterLocalPassiveMode() else ftp.enterLocalActiveMode(); ftp.setFileType(FTP.BINARY_FILE_TYPE); return ftp.listFiles(path.ifBlank{"/"}).filter{it.name!="."&&it.name!=".."}.map{ val child=(path.trimEnd('/')+"/"+it.name).let{v->if(v.startsWith("/"))v else "/$v"}; RemoteEntry(it.name,child,it.isDirectory,it.size) }.sortedWith(compareByDescending<RemoteEntry>{it.directory}.thenBy{it.name.lowercase()}) } finally { if(ftp.isConnected) runCatching{ftp.logout();ftp.disconnect()} }
    }
    private fun client(p:RemoteProfile):FTPClient=if(p.ftps) FTPSClient("TLS",false) else FTPClient()
    fun uri(p:RemoteProfile,path:String):Uri=Uri.parse("${if(p.type=="smb")"smbstream" else "ftpstream"}://${p.id}/${Uri.encode(path.trim('/'), "/")}")
}

/** Upload direct du fichier original vers un profil SMB/FTP existant. */
object RemoteUploads {
    fun upload(context: Context, destination: String, local: java.io.File, filename: String, progress: (Long, Long) -> Unit = { _, _ -> }) {
        when {
            destination.startsWith("smb:") || destination.startsWith("ftp:") -> uploadRemote(context, destination.substringAfter(':'), local, filename, progress)
            destination == "pcloud" -> uploadPcloud(context, local, filename, progress)
            else -> throw IOException("Destination non reconnue")
        }
    }
    private fun uploadRemote(context: Context, key: String, local: java.io.File, filename: String, progress: (Long, Long) -> Unit) {
        val id = key.substringAfter(':')
        val profile = RemoteProfiles.get(context, id) ?: throw IOException("Profil SMB/FTP introuvable")
        val remoteName = filename.replace('\\', '_').replace('/', '_')
        if (profile.type == "smb") {
            SMBClient().use { client -> client.connect(profile.host, profile.port).use { conn -> conn.authenticate(AuthenticationContext(profile.username, profile.password.toCharArray(), profile.domain)).use { session ->
                (session.connectShare(profile.share) as DiskShare).use { share ->
                    val path = remoteName
                    share.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OVERWRITE_IF, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { out ->
                        local.inputStream().use { input -> val b=ByteArray(128*1024); var n:Long=0; while(true){val r=input.read(b);if(r<0)break;out.write(b,n,0,r);n+=r;progress(n,local.length())} }
                    }
                }
            } } }
        } else {
            val ftp: FTPClient = if(profile.ftps) FTPSClient("TLS",false) else FTPClient()
            try {
                ftp.connect(profile.host, profile.port)
                if(!ftp.login(profile.username,profile.password)) throw IOException("Connexion FTP refusée")
                if(profile.ftps){(ftp as FTPSClient).execPBSZ(0);ftp.execPROT("P")}
                if(profile.passive)ftp.enterLocalPassiveMode() else ftp.enterLocalActiveMode()
                ftp.setFileType(FTP.BINARY_FILE_TYPE)
                val remotePath = listOf(profile.share.trimEnd('/'), remoteName).filter{it.isNotBlank()}.joinToString("/").ifBlank{remoteName}
                local.inputStream().use { input -> if(!ftp.storeFile(remotePath, input)) throw IOException("Upload FTP impossible: ${ftp.replyString}") }
                progress(local.length(),local.length())
            } finally { if(ftp.isConnected) runCatching{ftp.logout();ftp.disconnect()} }
        }
    }
    private fun uploadPcloud(context: Context, local: java.io.File, filename: String, progress: (Long, Long) -> Unit) {
        val saved = SecureCredentials.load(context) ?: throw IOException("Connecte-toi à pCloud et active « mémoriser » avant le transfert API")
        val client = okhttp3.OkHttpClient.Builder().callTimeout(java.time.Duration.ofMinutes(15)).build()
        val loginUrl = "https://api.pcloud.com/userinfo?getauth=1&username=${java.net.URLEncoder.encode(saved.email,"UTF-8")}&password=${java.net.URLEncoder.encode(saved.password,"UTF-8")}"
        val token = client.newCall(okhttp3.Request.Builder().url(loginUrl).build()).execute().use { r -> val obj=org.json.JSONObject(r.body?.string().orEmpty()); if(obj.optInt("result",-1)!=0) throw IOException("Connexion API pCloud refusée (${obj.optInt("result")})"); obj.optString("auth").takeIf{it.isNotBlank()} ?: throw IOException("Jeton pCloud absent") }
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file",filename, object: okhttp3.RequestBody(){ override fun contentType()= "application/octet-stream".toMediaTypeOrNull(); override fun contentLength()=local.length(); override fun writeTo(sink: okio.BufferedSink){ local.source().use { source -> var n:Long=0; val b=okio.Buffer(); while(source.read(b,128*1024)!=-1L){val size=b.size;sink.write(b,size);n+=size;progress(n,local.length())} } } })
            .build()
        val req=okhttp3.Request.Builder().url("https://api.pcloud.com/uploadfile?auth=$token&path=${java.net.URLEncoder.encode("/", "UTF-8")}").post(body).build()
        client.newCall(req).execute().use { r -> val text=r.body?.string().orEmpty(); if(!r.isSuccessful) throw IOException("Upload pCloud HTTP ${r.code}"); val obj=org.json.JSONObject(text); if(obj.optInt("result",-1)!=0) throw IOException("Upload pCloud refusé (${obj.optInt("result")})") }
    }
}

/** Range-aware Media3 source. SMB reads by absolute offset; FTP uses the REST restart marker where supported. */
class RemoteServerDataSource(private val context:Context):DataSource {
    private var uri:Uri?=null; private var position=0L; private var remaining=C.LENGTH_UNSET.toLong(); private var ftp:FTPClient?=null; private var ftpStream:java.io.InputStream?=null; private var smbClient:SMBClient?=null; private var smbShare:DiskShare?=null; private var smbFile:SmbFile?=null
    override fun addTransferListener(listener:TransferListener){}
    override fun open(dataSpec:DataSpec):Long {
        uri=dataSpec.uri; val p=RemoteProfiles.get(context,dataSpec.uri.authority?:"")?:throw IOException("Profil SMB/FTP introuvable"); position=dataSpec.position
        if(p.type=="smb") {
            val path=dataSpec.uri.path.orEmpty().trim('/').replace('/', '\\')
            smbClient=SMBClient(); val c=smbClient!!.connect(p.host,445); val s=c.authenticate(AuthenticationContext(p.username,p.password.toCharArray(),p.domain)); smbShare=s.connectShare(p.share) as DiskShare
            smbFile=smbShare!!.openFile(path,EnumSet.of(AccessMask.GENERIC_READ),null,EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ,SMB2ShareAccess.FILE_SHARE_WRITE,SMB2ShareAccess.FILE_SHARE_DELETE),SMB2CreateDisposition.FILE_OPEN,EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            remaining=C.LENGTH_UNSET.toLong()
        } else {
            ftp=if(p.ftps) FTPSClient("TLS",false) else FTPClient(); val f=ftp!!; f.connect(p.host,p.port); if(!f.login(p.username,p.password)) throw IOException("Connexion FTP refusée"); if(p.ftps)(f as FTPSClient).execPBSZ(0).also{(f as FTPSClient).execPROT("P")}; if(p.passive)f.enterLocalPassiveMode() else f.enterLocalActiveMode(); f.setFileType(FTP.BINARY_FILE_TYPE)
            val path=dataSpec.uri.path.orEmpty().ifBlank{"/"}; remaining=(f.mlistFile(path)?.size?:-1L)-position
            if(position>0) f.setRestartOffset(position)
            ftpStream=f.retrieveFileStream(path)?:throw IOException("Ouverture FTP impossible: ${f.replyString}")
        }
        if(dataSpec.length!=C.LENGTH_UNSET.toLong()) remaining=minOf(remaining,dataSpec.length)
        return if(remaining<0) C.LENGTH_UNSET.toLong() else remaining
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int { if(length==0)return 0; if(remaining==0L)return C.RESULT_END_OF_INPUT; val want=if(remaining<0)length else minOf(length.toLong(),remaining).toInt(); val n=if(smbFile!=null)smbFile!!.read(buffer,position,offset,want) else ftpStream!!.read(buffer,offset,want); if(n<0)return C.RESULT_END_OF_INPUT; position+=n; if(remaining>0)remaining-=n; return n }
    override fun getUri():Uri?=uri
    override fun close(){ runCatching{ftpStream?.close()}; runCatching{ftp?.completePendingCommand()}; runCatching{ftp?.logout();ftp?.disconnect()}; runCatching{smbFile?.close()}; runCatching{smbShare?.close()}; runCatching{smbClient?.close()}; ftpStream=null;ftp=null;smbFile=null;smbShare=null;smbClient=null;uri=null }
    class Factory(private val context:Context):DataSource.Factory { override fun createDataSource():DataSource=RemoteServerDataSource(context) }
}

class RoutingRemoteDataSource(private val context:Context, private val fallback:DataSource):DataSource {
 private var active:DataSource?=null
 private val listeners=mutableListOf<TransferListener>()
 override fun addTransferListener(listener:TransferListener){ listeners.add(listener) }
 override fun open(spec:DataSpec):Long { val selected=if(spec.uri.scheme=="smbstream"||spec.uri.scheme=="ftpstream") RemoteServerDataSource(context) else fallback; listeners.forEach{selected.addTransferListener(it)}; active=selected; return selected.open(spec) }
 override fun read(buffer:ByteArray,offset:Int,length:Int)=active?.read(buffer,offset,length)?:C.RESULT_END_OF_INPUT
 override fun getUri():Uri?=active?.uri
 override fun close(){try{active?.close()}finally{active=null}}
}
