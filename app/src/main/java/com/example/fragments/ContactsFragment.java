package com.example.fragments;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.MainActivity;
import com.example.R;
import com.example.adapters.ContactsAdapter;
import com.example.call.CallManager;
import com.example.database.DatabaseHelper;
import com.example.models.User;
import com.example.utils.AirLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class ContactsFragment extends Fragment {

    private static final int PERMISSION_REQUEST_CONTACTS = 101;

    private RecyclerView rvContacts;
    private EditText etSearchContacts;
    private ImageView btnClearSearch;
    private LinearLayout bannerPermission;
    private TextView tvContactsCount;

    private ContactsAdapter adapter;
    private DatabaseHelper dbHelper;
    private List<User> allContactsList = new ArrayList<>();
    private List<User> filteredContactsList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_contacts, container, false);

        rvContacts = view.findViewById(R.id.rvContacts);
        etSearchContacts = view.findViewById(R.id.etSearchContacts);
        btnClearSearch = view.findViewById(R.id.btnClearSearch);
        bannerPermission = view.findViewById(R.id.bannerPermission);
        tvContactsCount = view.findViewById(R.id.tvContactsCount);

        dbHelper = DatabaseHelper.getInstance(requireContext());

        rvContacts.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new ContactsAdapter(filteredContactsList, new ContactsAdapter.OnContactActionListener() {
            @Override
            public void onChatAction(User user) {
                if (getContext() != null) {
                    Intent intent = new Intent(requireContext(), MainActivity.class);
                    intent.putExtra("target_recipient", user.getPhone());
                    startActivity(intent);
                }
            }

            @Override
            public void onCallAction(User user) {
                if (getContext() != null) {
                    CallManager.placeCall(requireContext(), user.getPhone());
                }
            }
        });
        rvContacts.setAdapter(adapter);

        view.findViewById(R.id.btnGrantPermission).setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, PERMISSION_REQUEST_CONTACTS));

        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> etSearchContacts.setText(""));
        }

        etSearchContacts.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (btnClearSearch != null) {
                    btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
                filterContacts(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        loadContacts();

        return view;
    }

    private void loadContacts() {
        if (getContext() != null && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            bannerPermission.setVisibility(View.GONE);
            fetchDeviceContactsAsync();
        } else {
            bannerPermission.setVisibility(View.VISIBLE);
            loadDatabaseSeedContacts();
        }
    }

    private void fetchDeviceContactsAsync() {
        if (getContext() == null) return;

        // Safely capture Application Context to prevent requireContext() IllegalStateException on background threads
        final Context appContext = requireContext().getApplicationContext();

        tvContactsCount.setText("Reading device contacts...");
        Executors.newSingleThreadExecutor().execute(() -> {
            List<User> deviceContacts = new ArrayList<>();
            ContentResolver resolver = appContext.getContentResolver();
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
                        String idStr = idIdx != -1 ? cursor.getString(idIdx) : "0";

                        long contactId = 0;
                        try {
                            contactId = Long.parseLong(idStr);
                        } catch (Exception ignored) {}

                        if (phone != null && !phone.trim().isEmpty()) {
                            deviceContacts.add(new User(contactId, name, phone, ""));
                        }
                    }
                }
            } catch (Exception e) {
                AirLogger.e("ContactsFragment", "Error fetching device contacts", e);
            } finally {
                if (cursor != null) cursor.close();
            }

            List<User> dbUsers = DatabaseHelper.getInstance(appContext).getAllUsers();
            if (dbUsers != null) {
                for (User u : dbUsers) {
                    if (u == null || u.getPhone() == null) continue;
                    boolean exists = false;
                    for (User dc : deviceContacts) {
                        if (dc != null && dc.getPhone() != null &&
                                dc.getPhone().replaceAll("[^0-9]", "").equals(u.getPhone().replaceAll("[^0-9]", ""))) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        deviceContacts.add(u);
                    }
                }
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    allContactsList.clear();
                    allContactsList.addAll(deviceContacts);
                    filterContacts(etSearchContacts.getText().toString());
                    tvContactsCount.setText("Found " + allContactsList.size() + " contacts");
                });
            }
        });
    }

    private void loadDatabaseSeedContacts() {
        allContactsList.clear();
        List<User> users = dbHelper.getAllUsers();
        if (users != null) {
            allContactsList.addAll(users);
        }
        filterContacts(etSearchContacts.getText().toString());
        tvContactsCount.setText("Showing " + allContactsList.size() + " sample peers (Grant permission for device contacts)");
    }

    private void filterContacts(String query) {
        filteredContactsList.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredContactsList.addAll(allContactsList);
        } else {
            String lower = query.toLowerCase().trim();
            for (User u : allContactsList) {
                if (u != null && u.getName() != null && u.getPhone() != null) {
                    if (u.getName().toLowerCase().contains(lower) || u.getPhone().contains(lower)) {
                        filteredContactsList.add(u);
                    }
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadContacts();
            } else {
                if (getContext() != null) {
                    Toast.makeText(requireContext(), "Permission denied. Showing default contacts.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}