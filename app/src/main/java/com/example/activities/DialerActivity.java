package com.example.activities;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.MainActivity;
import com.example.R;
import com.example.adapters.ContactsAdapter;
import com.example.adapters.CallsAdapter;
import com.example.call.CallManager;
import com.example.database.DatabaseHelper;
import com.example.models.CallLogItem;
import com.example.models.User;
import com.example.services.AudioTransferService;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;

public class DialerActivity extends AppCompatActivity {

    private TextView tvNumber;
    private ImageButton btnBackspace;
    private View scrollQuickActions;
    private View gridDialpad;
    private View panelCallHistory;
    private View panelContacts;
    private RecyclerView rvDialerCallHistory;
    private RecyclerView rvDialerContacts;
    private ChipGroup chipGroupCallFilters;

    private StringBuilder numberBuilder = new StringBuilder();
    private ToneGenerator toneGenerator;

    private DatabaseHelper dbHelper;
    private List<CallLogItem> masterCallLogList = new ArrayList<>();
    private List<CallLogItem> filteredCallLogList = new ArrayList<>();
    private CallsAdapter callHistoryAdapter;

    private List<User> allContactsList = new ArrayList<>();
    private List<User> filteredContactsList = new ArrayList<>();
    private ContactsAdapter contactsAdapter;
    private EditText etDialerSearchContacts;

    private String currentFilter = "ALL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dialer);

        dbHelper = DatabaseHelper.getInstance(this);

        tvNumber = findViewById(R.id.tvDialerNumber);
        btnBackspace = findViewById(R.id.btnBackspace);
        scrollQuickActions = findViewById(R.id.scrollQuickActions);
        gridDialpad = findViewById(R.id.gridDialpad);

        panelCallHistory = findViewById(R.id.panelCallHistory);
        panelContacts = findViewById(R.id.panelContacts);
        rvDialerCallHistory = findViewById(R.id.rvDialerCallHistory);
        rvDialerContacts = findViewById(R.id.rvDialerContacts);
        chipGroupCallFilters = findViewById(R.id.chipGroupCallFilters);

