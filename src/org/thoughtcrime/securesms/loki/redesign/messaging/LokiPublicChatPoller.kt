package org.thoughtcrime.securesms.loki.redesign.messaging

import android.content.Context
import android.os.Handler
import android.util.Log
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.then
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.jobs.PushDecryptJob
import org.thoughtcrime.securesms.jobs.RetrieveProfileAvatarJob
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.libsignal.util.guava.Optional
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceContent
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroup
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.loki.api.LokiPublicChat
import org.whispersystems.signalservice.loki.api.LokiPublicChatAPI
import org.whispersystems.signalservice.loki.api.LokiPublicChatMessage
import org.whispersystems.signalservice.loki.api.LokiStorageAPI
import org.whispersystems.signalservice.loki.messaging.LokiThreadFriendRequestStatus
import org.whispersystems.signalservice.loki.utilities.successBackground
import java.security.MessageDigest
import java.util.*

class LokiPublicChatPoller(private val context: Context, private val group: LokiPublicChat) {
    private val handler = Handler()
    private var hasStarted = false

    // region Convenience
    private val userHexEncodedPublicKey = TextSecurePreferences.getLocalNumber(context)
    private var displayNameUpdatees = setOf<String>()

    private val api: LokiPublicChatAPI
        get() = {
            val userPrivateKey = IdentityKeyUtil.getIdentityKeyPair(context).privateKey.serialize()
            val lokiAPIDatabase = DatabaseFactory.getLokiAPIDatabase(context)
            val lokiUserDatabase = DatabaseFactory.getLokiUserDatabase(context)
            LokiPublicChatAPI(userHexEncodedPublicKey, userPrivateKey, lokiAPIDatabase, lokiUserDatabase)
        }()
    // endregion

    // region Tasks
    private val pollForNewMessagesTask = object : Runnable {

        override fun run() {
            pollForNewMessages()
            handler.postDelayed(this, pollForNewMessagesInterval)
        }
    }

    private val pollForDeletedMessagesTask = object : Runnable {

        override fun run() {
            pollForDeletedMessages()
            handler.postDelayed(this, pollForDeletedMessagesInterval)
        }
    }

    private val pollForModeratorsTask = object : Runnable {

        override fun run() {
            pollForModerators()
            handler.postDelayed(this, pollForModeratorsInterval)
        }
    }

    private val pollForDisplayNamesTask = object : Runnable {
        override fun run() {
            pollForDisplayNames()
            handler.postDelayed(this, pollForDisplayNamesInterval)
        }
    }
    // endregion

    // region Settings
    companion object {
        private val pollForNewMessagesInterval: Long = 4 * 1000
        private val pollForDeletedMessagesInterval: Long = 20 * 1000
        private val pollForModeratorsInterval: Long = 10 * 60 * 1000
        private val pollForDisplayNamesInterval: Long = 60 * 1000
    }
    // endregion

    // region Lifecycle
    fun startIfNeeded() {
        if (hasStarted) return
        pollForNewMessagesTask.run()
        pollForDeletedMessagesTask.run()
        pollForModeratorsTask.run()
        pollForDisplayNamesTask.run()
        hasStarted = true
    }

    fun stop() {
        handler.removeCallbacks(pollForNewMessagesTask)
        handler.removeCallbacks(pollForDeletedMessagesTask)
        handler.removeCallbacks(pollForModeratorsTask)
        handler.removeCallbacks(pollForDisplayNamesTask)
        hasStarted = false
    }
    // endregion

