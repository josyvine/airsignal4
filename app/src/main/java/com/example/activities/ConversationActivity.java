package com.example.activities;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.adapters.MessageAdapter;
import com.example.database.AppDatabase;
import com.example.database.DatabaseHelper;
import com.example.database.MessageEntity;
import com.example.models.Message;
import com.example.receivers.SmsStatusReceiver;
import com.example.services.SmsService;
import com.example.utils.AirLogger;
import com.example.utils.SimManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ConversationActivity extends AppCompatActivity {

    private static final String TAG = "ConversationActivity";

    private String recipientAddress = "";
    private RecyclerView recyclerView;
    private MessageAdapter messageAdapter;
    private EditText etInput;
    private Spinner spinnerSimSelector;
    private ImageButton btnSend;
    private TextView tvRecipientHeader;

    private List<Message> messageList = new ArrayList<>();
    private List<SimManager.SimInfo> availableSims = new ArrayList<>();

    private final BroadcastReceiver smsBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            AirLogger.i(TAG, "Live SMS broadcast received in ConversationActivity");
            loadMessages();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Extract target recipient from Intent
        if (getIntent() != null) {
            if (getIntent().hasExtra("target_recipient")) {
                recipientAddress = getIntent().getStringExtra("target_recipient");
            } else if (getIntent().getData() != null) {
                recipientAddress = getIntent().getData().getSchemeSpecificPart();
            }
        }

        if (TextUtils.isEmpty(recipientAddress)) {
            recipientAddress = "Unknown";
        }

        // Build Modern Chat Window UI Programmatically (Guarantees zero XML missing resource errors)
        setupUI();

        // Initialize Chat Adapter with Click & Long-Click Action Listeners
        messageAdapter = new MessageAdapter(messageList, new MessageAdapter.OnMessageActionListener() {
            @Override
            public void onMessageClick(Message message, int position) {
                showMessageOptionsDialog(message);
            }

            @Override
            public void onMessageLongClick(Message message, int position) {
                showMessageOptionsDialog(message);
            }
        });
        recyclerView.setAdapter(messageAdapter);

        // Setup SIM Selector
        setupSimSelector();

        // Load conversation message history
        loadMessages();
    }

    private void setupUI() {
        // Main Root Layout
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#0F172A")); // Modern Dark Theme
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. HEADER TOOLBAR
        RelativeLayout headerBar = new RelativeLayout(this);
        headerBar.setBackgroundColor(Color.parseColor("#1E293B"));
        headerBar.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));

        ImageButton btnBack = new ImageButton(this);
        btnBack.setId(View.generateViewId());
        btnBack.setImageResource(android.R.drawable.ic_menu_revert);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setColorFilter(Color.WHITE);
        btnBack.setOnClickListener(v -> finish());

        tvRecipientHeader = new TextView(this);
        tvRecipientHeader.setText(recipientAddress);
        tvRecipientHeader.setTextColor(Color.WHITE);
        tvRecipientHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvRecipientHeader.setTypeface(null, Typeface.BOLD);

        RelativeLayout.LayoutParams backParams = new RelativeLayout.LayoutParams(dpToPx(36), dpToPx(36));
        backParams.addRule(RelativeLayout.ALIGN_PARENT_START);
        backParams.addRule(RelativeLayout.CENTER_VERTICAL);

        RelativeLayout.LayoutParams titleParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.addRule(RelativeLayout.END_OF, btnBack.getId());
        titleParams.addRule(RelativeLayout.CENTER_VERTICAL);
        titleParams.setMarginStart(dpToPx(12));

        headerBar.addView(btnBack, backParams);
        headerBar.addView(tvRecipientHeader, titleParams);

        // 2. CHAT BUBBLES RECYCLERVIEW
        recyclerView = new RecyclerView(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Auto-scroll to bottom
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        recyclerView.setClipToPadding(false);

        LinearLayout.LayoutParams recyclerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        );

        // 3. FOOTER INPUT BAR WITH COMPACT SIM SELECTOR ICON
        LinearLayout footerLayout = new LinearLayout(this);
        footerLayout.setOrientation(LinearLayout.HORIZONTAL);
        footerLayout.setGravity(Gravity.CENTER_VERTICAL);
        footerLayout.setBackgroundColor(Color.parseColor("#1E293B"));
        footerLayout.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

        // Input Box container
        LinearLayout inputContainer = new LinearLayout(this);
        inputContainer.setOrientation(LinearLayout.HORIZONTAL);
        inputContainer.setGravity(Gravity.CENTER_VERTICAL);
        inputContainer.setBackgroundColor(Color.parseColor("#334155"));
        inputContainer.setPadding(dpToPx(10), dpToPx(4), dpToPx(6), dpToPx(4));

        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
        );

        // Text Input Field (Takes 85%+ width)
        etInput = new EditText(this);
        etInput.setHint("Type a message...");
        etInput.setHintTextColor(Color.parseColor("#94A3B8"));
        etInput.setTextColor(Color.WHITE);
        etInput.setBackgroundColor(Color.TRANSPARENT);
        etInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        etInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etInput.setMaxLines(4);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
        );

        // Tiny Compact SIM Selector Icon Dropdown (Placed on the extreme right inside text box)
        spinnerSimSelector = new Spinner(this);
        spinnerSimSelector.setBackgroundColor(Color.parseColor("#0284C7"));
        spinnerSimSelector.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));

        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        spinnerParams.setMarginStart(dpToPx(4));

        inputContainer.addView(etInput, inputParams);
        inputContainer.addView(spinnerSimSelector, spinnerParams);

        // Send Button with send_24px Vector Icon
        btnSend = new ImageButton(this);
        int sendDrawableId = getResources().getIdentifier("send_24px", "drawable", getPackageName());
        if (sendDrawableId != 0) {
            btnSend.setImageResource(sendDrawableId);
        } else {
            btnSend.setImageResource(android.R.drawable.ic_menu_send);
        }
        btnSend.setColorFilter(Color.WHITE);
        btnSend.setBackgroundColor(Color.parseColor("#0284C7"));
        btnSend.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));
        btnSend.setOnClickListener(v -> sendMessage());

        LinearLayout.LayoutParams sendBtnParams = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44));
        sendBtnParams.setMarginStart(dpToPx(8));

        footerLayout.addView(inputContainer, containerParams);
        footerLayout.addView(btnSend, sendBtnParams);

        // Add elements to Root
        rootLayout.addView(headerBar);
        rootLayout.addView(recyclerView, recyclerParams);
        rootLayout.addView(footerLayout);

        setContentView(rootLayout);
    }

    private void setupSimSelector() {
        availableSims = SimManager.getActiveSims(this);

        ArrayAdapter<SimManager.SimInfo> simAdapter = new ArrayAdapter<SimManager.SimInfo>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                availableSims
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                SimManager.SimInfo info = getItem(position);

                // COLLAPSED VIEW (Tiny SIM Icon + Slot # inside text bar)
                if (info != null) {
                    view.setText(String.valueOf(info.getSimSlotIndex() + 1));
                } else {
                    view.setText("1");
                }
                view.setTextColor(Color.WHITE);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                view.setTypeface(null, Typeface.BOLD);
                view.setGravity(Gravity.CENTER);

                try {
                    int simDrawableId = getResources().getIdentifier("sim_card_24px", "drawable", getPackageName());
                    if (simDrawableId != 0) {
                        view.setCompoundDrawablesWithIntrinsicBounds(simDrawableId, 0, 0, 0);
                        view.setCompoundDrawablePadding(dpToPx(2));
                    }
                } catch (Exception ignored) {
                }
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                SimManager.SimInfo info = getItem(position);

                // EXPANDED DROPDOWN VIEW (Full Network/Carrier Name)
                if (info != null) {
                    view.setText(info.getDisplayName());
                }
                view.setTextColor(Color.BLACK);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                view.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

                try {
                    int simDrawableId = getResources().getIdentifier("sim_card_24px", "drawable", getPackageName());
                    if (simDrawableId != 0) {
                        view.setCompoundDrawablesWithIntrinsicBounds(simDrawableId, 0, 0, 0);
                        view.setCompoundDrawablePadding(dpToPx(8));
                    }
                } catch (Exception ignored) {
                }
                return view;
            }
        };

        spinnerSimSelector.setAdapter(simAdapter);
    }

    private void showMessageOptionsDialog(final Message message) {
        if (message == null) return;

        List<String> options = new ArrayList<>();
        options.add("Copy Text");

        boolean isFailed = "FAILED".equalsIgnoreCase(message.getStatus()) || "ERROR".equalsIgnoreCase(message.getStatus());
        boolean isOutgoing = "me".equalsIgnoreCase(message.getSender()) || "OUTGOING".equalsIgnoreCase(message.getType());

        if (isFailed || isOutgoing) {
            options.add("Resend Message");
        }

        options.add("Delete Message");
        options.add("Message Details");

        final CharSequence[] items = options.toArray(new CharSequence[0]);

        new AlertDialog.Builder(this)
                .setTitle("Message Options")
                .setItems(items, (dialog, which) -> {
                    String selected = items[which].toString();
                    if ("Copy Text".equals(selected)) {
                        copyMessageText(message.getMessage());
                    } else if ("Resend Message".equals(selected)) {
                        retrySendMessage(message);
                    } else if ("Delete Message".equals(selected)) {
                        confirmDeleteMessage(message);
                    } else if ("Message Details".equals(selected)) {
                        showMessageDetails(message);
                    }
                })
                .show();
    }

    private void copyMessageText(String text) {
        if (text == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Message Text", text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteMessage(final Message message) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Message")
                .setMessage("Delete this message?")
                .setPositiveButton("Delete", (dialog, which) -> deleteIndividualMessage(message))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteIndividualMessage(final Message message) {
        new Thread(() -> {
            try {
                // 1. Delete from SQLite DatabaseHelper
                DatabaseHelper.getInstance(ConversationActivity.this).deleteMessage(message.getId());

                // 2. Delete from Room AppDatabase using deleteMessageById
                try {
                    AppDatabase db = AppDatabase.getInstance(ConversationActivity.this);
                    if (db != null && db.messageDao() != null) {
                        db.messageDao().deleteMessageById(message.getId());
                    }
                } catch (Exception ignored) {
                }

                AirLogger.i(TAG, "Deleted individual message ID=" + message.getId());

                runOnUiThread(() -> {
                    Toast.makeText(ConversationActivity.this, "Message deleted", Toast.LENGTH_SHORT).show();
                    loadMessages();
                });
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed deleting message ID=" + message.getId(), e);
            }
        }).start();
    }

    private void retrySendMessage(final Message message) {
        if (message == null || TextUtils.isEmpty(message.getMessage())) return;

        SimManager.SimInfo selectedSim = (SimManager.SimInfo) spinnerSimSelector.getSelectedItem();
        int subId = (selectedSim != null) ? selectedSim.getSubId() : -1;

        // Update status to SENDING in SQLite
        DatabaseHelper.getInstance(this).updateMessageStatus(message.getId(), "SENDING");

        // Update status in Room DB using updateStatus
        new Thread(() -> {
            try {
                AppDatabase.getInstance(ConversationActivity.this).messageDao().updateStatus(message.getId(), "SENDING");
            } catch (Exception ignored) {
            }
        }).start();

        loadMessages();

        // Re-dispatch SMS via SmsService
        SmsService.sendSms(this, recipientAddress, message.getMessage(), subId, message.getId());
        Toast.makeText(this, "Resending message...", Toast.LENGTH_SHORT).show();
    }

    private void showMessageDetails(final Message message) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy hh:mm:ss a", Locale.getDefault());
        String dateStr = sdf.format(new Date(message.getTimestamp()));

        String details = "Type: " + message.getType() + "\n" +
                "Status: " + message.getStatus() + "\n" +
                "Sender: " + message.getSender() + "\n" +
                "Recipient: " + message.getReceiver() + "\n" +
                "Time: " + dateStr;

        new AlertDialog.Builder(this)
                .setTitle("Message Details")
                .setMessage(details)
                .setPositiveButton("OK", null)
                .show();
    }

    private void loadMessages() {
        new Thread(() -> {
            try {
                List<Message> filteredMessages = new ArrayList<>();

                // 1. Fetch from DatabaseHelper
                List<Message> helperMsgs = DatabaseHelper.getInstance(ConversationActivity.this).getAllMessages();
                if (helperMsgs != null) {
                    for (Message m : helperMsgs) {
                        if (m != null && m.getSender() != null && m.getReceiver() != null) {
                            String cleanRecipient = cleanNumber(recipientAddress);
                            String cleanSender = cleanNumber(m.getSender());
                            String cleanReceiver = cleanNumber(m.getReceiver());

                            if (cleanSender.equals(cleanRecipient) || cleanReceiver.equals(cleanRecipient) ||
                                    recipientAddress.equalsIgnoreCase(m.getSender()) ||
                                    recipientAddress.equalsIgnoreCase(m.getReceiver())) {
                                filteredMessages.add(m);
                            }
                        }
                    }
                }

                // 2. Fetch from Room AppDatabase
                try {
                    AppDatabase db = AppDatabase.getInstance(ConversationActivity.this);
                    List<MessageEntity> entities = db.messageDao().getMessagesForConversation(recipientAddress);
                    if (entities != null) {
                        for (MessageEntity entity : entities) {
                            boolean exists = false;
                            for (Message existing : filteredMessages) {
                                if (existing.getTimestamp() == entity.timestamp && existing.getMessage().equals(entity.body)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                filteredMessages.add(new Message(
                                        entity.id,
                                        entity.sender,
                                        entity.recipient,
                                        entity.body,
                                        entity.timestamp,
                                        entity.type,
                                        entity.status
                                ));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                // Sort chronological
                Collections.sort(filteredMessages, (m1, m2) -> Long.compare(m1.getTimestamp(), m2.getTimestamp()));

                runOnUiThread(() -> {
                    this.messageList = filteredMessages;
                    messageAdapter.updateMessages(messageList);

                    if (messageList.size() > 0) {
                        recyclerView.scrollToPosition(messageList.size() - 1);
                    }
                });
            } catch (Exception e) {
                AirLogger.e(TAG, "Failed loading messages for conversation: " + recipientAddress, e);
            }
        }).start();
    }

    private String cleanNumber(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[^0-9]", "");
        if (cleaned.length() > 10) {
            cleaned = cleaned.substring(cleaned.length() - 10);
        }
        return cleaned;
    }

    private void sendMessage() {
        String textBody = etInput.getText().toString().trim();
        if (TextUtils.isEmpty(textBody)) {
            return;
        }

        // Get selected SIM subscription ID
        SimManager.SimInfo selectedSim = (SimManager.SimInfo) spinnerSimSelector.getSelectedItem();
        int subId = (selectedSim != null) ? selectedSim.getSubId() : -1;

        AirLogger.i(TAG, "Sending message to " + recipientAddress + " via SIM subId=" + subId);

        // Insert message locally into SQLite DatabaseHelper
        Message outgoingMsg = new Message(0, "me", recipientAddress, textBody, System.currentTimeMillis(), "SMS", "PENDING");
        long messageId = DatabaseHelper.getInstance(this).insertMessage(outgoingMsg);

        // Also insert message locally into Room AppDatabase
        new Thread(() -> {
            try {
                AppDatabase.getInstance(ConversationActivity.this).messageDao().insertMessage(
                        new MessageEntity(messageId, "me", recipientAddress, textBody, System.currentTimeMillis(), "SMS", "PENDING", subId, true)
                );
            } catch (Exception e) {
                AirLogger.e(TAG, "Error caching message to Room DB", e);
            }
        }).start();

        // Clear input box
        etInput.setText("");

        // Refresh conversation UI
        loadMessages();

        // Dispatch SMS via SmsService with selected SIM slot
        SmsService.sendSms(this, recipientAddress, textBody, subId, messageId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.ACTION_SMS_RECEIVED");
        filter.addAction(SmsStatusReceiver.ACTION_MESSAGE_STATUS_UPDATED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(smsBroadcastReceiver, filter);
        }

        loadMessages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(smsBroadcastReceiver);
        } catch (Exception ignored) {
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
}