package com.comadrez.mailapp

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

data class MailHeader(
    val uid: Long,
    val from: String,
    val subject: String,
    val date: Long,
    val seen: Boolean
)

object MailClient {
    private const val IMAP_HOST = "imap.mail.com"
    private const val SMTP_HOST = "smtp.mail.com"

    private fun connect(user: String, pass: String): Store {
        val props = Properties()
        props["mail.imaps.ssl.enable"] = "true"
        props["mail.imaps.connectiontimeout"] = "15000"
        props["mail.imaps.timeout"] = "20000"
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(IMAP_HOST, 993, user, pass)
        return store
    }

    fun fetchInbox(user: String, pass: String, limit: Int = 50): List<MailHeader> {
        val store = connect(user, pass)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            val n = folder.messageCount
            if (n == 0) return emptyList()
            val msgs = folder.getMessages(maxOf(1, n - limit + 1), n)
            val fp = FetchProfile()
            fp.add(FetchProfile.Item.ENVELOPE)
            fp.add(FetchProfile.Item.FLAGS)
            fp.add(UIDFolder.FetchProfileItem.UID)
            folder.fetch(msgs, fp)
            val uf = folder as UIDFolder
            return msgs.reversed().map { m ->
                val a = m.from?.firstOrNull() as? InternetAddress
                MailHeader(
                    uid = uf.getUID(m),
                    from = a?.personal ?: a?.address ?: "(unknown)",
                    subject = m.subject ?: "(no subject)",
                    date = m.sentDate?.time ?: 0L,
                    seen = m.isSet(Flags.Flag.SEEN)
                )
            }
        } finally {
            try { store.close() } catch (_: Exception) {}
        }
    }

    fun readMessage(user: String, pass: String, uid: Long): String {
        val store = connect(user, pass)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_WRITE)
            val m = (folder as UIDFolder).getMessageByUID(uid) ?: return "Message not found."
            val text = bodyText(m)
            m.setFlag(Flags.Flag.SEEN, true)
            return text.ifBlank { "(no readable text)" }
        } finally {
            try { store.close() } catch (_: Exception) {}
        }
    }

    private fun bodyText(p: Part): String {
        if (p.isMimeType("text/plain")) return p.content.toString()
        if (p.isMimeType("text/html")) return stripHtml(p.content.toString())
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as Multipart
            var fallback = ""
            for (i in 0 until mp.count) {
                val part = mp.getBodyPart(i)
                if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) continue
                if (part.isMimeType("text/plain")) return part.content.toString()
                val t = bodyText(part)
                if (fallback.isEmpty() && t.isNotBlank()) fallback = t
            }
            return fallback
        }
        return ""
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(style|script).*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    fun send(user: String, pass: String, to: String, subject: String, body: String) {
        val props = Properties()
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.starttls.enable"] = "true"
        props["mail.smtp.host"] = SMTP_HOST
        props["mail.smtp.port"] = "587"
        props["mail.smtp.connectiontimeout"] = "15000"
        props["mail.smtp.timeout"] = "20000"
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
        })
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(user))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        msg.setSubject(subject, "UTF-8")
        msg.setText(body, "UTF-8")
        Transport.send(msg)
    }
}
