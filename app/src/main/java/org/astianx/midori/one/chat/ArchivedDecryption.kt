package org.astianx.midori.one.chat

import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.element.OmemoElement
import org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException
import org.jivesoftware.smackx.omemo.exceptions.CryptoFailedException
import org.jivesoftware.smackx.omemo.exceptions.NoRawSessionException
import org.jxmpp.jid.BareJid

/** Only cryptographic unavailability produces an unreadable placeholder.
 * Disk, authentication and connection failures must stop the archive page.
 */
internal fun decryptArchived(manager: OmemoManager, sender: BareJid, encrypted: OmemoElement) = try {
    synchronized(manager) { manager.decrypt(sender, encrypted) }
} catch (_: CorruptedOmemoKeyException) { null }
catch (_: CryptoFailedException) { null }
catch (_: NoRawSessionException) { null }