        rvDialerCallHistory.setLayoutManager(new LinearLayoutManager(this));
        rvDialerContacts.setLayoutManager(new LinearLayoutManager(this));

        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_DTMF, 80);
        } catch (Exception ignored) {
        }

        setupKeypad();
        setupQuickActions();
        setupFooterActions();
        setupCallHistoryPanel();
        setupContactsPanel();

        findViewById(R.id.btnCloseDialer).setOnClickListener(v -> finish());

        btnBackspace.setOnClickListener(v -> {
            if (numberBuilder.length() > 0) {
                numberBuilder.deleteCharAt(numberBuilder.length() - 1);
                updateNumberDisplay();
            }
        });

        btnBackspace.setOnLongClickListener(v -> {
            numberBuilder.setLength(0);
            updateNumberDisplay();
            return true;
        });

        updateNumberDisplay();

        // Process incoming intent data from Google Search, Chrome, or external apps
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        String number = null;
        Uri data = intent.getData();

        if (data != null) {
            String scheme = data.getScheme();
            if ("tel".equalsIgnoreCase(scheme) || "voicemail".equalsIgnoreCase(scheme)) {
                number = data.getSchemeSpecificPart();
            } else {
                String fullData = data.toString();
                if (fullData.startsWith("tel:")) {
                    number = fullData.substring(4);
                } else {
                    number = fullData;
                }
            }
        }

        if (number == null || number.trim().isEmpty()) {
            if (intent.hasExtra(Intent.EXTRA_PHONE_NUMBER)) {
                number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            } else if (intent.hasExtra("phoneNumber")) {
                number = intent.getStringExtra("phoneNumber");
            } else if (intent.hasExtra("number")) {
                number = intent.getStringExtra("number");
            } else if (intent.hasExtra("phone")) {
                number = intent.getStringExtra("phone");
            }
        }

        if (number != null && !number.trim().isEmpty()) {
            number = Uri.decode(number).trim();

            StringBuilder cleanNumber = new StringBuilder();
            for (char c : number.toCharArray()) {
                if (Character.isDigit(c) || c == '+' || c == '*' || c == '#') {
                    cleanNumber.append(c);
                }
            }

            String finalNumber = cleanNumber.length() > 0 ? cleanNumber.toString() : number;

            numberBuilder.setLength(0);
            numberBuilder.append(finalNumber);
            updateNumberDisplay();

            if (panelContacts != null) panelContacts.setVisibility(View.GONE);
            if (panelCallHistory != null) panelCallHistory.setVisibility(View.GONE);
            if (gridDialpad != null) gridDialpad.setVisibility(View.VISIBLE);
        }
    }

    private void updateNumberDisplay() {
        String num = numberBuilder.toString();
        tvNumber.setText(num.isEmpty() ? "" : formatPhoneNumber(num));
        btnBackspace.setVisibility(num.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        scrollQuickActions.setVisibility(num.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String formatPhoneNumber(String raw) {
        if (raw.length() <= 3) return raw;
        if (raw.length() <= 6) return raw.substring(0, 3) + " " + raw.substring(3);
        if (raw.length() <= 10) return raw.substring(0, 3) + " " + raw.substring(3, 6) + " " + raw.substring(6);
        return raw.substring(0, 3) + " " + raw.substring(3, 6) + " " + raw.substring(6, 10) + " " + raw.substring(10);
    }

    private void setupFooterActions() {
        // Voice Call button
        findViewById(R.id.btnCallNormal).setOnClickListener(v -> {
            String num = numberBuilder.toString().trim();
            if (!num.isEmpty()) {
                CallManager.placeCall(this, num);
            } else {
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show();
            }
        });

        // Contacts button in footer
        findViewById(R.id.btnContactsFooter).setOnClickListener(v -> {
            if (panelContacts.getVisibility() == View.VISIBLE) {
                panelContacts.setVisibility(View.GONE);
                gridDialpad.setVisibility(View.VISIBLE);
            } else {
                panelCallHistory.setVisibility(View.GONE);
                gridDialpad.setVisibility(View.GONE);
                panelContacts.setVisibility(View.VISIBLE);
                loadContacts();
            }
        });

        // Audio Data button in footer
        findViewById(R.id.btnCallAudioData).setOnClickListener(v -> {
            String num = numberBuilder.toString().trim();
            if (!num.isEmpty()) {
                Intent intent = new Intent(this, AudioTransferService.class);
                startService(intent);
                CallManager.placeCall(this, num);
            } else {
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show();
            }
        });

        // Call History button in footer
        findViewById(R.id.btnCallHistoryFooter).setOnClickListener(v -> {
            if (panelCallHistory.getVisibility() == View.VISIBLE) {
                panelCallHistory.setVisibility(View.GONE);
                gridDialpad.setVisibility(View.VISIBLE);
            } else {
                panelContacts.setVisibility(View.GONE);
                gridDialpad.setVisibility(View.GONE);
                panelCallHistory.setVisibility(View.VISIBLE);
                loadCallHistory();
            }
        });
    }

    private void setupCallHistoryPanel() {
        findViewById(R.id.btnCloseCallHistory).setOnClickListener(v -> {
            panelCallHistory.setVisibility(View.GONE);
            gridDialpad.setVisibility(View.VISIBLE);
        });

        callHistoryAdapter = new CallsAdapter(filteredCallLogList, item -> {
            numberBuilder.setLength(0);
            numberBuilder.append(item.getNumber());
            updateNumberDisplay();
            panelCallHistory.setVisibility(View.GONE);
            gridDialpad.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Selected: " + item.getNumber(), Toast.LENGTH_SHORT).show();
        });
        rvDialerCallHistory.setAdapter(callHistoryAdapter);

        chipGroupCallFilters.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.chipFilterDialled) {
                currentFilter = "DIALLED";
            } else if (checkedId == R.id.chipFilterReceived) {
                currentFilter = "RECEIVED";
            } else if (checkedId == R.id.chipFilterMissed) {
                currentFilter = "MISSED";
            } else {
                currentFilter = "ALL";
            }
            filterCallHistory();
        });
    }

    private void loadCallHistory() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<CallLogItem> list = new ArrayList<>();
            list.addAll(dbHelper.getAllCalls());

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                ContentResolver resolver = getContentResolver();
                Cursor cursor = null;
                try {
                    cursor = resolver.query(
                            CallLog.Calls.CONTENT_URI,
                            new String[]{
                                    CallLog.Calls._ID,
                                    CallLog.Calls.NUMBER,
                                    CallLog.Calls.TYPE,
                                    CallLog.Calls.DURATION,
                                    CallLog.Calls.DATE
                            },
                            null,
                            null,
                            CallLog.Calls.DATE + " DESC LIMIT 50"
                    );

                    if (cursor != null) {
                        int numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                        int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);
                        int durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);
                        int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                        int idIdx = cursor.getColumnIndex(CallLog.Calls._ID);

                        while (cursor.moveToNext()) {
                            String num = numIdx != -1 ? cursor.getString(numIdx) : "";
                            int typeCode = typeIdx != -1 ? cursor.getInt(typeIdx) : CallLog.Calls.OUTGOING_TYPE;
                            long dur = durIdx != -1 ? cursor.getLong(durIdx) : 0;
                            long date = dateIdx != -1 ? cursor.getLong(dateIdx) : System.currentTimeMillis();
                            long id = idIdx != -1 ? cursor.getLong(idIdx) : 0;

                            String typeStr = "OUTGOING";
                            if (typeCode == CallLog.Calls.INCOMING_TYPE) {
                                typeStr = "INCOMING";
                            } else if (typeCode == CallLog.Calls.MISSED_TYPE) {
                                typeStr = "MISSED";
                            } else if (typeCode == CallLog.Calls.OUTGOING_TYPE) {
                                typeStr = "OUTGOING";
                            }

                            if (num != null && !num.trim().isEmpty()) {
                                list.add(new CallLogItem(id, num, typeStr, dur, date));
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (cursor != null) cursor.close();
                }
            }

            Collections.sort(list, (o1, o2) -> Long.compare(o2.getTimestamp(), o1.getTimestamp()));

            runOnUiThread(() -> {
                masterCallLogList.clear();
                masterCallLogList.addAll(list);
                filterCallHistory();
            });
        });
    }

    private void filterCallHistory() {
        filteredCallLogList.clear();
        for (CallLogItem item : masterCallLogList) {
            String t = item.getType().toUpperCase();
            if ("ALL".equals(currentFilter)) {
                filteredCallLogList.add(item);
            } else if ("DIALLED".equals(currentFilter) && ("OUTGOING".equals(t) || "DIALLED".equals(t))) {
                filteredCallLogList.add(item);
            } else if ("RECEIVED".equals(currentFilter) && ("INCOMING".equals(t) || "RECEIVED".equals(t))) {
                filteredCallLogList.add(item);
            } else if ("MISSED".equals(currentFilter) && "MISSED".equals(t)) {
                filteredCallLogList.add(item);
            }
        }
        callHistoryAdapter.notifyDataSetChanged();
    }

    private void setupContactsPanel() {
        findViewById(R.id.btnCloseContactsPanel).setOnClickListener(v -> {
            panelContacts.setVisibility(View.GONE);
            gridDialpad.setVisibility(View.VISIBLE);
        });

        etDialerSearchContacts = findViewById(R.id.etDialerSearchContacts);
        if (etDialerSearchContacts != null) {
            etDialerSearchContacts.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterContacts(s.toString());
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        contactsAdapter = new ContactsAdapter(filteredContactsList, new ContactsAdapter.OnContactActionListener() {
            @Override
            public void onChatAction(User user) {
                numberBuilder.setLength(0);
                numberBuilder.append(user.getPhone());
                updateNumberDisplay();
                panelContacts.setVisibility(View.GONE);
                gridDialpad.setVisibility(View.VISIBLE);
            }

            @Override
            public void onCallAction(User user) {
                numberBuilder.setLength(0);
                numberBuilder.append(user.getPhone());
                updateNumberDisplay();
                panelContacts.setVisibility(View.GONE);
                gridDialpad.setVisibility(View.VISIBLE);
                CallManager.placeCall(DialerActivity.this, user.getPhone());
            }
        });
        rvDialerContacts.setAdapter(contactsAdapter);
    }

    private void filterContacts(String query) {
        filteredContactsList.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredContactsList.addAll(allContactsList);
        } else {
            String lower = query.toLowerCase().trim();
            for (User u : allContactsList) {
                if ((u.getName() != null && u.getName().toLowerCase().contains(lower)) ||
                    (u.getPhone() != null && u.getPhone().contains(lower))) {
                    filteredContactsList.add(u);
                }
            }
        }
        if (contactsAdapter != null) {
            contactsAdapter.notifyDataSetChanged();
        }
    }

    private void loadContacts() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<User> list = new ArrayList<>();
            list.addAll(dbHelper.getAllUsers());

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                ContentResolver resolver = getContentResolver();
                Cursor cursor = null;
                try {
                    cursor = resolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            new String[]{
                                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                            },
                            null,
                            null,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                    );

                    if (cursor != null) {
                        int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                        int phoneIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                        int idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);

                        while (cursor.moveToNext()) {
                            String name = nameIdx != -1 ? cursor.getString(nameIdx) : "Unknown";
                            String phone = phoneIdx != -1 ? cursor.getString(phoneIdx) : "";
                            long id = 0;
                            try {
                                if (idIdx != -1) id = Long.parseLong(cursor.getString(idIdx));
                            } catch (Exception ignored) {}

                            if (phone != null && !phone.trim().isEmpty()) {
                                list.add(new User(id, name, phone, ""));
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (cursor != null) cursor.close();
                }
            }

            runOnUiThread(() -> {
                allContactsList.clear();
                allContactsList.addAll(list);
                filterContacts(etDialerSearchContacts != null ? etDialerSearchContacts.getText().toString() : "");
            });
        });
    }

    private void setupQuickActions() {
        findViewById(R.id.btnCreateContact).setOnClickListener(v -> {
            String num = numberBuilder.toString().trim();
            Intent intent = new Intent(Intent.ACTION_INSERT);
            intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, num);
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Save Contact: " + num, Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnSendMessage).setOnClickListener(v -> {
            String num = numberBuilder.toString().trim();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("target_recipient", num);
            startActivity(intent);
        });

        findViewById(R.id.btnAudioDataCallQuick).setOnClickListener(v -> {
            String num = numberBuilder.toString().trim();
            if (!num.isEmpty()) {
                Intent intent = new Intent(this, AudioTransferService.class);
                startService(intent);
                CallManager.placeCall(this, num);
            }
        });
    }

    private void setupKeypad() {
        int[] keyIds = {
                R.id.btnKey1, R.id.btnKey2, R.id.btnKey3,
                R.id.btnKey4, R.id.btnKey5, R.id.btnKey6,
                R.id.btnKey7, R.id.btnKey8, R.id.btnKey9,
                R.id.btnKeyStar, R.id.btnKey0, R.id.btnKeyHash
        };

        String[] keyValues = {
                "1", "2", "3",
                "4", "5", "6",
                "7", "8", "9",
                "*", "0", "#"
        };

        int[] toneTypes = {
                ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_2, ToneGenerator.TONE_DTMF_3,
                ToneGenerator.TONE_DTMF_4, ToneGenerator.TONE_DTMF_5, ToneGenerator.TONE_DTMF_6,
                ToneGenerator.TONE_DTMF_7, ToneGenerator.TONE_DTMF_8, ToneGenerator.TONE_DTMF_9,
                ToneGenerator.TONE_DTMF_S, ToneGenerator.TONE_DTMF_0, ToneGenerator.TONE_DTMF_P
        };

        for (int i = 0; i < keyIds.length; i++) {
            final String val = keyValues[i];
            final int tone = toneTypes[i];
            View keyView = findViewById(keyIds[i]);
            keyView.setOnClickListener(v -> {
                playTone(tone);
                numberBuilder.append(val);
                updateNumberDisplay();
            });
            if ("0".equals(val)) {
                keyView.setOnLongClickListener(v -> {
                    playTone(tone);
                    numberBuilder.append("+");
                    updateNumberDisplay();
                    return true;
                });
            }
        }
    }

    private void playTone(int tone) {
        if (toneGenerator != null) {
            try {
                toneGenerator.startTone(tone, 120);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        super.onDestroy();
    }
}