package jp.nano2.aloerina.nfchcepractice

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import dev.keiji.apdu.ApduCommand

private fun ByteArray.toHex(): String = joinToString(":") { "%02x".format(it).uppercase() }
private fun Byte.toUnsignedInt(): Int = (this.toInt() and 0xFF)

/**
 * CC(Capability Container)ファイル。
 * この後やり取りするNDEFファイルの仕様などが表現されている
 */
private val ccFile = byteArrayOf(
    0x00, 0x0F, // CCLEN(Indicates the size of this CC)
    0x20, // Mapping Version(Mapping Version 2.0)
    0x00, 0x3B, // Maximum R-APDU data size
    0x00, 0x34, // Maximum C-APDU data size
    0x04, 0x06, // Tag & Length
    0xE1.toByte(), 0x04, // NDEF File Identifier
    0x00, 0x32, // Maximum NDEF size
    0x00, // NDEF file read access granted
    0xFF.toByte(), // NDEF File write access denied
)

/**
 * SELECTコマンドでSelectしようとしている対象
 */
enum class SelectTarget {
    /** Application。最初にくるSelect対象なので、定義はしたが実装では使っていない */
    APP,
    /** CC(Capability Container)ファイル */
    CC_FILE,
    /** NDEFレコードファイル（レコード長情報） */
    NDEF_FILE_LENGTH,
    /** NDEFレコードファイル（レコード本体情報） */
    NDEF_FILE;
}

/**
 * Host-based Card EmulationのAPDUコマンドをやり取りするためのServiceクラス。
 * 本実装では最低限の実装のみで、エラーハンドリングなどは適当になっていることに留意
 */
class HceService : HostApduService() {
    companion object {
        private val TAG: String = HceService::class.java.simpleName
    }

    var selectTarget: SelectTarget? = null

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "processCommandApdu:${commandApdu.toHex()}")
        val apdu = ApduCommand.readFrom(commandApdu, 0)
        val ins = apdu.header.ins.toUnsignedInt()

        val response = when(ins) {
            0xA4 -> {
                // 0xA4: SELECT
                val body = apdu.body.data
                Log.d(TAG, "processCommandApdu: Select body:${body.toHex()}")
                if (body[0] == 0xE1.toByte() && body[1] == 0x03.toByte()) {
                    // CC(Capability Container)ファイルのSELECT要求
                    Log.d(TAG, "processCommandApdu: Select CC File")
                    selectTarget = SelectTarget.CC_FILE
                } else if (body[0] == 0xE1.toByte() && body[1] == 0x04.toByte()) {
                    // NDEFレコードファイルのSELECT要求
                    // 実際には、まずNDEFレコードファイルのファイル長情報を受け取り、続いてNDEFレコード本体を受け取る
                    Log.d(TAG, "processCommandApdu: Select NDEF Record File Length")
                    selectTarget = SelectTarget.NDEF_FILE_LENGTH
                }

                byteArrayOf(0x90.toByte(), 0x00)
            }
            0xB0 -> {
                // 0xB0: READ_BINARY（ファイル読み出し）
                val offset = apdu.header.p1 * 256 + apdu.header.p2
                val le = apdu.body.getLe()

                val response2 = when(selectTarget) {
                    SelectTarget.CC_FILE -> {
                        // CCファイルのREAD_BINARY要求
                        Log.d(TAG, "processCommandApdu: READ_BINARY CC File")
                        if (offset == 0 && le == ccFile.size) {
                            // CCファイルはoffset == 0 && le == CCファイルの長さである必要がある
                            ccFile + byteArrayOf(0x90.toByte(), 0x00)
                        } else {
                            byteArrayOf(0x6A.toByte(), 0x82.toByte())
                        }
                    }
                    SelectTarget.NDEF_FILE_LENGTH -> {
                        // NDEFレコードファイル（レコード長情報）のREAD_BINARY要求
                        Log.d(TAG, "processCommandApdu: READ_BINARY NDEF Record File Length")
                        selectTarget = SelectTarget.NDEF_FILE
                        val ndefMessageByteArray = createNdefRecordByteArray()
                        val messageLen = byteArrayOf(((0xFF00 and ndefMessageByteArray.size) / 256).toByte(), (0x00FF and ndefMessageByteArray.size).toByte())
                        messageLen + byteArrayOf(0x90.toByte(), 0x00)
                    }
                    SelectTarget.NDEF_FILE -> {
                        // NDEFレコードファイル（レコード本体情報）のREAD_BINARY要求
                        Log.d(TAG, "processCommandApdu: READ_BINARY NDEF Record File")
                        createNdefRecordByteArray() + byteArrayOf(0x90.toByte(), 0x00)
                    }
                    else -> byteArrayOf(0x90.toByte(), 0x00)
                }

                Log.i(TAG, "processCommandApdu:${response2.toHex()}")
                return response2
            }
            else -> {
                // Other:
                byteArrayOf(0x6A.toByte(), 0x82.toByte())
            }
        }

        return response
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "onDeactivated: ")
        selectTarget = null
    }

    /**
     * NDEFレコード本体のByte配列を生成する
     */
    private fun createNdefRecordByteArray(): ByteArray {
        val uriRecord = NdefRecord.createUri("https://www.google.co.jp/")
        val ndefMessage = NdefMessage(uriRecord)
        return ndefMessage.toByteArray()
    }
}