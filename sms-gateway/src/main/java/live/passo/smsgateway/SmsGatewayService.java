package live.passo.smsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class SmsGatewayService extends Service {
    private static final String KEY_BASE_URL = "base_url";
    // Keep this key in sync with MainActivity.KEY_DEVICE_TOKEN. The value is
    // encrypted at rest and must be decrypted before the service can heartbeat.
    private static final String KEY_DEVICE_TOKEN = "device_token_enc";
    private static final String CHANNEL_ID = "passo_sms_gateway";
    private static final int NOTIFICATION_ID = 5091;
    private static final int MAX_ENCRYPTED_PAYLOAD_CHARS = 16000;
    private static final long SMS_RESULT_TIMEOUT_SECONDS = 90;
    private static final ConcurrentHashMap<String, SmsSendTracker> pendingSms = new ConcurrentHashMap<>();
    private static final Pattern OTP_TEXT = Pattern.compile("^PASSO (?:sign-in|registration|cargo request|group travel request) verification code: \\d{6}\\. It expires in 10 minutes\\. Do not share it\\.$");
    private static final Pattern PASSO_TRANSACTION_TEXT = Pattern.compile("^(?:PASSO(?:[\\p{L}\\p{N}]+|[ :])|RoutePass(?::| ))[^\\r\\n]{1,1530}$");
    private static final Pattern ETHIOPIAN_PHONE = Pattern.compile("^\\+251[79]\\d{8}$");

    private volatile boolean running;
    private String baseUrl;
    private String deviceToken;
    private ScheduledExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires a started foreground service to publish its notification
        // immediately. Do this before every validation/early-return path so an
        // unpaired phone is handled cleanly instead of crashing the app.
        startForegroundServiceNotification("Starting secure SMS gateway…");
        if (intent == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        baseUrl = normalizeBase(intent.getStringExtra(KEY_BASE_URL));
        try {
            String encryptedToken = getSharedPreferences("passo_gateway", MODE_PRIVATE).getString(KEY_DEVICE_TOKEN, "");
            deviceToken = GatewayCrypto.decryptDeviceToken(encryptedToken);
        } catch (Exception ignored) { deviceToken = ""; }
        if (baseUrl.isEmpty() || deviceToken.length() < 32) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        updateNotification("Connecting to Passo…");
        if (executor == null || executor.isShutdown()) {
            running = true;
            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleWithFixedDelay(this::pollSafely, 0, 5, TimeUnit.SECONDS);
        }
        return START_NOT_STICKY;
    }

    private String normalizeBase(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result.startsWith("https://") ? result : "";
    }

    private void pollSafely() {
        if (!running) return;
        try {
            boolean smsPermissionGranted = checkSelfPermission(android.Manifest.permission.SEND_SMS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            JSONObject claimRequest = new JSONObject().put("smsPermissionGranted", smsPermissionGranted);
            JSONObject response = post("/api/sms-gateway/claim", claimRequest);
            if (!smsPermissionGranted) {
                updateNotification("Enable SMS permission in Passo SMS Gateway settings");
                return;
            }
            JSONObject job = response.optJSONObject("job");
            if (job == null || job == JSONObject.NULL) {
                updateNotification("Connected · waiting for Passo messages");
                return;
            }
            deliver(job);
        } catch (UnauthorizedException error) {
            updateNotification("Pair again in Passo Admin");
            running = false;
            stopSelf();
        } catch (Exception error) {
            updateNotification("Reconnecting to Passo…");
        }
    }

    private void deliver(JSONObject job) {
        String id = job.optString("id", "");
        String encryptedPayload = job.optString("encryptedPayload", "");
        String lease = job.optString("leaseToken", "");
        boolean sent = false;
        boolean payloadValid = false;
        String failureReason = "job_invalid";
        String phone = "";
        String message = "";
        try {
            // Hybrid encryption expands multipart text substantially. The old
            // 3 KB ceiling rejected valid messages near the carrier's 10-part
            // limit before Android could split and send them.
            if (encryptedPayload.length() < 100 || encryptedPayload.length() > MAX_ENCRYPTED_PAYLOAD_CHARS) throw new IllegalArgumentException("Invalid encrypted payload.");
            JSONObject secret = GatewayCrypto.decryptOtpPayload(encryptedPayload);
            phone = secret.optString("phone", "");
            message = secret.optString("message", "");
            payloadValid = true;
            failureReason = "payload_invalid";
        } catch (Exception error) {
            failureReason = "payload_" + error.getClass().getSimpleName();
            phone = "";
            message = "";
        }
        if (id.isEmpty() || lease.isEmpty()) {
            failureReason = "job_invalid";
        } else if (!payloadValid) {
            // Keep the decryption failure category recorded above.
        } else if (!ETHIOPIAN_PHONE.matcher(phone).matches()) {
            failureReason = "recipient_invalid";
        } else if (!OTP_TEXT.matcher(message).matches() && !PASSO_TRANSACTION_TEXT.matcher(message).matches()) {
            failureReason = "message_policy_rejected";
        } else {
            try {
                failureReason = sendAndConfirmWithSim(phone, message, id, lease);
                sent = failureReason.isEmpty();
            } catch (SecurityException error) {
                boolean permissionGranted = checkSelfPermission(android.Manifest.permission.SEND_SMS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                String stage = error instanceof SmsSendSecurityException
                        ? ((SmsSendSecurityException) error).stage : "manager";
                failureReason = permissionGranted ? "sms_android_blocked_" + stage : "sms_permission_denied";
                android.util.Log.w("PassoSmsGateway", "Android blocked SMS operation at " + stage +
                        "; smsPermissionGranted=" + permissionGranted);
            } catch (IllegalArgumentException error) {
                failureReason = "sms_arguments_invalid";
            } catch (Exception error) {
                failureReason = "sms_manager_" + error.getClass().getSimpleName();
                sent = false;
            }
        }
        if (!sent) android.util.Log.w("PassoSmsGateway", "SMS delivery failed [" + failureReason + "]");
        try {
            JSONObject acknowledgement = new JSONObject();
            acknowledgement.put("leaseToken", lease);
            acknowledgement.put("status", sent ? "SENT" : "FAILED");
            if (!sent) acknowledgement.put("failureCode", failureReason);
            post("/api/sms-gateway/jobs/" + id + "/ack", acknowledgement);
            updateNotification(sent ? "Passo message sent from this phone" :
                    "SMS not sent: " + smsFailureDescription(failureReason));
        } catch (Exception ignored) {
            updateNotification("Message queued · reconnecting to Passo");
        } finally {
            // Java strings cannot be reliably zeroed, so never display or log these values.
            phone = "";
            message = "";
        }
    }

    @SuppressWarnings("deprecation")
    private String sendAndConfirmWithSim(String phone, String message, String jobId, String lease) throws Exception {
        SmsManager smsManager = null;
        if (Build.VERSION.SDK_INT >= 31) {
            int subscriptionId;
            try {
                subscriptionId = SmsManager.getDefaultSmsSubscriptionId();
            } catch (SecurityException error) {
                throw new SmsSendSecurityException("subscription_lookup", error);
            }
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return "sms_subscription_not_selected";
            SmsManager defaultManager;
            try {
                defaultManager = getSystemService(SmsManager.class);
            } catch (SecurityException error) {
                // Some Android builds reject the typed service lookup for a
                // background gateway. The subscription-scoped legacy factory
                // remains available and avoids changing the selected SIM.
                try {
                    smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
                    defaultManager = null;
                } catch (SecurityException fallbackError) {
                    throw new SmsSendSecurityException("manager_lookup", fallbackError);
                }
            }
            if (defaultManager == null && smsManager == null) return "sms_not_supported";
            if (defaultManager != null) {
                try {
                    smsManager = defaultManager.createForSubscriptionId(subscriptionId);
                } catch (SecurityException error) {
                    try {
                        smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
                    } catch (SecurityException fallbackError) {
                        throw new SmsSendSecurityException("subscription_manager", fallbackError);
                    }
                }
            }
        } else {
            try {
                smsManager = SmsManager.getDefault();
            } catch (SecurityException error) {
                throw new SmsSendSecurityException("manager_lookup", error);
            }
        }
        java.util.ArrayList<String> parts;
        try {
            parts = smsManager.divideMessage(message);
        } catch (SecurityException error) {
            throw new SmsSendSecurityException("message_split", error);
        }
        if (parts == null || parts.isEmpty() || parts.size() > 10) return "sms_parts_limit";

        String trackerKey = jobId + ":" + lease;
        SmsSendTracker tracker = new SmsSendTracker(parts.size());
        pendingSms.put(trackerKey, tracker);
        try {
            java.util.ArrayList<PendingIntent> sentIntents = new java.util.ArrayList<>(parts.size());
            for (int index = 0; index < parts.size(); index++) {
                Intent callback = new Intent(this, SmsSentReceiver.class)
                        .setAction(getPackageName() + ".SMS_SENT." + jobId + "." + index)
                        .putExtra(SmsSentReceiver.EXTRA_JOB_ID, jobId)
                        .putExtra(SmsSentReceiver.EXTRA_LEASE, lease)
                        .putExtra(SmsSentReceiver.EXTRA_PART_INDEX, index);
                int requestCode = (jobId + ":" + lease + ":" + index).hashCode() & 0x7fffffff;
                try {
                    sentIntents.add(PendingIntent.getBroadcast(this, requestCode, callback,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                } catch (SecurityException error) {
                    throw new SmsSendSecurityException("callback", error);
                }
            }

            try {
                if (parts.size() == 1) {
                    smsManager.sendTextMessage(phone, null, parts.get(0), sentIntents.get(0), null);
                } else {
                    smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null);
                }
            } catch (SecurityException error) {
                throw new SmsSendSecurityException("submit", error);
            }

            boolean completed = tracker.latch.await(SMS_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) return "sms_send_timeout";
            return tracker.allAccepted.get() ? "" : tracker.failureCode;
        } finally {
            pendingSms.remove(trackerKey, tracker);
        }
    }

    private static final class SmsSendSecurityException extends SecurityException {
        final String stage;
        SmsSendSecurityException(String stage, SecurityException cause) {
            super(cause);
            this.stage = stage;
        }
    }

    static void recordSmsPartResult(String jobId, String lease, int partIndex, int resultCode) {
        SmsSendTracker tracker = pendingSms.get(jobId + ":" + lease);
        if (tracker == null || partIndex < 0 || partIndex >= tracker.partCount || !tracker.completedParts.add(partIndex)) return;
        if (resultCode != android.app.Activity.RESULT_OK) {
            tracker.allAccepted.set(false);
            tracker.failureCode = smsFailureCode(resultCode);
        }
        if (tracker.completedParts.size() == tracker.partCount) tracker.latch.countDown();
    }

    private static String smsFailureCode(int resultCode) {
        if (resultCode == SmsManager.RESULT_ERROR_RADIO_OFF) return "sms_radio_off";
        if (resultCode == SmsManager.RESULT_ERROR_NO_SERVICE) return "sms_no_service";
        if (resultCode == SmsManager.RESULT_ERROR_LIMIT_EXCEEDED) return "sms_limit_exceeded";
        if (resultCode == SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE) return "sms_fdn_check_failure";
        if (resultCode == SmsManager.RESULT_ERROR_NULL_PDU) return "sms_null_pdu";
        return "sms_generic_failure";
    }

    /** Keep SMS content and recipient private while giving the phone owner a useful cause. */
    private static String smsFailureDescription(String code) {
        switch (code) {
            case "sms_subscription_not_selected": return "select a SIM for SMS in phone settings";
            case "sms_not_supported": return "this phone cannot send SMS";
            case "sms_parts_limit": return "message is too long for this SIM";
            case "sms_permission_denied": return "allow SMS permission for Passo SMS Gateway";
            case "sms_android_blocked_subscription_lookup": return "Android blocked access to the default SMS SIM";
            case "sms_android_blocked_manager_lookup": return "Android could not open the phone SMS service";
            case "sms_android_blocked_subscription_manager": return "Android blocked access to the selected SIM SMS service";
            case "sms_android_blocked_message_split": return "Android could not prepare the message for this SIM";
            case "sms_android_blocked_submit": return "Android blocked the send request; check SMS access and the selected SIM";
            case "sms_android_blocked_callback": return "Android blocked SMS confirmation setup; check app permissions";
            case "sms_android_blocked_manager": return "Android blocked access to the SIM SMS service";
            case "sms_arguments_invalid": return "check the recipient number and message";
            case "sms_radio_off": return "SIM radio is off";
            case "sms_no_service": return "SIM has no mobile network service";
            case "sms_limit_exceeded": return "SIM or carrier sending limit was reached";
            case "sms_fdn_check_failure": return "SIM fixed-number restrictions blocked it";
            case "sms_null_pdu": return "phone could not prepare the SMS";
            case "sms_send_timeout": return "mobile network did not respond in time";
            case "sms_carrier_rejected_or_timeout": return "mobile network rejected it or did not confirm";
            case "sms_generic_failure": return "phone or mobile network rejected it";
            case "message_policy_rejected": return "message did not match Passo's sending rules";
            case "recipient_invalid": return "recipient number is invalid";
            default:
                if (code != null && code.startsWith("sms_manager_")) return "phone could not start the SMS service";
                if (code != null && code.startsWith("payload_")) return "secure message could not be opened";
                return "check the SIM and mobile network";
        }
    }

    private static final class SmsSendTracker {
        final int partCount;
        final java.util.Set<Integer> completedParts = ConcurrentHashMap.newKeySet();
        final AtomicBoolean allAccepted = new AtomicBoolean(true);
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String failureCode = "sms_carrier_rejected_or_timeout";
        SmsSendTracker(int partCount) { this.partCount = partCount; }
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + deviceToken);
        connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] responseBytes = input == null ? new byte[0] : input.readAllBytes();
        connection.disconnect();
        if (status == 401) throw new UnauthorizedException();
        if (status < 200 || status >= 300) throw new IllegalStateException("Gateway request failed.");
        return new JSONObject(new String(responseBytes, StandardCharsets.UTF_8));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Passo SMS gateway", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when Passo is sending encrypted SMS through this phone.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String message) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("Passo SMS Gateway")
                    .setContentText(message)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Passo SMS Gateway")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void startForegroundServiceNotification(String message) {
        Notification notification = notification(message);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(message));
    }

    @Override
    public void onDestroy() {
        running = false;
        if (executor != null) executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class UnauthorizedException extends Exception { }
}