    // region Polling
    private fun getDataMessage(message: LokiPublicChatMessage): SignalServiceDataMessage {
        val id = group.id.toByteArray()
        val serviceGroup = SignalServiceGroup(SignalServiceGroup.Type.UPDATE, id, SignalServiceGroup.GroupType.PUBLIC_CHAT, null, null, null, null)
        val quote = if (message.quote != null) {
            SignalServiceDataMessage.Quote(message.quote!!.quotedMessageTimestamp, SignalServiceAddress(message.quote!!.quoteeHexEncodedPublicKey), message.quote!!.quotedMessageBody, listOf())
        } else {
            null
        }
        val attachments = message.attachments.mapNotNull { attachment ->
            if (attachment.kind != LokiPublicChatMessage.Attachment.Kind.Attachment) { return@mapNotNull null }
            SignalServiceAttachmentPointer(
                attachment.serverID,
                attachment.contentType,
                ByteArray(0),
                Optional.of(attachment.size),
                Optional.absent(),
                attachment.width, attachment.height,
                Optional.absent(),
                Optional.of(attachment.fileName),
                false,
                Optional.fromNullable(attachment.caption),
                attachment.url)
        }
        val linkPreview = message.attachments.firstOrNull { it.kind == LokiPublicChatMessage.Attachment.Kind.LinkPreview }
        val signalLinkPreviews = mutableListOf<SignalServiceDataMessage.Preview>()
        if (linkPreview != null) {
            val attachment = SignalServiceAttachmentPointer(
                linkPreview.serverID,
                linkPreview.contentType,
                ByteArray(0),
                Optional.of(linkPreview.size),
                Optional.absent(),
                linkPreview.width, linkPreview.height,
                Optional.absent(),
                Optional.of(linkPreview.fileName),
                false,
                Optional.fromNullable(linkPreview.caption),
                linkPreview.url)
            signalLinkPreviews.add(SignalServiceDataMessage.Preview(linkPreview.linkPreviewURL!!, linkPreview.linkPreviewTitle!!, Optional.of(attachment)))
        }
        val body = if (message.body == message.timestamp.toString()) "" else message.body // Workaround for the fact that the back-end doesn't accept messages without a body
        return SignalServiceDataMessage(message.timestamp, serviceGroup, attachments, body, false, 0, false, null, false, quote, null, signalLinkPreviews, null)
    }

    fun pollForNewMessages() {
        fun processIncomingMessage(message: LokiPublicChatMessage) {
            // If the sender of the current message is not a secondary device, we need to set the display name in the database
            val primaryDevice = LokiStorageAPI.shared.getPrimaryDevicePublicKey(message.hexEncodedPublicKey).get()
            if (primaryDevice == null) {
                val senderDisplayName = "${message.displayName} (...${message.hexEncodedPublicKey.takeLast(8)})"
                DatabaseFactory.getLokiUserDatabase(context).setServerDisplayName(group.id, message.hexEncodedPublicKey, senderDisplayName)
            }
            val senderPublicKey = primaryDevice ?: message.hexEncodedPublicKey
            val serviceDataMessage = getDataMessage(message)
            val serviceContent = SignalServiceContent(serviceDataMessage, senderPublicKey, SignalServiceAddress.DEFAULT_DEVICE_ID, message.timestamp, false, false)
            if (serviceDataMessage.quote.isPresent || (serviceDataMessage.attachments.isPresent && serviceDataMessage.attachments.get().size > 0) || serviceDataMessage.previews.isPresent) {
                PushDecryptJob(context).handleMediaMessage(serviceContent, serviceDataMessage, Optional.absent(), Optional.of(message.serverID))
            } else {
                PushDecryptJob(context).handleTextMessage(serviceContent, serviceDataMessage, Optional.absent(), Optional.of(message.serverID))
            }
            // Update profile avatar if needed
            val senderRecipient = Recipient.from(context, Address.fromSerialized(senderPublicKey), false)
            if (message.avatar != null && message.avatar!!.url.isNotEmpty()) {
                val profileKey = message.avatar!!.profileKey
                val url = message.avatar!!.url
                if (senderRecipient.profileKey == null || !MessageDigest.isEqual(senderRecipient.profileKey, profileKey)) {
                    val database = DatabaseFactory.getRecipientDatabase(context)
                    database.setProfileKey(senderRecipient, profileKey)
                    ApplicationContext.getInstance(context).jobManager.add(RetrieveProfileAvatarJob(senderRecipient, url))
                }
            } else if (senderRecipient.profileAvatar.orEmpty().isNotEmpty()) {
                // Unset the avatar if we had an avatar before and we're not friends with the person
                val threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(senderRecipient)
                val friendRequestStatus = DatabaseFactory.getLokiThreadDatabase(context).getFriendRequestStatus(threadId)
                if (friendRequestStatus != LokiThreadFriendRequestStatus.FRIENDS) {
                    ApplicationContext.getInstance(context).jobManager.add(RetrieveProfileAvatarJob(senderRecipient, ""))
                }
            }
        }
        fun processOutgoingMessage(message: LokiPublicChatMessage) {
            val messageServerID = message.serverID ?: return
            val isDuplicate = DatabaseFactory.getLokiMessageDatabase(context).getMessageID(messageServerID) != null
            if (isDuplicate) { return }
            if (message.body.isEmpty() && message.attachments.isEmpty() && message.quote == null) { return }
            val localNumber = TextSecurePreferences.getLocalNumber(context)
            val dataMessage = getDataMessage(message)
            val transcript = SentTranscriptMessage(localNumber, dataMessage.timestamp, dataMessage, dataMessage.expiresInSeconds.toLong(), Collections.singletonMap(localNumber, false))
            transcript.messageServerID = messageServerID
            if (dataMessage.quote.isPresent || (dataMessage.attachments.isPresent && dataMessage.attachments.get().size > 0) || dataMessage.previews.isPresent) {
                PushDecryptJob(context).handleSynchronizeSentMediaMessage(transcript)
            } else {
                PushDecryptJob(context).handleSynchronizeSentTextMessage(transcript)
            }
            // If we got a message from our master device then make sure our mappings stay in sync
            val recipient = Recipient.from(context, Address.fromSerialized(message.hexEncodedPublicKey), false)
            if (recipient.isOurMasterDevice && message.avatar != null) {
                val profileKey = message.avatar!!.profileKey
                val url = message.avatar!!.url
                if (recipient.profileKey == null || !MessageDigest.isEqual(recipient.profileKey, profileKey)) {
                    val database = DatabaseFactory.getRecipientDatabase(context)
                    database.setProfileKey(recipient, profileKey)
                    database.setProfileAvatar(recipient, url)
                    ApplicationContext.getInstance(context).updatePublicChatProfileAvatarIfNeeded()
                }
            }
        }
        var userDevices = setOf<String>()
        var uniqueDevices = setOf<String>()
        val userPrivateKey = IdentityKeyUtil.getIdentityKeyPair(context).privateKey.serialize()
        val database = DatabaseFactory.getLokiAPIDatabase(context)
        LokiStorageAPI.configure(false, userHexEncodedPublicKey, userPrivateKey, database)
        LokiStorageAPI.shared.getAllDevicePublicKeys(userHexEncodedPublicKey).bind { devices ->
            userDevices = devices
            api.getMessages(group.channel, group.server)
        }.bind { messages ->
            if (messages.isNotEmpty()) {
                // We need to fetch device mappings for all the devices we don't have
                uniqueDevices = messages.map { it.hexEncodedPublicKey }.toSet()
                val devicesToUpdate = uniqueDevices.filter { !userDevices.contains(it) && LokiStorageAPI.shared.hasCacheExpired(it) }
                if (devicesToUpdate.isNotEmpty()) {
                    return@bind LokiStorageAPI.shared.getDeviceMappings(devicesToUpdate.toSet()).then { messages }
                }
            }
            Promise.of(messages)
        }.successBackground {
            // Get the set of primary device pubKeys FROM the secondary devices in uniqueDevices
            val newDisplayNameUpdatees = uniqueDevices.mapNotNull {
                // This will return null if current device is primary
                // So if it's non-null then we know the device is a secondary device
                val primaryDevice = LokiStorageAPI.shared.getPrimaryDevicePublicKey(it).get()
                primaryDevice
            }.toSet()
            // Fetch the display names of the primary devices
            displayNameUpdatees = displayNameUpdatees.union(newDisplayNameUpdatees)
        }.successBackground { messages ->
            // Process messages in the background
            messages.forEach { message ->
                if (userDevices.contains(message.hexEncodedPublicKey)) {
                    processOutgoingMessage(message)
                } else {
                    processIncomingMessage(message)
                }
            }
        }.fail {
            Log.d("Loki", "Failed to get messages for group chat with ID: ${group.channel} on server: ${group.server}.")
        }
    }

