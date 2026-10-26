package com.example.relaynet.util

import android.content.ClipData
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
     * Fast Share (Quick Share / Nearby Share / ShareIt / ShareMe / Wi-Fi Direct).
     * Transmits the APK without internet.
     */
    fun shareAppFast(context: Context) {
        prepareAndShare(
            context = context,
            asZip = false,
            chooserTitle = "Share RelayNet APK (Quick Share / ShareIt)"
        )
    }

    /**
     * Packages the APK into a compressed ZIP file (RelayNet_Offline.zip).
     */
    fun shareAppAsZip(context: Context) {
        Toast.makeText(context, "Compressing RelayNet into ZIP archive...", Toast.LENGTH_SHORT).show()
        prepareAndShare(
            context = context,
            asZip = true,
            chooserTitle = "Share RelayNet Compressed ZIP"
        )
    }

    /**
     * Standard Bluetooth / Offline Share.
     */
    fun shareAppViaBluetooth(context: Context) {
        prepareAndShare(
            context = context,
            asZip = false,
            chooserTitle = "Share RelayNet via Bluetooth / Nearby"
        )
    }

    private fun prepareAndShare(
        context: Context,
        asZip: Boolean,
        chooserTitle: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appInfo = context.applicationInfo
                val originalApk = File(appInfo.sourceDir)

                val targetFile: File

                if (asZip) {
                    targetFile = File(context.cacheDir, "RelayNet_Offline.zip")
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(targetFile))).use { zos ->
                        zos.setLevel(Deflater.BEST_COMPRESSION)
                        val entry = ZipEntry("RelayNet.apk")
                        zos.putNextEntry(entry)
                        FileInputStream(originalApk).use { fis ->
                            fis.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                } else {
                    targetFile = File(context.cacheDir, "RelayNet.apk")
                    if (!targetFile.exists() || targetFile.length() != originalApk.length()) {
                        FileInputStream(originalApk).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "com.example.relaynet.fileprovider",
                    targetFile
                )

                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        // Using */* allows Quick Share, ShareIt, Bluetooth, WhatsApp, Files to accept and send
                        type = "*/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, targetFile.name)
                        // ClipData is required on Android 10+ for Intent.createChooser to grant URI permissions to external apps
                        clipData = ClipData.newRawUri(targetFile.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val chooser = Intent.createChooser(intent, chooserTitle).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(chooser)
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
