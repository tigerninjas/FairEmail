package eu.faircode.email;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import com.sun.mail.iap.ConnectionException;
import com.sun.mail.iap.Response;
import com.sun.mail.imap.AppendUID;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.protocol.FetchResponse;
import com.sun.mail.imap.protocol.IMAPProtocol;
import com.sun.mail.imap.protocol.UID;
import com.sun.mail.util.FolderClosedIOException;
import com.sun.mail.util.MailConnectException;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.FolderNotFoundException;
import javax.mail.Message;
import javax.mail.MessageRemovedException;
import javax.mail.MessagingException;
import javax.mail.SendFailedException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.StoreClosedException;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.MessageIDTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;
import javax.net.ssl.SSLException;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

class Core {
    private static final int SYNC_BATCH_SIZE = 20;
    private static final int DOWNLOAD_BATCH_SIZE = 20;
    private static final long YIELD_DURATION = 200L; // milliseconds

    static void processOperations(
            Context context,
            EntityAccount account, EntityFolder folder,
            Session isession, Store istore, Folder ifolder,
            State state)
            throws MessagingException, JSONException, IOException {
        try {
            Log.i(folder.name + " start process");

            DB db = DB.getInstance(context);
            List<EntityOperation> ops = db.operation().getOperations(folder.id);
            Log.i(folder.name + " pending operations=" + ops.size());
            for (int i = 0; i < ops.size() && state.running(); i++) {
                EntityOperation op = ops.get(i);
                try {
                    Log.i(folder.name +
                            " start op=" + op.id + "/" + op.name +
                            " msg=" + op.message +
                            " args=" + op.args);

                    // Fetch most recent copy of message
                    EntityMessage message = null;
                    if (op.message != null)
                        message = db.message().getMessage(op.message);

                    JSONArray jargs = new JSONArray(op.args);

                    try {
                        if (message == null && !EntityOperation.SYNC.equals(op.name))
                            throw new MessageRemovedException();

                        db.operation().setOperationError(op.id, null);
                        if (message != null)
                            db.message().setMessageError(message.id, null);

                        if (message != null && message.uid == null &&
                                !(EntityOperation.ADD.equals(op.name) ||
                                        EntityOperation.DELETE.equals(op.name) ||
                                        EntityOperation.SEND.equals(op.name) ||
                                        EntityOperation.SYNC.equals(op.name)))
                            throw new IllegalArgumentException(op.name + " without uid " + op.args);

                        // Operations should use database transaction when needed

                        switch (op.name) {
                            case EntityOperation.SEEN:
                                onSeen(context, jargs, folder, message, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.FLAG:
                                onFlag(context, jargs, folder, message, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.ANSWERED:
                                onAnswered(context, jargs, folder, message, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.KEYWORD:
                                onKeyword(context, jargs, folder, message, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.ADD:
                                onAdd(context, jargs, folder, message, isession, (IMAPStore) istore, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.MOVE:
                                onMove(context, jargs, folder, message, isession, (IMAPStore) istore, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.DELETE:
                                onDelete(context, jargs, folder, message, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.HEADERS:
                                onHeaders(context, folder, message, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.RAW:
                                onRaw(context, jargs, folder, message, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.BODY:
                                onBody(context, folder, message, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.ATTACHMENT:
                                onAttachment(context, jargs, folder, message, op, (IMAPFolder) ifolder);
                                break;

                            case EntityOperation.SYNC:
                                onSynchronizeMessages(context, account, folder, (IMAPFolder) ifolder, jargs, state);
                                break;

                            default:
                                throw new IllegalArgumentException("Unknown operation=" + op.name);
                        }

                        // Operation succeeded
                        db.operation().deleteOperation(op.id);
                    } catch (Throwable ex) {
                        // TODO: SMTP response codes: https://www.ietf.org/rfc/rfc821.txt
                        Log.e(folder.name, ex);
                        reportError(context, account, folder, ex);

                        db.operation().setOperationError(op.id, Helper.formatThrowable(ex));

                        if (message != null &&
                                !(ex instanceof MessageRemovedException) &&
                                !(ex instanceof FolderClosedException) &&
                                !(ex instanceof IllegalStateException))
                            db.message().setMessageError(message.id, Helper.formatThrowable(ex));

                        if (ex instanceof MessageRemovedException ||
                                ex instanceof FolderNotFoundException ||
                                ex instanceof IllegalArgumentException) {
                            Log.w("Unrecoverable", ex);

                            // There is no use in repeating
                            db.operation().deleteOperation(op.id);

                            // Cleanup
                            if (message != null) {
                                if (ex instanceof MessageRemovedException)
                                    db.message().deleteMessage(message.id);

                                Long newid = null;

                                if (EntityOperation.MOVE.equals(op.name) &&
                                        jargs.length() > 2)
                                    newid = jargs.getLong(2);

                                if ((EntityOperation.ADD.equals(op.name) ||
                                        EntityOperation.RAW.equals(op.name)) &&
                                        jargs.length() > 0 && !jargs.isNull(0))
                                    newid = jargs.getLong(0);

                                // Delete temporary copy in target folder
                                if (newid != null) {
                                    db.message().deleteMessage(newid);
                                    db.message().setMessageUiHide(message.id, false);
                                }
                            }

                            continue;
                        } else if (ex instanceof MessagingException) {
                            // Socket timeout is a recoverable condition (send message)
                            if (ex.getCause() instanceof SocketTimeoutException) {
                                Log.w("Recoverable", ex);
                                // No need to inform user
                                return;
                            }
                        }

                        throw ex;
                    }
                } finally {
                    Log.i(folder.name + " end op=" + op.id + "/" + op.name);
                }
            }
        } finally {
            Log.i(folder.name + " end process state=" + state);
        }
    }

    private static void onSeen(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, IMAPFolder ifolder) throws MessagingException, JSONException {
        // Mark message (un)seen
        DB db = DB.getInstance(context);

        if (!ifolder.getPermanentFlags().contains(Flags.Flag.SEEN)) {
            db.message().setMessageSeen(message.id, false);
            db.message().setMessageUiSeen(message.id, false);
            return;
        }

        boolean seen = jargs.getBoolean(0);
        if (message.seen.equals(seen))
            return;

        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        imessage.setFlag(Flags.Flag.SEEN, seen);

        db.message().setMessageSeen(message.id, seen);
    }

    private static void onFlag(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, IMAPFolder ifolder) throws MessagingException, JSONException {
        // Star/unstar message
        DB db = DB.getInstance(context);

        if (!ifolder.getPermanentFlags().contains(Flags.Flag.FLAGGED)) {
            db.message().setMessageFlagged(message.id, false);
            db.message().setMessageUiFlagged(message.id, false);
            return;
        }

        boolean flagged = jargs.getBoolean(0);
        if (message.flagged.equals(flagged))
            return;

        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        imessage.setFlag(Flags.Flag.FLAGGED, flagged);

        db.message().setMessageFlagged(message.id, flagged);
    }

    private static void onAnswered(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, IMAPFolder ifolder) throws MessagingException, JSONException {
        // Mark message (un)answered
        DB db = DB.getInstance(context);

        if (!ifolder.getPermanentFlags().contains(Flags.Flag.ANSWERED)) {
            db.message().setMessageAnswered(message.id, false);
            db.message().setMessageUiAnswered(message.id, false);
            return;
        }

        boolean answered = jargs.getBoolean(0);
        if (message.answered.equals(answered))
            return;

        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        imessage.setFlag(Flags.Flag.ANSWERED, answered);

        db.message().setMessageAnswered(message.id, answered);
    }

    private static void onKeyword(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, IMAPFolder ifolder) throws MessagingException, JSONException {
        // Set/reset user flag
        DB db = DB.getInstance(context);

        if (!ifolder.getPermanentFlags().contains(Flags.Flag.USER)) {
            db.message().setMessageKeywords(message.id, DB.Converters.fromStringArray(null));
            return;
        }

        // https://tools.ietf.org/html/rfc3501#section-2.3.2
        String keyword = jargs.getString(0);
        boolean set = jargs.getBoolean(1);

        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        Flags flags = new Flags(keyword);
        imessage.setFlags(flags, set);

        try {
            db.beginTransaction();

            message = db.message().getMessage(message.id);

            List<String> keywords = new ArrayList<>(Arrays.asList(message.keywords));
            if (set) {
                if (!keywords.contains(keyword))
                    keywords.add(keyword);
            } else
                keywords.remove(keyword);
            db.message().setMessageKeywords(message.id, DB.Converters.fromStringArray(keywords.toArray(new String[0])));

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void onAdd(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, Session isession, IMAPStore istore, IMAPFolder ifolder) throws MessagingException, JSONException, IOException {
        // Add message
        DB db = DB.getInstance(context);

        if (TextUtils.isEmpty(message.msgid))
            throw new IllegalArgumentException("Message ID missing");

        // Get message
        MimeMessage imessage;
        if (folder.id.equals(message.folder)) {
            // Pre flight checks
            if (!message.content)
                throw new IllegalArgumentException("Message body missing");

            EntityIdentity identity =
                    (message.identity == null ? null : db.identity().getIdentity(message.identity));

            imessage = MessageHelper.from(context, message, isession,
                    identity == null ? false : identity.plain_only);
        } else {
            // Cross account move
            File file = EntityMessage.getRawFile(context, message.id);
            if (!file.exists())
                throw new IllegalArgumentException("raw message file not found");

            Log.i(folder.name + " reading " + file);
            try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                imessage = new MimeMessage(isession, is);
            }
        }

        // Handle auto read
        boolean autoread = false;
        if (jargs.length() > 1) {
            autoread = jargs.getBoolean(1);
            if (ifolder.getPermanentFlags().contains(Flags.Flag.SEEN)) {
                if (autoread && !imessage.isSet(Flags.Flag.SEEN)) {
                    Log.i(folder.name + " autoread");
                    imessage.setFlag(Flags.Flag.SEEN, true);
                }
            }
        }

        // Handle draft
        if (EntityFolder.DRAFTS.equals(folder.type))
            if (ifolder.getPermanentFlags().contains(Flags.Flag.DRAFT))
                imessage.setFlag(Flags.Flag.DRAFT, true);

        // Add message
        long uid = append(istore, ifolder, imessage);
        Log.i(folder.name + " appended id=" + message.id + " uid=" + uid);
        db.message().setMessageUid(message.id, uid);

        if (folder.id.equals(message.folder)) {
            // Delete previous message
            Message[] ideletes = ifolder.search(new MessageIDTerm(message.msgid));
            for (Message idelete : ideletes) {
                long duid = ifolder.getUID(idelete);
                if (duid == uid)
                    Log.i(folder.name + " append confirmed uid=" + duid);
                else {
                    Log.i(folder.name + " deleting uid=" + duid + " msgid=" + message.msgid);
                    idelete.setFlag(Flags.Flag.DELETED, true);
                }
            }
            ifolder.expunge();
        } else {
            // Cross account move
            if (autoread) {
                Log.i(folder.name + " queuing SEEN id=" + message.id);
                EntityOperation.queue(context, db, message, EntityOperation.SEEN, true);
            }

            Log.i(folder.name + " queuing DELETE id=" + message.id);
            EntityOperation.queue(context, db, message, EntityOperation.DELETE);
        }
    }

    private static void onMove(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, Session isession, IMAPStore istore, IMAPFolder ifolder) throws JSONException, MessagingException, IOException {
        // Move message
        DB db = DB.getInstance(context);

        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        // Get parameters
        boolean autoread = jargs.getBoolean(1);

        // Get target folder
        long id = jargs.getLong(0);
        EntityFolder target = db.folder().getFolder(id);
        if (target == null)
            throw new FolderNotFoundException();
        IMAPFolder itarget = (IMAPFolder) istore.getFolder(target.name);

        boolean canMove = istore.hasCapability("MOVE");
        if (canMove &&
                !EntityFolder.DRAFTS.equals(folder.type) &&
                !EntityFolder.DRAFTS.equals(target.type)) {
            // Autoread
            if (ifolder.getPermanentFlags().contains(Flags.Flag.SEEN))
                if (autoread && !imessage.isSet(Flags.Flag.SEEN))
                    imessage.setFlag(Flags.Flag.SEEN, true);

            // Move message to
            ifolder.moveMessages(new Message[]{imessage}, itarget);
        } else {
            Log.w(folder.name + " MOVE by DELETE/APPEND" +
                    " cap=" + canMove + " from=" + folder.type + " to=" + target.type);

            // Serialize source message
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            imessage.writeTo(bos);

            // Deserialize target message
            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            Message icopy = new MimeMessage(isession, bis);

            // Make sure the message has a message ID
            if (message.msgid == null) {
                String msgid = EntityMessage.generateMessageId();
                Log.i(target.name + " generated message id=" + msgid);
                icopy.setHeader("Message-ID", msgid);
            }

            try {
                // Needed to read flags
                itarget.open(Folder.READ_WRITE);

                // Auto read
                if (itarget.getPermanentFlags().contains(Flags.Flag.SEEN))
                    if (autoread && !icopy.isSet(Flags.Flag.SEEN))
                        icopy.setFlag(Flags.Flag.SEEN, true);

                // Move from drafts
                if (EntityFolder.DRAFTS.equals(folder.type))
                    if (itarget.getPermanentFlags().contains(Flags.Flag.DRAFT))
                        icopy.setFlag(Flags.Flag.DRAFT, false);

                // Move to drafts
                if (EntityFolder.DRAFTS.equals(target.type))
                    if (itarget.getPermanentFlags().contains(Flags.Flag.DRAFT))
                        icopy.setFlag(Flags.Flag.DRAFT, true);

                // Append target
                long uid = append(istore, itarget, (MimeMessage) icopy);
                Log.i(target.name + " appended id=" + message.id + " uid=" + uid);

                // Fixed timing issue of at least Courier based servers
                itarget.close(false);
                itarget.open(Folder.READ_WRITE);

                // Some providers, like Gmail, don't honor the appended seen flag
                if (itarget.getPermanentFlags().contains(Flags.Flag.SEEN)) {
                    boolean seen = (autoread || message.ui_seen);
                    icopy = itarget.getMessageByUID(uid);
                    if (seen != icopy.isSet(Flags.Flag.SEEN)) {
                        Log.i(target.name + " Fixing id=" + message.id + " seen=" + seen);
                        icopy.setFlag(Flags.Flag.SEEN, seen);
                    }
                }

                // This is not based on an actual case, so this is just a safeguard
                if (itarget.getPermanentFlags().contains(Flags.Flag.DRAFT)) {
                    boolean draft = EntityFolder.DRAFTS.equals(target.type);
                    icopy = itarget.getMessageByUID(uid);
                    if (draft != icopy.isSet(Flags.Flag.DRAFT)) {
                        Log.i(target.name + " Fixing id=" + message.id + " draft=" + draft);
                        icopy.setFlag(Flags.Flag.DRAFT, draft);
                    }
                }

                // Delete source
                imessage.setFlag(Flags.Flag.DELETED, true);
                ifolder.expunge();
            } catch (Throwable ex) {
                if (itarget.isOpen())
                    itarget.close();
                throw ex;
            }
        }
    }

    private static void onDelete(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, IMAPFolder ifolder) throws MessagingException {
        // Delete message
        DB db = DB.getInstance(context);

        if (TextUtils.isEmpty(message.msgid))
            throw new IllegalArgumentException("Message ID missing");

        Message[] imessages = ifolder.search(new MessageIDTerm(message.msgid));
        for (Message imessage : imessages) {
            Log.i(folder.name + " deleting uid=" + message.uid + " msgid=" + message.msgid);
            imessage.setFlag(Flags.Flag.DELETED, true);
        }
        ifolder.expunge();

        db.message().deleteMessage(message.id);
    }

    private static void onHeaders(Context context, EntityFolder folder, EntityMessage message, IMAPFolder ifolder) throws MessagingException {
        // Download headers
        DB db = DB.getInstance(context);

        if (message.headers != null)
            return;

        IMAPMessage imessage = (IMAPMessage) ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        MessageHelper helper = new MessageHelper(imessage);
        db.message().setMessageHeaders(message.id, helper.getHeaders());
    }

    private static void onRaw(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, IMAPFolder ifolder) throws MessagingException, IOException, JSONException {
        // Download raw message
        DB db = DB.getInstance(context);

        if (message.raw == null || !message.raw) {
            IMAPMessage imessage = (IMAPMessage) ifolder.getMessageByUID(message.uid);
            if (imessage == null)
                throw new MessageRemovedException();

            File file = EntityMessage.getRawFile(context, message.id);

            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(file))) {
                imessage.writeTo(os);
                db.message().setMessageRaw(message.id, true);
            }
        }

        if (jargs.length() > 0) {
            long target = jargs.getLong(2);
            jargs.remove(2);
            Log.i(folder.name + " queuing ADD id=" + message.id + ":" + target);

            EntityOperation operation = new EntityOperation();
            operation.folder = target;
            operation.message = message.id;
            operation.name = EntityOperation.ADD;
            operation.args = jargs.toString();
            operation.created = new Date().getTime();
            operation.id = db.operation().insertOperation(operation);
        }
    }

    private static void onBody(Context context, EntityFolder folder, EntityMessage message, IMAPFolder ifolder) throws MessagingException, IOException {
        // Download message body
        DB db = DB.getInstance(context);

        if (message.content)
            return;

        // Get message
        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        MessageHelper helper = new MessageHelper((MimeMessage) imessage);
        MessageHelper.MessageParts parts = helper.getMessageParts();
        String body = parts.getHtml(context);
        String preview = HtmlHelper.getPreview(body);
        Helper.writeText(EntityMessage.getFile(context, message.id), body);
        db.message().setMessageContent(message.id, true, preview);
        db.message().setMessageWarning(message.id, parts.getWarnings(message.warning));
    }

    private static void onAttachment(Context context, JSONArray jargs, EntityFolder folder, EntityMessage message, EntityOperation op, IMAPFolder ifolder) throws JSONException, MessagingException, IOException {
        // Download attachment
        DB db = DB.getInstance(context);

        int sequence = jargs.getInt(0);

        // Get attachment
        EntityAttachment attachment = db.attachment().getAttachment(op.message, sequence);
        if (attachment.available)
            return;

        // Get message
        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        // Download attachment
        MessageHelper helper = new MessageHelper((MimeMessage) imessage);
        MessageHelper.MessageParts parts = helper.getMessageParts();
        parts.downloadAttachment(context, db, attachment.id, sequence);
    }

    private static long append(IMAPStore istore, IMAPFolder ifolder, MimeMessage imessage) throws MessagingException {
        if (istore.hasCapability("UIDPLUS")) {
            AppendUID[] uids = ifolder.appendUIDMessages(new Message[]{imessage});
            if (uids == null || uids.length == 0)
                throw new MessageRemovedException("Message not appended");
            return uids[0].uid;
        } else {
            ifolder.appendMessages(new Message[]{imessage});

            long uid = -1;
            String msgid = imessage.getMessageID();
            Log.i("Searching for appended msgid=" + msgid);
            Message[] messages = ifolder.search(new MessageIDTerm(msgid));
            if (messages != null)
                for (Message iappended : messages) {
                    long muid = ifolder.getUID(iappended);
                    Log.i("Found appended uid=" + muid);
                    // RFC3501: Unique identifiers are assigned in a strictly ascending fashion
                    if (muid > uid)
                        uid = muid;
                }

            if (uid < 0)
                throw new IllegalArgumentException("uid not found");

            return uid;
        }
    }

    static void onSynchronizeFolders(Context context, EntityAccount account, Store istore, State state) throws MessagingException {
        DB db = DB.getInstance(context);
        try {
            db.beginTransaction();

            Log.i("Start sync folders account=" + account.name);

            List<String> names = new ArrayList<>();
            for (EntityFolder folder : db.folder().getFolders(account.id))
                if (folder.tbc != null) {
                    Log.i(folder.name + " creating");
                    Folder ifolder = istore.getFolder(folder.name);
                    if (!ifolder.exists())
                        ifolder.create(Folder.HOLDS_MESSAGES);
                    db.folder().resetFolderTbc(folder.id);
                } else if (folder.tbd != null && folder.tbd) {
                    Log.i(folder.name + " deleting");
                    Folder ifolder = istore.getFolder(folder.name);
                    if (ifolder.exists())
                        ifolder.delete(false);
                    db.folder().deleteFolder(folder.id);
                } else
                    names.add(folder.name);
            Log.i("Local folder count=" + names.size());

            Folder defaultFolder = istore.getDefaultFolder();
            char separator = defaultFolder.getSeparator();
            EntityLog.log(context, account.name + " folder separator=" + separator);

            Folder[] ifolders = defaultFolder.list("*");
            Log.i("Remote folder count=" + ifolders.length + " separator=" + separator);

            for (Folder ifolder : ifolders) {
                String fullName = ifolder.getFullName();
                String[] attrs = ((IMAPFolder) ifolder).getAttributes();
                String type = EntityFolder.getType(attrs, fullName);

                EntityLog.log(context, account.name + ":" + fullName +
                        " attrs=" + TextUtils.join(" ", attrs) + " type=" + type);

                if (type != null) {
                    names.remove(fullName);

                    int level = EntityFolder.getLevel(separator, fullName);
                    String display = null;
                    if (account.prefix != null && fullName.startsWith(account.prefix + separator))
                        display = fullName.substring(account.prefix.length() + 1);

                    EntityFolder folder = db.folder().getFolderByName(account.id, fullName);
                    if (folder == null) {
                        folder = new EntityFolder();
                        folder.account = account.id;
                        folder.name = fullName;
                        folder.display = display;
                        folder.type = (EntityFolder.SYSTEM.equals(type) ? type : EntityFolder.USER);
                        folder.level = level;
                        folder.synchronize = false;
                        folder.poll = ("imap.gmail.com".equals(account.host));
                        folder.sync_days = EntityFolder.DEFAULT_SYNC;
                        folder.keep_days = EntityFolder.DEFAULT_KEEP;
                        db.folder().insertFolder(folder);
                        Log.i(folder.name + " added type=" + folder.type);
                    } else {
                        Log.i(folder.name + " exists type=" + folder.type);

                        if (folder.display == null) {
                            if (display != null) {
                                db.folder().setFolderDisplay(folder.id, display);
                                EntityLog.log(context, account.name + ":" + folder.name +
                                        " removed prefix display=" + display + " separator=" + separator);
                            }
                        } else {
                            if (account.prefix == null && folder.name.endsWith(separator + folder.display)) {
                                db.folder().setFolderDisplay(folder.id, null);
                                EntityLog.log(context, account.name + ":" + folder.name +
                                        " restored prefix display=" + folder.display + " separator=" + separator);
                            }
                        }

                        db.folder().setFolderLevel(folder.id, level);

                        // Compatibility
                        if ("Inbox_sub".equals(folder.type))
                            db.folder().setFolderType(folder.id, EntityFolder.USER);
                        else if (EntityFolder.USER.equals(folder.type) && EntityFolder.SYSTEM.equals(type))
                            db.folder().setFolderType(folder.id, type);
                        else if (EntityFolder.SYSTEM.equals(folder.type) && EntityFolder.USER.equals(type))
                            db.folder().setFolderType(folder.id, type);
                    }
                }
            }

            Log.i("Delete local count=" + names.size());
            for (String name : names) {
                Log.i(name + " delete");
                db.folder().deleteFolder(account.id, name);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            Log.i("End sync folder");
        }
    }

    static void onSynchronizeMessages(Context context, EntityAccount account, final EntityFolder folder, IMAPFolder ifolder, JSONArray jargs, State state) throws JSONException, MessagingException, IOException {
        final DB db = DB.getInstance(context);
        try {
            int sync_days = jargs.getInt(0);
            int keep_days = jargs.getInt(1);
            boolean download = jargs.getBoolean(2);

            if (keep_days == sync_days)
                keep_days++;

            Log.i(folder.name + " start sync after=" + sync_days + "/" + keep_days);

            db.folder().setFolderSyncState(folder.id, "syncing");

            // Get reference times
            Calendar cal_sync = Calendar.getInstance();
            cal_sync.add(Calendar.DAY_OF_MONTH, -sync_days);
            cal_sync.set(Calendar.HOUR_OF_DAY, 0);
            cal_sync.set(Calendar.MINUTE, 0);
            cal_sync.set(Calendar.SECOND, 0);
            cal_sync.set(Calendar.MILLISECOND, 0);

            Calendar cal_keep = Calendar.getInstance();
            cal_keep.add(Calendar.DAY_OF_MONTH, -keep_days);
            cal_keep.set(Calendar.HOUR_OF_DAY, 0);
            cal_keep.set(Calendar.MINUTE, 0);
            cal_keep.set(Calendar.SECOND, 0);
            cal_keep.set(Calendar.MILLISECOND, 0);

            long sync_time = cal_sync.getTimeInMillis();
            if (sync_time < 0)
                sync_time = 0;

            long keep_time = cal_keep.getTimeInMillis();
            if (keep_time < 0)
                keep_time = 0;

            Log.i(folder.name + " sync=" + new Date(sync_time) + " keep=" + new Date(keep_time));

            // Delete old local messages
            int old = db.message().deleteMessagesBefore(folder.id, keep_time, false);
            Log.i(folder.name + " local old=" + old);

            // Get list of local uids
            final List<Long> uids = db.message().getUids(folder.id, null);
            Log.i(folder.name + " local count=" + uids.size());

            // Reduce list of local uids
            SearchTerm searchTerm = new ReceivedDateTerm(ComparisonTerm.GE, new Date(sync_time));
            if (ifolder.getPermanentFlags().contains(Flags.Flag.FLAGGED))
                searchTerm = new OrTerm(searchTerm, new FlagTerm(new Flags(Flags.Flag.FLAGGED), true));

            long search = SystemClock.elapsedRealtime();
            Message[] imessages = ifolder.search(searchTerm);
            Log.i(folder.name + " remote count=" + imessages.length +
                    " search=" + (SystemClock.elapsedRealtime() - search) + " ms");

            FetchProfile fp = new FetchProfile();
            fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(FetchProfile.Item.FLAGS);
            ifolder.fetch(imessages, fp);

            long fetch = SystemClock.elapsedRealtime();
            Log.i(folder.name + " remote fetched=" + (SystemClock.elapsedRealtime() - fetch) + " ms");

            for (int i = 0; i < imessages.length && state.running(); i++)
                try {
                    uids.remove(ifolder.getUID(imessages[i]));
                } catch (MessageRemovedException ex) {
                    Log.w(folder.name, ex);
                } catch (Throwable ex) {
                    Log.e(folder.name, ex);
                    reportError(context, account, folder, ex);
                    db.folder().setFolderError(folder.id, Helper.formatThrowable(ex, true));
                }

            if (uids.size() > 0) {
                ifolder.doCommand(new IMAPFolder.ProtocolCommand() {
                    @Override
                    public Object doCommand(IMAPProtocol protocol) {
                        Log.i("Executing uid fetch count=" + uids.size());
                        Response[] responses = protocol.command(
                                "UID FETCH " + TextUtils.join(",", uids) + " (UID)", null);

                        for (int i = 0; i < responses.length; i++) {
                            if (responses[i] instanceof FetchResponse) {
                                FetchResponse fr = (FetchResponse) responses[i];
                                UID uid = fr.getItem(UID.class);
                                if (uid != null)
                                    uids.remove(uid.uid);
                            } else {
                                if (responses[i].isOK())
                                    Log.i(folder.name + " response=" + responses[i]);
                                else {
                                    Log.e(folder.name + " response=" + responses[i]);
                                    db.folder().setFolderError(folder.id, responses[i].toString());
                                }
                            }
                        }
                        return null;
                    }
                });

                long getuid = SystemClock.elapsedRealtime();
                Log.i(folder.name + " remote uids=" + (SystemClock.elapsedRealtime() - getuid) + " ms");
            }

            // Delete local messages not at remote
            Log.i(folder.name + " delete=" + uids.size());
            for (Long uid : uids) {
                int count = db.message().deleteMessage(folder.id, uid);
                Log.i(folder.name + " delete local uid=" + uid + " count=" + count);
            }

            List<EntityRule> rules = db.rule().getEnabledRules(folder.id);

            fp.add(FetchProfile.Item.ENVELOPE);
            // fp.add(FetchProfile.Item.FLAGS);
            fp.add(FetchProfile.Item.CONTENT_INFO); // body structure
            // fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(IMAPFolder.FetchProfileItem.HEADERS);
            // fp.add(IMAPFolder.FetchProfileItem.MESSAGE);
            fp.add(FetchProfile.Item.SIZE);
            fp.add(IMAPFolder.FetchProfileItem.INTERNALDATE);

            // Add/update local messages
            Long[] ids = new Long[imessages.length];
            Log.i(folder.name + " add=" + imessages.length);
            for (int i = imessages.length - 1; i >= 0 && state.running(); i -= SYNC_BATCH_SIZE) {
                int from = Math.max(0, i - SYNC_BATCH_SIZE + 1);
                Message[] isub = Arrays.copyOfRange(imessages, from, i + 1);

                // Full fetch new/changed messages only
                List<Message> full = new ArrayList<>();
                for (Message imessage : isub) {
                    long uid = ifolder.getUID(imessage);
                    EntityMessage message = db.message().getMessageByUid(folder.id, uid);
                    if (message == null)
                        full.add(imessage);
                }
                if (full.size() > 0) {
                    long headers = SystemClock.elapsedRealtime();
                    ifolder.fetch(full.toArray(new Message[0]), fp);
                    Log.i(folder.name + " fetched headers=" + full.size() +
                            " " + (SystemClock.elapsedRealtime() - headers) + " ms");
                }

                for (int j = isub.length - 1; j >= 0 && state.running(); j--)
                    try {
                        db.beginTransaction();
                        EntityMessage message = synchronizeMessage(
                                context,
                                folder, ifolder, (IMAPMessage) isub[j],
                                false,
                                rules);
                        ids[from + j] = message.id;
                        db.setTransactionSuccessful();
                    } catch (MessageRemovedException ex) {
                        Log.w(folder.name, ex);
                    } catch (FolderClosedException ex) {
                        throw ex;
                    } catch (IOException ex) {
                        if (ex.getCause() instanceof MessagingException) {
                            Log.w(folder.name, ex);
                            db.folder().setFolderError(folder.id, Helper.formatThrowable(ex, true));
                        } else
                            throw ex;
                    } catch (Throwable ex) {
                        Log.e(folder.name, ex);
                        db.folder().setFolderError(folder.id, Helper.formatThrowable(ex, true));
                    } finally {
                        db.endTransaction();
                        // Reduce memory usage
                        ((IMAPMessage) isub[j]).invalidateHeaders();
                    }
            }

            // Delete not synchronized messages without uid
            db.message().deleteOrphans(folder.id);

            // Add local sent messages to remote sent folder
            if (EntityFolder.SENT.equals(folder.type)) {
                List<EntityMessage> orphans = db.message().getSentOrphans(folder.account);
                Log.i(folder.name + " sent orphans=" + orphans.size() + " account=" + folder.account);
                for (EntityMessage orphan : orphans) {
                    Log.i(folder.name + " adding orphan id=" + orphan.id + " sent=" + new Date(orphan.sent));
                    orphan.folder = folder.id;
                    db.message().updateMessage(orphan);
                    EntityOperation.queue(context, db, orphan, EntityOperation.ADD);
                }
            }

            if (download) {
                db.folder().setFolderSyncState(folder.id, "downloading");

                //fp.add(IMAPFolder.FetchProfileItem.MESSAGE);

                // Download messages/attachments
                Log.i(folder.name + " download=" + imessages.length);
                for (int i = imessages.length - 1; i >= 0 && state.running(); i -= DOWNLOAD_BATCH_SIZE) {
                    int from = Math.max(0, i - DOWNLOAD_BATCH_SIZE + 1);

                    Message[] isub = Arrays.copyOfRange(imessages, from, i + 1);
                    // Fetch on demand

                    for (int j = isub.length - 1; j >= 0 && state.running(); j--)
                        try {
                            db.beginTransaction();
                            if (ids[from + j] != null)
                                downloadMessage(
                                        context,
                                        folder, ifolder,
                                        (IMAPMessage) isub[j], ids[from + j]);
                            db.setTransactionSuccessful();
                        } catch (FolderClosedException ex) {
                            throw ex;
                        } catch (FolderClosedIOException ex) {
                            throw ex;
                        } catch (Throwable ex) {
                            Log.e(folder.name, ex);
                        } finally {
                            db.endTransaction();
                            // Free memory
                            ((IMAPMessage) isub[j]).invalidateHeaders();
                        }
                }
            }

            if (state.running)
                db.folder().setFolderInitialized(folder.id);

            db.folder().setFolderSync(folder.id, new Date().getTime());
            db.folder().setFolderError(folder.id, null);

        } finally {
            Log.i(folder.name + " end sync state=" + state);
            db.folder().setFolderSyncState(folder.id, null);
        }
    }

    static EntityMessage synchronizeMessage(
            Context context,
            EntityFolder folder, IMAPFolder ifolder, IMAPMessage imessage,
            boolean browsed,
            List<EntityRule> rules) throws MessagingException, IOException {
        long uid = ifolder.getUID(imessage);

        if (imessage.isExpunged()) {
            Log.i(folder.name + " expunged uid=" + uid);
            throw new MessageRemovedException();
        }
        if (imessage.isSet(Flags.Flag.DELETED)) {
            Log.i(folder.name + " deleted uid=" + uid);
            throw new MessageRemovedException();
        }

        MessageHelper helper = new MessageHelper(imessage);
        boolean seen = helper.getSeen();
        boolean answered = helper.getAnsered();
        boolean flagged = helper.getFlagged();
        String flags = helper.getFlags();
        String[] keywords = helper.getKeywords();
        boolean filter = false;

        DB db = DB.getInstance(context);

        // Find message by uid (fast, no headers required)
        EntityMessage message = db.message().getMessageByUid(folder.id, uid);

        // Find message by Message-ID (slow, headers required)
        // - messages in inbox have same id as message sent to self
        // - messages in archive have same id as original
        if (message == null) {
            // Will fetch headers within database transaction
            String msgid = helper.getMessageID();
            Log.i(folder.name + " searching for " + msgid);
            for (EntityMessage dup : db.message().getMessageByMsgId(folder.account, msgid)) {
                EntityFolder dfolder = db.folder().getFolder(dup.folder);
                Log.i(folder.name + " found as id=" + dup.id + "/" + dup.uid +
                        " folder=" + dfolder.type + ":" + dup.folder + "/" + folder.type + ":" + folder.id +
                        " msgid=" + dup.msgid + " thread=" + dup.thread);

                if (dup.folder.equals(folder.id) ||
                        (EntityFolder.OUTBOX.equals(dfolder.type) && EntityFolder.SENT.equals(folder.type))) {
                    String thread = helper.getThreadId(uid);
                    Log.i(folder.name + " found as id=" + dup.id +
                            " uid=" + dup.uid + "/" + uid +
                            " msgid=" + msgid + " thread=" + thread);
                    dup.folder = folder.id; // outbox to sent

                    if (dup.uid == null) {
                        Log.i(folder.name + " set uid=" + uid);
                        dup.uid = uid;
                        filter = true;
                    } else
                        Log.w(folder.name + " changed uid=" + dup.uid + " -> " + uid);

                    dup.msgid = msgid;
                    dup.thread = thread;
                    dup.error = null;
                    db.message().updateMessage(dup);
                    message = dup;
                }
            }

            if (message == null)
                filter = true;
        }

        if (message == null) {
            // Build list of addresses
            Address[] recipients = helper.getTo();
            Address[] senders = helper.getFrom();
            if (recipients == null)
                recipients = new Address[0];
            if (senders == null)
                senders = new Address[0];
            Address[] all = Arrays.copyOf(recipients, recipients.length + senders.length);
            System.arraycopy(senders, 0, all, recipients.length, senders.length);

            List<String> emails = new ArrayList<>();
            for (Address address : all) {
                String to = ((InternetAddress) address).getAddress();
                if (!TextUtils.isEmpty(to)) {
                    to = to.toLowerCase();
                    emails.add(to);
                    String canonical = Helper.canonicalAddress(to);
                    if (!to.equals(canonical))
                        emails.add(canonical);
                }
            }
            String delivered = helper.getDeliveredTo();
            if (!TextUtils.isEmpty(delivered)) {
                delivered = delivered.toLowerCase();
                emails.add(delivered);
                String canonical = Helper.canonicalAddress(delivered);
                if (!delivered.equals(canonical))
                    emails.add(canonical);
            }

            // Search for identity
            EntityIdentity identity = null;
            for (String email : emails) {
                identity = db.identity().getIdentity(folder.account, email);
                if (identity != null)
                    break;
            }

            message = new EntityMessage();
            message.account = folder.account;
            message.folder = folder.id;
            message.identity = (identity == null ? null : identity.id);
            message.uid = uid;

            message.msgid = helper.getMessageID();
            if (TextUtils.isEmpty(message.msgid))
                Log.w("No Message-ID id=" + message.id + " uid=" + message.uid);

            message.references = TextUtils.join(" ", helper.getReferences());
            message.inreplyto = helper.getInReplyTo();
            message.deliveredto = helper.getDeliveredTo();
            message.thread = helper.getThreadId(uid);
            message.sender = MessageHelper.getSortKey(helper.getFrom());
            message.from = helper.getFrom();
            message.to = helper.getTo();
            message.cc = helper.getCc();
            message.bcc = helper.getBcc();
            message.reply = helper.getReply();
            message.subject = helper.getSubject();
            message.size = helper.getSize();
            message.content = false;
            message.received = helper.getReceived();
            message.sent = helper.getSent();
            message.seen = seen;
            message.answered = answered;
            message.flagged = flagged;
            message.flags = flags;
            message.keywords = keywords;
            message.ui_seen = seen;
            message.ui_answered = answered;
            message.ui_flagged = flagged;
            message.ui_hide = false;
            message.ui_found = false;
            message.ui_ignored = seen;
            message.ui_browsed = browsed;

            Uri lookupUri = ContactInfo.getLookupUri(context, message.from);
            message.avatar = (lookupUri == null ? null : lookupUri.toString());

            // Check sender
            Address sender = helper.getSender();
            if (sender != null && senders.length > 0) {
                String[] f = ((InternetAddress) senders[0]).getAddress().split("@");
                String[] s = ((InternetAddress) sender).getAddress().split("@");
                if (f.length > 1 && s.length > 1) {
                    if (!f[1].equals(s[1]))
                        message.warning = context.getString(R.string.title_via, s[1]);
                }
            }

            message.id = db.message().insertMessage(message);

            Log.i(folder.name + " added id=" + message.id + " uid=" + message.uid);

            int sequence = 1;
            MessageHelper.MessageParts parts = helper.getMessageParts();
            for (EntityAttachment attachment : parts.getAttachments()) {
                Log.i(folder.name + " attachment seq=" + sequence +
                        " name=" + attachment.name + " type=" + attachment.type +
                        " cid=" + attachment.cid + " pgp=" + attachment.encryption);
                attachment.message = message.id;
                attachment.sequence = sequence++;
                attachment.id = db.attachment().insertAttachment(attachment);
            }
        } else {
            boolean update = false;

            if (!message.seen.equals(seen) || !message.seen.equals(message.ui_seen)) {
                update = true;
                message.seen = seen;
                message.ui_seen = seen;
                Log.i(folder.name + " updated id=" + message.id + " uid=" + message.uid + " seen=" + seen);
            }

            if (!message.answered.equals(answered) || !message.answered.equals(message.ui_answered)) {
                update = true;
                message.answered = answered;
                message.ui_answered = answered;
                Log.i(folder.name + " updated id=" + message.id + " uid=" + message.uid + " answered=" + answered);
            }

            if (!message.flagged.equals(flagged) || !message.flagged.equals(message.ui_flagged)) {
                update = true;
                message.flagged = flagged;
                message.ui_flagged = flagged;
                Log.i(folder.name + " updated id=" + message.id + " uid=" + message.uid + " flagged=" + flagged);
            }

            if (!Objects.equals(flags, message.flags)) {
                update = true;
                message.flags = flags;
                Log.i(folder.name + " updated id=" + message.id + " uid=" + message.uid + " flags=" + flags);
            }

            if (!Helper.equal(message.keywords, keywords)) {
                update = true;
                message.keywords = keywords;
                Log.i(folder.name + " updated id=" + message.id + " uid=" + message.uid +
                        " keywords=" + TextUtils.join(" ", keywords));
            }

            if (message.ui_hide && db.operation().getOperationCount(folder.id, message.id) == 0) {
                update = true;
                message.ui_hide = false;
                Log.i(folder.name + " updated id=" + message.id + " uid=" + message.uid + " unhide");
            }

            if (message.ui_browsed) {
                update = true;
                message.ui_browsed = false;
                Log.i(folder.name + " updated id=" + message.id + " uid=" + message.uid + " unbrowse");
            }

            if (message.avatar == null) {
                Uri lookupUri = ContactInfo.getLookupUri(context, message.from);
                if (lookupUri != null) {
                    update = true;
                    message.avatar = lookupUri.toString();
                    Log.i(folder.name + " updated id=" + message.id + " lookup=" + lookupUri);
                }
            }

            if (update)
                db.message().updateMessage(message);
            else
                Log.i(folder.name + " unchanged uid=" + uid);
        }

        if (!folder.isOutgoing() && !EntityFolder.ARCHIVE.equals(folder.type)) {
            Address[] senders = (message.reply != null ? message.reply : message.from);
            if (senders != null)
                for (Address sender : senders) {
                    String email = ((InternetAddress) sender).getAddress();
                    String name = ((InternetAddress) sender).getPersonal();
                    List<EntityContact> contacts = db.contact().getContacts(EntityContact.TYPE_FROM, email);
                    if (contacts.size() == 0) {
                        EntityContact contact = new EntityContact();
                        contact.type = EntityContact.TYPE_FROM;
                        contact.email = email;
                        contact.name = name;
                        contact.id = db.contact().insertContact(contact);
                        Log.i("Inserted sender contact=" + contact);
                    } else {
                        EntityContact contact = contacts.get(0);
                        if (name != null && !name.equals(contact.name)) {
                            contact.name = name;
                            db.contact().updateContact(contact);
                            Log.i("Updated sender contact=" + contact);
                        }
                    }
                }
        }

        List<String> fkeywords = new ArrayList<>(Arrays.asList(folder.keywords));

        for (String keyword : keywords)
            if (!fkeywords.contains(keyword)) {
                Log.i(folder.name + " adding keyword=" + keyword);
                fkeywords.add(keyword);
            }

        if (folder.keywords.length != fkeywords.size()) {
            Collections.sort(fkeywords);
            db.folder().setFolderKeywords(folder.id, DB.Converters.fromStringArray(fkeywords.toArray(new String[0])));
        }

        if (filter && Helper.isPro(context))
            try {
                for (EntityRule rule : rules)
                    if (rule.matches(context, message, imessage)) {
                        rule.execute(context, db, message);
                        if (rule.stop)
                            break;
                    }
            } catch (Throwable ex) {
                Log.e(ex);
                db.message().setMessageError(message.id, Helper.formatThrowable(ex));
            }

        return message;
    }

    static void downloadMessage(
            Context context,
            EntityFolder folder, IMAPFolder ifolder,
            IMAPMessage imessage, long id) throws MessagingException, IOException {
        DB db = DB.getInstance(context);
        EntityMessage message = db.message().getMessage(id);
        if (message == null)
            return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long maxSize = prefs.getInt("download", 32768);
        if (maxSize == 0)
            maxSize = Long.MAX_VALUE;

        List<EntityAttachment> attachments = db.attachment().getAttachments(message.id);
        MessageHelper helper = new MessageHelper(imessage);
        Boolean isMetered = Helper.isMetered(context, false);
        boolean metered = (isMetered == null || isMetered);

        boolean fetch = false;
        if (!message.content)
            if (!metered || (message.size != null && message.size < maxSize))
                fetch = true;

        if (!fetch)
            for (EntityAttachment attachment : attachments)
                if (!attachment.available)
                    if (!metered || (attachment.size != null && attachment.size < maxSize)) {
                        fetch = true;
                        break;
                    }

        if (fetch) {
            Log.i(folder.name + " fetching message id=" + message.id);
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(FetchProfile.Item.CONTENT_INFO); // body structure
            fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(IMAPFolder.FetchProfileItem.HEADERS);
            fp.add(IMAPFolder.FetchProfileItem.MESSAGE);
            fp.add(FetchProfile.Item.SIZE);
            fp.add(IMAPFolder.FetchProfileItem.INTERNALDATE);
            ifolder.fetch(new Message[]{imessage}, fp);

            MessageHelper.MessageParts parts = helper.getMessageParts();

            if (!message.content) {
                if (!metered || (message.size != null && message.size < maxSize)) {
                    String body = parts.getHtml(context);
                    Helper.writeText(EntityMessage.getFile(context, message.id), body);
                    db.message().setMessageContent(message.id, true, HtmlHelper.getPreview(body));
                    db.message().setMessageWarning(message.id, parts.getWarnings(message.warning));
                    Log.i(folder.name + " downloaded message id=" + message.id + " size=" + message.size);
                }
            }

            for (EntityAttachment attachment : attachments)
                if (!attachment.available)
                    if (!metered || (attachment.size != null && attachment.size < maxSize))
                        if (!parts.downloadAttachment(context, db, attachment.id, attachment.sequence))
                            break;
        }
    }

    static void reportError(Context context, EntityAccount account, EntityFolder folder, Throwable ex) {
        // FolderClosedException: can happen when no connectivity

        // IllegalStateException:
        // - "This operation is not allowed on a closed folder"
        // - can happen when syncing message

        // ConnectionException
        // - failed to create new store connection (connectivity)

        // MailConnectException
        // - on connectivity problems when connecting to store

        String title;
        if (account == null)
            title = folder.name;
        else if (folder == null)
            title = account.name;
        else
            title = account.name + "/" + folder.name;

        String tag = "error:" + (account == null ? 0 : account.id) + ":" + (folder == null ? 0 : folder.id);

        EntityLog.log(context, title + " " + Helper.formatThrowable(ex));

        if ((ex instanceof SendFailedException) || (ex instanceof AlertException)) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(tag, 1, getNotificationError(context, title, ex).build());
        }

        // connection failure: Too many simultaneous connections

        if (BuildConfig.DEBUG &&
                !(ex instanceof SendFailedException) &&
                !(ex instanceof MailConnectException) &&
                !(ex instanceof FolderClosedException) &&
                !(ex instanceof IllegalStateException) &&
                !(ex instanceof AuthenticationFailedException) && // Also: Too many simultaneous connections
                !(ex instanceof StoreClosedException) &&
                !(ex instanceof MessageRemovedException) &&
                !(ex instanceof MessagingException && ex.getCause() instanceof UnknownHostException) &&
                !(ex instanceof MessagingException && ex.getCause() instanceof ConnectionException) &&
                !(ex instanceof MessagingException && ex.getCause() instanceof SocketException) &&
                !(ex instanceof MessagingException && ex.getCause() instanceof SocketTimeoutException) &&
                !(ex instanceof MessagingException && ex.getCause() instanceof SSLException) &&
                !(ex instanceof MessagingException && "connection failure".equals(ex.getMessage()))) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(tag, 1, getNotificationError(context, title, ex).build());
        }
    }

    static NotificationCompat.Builder getNotificationError(Context context, String title, Throwable ex) {
        return getNotificationError(context, "error", title, ex, true);
    }

    static NotificationCompat.Builder getNotificationError(Context context, String channel, String title, Throwable ex, boolean debug) {
        // Build pending intent
        Intent intent = new Intent(context, ActivitySetup.class);
        if (debug)
            intent.setAction("error");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                context, ActivitySetup.REQUEST_ERROR, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        // Build notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channel);

        builder
                .setSmallIcon(R.drawable.baseline_warning_white_24)
                .setContentTitle(context.getString(R.string.title_notification_failed, title))
                .setContentText(Helper.formatThrowable(ex))
                .setContentIntent(pi)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        builder.setStyle(new NotificationCompat.BigTextStyle()
                .bigText(Helper.formatThrowable(ex, false, "\n")));

        return builder;
    }

    static class AlertException extends Throwable {
        private String alert;

        AlertException(String alert) {
            this.alert = alert;
        }

        @Override
        public String getMessage() {
            return alert;
        }
    }

    static class State {
        private Thread thread;
        private Semaphore semaphore = new Semaphore(0);
        private boolean running = true;

        void runnable(Runnable runnable, String name) {
            thread = new Thread(runnable, name);
            thread.setPriority(THREAD_PRIORITY_BACKGROUND);
        }

        void release() {
            semaphore.release();
            yield();
        }

        void acquire() throws InterruptedException {
            semaphore.acquire();
        }

        boolean acquire(long milliseconds) throws InterruptedException {
            return semaphore.tryAcquire(milliseconds, TimeUnit.MILLISECONDS);
        }

        void error() {
            thread.interrupt();
            yield();
        }

        private void yield() {
            try {
                // Give interrupted thread some time to acquire wake lock
                Thread.sleep(YIELD_DURATION);
            } catch (InterruptedException ignored) {
            }
        }

        void start() {
            thread.start();
        }

        void stop() {
            running = false;
            semaphore.release();
        }

        void join() {
            join(thread);
        }

        boolean running() {
            return running;
        }

        void join(Thread thread) {
            boolean joined = false;
            while (!joined)
                try {
                    Log.i("Joining " + thread.getName());
                    thread.join();
                    joined = true;
                    Log.i("Joined " + thread.getName());
                } catch (InterruptedException ex) {
                    Log.w(thread.getName() + " join " + ex.toString());
                }
        }

        @NonNull
        @Override
        public String toString() {
            return "[running=" + running + "]";
        }
    }
}
