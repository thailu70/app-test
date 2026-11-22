package live.passo.smsgateway;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQUEST_GATEWAY_PERMISSIONS = 8102;
    private static final String PREFS = "passo_gateway";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_DEVICE_TOKEN = "device_token_enc";

    private EditText serverField;
    private EditText pairingCodeField;
    private TextView statusLabel;
    private Button pairButton;
    private Button startButton;
    private SharedPreferences preferences;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildScreen();
        String savedBase = preferences.getString(KEY_BASE_URL, "https://passo.live");
        serverField.setText(savedBase);
        updatePairedState();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        layout.setPadding(pad, dp(34), pad, pad);
        layout.setBackgroundColor(0xFFFFFBF6);

        TextView title = new TextView(this);
        title.setText("Passo SMS Gateway");
        title.setTextSize(28);
        title.setTextColor(0xFF102640);
        title.setGravity(Gravity.START);
        layout.addView(title, matchWrap());

        TextView intro = new TextView(this);
        intro.setText("A private pilot companion that sends Passo sign-in codes through this phone’s SIM. It only sends codes to numbers enabled in Passo Admin → Settings.");
        intro.setTextSize(16);
        intro.setTextColor(0xFF506070);
        intro.setPadding(0, dp(12), 0, dp(20));
        layout.addView(intro, matchWrap());

        TextView serverLabel = label("Passo server");
        layout.addView(serverLabel, matchWrap());
        serverField = new EditText(this);
        serverField.setSingleLine(true);
        serverField.setInputType(0x00000011);
        serverField.setHint("https://passo.live");
        layout.addView(serverField, matchWrap());

        TextView codeLabel = label("One-time pairing code");
        codeLabel.setPadding(0, dp(20), 0, 0);
        layout.addView(codeLabel, matchWrap());
        pairingCodeField = new EditText(this);
        pairingCodeField.setSingleLine(true);
        pairingCodeField.setInputType(2);
        pairingCodeField.setHint("Enter the 8-digit code from Admin");
        layout.addView(pairingCodeField, matchWrap());

        pairButton = new Button(this);
        pairButton.setText("Pair this phone");
        pairButton.setOnClickListener(view -> pairPhone());
        layout.addView(pairButton, buttonParams());

        statusLabel = new TextView(this);
        statusLabel.setTextSize(15);
        statusLabel.setTextColor(0xFF0C7667);
        statusLabel.setPadding(0, dp(16), 0, dp(12));
        layout.addView(statusLabel, matchWrap());

        startButton = new Button(this);
        startButton.setText("Start SMS gateway");
        startButton.setOnClickListener(view -> requestGatewayPermissions());
        layout.addView(startButton, buttonParams());

        Button stopButton = new Button(this);
        stopButton.setText("Stop SMS gateway");
        stopButton.setOnClickListener(view -> {
            stopService(new Intent(this, SmsGatewayService.class));
            statusLabel.setText("Gateway stopped.");
        });
        layout.addView(stopButton, buttonParams());

        Button disconnectButton = new Button(this);
        disconnectButton.setText("Clear pairing from this phone");
        disconnectButton.setOnClickListener(view -> {
            stopService(new Intent(this, SmsGatewayService.class));
            preferences.edit().remove(KEY_DEVICE_TOKEN).apply();
            GatewayCrypto.deleteKeys();
            updatePairedState();
        });
        layout.addView(disconnectButton, buttonParams());

        TextView note = new TextView(this);
        note.setText("OTP content is encrypted to this phone and never shown here or in notifications. Android may keep sent messages in its Messages history. Keep this phone powered and connected while testing; SMS charges come from its SIM. Disconnect it in Passo Admin → Settings.");
        note.setTextSize(13);
        note.setTextColor(0xFF65717D);
        note.setPadding(0, dp(20), 0, 0);
        layout.addView(note, matchWrap());

        scroll.addView(layout);
        setContentView(scroll);
    }

    private TextView label(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(0xFF263A4F);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String cleanBaseUrl() {
        String value = serverField.getText().toString().trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.startsWith("https://") || value.length() > 200) return "";
        return value;
    }

    private void pairPhone() {
        String base = cleanBaseUrl();
        String code = pairingCodeField.getText().toString().trim();
        if (base.isEmpty() || !code.matches("\\d{8}")) {
            statusLabel.setText("Enter the secure Passo address and current 8-digit pairing code.");
            return;
        }
        pairButton.setEnabled(false);
        statusLabel.setText("Pairing with Passo…");
        new Thread(() -> {
            try {
                String publicKey = GatewayCrypto.publicKeyPem();
                JSONObject body = new JSONObject();
                body.put("code", code);
                body.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
                body.put("publicKey", publicKey);
                JSONObject result = post(base + "/api/sms-gateway/pair", null, body);
                String token = result.optString("token", "");
                if (token.length() < 32) throw new IllegalStateException("The server returned an invalid pairing response.");
                String protectedToken = GatewayCrypto.encryptDeviceToken(token);
                preferences.edit().putString(KEY_BASE_URL, base).putString(KEY_DEVICE_TOKEN, protectedToken).apply();
                runOnUiThread(() -> {
                    pairingCodeField.setText("");
                    statusLabel.setText("Phone paired. Start the gateway when you’re ready to test sign-in SMS.");
                    updatePairedState();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    statusLabel.setText("Could not pair. Check the code and internet connection, then try again.");
                    pairButton.setEnabled(true);
                });
            }
        }, "passo-gateway-pair").start();
    }

    private void updatePairedState() {
        boolean paired = !preferences.getString(KEY_DEVICE_TOKEN, "").isEmpty() && GatewayCrypto.hasRsaKey();
        statusLabel.setText(paired ? "Phone is paired with Passo." : "Pair this phone before starting the gateway.");
        startButton.setEnabled(paired);
        pairButton.setEnabled(true);
    }

    private void requestGatewayPermissions() {
        if (preferences.getString(KEY_DEVICE_TOKEN, "").length() < 32) {
            updatePairedState();
            return;
        }
        boolean needsSms = checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED;
        boolean needsNotifications = Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        if (needsSms || needsNotifications) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS}, REQUEST_GATEWAY_PERMISSIONS);
            } else {
                requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQUEST_GATEWAY_PERMISSIONS);
            }
            return;
        }
        startGateway();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_GATEWAY_PERMISSIONS) return;
        boolean smsGranted = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        if (!smsGranted) {
            statusLabel.setText("SMS permission is needed to send test sign-in codes.");
            return;
        }
        startGateway();
    }

    private void startGateway() {
        String base = preferences.getString(KEY_BASE_URL, "https://passo.live");
        String token = preferences.getString(KEY_DEVICE_TOKEN, "");
        Intent intent = new Intent(this, SmsGatewayService.class);
        intent.putExtra(KEY_BASE_URL, base);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        statusLabel.setText("Starting the gateway…");
    }

    private JSONObject post(String urlText, String token, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.getOutputStream().write(bytes);
        int status = connection.getResponseCode();
        java.io.InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] response = input == null ? new byte[0] : input.readAllBytes();
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IllegalStateException("Pairing was rejected.");
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }
}
