package com.example.relaynet.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactHelper {

    /**
     * Resolves a peer's identity by checking if their phone number matches an entry
     * in the local Android phonebook. This prevents impersonation / Sybil / spoofing attacks offline.
     */
    fun resolveContactName(context: Context, rawPeerIdentifier: String): String {
        val cleanNumber = rawPeerIdentifier.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.length < 7) {
            return rawPeerIdentifier
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return rawPeerIdentifier
        }

        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(cleanNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val contactName = it.getString(nameIndex)
                        if (!contactName.isNullOrEmpty()) {
                            return "$contactName ($cleanNumber)"
                        }
                    }
                }
            }
            // If number was not found in user's saved phonebook
            "$cleanNumber (Unsaved)"
        } catch (e: Exception) {
            rawPeerIdentifier
        }
    }
}
