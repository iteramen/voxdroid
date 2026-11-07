package io.github.iteramen.voxdroid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable configuration only. Privacy consent, history, audio and Android permissions are not configuration. */
internal data class ConfigSnapshot(val settings: Map<String, Any>, val secrets: Map<String, Any>)

internal object ConfigBackup {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val ITERATIONS = 600_000
    private const val MAGIC = 0x56445842 // VDXB
    private const val VERSION = 1
    private const val HEADER_SIZE = 40 // magic, version, work factor, salt, nonce
    private val text = setOf("stt_base_url", "stt_model", "stt_prompt", "polish_base_url", "polish_model",
        "chip_languages", "no_field_mode")
    private val flags = setOf("allow_insecure_http", "polish_enabled", "capitalize_first_word", "strip_terminal_period",
        "trigger_volume", "trigger_button", "allow_during_call", "haptic_cues", "audio_cues")
    private val whole = setOf("stt_timeout_s", "polish_min_words", "polish_timeout_s", "volume_hold_ms",
        "cue_volume", "pill_bottom_above_keyboard")
    private val language = Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")

    private fun lane(key: String, prefix: String): Boolean = key.startsWith(prefix) && language.matches(key.removePrefix(prefix))

    fun allowed(key: String, secret: Boolean): Boolean = if (secret) {
        key in setOf("stt_token", "polish_token") || lane(key, "lane_token_")
    } else key in text || key in flags || key in whole || key == "no_speech_threshold" ||
        lane(key, "lane_base_url_") || lane(key, "lane_inherit_token_") || lane(key, "explicit_")

    fun snapshot(settings: Map<String, *>, secrets: Map<String, *>): ConfigSnapshot = ConfigSnapshot(
        settings.filterKeys { allowed(it, false) }.mapValues { requireNotNull(it.value) },
        secrets.filterKeys { allowed(it, true) }.mapValues { requireNotNull(it.value) },
    ).also(::validate)

    fun validate(snapshot: ConfigSnapshot) {
        for ((secret, entries) in listOf(false to snapshot.settings, true to snapshot.secrets)) {
            require(entries.size <= 1024)
            for ((key, value) in entries) {
                require(allowed(key, secret))
                when {
                    secret || key in text || lane(key, "lane_base_url_") -> require(value is String && value.length <= 262144)
                    key in flags || lane(key, "lane_inherit_token_") || lane(key, "explicit_") -> require(value is Boolean)
                    key in whole -> {
                        require(value is Int && value >= 0)
                        if (key == "cue_volume") require(value <= 100)
                        if (key == "volume_hold_ms") require(value >= MIN_HOLD_MS)
                    }
                    key == "no_speech_threshold" -> require(value is Float && value.isFinite() && value in 0f..1f)
                }
                if (key == "no_field_mode") require(value in setOf("refuse", "clipboard"))
            }
        }
    }

    fun readBounded(input: InputStream): ByteArray {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES)
        return bytes
    }

    fun encrypt(snapshot: ConfigSnapshot, password: CharArray): ByteArray {
        require(password.size in 12..1024)
        validate(snapshot)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(MAGIC); it.writeInt(VERSION); it.writeInt(ITERATIONS); it.write(salt); it.write(nonce)
        } }.toByteArray()
        val plain = encode(snapshot)
        return try {
            val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce)
            cipher.updateAAD(header)
            (header + cipher.doFinal(plain)).also { require(it.size <= MAX_BYTES) }
        } finally { plain.fill(0) }
    }

    fun decrypt(bytes: ByteArray, password: CharArray): ConfigSnapshot {
        require(bytes.size in (HEADER_SIZE + 16)..MAX_BYTES && password.size in 12..1024)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC && input.readInt() == VERSION && input.readInt() == ITERATIONS)
        val salt = ByteArray(16).also(input::readFully)
        val nonce = ByteArray(12).also(input::readFully)
        val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, nonce)
        cipher.updateAAD(bytes, 0, HEADER_SIZE)
        val plain = cipher.doFinal(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        return try { decode(plain) } finally { plain.fill(0) }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try { Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        } } finally { key.fill(0) }
    }

    private fun encode(snapshot: ConfigSnapshot): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            for (entries in listOf(snapshot.settings, snapshot.secrets)) {
                out.writeInt(entries.size)
                for ((key, value) in entries.toSortedMap()) {
                    out.writeUTF(key)
                    when (value) {
                        is String -> { out.writeByte(1); val raw = value.toByteArray(Charsets.UTF_8)
                            try { out.writeInt(raw.size); out.write(raw) } finally { raw.fill(0) } }
                        is Boolean -> { out.writeByte(2); out.writeBoolean(value) }
                        is Int -> { out.writeByte(3); out.writeInt(value) }
                        is Float -> { out.writeByte(4); out.writeFloat(value) }
                        else -> error("Unsupported configuration type")
                    }
                }
            }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES - HEADER_SIZE - 16) }

    private fun decode(bytes: ByteArray): ConfigSnapshot {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        fun entries(): Map<String, Any> {
            val size = input.readInt().also { require(it in 0..1024) }
            val result = mutableMapOf<String, Any>()
            repeat(size) {
                val key = input.readUTF()
                require(!result.containsKey(key))
                result[key] = when (input.readUnsignedByte()) {
                    1 -> {
                        val length = input.readInt().also { require(it in 0..MAX_BYTES && it <= input.available()) }
                        val raw = ByteArray(length).also(input::readFully)
                        try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString() }
                            finally { raw.fill(0) }
                    }
                    2 -> input.readUnsignedByte().also { require(it in 0..1) } == 1
                    3 -> input.readInt()
                    4 -> input.readFloat()
                    else -> error("Unsupported configuration type")
                }
            }
            return result
        }
        return ConfigSnapshot(entries(), entries()).also { require(input.available() == 0); validate(it) }
    }
}

/** The caller holds the UI thread and has checked that no request is active. Fail closed across two files. */
internal fun restoreConfig(snapshot: ConfigSnapshot, read: () -> ConfigSnapshot,
    writeSettings: (Map<String, Any>) -> Boolean, writeSecrets: (Map<String, Any>) -> Boolean) {
    ConfigBackup.validate(snapshot)
    val before = read()
    check(writeSecrets(emptyMap())) // Never expose old credentials to a new endpoint, including across a crash.
    try {
        check(writeSettings(snapshot.settings))
        check(writeSecrets(snapshot.secrets))
        check(read() == snapshot)
    } catch (failed: Exception) {
        if (writeSecrets(emptyMap()) && writeSettings(before.settings)) writeSecrets(before.secrets)
        throw IllegalStateException("Restore failed; retry from the backup")
    }
}