    private fun pollForDisplayNames() {
        if (displayNameUpdatees.isEmpty()) { return }
        val hexEncodedPublicKeys = displayNameUpdatees
        displayNameUpdatees = setOf()
        api.getDisplayNames(hexEncodedPublicKeys, group.server).successBackground { mapping ->
            for (pair in mapping.entries) {
                val senderDisplayName = "${pair.value} (...${pair.key.takeLast(8)})"
                DatabaseFactory.getLokiUserDatabase(context).setServerDisplayName(group.id, pair.key, senderDisplayName)
            }
        }.fail {
            displayNameUpdatees = displayNameUpdatees.union(hexEncodedPublicKeys)
        }
    }

    private fun pollForDeletedMessages() {
        api.getDeletedMessageServerIDs(group.channel, group.server).success { deletedMessageServerIDs ->
            val lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context)
            val deletedMessageIDs = deletedMessageServerIDs.mapNotNull { lokiMessageDatabase.getMessageID(it) }
            val smsMessageDatabase = DatabaseFactory.getSmsDatabase(context)
            val mmsMessageDatabase = DatabaseFactory.getMmsDatabase(context)
            deletedMessageIDs.forEach {
                smsMessageDatabase.deleteMessage(it)
                mmsMessageDatabase.delete(it)
            }
        }.fail {
            Log.d("Loki", "Failed to get deleted messages for group chat with ID: ${group.channel} on server: ${group.server}.")
        }
    }

    private fun pollForModerators() {
        api.getModerators(group.channel, group.server)
    }
    // endregion
}