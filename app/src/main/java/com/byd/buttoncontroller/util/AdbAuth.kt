package com.byd.buttoncontroller.util

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 内嵌轻量 ADB 客户端：App 通过无线 ADB 连接车机自身（局域网 IP:5555），
 * 认证后执行 settings 授权命令，实现「重启后自动修复无障碍授权」。
 *
 * 2026-08-07 实车验证（DiLink 车机，Android 10，adbd 定制）的三个关键点：
 * 1. 签名：不是标准 SHA1withRSA，而是把 token 直接放进 SHA1 DigestInfo 的哈希字段，
 *    再 RSA 私钥加密（官方 adb v37 客户端行为）。车机 adbd 恢复 DigestInfo 后
 *    直接取哈希字段与 token 对比（不再重新 SHA1），所以标准 SHA1withRSA 永远失败。
 * 2. 公钥：adb_keys 只认 android_pubkey 编码（524 字节），openssh 格式会被
 *    adbd 以 "Invalid base64 key" 拒绝。
 * 3. RSAPUBLICKEY 帧 payload 必须以 null 终止（adbd 用 std::string(payload.data())
 *    截断，否则会把相邻内存垃圾读进 key）。
 *
 * 认证流程：CNXN -> TOKEN -> SIGNATURE（已信任 key 秒过）
 *          -> 未信任 -> TOKEN -> RSAPUBLICKEY（android_pubkey + \0）-> 车机弹窗
 *          -> TOKEN -> SIGNATURE（注册后重试，通过）-> CNXN
 */
object AdbAuth {

    const val ADB_PORT = 5555
    private const val VERSION = 0x01000001
    /** 与官方 adb 客户端一致的 maxdata（1MB），避免兼容问题 */
    private const val MAX_PAYLOAD = 0x00100000

