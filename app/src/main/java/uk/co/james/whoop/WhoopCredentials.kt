package uk.co.james.whoop

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import uk.co.james.core.*
import kotlinx.serialization.json.jsonObject

/** Device-only WHOOP connection key. Never included in James exports or Android backup. */
class WhoopCredentials(context:Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"whoop-device-access"))
    private val alias="james-whoop-device-access-v1"
    fun configured()=file.baseFile.exists()
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read():String {
        if(!configured())return ""
        try {
            val payload=json.parseToJsonElement(file.readFully().toString(Charsets.UTF_8)).jsonObject
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(payload.text("iv"),Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(payload.text("data"),Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }catch(e:Exception){throw IllegalStateException("WHOOP access could not be unlocked. Remove it and save a new token.")}
    }
    @Synchronized fun save(value:String) {
        val token=value.trim();require(token.length in 20..1024 && token.none {it.isWhitespace()}) {"Enter a valid WHOOP access token."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val payload=fields("iv" to p(Base64.encodeToString(cipher.iv,Base64.NO_WRAP)),"data" to p(Base64.encodeToString(cipher.doFinal(token.toByteArray()),Base64.NO_WRAP)))
        val out=file.startWrite()
        try {out.write(payload.toString().toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    @Synchronized fun clear(){file.delete()}
}
