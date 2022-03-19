package com.example.appprojetreseau;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.Pair;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// https://developer.android.com/guide/topics/connectivity/bluetooth

public class MainActivity extends AppCompatActivity {

    Button sendButton;
    EditText messageInput;
    LinearLayout messageList;

    static BluetoothAdapter bluetoothAdapter;
    static Map<String, BluetoothDevice> pairedDevices;
    static BluetoothSocket socket;
    static OutputStream socketOutput;
    static BufferedInputStream socketInput;
    static int channel;
    static boolean initialized = false;
    static ArrayList<Pair<String, String>> savedMessageList;
    static Thread receiveMessageThread;

    void init() {

        if (!initialized) {

            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                displayMessage("erreur", "BluetoothAdapter.getDefaultAdapter()");
            }

            socket = null;
            channel = 1;
            updateBluetoothDevices();
            savedMessageList = new ArrayList<>();
            receiveMessageThread = null;

            initialized = true;
        }

        else {
            restoreSavedMessages();
            if (socket != null) {
                startReceiveMessageThread();
            }
        }
    }

    void restoreSavedMessages() {
        for (Pair<String, String> message : savedMessageList) {
            displayMessage(message.first, message.second, false);
        }
    }

    void startReceiveMessageThread() {
        receiveMessageThread = new Thread(this::receiveMessages);
        receiveMessageThread.start();
    }

    void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)getSystemService(Service.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(messageInput.getWindowToken(), 0);
    }

    String getInput() {
        String text = messageInput.getText().toString();
        messageInput.setText("");
        return text.trim();
    }

    void displayMessage(String title, String content, boolean save) {
        TextView message = new TextView(this);
        message.setTextSize(16);
        if (title != null) {
            SpannableString spannableText = new SpannableString(title + " : " + content);
            spannableText.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length() + 2, 0);
            message.setText(spannableText);
        } else {
            message.setText(content);
        }
        if (save) {
            savedMessageList.add(new Pair<>(title, content));
        }
        messageList.addView(message);
    }

    void displayMessage(String title, String content) {
        displayMessage(title, content, true);
    }

    void displayMessage(String content) {
        displayMessage(null, content);
    }

    boolean checkBluetoothPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        displayMessage("erreur", "permission bluetooth");
        return false;
    }

    void updateBluetoothDevices() {
        if (checkBluetoothPermission()) {
            Set<BluetoothDevice> devices = bluetoothAdapter.getBondedDevices();
            pairedDevices = new HashMap<>();
            for (BluetoothDevice device : devices) {
                pairedDevices.put(device.getName(), device);
            }
        }
    }

    void displayBluetoothDevices() {
        if (pairedDevices.isEmpty()) {
            displayMessage("liste des appareils", "(vide)");
        } else {
            displayMessage("liste des appareils", "");
            for (String name : pairedDevices.keySet()) {
                displayMessage(" - " + name);
            }
        }
    }

    boolean createSocket(BluetoothDevice device) {
        if (checkBluetoothPermission()) {
            try {
                Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                socket = (BluetoothSocket)m.invoke(device, channel);
                return true;
            } catch (Exception e) {
                socket = null;
                displayMessage("erreur", "creation du socket (" + e + ")");
            }
        }
        return false;
    }

    boolean closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch(Exception e2) {
                displayMessage("erreur", "fermeture du socket (" + e2 + ")");
            }
            socket = null;
            return true;
        }
        return false;
    }

    void connect(String name) {
        BluetoothDevice device = pairedDevices.get(name);
        if (device == null) {
            displayMessage("erreur", "mauvais nom");
        } else if (createSocket(device)) {
            try {
                socket.connect();
                socketOutput = socket.getOutputStream();
                socketInput = new BufferedInputStream(socket.getInputStream());
                startReceiveMessageThread();
                displayMessage("info", "connecté");
            } catch (Exception e) {
                closeSocket();
                displayMessage("erreur", "connection (" + e + ")");
            }
        }
    }

    void disconnect() {
        if (closeSocket()) {
            displayMessage("info", "déconnecté");
        } else {
            displayMessage("erreur", "aucun appareil connecté");
        }
    }

    String waitForMessage() {
        byte[] buf = new byte[512];
        try {
            while (Thread.currentThread() == receiveMessageThread) {
                int n = socketInput.read(buf, 0, Math.min(buf.length, socketInput.available()));
                if (n < 0) {
                    return null;
                }
                if (n > 0) {
                    return new String(buf, 0, n);
                }
                Thread.sleep(500);
            }
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (socket != null) displayMessage("erreur", e.toString());
            });
        }
        return null;
    }

    void receiveMessages() {
        while (true) {
            final String message = waitForMessage();
            if (Thread.currentThread() != receiveMessageThread) {
                break;
            }
            if (message == null) {
                runOnUiThread(() -> {
                    if (socket != null) {
                        disconnect();
                    }
                });
                break;
            }
            runOnUiThread(() -> {
                displayMessage("reçu", message);
            });
        }
    }

    void sendMessage(String message) {
        if (socket != null) {
            try {
                socketOutput.write(message.getBytes());
                displayMessage("envoyé", message);
            } catch (Exception e) {
                displayMessage("erreur", e.toString());
                disconnect();
            }
        } else {
            displayMessage("erreur", "aucun appareil connecté");
        }
    }

    void stopServer() {
        if (socket != null) {
            sendMessage("stop");
            disconnect();
        } else {
            displayMessage("erreur", "aucun appareil connecté");
        }
    }

    void setChannel(String channel) {
        try {
            int tmp = Integer.parseInt(channel);
            if (tmp < 1 || tmp > 30) {
                displayMessage("erreur", "channel invalide");
            }
            else {
                this.channel = tmp;
                displayMessage("channel", Integer.toString(this.channel));
            }
        } catch (Exception e) {
            displayMessage("erreur", "valeur invalide");
        }
    }

    void processInput() {

        String text = getInput();

        if (!text.isEmpty()) {

            if (text.equals("list")) {
                displayBluetoothDevices();
            } else if (text.equals("test")) {
                for (int i = 0; i < 30; i++) displayMessage(Integer.toString(i));
            } else if (text.equals("update")) {
                updateBluetoothDevices();
                displayBluetoothDevices();
            } else if (text.equals("disconnect")) {
                disconnect();
            } else if (text.equals("stop")) {
                stopServer();
            } else if (text.startsWith("connect ")) {
                connect(text.substring(8).trim());
            } else if (text.equals("channel")) {
                displayMessage("channel", Integer.toString(channel));
            } else if (text.startsWith("channel ")) {
                setChannel(text.substring(8).trim());
            } else {
                sendMessage(text);
            }

            hideKeyboard();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sendButton = findViewById(R.id.sendButton);
        messageInput = findViewById(R.id.messageInput);
        messageList = findViewById(R.id.messageList);

        init();

        sendButton.setOnClickListener(v -> processInput());
    }
}