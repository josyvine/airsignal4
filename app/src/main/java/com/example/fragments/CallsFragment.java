package com.example.fragments;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.activities.ConversationActivity;
import com.example.activities.DialerActivity;
import com.example.adapters.CallsAdapter;
import com.example.call.CallManager;
import com.example.database.DatabaseHelper;
import com.example.models.CallLogItem;
import com.example.models.User;
import com.example.services.AudioTransferService;
import com.example.utils.AirLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class CallsFragment extends Fragment {

    private RecyclerView rvCalls;
    private CallsAdapter adapter;
    private DatabaseHelper dbHelper;
    private List<CallLogItem> callLogList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_calls, container, false);

        rvCalls = view.findViewById(R.id.rvCalls);
        dbHelper = DatabaseHelper.getInstance(requireContext());

        rvCalls.setLayoutManager(new LinearLayoutManager(requireContext()));

        view.findViewById(R.id.btnOpenDialer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(requireContext(), DialerActivity.class);
                startActivity(intent);
            }
        });

        view.findViewById(R.id.btnStartAudioDataCall).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                requireContext().startService(serviceIntent);
            }
        });

        loadCalls();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadCalls();
    }

    private void loadCalls() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<CallLogItem> rawCalls = dbHelper.getAllCalls();
                if (rawCalls == null) rawCalls = new ArrayList<>();

                // Resolve contact names for each call entry
                List<User> dbUsers = dbHelper.getAllUsers();

                for (CallLogItem call : rawCalls) {
                    if (call == null || call.getNumber() == null) continue;
                    String resolvedName = resolveContactName(call.getNumber(), dbUsers);
                    if (resolvedName != null && !resolvedName.trim().isEmpty() && !resolvedName.equals(call.getNumber())) {
                        call.setName(resolvedName);
                    }
                }

                final List<CallLogItem> resolvedCalls = rawCalls;

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        callLogList = resolvedCalls;

                        if (adapter == null) {
                            adapter = new CallsAdapter(
                                    callLogList,
                                    item -> {
                                        if (item != null && item.getNumber() != null) {
                                            CallManager.placeCall(requireContext(), item.getNumber());
                                        }
                                    },
                                    item -> {
                                        showCallItemOptionsDialog(item);
                                    }
                            );
                            rvCalls.setAdapter(adapter);
                        } else {
                            adapter.updateList(callLogList);
                        }
                    });
                }
            } catch (Exception e) {
                AirLogger.e("CallsFragment", "Error loading call logs", e);
            }
        });
    }

    private void showCallItemOptionsDialog(final CallLogItem item) {
        if (item == null || getContext() == null) return;

        final String number = item.getNumber();
        final boolean isSaved = item.hasContactName();
        final String displayName = isSaved ? item.getName() : number;

        List<String> optionsList = new ArrayList<>();

        if (!isSaved) {
            optionsList.add("Create new contact");
        }

        optionsList.add("Send message");
        optionsList.add("Copy number");
        optionsList.add("Edit before call");
        optionsList.add("Delete entry");

        final CharSequence[] options = optionsList.toArray(new CharSequence[0]);

        new AlertDialog.Builder(requireContext())
                .setTitle(isSaved ? displayName + "\n" + number : number)
                .setItems(options, (dialog, which) -> {
                    String selectedOption = options[which].toString();

                    if ("Create new contact".equals(selectedOption)) {
                        createNewContact(number);
                    } else if ("Send message".equals(selectedOption)) {
                        openConversation(number);
                    } else if ("Copy number".equals(selectedOption)) {
                        copyNumberToClipboard(number);
                    } else if ("Edit before call".equals(selectedOption)) {
                        editBeforeCall(number);
                    } else if ("Delete entry".equals(selectedOption)) {
                        deleteCallEntry(item);
                    }
                })
                .show();
    }

    private void createNewContact(String number) {
        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
        intent.putExtra(ContactsContract.Intents.Insert.PHONE, number);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Unable to open contact creator", Toast.LENGTH_SHORT).show();
        }
    }

    private void openConversation(String number) {
        Intent intent = new Intent(requireContext(), ConversationActivity.class);
        intent.putExtra("target_recipient", number);
        startActivity(intent);
    }

    private void copyNumberToClipboard(String number) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Phone Number", number);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "Number copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void editBeforeCall(String number) {
        Intent intent = new Intent(requireContext(), DialerActivity.class);
        intent.setData(Uri.parse("tel:" + number));
        intent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);
        startActivity(intent);
    }

    private void deleteCallEntry(final CallLogItem item) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                dbHelper.deleteCall(item.getId());
                AirLogger.i("CallsFragment", "Deleted call log entry ID=" + item.getId());

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(requireContext(), "Call entry deleted", Toast.LENGTH_SHORT).show();
                        loadCalls();
                    });
                }
            } catch (Exception e) {
                AirLogger.e("CallsFragment", "Failed to delete call log entry", e);
            }
        });
    }

    private String resolveContactName(String phoneNumber, List<User> dbUsers) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return phoneNumber;

        // 1. Check local DB users
        if (dbUsers != null) {
            for (User u : dbUsers) {
                if (u != null && u.getPhone() != null && cleanNumber(u.getPhone()).equals(cleanNumber(phoneNumber))) {
                    return u.getName();
                }
            }
        }

        // 2. Check Android system Contacts provider
        if (getContext() != null && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                ContentResolver resolver = requireContext().getContentResolver();
                Cursor cursor = resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                        ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?",
                        new String[]{"%" + cleanNumber(phoneNumber) + "%"},
                        null
                );
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    if (nameIdx != -1) {
                        String name = cursor.getString(nameIdx);
                        cursor.close();
                        if (name != null && !name.trim().isEmpty()) {
                            return name;
                        }
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {
            }
        }

        return phoneNumber;
    }

    private String cleanNumber(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[^0-9]", "");
        if (cleaned.length() > 10) {
            cleaned = cleaned.substring(cleaned.length() - 10);
        }
        return cleaned;
    }
}