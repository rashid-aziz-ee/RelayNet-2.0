package com.example.relaynet.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ApkShareUtil {

    /**
     * Fast Share (Quick Share / Nearby Share / Wi-Fi Direct).
     * Transmits the APK at 30-50 MB/s (1-2 seconds) without internet.
     * Recipient gets direct 1-tap installation.
     */
    fun shareAppFast(context: Context) {
        prepareAndShare(
            context = context,
            asZip = false,
            forceBluetooth = false,
            chooserTitle = "Fast Share RelayNet (Quick Share / Nearby)"
        )
    }

    /**
     * Packages the APK into a compressed ZIP file (RelayNet_Offline.zip)
     * using maximum Deflate compression.
     */
    fun shareAppAsZip(context: Context) {
        Toast.makeText(context, "Compressing RelayNet into ZIP archive...", Toast.LENGTH_SHORT).show()
        prepareAndShare(
            context = context,
            asZip = true,
            forceBluetooth = false,
            chooserTitle = "Share RelayNet Compressed ZIP"
        )
    }

    /**
     * Legacy direct Bluetooth transfer (forces com.android.bluetooth).
     * Slower (150-250 KB/s) due to hardware Bluetooth OPP limits.
     */
    fun shareAppViaBluetooth(context: Context) {
        prepareAndShare(
            context = context,
            asZip = false,
            forceBluetooth = true,
            chooserTitle = "Share RelayNet via Bluetooth"
        )
    }

    private fun prepareAndShare(
        context: Context,
        asZip: Boolean,
        forceBluetooth: Boolean,
        chooserTitle: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appInfo = context.applicationInfo
                val originalApk = File(appInfo.sourceDir)

                val targetFile: File
                val mimeType: String

                if (asZip) {
                    targetFile = File(context.cacheDir, "RelayNet_Offline.zip")
                    // Compress original APK into a ZIP archive with highest compression
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(targetFile))).use { zos ->
                        zos.setLevel(Deflater.BEST_COMPRESSION)
                        val entry = ZipEntry("RelayNet.apk")
                        zos.putNextEntry(entry)
                        FileInputStream(originalApk).use { fis ->
                            fis.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                    mimeType = "application/zip"
                } else {
                    targetFile = File(context.cacheDir, "RelayNet_Offline.apk")
                    if (!targetFile.exists() || targetFile.length() != originalApk.length()) {
                        FileInputStream(originalApk).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    mimeType = "application/vnd.android.package-archive"
                }

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "com.example.relaynet.fileprovider",
                    targetFile
                )

                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, targetFile.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (forceBluetooth) {
                            setPackage("com.android.bluetooth")
                        }
                    }

                    if (forceBluetooth && intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        val chooser = Intent.createChooser(intent, chooserTitle).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(chooser)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error sharing app: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