    private const val CMD_CNXN = "CNXN"
    private const val CMD_AUTH = "AUTH"
    private const val CMD_OPEN = "OPEN"
    private const val CMD_OKAY = "OKAY"
    private const val CMD_CLSE = "CLSE"
    private const val CMD_WRTE = "WRTE"

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    /** SHA1 DigestInfo 前缀（不含 20 字节哈希） */
    private val DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
        0x1a, 0x05, 0x00, 0x04, 0x14
    )

    /** CNXN 的 features（与官方 adb 客户端一致） */
    private const val CNXN_FEATURES =
        "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_symlink_timestamp,apex,abb,file_sync_v2,fixed_push_mkdir,delayed_ack"

    class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class Frame(val cmd: String, val arg0: Int, val arg1: Int, val payload: ByteArray)

    // ---------------- 密钥管理 ----------------

    /** 从 App 私有目录加载或生成 RSA key（持久化，首次授权后复用）。 */
    fun loadOrCreateKeyPair(dir: File): KeyPair {
        val privFile = File(dir, "adbkey.pk8")
        val pubFile = File(dir, "adbkey.x509")
        try {
            if (privFile.exists() && pubFile.exists()) {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            }
        } catch (t: Throwable) {
            AppLog.w("加载 ADB key 失败，重新生成: ${t.message}")
        }
        dir.mkdirs()
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        AppLog.i("已生成新的 ADB 密钥")
        return kp
    }

    /**
     * android_pubkey 编码（524 字节）：
     * [4B words=64][4B n0inv][256B modulus LE][256B rr][4B exponent LE]。
     * 这是 adbd 的 adb_keys 唯一能解析的公钥格式（openssh 格式会被拒绝）。
     */
    fun androidPubKey(pub: PublicKey, comment: String = "byd-button-controller@local"): String {
        val rsa = pub as java.security.interfaces.RSAPublicKey
        val n = rsa.modulus
        val e = rsa.publicExponent

        // modulus 大端固定 256 字节 -> 反转成小端
        val nBE = ByteArray(256)
        val nb = n.toByteArray()
        System.arraycopy(nb, nb.size - 256, nBE, 0, 256)
        val modLE = ByteArray(256) { nBE[255 - it] }

        // n0inv = -n^{-1} mod 2^32
        val mod32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = mod32.subtract(n.mod(mod32).modInverse(mod32)).toInt()

        // rr = (2^4096) mod n，小端
        val r2 = BigInteger.ONE.shiftLeft(64 * 32 * 2).mod(n)
        val r2Fixed = ByteArray(256)
        val r2Arr = r2.toByteArray()
        System.arraycopy(r2Arr, r2Arr.size - 256, r2Fixed, 0, 256)
        val rr = ByteArray(256) { r2Fixed[255 - it] }

        val bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(64)
        bb.putInt(n0inv)
        bb.put(modLE)
        bb.put(rr)
        bb.putInt(e.toInt())
        return Base64.getEncoder().encodeToString(bb.array()) + " $comment"
    }

    // ---------------- adb 协议帧 ----------------

    private fun frame(cmd: String, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        val c = cmd.toByteArray(StandardCharsets.US_ASCII)
        val cmdInt = (c[0].toInt() and 0xff) or ((c[1].toInt() and 0xff) shl 8) or
                ((c[2].toInt() and 0xff) shl 16) or ((c[3].toInt() and 0xff) shl 24)
        // data_check：字节和（官方 calculate_apacket_checksum；接收端实际不校验，保持与官方一致）
        var sum = 0L
        for (b in payload) sum += (b.toInt() and 0xff)
        bb.putInt(cmdInt)
        bb.putInt(arg0)
        bb.putInt(arg1)
        bb.putInt(payload.size)
        bb.putInt((sum and 0xffffffffL).toInt())
        bb.putInt(cmdInt xor -1)
        bb.put(payload)
        return bb.array()
    }

    private fun readFrame(input: InputStream): Frame {
        val hdr = readExactly(input, 24)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmdInt = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        bb.int // data_check
        bb.int // magic
        val payload = readExactly(input, len)
        val cmd = buildString { for (i in 0..3) append(((cmdInt shr (8 * i)) and 0xff).toChar()) }
        return Frame(cmd, arg0, arg1, payload)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw AuthException("ADB 连接被关闭")
            off += r
        }
        return out
    }

    // ---------------- 签名 ----------------

    /**
     * 车机签名方式：DigestInfo(哈希字段 = token) + RSA 私钥加密（PKCS1 v1.5 padding）。
     * 注意：不是标准 SHA1withRSA！DiLink adbd 恢复 DigestInfo 后直接取哈希字段与
     * token 对比，不再重新 SHA1。
     */
    private fun signToken(kp: KeyPair, token: ByteArray): ByteArray {
        val digestInfo = ByteArray(35)
        System.arraycopy(DIGEST_INFO_PREFIX, 0, digestInfo, 0, 15)
        System.arraycopy(token, 0, digestInfo, 15, 20)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, kp.private)
        return cipher.doFinal(digestInfo)
    }

    // ---------------- 认证与执行 ----------------

    /**
     * 连接、认证并执行一条 shell 命令，返回输出文本。
     *
     * 注意：使用一次性命令模式 `shell:<command>`（官方 adb shell 行为），命令执行完
     * shell 自动退出并回 CLSE。不要用交互式 shell（OPEN shell: + WRTE 喂命令），
     * 那种模式 shell 不自动退出，会一直读到 SocketTimeout。
     *
     * @param authTimeoutMs 认证等待时间；首次注册需用户在车机弹窗点允许，
     *                      已信任的 key 秒过。默认 35 秒。
     */
    fun execShell(
        host: String,
        port: Int,
        kp: KeyPair,
        command: String,
        authTimeoutMs: Long = 35_000L
    ): String {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), 5000)
            authenticate(sock, kp, authTimeoutMs)

            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val localId = 1
            // 一次性 shell 命令：命令执行完自动 CLSE
            output.write(frame(CMD_OPEN, localId, 0, ("shell:" + command).toByteArray(StandardCharsets.UTF_8)))
            output.flush()

            val result = StringBuilder()
            while (true) {
                sock.soTimeout = 15_000
                val f = readFrame(input)
                when (f.cmd) {
                    CMD_OKAY -> Unit
                    CMD_WRTE -> {
                        result.append(String(f.payload, StandardCharsets.UTF_8))
                        output.write(frame(CMD_OKAY, f.arg1, f.arg0, ByteArray(0)))
                        output.flush()
                    }
                    CMD_CLSE -> break
                    else -> Unit
                }
            }
            return result.toString()
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun authenticate(sock: Socket, kp: KeyPair, authTimeoutMs: Long) {
        val input = sock.getInputStream()
        val output = sock.getOutputStream()
        val cnxnPayload = (CNXN_FEATURES + "\u0000").toByteArray(StandardCharsets.UTF_8)
        output.write(frame(CMD_CNXN, VERSION, MAX_PAYLOAD, cnxnPayload))
        output.flush()

        val deadline = System.currentTimeMillis() + authTimeoutMs
        var sentSig = false
        var sentKey = false
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) throw AuthException("ADB 认证超时（首次使用请在车机弹窗点允许）")
            sock.soTimeout = minOf(remain, 30_000L).toInt()
            val f = readFrame(input)
            when (f.cmd) {
                CMD_CNXN -> {
                    AppLog.i("ADB 认证成功")
                    return
                }
                CMD_AUTH -> when (f.arg0) {
                    AUTH_TOKEN -> {
                        when {
                            !sentSig -> {
                                // 已信任的 key：签名直接验证通过
                                output.write(frame(CMD_AUTH, AUTH_SIGNATURE, 0, signToken(kp, f.payload)))
                                output.flush()
                                sentSig = true
                                AppLog.i("ADB: 已发送签名")
                            }
                            !sentKey -> {
                                // 签名未被接受（key 未信任）-> 发公钥注册，触发车机弹窗
                                sendPublicKey(output, kp)
                                sentKey = true
                                AppLog.i("ADB: 已发送公钥，请在车机弹窗点允许")
                            }
                            else -> {
                                // 注册完成（用户已点允许）-> 重发签名，key 已入 adb_keys 应通过
                                output.write(frame(CMD_AUTH, AUTH_SIGNATURE, 0, signToken(kp, f.payload)))
                                output.flush()
                                AppLog.i("ADB: 注册后重发签名")
                            }
                        }
                    }
                    AUTH_RSAPUBLICKEY -> {
                        sendPublicKey(output, kp)
                        sentKey = true
                    }
                    else -> throw AuthException("ADB 认证未知响应: arg0=${f.arg0}")
                }
                else -> throw AuthException("ADB 认证异常: ${f.cmd}")
            }
        }
    }

    private fun sendPublicKey(output: OutputStream, kp: KeyPair) {
        // android_pubkey 格式 + null 终止符（adbd 用 std::string(payload.data()) 截断）
        val pubKeyStr = androidPubKey(kp.public)
        val keyBytes = (pubKeyStr + "\u0000").toByteArray(StandardCharsets.UTF_8)
        output.write(frame(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, keyBytes))
        output.flush()
    }
}
